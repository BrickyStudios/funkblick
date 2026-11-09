package io.github.brickystudios.funkblick.scan

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.brickystudios.funkblick.MainActivity
import io.github.brickystudios.funkblick.R
import io.github.brickystudios.funkblick.core.APPLE
import io.github.brickystudios.funkblick.core.FRESH_MS
import io.github.brickystudios.funkblick.core.Alert
import io.github.brickystudios.funkblick.core.Radio
import io.github.brickystudios.funkblick.core.RuleField
import io.github.brickystudios.funkblick.core.RuleKind
import io.github.brickystudios.funkblick.core.Sighting
import io.github.brickystudios.funkblick.core.TRACKER_SERVICE_UUIDS
import io.github.brickystudios.funkblick.core.hexOf
import io.github.brickystudios.funkblick.core.kind
import io.github.brickystudios.funkblick.core.label
import io.github.brickystudios.funkblick.core.securityOf
import io.github.brickystudios.funkblick.core.tr
import java.util.UUID

/**
 * Scans Wi-Fi and Bluetooth in the foreground (with a notification) so it keeps going in a pocket or a car mount.
 * Only reads what devices broadcast to everyone: no connecting, no pairing, no traffic.
 */
class ScanService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var alerter: Alerter
    private var wakeLock: PowerManager.WakeLock? = null
    private var dirty = false
    private var screenOn = true
    private val warnedCams = HashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Funk.init(this)
        alerter = Alerter(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SCAN, tr("Scan läuft", "Scanning"), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Funk.running.value) return START_STICKY
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: Exception) { // Android 14 refuses a location service without the permission
            stopSelf()
            return START_NOT_STICKY
        }
        Funk.newSession() // every scan is its own session and its own log
        Funk.demo.value = false
        Funk.running.value = true
        Funk.diag.value = Diag(serviceSince = System.currentTimeMillis())
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "funkblick:scan")
            .apply { acquire(12 * 60 * 60_000L) }
        startWifi()
        if (Funk.settings.value.ble) startBle()
        startLocation()
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        registerReceiver(radios, IntentFilter().apply { addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(WifiManager.WIFI_STATE_CHANGED_ACTION) })
        main.post(tick)
        main.postDelayed({ alerter.play("start", voice = true) }, 400) // SoundPool needs a moment to load
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(wifiReceiver) }
        runCatching { unregisterReceiver(screen) }
        runCatching { unregisterReceiver(radios) }
        radioBack(bt = true); radioBack(bt = false)
        stopBle()
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(location) }
        if (Funk.running.value) {
            Funk.saveLog()
            alerter.play("stop", voice = false)
        }
        Funk.running.value = false
        Funk.camAhead.value = null // the last fix stays: the session recap centres its map on it
        wakeLock?.let { if (it.isHeld) it.release() }
        main.postDelayed({ alerter.release() }, 1500) // let the stop sound finish
        super.onDestroy()
    }

    /** Swiped away from the recent apps: closing the app ends the scan and saves the log. */
    override fun onTaskRemoved(rootIntent: Intent?) = stopSelf()

    // ------------------------------------------------------------- loop

    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            // Android allows 4 Wi-Fi scans per 2 minutes and simply refuses the rest (counted as throttled).
            // Asking every 10 s costs nothing and triples the rate in a car once "Wi-Fi scan throttling" is off.
            if (ticks % 10 == 0) requestWifiScan()
            if (dirty) { dirty = false; Funk.publish() }
            if (ticks % 10 == 0) updateNotification()
            if (ticks % 60 == 59) Funk.saveLog()
            // Android quietly downgrades a long BLE scan to "opportunistic" (only other apps' results, no error):
            // both drives on 09.11. went deaf exactly 10 min after the start. A fresh scan every 4 min stays below that.
            if (ticks % 240 == 239 && bleFiltered != null) { stopBle(); startBle() }
            ticks++
            main.postDelayed(this, 1000)
        }
    }

    private fun handle(s: Sighting) {
        dirty = true
        Funk.ingest(s)?.let(::ring)
    }

    private fun ring(a: Alert) {
        Funk.logAlert(a)
        alerter.play(a.reason.kind(), voice = true)
        val d = a.device
        val name = Funk.screenMasker().name(d) ?: tr("ohne Namen", "no name")
        alerter.notify(d.id.hashCode(), a.reason.label(), "$name, ${d.rssi} dBm", if (a.reason == io.github.brickystudios.funkblick.core.AlertReason.TRACKER_FOLLOWING) d.id else null)
    }

    // ------------------------------------------------------------- Wi-Fi

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = readWifi()
    }

    private fun startWifi() {
        ContextCompat.registerReceiver(this, wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
        readWifi() // whatever the system found recently, other apps' scans included
    }

    private fun requestWifiScan() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        @Suppress("DEPRECATION") val ok = wifi.startScan()
        Funk.diag.value = Funk.diag.value.let { it.copy(wifiRequested = it.wifiRequested + 1, wifiThrottled = it.wifiThrottled + if (ok) 0 else 1) }
    }

    @SuppressLint("MissingPermission") // only called after ACCESS_FINE_LOCATION was granted
    private fun readWifi() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val results = runCatching { wifi.scanResults }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val bootNow = SystemClock.elapsedRealtime()
        for (r in results) {
            val bssid = r.BSSID ?: continue
            @Suppress("DEPRECATION") val ssid = r.SSID?.takeIf { it.isNotEmpty() }
            val seen = now - (bootNow - r.timestamp / 1000) // timestamp: microseconds since boot
            // Android's cache still holds the last scan's results, maybe from the previous drive: they'd land here on the map
            if (seen < Funk.diag.value.serviceSince - FRESH_MS) continue
            handle(Sighting(Radio.WIFI, bssid.uppercase(), ssid, r.level, seen, r.frequency, securityOf(r.capabilities ?: "")))
        }
        Funk.diag.value = Funk.diag.value.copy(wifiResults = results.size, lastWifi = now)
    }

    // ------------------------------------------------------------- Bluetooth

    private var bleFiltered: Boolean? = null

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) = handleBle(r)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleBle)
        override fun onScanFailed(errorCode: Int) {
            Funk.diag.value = Funk.diag.value.copy(bleRunning = false, bleError = errorCode)
        }
    }

    private fun handleBle(r: ScanResult) {
        val rec = r.scanRecord
        val mfg = HashMap<Int, ByteArray>()
        rec?.manufacturerSpecificData?.let { sa -> for (i in 0 until sa.size()) mfg[sa.keyAt(i)] = sa.valueAt(i) }
        val sd = HashMap<Int, ByteArray>()
        rec?.serviceData?.forEach { (uuid, data) -> short(uuid)?.let { sd[it] = data } }
        val uuids = rec?.serviceUuids?.mapNotNull(::short)?.toSet() ?: emptySet()
        val now = System.currentTimeMillis()
        val seen = now - (SystemClock.elapsedRealtimeNanos() - r.timestampNanos) / 1_000_000
        handle(Sighting(Radio.BLE, r.device.address.uppercase(), rec?.deviceName, r.rssi, seen, manufacturerData = mfg, serviceData = sd, serviceUuids = uuids))
        Funk.diag.value = Funk.diag.value.let { it.copy(bleAdverts = it.bleAdverts + 1, lastBle = now) }
    }

    /** 16-bit Bluetooth SIG UUID out of 0000XXXX-0000-1000-8000-00805F9B34FB. */
    private fun short(u: ParcelUuid): Int? {
        val msb = u.uuid.mostSignificantBits
        return if (msb ushr 48 == 0L && u.uuid.leastSignificantBits == BASE_LSB) ((msb ushr 32) and 0xFFFF).toInt() else null
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_SCAN (12+) / location (≤11) checked in hasBlePermission
    private fun startBle() {
        if (!hasBlePermission()) return
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val filtered = !screenOn
        if (bleFiltered == filtered) return
        stopBle()
        // Android pauses unfiltered scans while the screen is off. Then only trackers and watched addresses count.
        val filters = if (filtered) screenOffFilters() else null
        val settings = ScanSettings.Builder()
            .setScanMode(if (filtered) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(filters, settings, bleCallback) }
            .onSuccess { bleFiltered = filtered; Funk.diag.value = Funk.diag.value.copy(bleRunning = true, bleFiltered = filtered, bleError = 0) }
            .onFailure { Funk.diag.value = Funk.diag.value.copy(bleRunning = false, bleError = -1) }
    }

    private fun screenOffFilters(): List<ScanFilter> {
        val list = mutableListOf(ScanFilter.Builder().setManufacturerData(APPLE, byteArrayOf(0x12), byteArrayOf(0xFF.toByte())).build())
        for (u in TRACKER_SERVICE_UUIDS) list += ScanFilter.Builder().setServiceData(ParcelUuid(uuid16(u)), ByteArray(0)).build()
        TRACKER_SERVICE_UUIDS.forEach { list += ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid16(it))).build() }
        // shortcut: only exact addresses survive screen-off; name and maker rules need the screen on
        for (r in Funk.rules.value) if (r.kind != RuleKind.MUTE && r.field == RuleField.ADDRESS) hexOf(r.value)?.let { h ->
            list += ScanFilter.Builder().setDeviceAddress(h.chunked(2).joinToString(":")).build()
        }
        return list
    }

    private fun uuid16(u: Int) = UUID((u.toLong() shl 32) or 0x1000L, BASE_LSB)

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        if (bleFiltered == null) return
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(bleCallback) }
        bleFiltered = null
        Funk.diag.value = Funk.diag.value.copy(bleRunning = false)
    }

    private fun hasBlePermission() = if (Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_SCAN) else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            if (Funk.settings.value.ble) startBle()
        }
    }

    // ------------------------------------------------------------- radios switched off

    /** GrapheneOS can switch Wi-Fi and Bluetooth off after a while without a connection; Bluetooth also needs a restart of the scan. */
    private val radios = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == BluetoothAdapter.ACTION_STATE_CHANGED) when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> { bleFiltered = null; if (Funk.settings.value.ble) startBle(); radioBack(bt = true) }
                BluetoothAdapter.STATE_OFF -> {
                    bleFiltered = null // the scan died with the radio
                    Funk.diag.value = Funk.diag.value.copy(bleRunning = false)
                    if (Funk.settings.value.ble) radioOff(bt = true)
                }
            } else when (i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)) {
                WifiManager.WIFI_STATE_ENABLED -> radioBack(bt = false)
                WifiManager.WIFI_STATE_DISABLED -> if (applicationContext.getSystemService(WifiManager::class.java)?.isScanAlwaysAvailable != true) radioOff(bt = false)
            }
        }
    }

    private fun radioOff(bt: Boolean) {
        val tap = PendingIntent.getActivity(this, if (bt) 2 else 3, radioOn(this, bt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val text = if (bt) tr("Ohne Bluetooth keine Tracker-Warnung. Tippen zum Einschalten.", "No tracker alerts without Bluetooth. Tap to turn it on.")
        else tr("Ohne WLAN keine WLAN-Scans. Tippen zum Einschalten.", "No Wi-Fi scans without Wi-Fi. Tap to turn it on.")
        val tip = tr("Schaltet GrapheneOS es von selbst aus: in den Einstellungen nach „automatisch“ suchen und den Timer auf „Nie“ stellen.",
            "If GrapheneOS switches it off by itself: search Settings for \"automatically\" and set the timer to never.")
        val n = NotificationCompat.Builder(this, Alerter.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (bt) tr("Bluetooth ist aus", "Bluetooth is off") else tr("WLAN ist aus", "Wi-Fi is off"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text $tip"))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(radioId(bt), n) }
    }

    private fun radioBack(bt: Boolean) = runCatching { getSystemService(NotificationManager::class.java).cancel(radioId(bt)) }

    private fun radioId(bt: Boolean) = if (bt) NOTIFICATION_BT else NOTIFICATION_WIFI

    // ------------------------------------------------------------- position + speed cameras

    private val location = LocationListener { l: Location ->
        val f = Fix(l.latitude, l.longitude, if (l.hasSpeed()) l.speed * 3.6f else null, if (l.hasBearing()) l.bearing else null, l.time)
        Funk.fix.value = f
        Funk.addToTrack(f)
        Funk.diag.value = Funk.diag.value.let { it.copy(fixes = it.fixes + 1) }
        checkSpeedCam(f)
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        // plain Android location, no Google services: works the same on GrapheneOS
        for ((p, ms) in listOf(LocationManager.GPS_PROVIDER to 1000L, LocationManager.NETWORK_PROVIDER to 10_000L))
            if (p in lm.allProviders) runCatching { lm.requestLocationUpdates(p, ms, 0f, location, Looper.getMainLooper()) }
    }

    private fun checkSpeedCam(f: Fix) {
        if (!Funk.settings.value.traffic) { Funk.camAhead.value = null; return }
        val moving = (f.speedKmh ?: 0f) > 8f
        val ahead = Funk.speedCams.ahead(f.lat, f.lon, if (moving) f.bearing?.toDouble() else null)
        Funk.camAhead.value = ahead
        if (ahead == null || !moving) return
        val key = "%.5f,%.5f".format(java.util.Locale.ROOT, ahead.cam.lat, ahead.cam.lon)
        val now = System.currentTimeMillis()
        if ((warnedCams[key] ?: 0L) + 10 * 60_000L > now) return
        warnedCams[key] = now
        val limit = ahead.cam.maxspeed?.let { " · $it km/h" } ?: ""
        Funk.logAlert("speedcam", tr("Blitzer in ${ahead.meters} m$limit", "Speed camera in ${ahead.meters} m$limit"), null)
        alerter.play("speedcam", voice = true)
    }

    // ------------------------------------------------------------- notification

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Funk.devices.value.size
        return NotificationCompat.Builder(this, CHANNEL_SCAN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(tr("Funkblick scannt", "Funkblick is scanning"))
            .setContentText(tr("$n Geräte in Reichweite gesehen", "$n devices seen"))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, tr("Stopp", "Stop"), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        if (Funk.running.value) runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }

    companion object {
        private const val CHANNEL_SCAN = "scan"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_BT = 2
        private const val NOTIFICATION_WIFI = 3
        private const val ACTION_STOP = "stop"
        private const val BASE_LSB = -0x7fffff7fa064cb05L // 0x800000805F9B34FB

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, ScanService::class.java).setAction(ACTION_STOP))

        /** One tap instead of a trip through Settings: since Android 10 (Wi-Fi) and 13 (Bluetooth) no app may switch them on itself. */
        fun radioOn(context: Context, bt: Boolean): Intent = when {
            !bt -> Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
            Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ->
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            else -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
    }
}
