package io.github.brickystudios.funkblick.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.brickystudios.funkblick.MainActivity
import io.github.brickystudios.funkblick.R
import io.github.brickystudios.funkblick.core.tr

/**
 * The phone connected to a car over Bluetooth: offer car mode with one tap. A notification, not an automatic start,
 * because Android doesn't let a background app start location scanning on its own (and you may not want it every trip).
 */
class CarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        Funk.init(context)
        if (!Funk.settings.value.carStart || Funk.running.value) return
        val device: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
        // reading the class and name needs BLUETOOTH_CONNECT on Android 12+, asked for when the setting is switched on
        val (isCar, name) = runCatching {
            val c = device.bluetoothClass?.deviceClass
            (c == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || c == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE) to (device.name ?: "")
        }.getOrDefault(false to "")
        if (!isCar || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Auto erkannt", "Car connected"), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_CAR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(tr("Mit ${name.ifEmpty { "dem Auto" }} verbunden", "Connected to ${name.ifEmpty { "your car" }}"))
            .setContentText(tr("Tippen startet den Auto-Modus.", "Tap to start car mode."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        @Suppress("MissingPermission") NotificationManagerCompat.from(context).notify(3, n)
    }

    companion object { private const val CHANNEL = "car" }
}
