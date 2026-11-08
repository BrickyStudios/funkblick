package io.github.brickystudios.funkblick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
private fun ble(addr: String = "C6:00:00:00:00:01", name: String? = null, mfg: Map<Int, ByteArray> = emptyMap(), sd: Map<Int, ByteArray> = emptyMap(), uuids: Set<Int> = emptySet()) =
    Sighting(Radio.BLE, addr, name, -60, 0, manufacturerData = mfg, serviceData = sd, serviceUuids = uuids)
private fun wifi(addr: String, ssid: String, sec: Security = Security.WPA2, t: Long = 0, freq: Int = 2437) = Sighting(Radio.WIFI, addr, ssid, -60, t, freq, sec)

/** The real registry from the APK, so the tests break when a rebuilt oui.txt renames a vendor the signatures rely on. */
val realOui: OuiDb by lazy { OuiDb(File("src/main/assets/oui.txt").readLines().asSequence()) }

class TrackerTest {
    @Test fun airTagAwayFromOwner() {
        val t = parseTracker(ble(mfg = mapOf(APPLE to b(0x12, 0x19, 0x10, 1, 2, 3))))
        assertEquals(Tracker(TrackerKind.AIRTAG, true), t)
    }

    @Test fun airTagNearOwnerSendsShortPayload() {
        assertEquals(Tracker(TrackerKind.AIRTAG, false), parseTracker(ble(mfg = mapOf(APPLE to b(0x12, 0x02, 0x10, 0)))))
    }

    @Test fun statusBitsTellAirPodsFindMyAndPhones() {
        assertEquals(TrackerKind.AIRPODS, parseTracker(ble(mfg = mapOf(APPLE to b(0x12, 0x19, 0x30))))!!.kind)
        assertEquals(TrackerKind.APPLE_FIND_MY, parseTracker(ble(mfg = mapOf(APPLE to b(0x12, 0x19, 0x20))))!!.kind)
        assertEquals(TrackerKind.APPLE_DEVICE, parseTracker(ble(mfg = mapOf(APPLE to b(0x12, 0x19, 0x00))))!!.kind)
        assertFalse(TrackerKind.AIRPODS.isTag)
    }

    @Test fun otherAppleAdvertsAreNoTracker() {
        assertNull(parseTracker(ble(mfg = mapOf(APPLE to b(0x10, 0x05, 0x1B)))))
    }

    @Test fun googleFindMyButNotEddystone() {
        assertEquals(Tracker(TrackerKind.GOOGLE_FIND_MY, true), parseTracker(ble(sd = mapOf(UUID_GOOGLE_FMDN to b(0x41, 1, 2)))))
        assertEquals(Tracker(TrackerKind.GOOGLE_FIND_MY, false), parseTracker(ble(sd = mapOf(UUID_GOOGLE_FMDN to b(0x40, 1, 2)))))
        assertNull(parseTracker(ble(sd = mapOf(UUID_GOOGLE_FMDN to b(0x10, 1, 2))))) // Eddystone-URL beacon
    }

    @Test fun samsungTileChipolo() {
        assertEquals(Tracker(TrackerKind.SAMSUNG_SMARTTAG, true), parseTracker(ble(sd = mapOf(UUID_SAMSUNG to b(0x12)))))
        assertEquals(Tracker(TrackerKind.SAMSUNG_SMARTTAG, false), parseTracker(ble(sd = mapOf(UUID_SAMSUNG to b(0x15)))))
        assertEquals(TrackerKind.TILE, parseTracker(ble(uuids = setOf(UUID_TILE)))!!.kind)
        assertEquals(TrackerKind.CHIPOLO, parseTracker(ble(sd = mapOf(UUID_CHIPOLO to b(1))))!!.kind)
    }

    @Test fun wifiIsNeverATracker() {
        assertNull(parseTracker(Sighting(Radio.WIFI, "00:00:00:00:00:01", "x", -50, 0, manufacturerData = mapOf(APPLE to b(0x12, 0x19, 0x10)))))
    }
}

