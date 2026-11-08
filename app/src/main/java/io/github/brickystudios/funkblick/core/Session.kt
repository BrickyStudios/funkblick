package io.github.brickystudios.funkblick.core

/** Which findings may ring. Classification alone never does. */
data class AlertSettings(
    val trackers: Boolean = true,
    val cameras: Boolean = true,
    // Off by default and behind a warning: drivers in Germany must not use functions that point out traffic
    // enforcement (StVO § 23 Abs. 1c). Covers public safety gear, vehicle routers and speed cameras.
    val traffic: Boolean = false,
)

enum class AlertReason { WATCH, TRACKER_FOLLOWING, POLICE_PROBABLE, PRIORITY, CAMERA, FLEET, VEHICLE_FOLLOWING }

data class Alert(val device: Device, val reason: AlertReason)

const val FRESH_MS = 30_000L // older observations come from Android's scan cache
const val COOLDOWN_MS = 5 * 60_000L
const val FOLLOW_MS = 10 * 60_000L // a separated tag this long nearby is following you
const val GAP_MS = 5 * 60_000L // absent longer than this, the clock starts again
const val PLACE_METERS = 300.0 // two sightings this far apart count as two places
const val FOLLOW_PLACES = 3 // with GPS: a tag has to show up at this many places, like AirGuard does
const val VEHICLE_FOLLOW_MS = 4 * 60_000L // a car's Wi-Fi this long behind you, across 3 places
const val OWN_WINDOW_MS = 2 * 60_000L // there within the first 2 minutes ...
const val OWN_RSSI = -55 // ... and this strong: your own car or phone, never a follower
const val DRIVING_KMH = 8f
const val CAMERA_DRIVING_MS = 60_000L // driving, a camera has to stay this long: house cameras by the road pass in seconds

/**
 * All transmitters of one scanning session, in memory only. Feed it sightings; it returns an alert when one is due.
 * Not thread-safe: the scan service calls it from the main thread.
 */
class Session(private val classifier: Classifier, private val now: () -> Long = System::currentTimeMillis) {

    val devices = LinkedHashMap<String, Device>()
    private val lastObservation = HashMap<String, Long>()
    private val streakStart = HashMap<String, Long>()
    private val lastAlert = HashMap<String, Long>()
    private val lastSighting = HashMap<String, Sighting>() // BLE payloads, for reclassify()
    private val places = HashMap<String, MutableList<Pair<Double, Double>>>()
    private val readings = HashMap<String, MutableList<Reading>>()
    private var lastLat: Double? = null // for reclassify(): the NRW fleet rule needs to know where
    private var lastLon: Double? = null
    var startedAt = now()
        private set

    fun clear() {
        devices.clear(); lastObservation.clear(); streakStart.clear(); lastAlert.clear(); lastSighting.clear(); places.clear(); readings.clear()
        startedAt = now()
    }

    /** lat/lon: where the phone was, if GPS knows. With places, a tracker must follow you somewhere to count. */
    fun add(s: Sighting, rules: List<Rule>, settings: AlertSettings, lat: Double? = null, lon: Double? = null, speedKmh: Float? = null): Alert? {
        val id = deviceId(s.radio, s.address)
        // replay protection: Wi-Fi scans hand back cached results with their old timestamp
        val prevObs = lastObservation[id]
        if (prevObs != null && s.timeMs <= prevObs) return null
        lastObservation[id] = s.timeMs

        val cls = classifier.classify(s, rules)
        val old = devices[id]
        var pos = old?.pos
        if (lat != null && lon != null) {
            lastLat = lat; lastLon = lon
            val list = places.getOrPut(id) { mutableListOf() }
            if (list.none { (a, b) -> distanceMeters(a, b, lat, lon) < PLACE_METERS }) list += lat to lon
            val r = readings.getOrPut(id) { mutableListOf() }
            if (r.addReading(Reading(lat, lon, s.rssi))) pos = estimatePosition(r)
        }
        val own = old?.own ?: (s.timeMs - startedAt <= OWN_WINDOW_MS && s.rssi >= OWN_RSSI)
        val d = Device(
            radio = s.radio, address = s.address, name = s.name ?: old?.name,
            rssi = s.rssi, rssiMax = maxOf(s.rssi, old?.rssiMax ?: s.rssi),
            freqMhz = s.freqMhz, security = s.security ?: old?.security,
            firstSeen = old?.firstSeen ?: s.timeMs, lastSeen = s.timeMs,
            seenCount = (old?.seenCount ?: 0) + 1, cls = cls, places = places[id]?.size ?: 0, own = own, pos = pos,
        )
        val withPolice = d.copy(police = policeFor(d, devices.values, lat, lon))
        devices[id] = withPolice
        lastSighting[id] = s
        if (prevObs == null || s.timeMs - prevObs > GAP_MS) streakStart[id] = s.timeMs

        val fresh = now() - s.timeMs <= FRESH_MS
        val reason = reasonFor(withPolice, settings, s.timeMs - (streakStart[id] ?: s.timeMs), (speedKmh ?: 0f) > DRIVING_KMH)
        if (!fresh || reason == null) return null
        val key = "$id/$reason"
        if ((lastAlert[key] ?: Long.MIN_VALUE / 2) + COOLDOWN_MS > now()) return null
        lastAlert[key] = now()
        return Alert(withPolice, reason)
    }

