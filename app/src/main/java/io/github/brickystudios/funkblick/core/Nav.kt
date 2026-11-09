package io.github.brickystudios.funkblick.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.cos
import kotlin.math.hypot

// Navigation, so no second nav app has to run next to Funkblick (09.11.). Routes come from FOSSGIS Valhalla
// (OpenStreetMap Germany), places from Photon (komoot). This file is the part without Android: reading their answers,
// following the route and deciding what to say when. Deliberately no option to route around speed cameras, cameras or
// police (plan G, StVO § 23 Abs. 1c).

data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

/** type: Valhalla maneuver type. at: index into the route shape where it happens. exit: roundabout exit number. */
data class Maneuver(val type: Int, val text: String, val at: Int, val exit: Int? = null)

data class Route(val shape: List<Pair<Double, Double>>, val maneuvers: List<Maneuver>, val seconds: Double) {
    /** metres from the start to each shape point */
    val cum: DoubleArray = DoubleArray(shape.size).also { c ->
        for (i in 1 until shape.size) c[i] = c[i - 1] + distanceMeters(shape[i - 1].first, shape[i - 1].second, shape[i].first, shape[i].second)
    }
    val meters: Double get() = cum.lastOrNull() ?: 0.0
}

/** Google's encoded polyline format; Valhalla encodes with 6 decimals. */
fun decodePolyline(s: String, precision: Int = 6): List<Pair<Double, Double>> {
    val factor = Math.pow(10.0, precision.toDouble())
    val out = mutableListOf<Pair<Double, Double>>()
    var i = 0; var lat = 0L; var lon = 0L
    fun next(): Long {
        var shift = 0; var result = 0L
        while (true) {
            val b = s[i++].code - 63
            result = result or ((b and 0x1F).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
    }
    while (i < s.length) {
        lat += next(); lon += next()
        out += lat / factor to lon / factor
    }
    return out
}

private fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString

fun parseValhalla(json: String): Route? = runCatching {
    val trip = JsonParser.parseString(json).asJsonObject.getAsJsonObject("trip")
    val leg = trip.getAsJsonArray("legs")[0].asJsonObject
    val maneuvers = leg.getAsJsonArray("maneuvers").map { it.asJsonObject }.map { m ->
        Maneuver(m["type"].asInt, m.str("instruction").orEmpty(), m["begin_shape_index"].asInt,
            m.get("roundabout_exit_count")?.takeIf { !it.isJsonNull }?.asInt)
    }
    Route(decodePolyline(leg["shape"].asString), maneuvers, trip.getAsJsonObject("summary")["time"].asDouble)
}.getOrNull()?.takeIf { it.shape.isNotEmpty() && it.maneuvers.isNotEmpty() }

fun parsePhoton(json: String): List<Place> = runCatching {
    JsonParser.parseString(json).asJsonObject.getAsJsonArray("features").map { it.asJsonObject }.mapNotNull { f ->
        val c = f.getAsJsonObject("geometry").getAsJsonArray("coordinates")
        val p = f.getAsJsonObject("properties")
        val street = listOfNotNull(p.str("street"), p.str("housenumber")).joinToString(" ").ifEmpty { null }
        val name = p.str("name") ?: street ?: return@mapNotNull null
        val town = listOfNotNull(p.str("postcode"), p.str("city") ?: p.str("town") ?: p.str("village")).joinToString(" ").ifEmpty { null }
        Place(name, listOfNotNull(street?.takeIf { it != name }, p.str("district"), town).joinToString(", "), c[1].asDouble, c[0].asDouble)
    }.distinctBy { it.name to it.detail } // Photon often has a square and its street twice, same text: one is enough
}.getOrDefault(emptyList())

/** Arrow for the banner. Roundabouts in Germany turn counter-clockwise. */
fun arrow(type: Int): String = when (type) {
    10 -> "↱"; 15 -> "↰"
    9, 18, 20, 23, 37 -> "↗"; 16, 19, 21, 24, 38 -> "↖"
    11 -> "↘"; 14 -> "↙"; 12, 13 -> "↶"
    26, 27 -> "↺"; 4, 5, 6 -> "⚑"
    else -> "↑"
}

/** The spoken part for a maneuver (a clip in assets/sounds); null = nothing to say, like "continue on". */
fun clipFor(m: Maneuver): String? = when (m.type) {
    9 -> "nav_slight_right"; 10 -> "nav_right"; 11 -> "nav_sharp_right"; 12, 13 -> "nav_uturn"
    14 -> "nav_sharp_left"; 15 -> "nav_left"; 16 -> "nav_slight_left"
    17, 22 -> "nav_straight"; 18 -> "nav_ramp_right"; 19 -> "nav_ramp_left"; 20 -> "nav_exit_right"; 21 -> "nav_exit_left"
    23 -> "nav_keep_right"; 24 -> "nav_keep_left"; 25, 37, 38 -> "nav_merge"
    26 -> m.exit?.takeIf { it in 1..5 }?.let { "nav_round$it" } ?: "nav_round"
    4, 5, 6 -> "nav_dest"
    else -> null
}

/** next: the maneuver coming up. say: clip keys to play now, in order. */
data class Progress(val next: Maneuver?, val toNext: Double, val left: Double, val leftSeconds: Double,
                    val offRoute: Boolean, val arrived: Boolean, val say: List<String>)

const val OFF_ROUTE_M = 40.0
private val BUCKETS = listOf(100, 200, 300, 500, 800, 1000, 2000)

/** Follows one route fix by fix: where you are on it, what comes next, when to announce it. */
class Guide(val route: Route) {
    private var seg = 0
    private var far = -1 // maneuvers already announced from afar / at the turn
    private var near = -1
    private var off = 0
    private var arrived = false

    fun update(lat: Double, lon: Double, speedKmh: Float?): Progress {
        val shape = route.shape
        val (dist, along) = if (shape.size < 2) distanceMeters(lat, lon, shape[0].first, shape[0].second) to 0.0 else snap(lat, lon)
        off = if (dist > OFF_ROUTE_M) off + 1 else 0
        val idx = route.maneuvers.indexOfFirst { it.type !in 1..3 && route.cum[it.at.coerceIn(0, shape.lastIndex)] > along + 5 }
        val m = route.maneuvers.getOrNull(idx)
        val toNext = m?.let { route.cum[it.at.coerceIn(0, shape.lastIndex)] - along } ?: 0.0
        val left = (route.meters - along).coerceAtLeast(0.0)
        val say = mutableListOf<String>()
        val v = maxOf(8.0, (speedKmh ?: 0f) / 3.6)
        val nearT = maxOf(30.0, v * 4)
        val dest = m == null || m.type in 4..6
        if (!arrived && off == 0 && dest && toNext <= 30) {
            arrived = true; say += "nav_arrived"
        } else if (m != null && off == 0) {
            val clip = clipFor(m)
            if (clip != null && !dest && idx != near && toNext <= nearT) {
                near = idx; far = idx; say += listOf("nav_now", clip)
            } else if (clip != null && idx != far && toNext > nearT) {
                val farT = (v * 30).coerceIn(300.0, 2000.0)
                val b = BUCKETS.lastOrNull { it <= minOf(farT, toNext + 20) }
                if (b != null && toNext <= b + 20) { far = idx; say += listOf("nav_d$b", clip) }
            }
        }
        val share = if (route.meters > 0) left / route.meters else 0.0
        return Progress(m, toNext, left, route.seconds * share, off >= 3, arrived, say)
    }

    /** Distance to the route and metres along it. Searches near the last spot, so a parallel road ahead can't capture you. */
    private fun snap(lat: Double, lon: Double): Pair<Double, Double> {
        val shape = route.shape
        val kx = 111_320.0 * cos(Math.toRadians(lat)); val ky = 111_320.0
        var best = Double.MAX_VALUE; var bestSeg = seg; var bestT = 0.0
        for (i in maxOf(0, seg - 3)..minOf(shape.size - 2, seg + 80)) {
            val ax = (shape[i].second - lon) * kx; val ay = (shape[i].first - lat) * ky
            val bx = (shape[i + 1].second - lon) * kx; val by = (shape[i + 1].first - lat) * ky
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < best) { best = d; bestSeg = i; bestT = t }
        }
        if (best <= OFF_ROUTE_M) seg = bestSeg // off the route: keep the old spot until the new route comes
        return best to route.cum[bestSeg] + bestT * (route.cum[bestSeg + 1] - route.cum[bestSeg])
    }
}
