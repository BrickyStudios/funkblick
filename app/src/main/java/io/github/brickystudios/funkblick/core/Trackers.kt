package io.github.brickystudios.funkblick.core

// Item trackers announce themselves in their BLE advertisement. Formats as documented by the AirGuard project
// (TU Darmstadt, seemoo-lab/AirGuard) and the Google Find My Device Network spec.

const val APPLE = 0x004C
const val UUID_SAMSUNG = 0xFD5A
const val UUID_TILE = 0xFEED
const val UUID_CHIPOLO = 0xFE33
const val UUID_GOOGLE_FMDN = 0xFEAA // shared with Eddystone; frame type 0x40/0x41 means Find My Device Network
const val UUID_PEBBLEBEE = 0xFA25

/** Service UUIDs used for the screen-off scan filter (Android stops unfiltered scans while the screen is off). */
val TRACKER_SERVICE_UUIDS = listOf(UUID_SAMSUNG, UUID_TILE, UUID_CHIPOLO, UUID_GOOGLE_FMDN, UUID_PEBBLEBEE)

fun parseTracker(s: Sighting): Tracker? {
    if (s.radio != Radio.BLE) return null
    s.manufacturerData[APPLE]?.let { d ->
        // 0x12 = Find My "offline finding"; length 0x19 = full public key, sent when away from the owner
        if (d.size >= 3 && d[0] == 0x12.toByte()) {
            val separated = d[1] == 0x19.toByte()
            val kind = when ((d[2].toInt() and 0x30) shr 4) {
                1 -> TrackerKind.AIRTAG
                2 -> TrackerKind.APPLE_FIND_MY
                3 -> TrackerKind.AIRPODS
                else -> TrackerKind.APPLE_DEVICE
            }
            return Tracker(kind, separated)
        }
    }
    s.serviceData[UUID_GOOGLE_FMDN]?.let { d ->
        // 0x41 = unwanted-tracking protection mode, switched on when the tag is away from its owner
        if (d.isNotEmpty() && (d[0] == 0x40.toByte() || d[0] == 0x41.toByte()))
            return Tracker(TrackerKind.GOOGLE_FIND_MY, d[0] == 0x41.toByte())
    }
    fun has(uuid: Int) = uuid in s.serviceUuids || s.serviceData.containsKey(uuid)
    return when {
        has(UUID_SAMSUNG) -> Tracker(TrackerKind.SAMSUNG_SMARTTAG, samsungSeparated(s.serviceData[UUID_SAMSUNG]))
        has(UUID_TILE) -> Tracker(TrackerKind.TILE, null)
        has(UUID_CHIPOLO) -> Tracker(TrackerKind.CHIPOLO, null)
        has(UUID_PEBBLEBEE) -> Tracker(TrackerKind.PEBBLEBEE, null)
        else -> null
    }
}

/** SmartTag: the low 3 bits of the first byte are the state; 1, 2, 3 = offline for a while, anything else = with its phone. */
private fun samsungSeparated(d: ByteArray?): Boolean? {
    if (d == null || d.isEmpty()) return null
    return (d[0].toInt() and 0x07) in 1..3
}

/** Phones, laptops and AirPods also speak Find My; only tags are built to hide in a bag. */
val TrackerKind.isTag get() = this != TrackerKind.APPLE_DEVICE && this != TrackerKind.AIRPODS
