package io.github.brickystudios.funkblick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    private var clock = 1_000_000_000L
    private val session = Session(Classifier(realOui)) { clock }
    private val s = AlertSettings()
    private val separatedAirTag = mapOf(0x004C to byteArrayOf(0x12, 0x19, 0x10))
    // invented random address; a separated AirTag keeps it for up to a day
    private fun tag(t: Long) = Sighting(Radio.BLE, "C6:00:00:00:00:51", null, -60, t, manufacturerData = separatedAirTag)

    @Test fun aTagParkedByTheRoadNeverCameAlong() {
        for (m in 0..20) { clock += 60_000; session.add(tag(clock), emptyList(), s, 53.5500, 9.9900) }
        assertFalse(session.devices.values.single().cameAlong())
    }

    @Test fun aTagThatMovesWithYouCameAlong() {
        session.add(tag(clock), emptyList(), s, 53.5500, 9.9900)
        clock += 60_000; session.add(tag(clock), emptyList(), s, 53.5536, 9.9900) // 400 m on
        assertTrue(session.devices.values.single().cameAlong())
    }

    @Test fun withoutGpsFiveMinutesCount() {
        session.add(tag(clock), emptyList(), s)
        clock += ALONG_MS; session.add(tag(clock), emptyList(), s)
        assertTrue(session.devices.values.single().cameAlong())
    }

    @Test fun cameAlongOnAnEarlierDriveRingsLongBeforeTenMinutes() {
        session.cameAlongBefore = { true }
        assertNull(session.add(tag(clock), emptyList(), s, 53.5500, 9.9900)) // just there: could be anyone's
        clock += 60_000
        assertEquals(AlertReason.TRACKER_AGAIN, session.add(tag(clock), emptyList(), s, 53.5536, 9.9900)?.reason)
    }

    @Test fun notSeenBeforeStaysQuietUntilTheUsualRule() {
        session.add(tag(clock), emptyList(), s, 53.5500, 9.9900)
        clock += 60_000
        assertNull(session.add(tag(clock), emptyList(), s, 53.5536, 9.9900))
    }

    @Test fun trackerAlertsOffMeansOff() {
        session.cameAlongBefore = { true }
        session.add(tag(clock), emptyList(), s, 53.5500, 9.9900)
        clock += 60_000
        assertNull(session.add(tag(clock), emptyList(), AlertSettings(trackers = false), 53.5536, 9.9900))
    }

    @Test fun oneEntryPerSessionAndOnlyTheLastTen() {
        val l = mutableListOf<Long>()
        l.addSession(1); l.addSession(1)
        assertEquals(listOf(1L), l)
        for (i in 2L..20L) l.addSession(i)
        assertEquals((11L..20L).toList(), l)
    }

    @Test fun theTighterCameraEstimateWins() {
        val wide = KnownCam(53.55, 9.99, 120.0, "IPCAM", 1)
        val tight = KnownCam(53.551, 9.991, 30.0, "IPCAM", 2)
        assertEquals(tight, wide.merge(tight))
        assertEquals(tight.copy(lastSeen = 3), tight.merge(wide.copy(lastSeen = 3))) // keeps the spot, takes the date
        assertEquals(wide, null.merge(wide))
    }
}
