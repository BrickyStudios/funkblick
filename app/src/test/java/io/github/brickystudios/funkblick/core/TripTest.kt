package io.github.brickystudios.funkblick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripTest {
    private val t0 = 1_000_000_000L

    @Test fun statsLeaveOutOwnDevicesAndCountPerKm() {
        val s = Session(Classifier(realOui)) { t0 }
        fun add(addr: String, rssi: Int, min: Int, sec: Security? = Security.WPA2, radio: Radio = Radio.WIFI) =
            s.add(Sighting(radio, addr, null, rssi, t0 + min * 60_000L, if (radio == Radio.WIFI) 2412 else 0, sec), emptyList(), AlertSettings())
        add("3A:00:00:00:00:01", -40, 0) // strong from the start: your own car
        add("3A:00:00:00:00:02", -70, 3, Security.OPEN)
        add("3A:00:00:00:00:03", -75, 5)
        add("3A:00:00:00:00:04", -80, 10)
        add("3A:00:00:00:00:05", -80, 6, null, Radio.BLE)
        // a made-up drive north through Hamburg, 0.01° of latitude is about 1.1 km
        val track = listOf(TrackPoint(53.55, 9.99, t0), TrackPoint(53.56, 9.99, t0 + 1), TrackPoint(53.57, 9.99, t0 + 2))
        val st = tripStats(s.devices.values, track)!!
        assertEquals(4, st.devices)
        assertEquals(3, st.wifi)
        assertEquals(1, st.ble)
        assertEquals(33, st.openPercent)
        assertEquals(2.22, st.km, 0.02)
        assertEquals(1, st.perKm)
        assertEquals(600_000L, st.millis) // the whole session, own devices included
    }

    @Test fun noDistanceNoPerKm() {
        val s = Session(Classifier(realOui)) { t0 }
        s.add(Sighting(Radio.BLE, "3A:00:00:00:00:09", null, -80, t0 + 300_000, 0, null), emptyList(), AlertSettings())
        val st = tripStats(s.devices.values, emptyList())!!
        assertNull(st.perKm)
        assertNull(st.openPercent)
        assertNull(tripStats(emptyList(), emptyList()))
    }

    @Test fun makerNamesLoseLegalSuffixes() {
        assertEquals("Apple", shortMaker("Apple, Inc."))
        assertEquals("Huawei", shortMaker("Huawei Technologies Co.,Ltd"))
        assertEquals("Sagemcom Broadband SAS", shortMaker("Sagemcom Broadband SAS"))
    }
}
