package io.github.brickystudios.funkblick.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class LocateTest {
    // a router in Hamburg, and roads running north-south past it
    private val apLat = 53.5500
    private val apLon = 9.9900

    private fun rssiAt(lat: Double, lon: Double): Int {
        val d = maxOf(1.0, distanceMeters(lat, lon, apLat, apLon))
        return (-40 - 27 * log10(d)).toInt()
    }

    /** Readings every 20 m along a north-south road [offsetM] east (+) or west (-) of the router. */
    private fun drive(offsetM: Double) = (-8..8).map { i ->
        val lat = apLat + metersToLat(i * 20.0)
        val lon = apLon + metersToLon(offsetM, apLat)
        Reading(lat, lon, rssiAt(lat, lon))
    }

    private fun error(e: Estimate) = distanceMeters(e.lat, e.lon, apLat, apLon)

    @Test fun onePassLeansTowardsTheRoadButStaysClose() {
        val e = estimatePosition(drive(-30.0))
        assertNotNull(e)
        assertTrue("error ${error(e!!)}", error(e) < 35)
    }

    @Test fun passesOnBothSidesHitIt() {
        val e = estimatePosition(drive(-30.0) + drive(30.0))!!
        assertTrue("error ${error(e)}", error(e) < 10)
        assertTrue(e.radiusM >= 25)
    }

    @Test fun aGpsJumpDoesNotDragItAway() {
        val bogus = Reading(apLat + metersToLat(600.0), apLon, -45)
        val e = estimatePosition(drive(-30.0) + bogus)!!
        assertTrue("error ${error(e)}", error(e) < 40)
    }

    @Test fun needsReadingsSpreadOverGround() {
        assertNull(estimatePosition(drive(-30.0).take(2)))
        val standing = List(10) { Reading(apLat, apLon + metersToLon(-30.0, apLat), -70) }
        assertNull(estimatePosition(standing))
    }

    @Test fun readingsOnlyKeptWhenTheyAddSomething() {
        val list = mutableListOf<Reading>()
        assertTrue(list.addReading(Reading(apLat, apLon, -60)))
        assertTrue(!list.addReading(Reading(apLat + metersToLat(3.0), apLon, -61)))
        assertTrue(list.addReading(Reading(apLat + metersToLat(30.0), apLon, -61)))
        repeat(60) { list.addReading(Reading(apLat + metersToLat(it * 15.0 + 100), apLon, -60)) }
        assertTrue(list.size == MAX_READINGS)
    }

    @Test fun sessionGivesFixedDevicesAPosition() {
        val s = Session(Classifier(realOui)) { 1_000_000_000L }
        drive(-30.0).forEachIndexed { i, r ->
            s.add(Sighting(Radio.WIFI, "00:04:0E:A1:22:F3", "FRITZ!Box 7590 KQ", r.rssi, 1_000_000_000L + i, 2437, Security.WPA2), emptyList(), AlertSettings(), r.lat, r.lon)
        }
        val pos = s.devices.values.single().pos
        assertNotNull(pos)
        assertTrue(error(pos!!) < 35)
    }

    @Test fun radarSpotPointsWhereTheRadarDrawsIt() {
        val (eLat, eLon) = spotAt(apLat, apLon, 100.0, 0.0) // 0° = east
        assertTrue(eLon > apLon && kotlin.math.abs(eLat - apLat) < 1e-9)
        assertTrue(kotlin.math.abs(distanceMeters(apLat, apLon, eLat, eLon) - 100) < 1)
        assertTrue(spotAt(apLat, apLon, 100.0, 90.0).first < apLat) // clockwise on screen: 90° = south
    }
}
