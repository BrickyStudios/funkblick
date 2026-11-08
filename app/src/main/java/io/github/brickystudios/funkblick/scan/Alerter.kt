package io.github.brickystudios.funkblick.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.brickystudios.funkblick.MainActivity
import io.github.brickystudios.funkblick.R
import io.github.brickystudios.funkblick.core.isGerman
import io.github.brickystudios.funkblick.core.tr

/**
 * Sound, voice, vibration and notification for an alert. Each kind has its own signal (assets/sounds/tone_*.ogg,
 * tools/build_sounds.py), followed by a spoken line in the phone's language. Navigation-guidance audio:
 * music ducks, and in the car it comes out where the navigation voice does.
 */
class Alerter(private val context: Context) {
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
    private val ids = HashMap<String, Int>()
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var busyUntil = 0L

    init {
        val lang = if (isGerman()) "de" else "en"
        for (name in context.assets.list("sounds").orEmpty()) {
            val key = name.removeSuffix(".ogg")
            if (key.startsWith("tone_") || key.startsWith("${lang}_")) context.assets.openFd("sounds/$name").use { ids[key] = pool.load(it, 1) }
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, tr("Warnungen", "Alerts"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // Funkblick plays its own sounds, the channel only shows
            enableVibration(false)
        })
    }

    /** kind: tracker, priority, speedcam, camera, fleet, watch, start, stop. */
    fun play(kind: String, voice: Boolean) {
        val s = Funk.settings.value
        if (s.sound) {
            val now = System.currentTimeMillis()
            // two alerts at once: the second waits for the first, never talks over it
            val start = maxOf(0L, busyUntil - now)
            busyUntil = now + start + TONE_MS + if (voice && s.voice) VOICE_MS else 0
            main.postDelayed({ duck(); ids["tone_$kind"]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) } }, start)
            if (voice && s.voice) main.postDelayed({ ids[langKey(kind)]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) } }, start + TONE_MS)
            main.postDelayed({ unduck() }, busyUntil - now + 200)
            Funk.diag.value = Funk.diag.value.copy(sounds = Funk.diag.value.sounds + 1)
        }
        if (s.vibrate && voice) vibrate(kind)
    }

    private fun langKey(kind: String) = (if (isGerman()) "de_" else "en_") + kind

    private fun duck() {
        if (focus != null) return
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
            .also { audio.requestAudioFocus(it) }
    }

    private fun unduck() {
        if (System.currentTimeMillis() < busyUntil) return
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun vibrate(kind: String) {
        val pattern = when (kind) {
            "tracker" -> longArrayOf(0, 120, 80, 120, 80, 400)
            "priority" -> longArrayOf(0, 200, 100, 200)
            "police" -> longArrayOf(0, 200, 100, 200, 100, 200)
            "speedcam" -> longArrayOf(0, 60, 60, 60, 60, 250)
            "follow" -> longArrayOf(0, 90, 120, 90, 300, 250)
            else -> longArrayOf(0, 150)
        }
        val v = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /** findId: a tracker's device id; tapping then opens find mode with help (plan 3A). */
    fun notify(id: Int, title: String, text: String, findId: String? = null) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (findId != null) intent.setAction(MainActivity.ACTION_FIND).putExtra(MainActivity.EXTRA_ID, findId)
        val open = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val quiet = Funk.discreet.value // discreet mode: no details on the lock screen or in the shade
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (quiet) "Funkblick" else title)
            .setContentText(if (quiet) tr("Neue Meldung", "New alert") else text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (quiet) tr("Neue Meldung", "New alert") else text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        @Suppress("MissingPermission") // checked above: areNotificationsEnabled is false without POST_NOTIFICATIONS
        NotificationManagerCompat.from(context).notify(id, n)
    }

    fun release() = pool.release()

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        private const val TONE_MS = 1700L // the start sequence is the longest signal
        private const val VOICE_MS = 2600L
    }
}
