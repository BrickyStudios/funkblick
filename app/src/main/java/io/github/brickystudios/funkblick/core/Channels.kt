package io.github.brickystudios.funkblick.core

enum class Band(val label: String) { GHZ_2_4("2,4 GHz"), GHZ_5("5 GHz"), GHZ_6("6 GHz") }

fun bandOf(freq: Int): Band? = when (freq) {
    in 2400..2500 -> Band.GHZ_2_4
    in 5150..5895 -> Band.GHZ_5
    in 5925..7125 -> Band.GHZ_6
    else -> null
}

fun channelOf(freq: Int): Int? = when {
    freq == 2484 -> 14
    freq in 2412..2472 -> (freq - 2407) / 5
    freq in 5160..5885 -> (freq - 5000) / 5
    freq in 5955..7115 -> (freq - 5950) / 5
    else -> null
}

/** EU 5 GHz channels 52–64 and 100–140 need DFS: the router listens for radar first and may have to switch. */
fun isDfs(channel: Int) = channel in 52..64 || channel in 100..144

data class ChannelUse(val band: Band, val channel: Int, val networks: Int, val strongest: Int)

data class ChannelTip(val band: Band, val channel: Int, val dfs: Boolean, val load: Double, val networks: Int)

/** Signal weight: a neighbour at -40 dBm hurts, one at -90 dBm barely matters. */
private fun weight(rssi: Int) = ((rssi + 95).coerceIn(5, 65)) / 65.0

fun channelUse(devices: Collection<Device>): List<ChannelUse> =
    devices.filter { it.radio == Radio.WIFI }
        .mapNotNull { d -> val b = bandOf(d.freqMhz); val c = channelOf(d.freqMhz); if (b == null || c == null) null else Triple(b, c, d.rssi) }
        .groupBy { it.first to it.second }
        .map { (k, v) -> ChannelUse(k.first, k.second, v.size, v.maxOf { it.third }) }
        .sortedWith(compareBy({ it.band }, { it.channel }))

// EU allows 2.4 GHz channels 1–13 (the US stops at 11), so 13 is a real option here.
private val CANDIDATES_24 = listOf(1, 6, 11, 13)
private val CANDIDATES_5 = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 132, 136, 140)

/**
 * Least crowded channel for your own router. 2.4 GHz channels are 20 MHz wide on a 5 MHz grid, so neighbours up to
 * 4 channels away overlap. 5 GHz: counted per 20 MHz channel, ties go to channels without DFS.
 * shortcut: ignores channel width (40/80/160 MHz) of neighbours; read ScanResult.channelWidth if tips turn out wrong.
 */
fun channelTips(devices: Collection<Device>): List<ChannelTip> {
    val aps = devices.filter { it.radio == Radio.WIFI }.mapNotNull { d -> channelOf(d.freqMhz)?.let { Triple(bandOf(d.freqMhz), it, d.rssi) } }
    fun tip(band: Band, candidates: List<Int>, overlap: Int): ChannelTip {
        val scored = candidates.map { c ->
            val near = aps.filter { it.first == band && kotlin.math.abs(it.second - c) <= overlap }
            val load = near.sumOf { (_, ch, rssi) -> weight(rssi) * (overlap + 1 - kotlin.math.abs(ch - c)) / (overlap + 1) }
            ChannelTip(band, c, band == Band.GHZ_5 && isDfs(c), load, near.size)
        }
        return scored.minWith(compareBy<ChannelTip>({ Math.round(it.load * 10) }, { it.dfs }))
    }
    return listOf(tip(Band.GHZ_2_4, CANDIDATES_24, 4), tip(Band.GHZ_5, CANDIDATES_5, 0))
}

/** Android's ScanResult.capabilities, e.g. "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]". Strongest scheme wins. */
fun securityOf(capabilities: String): Security {
    val c = capabilities.uppercase()
    return when {
        "SAE" in c -> Security.WPA3
        "OWE" in c -> Security.OWE
        "RSN" in c || "WPA2" in c -> Security.WPA2
        "WPA" in c -> Security.WPA
        "WEP" in c -> Security.WEP
        else -> Security.OPEN
    }
}
