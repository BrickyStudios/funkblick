package io.github.brickystudios.funkblick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTest {
    private var clock = 1_000_000_000L
    private val session = Session(Classifier(realOui)) { clock }
    private val s = AlertSettings()

    // invented addresses; the router and the camera have fixed (globally administered) addresses
    private fun router(t: Long) = Sighting(Radio.WIFI, "00:1A:4F:00:00:01", "Router 1001", -45, t, 2437, Security.WPA2)
    private fun ownCam(t: Long) = Sighting(Radio.WIFI, "00:4B:12:7F:3A:C9", "V380_8F2A1C", -70, t, 2437, Security.OPEN)
    private fun newCam(t: Long) = Sighting(Radio.WIFI, "00:4B:12:7F:3A:D0", "IPCAM-101030", -72, t, 2412, Security.OPEN)
    private fun phone(t: Long) = Sighting(Radio.BLE, "C6:00:00:00:00:40", null, -40, t) // random address

    private fun learned() {
        session.add(router(clock), emptyList(), s)
        session.add(ownCam(clock), emptyList(), s)
        assertNotNull(session.markHome())
        clock += HOME_LEARN_MS + 1_000
        session.add(router(clock), emptyList(), s) // still there: you're home
    }

    @Test fun onlyWhatIsNewRings() {
        learned()
        assertTrue(session.atHome)
        assertNull(session.add(ownCam(clock), emptyList(), s)) // your own camera, known: quiet at home
        assertEquals(AlertReason.CAMERA, session.add(newCam(clock), emptyList(), s)?.reason) // a new camera keeps its own alert
        assertTrue(session.newAtHome.any { it.endsWith("00:4B:12:7F:3A:D0") }) // and can be accepted
        clock += 1_000
        session.add(router(clock), emptyList(), s)
        // a strong device with a fixed address, not there while learning
        val speaker = Sighting(Radio.BLE, "00:1B:66:00:00:41", "Speaker 1041", -48, clock)
        assertEquals(AlertReason.HOME_NEW, session.add(speaker, emptyList(), s)?.reason)
        assertTrue(session.newAtHome.isNotEmpty())
        assertNull(session.add(phone(clock), emptyList(), s)) // a phone changes its address: nothing to remember
    }

    @Test fun awayFromHomeNothingChanges() {
        learned()
        clock += HOME_PRESENT_MS + 60_000 // the anchors have gone quiet: you left
        assertFalse(session.atHome)
        assertEquals(AlertReason.CAMERA, session.add(ownCam(clock), emptyList(), s)?.reason)
    }

    @Test fun acceptedDevicesStayQuiet() {
        learned()
        val speaker = { t: Long -> Sighting(Radio.BLE, "00:1B:66:00:00:42", "Speaker 1042", -48, t) }
        assertEquals(AlertReason.HOME_NEW, session.add(speaker(clock), emptyList(), s)?.reason)
        session.acceptNewAtHome()
        assertTrue(session.home!!.known.any { it.endsWith("00:1B:66:00:00:42") })
        clock += COOLDOWN_MS + 1_000
        session.add(router(clock), emptyList(), s)
        assertNull(session.add(speaker(clock), emptyList(), s))
    }

    @Test fun noWifiNoPlace() {
        session.add(phone(clock), emptyList(), s)
        assertNull(session.markHome())
    }
}
