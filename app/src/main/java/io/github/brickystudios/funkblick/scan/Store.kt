package io.github.brickystudios.funkblick.scan

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.brickystudios.funkblick.BuildConfig
import io.github.brickystudios.funkblick.core.Alert
import io.github.brickystudios.funkblick.core.AlertReason
import io.github.brickystudios.funkblick.core.HomeWatch
import io.github.brickystudios.funkblick.core.Category
import io.github.brickystudios.funkblick.core.isFollowingVehicle
import io.github.brickystudios.funkblick.core.KnownCam
import io.github.brickystudios.funkblick.core.addSession
import io.github.brickystudios.funkblick.core.cameAlong
import io.github.brickystudios.funkblick.core.merge
import io.github.brickystudios.funkblick.core.AlertSettings
import io.github.brickystudios.funkblick.core.CamAhead
import io.github.brickystudios.funkblick.core.Classifier
import io.github.brickystudios.funkblick.core.Device
import io.github.brickystudios.funkblick.core.ExportMeta
import io.github.brickystudios.funkblick.core.LoggedAlert
import io.github.brickystudios.funkblick.core.Masker
import io.github.brickystudios.funkblick.core.OuiDb
import io.github.brickystudios.funkblick.core.Rule
import io.github.brickystudios.funkblick.core.Session
import io.github.brickystudios.funkblick.core.Sighting
import io.github.brickystudios.funkblick.core.SpeedCamDb
import io.github.brickystudios.funkblick.core.demoSightings
import io.github.brickystudios.funkblick.core.kind
import io.github.brickystudios.funkblick.core.label
import io.github.brickystudios.funkblick.core.toJson
import io.github.brickystudios.funkblick.core.overpassQuery
import io.github.brickystudios.funkblick.core.overpassToTsv
import io.github.brickystudios.funkblick.core.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Saved on the phone (SharedPreferences). Defaults are the private, quiet choice. */
data class Settings(
    val safeMode: Boolean = true, // exports and logs
    val safeScreen: Boolean = false, // the screen, for screenshots and screen sharing
    val sound: Boolean = true,
    val voice: Boolean = true,
    val vibrate: Boolean = true,
    val trackers: Boolean = true,
    val cameras: Boolean = true,
    val hackers: Boolean = true,
    val traffic: Boolean = false, // speed cameras + public safety + vehicle routers, only after the StVO warning
    val ble: Boolean = true,
    val log: Boolean = true,
    val carStart: Boolean = false, // offer car mode when the phone connects to a car's Bluetooth
    val mapData: Boolean = true, // load map data from the network; off = offline regions and cache only
)

data class Fix(val lat: Double, val lon: Double, val speedKmh: Float?, val bearing: Float?, val time: Long)

data class Diag(
    val wifiRequested: Int = 0,
    val wifiThrottled: Int = 0,
    val wifiResults: Int = 0,
    val lastWifi: Long = 0,
    val bleRunning: Boolean = false,
    val bleFiltered: Boolean = false,
    val bleError: Int = 0,
    val bleAdverts: Long = 0,
    val lastBle: Long = 0,
    val serviceSince: Long = 0,
    val sounds: Int = 0,
    val fixes: Int = 0,
)

/** The app's state. The scan service writes, the UI reads. Main thread only. */
object Funk {
    private val gson = Gson()
    private lateinit var app: Context
    lateinit var classifier: Classifier
        private set
    var speedCams = SpeedCamDb.EMPTY
        private set
    var trafficCams = SpeedCamDb.EMPTY // traffic cameras, number plate scanners, counters (OpenStreetMap, bundled)
        private set
    var ouiSize = 0
        private set

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    lateinit var session: Session
        private set

    /** Home watch for the More page: how many devices it knows, minutes still learning, are you there, how many new ones. */
    data class HomeState(val known: Int, val learningMin: Int, val there: Boolean, val new: Int, val cameras: Int)
    val home = MutableStateFlow<HomeState?>(null)
    private var savedHome: HomeWatch? = null
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    private val _alerts = MutableStateFlow<List<LoggedAlert>>(emptyList())
    val alerts: StateFlow<List<LoggedAlert>> = _alerts.asStateFlow()
    val running = MutableStateFlow(false)
    val discreet = MutableStateFlow(false) // notifications say only "Funkblick" while true

