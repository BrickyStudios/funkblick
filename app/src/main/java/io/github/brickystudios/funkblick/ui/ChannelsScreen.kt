package io.github.brickystudios.funkblick.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brickystudios.funkblick.core.Band
import io.github.brickystudios.funkblick.core.ChannelUse
import io.github.brickystudios.funkblick.core.Radio
import io.github.brickystudios.funkblick.core.channelTips
import io.github.brickystudios.funkblick.core.channelUse
import io.github.brickystudios.funkblick.core.tr
import io.github.brickystudios.funkblick.scan.Funk

@Composable
fun ChannelsScreen() {
    val devices by Funk.devices.collectAsStateWithLifecycle()
    val wifi = devices.filter { it.radio == Radio.WIFI }
    val use = channelUse(wifi)
    val tips = channelTips(wifi)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("WLAN-Kanäle", "Wi-Fi channels"), fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.amber)
        Text(tr("Welcher Kanal ist bei dir am freiesten? Funkblick zählt die Netze in Reichweite und gewichtet sie nach Signalstärke. EU-Regeln: 2,4 GHz Kanal 1 bis 13.",
            "Which channel is the quietest here? Funkblick counts the networks in range and weighs them by signal. EU rules: 2.4 GHz channels 1 to 13."), color = C.dim, fontSize = 14.sp, lineHeight = 19.sp)
        if (wifi.isEmpty()) {
            Text(tr("Noch keine WLANs gesehen. Starte den Scan.", "No Wi-Fi networks seen yet. Start the scan."), color = C.dim, fontSize = 14.sp)
            return@Column
        }
        Header(tr("Empfehlung für deinen Router", "Suggestion for your router"))
        for (t in tips) Panel(accent = C.amber) {
            Column {
                Text(t.band.label, style = label)
                Text(tr("Kanal ${t.channel}", "Channel ${t.channel}"), style = data.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.amber))
                Text(
                    when {
                        t.networks == 0 -> tr("kein anderes Netz in der Nähe", "no other network near it")
                        else -> tr("${t.networks} Netze überlappen, meist schwach", "${t.networks} networks overlap, mostly weak")
                    } + if (t.dfs) tr(". DFS: der Router hört erst 1 Minute auf Radar", ". DFS: the router listens for radar for a minute first") else "",
                    color = C.dim, fontSize = 14.sp,
                )
            }
        }
        Text(tr("FRITZ!Box: unter WLAN, Funkkanal die Funkkanal-Einstellungen anpassen. Andere Router: im WLAN-Menü nach „Kanal“ suchen.",
            "FRITZ!Box: under Wi-Fi, Radio channel, adjust the radio channel settings. Other routers: look for \"channel\" in the Wi-Fi settings."), color = C.dim, fontSize = 13.sp, lineHeight = 17.sp)
        for (band in Band.entries) {
            val list = use.filter { it.band == band }
            if (list.isEmpty()) continue
            Header(band.label, trailing = tr("${list.sumOf { it.networks }} Netze", "${list.sumOf { it.networks }} networks"))
            Bars(band, list)
        }
    }
}

@Composable
private fun Bars(band: Band, list: List<ChannelUse>) {
    val measurer = rememberTextMeasurer()
    val channels = when (band) {
        Band.GHZ_2_4 -> (1..13).toList()
        else -> list.map { it.channel }.sorted()
    }
    val max = (list.maxOfOrNull { it.networks } ?: 1).coerceAtLeast(3)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val slot = size.width / channels.size
        val base = size.height - 18.dp.toPx()
        for ((i, ch) in channels.withIndex()) {
            val u = list.firstOrNull { it.channel == ch }
            val h = (u?.networks ?: 0) / max.toFloat() * (base - 16.dp.toPx())
            val strength = (((u?.strongest ?: -100) + 95) / 60f).coerceIn(0.25f, 1f)
            val w = slot * 0.62f
            val x = i * slot + (slot - w) / 2
            drawRoundRect(C.line, Offset(x, base - 2.dp.toPx()), Size(w, 2.dp.toPx()), CornerRadius(1f))
            if (u != null) {
                drawRoundRect(C.amber.copy(alpha = strength), Offset(x, base - h), Size(w, h), CornerRadius(3.dp.toPx()))
                drawText(measurer, "${u.networks}", Offset(x, base - h - 15.dp.toPx()), style = data.copy(fontSize = 13.sp, color = C.text))
            }
            if (band != Band.GHZ_2_4 || ch in listOf(1, 6, 11, 13) || u != null)
                drawText(measurer, "$ch", Offset(x, base + 3.dp.toPx()), style = data.copy(fontSize = 13.sp, color = C.dim))
        }
    }
}
