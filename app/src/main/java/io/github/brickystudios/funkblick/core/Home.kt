package io.github.brickystudios.funkblick.core

/**
 * Home watch (plan F3): remember a place by the Wi-Fi networks that are always there, then report only what is new
 * there: a camera, a tag that stays, a device right next to you. The place is those networks, no coordinates: it works
 * indoors without GPS, and where you live is never written down. Stored on the phone only, never exported.
 */
data class HomeWatch(
    val anchors: Set<String>, // device ids of the strongest fixed Wi-Fi when you marked the place
    val known: Set<String>, // everything seen there while learning, plus what you accepted later
    val learnUntil: Long,
)

const val HOME_LEARN_MS = 10 * 60_000L
const val HOME_PRESENT_MS = 2 * 60_000L // an anchor heard this recently: you are there
const val HOME_STRONG_RSSI = -55 // right next to you: in the flat, not at the neighbours'
const val HOME_TAG_RSSI = -70
const val HOME_TAG_MS = 10 * 60_000L // a tag away from its owner, this long at your place

/**
 * The three strongest Wi-Fi networks heard in the last minute. Hotspots, vehicles and cameras can travel: a camera you
 * take to a holiday flat would make the flat look like home, so only what stays put marks the place.
 */
fun homeAnchors(devices: Collection<Device>, now: Long): Set<String> = devices
    .filter { it.radio == Radio.WIFI && now - it.lastSeen < 60_000 && it.cls.category !in setOf(Category.PERSONAL, Category.CAR, Category.FLEET, Category.CAMERA) }
    .sortedByDescending { it.rssi }.take(3).map { it.id }.toSet()

/** Can be recognised next time: Wi-Fi, or Bluetooth with a fixed address. Phones change theirs every few minutes. */
fun Device.fixedAddress() = radio == Radio.WIFI || !cls.randomAddress

/** Worth a word at your place, if it wasn't there while learning. [streakMs]: how long it has been around without a gap. */
fun Device.newAtHomeMatters(streakMs: Long): Boolean {
    if (cls.mute != null) return false
    val t = cls.tracker
    if (t != null && t.kind.isTag) return t.separated == true && rssi >= HOME_TAG_RSSI && streakMs >= HOME_TAG_MS
    if (!fixedAddress()) return false
    return cls.category == Category.CAMERA || rssi >= HOME_STRONG_RSSI
}
