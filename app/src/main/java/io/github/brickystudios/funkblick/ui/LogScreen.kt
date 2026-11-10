package io.github.brickystudios.funkblick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brickystudios.funkblick.core.PastSession
import io.github.brickystudios.funkblick.core.tr
import io.github.brickystudios.funkblick.scan.Funk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** An old log opened from More: the route with devices and alerts on the map, the review, and every device. */
@Composable
fun LogScreen(file: File, onClose: () -> Unit) {
    // a long drive is a 1 MB log: read it off the main thread
    val loaded by produceState<Result<PastSession>?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { Funk.openLog(file) } } }
    val settings by Funk.settings.collectAsStateWithLifecycle()
    val m = remember(settings.safeScreen) { Funk.screenMasker() }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val clock = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT) }
    val p = loaded?.getOrNull()
    Column(Modifier.fillMaxSize().background(C.ink).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction(tr("← Zurück", "← Back"), onClick = onClose)
            Spacer(Modifier.weight(1f))
            Text(p?.let { sessionLabel(it.startedAt) } ?: file.nameWithoutExtension.removePrefix("funkblick-").replace('_', ' '),
                fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.amber)
        }
        when {
            loaded == null -> Text(tr("Lädt …", "Loading ..."), color = C.dim, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            p == null -> Text(tr("Dieses Log lässt sich nicht lesen.", "This log can't be read."), color = C.red, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.track.size > 1 || p.devices.any { it.pos != null } || p.alerts.any { it.lat != null }) item {
                    FunkMap(Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(8.dp)), follow = false, past = p, onTap = { open = it })
                }
                // logs before 1.13 have no route file: only alerts carry a place, and in safe mode only to about 1 km
                if (p.track.size < 2) item { Text(tr("Ohne Route: Logs speichern die Strecke erst ab Version 1.13.", "No route: logs keep the drive only from version 1.13."), color = C.dim, fontSize = 14.sp) }
                item { Recap(p.devices, p.alerts, p.track, title = tr("Rückblick", "Review")) }
                val top = notable(p.devices)
                if (top.isNotEmpty()) {
                    item { Header(tr("Auffällig", "Notable"), trailing = "${top.size}") }
                    items(top) { d -> DeviceRow(d, m, 0, time = clock.format(java.util.Date(d.lastSeen))) { open = d.id } }
                }
                item { Header(tr("Alle Geräte, stärkstes Signal zuerst", "All devices, strongest first"), trailing = "${p.devices.size}") }
                // no keys: in safe mode two shortened addresses can collide
                items(p.devices.sortedByDescending { it.rssiMax }) { d -> DeviceRow(d, m, 0, time = clock.format(java.util.Date(d.lastSeen))) { open = d.id } }
            }
        }
    }
    p?.devices?.firstOrNull { it.id == open }?.let { d -> DeviceSheet(d, onFind = {}, onDismiss = { open = null }, past = true) }
}
