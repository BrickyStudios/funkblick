package io.github.brickystudios.funkblick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NavTest {
    @Test fun decodesGooglesReferencePolyline() {
        // the example from Google's polyline documentation, 5 decimals
        val p = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453), p)
    }

    @Test fun readsARealValhallaAnswer() {
        // a short drive through Hamburg, answered by valhalla1.openstreetmap.de on 09.11.2026
        val r = parseValhalla(javaClass.classLoader!!.getResource("valhalla_hamburg.json")!!.readText())
        assertNotNull(r)
        assertEquals(8, r!!.maneuvers.size)
        assertEquals(15, r.maneuvers[1].type)
        assertEquals("Links auf Mattentwiete abbiegen.", r.maneuvers[1].text)
        assertTrue(abs(r.shape.first().first - 53.5447) < 0.001)
        assertTrue("length ${r.meters}", abs(r.meters - 1216) < 60) // Valhalla says 1.216 km
        assertEquals(r.shape.lastIndex, r.maneuvers.last().at)
    }

    @Test fun readsPhotonPlaces() {
        val json = """{"features":[{"geometry":{"coordinates":[9.9928977,53.550861]},"properties":{"name":"Rathausmarkt",
            "district":"Altstadt","city":"Hamburg","postcode":"20095"}},{"geometry":{"coordinates":[9.99,53.55]},
            "properties":{"street":"Mönckebergstraße","housenumber":"7","city":"Hamburg"}}]}"""
        val p = parsePhoton(json)
        assertEquals(Place("Rathausmarkt", "Altstadt, 20095 Hamburg", 53.550861, 9.9928977), p[0])
        assertEquals("Mönckebergstraße 7", p[1].name)
        assertTrue(parsePhoton("not json").isEmpty())
    }

    // 1 km north, turn right, 500 m east to the destination
    private val a = 53.55 to 9.99
    private val b = a.first + metersToLat(1000.0) to a.second
    private val c = b.first to b.second + metersToLon(500.0, b.first)
    private val route = Route(listOf(a, b, c), listOf(Maneuver(1, "Start", 0), Maneuver(10, "Rechts abbiegen", 1), Maneuver(4, "Ziel", 2)), 120.0)

    /** Drives the route at 50 km/h, one fix a second, and collects everything said. */
    @Test fun announcesEachTurnOnceFromAfarAndAtTheTurn() {
        val g = Guide(route)
        val said = mutableListOf<List<String>>()
        val step = 50 / 3.6
        var m = 0.0
        while (m <= 1500) {
            val (lat, lon) = if (m <= 1000) a.first + metersToLat(m) to a.second else b.first to b.second + metersToLon(m - 1000, b.first)
            val p = g.update(lat, lon, 50f)
            if (p.say.isNotEmpty()) said += p.say
            assertFalse(p.offRoute)
            m += step
        }
        assertEquals(listOf(listOf("nav_d300", "nav_right"), listOf("nav_now", "nav_right"), listOf("nav_d300", "nav_dest"), listOf("nav_arrived")), said)
    }

    @Test fun noticesLeavingTheRouteAfterThreeFixes() {
        val g = Guide(route)
        g.update(a.first + metersToLat(100.0), a.second, 50f)
        val away = a.second + metersToLon(100.0, a.first)
        val flags = (1..3).map { g.update(a.first + metersToLat(120.0 + it * 14), away, 50f).offRoute }
        assertEquals(listOf(false, false, true), flags)
        assertFalse(g.update(a.first + metersToLat(170.0), a.second, 50f).offRoute) // back on the road
    }

    @Test fun warnsOnlyForCamerasAtTheRouteAheadWithRoadDistance() {
        val g = Guide(route)
        g.update(a.first + metersToLat(600.0), a.second, 50f) // 600 m up the first road
        val beside = SpeedCam(a.first + metersToLat(800.0), a.second + metersToLon(60.0, a.first), 50)    // parallel street
        val behind = SpeedCam(a.first + metersToLat(500.0), a.second, 50)                                 // passed
        val afterTurn = SpeedCam(b.first, b.second + metersToLon(300.0, b.first), 30)                     // 400 + 300 m of road
        val hit = g.camOnRoute(listOf(beside, behind, afterTurn), 800.0)
        assertEquals(afterTurn, hit!!.cam)
        assertTrue("road ${hit.meters}", abs(hit.meters - 700) < 5) // straight line would be about 500 m
        assertEquals(null, g.camOnRoute(listOf(beside, behind), 800.0))
        val onRoad = SpeedCam(a.first + metersToLat(900.0), a.second + metersToLon(8.0, a.first), 50)   // pole next to the road
        assertEquals(onRoad, g.camOnRoute(listOf(afterTurn, onRoad), 800.0)!!.cam)                        // the nearer one wins
    }

    @Test fun startingInACarParkDoesNotRerouteForever() {
        val g = Guide(route)
        val park = a.second + metersToLon(70.0, a.first) // 70 m beside the road the route starts on
        assertFalse((1..10).map { g.update(a.first, park, 0f).offRoute }.any { it })
        assertFalse(g.update(a.first + metersToLat(20.0), a.second, 20f).offRoute) // on the road now
        val away = a.second + metersToLon(100.0, a.first)
        assertTrue((1..3).map { g.update(a.first + metersToLat(300.0), away, 50f).offRoute }.last())
        val g2 = Guide(route) // driving off the other way from the car park still counts
        g2.update(a.first, park, 0f)
        assertTrue((1..4).map { g2.update(a.first, a.second + metersToLon(250.0, a.first), 30f).offRoute }.last())
    }

    @Test fun oneOutlierFixAheadDoesNotStrandTheGuide() {
        val pts = (0..100).map { a.first + metersToLat(it * 10.0) to a.second }
        val g = Guide(Route(pts, listOf(Maneuver(1, "Start", 0), Maneuver(4, "Ziel", 100)), 120.0))
        g.update(a.first + metersToLat(100.0), a.second, 50f)
        g.update(a.first + metersToLat(250.0), a.second + metersToLon(30.0, a.first), null) // a cell fix 150 m ahead
        val flags = listOf(114.0, 128.0, 142.0).map { g.update(a.first + metersToLat(it), a.second, 50f).offRoute }
        assertEquals(listOf(false, false, false), flags)
    }

    @Test fun aSilentStepBeforeTheTurnDoesNotSwallowItsAnnouncement() {
        val p900 = a.first + metersToLat(900.0) to a.second
        val r = Route(listOf(a, p900, b, c), listOf(Maneuver(1, "Start", 0), Maneuver(8, "Weiter auf B 4", 1),
            Maneuver(10, "Rechts abbiegen", 2), Maneuver(4, "Ziel", 3)), 120.0)
        val g = Guide(r)
        val said = mutableListOf<List<String>>()
        var m = 0.0
        while (m <= 1000) { g.update(a.first + metersToLat(m), a.second, 50f).say.takeIf { it.isNotEmpty() }?.let { said += it }; m += 50 / 3.6 }
        assertEquals(listOf(listOf("nav_d300", "nav_right"), listOf("nav_now", "nav_right")), said)
    }

    @Test fun everyNavClipHasASoundInBothLanguages() {
        val have = java.io.File("src/main/assets/sounds").list()!!.map { it.removeSuffix(".ogg") }.toSet()
        val keys = (0..40).flatMap { t -> (0..7).mapNotNull { e -> clipFor(Maneuver(t, "", 0, e.takeIf { it > 0 })) } }.toSet() +
            listOf("nav_now", "nav_arrived", "nav_start", "nav_reroute") + listOf(100, 200, 300, 500, 800, 1000, 2000).map { "nav_d$it" }
        for (lang in listOf("de", "en")) for (k in keys) assertTrue("${lang}_$k", "${lang}_$k" in have)
    }
}