class OuiTest {
    private val db = OuiDb(sequenceOf("003044\tCradlePoint", "70B3D5\tIEEE big", "70B3D51\tSmall Co", "70B3D5123\tTiny Co", "bad line"))

    @Test fun longestPrefixWins() {
        assertEquals("Tiny Co", db.lookup("70:B3:D5:12:34:56"))
        assertEquals("Small Co", db.lookup("70-b3-d5-1f-00-00"))
        assertEquals("IEEE big", db.lookup("70B3D5FF0000"))
        assertEquals("CradlePoint", db.lookup("00:30:44:24:6a:3c"))
        assertNull(db.lookup("not a mac"))
    }

    @Test fun locallyAdministeredBit() {
        assertTrue(isLocallyAdministered("DA:A1:19:5E:02:77"))
        assertFalse(isLocallyAdministered("00:30:44:24:6A:3C"))
    }

    @Test fun realRegistryIsComplete() {
        assertTrue(realOui.size > 50_000)
        assertEquals("CradlePoint", realOui.lookup("00:30:44:24:6A:3C"))
        assertEquals("AVM", realOui.lookup("00:04:0E:00:00:01"))
    }
}

class ClassifierTest {
    private val c = Classifier(realOui)

    @Test fun cradlepointIsVehicleRouter() {
        val r = c.classify(wifi("00:30:44:24:6A:3C", "14F117_5g"), emptyList())
        assertEquals(Category.FLEET, r.category)
        assertEquals("CradlePoint", r.vendor)
        assertTrue(r.evidence.any { it.source == Source.REGISTRY })
    }

    @Test fun trainWifiIsPublicNotAVehicleAlert() {
        val r = c.classify(wifi("40:40:6B:0C:7E:91", "WIFIonICE"), emptyList())
        assertEquals("Icomera", r.vendor)
        assertEquals(Category.PUBLIC_WIFI, r.category)
    }

    @Test fun namesFromTheFirstDrives() {
        // the shapes of names seen on real drives; numbers and addresses are invented
        for (n in listOf("BMW 10003", "DIRECT-BMW 10007", "My VW 1013", "My SEAT 1012", "Audi_MMI_1002", "My BMW Hotspot 1010", "CarPlay_1005", "CAR-WIFI-1004", "Hyundai_1008", "Share_RENAULT_HotSpot_1016", "Toyota 1017"))
            assertEquals(n, Category.CAR, c.classify(wifi("3A:00:00:00:00:24", n), emptyList()).category)
        assertEquals(Category.FLEET, c.classify(wifi("20:97:27:00:00:23", "RUT956_1014"), emptyList()).category)
        assertEquals(Category.PERSONAL, c.classify(wifi("00:EE:F2:00:00:22", "4G-MIFI-1001"), emptyList()).category) // pocket router, no alert since 1.5.1
        assertEquals(Category.HOME, c.classify(wifi("4A:00:00:00:00:26", "DIRECT-1A-HP LaserJet 1006"), emptyList()).category)
        assertEquals(Category.CAMERA, c.classify(wifi("E0:09:BF:00:00:2B", "IPCAM-101009"), emptyList()).category)
        assertEquals(Category.CAMERA, c.classify(wifi("9C:76:13:00:00:28", "Ring Setup 1f"), emptyList()).category)
        assertEquals(Category.CAR, c.classify(wifi("00:13:1E:00:00:21", "x"), emptyList()).category) // peiker acustic, BMW telematics
    }

    @Test fun axonIsPublicSafetyGear() {
        assertEquals(Category.PRIORITY, c.classify(wifi("00:25:DF:31:0A:4E", "x"), emptyList()).category)
        assertEquals(Category.PRIORITY, c.classify(ble(name = "Axon Body 3"), emptyList()).category)
    }

    @Test fun cameraNameBeatsHomeVendor() {
        val r = c.classify(wifi("00:4B:12:7F:3A:C9", "V380_8F2A1C", Security.OPEN), emptyList())
        assertEquals(Category.CAMERA, r.category)
        assertTrue(r.evidence.any { it.source == Source.SIGNATURE }) // Espressif still listed as evidence
    }

