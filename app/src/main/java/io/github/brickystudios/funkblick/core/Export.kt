package io.github.brickystudios.funkblick.core

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Safe mode (on by default): the manufacturer part of an address stays, the device part becomes a short hash that
// is stable within one session but can't be turned back into the address. Private network and device names go.

class Masker(private val safe: Boolean, private val salt: String) {
    fun address(a: String): String {
        if (!safe) return a
        val hex = hexOf(a) ?: return "••"
        val h = MessageDigest.getInstance("SHA-256").digest((salt + hex).toByteArray())
        val tag = h.take(2).joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 2)}:${hex.substring(2, 4)}:${hex.substring(4, 6)}:#$tag"
    }

    /** Names of public, business and vehicle networks say what the thing is; names of homes and phones say who. */
    fun name(d: Device): String? {
        val n = d.name ?: return null
        if (!safe) return n
        return when (d.cls.category) {
            Category.PUBLIC_WIFI, Category.INFRA, Category.FLEET, Category.CAR, Category.CAMERA, Category.TRACKER, Category.PRIORITY -> n
            else -> tr("(versteckt)", "(hidden)")
        }
    }
}

/** One alert in the session log. kind matches the sound file names: tracker, priority, speedcam, camera, fleet, watch. */
data class LoggedAlert(val time: Long, val kind: String, val text: String, val address: String?, val lat: Double?, val lon: Double?)

fun AlertReason.kind(): String = when (this) {
    AlertReason.WATCH -> "watch"
    AlertReason.TRACKER_FOLLOWING -> "tracker"
    AlertReason.TRACKER_AGAIN -> "again"
    AlertReason.POLICE_PROBABLE -> "police"
    AlertReason.PRIORITY -> "priority"
    AlertReason.CAMERA -> "camera"
    AlertReason.FLEET -> "fleet"
    AlertReason.VEHICLE_FOLLOWING -> "follow"
    AlertReason.HOME_NEW -> "home"
    AlertReason.HACKER, AlertReason.BLE_SPAM, AlertReason.EVIL_TWIN -> "hacker"
}

data class ExportMeta(val app: String, val build: String, val android: String, val startedAt: Long, val exportedAt: Long, val safe: Boolean)

private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date(ms))

private fun csvCell(s: String?): String {
    val v = s ?: ""
    // a leading = + - @ would run as a formula in Excel/LibreOffice
    val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
    return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
}

private val CSV_HEAD = listOf(
    "radio", "address", "vendor", "name", "category", "tracker", "separated", "watch", "mute",
    "rssi_last", "rssi_max", "freq_mhz", "channel", "security", "first_seen", "last_seen", "seen_count", "evidence",
)

fun toCsv(devices: Collection<Device>, m: Masker): String = buildString {
    append(CSV_HEAD.joinToString(",")).append("\r\n")
    for (d in devices) {
        val row = listOf(
            d.radio.name, m.address(d.address), d.cls.vendor, m.name(d), d.cls.category.name,
            d.cls.tracker?.kind?.label, d.cls.tracker?.separated?.toString(), (d.cls.watch != null).toString(), (d.cls.mute != null).toString(),
            d.rssi.toString(), d.rssiMax.toString(), d.freqMhz.takeIf { it > 0 }?.toString(), channelOf(d.freqMhz)?.toString(),
            d.security?.name, iso(d.firstSeen), iso(d.lastSeen), d.seenCount.toString(),
            d.cls.evidence.joinToString(" | ") { "${it.source}: ${it.text}" },
        )
        append(row.joinToString(",") { csvCell(it) }).append("\r\n")
    }
}

private fun jsonStr(s: String?): String = if (s == null) "null" else buildString {
    append('"')
    for (c in s) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c < ' ' -> append("\\u%04x".format(c.code))
        else -> append(c)
    }
    append('"')
}

fun toJson(devices: Collection<Device>, meta: ExportMeta, m: Masker, alerts: List<LoggedAlert> = emptyList()): String = buildString {
    append("{\n  \"app\": ${jsonStr(meta.app)},\n  \"build\": ${jsonStr(meta.build)},\n  \"android\": ${jsonStr(meta.android)},\n")
    append("  \"session_start\": ${jsonStr(iso(meta.startedAt))},\n  \"exported\": ${jsonStr(iso(meta.exportedAt))},\n")
    append("  \"safe_mode\": ${meta.safe},\n  \"devices\": [")
    devices.forEachIndexed { i, d ->
        if (i > 0) append(',')
        append("\n    {\"radio\": ${jsonStr(d.radio.name)}, \"address\": ${jsonStr(m.address(d.address))}, \"vendor\": ${jsonStr(d.cls.vendor)}, ")
        append("\"random_address\": ${d.cls.randomAddress}, \"name\": ${jsonStr(m.name(d))}, \"category\": ${jsonStr(d.cls.category.name)}, ")
        append("\"tracker\": ${jsonStr(d.cls.tracker?.kind?.label)}, \"separated\": ${d.cls.tracker?.separated}, ")
        append("\"watch\": ${d.cls.watch != null}, \"mute\": ${d.cls.mute != null}, \"rssi_last\": ${d.rssi}, \"rssi_max\": ${d.rssiMax}, ")
        append("\"freq_mhz\": ${d.freqMhz.takeIf { it > 0 }}, \"channel\": ${channelOf(d.freqMhz)}, \"security\": ${jsonStr(d.security?.name)}, ")
        append("\"first_seen\": ${jsonStr(iso(d.firstSeen))}, \"last_seen\": ${jsonStr(iso(d.lastSeen))}, \"seen_count\": ${d.seenCount}, \"places\": ${d.places}, \"own\": ${d.own}, \"police\": ${jsonStr(d.police?.level?.name)}, \"police_why\": ${jsonStr(d.police?.why)}, ")
        append("\"evidence\": [${d.cls.evidence.joinToString(", ") { "{\"source\": ${jsonStr(it.source.name)}, \"confidence\": ${jsonStr(it.confidence.name)}, \"text\": ${jsonStr(it.text)}}" }}]}")
    }
    append("\n  ],\n  \"alerts\": [")
    alerts.forEachIndexed { i, a ->
        if (i > 0) append(',')
        // safe mode rounds positions to 0.01° (about 1 km): enough to tell motorway from town, not your street
        fun pos(v: Double?) = v?.let { if (meta.safe) "%.2f".format(Locale.ROOT, it) else "%.5f".format(Locale.ROOT, it) } ?: "null"
        append("\n    {\"time\": ${jsonStr(iso(a.time))}, \"kind\": ${jsonStr(a.kind)}, \"text\": ${jsonStr(a.text)}, ")
        append("\"address\": ${jsonStr(a.address?.let { m.address(it) })}, \"lat\": ${pos(a.lat)}, \"lon\": ${pos(a.lon)}}")
    }
    append("\n  ]\n}\n")
}

