package io.github.brickystudios.funkblick.core

import java.util.Locale

/** Funkblick speaks German on German phones and English everywhere else. */
fun isGerman(): Boolean = Locale.getDefault().language == "de"

fun tr(de: String, en: String): String = if (isGerman()) de else en

enum class Radio { WIFI, BLE }

enum class Security { OPEN, OWE, WEP, WPA, WPA2, WPA3 }

/** One observation from one scan, already free of Android types so the logic runs in unit tests. */
class Sighting(
    val radio: Radio,
    val address: String, // "AA:BB:CC:DD:EE:FF"
    val name: String?, // SSID or BLE local name
    val rssi: Int,
    val timeMs: Long, // wall clock of the radio observation, not of the callback
    val freqMhz: Int = 0, // Wi-Fi only
    val security: Security? = null, // Wi-Fi only
    val manufacturerData: Map<Int, ByteArray> = emptyMap(), // BLE company id -> payload
    val serviceData: Map<Int, ByteArray> = emptyMap(), // BLE 16-bit service UUID -> payload
    val serviceUuids: Set<Int> = emptySet(), // BLE 16-bit service UUIDs
)

enum class Category { TRACKER, PRIORITY, CAMERA, HACKER, FLEET, CAR, PUBLIC_WIFI, INFRA, HOME, PERSONAL, UNKNOWN }

enum class Confidence { LOW, MEDIUM, HIGH }

/** Where a classification came from: the IEEE registry says who owns a MAC block, a signature says what the
 *  equipment probably is, a payload is what the device itself broadcasts, a rule is the user's own. */
enum class Source { REGISTRY, SIGNATURE, PAYLOAD, NAME, RULE }

data class Evidence(val source: Source, val text: String, val confidence: Confidence)

enum class TrackerKind(val label: String) {
    AIRTAG("AirTag"),
    APPLE_FIND_MY("Apple Find My"),
    AIRPODS("AirPods"),
    APPLE_DEVICE("Apple"),
    SAMSUNG_SMARTTAG("Samsung SmartTag"),
    TILE("Tile"),
    CHIPOLO("Chipolo"),
    GOOGLE_FIND_MY("Google Find My"),
    PEBBLEBEE("Pebblebee"),
}

/** separated: true = away from its owner (the case that matters for stalking), null = the payload doesn't say. */
data class Tracker(val kind: TrackerKind, val separated: Boolean?)

data class Classification(
    val vendor: String?,
    val randomAddress: Boolean,
    val category: Category,
    val tracker: Tracker?,
    val evidence: List<Evidence>,
    val watch: Rule?,
    val mute: Rule?,
    val police: Rule? = null, // marked as police by you
)

/** Everything Funkblick knows about one transmitter during this session. */
data class Device(
    val radio: Radio,
    val address: String,
    val name: String?,
    val rssi: Int,
    val rssiMax: Int,
    val freqMhz: Int,
    val security: Security?,
    val firstSeen: Long,
    val lastSeen: Long,
    val seenCount: Int,
    val cls: Classification,
    val places: Int = 0, // distinct places (≥ PLACE_METERS apart) it was seen at, with GPS
    val own: Boolean = false, // there from the start of the session with a strong signal: your car, your phone
    val pos: Estimate? = null, // where a fixed transmitter probably is (Locate.kt), null until there are enough readings
    val police: Police? = null, // "possible" or "probable" police, with the reason (Police.kt)
) {
    val id get() = deviceId(radio, address)
}

fun deviceId(radio: Radio, address: String) = "${radio.name}/$address"

fun Category.label(): String = when (this) {
    Category.TRACKER -> tr("Tracker", "Tracker")
    Category.PRIORITY -> tr("Behörden- und Einsatztechnik", "Public safety gear")
    Category.CAMERA -> tr("Kamera", "Camera")
    Category.HACKER -> tr("Hacker-Werkzeug oder Skimmer", "Hacking tool or skimmer")
    Category.FLEET -> tr("Fahrzeug-/Mobilfunkrouter", "Vehicle / cellular router")
    Category.CAR -> tr("Auto (Infotainment-WLAN)", "Car (infotainment Wi-Fi)")
    Category.PUBLIC_WIFI -> tr("Öffentliches WLAN", "Public Wi-Fi")
    Category.INFRA -> tr("Firmen-Infrastruktur", "Business infrastructure")
    Category.HOME -> tr("Router / Zuhause", "Home / consumer router")
    Category.PERSONAL -> tr("Handy, Uhr, Kopfhörer", "Phone, watch, headphones")
    Category.UNKNOWN -> tr("Unbekannt", "Unknown")
}