    @Test fun germanPublicWifi() {
        for (ssid in listOf("WIFIonICE", "WIFI@DB", "Telekom", "eduroam", "Freifunk Hamburg", "@BayernWLAN", "Vodafone Hotspot", "ÖBB Railnet"))
            assertEquals(ssid, Category.PUBLIC_WIFI, c.classify(wifi("DA:00:00:00:00:01", ssid), emptyList()).category)
        assertEquals(Category.UNKNOWN, c.classify(wifi("DA:00:00:00:00:01", "TelekomTest"), emptyList()).category)
    }

    @Test fun homeRoutersAndRandomHotspots() {
        assertEquals(Category.HOME, c.classify(wifi("00:04:0E:A1:22:F3", "FRITZ!Box 7590 KQ"), emptyList()).category)
        val r = c.classify(wifi("DA:A1:19:5E:02:77", "Pixel_4821"), emptyList())
        assertTrue(r.randomAddress)
        assertNull(r.vendor)
        assertEquals(Category.UNKNOWN, r.category)
    }

    @Test fun openAndWepNetworksAreFlagged() {
        assertTrue(c.classify(wifi("DA:00:00:00:00:01", "x", Security.OPEN), emptyList()).evidence.any { it.confidence == Confidence.HIGH && it.source == Source.NAME })
        assertTrue(c.classify(wifi("DA:00:00:00:00:01", "x", Security.WEP), emptyList()).evidence.isNotEmpty())
    }

    @Test fun bleCompanyIdNamesTheVendor() {
        val r = c.classify(ble("DA:3B:BF:01:72:E0", "Galaxy Buds2 Pro", mfg = mapOf(0x0075 to b(1))), emptyList())
        assertEquals("Samsung", r.vendor)
        assertEquals(Category.PERSONAL, r.category)
    }

    @Test fun separatedAirTagIsTrackerIPhoneIsNot() {
        val tag = c.classify(ble(mfg = mapOf(APPLE to b(0x12, 0x19, 0x10))), emptyList())
        assertEquals(Category.TRACKER, tag.category)
        assertTrue(tag.evidence.none { it.source == Source.SIGNATURE }) // no "maker of phones" on a tag
        assertEquals(Category.PERSONAL, c.classify(ble(mfg = mapOf(APPLE to b(0x12, 0x19, 0x00))), emptyList()).category)
    }
}

class RuleTest {
    private val s = wifi("00:30:44:24:6A:3C", "14F117_5g")

    @Test fun fields() {
        assertTrue(Rule(RuleKind.WATCH, RuleField.ADDRESS, "00-30-44-24-6a-3c").matches(s, null))
        assertTrue(Rule(RuleKind.WATCH, RuleField.PREFIX, "00:30:44").matches(s, null))
        assertFalse(Rule(RuleKind.WATCH, RuleField.PREFIX, "00:30:45").matches(s, null))
        assertTrue(Rule(RuleKind.WATCH, RuleField.NAME_EXACT, "14f117_5G").matches(s, null))
        assertTrue(Rule(RuleKind.WATCH, RuleField.NAME_CONTAINS, "117").matches(s, null))
        assertTrue(Rule(RuleKind.WATCH, RuleField.VENDOR, "cradle").matches(s, "CradlePoint"))
    }

    @Test fun brokenRulesNeverMatch() {
        assertFalse(Rule(RuleKind.WATCH, RuleField.NAME_CONTAINS, "  ").matches(s, null))
        assertFalse(Rule(RuleKind.WATCH, RuleField.PREFIX, "zz").matches(s, null))
        assertFalse(Rule(RuleKind.WATCH, RuleField.ADDRESS, "00:30").isValid())
    }
}

class SessionTest {
    private var clock = 1_000_000_000L
    private val session = Session(Classifier(realOui)) { clock }
    private val cam = { t: Long -> wifi("00:4B:12:7F:3A:C9", "V380_8F2A1C", Security.OPEN, t) }

