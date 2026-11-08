package io.github.brickystudios.funkblick.core

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTest {
    private val session = Session(Classifier(realOui)) { 1_000_000_000L }.apply {
        demoSightings(1_000_000_000L).forEach { add(it, emptyList(), AlertSettings()) }
        add(Sighting(Radio.WIFI, "DA:00:00:00:00:02", "=HYPERLINK(\"x\"),\n2", -70, 1_000_000_000L, 2412, Security.WPA2), emptyList(), AlertSettings())
    }
    private val meta = ExportMeta("Funkblick", "1.0.0 · test", "15", 0, 1_000_000_000L, true)

    @Test fun safeModeKeepsMakerHidesDeviceAndHomeNames() {
        val m = Masker(true, "salt")
        val masked = m.address("00:04:0E:A1:22:F3")
        assertTrue(masked, masked.startsWith("00:04:0E:#"))
        assertEquals(masked, m.address("00:04:0e:a1:22:f3")) // stable within a session
        val fritz = session.devices.values.first { it.name == "FRITZ!Box 7590 KQ" }
        assertEquals(tr("(versteckt)", "(hidden)"), m.name(fritz))
        val ice = session.devices.values.first { it.name == "WIFIonICE" }
        assertEquals("WIFIonICE", m.name(ice))
        assertEquals("00:04:0E:A1:22:F3", Masker(false, "salt").address("00:04:0E:A1:22:F3"))
    }

    @Test fun csvQuotesAndDefusesFormulas() {
        val csv = toCsv(session.devices.values, Masker(false, "s"))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\"),\n2\""))
        assertEquals(session.devices.size + 1, csv.split("\r\n").filter { it.isNotEmpty() }.size)
    }

    @Test fun jsonIsValid() {
        val root = JsonParser.parseString(toJson(session.devices.values, meta, Masker(true, "s"))).asJsonObject
        assertEquals(session.devices.size, root.getAsJsonArray("devices").size())
        assertTrue(root.get("safe_mode").asBoolean)
    }

    @Test fun aiReportListsTheFollowingTracker() {
        val text = aiReport(session.devices.values, meta, Masker(true, "s"))
        assertTrue(text.contains("AirTag"))
        assertFalse(text.contains("00:04:0E:A1:22:F3")) // safe mode
    }
}

class OverpassTest {
    @Test fun parsesCamerasAndSkipsJunk() {
        val json = """{"elements":[{"type":"node","id":1,"lat":53.56,"lon":9.99,"tags":{"highway":"speed_camera","maxspeed":"50 mph"}},
            {"type":"node","id":2,"lat":50.1,"lon":7.1,"tags":{"highway":"speed_camera"}},{"type":"way","id":3}]}"""
        assertEquals(listOf("53.56000\t9.99000\t50\t", "50.10000\t7.10000\t\t"), overpassToTsv(json))
        assertTrue(overpassToTsv("<html>busy</html>").isEmpty())
        assertTrue(overpassQuery(53.56, 9.99).contains("(52.960,9.090,54.160,10.890)"))
    }

    @Test fun refreshedCamerasDontCountTwice() {
        assertEquals(1, SpeedCamDb(sequenceOf("53.56000\t9.99000\t50\t", "53.56000\t9.99000\t50\t")).size)
    }
}

class SpeedCamTest {
    // two cameras on a north-south road in Hamburg, 400 m north and 400 m south of us
    private val db = SpeedCamDb(sequenceOf("53.56000\t9.99000\t50\t", "53.55280\t9.99000\t\t", "garbage"))

    @Test fun nearestAheadOnly() {
        assertEquals(2, db.size)
        val north = db.ahead(53.55640, 9.99, heading = 0.0)
        assertNotNull(north)
        assertEquals(50, north!!.cam.maxspeed)
        assertTrue(north.meters in 390..410)
        assertEquals(53.55280, db.ahead(53.55640, 9.99, heading = 180.0)!!.cam.lat, 1e-6)
        assertNull(db.ahead(53.55640, 9.99, heading = 90.0)) // driving east, both are to the side
        assertNull(db.ahead(51.5, 7.5, heading = null))
    }
}

class GeoExportTest {
    private val session = Session(Classifier(realOui)) { 1_000_000_000L }.apply {
        // a camera seen from 6 points along a road, so it gets a position
        for (i in 0..5) add(Sighting(Radio.WIFI, "00:4B:12:7F:3A:C9", "V380_<&>\"", -60 - i, 1_000_000_000L + i, 2437, Security.OPEN),
            emptyList(), AlertSettings(), 53.55 + i * 0.0003, 9.99)
    }
    private val track = (0..5).map { TrackPoint(53.55 + it * 0.0003, 9.99, 1_000_000_000L + it * 1000) }
    private val alerts = listOf(LoggedAlert(1_000_000_000L, "camera", "Kamera-WLAN in der Nähe", "00:4B:12:7F:3A:C9", 53.55, 9.99))

    @Test fun gpxIsWellFormedXmlWithTrackAndWaypoints() {
        val gpx = toGpx(session.devices.values, track, alerts, Masker(false, "s"))
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gpx.byteInputStream())
        assertEquals(6, doc.getElementsByTagName("trkpt").length)
        assertEquals(2, doc.getElementsByTagName("wpt").length) // the camera and the alert
        assertTrue(gpx.contains("V380_&lt;&amp;&gt;&quot;"))
    }

    @Test fun geoJsonParses() {
        val root = JsonParser.parseString(toGeoJson(session.devices.values, track, alerts, Masker(true, "s"))).asJsonObject
        val kinds = root.getAsJsonArray("features").map { it.asJsonObject.getAsJsonObject("properties").get("kind").asString }
        assertEquals(listOf("track", "device", "alert"), kinds)
    }
}
