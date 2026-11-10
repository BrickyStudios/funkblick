package io.github.brickystudios.funkblick.core

// What Funkblick remembers between sessions (Store keeps it, keyed by a salted hash, never by address).
// A Find My tag away from its owner keeps its address for up to 24 hours: "it rode along this morning and is here again"
// is recognisable, "yesterday and today" mostly is not, the address has changed by then. Tile-like tags never change.

const val ALONG_MS = 5 * 60_000L // without GPS: a separated tag this long nearby came along
const val SESSIONS_KEPT = 10 // per device: the start times of the last sessions it was seen in

/**
 * A separated tag that came with you this session: to a second place 300 m on, or (no GPS) for 5 minutes.
 * A lost tag by the road you pass twice a day doesn't, so it never becomes "seen again".
 */
fun Device.cameAlong(streakMs: Long = lastSeen - firstSeen): Boolean {
    val t = cls.tracker ?: return false
    if (!t.kind.isTag || t.separated != true || cls.mute != null) return false
    return if (places > 0) places >= 2 else streakMs >= ALONG_MS
}

/** One entry per session: the session's start time, newest last. */
fun MutableList<Long>.addSession(start: Long) {
    if (lastOrNull() == start) return
    add(start)
    if (size > SESSIONS_KEPT) removeAt(0)
}

/** A camera from an earlier session, where its signal put it. label: its network name or maker. */
data class KnownCam(val lat: Double, val lon: Double, val radiusM: Double, val label: String, val lastSeen: Long)

/** The tighter of two estimates for the same camera, with the newer date. */
fun KnownCam?.merge(new: KnownCam): KnownCam =
    if (this == null || new.radiusM <= radiusM) new.copy(lastSeen = maxOf(new.lastSeen, this?.lastSeen ?: 0)) else copy(lastSeen = maxOf(lastSeen, new.lastSeen))