    @Test fun cachedResultsAreNoNewObservation() {
        assertNotNull(session.add(cam(clock), emptyList(), AlertSettings()))
        assertNull(session.add(cam(clock), emptyList(), AlertSettings()))
        assertEquals(1, session.devices.values.single().seenCount)
    }

    @Test fun staleObservationsDontRing() {
        assertNull(session.add(cam(clock - FRESH_MS - 1), emptyList(), AlertSettings()))
        assertEquals(1, session.devices.size)
    }

    @Test fun drivingPastAHouseCameraStaysQuiet() {
        // seen once at 50 km/h: a house camera by the road
        assertNull(session.add(cam(clock), emptyList(), AlertSettings(), 53.55, 9.99, 50f))
        // a camera that stays a minute while you drive (a dashcam behind you) does ring
        clock += CAMERA_DRIVING_MS
        assertEquals(AlertReason.CAMERA, session.add(cam(clock), emptyList(), AlertSettings(), 53.56, 9.99, 50f)?.reason)
    }

    @Test fun standingStillACameraRingsAtOnce() {
        assertEquals(AlertReason.CAMERA, session.add(cam(clock), emptyList(), AlertSettings(), 53.55, 9.99, 0f)?.reason)
    }

    @Test fun cooldownPerDevice() {
        assertNotNull(session.add(cam(clock), emptyList(), AlertSettings()))
        clock += 60_000
        assertNull(session.add(cam(clock), emptyList(), AlertSettings()))
        clock += COOLDOWN_MS
        assertNotNull(session.add(cam(clock), emptyList(), AlertSettings()))
    }

    @Test fun muteWinsOverWatch() {
        val rules = listOf(Rule(RuleKind.WATCH, RuleField.PREFIX, "004B12"), Rule(RuleKind.MUTE, RuleField.NAME_CONTAINS, "V380"))
        assertNull(session.add(cam(clock), rules, AlertSettings()))
        assertEquals(AlertReason.WATCH, session.add(cam(clock + 1), rules.take(1), AlertSettings())?.reason)
    }

    @Test fun trackerAlertsOnlyAfterFollowingTenMinutes() {
        val tag = { t: Long -> ble(mfg = mapOf(APPLE to b(0x12, 0x19, 0x10))).let { Sighting(it.radio, it.address, null, -60, t, manufacturerData = it.manufacturerData) } }
        val start = clock
        for (m in 0..9) {
            clock = start + m * 60_000L
            assertNull("minute $m", session.add(tag(clock), emptyList(), AlertSettings()))
        }
        clock = start + FOLLOW_MS
        assertEquals(AlertReason.TRACKER_FOLLOWING, session.add(tag(clock), emptyList(), AlertSettings())?.reason)
    }

    @Test fun withGpsATagMustComeAlongToThreePlaces() {
        val tag = { t: Long -> Sighting(Radio.BLE, "C6:00:00:00:00:0A", null, -60, t, manufacturerData = mapOf(APPLE to b(0x12, 0x19, 0x10))) }
        val start = clock
        // 20 minutes at home: a lost tag next door, never an alert
        for (m in 0..20) { clock = start + m * 60_000L; assertNull(session.add(tag(clock), emptyList(), AlertSettings(), 53.5500, 9.9900)) }
        assertEquals(1, session.devices.values.single().places)
        // then it rides along 400 m and another 400 m
        clock += 60_000; assertNull(session.add(tag(clock), emptyList(), AlertSettings(), 53.5536, 9.9900))
        clock += 60_000
        assertEquals(AlertReason.TRACKER_FOLLOWING, session.add(tag(clock), emptyList(), AlertSettings(), 53.5572, 9.9900)?.reason)
        assertEquals(3, session.devices.values.single().places)
    }

