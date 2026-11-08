package io.github.brickystudios.funkblick.core

/** The numbers of one session for the trip review (plan F1, F4). Your own devices are left out. */
data class TripStats(
    val millis: Long, // the whole session, own devices included
    val km: Double,
    val devices: Int,
    val wifi: Int,
    val ble: Int,
    val openPercent: Int?, // share of Wi-Fi networks without encryption; null without Wi-Fi
    val perKm: Int?, // devices per km; null under one km, where it says nothing
    val carWifi: Int,
    val followed: Int,
    val police: Int,
    val cameras: Int,
    val trackers: Int, // trackers away from their owner
    val makers: List<Pair<String, Int>>, // the five makers with the most devices
)

fun trackKm(track: List<TrackPoint>): Double = track.zipWithNext { a, b -> distanceMeters(a.lat, a.lon, b.lat, b.lon) }.sum() / 1000

fun tripStats(all: Collection<Device>, track: List<TrackPoint>): TripStats? {
    if (all.isEmpty()) return null
    val ds = all.filter { !it.own }
    val km = trackKm(track)
    val wifi = ds.filter { it.radio == Radio.WIFI }
    return TripStats(
        millis = all.maxOf { it.lastSeen } - all.minOf { it.firstSeen },
        km = km,
        devices = ds.size,
        wifi = wifi.size,
        ble = ds.size - wifi.size,
        openPercent = if (wifi.isEmpty()) null else wifi.count { it.security == Security.OPEN } * 100 / wifi.size,
        perKm = if (km < 1) null else (ds.size / km).toInt(),
        carWifi = ds.count { it.cls.category == Category.CAR },
        followed = ds.count { it.isFollowingVehicle() },
        police = ds.count { it.police != null },
        cameras = ds.count { it.cls.category == Category.CAMERA },
        trackers = ds.count { it.cls.category == Category.TRACKER && it.cls.tracker?.separated == true },
        makers = ds.mapNotNull { it.cls.vendor?.let(::shortMaker) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
    )
}

/** "Apple, Inc." reads as "Apple": the registry's legal suffixes add nothing on a phone screen. */
fun shortMaker(v: String): String =
    v.replace(Regex("""[,.]?\s+(inc|ltd|llc|gmbh|co|corp|corporation|limited|ag|sa|bv|technologies|technology)\b\.?.*$""", RegexOption.IGNORE_CASE), "")
        .trim().ifEmpty { v }
