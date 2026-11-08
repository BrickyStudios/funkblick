package io.github.brickystudios.funkblick.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

// Fixed speed cameras in Germany from OpenStreetMap (assets/speedcams.tsv, tools/build_speedcams.py), ODbL.
// Bundled on purpose: the warning works offline and your position never leaves the phone.

/** kind: null for a speed camera; "cam", "alpr" or "sensor" for the traffic cameras and counters in assets/trafficcams.tsv. */
data class SpeedCam(val lat: Double, val lon: Double, val maxspeed: Int?, val kind: String? = null)

data class CamAhead(val cam: SpeedCam, val meters: Int)

const val WARN_METERS = 800

fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/**
 * Overpass answer (node["highway"="speed_camera"]) -> the same TSV lines as assets/speedcams.tsv.
 * Used by the in-app refresh for your region; tools/build_speedcams.py does the same for all of Germany.
 */
fun overpassToTsv(json: String): List<String> {
    val els = runCatching { com.google.gson.JsonParser.parseString(json).asJsonObject.getAsJsonArray("elements") }.getOrNull() ?: return emptyList()
    return els.mapNotNull { e ->
        val o = e.asJsonObject
        val lat = o.get("lat")?.asDouble ?: return@mapNotNull null
        val lon = o.get("lon")?.asDouble ?: return@mapNotNull null
        val speed = o.getAsJsonObject("tags")?.get("maxspeed")?.asString?.let { Regex("^\\d+").find(it)?.value } ?: ""
        "%.5f\t%.5f\t%s\t".format(java.util.Locale.ROOT, lat, lon, speed)
    }
}

/** Overpass query for a box of about 130 × 130 km around you. */
fun overpassQuery(lat: Double, lon: Double): String {
    val dLat = 0.6
    val dLon = 0.9
    return "[out:json][timeout:60];node[\"highway\"=\"speed_camera\"](%.3f,%.3f,%.3f,%.3f);out body;"
        .format(java.util.Locale.ROOT, lat - dLat, lon - dLon, lat + dLat, lon + dLon)
}

class SpeedCamDb(lines: Sequence<String>) {
    // 0.1° cells (about 11 × 7 km in Germany): a lookup only touches the 9 cells around you
    private val grid = HashMap<Long, MutableList<SpeedCam>>()

    init {
        val seen = HashSet<String>()
        for (line in lines) {
            if (!seen.add(line.split('\t').take(2).joinToString(","))) continue // bundled + refreshed overlap
            val p = line.split('\t')
            val lat = p.getOrNull(0)?.toDoubleOrNull() ?: continue
            val lon = p.getOrNull(1)?.toDoubleOrNull() ?: continue
            grid.getOrPut(cell(lat, lon)) { mutableListOf() } += SpeedCam(lat, lon, p.getOrNull(2)?.toIntOrNull(), p.getOrNull(4)?.takeIf { it.isNotEmpty() })
        }
    }

    val size get() = grid.values.sumOf { it.size }

    private fun cell(lat: Double, lon: Double) = floor(lat * 10).toLong() * 10_000 + floor(lon * 10).toLong()

    /** Nearest camera within [WARN_METERS]; with a heading (driving), only cameras in front within ±50°. */
    fun ahead(lat: Double, lon: Double, heading: Double?): CamAhead? {
        var best: CamAhead? = null
        for (dy in -1..1) for (dx in -1..1) {
            val list = grid[cell(lat + dy / 10.0, lon + dx / 10.0)] ?: continue
            for (c in list) {
                val m = distanceMeters(lat, lon, c.lat, c.lon)
                if (m > WARN_METERS || (best != null && m >= best.meters)) continue
                if (heading != null && m > 30) {
                    val diff = abs(((bearingDegrees(lat, lon, c.lat, c.lon) - heading + 540) % 360) - 180)
                    if (diff > 50) continue
                }
                best = CamAhead(c, m.toInt())
            }
        }
        return best
    }

    /** All cameras within [meters], for drawing them on the map. */
    fun near(lat: Double, lon: Double, meters: Double): List<SpeedCam> {
        val cells = (meters / 7_000).toInt() + 1
        val out = mutableListOf<SpeedCam>()
        for (dy in -cells..cells) for (dx in -cells..cells)
            grid[cell(lat + dy / 10.0, lon + dx / 10.0)]?.filterTo(out) { distanceMeters(lat, lon, it.lat, it.lon) <= meters }
        return out
    }

    companion object {
        val EMPTY = SpeedCamDb(emptySequence())
    }
}
