package io.github.brickystudios.funkblick.core

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import com.google.gson.annotations.SerializedName

// Where is a fixed transmitter (router, camera)? A phone can't measure direction, but it can measure signal
// strength at many GPS points while you move. The usual answer from wardriving: a weighted centroid of those points,
// stronger readings counting more, outliers dropped. Good enough to put a router on the right block; when you only
// drive past on one side, the estimate leans towards the road. Moving things (cars) don't get a position.

data class Reading(val lat: Double, val lon: Double, val rssi: Int)

/** radiusM: rough uncertainty, the spread of the readings that carried the estimate. */
data class Estimate( // in route files too: see TrackPoint for the alternate names
    @field:SerializedName("lat", alternate = ["a"]) val lat: Double,
    @field:SerializedName("lon", alternate = ["b"]) val lon: Double,
    @field:SerializedName("radiusM", alternate = ["c"]) val radiusM: Double,
    @field:SerializedName("points", alternate = ["d"]) val points: Int,
)

const val MIN_SPREAD_M = 50.0 // readings must cover at least this much ground
const val MAX_READINGS = 40

/** Distance from signal strength, log-distance path loss with -40 dBm at 1 m and exponent 2.7 (streets, buildings). */
fun distanceFromRssi(rssi: Int): Double = 10.0.pow((-40.0 - rssi) / (10 * 2.7))

fun estimatePosition(readings: List<Reading>): Estimate? {
    if (readings.size < 3) return null
    // spread as the diagonal of the bounding box: cheap enough to run on every new reading
    val spread = distanceMeters(readings.minOf { it.lat }, readings.minOf { it.lon }, readings.maxOf { it.lat }, readings.maxOf { it.lon })
    if (spread < MIN_SPREAD_M) return null
    // Outliers (a GPS jump, a reflection) are judged against the median point, not the weighted centroid:
    // one bogus strong reading would otherwise pull the centroid onto itself and survive its own test.
    val n = readings.size
    val mLat = readings.map { it.lat }.sorted()[n / 2]
    val mLon = readings.map { it.lon }.sorted()[n / 2]
    val d = readings.map { distanceMeters(it.lat, it.lon, mLat, mLon) }
    val med = d.sorted()[n / 2]
    val mad = d.map { kotlin.math.abs(it - med) }.sorted()[n / 2]
    val kept = readings.filterIndexed { i, _ -> d[i] <= med + 3 * maxOf(mad, 20.0) }
    val use = if (kept.size >= 3) kept else readings
    val est = centroid(use)
    val w = use.map { weight(it.rssi) }
    val rms = sqrt(use.indices.sumOf { i -> w[i] * distanceMeters(use[i].lat, use[i].lon, est.first, est.second).pow(2) } / w.sum())
    return Estimate(est.first, est.second, maxOf(25.0, rms * 0.6), use.size)
}

// capped at 5 m: a reading right next to the transmitter shouldn't outweigh all others a thousandfold
private fun weight(rssi: Int): Double = 1.0 / maxOf(5.0, distanceFromRssi(rssi)).pow(2)

private fun centroid(r: List<Reading>): Pair<Double, Double> {
    val w = r.map { weight(it.rssi) }
    val sum = w.sum()
    return r.indices.sumOf { r[it].lat * w[it] } / sum to r.indices.sumOf { r[it].lon * w[it] } / sum
}

/** Keeps a reading only when it adds something: moved 10 m or the signal changed. Oldest drop out at MAX_READINGS. */
fun MutableList<Reading>.addReading(r: Reading): Boolean {
    val last = lastOrNull()
    if (last != null && distanceMeters(last.lat, last.lon, r.lat, r.lon) < 10 && kotlin.math.abs(last.rssi - r.rssi) < 3) return false
    add(r)
    if (size > MAX_READINGS) removeAt(0)
    return true
}

/** Metres to degrees around a latitude, for tests and map offsets. */
fun metersToLat(m: Double) = m / 111_320.0
fun metersToLon(m: Double, lat: Double) = m / (111_320.0 * cos(Math.toRadians(lat)))

/** The spot [meters] away at a radar angle (0° = east, then clockwise as on screen): devices without an estimate on the map. */
fun spotAt(lat: Double, lon: Double, meters: Double, radarDeg: Double): Pair<Double, Double> {
    val a = Math.toRadians(radarDeg)
    return lat - metersToLat(meters * sin(a)) to lon + metersToLon(meters * cos(a), lat)
}
