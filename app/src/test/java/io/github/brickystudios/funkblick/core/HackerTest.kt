package io.github.brickystudios.funkblick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HackerTest {
    private val c = Classifier(realOui)
    private var clock = 1_000_000_000L
    private val session = Session(Classifier(realOui)) { clock }
    private val s = AlertSettings()

    private fun ble(addr: String, name: String? = null, mfg: Map<Int, ByteArray> = emptyMap(), sd: Map<Int, ByteArray> = emptyMap(), uuids: Set<Int> = emptySet()) =
        Sighting(Radio.BLE, addr, name, -60, clock, manufacturerData = mfg, serviceData = sd, serviceUuids = uuids)

    @Test fun flipperPineappleDeautherSkimmer() {
        // invented addresses; identifiers after SquachWatch / RF Sentinel
        assertEquals(Category.HACKER, c.classify(ble("C6:00:00:00:00:50", uuids = setOf(0x3082)), emptyList()).category)
        assertEquals(Category.HACKER, c.classify(ble("C6:00:00:00:00:51", mfg = mapOf(0x0E29 to byteArrayOf(1))), emptyList()).category)
        assertEquals(Category.HACKER, c.classify(ble("C6:00:00:00:00:52", name = "Flipper Ab1cd"), emptyList()).category)
        assertEquals(Category.HACKER, c.classify(ble("C6:00:00:00:00:53", name = "HC-08"), emptyList()).category)
        assertEquals(Category.HACKER, c.classify(Sighting(Radio.WIFI, "02:13:37:00:00:54", "Pineapple_1A2B", -60, 0, 2437, Security.OPEN), emptyList()).category)
        assertEquals(Category.HACKER, c.classify(Sighting(Radio.WIFI, "3A:00:00:00:00:55", "pwned", -60, 0, 2437, Security.WPA2), emptyList()).category)
        assertEquals(AlertReason.HACKER, session.add(ble("C6:00:00:00:00:56", uuids = setOf(0x3081)), emptyList(), s)?.reason)
        assertNull(session.add(ble("C6:00:00:00:00:57", uuids = setOf(0x3083)), emptyList(), s.copy(hackers = false)))
    }

    @Test fun aFloodOfPairingPopupsIsSpam() {
        val popup = mapOf(APPLE to byteArrayOf(0x07, 0x19, 0x01))
        val reasons = (0 until SPAM_ADDRESSES).mapNotNull { i ->
            clock += 500
            session.add(ble("C6:00:00:00:%02X:%02X".format(i / 256, i % 256), mfg = popup), emptyList(), s)?.reason
        }
        assertEquals(listOf(AlertReason.BLE_SPAM), reasons) // once per flood
    }

    @Test fun aFewRealAirPodsAreNoSpam() {
        val popup = mapOf(APPLE to byteArrayOf(0x07, 0x19, 0x01))
        for (i in 0 until 10) { clock += 500; assertNull(session.add(ble("C6:00:00:00:01:%02X".format(i), mfg = popup), emptyList(), s)) }
    }

    @Test fun sameNameOpenAndProtectedFromTwoMakersIsAnEvilTwin() {
        val real = Sighting(Radio.WIFI, "00:1A:4F:00:00:60", "Cafe Lindenblatt", -60, clock, 2437, Security.WPA2) // AVM
        val fake = Sighting(Radio.WIFI, "3A:00:00:00:00:61", "Cafe Lindenblatt", -55, clock, 2437, Security.OPEN) // no maker
        assertNull(session.add(real, emptyList(), s))
        val a = session.add(fake, emptyList(), s)
        assertEquals(AlertReason.EVIL_TWIN, a?.reason)
        assertTrue(a!!.device.address == "3A:00:00:00:00:61") // the open copy
        // a dual-band router: same maker, nothing
        val b1 = Sighting(Radio.WIFI, "00:1A:4F:00:00:62", "Home 1062", -50, clock, 2437, Security.WPA2)
        val b2 = Sighting(Radio.WIFI, "00:1A:4F:00:00:63", "Home 1062", -50, clock, 5180, Security.OPEN)
        assertNull(session.add(b1, emptyList(), s))
        assertNull(session.add(b2, emptyList(), s))
    }
}
