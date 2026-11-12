package io.github.brickystudios.funkblick.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.google.gson.annotations.SerializedName

// A drive for other map apps: GPX opens in Organic Maps (and OsmAnd, Komoot, Google Earth), GeoJSON in geojson.io,
// uMap and QGIS. Notable devices with an estimated position become waypoints, alerts become points.

// stored in route files by Gson (kept from R8 in proguard-rules.pro); alternate = the names R8 gave the fields in the
// 1.13 and 1.14 release builds, so route files written by them still open
data class TrackPoint(
    @field:SerializedName("lat", alternate = ["a"]) val lat: Double,
    @field:SerializedName("lon", alternate = ["b"]) val lon: Double,
    @field:SerializedName("time", alternate = ["c"]) val time: Long,
)

private fun utc(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun num(v: Double) = "%.6f".format(Locale.ROOT, v)

private fun shown(devices: Collection<Device>) = devices.filter { it.pos != null && it.cls.mute == null &&
    (it.cls.watch != null || it.police != null || it.cls.category.ordinal <= Category.FLEET.ordinal) }

fun toGpx(devices: Collection<Device>, track: List<TrackPoint>, alerts: List<LoggedAlert>, m: Masker): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Funkblick\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    for (d in shown(devices)) {
        val p = d.pos!!
        append("<wpt lat=\"${num(p.lat)}\" lon=\"${num(p.lon)}\"><name>${xml(m.name(d) ?: d.cls.tracker?.kind?.label ?: d.cls.category.label())}</name>")
        append("<desc>${xml(d.cls.category.label() + (d.cls.vendor?.let { ", $it" } ?: "") + ", ±${p.radiusM.toInt()} m")}</desc></wpt>\n")
    }
    for (a in alerts) if (a.lat != null && a.lon != null)
        append("<wpt lat=\"${num(a.lat)}\" lon=\"${num(a.lon)}\"><time>${utc(a.time)}</time><name>${xml(a.text)}</name></wpt>\n")
    if (track.size > 1) {
        append("<trk><name>Funkblick ${utc(track.first().time).take(10)}</name><trkseg>\n")
        for (t in track) append("<trkpt lat=\"${num(t.lat)}\" lon=\"${num(t.lon)}\"><time>${utc(t.time)}</time></trkpt>\n")
        append("</trkseg></trk>\n")
    }
    append("</gpx>\n")
}

private fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

fun toGeoJson(devices: Collection<Device>, track: List<TrackPoint>, alerts: List<LoggedAlert>, m: Masker): String {
    val f = mutableListOf<String>()
    if (track.size > 1) f += "{\"type\":\"Feature\",\"properties\":{\"kind\":\"track\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[" +
        track.joinToString(",") { "[${num(it.lon)},${num(it.lat)}]" } + "]}}"
    for (d in shown(devices)) {
        val p = d.pos!!
        f += "{\"type\":\"Feature\",\"properties\":{\"kind\":\"device\",\"name\":${js(m.name(d) ?: "")},\"category\":${js(d.cls.category.name)}," +
            "\"vendor\":${js(d.cls.vendor ?: "")},\"radius_m\":${p.radiusM.toInt()},\"police\":${js(d.police?.level?.name ?: "")}}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${num(p.lon)},${num(p.lat)}]}}"
    }
    for (a in alerts) if (a.lat != null && a.lon != null)
        f += "{\"type\":\"Feature\",\"properties\":{\"kind\":\"alert\",\"alert\":${js(a.kind)},\"text\":${js(a.text)},\"time\":${js(utc(a.time))}}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${num(a.lon)},${num(a.lat)}]}}"
    return "{\"type\":\"FeatureCollection\",\"features\":[\n" + f.joinToString(",\n") + "\n]}\n"
}
