package io.github.brickystudios.funkblick.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brickystudios.funkblick.core.Radio
import io.github.brickystudios.funkblick.core.tr
import io.github.brickystudios.funkblick.scan.Funk
import kotlinx.coroutines.delay

/**
 * Find mode: one device, a big signal meter and a beep that speeds up as you get closer, like a metal detector.
 * Signal strength jumps around a lot, so the meter shows a smoothed value and the trend over the last seconds.
 */
@Composable
fun FindScreen(id: String, onStartScan: () -> Unit, onClose: () -> Unit, help: Boolean = false) {
    val devices by Funk.devices.collectAsStateWithLifecycle()
    val running by Funk.running.collectAsStateWithLifecycle()
    val d = devices.firstOrNull { it.id == id }
    val now = rememberNow()
    var smooth by remember { mutableFloatStateOf(d?.rssi?.toFloat() ?: -100f) }
    var trend by remember { mutableFloatStateOf(0f) }
    var sound by remember { mutableStateOf(true) }
    LaunchedEffect(d?.lastSeen) {
        val r = d?.rssi?.toFloat() ?: return@LaunchedEffect
        val before = smooth
        smooth = smooth * 0.6f + r * 0.4f
        trend = trend * 0.5f + (smooth - before)
    }
    val stale = d == null || now - d.lastSeen > 15_000
    val level = ((smooth + 100f) / 65f).coerceIn(0f, 1f)
    val shown by animateFloatAsState(if (stale) 0f else level, tween(400), label = "level")
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true // BLE scans run fast only while the screen is on
        onDispose { view.keepScreenOn = false }
    }
    val level2 by rememberUpdatedState(if (stale || !sound) -1f else level)
    LaunchedEffect(Unit) {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        try {
            while (true) {
                val l = level2
                if (l >= 0f) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                delay(if (l < 0f) 500L else (1400 - 1300 * l).toLong().coerceAtLeast(90))
            }
        } finally { tone.release() }
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(C.ink).statusBarsPadding().navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(tr("Peilen", "Find"), fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.amber, modifier = Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            val name = d?.let { Funk.screenMasker().name(it) ?: it.cls.tracker?.kind?.label } ?: "?"
            Text(name, color = C.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(d?.cls?.category?.short() ?: "", color = d?.cls?.category?.color() ?: C.dim, fontSize = 15.sp)
            Text(if (stale) "–" else "%.0f".format(smooth), style = data.copy(fontSize = 96.sp, fontWeight = FontWeight.Bold, color = if (stale) C.dim else C.amber))
            Text("dBm", style = label)
            Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                drawRoundRect(C.line, Offset.Zero, size, CornerRadius(18f))
                drawRoundRect(C.amber, Offset.Zero, Size(size.width * shown, size.height), CornerRadius(18f))
            }
            Text(
                when {
                    !running -> tr("Der Scan läuft nicht.", "The scan is not running.")
                    stale -> tr("Kein Signal seit über 15 s. Weiter suchen oder zurückgehen.", "No signal for over 15 s. Keep looking or go back.")
                    trend > 0.8f -> tr("Wärmer", "Warmer")
                    trend < -0.8f -> tr("Kälter", "Colder")
                    else -> tr("gleich", "steady")
                },
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = when { trend > 0.8f && !stale -> C.amber; else -> C.dim },
            )
            Text(
                if (d?.radio == Radio.WIFI) tr("WLAN aktualisiert nur alle 10–30 s. Langsam gehen.", "Wi-Fi updates only every 10–30 s. Walk slowly.")
                else tr("Handy langsam drehen und in Taschen, unter Sitze und an Rückseiten halten. Je höher die Zahl, desto näher.",
                    "Move the phone slowly over bags, under seats and along backs of things. The higher the number, the closer."),
                color = C.dim, fontSize = 14.sp, textAlign = TextAlign.Center,
            )
            if (help) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Was jetzt?", "What now?"), color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    for (s in listOf(
                        tr("1. Suchen: Taschen, Jacke, Rucksack, Auto. Die Zahl steigt, wenn du näher kommst.", "1. Search bags, jacket, backpack, car. The number rises as you get closer."),
                        tr("2. Gefunden? Behalten, nicht wegwerfen: es ist ein Beweismittel.", "2. Found it? Keep it, it is evidence."),
                        tr("3. Fühlst du dich bedroht, ruf an. Kostenlos und anonym:", "3. If you feel threatened, call. Free and anonymous in Germany:"),
                    )) Text(s, color = C.text, fontSize = 15.sp, lineHeight = 21.sp)
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((n, t) in listOf("110" to tr("Polizei", "Police"), "116016" to tr("Hilfetelefon", "Helpline")))
                            BigButton("$t $n", filled = false, color = C.red, modifier = Modifier.weight(1f), height = 48) {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$n")))
                            }
                    }
                }
            }
            Box(Modifier.weight(1f))
            if (!running) BigButton(tr("Scan starten", "Start scan"), filled = true, onClick = onStartScan)
            BigButton(if (sound) tr("Ton aus", "Sound off") else tr("Ton an", "Sound on"), filled = false) { sound = !sound }
            BigButton(tr("Schließen", "Close"), filled = false, color = C.dim, onClick = onClose)
        }
    }
}
