package io.github.brickystudios.funkblick.core

// Attack tools and card skimmers. Signatures and their grading after SquachWatch's DETECTIONS.md (GPL-3.0) as used
// by RF Sentinel (github.com/CIS-C0/RFSentinel, GPL-3.0), re-checked against the IEEE and Bluetooth SIG registries.
// Receive-only like the rest of Funkblick: it only reads what these devices broadcast.

private val FLIPPER_UUIDS = setOf(0x3081, 0x3082, 0x3083) // one per case colour
private const val CID_FLIPPER = 0x0E29 // Flipper Devices Inc.
private const val OUI_FLIPPER = "0CFA22"
private val SKIMMER_NAME = Regex("^(HC-0[3568]|HM-10|RN42|BT04-A)\\b", RegexOption.IGNORE_CASE)

/** One advert on its own: Flipper Zero, WiFi Pineapple, ESP deauther, the Bluetooth modules card skimmers are built from. */
fun hackerSignature(s: Sighting): PayloadHit? {
    val name = s.name?.trim().orEmpty()
    if (s.radio == Radio.BLE) {
        val shorts = s.serviceUuids + s.serviceData.keys
        if (shorts.any { it in FLIPPER_UUIDS } || CID_FLIPPER in s.manufacturerData || hexOf(s.address)?.startsWith(OUI_FLIPPER) == true)
            return PayloadHit(Category.HACKER, false, tr("Flipper Zero: Funk-Werkzeug, das Bluetooth-Spam, Funkschlüssel und Karten nachahmen kann",
                "Flipper Zero: a radio tool that can spam Bluetooth and mimic remotes and cards"))
        if (name.startsWith("Flipper ")) return PayloadHit(Category.HACKER, false,
            tr("Name „$name“ wie bei einem Flipper Zero (umbenennbar, also prüfen)", "Name \"$name\" like a Flipper Zero (can be renamed, so verify)"))
        if (SKIMMER_NAME.containsMatchIn(name)) return PayloadHit(Category.HACKER, false,
            tr("Billiges Bluetooth-Modul („$name“), wie es in Karten-Skimmern an Tankstellen und Automaten steckt. Bastler nutzen es auch: Kartenleser prüfen",
                "Cheap Bluetooth module (\"$name\") as found in card skimmers at pumps and ATMs. Hobbyists use it too: check the card reader"))
    } else {
        if (name.startsWith("Pineapple_")) return PayloadHit(Category.HACKER, false,
            tr("WiFi Pineapple (Hak5): fängt WLAN-Verbindungen ab", "WiFi Pineapple (Hak5): intercepts Wi-Fi connections"))
        if (name.startsWith("pwned")) return PayloadHit(Category.HACKER, false,
            tr("Deauther (ESP8266/ESP32): wirft Geräte aus dem WLAN", "Deauther (ESP8266/ESP32): kicks devices off Wi-Fi"))
    }
    return null
}

/** Pairing pop-ups: Apple proximity pairing and nearby action, Microsoft Swift Pair, Google Fast Pair. Flipper and ESP32 spam fakes them. */
fun Sighting.isPairingPopup(): Boolean {
    manufacturerData[APPLE]?.let { if (it.isNotEmpty() && (it[0].toInt() == 0x07 || it[0].toInt() == 0x0F)) return true }
    manufacturerData[0x0006]?.let { if (it.isNotEmpty() && it[0].toInt() == 0x03) return true }
    return 0xFE2C in serviceData.keys
}

const val SPAM_ADDRESSES = 50 // new addresses with pairing pop-ups within a minute
const val SPAM_WINDOW_MS = 60_000L
const val SPAM_PAUSE_MS = 10 * 60_000L

/** Counts new Bluetooth addresses that ask to pair. A flood of them is a Bluetooth spam attack. */
class SpamCounter {
    private val seen = LinkedHashMap<String, Long>()
    private var lastFlood = Long.MIN_VALUE / 2

    /** True once per flood: [address] is new and the last minute brought [SPAM_ADDRESSES] of them. */
    fun add(address: String, now: Long): Boolean {
        val it = seen.values.iterator()
        while (it.hasNext() && now - it.next() > SPAM_WINDOW_MS) it.remove()
        if (seen.put(address, now) != null) return false
        if (seen.size < SPAM_ADDRESSES || now - lastFlood < SPAM_PAUSE_MS) return false
        lastFlood = now
        return true
    }

    fun clear() { seen.clear(); lastFlood = Long.MIN_VALUE / 2 }
}

const val TWIN_WINDOW_MS = 5 * 60_000L

/**
 * Evil twin: one network name from two makers' hardware, one open and one protected, both on air within five minutes.
 * One of them isn't what it claims to be; the open one is the likely fake. Mesh nodes and dual-band boxes share a maker.
 */
class TwinWatch {
    private class Ap(val id: String, val maker: String, var open: Boolean, var seen: Long)
    private val bySsid = LinkedHashMap<String, MutableList<Ap>>()
    private val flagged = HashSet<String>()

    /** Returns the id of the open copy when [d] completes a twin, once per network name. */
    fun add(d: Device): String? {
        val ssid = d.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sec = d.security ?: return null
        if (d.radio != Radio.WIFI) return null
        val open = sec == Security.OPEN
        val maker = d.cls.vendor ?: hexOf(d.address)?.take(6) ?: return null
        val list = bySsid.getOrPut(ssid) { mutableListOf() }
        if (bySsid.size > 400) bySsid.remove(bySsid.keys.first())
        list.firstOrNull { it.id == d.id }?.let { it.open = open; it.seen = d.lastSeen; return null }
        if (list.size >= 64) list.removeAt(0) // a hotspot chain has thousands of access points
        val other = list.firstOrNull { d.lastSeen - it.seen < TWIN_WINDOW_MS && it.maker != maker && it.open != open }
        list += Ap(d.id, maker, open, d.lastSeen)
        if (other == null || !flagged.add(ssid)) return null
        return if (open) d.id else other.id
    }

    fun clear() { bySsid.clear(); flagged.clear() }
}