    private fun reasonFor(d: Device, settings: AlertSettings, streakMs: Long, driving: Boolean): AlertReason? {
        val c = d.cls
        if (c.mute != null) return null // mute wins over everything, also over watch
        if (c.watch != null) return AlertReason.WATCH
        val t = c.tracker
        // without GPS the 10 minutes alone decide; with GPS the tag also has to come along to 3 places
        val moved = d.places == 0 || d.places >= FOLLOW_PLACES
        if (settings.trackers && t != null && t.kind.isTag && t.separated == true && streakMs >= FOLLOW_MS && moved) return AlertReason.TRACKER_FOLLOWING
        if (settings.cameras && c.category == Category.CAMERA && (!driving || streakMs >= CAMERA_DRIVING_MS)) return AlertReason.CAMERA
        if (settings.traffic && d.police?.level == PoliceLevel.PROBABLE) return AlertReason.POLICE_PROBABLE
        if (settings.traffic && c.category == Category.PRIORITY) return AlertReason.PRIORITY
        if (settings.traffic && d.isFollowingVehicle(streakMs)) return AlertReason.VEHICLE_FOLLOWING
        if (settings.traffic && c.category == Category.FLEET) return AlertReason.FLEET
        return null
    }

    /** Re-run classification after the rules changed, so watch/mute show up without waiting for the next scan. */
    fun reclassify(rules: List<Rule>) {
        for ((id, d) in devices) lastSighting[id]?.let {
            val n = d.copy(cls = classifier.classify(it, rules))
            devices[id] = n.copy(police = policeFor(n, devices.values, lastLat, lastLon)) // a police mark shows at once
        }
    }
}

/**
 * A vehicle's Wi-Fi that comes along: seen at 3 places over 4 minutes, not your own car. Wi-Fi only, because Bluetooth
 * addresses of phones change every few minutes and passengers in the next car would count too.
 */
fun Device.isFollowingVehicle(streakMs: Long = lastSeen - firstSeen): Boolean =
    radio == Radio.WIFI && !own && cls.mute == null && places >= FOLLOW_PLACES && streakMs >= VEHICLE_FOLLOW_MS &&
        cls.category in setOf(Category.CAR, Category.FLEET, Category.PRIORITY, Category.UNKNOWN, Category.PERSONAL)

/** Minutes a separated tracker has been around, for the "following you" banner. */
fun Device.followingMinutes(): Long? {
    val t = cls.tracker ?: return null
    if (!t.kind.isTag || t.separated != true) return null
    return (lastSeen - firstSeen) / 60_000
}

fun AlertReason.label(): String = when (this) {
    AlertReason.WATCH -> tr("Beobachtetes Gerät in der Nähe", "Watched device nearby")
    AlertReason.TRACKER_FOLLOWING -> tr("Tracker begleitet dich seit über 10 Minuten", "A tracker has been with you for over 10 minutes")
    AlertReason.POLICE_PROBABLE -> tr("Wahrscheinlich Polizei in der Nähe", "Probably police nearby")
    AlertReason.PRIORITY -> tr("Mögliche Behörden-/Einsatztechnik in der Nähe", "Possible public safety gear nearby")
    AlertReason.VEHICLE_FOLLOWING -> tr("Ein Fahrzeug fährt seit Minuten mit dir", "A vehicle has been with you for minutes")
    AlertReason.CAMERA -> tr("Kamera in der Nähe", "Camera nearby")
    AlertReason.FLEET -> tr("Fahrzeug-/Mobilfunkrouter in der Nähe", "Vehicle / cellular router nearby")
}