    @Test fun aCarThatComesAlongIsReportedYourOwnNever() {
        val start = clock
        val mine = { t: Long -> Sighting(Radio.WIFI, "3A:00:00:00:00:25", "BMW 10003", -36, t, 2437, Security.WPA2) }
        val vw = { t: Long -> Sighting(Radio.WIFI, "A6:00:00:00:00:29", "My VW 1013", -84, t, 2437, Security.WPA2) }
        val traffic = AlertSettings(traffic = true)
        session.add(mine(start), emptyList(), traffic, 53.5500, 9.9900)
        clock += 3 * 60_000
        val alerts = mutableListOf<AlertReason>()
        for (i in 0..6) { // driving north, 400 m per step, one minute apart
            clock += 60_000
            val lat = 53.5500 + i * 0.0036
            session.add(mine(clock), emptyList(), traffic, lat, 9.99)?.let { alerts += it.reason }
            session.add(vw(clock), emptyList(), traffic, lat, 9.99)?.let { alerts += it.reason }
        }
        assertEquals(listOf(AlertReason.VEHICLE_FOLLOWING), alerts) // the VW once, my own BMW never
        assertTrue(session.devices.values.first { it.name == "BMW 10003" }.own)
        assertNull(session.add(vw(clock + 1), emptyList(), AlertSettings(), 53.58, 9.99)) // and only with traffic alerts on
    }

    @Test fun aGapRestartsTheFollowingClock() {
        val tag = { t: Long -> Sighting(Radio.BLE, "C6:00:00:00:00:09", null, -60, t, manufacturerData = mapOf(APPLE to b(0x12, 0x19, 0x10))) }
        val start = clock
        session.add(tag(start), emptyList(), AlertSettings())
        clock = start + GAP_MS + 60_000
        session.add(tag(clock), emptyList(), AlertSettings())
        clock = start + FOLLOW_MS + 60_000
        assertNull(session.add(tag(clock), emptyList(), AlertSettings()))
    }

    @Test fun trafficAlertsNeedTheOptIn() {
        val bus = wifi("00:30:44:24:6A:3C", "BUS-2471", t = clock)
        assertNull(session.add(bus, emptyList(), AlertSettings()))
        val axon = wifi("00:25:DF:31:0A:4E", "x", t = clock) // Axon: NRW body cams, so more than "possible" since 1.4
        assertEquals(AlertReason.POLICE_PROBABLE, session.add(axon, emptyList(), AlertSettings(traffic = true))?.reason)
        val moto = wifi("00:04:56:00:00:01", "x", t = clock) // another public safety maker stays "possible"
        val r = session.add(moto, emptyList(), AlertSettings(traffic = true))?.reason
        assertTrue(r == null || r == AlertReason.PRIORITY)
        val bus2 = wifi("00:30:44:24:6A:3D", "BUS-2472", t = clock)
        assertEquals(AlertReason.FLEET, session.add(bus2, emptyList(), AlertSettings(traffic = true))?.reason)
    }

    @Test fun demoShowsEveryCategoryAndATrackerFollowing() {
        val s = Session(Classifier(realOui)) { 1_000_000_000L }
        val alerts = demoSightings(1_000_000_000L).mapNotNull { s.add(it, emptyList(), AlertSettings(traffic = true)) }
        assertTrue(alerts.any { it.reason == AlertReason.TRACKER_FOLLOWING })
        val cats = s.devices.values.map { it.cls.category }.toSet()
        for (c in listOf(Category.TRACKER, Category.PRIORITY, Category.CAMERA, Category.FLEET, Category.PUBLIC_WIFI, Category.HOME, Category.PERSONAL, Category.UNKNOWN))
            assertTrue("demo lacks $c", c in cats)
    }
}

class ChannelTest {
    private fun ap(freq: Int, rssi: Int = -50) = Device(Radio.WIFI, "00:00:00:00:00:01", "x", rssi, rssi, freq, null, 0, 0, 1,
        Classification(null, false, Category.UNKNOWN, null, emptyList(), null, null))

    @Test fun channelNumbers() {
        assertEquals(1, channelOf(2412)); assertEquals(13, channelOf(2472)); assertEquals(14, channelOf(2484))
        assertEquals(36, channelOf(5180)); assertEquals(149, channelOf(5745)); assertEquals(1, channelOf(5955))
        assertNull(channelOf(900))
        assertTrue(isDfs(52)); assertFalse(isDfs(36))
    }

