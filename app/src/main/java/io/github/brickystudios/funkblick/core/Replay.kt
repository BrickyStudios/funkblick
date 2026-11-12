package io.github.brickystudios.funkblick.core

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Locale

// An old log opened again: the JSON log (shareable, masked in safe mode) plus the route file next to it. The route
// file holds the track and where fixed devices probably are, at full precision, so it never leaves the app's storage:
// not shared, not copied to Downloads, not backed up. Keys are the device ids as the log writes them (masked in safe mode).

/** alerts: the exact place of each alert, in the log's order (the log rounds them to about 1 km in safe mode). */
data class RouteFile(val track: List<TrackPoint>, val pos: Map<String, Estimate>, val alerts: List<TrackPoint?>? = null)

data class PastSession(val startedAt: Long, val devices: List<Device>, val alerts: List<LoggedAlert>, val track: List<TrackPoint>)

/** [alerts] in the same order as given to toJson. */
fun routeJson(devices: Collection<Device>, track: List<TrackPoint>, m: Masker, alerts: List<LoggedAlert> = emptyList()): String =
    Gson().toJson(RouteFile(track, devices.mapNotNull { d -> d.pos?.let { deviceId(d.radio, m.address(d.address)) to it } }.toMap(),
        alerts.map { a -> if (a.lat != null && a.lon != null) TrackPoint(a.lat, a.lon, a.time) else null }))

private fun JsonObject.opt(k: String): JsonElement? = get(k)?.takeIf { !it.isJsonNull }
private fun JsonObject.str(k: String) = opt(k)?.asString
private fun JsonObject.int(k: String) = opt(k)?.asInt
private fun JsonObject.bool(k: String) = opt(k)?.asBoolean
private fun JsonObject.dbl(k: String) = opt(k)?.asDouble

/** [route] is null for logs from before 1.13 (no side file): then only alerts with a position reach the map. */
fun readLog(json: String, route: String?): PastSession {
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
    fun time(s: String?) = s?.let { runCatching { iso.parse(it)?.time }.getOrNull() } ?: 0L
    val root = JsonParser.parseString(json).asJsonObject
    val r = route?.let { runCatching { Gson().fromJson(it, RouteFile::class.java) }.getOrNull() }
    val devices = root.getAsJsonArray("devices")?.mapNotNull { e ->
        runCatching { // a newer app may write a category this one doesn't know: skip that device, keep the rest
            val o = e.asJsonObject
            val radio = Radio.valueOf(o.str("radio")!!)
            val address = o.str("address")!!
            val tracker = o.str("tracker")?.let { l -> TrackerKind.entries.firstOrNull { it.label == l } }?.let { Tracker(it, o.bool("separated")) }
            val evidence = o.getAsJsonArray("evidence")?.mapNotNull { v ->
                v.asJsonObject.let { runCatching { Evidence(Source.valueOf(it.str("source")!!), it.str("text") ?: "", Confidence.valueOf(it.str("confidence")!!)) }.getOrNull() }
            }.orEmpty()
            // the log keeps only "watched"/"muted", not the rule itself: a stand-in rule keeps colours and filters right
            fun rule(k: RuleKind) = Rule(k, RuleField.ADDRESS, address, id = 0)
            Device(
                radio = radio, address = address, name = o.str("name"),
                rssi = o.int("rssi_last") ?: -100, rssiMax = o.int("rssi_max") ?: -100, freqMhz = o.int("freq_mhz") ?: 0,
                security = o.str("security")?.let { runCatching { Security.valueOf(it) }.getOrNull() },
                firstSeen = time(o.str("first_seen")), lastSeen = time(o.str("last_seen")), seenCount = o.int("seen_count") ?: 0,
                cls = Classification(o.str("vendor"), o.bool("random_address") ?: false, Category.valueOf(o.str("category")!!), tracker, evidence,
                    watch = if (o.bool("watch") == true) rule(RuleKind.WATCH) else null, mute = if (o.bool("mute") == true) rule(RuleKind.MUTE) else null),
                places = o.int("places") ?: 0, own = o.bool("own") ?: false,
                pos = r?.pos?.get(deviceId(radio, address)),
                police = o.str("police")?.let { runCatching { Police(PoliceLevel.valueOf(it), o.str("police_why") ?: "") }.getOrNull() },
            )
        }.getOrNull()
    }.orEmpty()
    val logged = root.getAsJsonArray("alerts")?.mapNotNull { e ->
        e.asJsonObject.let { LoggedAlert(time(it.str("time")), it.str("kind") ?: "", it.str("text") ?: "", it.str("address"), it.dbl("lat"), it.dbl("lon")) }
    }.orEmpty()
    val exact = r?.alerts?.takeIf { it.size == logged.size }
    val alerts = if (exact == null) logged else logged.mapIndexed { i, a -> a.copy(lat = exact[i]?.lat, lon = exact[i]?.lon) }
    return PastSession(time(root.str("session_start")), devices, alerts, r?.track.orEmpty())
}

/** Every log as one, for the map of all drives. A device heard on two drives keeps both places; newest sighting first. */
fun mergeSessions(all: List<PastSession>) = PastSession(
    all.minOfOrNull { it.startedAt } ?: 0L,
    all.flatMap { it.devices }.sortedByDescending { it.lastSeen },
    all.flatMap { it.alerts }.sortedBy { it.time },
    all.sortedBy { it.startedAt }.flatMap { it.track },
)

/** A track cut where it paused for over 10 minutes, so two drives on one map get no line from the end of one to the start of the next. */
fun <T> legs(track: List<T>, time: (T) -> Long): List<List<T>> {
    val out = mutableListOf<MutableList<T>>()
    for (p in track) if (out.isEmpty() || time(p) - time(out.last().last()) > 10 * 60_000) out += mutableListOf(p) else out.last() += p
    return out
}
