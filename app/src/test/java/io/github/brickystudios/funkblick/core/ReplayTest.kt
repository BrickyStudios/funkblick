package io.github.brickystudios.funkblick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTest {
    private val session = Session(Classifier(realOui)) { 1_000_000_000L }.apply {
        demoSightings(1_000_000_000L).forEach { add(it, emptyList(), AlertSettings()) }
    }
    // Hamburg, invented: one fixed device gets a position, the drive is three points
    private val devices = session.devices.values.mapIndexed { i, d -> if (i == 0) d.copy(pos = Estimate(53.55, 9.99, 40.0, 12)) else d }
    private val track = listOf(TrackPoint(53.54, 9.98, 1_000_000_000L), TrackPoint(53.545, 9.985, 1_000_030_000L), TrackPoint(53.55, 9.99, 1_000_060_000L))
    private val alert = LoggedAlert(1_000_045_000L, "camera", "Kamera", null, 53.551, 9.991)
    private val meta = ExportMeta("Funkblick", "test", "15", 1_000_000_000L, 1_000_060_000L, true)

    @Test fun logAndRouteFileComeBackTogether() {
        val m = Masker(true, "salt")
        val past = readLog(toJson(devices, meta, m, listOf(alert)), routeJson(devices, track, m, listOf(alert)))
        assertEquals(devices.size, past.devices.size)
        assertEquals(1_000_000_000L, past.startedAt)
        assertEquals(track, past.track)
        // the position joins on the shortened address the log carries
        val placed = past.devices.single { it.pos != null }
        assertEquals(Estimate(53.55, 9.99, 40.0, 12), placed.pos)
        assertEquals(m.address(devices[0].address), placed.address)
        assertEquals(devices.map { it.cls.category }.sorted(), past.devices.map { it.cls.category }.sorted())
        assertTrue(past.devices.any { it.cls.tracker?.kind == TrackerKind.AIRTAG })
        // safe mode rounds the alert in the log to about 1 km; the private route file keeps it exact
        assertEquals(listOf(alert.time to 53.551), past.alerts.map { it.time to it.lat })
        assertEquals(53.55, readLog(toJson(devices, meta, m, listOf(alert)), null).alerts.single().lat!!, 1e-9)
        assertEquals(devices.sumOf { it.seenCount }, past.devices.sumOf { it.seenCount })
    }

    @Test fun oldLogWithoutRouteFileStillOpens() {
        val past = readLog(toJson(devices, meta, Masker(false, "s")), null)
        assertEquals(devices.size, past.devices.size)
        assertTrue(past.track.isEmpty())
        assertNull(past.devices.firstOrNull { it.pos != null })
    }

    @Test fun unknownCategoryFromANewerAppIsSkipped() {
        val json = toJson(devices, meta, Masker(false, "s")).replaceFirst("\"category\": \"", "\"category\": \"FUTURE_")
        assertEquals(devices.size - 1, readLog(json, "not json").devices.size)
    }

    @Test fun allLogsOnOneMapKeepEveryDriveApart() {
        val later = track.map { it.copy(time = it.time + 86_400_000L) }
        val a = PastSession(1_000_000_000L, devices, listOf(alert), track)
        val b = PastSession(1_086_400_000L, devices.map { it.copy(lastSeen = it.lastSeen + 86_400_000L) }, emptyList(), later)
        val all = mergeSessions(listOf(b, a))
        assertEquals(1_000_000_000L, all.startedAt)
        assertEquals(track + later, all.track)
        assertEquals(devices.size * 2, all.devices.size) // the same device on two drives: two places on the map
        assertTrue(all.devices.first().lastSeen >= all.devices.last().lastSeen)
        assertEquals(listOf(track, later), legs(all.track) { it.time })
        assertEquals(listOf(track), legs(track) { it.time })
        assertTrue(legs(emptyList<TrackPoint>()) { it.time }.isEmpty())
    }

    @Test fun routeFileFromTheShrunkReleaseBuildStillReads() {
        // 1.13 and 1.14 release builds wrote the route file with R8's field names (a, b, c, d)
        val m = Masker(false, "s")
        val placed = devices[0]
        val id = deviceId(placed.radio, placed.address)
        val old = """{"a":[{"a":53.54,"b":9.98,"c":1000000000},{"a":53.55,"b":9.99,"c":1000060000}],""" +
            """"b":{"$id":{"a":53.55,"b":9.99,"c":40.0,"d":12}},"c":[{"a":53.551,"b":9.991,"c":1000045000}]}"""
        val past = readLog(toJson(devices, meta, m, listOf(alert)), old)
        assertEquals(listOf(TrackPoint(53.54, 9.98, 1_000_000_000L), TrackPoint(53.55, 9.99, 1_000_060_000L)), past.track)
        assertEquals(Estimate(53.55, 9.99, 40.0, 12), past.devices.single { it.pos != null }.pos)
        assertEquals(53.551, past.alerts.single().lat!!, 1e-9)
    }
}
