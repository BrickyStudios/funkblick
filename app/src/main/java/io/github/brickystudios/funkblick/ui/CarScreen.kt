package io.github.brickystudios.funkblick.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brickystudios.funkblick.core.Category
import io.github.brickystudios.funkblick.core.WARN_METERS
import io.github.brickystudios.funkblick.core.isFollowingVehicle
import io.github.brickystudios.funkblick.core.tr
import io.github.brickystudios.funkblick.scan.Funk
import kotlinx.coroutines.launch

/** Opens the official Blitzer.de app (free or PRO) if installed, otherwise their map in the browser. */
fun openBlitzerDe(context: Context) {
    val pm = context.packageManager
    val app = listOf("de.blitzer.plus", "de.blitzer").firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
    context.startActivity((app ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.blitzer.de/karte/"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Car mode, full screen (plan 1D): the map on two thirds, below it speed, the next speed camera, four counters and the
 * last alert in big type. Screen stays on. Without GPS the radar stands in (plan 2C). Landscape: map left, bar right.
 */
@Composable
fun CarScreen(running: Boolean, onStart: () -> Unit, onEnableTraffic: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val settings by Funk.settings.collectAsStateWithLifecycle()
    val devices by Funk.devices.collectAsStateWithLifecycle()
    val alerts by Funk.alerts.collectAsStateWithLifecycle()
    val fix by Funk.fix.collectAsStateWithLifecycle()
    val cam by Funk.camAhead.collectAsStateWithLifecycle()
    val diag by Funk.diag.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    DisposableEffect(Unit) {
        val w = (context as? android.app.Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val near = devices.filter { now - it.lastSeen < 60_000 && it.cls.mute == null }
    val view = @Composable { mod: Modifier ->
        Box(mod.clip(RoundedCornerShape(8.dp))) {
            if (fix != null) {
                FunkMap(Modifier.fillMaxSize(), follow = follow, onFollowLost = { follow = false })
                if (!follow) Box(Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                    BigButton(tr("Mitfahren", "Follow"), filled = true, modifier = Modifier.width(140.dp), height = 48) { follow = true }
                }
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Radar(devices, running, Modifier.fillMaxHeight(), labels = false)
                Text(tr("Warte auf GPS", "Waiting for GPS"), color = C.dim, fontSize = 15.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
        }
    }
    val bar = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(fix?.speedKmh?.let { "%.0f".format(it) } ?: "–", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 56.sp, color = C.text, lineHeight = 56.sp)
                    Text("km/h", style = label)
                }
                Box(Modifier.weight(1f)) { CamGauge(settings.traffic, cam?.meters, cam?.cam?.maxspeed) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // "Follows" is no category: vehicles whose Wi-Fi came along over 3 places (Session.isFollowingVehicle)
                for (cat in listOf(Category.PRIORITY, Category.FLEET, null, Category.TRACKER)) {
                    val n = when (cat) {
                        null -> near.count { it.isFollowingVehicle() }
                        Category.PRIORITY -> near.count { it.cls.category == cat || it.police != null }
                        else -> near.count { it.cls.category == cat }
                    }
                    val col = cat?.color() ?: Category.FLEET.color()
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (n > 0) col.copy(alpha = 0.14f) else C.panel).padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$n", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = if (n > 0) col else C.dim)
                        Text(when (cat) { Category.PRIORITY -> tr("Behörde?", "Police?"); null -> tr("Folgt", "Follows"); Category.FLEET -> tr("Router", "Router"); else -> cat.short() },
                            fontSize = 13.sp, color = C.dim, maxLines = 1)
                    }
                }
            }
            alerts.firstOrNull()?.let { a ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(kindColor(a.kind).copy(alpha = 0.14f)).padding(16.dp)) {
                    Text(a.text, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                    Text(ago(a.time, now), style = data.copy(fontSize = 14.sp, color = kindColor(a.kind)))
                }
            } ?: Text(tr("Noch keine Meldung.", "No alerts yet."), color = C.dim, fontSize = 16.sp)
            if (!running) BigButton(tr("Scan starten", "Start scan"), filled = true, height = 64, onClick = onStart)
            if (!settings.traffic) BigButton(tr("Verkehrs-Hinweise einschalten", "Turn on traffic alerts"), filled = false, color = C.red, height = 64, onClick = onEnableTraffic)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Blitzer.de", filled = false, color = C.dim, height = 64, modifier = Modifier.weight(1f)) { openBlitzerDe(context) }
                BigButton(tr("Beenden", "Close"), filled = false, height = 64, modifier = Modifier.weight(1f), onClick = onClose)
            }
            if (settings.traffic) TextAction(if (refreshing) tr("Blitzer werden geladen", "Loading speed cameras") else tr("Blitzer der Region aktualisieren", "Refresh speed cameras for this region"), color = C.dim) {
                val f = fix ?: run { Toast.makeText(context, tr("Erst braucht es eine GPS-Position.", "Needs a GPS position first."), Toast.LENGTH_LONG).show(); return@TextAction }
                if (refreshing) return@TextAction
                refreshing = true
                scope.launch {
                    val n = Funk.refreshSpeedCams(f.lat, f.lon)
                    refreshing = false
                    Toast.makeText(context, n?.let { tr("$it Blitzer in der Region geladen.", "$it cameras loaded for the region.") } ?: tr("OpenStreetMap antwortet gerade nicht.", "OpenStreetMap isn't answering right now."), Toast.LENGTH_LONG).show()
                }
            }
            if (running && diag.wifiThrottled > 0) Text(
                tr("Android drosselt WLAN-Scans auf einen alle 30 s. In den Entwickleroptionen die WLAN-Scan-Drosselung ausschalten, dann alle 10 s.",
                    "Android throttles Wi-Fi scans to one every 30 s. Switch off Wi-Fi scan throttling in the developer options for one every 10 s."),
                color = C.amber, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Text(tr("Fahrer dürfen in Deutschland keine Funktionen nutzen, die Verkehrskontrollen anzeigen (§ 23 Abs. 1c StVO). Blitzer- und Behörden-Hinweise sind für Beifahrer.",
                "In Germany drivers must not use functions that point out traffic enforcement (StVO § 23 (1c)). Speed camera and public safety alerts are for passengers."),
                color = C.dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(C.ink).statusBarsPadding().navigationBarsPadding()) {
        if (maxWidth > maxHeight) Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            view(Modifier.weight(1.3f).fillMaxHeight())
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { bar() }
        } else Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            view(Modifier.fillMaxWidth().weight(2f))
            Column(Modifier.weight(1.4f).verticalScroll(rememberScrollState())) { bar() }
        }
    }
}

/** Distance ring that fills up as the camera comes closer, with a speed limit sign in the middle. */
@Composable
private fun CamGauge(enabled: Boolean, meters: Int?, limit: Int?) {
    when {
        !enabled -> Column { Text(tr("aus", "off"), fontFamily = Display, fontSize = 28.sp, color = C.dim); Text(tr("Blitzer", "Speed camera"), style = label) }
        meters == null -> Column { Text(tr("frei", "clear"), fontFamily = Display, fontSize = 28.sp, color = C.dim); Text(tr("kein Blitzer in $WARN_METERS m", "no camera within $WARN_METERS m"), style = label) }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                val p = 1f - meters / WARN_METERS.toFloat()
                Canvas(Modifier.fillMaxSize()) {
                    val s = 5.dp.toPx()
                    drawArc(C.line, -90f, 360f, false, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s))
                    drawArc(C.red, -90f, 360f * p, false, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s, cap = StrokeCap.Round))
                }
                if (limit != null) Box(Modifier.size(40.dp).clip(CircleShape).background(C.text), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(C.red).padding(5.dp).clip(CircleShape).background(C.text), contentAlignment = Alignment.Center) {
                        Text("$limit", color = C.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("$meters m", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = C.red)
        }
    }
}

/** The warning before traffic alerts go on. Off by default; the user has to read this and agree. */
@Composable
fun TrafficWarning(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.panel,
        title = { Text(tr("Nicht für Fahrer in Deutschland", "Not for drivers in Germany"), color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Verkehrs-Hinweise warnen vor festen Blitzern (OpenStreetMap) und melden mögliche Behörden- und Fahrzeugtechnik per Ton und Ansage.",
                    "Traffic alerts warn about fixed speed cameras (OpenStreetMap) and announce possible public safety and vehicle gear."), color = C.text, fontSize = 15.sp)
                Text(tr("§ 23 Abs. 1c StVO verbietet Fahrern, Geräte oder App-Funktionen zu nutzen, die Verkehrskontrollen anzeigen. Das kostet 75 € und einen Punkt. Das gilt auch, wenn das Handy des Beifahrers für dich warnt.",
                    "StVO § 23 (1c) forbids drivers in Germany to use devices or app functions that point out traffic enforcement: 75 € and a point in Flensburg, also when a passenger's phone warns for you."), color = C.text, fontSize = 15.sp)
                Text(tr("In der Schweiz sind Blitzerwarner ganz verboten, in Österreich Radarwarner.",
                    "Speed camera warners are banned altogether in Switzerland, radar detectors in Austria."), color = C.dim, fontSize = 14.sp)
                Text(tr("Ein Hersteller-Treffer heißt nicht, dass Polizei in der Nähe ist. Fahr nach Schildern.",
                    "A maker match doesn't mean police are nearby. Drive by the signs."), color = C.dim, fontSize = 14.sp)
            }
        },
        confirmButton = { TextButton(onAccept) { Text(tr("Verstanden, ich bin Beifahrer", "Understood, I'm a passenger"), color = C.red) } },
        dismissButton = { TextButton(onDismiss) { Text(tr("Aus lassen", "Keep off"), color = C.amber) } },
    )
}