    @Test fun picksTheQuietChannel() {
        val busy = listOf(ap(2412), ap(2412), ap(2437), ap(2462, -85), ap(5180), ap(5200), ap(5220))
        val tips = channelTips(busy)
        val t24 = tips.first { it.band == Band.GHZ_2_4 }
        assertEquals(13, t24.channel) // 11 has a weak neighbour on it, 13 only feels that one from 2 channels away
        val t5 = tips.first { it.band == Band.GHZ_5 }
        assertEquals(48, t5.channel) // first free channel without DFS
        assertFalse(t5.dfs)
    }

    @Test fun emptyAirPrefersChannelOne() {
        assertEquals(1, channelTips(emptyList()).first { it.band == Band.GHZ_2_4 }.channel)
    }
}

class SecurityTest {
    @Test fun capabilities() {
        assertEquals(Security.WPA3, securityOf("[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]"))
        assertEquals(Security.WPA2, securityOf("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]"))
        assertEquals(Security.OWE, securityOf("[RSN-OWE-CCMP][ESS]"))
        assertEquals(Security.WEP, securityOf("[WEP][ESS]"))
        assertEquals(Security.OPEN, securityOf("[ESS]"))
    }
}

class PoliceTest {
    private var clock = 1_000_000_000L
    private val traffic = AlertSettings(traffic = true)

    private fun drive(session: Session, vararg s: (Long) -> Sighting): List<AlertReason> {
        val out = mutableListOf<AlertReason>()
        for (i in 0..6) { // north through Dortmund (NRW), 400 m per minute
            clock += 60_000
            val lat = 51.5000 + i * 0.0036
            for (f in s) session.add(f(clock), emptyList(), traffic, lat, 7.47)?.let { out += it.reason }
        }
        return out
    }

    @Test fun axonBluetoothIsProbablyPolice() {
        val session = Session(Classifier(realOui)) { clock }
        val axon = Sighting(Radio.BLE, "00:25:DF:12:34:56", null, -70, clock)
        val a = session.add(axon, emptyList(), traffic, 51.50, 7.47)
        assertEquals(AlertReason.POLICE_PROBABLE, a?.reason)
        assertEquals(PoliceLevel.PROBABLE, session.devices.values.single().police?.level)
    }

    @Test fun aFollowingCarWithBodycamNearbyIsProbablyAPatrolCar() {
        val session = Session(Classifier(realOui)) { clock }
        val vw = { t: Long -> Sighting(Radio.WIFI, "A6:00:00:00:00:29", "My VW 1013", -84, t, 2437, Security.WPA2) }
        val cam = { t: Long -> Sighting(Radio.WIFI, "00:25:DF:31:0A:4E", "x", -80, t, 2462, Security.WPA2) }
        clock += 3 * 60_000 // not "own": both appear after the first two minutes
        val reasons = drive(session, vw, cam)
        assertTrue(reasons.toString(), AlertReason.POLICE_PROBABLE in reasons)
        assertEquals(PoliceLevel.PROBABLE, session.devices.values.first { it.name == "My VW 1013" }.police?.level)
    }

    @Test fun aFollowingFordInNrwIsOnlyPossible() {
        val session = Session(Classifier(realOui)) { clock }
        clock += 3 * 60_000
        drive(session, { t -> Sighting(Radio.WIFI, "86:00:00:00:00:27", "My Ford 1011", -78, t, 2437, Security.WPA2) })
        val ford = session.devices.values.single()
        assertEquals(PoliceLevel.POSSIBLE, ford.police?.level)
        assertNull(policeFor(ford, emptyList(), 48.1, 11.5)) // Munich: the NRW fleet rule doesn't hold
    }

    @Test fun anOrdinaryCarIsNothing() {
        val session = Session(Classifier(realOui)) { clock }
        clock += 3 * 60_000
        drive(session, { t -> Sighting(Radio.WIFI, "D6:00:00:00:00:2A", "Audi_MMI_1002", -80, t, 2437, Security.WPA2) })
        assertNull(session.devices.values.single().police)
    }
}
