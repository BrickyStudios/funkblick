package io.github.brickystudios.funkblick.scan

import io.github.brickystudios.funkblick.BuildConfig
import io.github.brickystudios.funkblick.core.CamAhead
import io.github.brickystudios.funkblick.core.Guide
import io.github.brickystudios.funkblick.core.SpeedCam
import io.github.brickystudios.funkblick.core.Place
import io.github.brickystudios.funkblick.core.Progress
import io.github.brickystudios.funkblick.core.Route
import io.github.brickystudios.funkblick.core.isGerman
import io.github.brickystudios.funkblick.core.parsePhoton
import io.github.brickystudios.funkblick.core.parseValhalla
import io.github.brickystudios.funkblick.core.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** busy: a route request is on its way (first route or a new one after leaving it). */
data class NavState(val dest: Place, val route: Route? = null, val progress: Progress? = null, val busy: Boolean = false, val error: String? = null)

/**
 * Talks to Photon (places) and FOSSGIS Valhalla (routes) and runs the [Guide] on every fix from the scan service.
 * Both servers learn start and destination; nothing of it goes into logs or exports. FOSSGIS allows one request per
 * second, a new route is asked for at most every 15 s. Everything that changes state runs on the main thread.
 */
object Nav {
    private const val ROUTER = "https://valhalla1.openstreetmap.de/route"
    private const val PLACES = "https://photon.komoot.io/api/"
    private const val RETRY_MS = 15_000L

    val state = MutableStateFlow<NavState?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var guide: Guide? = null
    private var job: Job? = null
    private var asked = 0L
    private var arrivedAt = 0L
    private var refused = false // the server said this destination can't be reached by car: asking again won't change that
    private val pending = mutableListOf<String>() // said with the next fix: the scan service owns the speaker

    /** null: no connection, or "Load map data from the network" is off. */
    suspend fun search(q: String, near: Fix?): List<Place>? = withContext(Dispatchers.IO) {
        if (!Funk.settings.value.mapData) return@withContext null
        // a bias only needs the town: about 1 km is enough to prefer nearby places
        val bias = near?.let { "&lat=%.2f&lon=%.2f".format(java.util.Locale.ROOT, it.lat, it.lon) } ?: ""
        runCatching { parsePhoton(get("$PLACES?q=${URLEncoder.encode(q, "UTF-8")}&limit=8&lang=${if (isGerman()) "de" else "en"}$bias")) }.getOrNull()
    }

    fun start(dest: Place) {
        stop()
        state.value = NavState(dest)
        // only from a live fix of a running scan; the recap keeps the last one of an old drive, the first new fix asks then
        Funk.fix.value?.takeIf { Funk.running.value && System.currentTimeMillis() - it.time < 10_000 }?.let(::route)
    }

    fun stop() {
        job?.cancel(); job = null
        guide = null; asked = 0L; arrivedAt = 0L; refused = false
        pending.clear()
        state.value = null
    }

    /** Every GPS fix while a destination is set. Returns the clips to say now. */
    fun onFix(f: Fix): List<String> {
        val s = state.value ?: return emptyList()
        val now = System.currentTimeMillis()
        val idle = job?.isActive != true
        val g = guide
        if (g == null) {
            if (idle && !refused && now - asked > RETRY_MS) route(f) // first route, or again after no connection
            return take()
        }
        val p = g.update(f.lat, f.lon, f.speedKmh)
        // a failed new route only matters while you're off the old one; back on it, the arrow comes back
        state.value = s.copy(progress = p, error = s.error.takeIf { p.offRoute })
        if (p.arrived && arrivedAt == 0L) arrivedAt = now
        // arrived: looking for a parking space is no reason for a new route; the guidance ends a minute later
        if (p.offRoute && arrivedAt == 0L && !refused && idle && now - asked > RETRY_MS) route(f)
        if (arrivedAt != 0L && now - arrivedAt > 60_000) stop()
        return take() + p.say
    }

    /** On a route and on it: speed cameras are then looked for along the road instead of in a radius. */
    fun following(): Boolean = guide != null && state.value?.progress?.offRoute != true

    fun camOnRoute(cams: List<SpeedCam>, maxM: Double): CamAhead? = guide?.camOnRoute(cams, maxM)

    private fun take(): List<String> = pending.toList().also { pending.clear() }

    private fun route(from: Fix) {
        val s = state.value ?: return
        asked = System.currentTimeMillis()
        // the one place every request passes (start, "Home", retry, new route): the privacy switch holds for all of them
        if (!Funk.settings.value.mapData) {
            state.value = s.copy(busy = false, error = tr("Navigation braucht „Kartendaten aus dem Netz laden“ (unter Mehr).",
                "Navigation needs \"Load map data from the network\" (under More)."))
            return
        }
        state.value = s.copy(busy = true)
        job = scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { parseValhalla(post(ROUTER, body(from, s.dest))) } }
            val r = res.getOrNull()
            val now = state.value ?: return@launch // stopped meanwhile
            if (r == null) {
                refused = res.exceptionOrNull() is Refused
                state.value = now.copy(busy = false, error = if (refused) tr("Zu diesem Ziel findet FOSSGIS keine Autoroute.", "FOSSGIS finds no car route to this destination.")
                    else tr("Keine Route. Ohne Netz versucht Funkblick es alle 15 s neu.", "No route. Without network Funkblick retries every 15 s."))
                return@launch
            }
            pending += if (guide == null) "nav_start" else "nav_reroute"
            val g = Guide(r).also { guide = it }
            // the car moved on while the server answered: place it on the new route right away
            val p = Funk.fix.value?.let { g.update(it.lat, it.lon, it.speedKmh) }?.also { pending += it.say }
            state.value = now.copy(route = r, progress = p, busy = false, error = null)
        }
    }

    private fun body(f: Fix, d: Place): String {
        // when driving, start in the driving direction instead of a U-turn
        val h = f.bearing?.takeIf { (f.speedKmh ?: 0f) > 8f }?.let { ""","heading":${it.toInt()},"heading_tolerance":45""" } ?: ""
        val lang = if (isGerman()) "de-DE" else "en-US"
        return """{"locations":[{"lat":${f.lat},"lon":${f.lon}$h},{"lat":${d.lat},"lon":${d.lon}}],"costing":"auto","units":"kilometers","language":"$lang"}"""
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 20_000
        setRequestProperty("User-Agent", "Funkblick/${BuildConfig.VERSION_NAME} (github.com/BrickyStudios/funkblick)")
    }

    /** 4xx except 429: the request itself can't succeed (Valhalla: no route, too far). Retrying won't help. */
    private class Refused : Exception()

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        if (code in 400..499 && code != 429) { runCatching { c.errorStream?.close() }; throw Refused() }
        return c.inputStream.bufferedReader().use { it.readText() } // 429 and 5xx throw here: counted as "no connection", retried
    }

    // disconnect() in every case, also when sending already fails without network
    private fun get(url: String) = open(url).let { c -> try { read(c) } finally { c.disconnect() } }

    private fun post(url: String, json: String) = open(url).let { c ->
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray()) }
            read(c)
        } finally { c.disconnect() }
    }
}