/** Text to paste into ChatGPT, Claude, Gemini or a local model. Funkblick itself never calls an AI. */
fun aiReport(devices: Collection<Device>, meta: ExportMeta, m: Masker): String = buildString {
    val de = isGerman()
    val tz = TimeZone.getDefault().id
    if (de) {
        append("Du bekommst das Protokoll einer passiven Funk-Beobachtung (WLAN + Bluetooth) mit der Android-App Funkblick.\n")
        append("Bitte: 1) Fasse die Umgebung zusammen (Wohngebiet, Innenstadt, Bahn, Büro …). 2) Nenne Auffälliges, ")
        append("besonders Tracker, die vom Besitzer getrennt sind und lange mitlaufen, Kameras mit eigenem WLAN und offene Netze. ")
        append("3) Sag, was harmlos ist. Wichtig: Ein Hersteller-Treffer sagt nur, wer die Hardware gebaut hat, nicht wer sie betreibt. ")
        append("Versuche nicht, Personen zu identifizieren.\n\n")
    } else {
        append("Below is the log of a passive radio observation (Wi-Fi + Bluetooth) made with the Android app Funkblick.\n")
        append("Please: 1) Summarise the environment (residential, city centre, train, office ...). 2) Point out anything unusual, ")
        append("especially trackers away from their owner that stay around for a long time, cameras running their own Wi-Fi, and open networks. ")
        append("3) Say what is harmless. Note: a manufacturer match only tells who built the hardware, not who operates it. ")
        append("Do not try to identify people.\n\n")
    }
    append("## ${if (de) "Sitzung" else "Session"}\n")
    append("- App: ${meta.app} ${meta.build}, Android ${meta.android}\n")
    append("- ${if (de) "Beginn" else "Start"}: ${iso(meta.startedAt)}, ${if (de) "Export" else "Exported"}: ${iso(meta.exportedAt)} ($tz)\n")
    append("- ${if (de) "Sicherer Modus (Kennungen gekürzt)" else "Safe mode (identifiers shortened)"}: ${meta.safe}\n")
    val byCat = devices.groupBy { it.cls.category }
    append("- ${if (de) "Geräte" else "Devices"}: ${devices.size} (" + Category.entries.filter { byCat[it] != null }.joinToString(", ") { "${it.label()} ${byCat[it]!!.size}" } + ")\n\n")
    append("## ${if (de) "Auffällig" else "Notable"}\n")
    val notable = devices.filter { it.cls.watch != null || it.cls.category in setOf(Category.TRACKER, Category.CAMERA, Category.FLEET) || it.security == Security.OPEN || it.security == Security.WEP }
        .sortedByDescending { it.lastSeen - it.firstSeen }
    if (notable.isEmpty()) append(if (de) "- nichts\n" else "- nothing\n")
    for (d in notable.take(40)) {
        val minutes = (d.lastSeen - d.firstSeen) / 60_000
        append("- ${d.cls.category.label()}: ${m.name(d) ?: "–"} · ${m.address(d.address)} · ${d.cls.vendor ?: "?"} · ${d.rssi} dBm (max ${d.rssiMax}) · ")
        append(if (de) "$minutes min sichtbar, ${d.seenCount}× gesehen" else "visible $minutes min, seen ${d.seenCount}×")
        d.cls.tracker?.let { append(" · ${it.kind.label}${if (it.separated == true) (if (de) " (getrennt)" else " (separated)") else ""}") }
        append("\n")
        for (e in d.cls.evidence) append("  - ${e.source}/${e.confidence}: ${e.text}\n")
    }
    append("\n## ${if (de) "Alle Geräte (stärkstes Signal zuerst)" else "All devices (strongest first)"}\n")
    append("| ${if (de) "Funk" else "Radio"} | ${if (de) "Kategorie" else "Category"} | Name | ${if (de) "Hersteller" else "Maker"} | dBm | min |\n|---|---|---|---|---|---|\n")
    for (d in devices.sortedByDescending { it.rssiMax }.take(150)) {
        val name = (m.name(d) ?: "").replace("|", "/")
        append("| ${d.radio} | ${d.cls.category.label()} | $name | ${d.cls.vendor ?: "?"} | ${d.rssiMax} | ${(d.lastSeen - d.firstSeen) / 60_000} |\n")
    }
}
