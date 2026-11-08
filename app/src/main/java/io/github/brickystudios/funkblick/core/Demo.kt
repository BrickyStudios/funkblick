package io.github.brickystudios.funkblick.core

// Invented surroundings for the demo mode and the README screenshots: a street with a train passing by.
// Addresses use real manufacturer prefixes so the classification is the same as in the field.

private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

/** Sightings spread over the last 14 minutes, so the tracker counts as following you. Oldest first. */
fun demoSightings(now: Long): List<Sighting> {
    val out = mutableListOf<Sighting>()
    fun wifi(addr: String, ssid: String, rssi: Int, freq: Int, sec: Security, ago: Long = 0) =
        Sighting(Radio.WIFI, addr, ssid, rssi, now - ago, freq, sec)
    val airtag = b(0x12, 0x19, 0x10, *IntArray(23) { (it * 37) and 0xFF }, 0x01, 0x00)
    out += listOf(
        wifi("00:04:0E:A1:22:F3", "FRITZ!Box 7590 KQ", -47, 2437, Security.WPA2),
        wifi("00:04:0E:A1:22:F4", "FRITZ!Box 7590 KQ", -55, 5180, Security.WPA2),
        wifi("00:12:BF:5C:10:7A", "WLAN-472913", -69, 2412, Security.WPA2),
        wifi("00:0E:59:7B:A4:C2", "Vodafone-A4C2", -76, 2462, Security.WPA2),
        wifi("00:0A:EB:11:9F:20", "Gastnetz Bäckerei", -71, 2437, Security.WPA3),
        wifi("E0:AF:4F:20:11:05", "Telekom", -73, 2412, Security.OPEN),
        wifi("40:40:6B:0C:7E:91", "WIFIonICE", -58, 5745, Security.OPEN),
        wifi("00:18:0A:6D:32:10", "eduroam", -78, 5260, Security.WPA2),
        wifi("00:4B:12:7F:3A:C9", "V380_8F2A1C", -62, 2437, Security.OPEN),
        wifi("00:30:44:24:6A:3C", "BUS-2471", -64, 2462, Security.WPA2),
        wifi("DA:A1:19:5E:02:77", "Pixel_4821", -59, 2437, Security.WPA3),
        wifi("00:25:DF:31:0A:4E", "AXON-B3-5521", -80, 2462, Security.WPA2),
        Sighting(Radio.BLE, "F2:31:9C:44:0E:11", null, -52, now, manufacturerData = mapOf(APPLE to b(0x10, 0x05, 0x1B, 0x1C, 0x7A, 0x22))),
        Sighting(Radio.BLE, "DA:3B:BF:01:72:E0", "Galaxy Buds2 Pro", -61, now, manufacturerData = mapOf(0x0075 to b(0x42, 0x09, 0x81))),
        Sighting(Radio.BLE, "E6:5F:01:AB:23:9D", "Forerunner 265", -70, now, manufacturerData = mapOf(0x0087 to b(0x01, 0x00))),
        Sighting(Radio.BLE, "CB:10:4D:72:93:01", null, -74, now, serviceData = mapOf(UUID_SAMSUNG to b(0x12, 0x00, 0x7A, 0x33, 0x10))),
        Sighting(Radio.BLE, "EF:22:01:9A:6C:4D", "Tile", -81, now, serviceUuids = setOf(UUID_TILE)),
        Sighting(Radio.BLE, "7A:13:6E:0F:21:C8", null, -88, now),
    )
    // the tag last, so the demo opens with its alert
    for (min in listOf(14L, 11L, 8L, 5L, 2L, 0L)) out += Sighting(Radio.BLE, "C6:7E:1A:33:90:5B", null, -66 + min.toInt() % 3, now - min * 60_000, manufacturerData = mapOf(APPLE to airtag))
    return out
}