    fun discreetHintSeen() = prefs().getBoolean("discreetHint", false)
    fun markDiscreetHintSeen() = prefs().edit().putBoolean("discreetHint", true).apply()

    /** "Home" for navigation: an address with coordinates, unlike the home watch. Only in the app's own storage
     * (no backup, allowBackup=false), never in a log or export. */
    val navHome = MutableStateFlow<io.github.brickystudios.funkblick.core.Place?>(null)
    fun setNavHome(p: io.github.brickystudios.funkblick.core.Place?) {
        navHome.value = p
        prefs().edit().apply { if (p == null) remove("navHome") else putString("navHome", gson.toJson(p)) }.apply()
    }
    val demo = MutableStateFlow(false)
    val diag = MutableStateFlow(Diag())
    val fix = MutableStateFlow<Fix?>(null)
    val track = MutableStateFlow<List<Fix>>(emptyList()) // where you went this session, every 15 m

    fun addToTrack(f: Fix) {
        val last = track.value.lastOrNull()
        if (last == null || io.github.brickystudios.funkblick.core.distanceMeters(last.lat, last.lon, f.lat, f.lon) >= 15)
            track.value = (track.value + f).takeLast(3000)
    }
    val camAhead = MutableStateFlow<CamAhead?>(null)
    private var salt = UUID.randomUUID().toString()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val oui = app.assets.open("oui.txt").bufferedReader().useLines { OuiDb(it) }
        ouiSize = oui.size
        classifier = Classifier(oui)
        session = Session(classifier)
        session.cameAlongBefore = { seenBefore(it).isNotEmpty() }
        loadSpeedCams()
        loadCams()
        val prefs = prefs()
        prefs.getString("settings", null)?.let { s -> runCatching { gson.fromJson(s, Settings::class.java) }.getOrNull()?.let { _settings.value = it } }
        // the place is a handful of Wi-Fi ids, no coordinates; it stays in the app's own storage and never goes into a log
        prefs.getString("home", null)?.let { s -> runCatching { gson.fromJson(s, HomeWatch::class.java) }.getOrNull()?.let { session.home = it; savedHome = it } }
        prefs.getString("navHome", null)?.let { s -> navHome.value = runCatching { gson.fromJson(s, io.github.brickystudios.funkblick.core.Place::class.java) }.getOrNull() }
        prefs.getString("rules", null)?.let { s ->
            runCatching { gson.fromJson<List<Rule>>(s, object : TypeToken<List<Rule>>() {}.type) }.getOrNull()?.let { _rules.value = it }
        }
    }

    private fun prefs() = app.getSharedPreferences("funkblick", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- speed cameras

    private fun localCams() = File(app.filesDir, "speedcams-local.tsv")

    private fun latestCams(name: String) = File(app.filesDir, "$name-latest.tsv")

    /** The whole-Germany file: the weekly download if it was made by this app version, else the one in the APK. */
    private fun camLines(name: String): List<String> =
        latestCams(name).takeIf { it.exists() && prefs().getInt("camDataVersion", 0) == BuildConfig.VERSION_CODE }?.readLines()
            ?: app.assets.open("$name.tsv").bufferedReader().readLines()

    /** Speed cameras (whole Germany plus the ones refreshed for your region), traffic cameras and counters. */
    private fun loadSpeedCams() {
        val local = localCams().takeIf { it.exists() }?.readLines() ?: emptyList()
        speedCams = SpeedCamDb((local + camLines("speedcams")).asSequence())
        trafficCams = SpeedCamDb(camLines("trafficcams").asSequence())
    }

    /**
     * Once a week: the camera files from the repo, which a GitHub Action rebuilds from OpenStreetMap every Monday
     * (.github/workflows/data.yml). Everyone gets the same file for all of Germany, so no position leaves the phone.
     */
    suspend fun refreshCameraData() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val p = prefs()
        val now = System.currentTimeMillis()
        val sameApp = p.getInt("camDataVersion", 0) == BuildConfig.VERSION_CODE
        if (sameApp && now - p.getLong("camDataChecked", 0) < 7 * 24 * 3_600_000L) return@withContext
        var got = 0
        for (name in listOf("speedcams", "trafficcams")) runCatching {
            val c = (java.net.URL("$CAM_DATA/$name.tsv").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "Funkblick/${BuildConfig.VERSION_NAME} (github.com/BrickyStudios/funkblick)")
            }
            val text = if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else return@runCatching
            if (SpeedCamDb(text.lineSequence()).size < 1000) return@runCatching // a broken file never replaces a good one
            latestCams(name).writeText(text)
            got++
        }
        if (got == 2) { // offline or a half answer: try again at the next start
            p.edit().putInt("camDataVersion", BuildConfig.VERSION_CODE).putLong("camDataChecked", now).apply()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { loadSpeedCams() }
        }
    }

    private val CAM_DATA = "https://raw.githubusercontent.com/BrickyStudios/funkblick/main/app/src/main/assets"

    fun speedCamsUpdated(): Long = localCams().takeIf { it.exists() }?.lastModified() ?: 0L

    /**
     * Asks OpenStreetMap (Overpass) for the cameras in a box of about 130 km around [lat]/[lon]. That box is all
     * that leaves the phone. Returns the number of cameras found there, or null when no server answered.
     */
    suspend fun refreshSpeedCams(lat: Double, lon: Double): Int? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val body = "data=" + java.net.URLEncoder.encode(overpassQuery(lat, lon), "UTF-8")
        val lines = OVERPASS.firstNotNullOfOrNull { server ->
            runCatching {
                val c = (java.net.URL(server).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 90_000
                    setRequestProperty("User-Agent", "Funkblick/${BuildConfig.VERSION_NAME} (github.com/BrickyStudios/funkblick)")
                }
                c.outputStream.use { it.write(body.toByteArray()) }
                if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { overpassToTsv(it.readText()) }
            }.getOrNull()
        } ?: return@withContext null
        val old = localCams().takeIf { it.exists() }?.readLines() ?: emptyList()
        localCams().writeText((lines + old).distinct().joinToString("\n", postfix = "\n"))
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { loadSpeedCams() }
        lines.size
    }

    private val OVERPASS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter")

    fun updateSettings(f: (Settings) -> Settings) {
        _settings.update(f)
        prefs().edit().putString("settings", gson.toJson(_settings.value)).apply()
    }

    fun setRules(rules: List<Rule>) {
        _rules.value = rules
        prefs().edit().putString("rules", gson.toJson(rules)).apply()
        session.reclassify(rules)
        publish()
    }

    fun alertSettings() = _settings.value.let { AlertSettings(trackers = it.trackers, cameras = it.cameras, traffic = it.traffic, hackers = it.hackers) }

    /** One sighting from a radio. Returns the alert to ring, if any. */
    fun ingest(s: Sighting): Alert? = fix.value.let { f -> session.add(s, _rules.value, alertSettings(), f?.lat, f?.lon, f?.speedKmh) }

    fun publish() {
        _devices.value = session.devices.values.toList()
        val now = System.currentTimeMillis()
        home.value = session.home?.let { h ->
            HomeState(h.known.size, if (now < h.learnUntil) ((h.learnUntil - now + 59_999) / 60_000).toInt() else 0, running.value && session.atHome, session.newAtHome.size,
                session.devices.values.count { it.id in h.known && it.cls.category == Category.CAMERA })
        }
    }

    /** Remembers the place you are at now. False when no Wi-Fi is in range to recognise it by. */
    fun markHome(): Boolean = (session.markHome() != null).also { saveHome(); publish() }

    fun acceptNewAtHome() { session.acceptNewAtHome(); saveHome(); publish() }

    fun forgetHome() { session.home = null; saveHome(); publish() }

    private fun saveHome() {
        if (session.home == savedHome) return
        savedHome = session.home
        prefs().edit().putString("home", session.home?.let(gson::toJson)).apply()
    }

    /** where = false: no position with the alert, so a shared log doesn't point at your saved place. */
    fun logAlert(kind: String, text: String, address: String?, where: Boolean = true) {
        val f = fix.value?.takeIf { where }
        _alerts.update { (listOf(LoggedAlert(System.currentTimeMillis(), kind, text, address, f?.lat, f?.lon)) + it).take(200) }
    }

    fun logAlert(a: Alert) = logAlert(a.reason.kind(), a.reason.label(), a.device.address, where = a.reason != AlertReason.HOME_NEW)

    fun newSession() {
        session.clear()
        _alerts.value = emptyList()
        track.value = emptyList()
        salt = UUID.randomUUID().toString()
        logFile = null
        publish()
    }

    /** Demo mode: invented surroundings for trying the app without radios (and for screenshots). */
    fun loadDemo() {
        newSession()
        demo.value = true
        val now = System.currentTimeMillis()
        // a drive north through Hamburg towards a real fixed camera (50 km/h at 53.54785, 9.98667), for the car page
        val lat0 = 53.54470
        val lon0 = 9.98667
        val track0 = (0..9).map { Fix(lat0 - 0.0036 + it * 0.0004, lon0, 48f, 0f, now - (9 - it) * 4_000L) }
        track.value = track0
        fix.value = track0.last()
        if (_settings.value.traffic) camAhead.value = speedCams.ahead(lat0, lon0, 0.0)?.also {
            logAlert("speedcam", tr("Blitzer in ${it.meters} m, ${it.cam.maxspeed} km/h", "Speed camera in ${it.meters} m, ${it.cam.maxspeed} km/h"), null)
        }
        // fixed Wi-Fi devices get readings along that road, so the map can show where they probably are
        for (s in demoSightings(now)) {
            if (s.radio == io.github.brickystudios.funkblick.core.Radio.WIFI) {
                val h = s.address.hashCode()
                val dLat = ((h and 0xFF) - 128) / 128.0 * 0.0016
                val dLon = (((h shr 8) and 0xFF) - 128) / 128.0 * 0.0025
                for ((i, f) in track0.withIndex()) {
                    val d = maxOf(5.0, io.github.brickystudios.funkblick.core.distanceMeters(f.lat, f.lon, lat0 + dLat, lon0 + dLon))
                    val rssi = (-40 - 27 * kotlin.math.log10(d)).toInt()
                    session.add(io.github.brickystudios.funkblick.core.Sighting(s.radio, s.address, s.name, rssi, s.timeMs - (9 - i) * 1000L, s.freqMhz, s.security),
                        _rules.value, alertSettings(), f.lat, f.lon)?.let { logAlert(it) }
                }
            } else session.add(s, _rules.value, alertSettings())?.let { logAlert(it) }
        }
        publish()
    }

    fun masker() = Masker(_settings.value.safeMode, salt)
    fun screenMasker() = Masker(_settings.value.safeScreen, salt)

    fun meta() = ExportMeta(
        // device and build identity, so a log tells which phone and OS (GrapheneOS builds as grapheneos@grapheneos)
        "Funkblick", build(), "$os ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.DISPLAY} · ${Build.USER}@${Build.HOST}",
        session.startedAt, System.currentTimeMillis(), _settings.value.safeMode,
    )

    fun build() = "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_HASH}"

    /** "GrapheneOS" when its app store is installed (it can't be removed there), otherwise "Android". */
    val os: String by lazy {
        if (runCatching { app.packageManager.getPackageInfo("app.grapheneos.apps", 0) }.isSuccess) "GrapheneOS" else "Android"
    }

    // ---------------------------------------------------------------- logs

    fun logDir() = File(app.filesDir, "logs").apply { mkdirs() }

    private var logFile: File? = null

    /** Writes the current session to logs/; called every minute while scanning and on stop. */
    fun saveLog() {
        if (!demo.value) { rememberSeen(); saveHome() }
        if (!_settings.value.log || demo.value || session.devices.isEmpty()) return
        val f = logFile ?: File(logDir(), "funkblick-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date(session.startedAt)) + ".json").also { logFile = it }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(toJson(session.devices.values, meta(), masker(), _alerts.value.reversed()))
        tmp.renameTo(f) // never leave a half-written log behind
    }

    /** Copies every log to Download/Funkblick, where a USB cable or the Files app reaches them. Returns how many. */
    fun exportLogsToDownloads(): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        val resolver = app.contentResolver
        var n = 0
        for (f in logs()) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/Funkblick")
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: continue
            n++
        }
        return n
    }

    // ---------------------------------------------------------------- what came along before, where cameras were

    /** Salted hash -> start times of the sessions it came along in (Memory.kt). Can't be read back into addresses. */
    private val seen: HashMap<String, MutableList<Long>> by lazy { loadSeen() }

    /** Cameras from earlier sessions where their signal put them, for the map. Only in the app's own storage (allowBackup=false). */
    private val cams = HashMap<String, KnownCam>()
    val knownCams = MutableStateFlow<List<KnownCam>>(emptyList())

    private fun loadSeen(): HashMap<String, MutableList<Long>> {
        val p = prefs()
        p.getString("seenAt", null)?.let { s ->
            return runCatching { gson.fromJson<HashMap<String, MutableList<Long>>>(s, object : TypeToken<HashMap<String, MutableList<Long>>>() {}.type) }.getOrNull() ?: HashMap()
        }
        // up to 1.11 one date per device ("dd.MM.yyyy"): it becomes one session at noon that day
        val old = runCatching { gson.fromJson<HashMap<String, String>>(p.getString("seen", "{}"), object : TypeToken<HashMap<String, String>>() {}.type) }.getOrNull().orEmpty()
        val day = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
        return HashMap(old.mapNotNull { (k, v) -> runCatching { k to mutableListOf(day.parse(v)!!.time + 12 * 3_600_000L) }.getOrNull() }.toMap())
    }

    private fun loadCams() {
        runCatching { gson.fromJson<HashMap<String, KnownCam>>(prefs().getString("knownCams", "{}"), object : TypeToken<HashMap<String, KnownCam>>() {}.type) }
            .getOrNull()?.let { cams.putAll(it) }
        knownCams.value = cams.values.toList()
    }

    private val seenSecret: String by lazy {
        prefs().getString("seenSalt", null) ?: UUID.randomUUID().toString().also { prefs().edit().putString("seenSalt", it).apply() }
    }

    /** Salted hash: the lists can't be read back into addresses, they only answer "seen before?". */
    private fun seenKey(d: Device): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest((seenSecret + d.address + (d.name ?: "")).toByteArray())
        return h.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun worthRemembering(d: Device) = d.cameAlong() || d.isFollowingVehicle() || d.police != null ||
        d.cls.category in setOf(Category.PRIORITY, Category.FLEET, Category.CAMERA)

    /** Start times of earlier sessions this tag, vehicle, router or camera was seen in, newest first. */
    fun seenBefore(d: Device): List<Long> = seen[seenKey(d)].orEmpty().filter { it != session.startedAt }.reversed()

    private fun rememberSeen() {
        var changed = false
        var camsChanged = false
        for (d in session.devices.values) {
            if (worthRemembering(d)) {
                val list = seen.getOrPut(seenKey(d)) { mutableListOf() }
                if (list.lastOrNull() != session.startedAt) { list.addSession(session.startedAt); changed = true }
            }
            val p = d.pos
            if (d.cls.category == Category.CAMERA && d.cls.mute == null && p != null) {
                val k = seenKey(d)
                val n = cams[k].merge(KnownCam(p.lat, p.lon, p.radiusM, d.name ?: d.cls.vendor ?: "", d.lastSeen))
                if (n != cams[k]) { cams[k] = n; camsChanged = true }
            }
        }
        if (changed) {
            if (seen.size > 5000) seen.entries.sortedBy { it.value.lastOrNull() ?: 0L }.take(seen.size - 5000).forEach { seen.remove(it.key) }
            prefs().edit().putString("seenAt", gson.toJson(seen)).remove("seen").apply()
        }
        if (camsChanged) {
            if (cams.size > 2000) cams.entries.sortedBy { it.value.lastSeen }.take(cams.size - 2000).forEach { cams.remove(it.key) }
            prefs().edit().putString("knownCams", gson.toJson(cams)).apply()
            knownCams.value = cams.values.toList()
        }
    }

    /** Forgets all earlier sessions: what came along and where the cameras were. */
    fun forgetMemory() {
        seen.clear(); cams.clear(); knownCams.value = emptyList()
        prefs().edit().remove("seen").remove("seenAt").remove("knownCams").apply()
    }

    fun logs(): List<File> = logDir().listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: emptyList()
}
