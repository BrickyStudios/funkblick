package io.github.brickystudios.funkblick.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brickystudios.funkblick.core.Place
import io.github.brickystudios.funkblick.core.arrow
import io.github.brickystudios.funkblick.core.tr
import io.github.brickystudios.funkblick.scan.Funk
import io.github.brickystudios.funkblick.scan.Nav
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Colours for the dark car look and the light discreet look. */
private class Look(val bg: Color, val card: Color, val ink: Color, val dim: Color, val accent: Color)

private fun look(light: Boolean) = if (light) Look(Color.White, Color.White, Color(0xFF202124), Color(0xFF5A5F66), Color(0xFF3D7BE0))
else Look(C.ink, C.panel2, C.text, C.dim, C.amber)

private fun meters(m: Double) = if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1000)

/** Next turn, distance to it, what's left and when you arrive. Shows nothing without a destination. */
@Composable
fun NavBanner(light: Boolean, modifier: Modifier = Modifier) {
    val nav by Nav.state.collectAsStateWithLifecycle()
    val fix by Funk.fix.collectAsStateWithLifecycle()
    val running by Funk.running.collectAsStateWithLifecycle()
    val n = nav ?: return
    val c = look(light)
    val p = n.progress
    val m = p?.next
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.card).padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (m != null && !p.arrived) Text(arrow(m.type), color = c.accent, fontSize = 40.sp, modifier = Modifier.width(52.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                p?.arrived == true -> Text(tr("Ziel erreicht", "You have arrived"), color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                p != null && m != null -> {
                    Text(meters(p.toNext), color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = if (light) null else Display)
                    Text(m.text, color = c.ink, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 2)
                    val eta = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(System.currentTimeMillis() + (p.leftSeconds * 1000).toLong()))
                    Text(tr("${meters(p.left)} · Ankunft $eta", "${meters(p.left)} · arrive $eta"), color = c.dim, fontSize = 14.sp)
                    n.error?.let { Text(it, color = c.accent, fontSize = 14.sp, lineHeight = 18.sp) } // a failed new route, while off the old one
                }
                n.error != null -> Text(n.error, color = c.ink, fontSize = 16.sp, lineHeight = 20.sp)
                !running -> Text(tr("Startet, sobald der Scan läuft", "Starts once the scan runs"), color = c.ink, fontSize = 18.sp)
                fix == null -> Text(tr("Warte auf GPS", "Waiting for GPS"), color = c.ink, fontSize = 18.sp)
                else -> Text(tr("Route nach ${n.dest.name} …", "Route to ${n.dest.name} …"), color = c.ink, fontSize = 18.sp, maxLines = 2)
            }
            if (n.busy && p != null) Text(tr("Neue Route …", "New route …"), color = c.accent, fontSize = 14.sp)
        }
        val stop = tr("Navigation beenden", "End navigation")
        Box(Modifier.size(56.dp).clickable(role = Role.Button) { Nav.stop() }.semantics { contentDescription = stop }, contentAlignment = Alignment.Center) {
            Text("✕", color = c.dim, fontSize = 22.sp)
        }
    }
}

/** One place in the list: tap to drive there; the small button on the right does [onAction]. */
@Composable
private fun PlaceRow(p: Place, c: Look, icon: String?, title: String = p.name, action: String, actionLabel: String, actionOn: Boolean = false,
                     onAction: () -> Unit, onGo: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Text(icon, color = c.accent, fontSize = 24.sp, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onGo).heightIn(min = 56.dp).padding(vertical = 8.dp)) {
            Text(title, color = c.ink, fontSize = 17.sp)
            val sub = if (title == p.name) p.detail else listOf(p.name, p.detail).filter { it.isNotEmpty() }.joinToString(", ")
            if (sub.isNotEmpty()) Text(sub, color = c.dim, fontSize = 14.sp)
        }
        Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onAction).semantics { contentDescription = actionLabel },
            contentAlignment = Alignment.Center) {
            Text(action, color = if (actionOn) c.accent else c.dim, fontSize = 20.sp)
        }
    }
}

/**
 * Where to: a search box, the places Photon finds (near you first), tap one to start. Says plainly who learns what.
 * Honours "Load map data from the network": off means no search and no route either.
 */
@Composable
fun DestinationSearch(light: Boolean, onClose: () -> Unit) {
    val settings by Funk.settings.collectAsStateWithLifecycle()
    val home by Funk.navHome.collectAsStateWithLifecycle()
    val c = look(light)
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun go() {
        if (q.isBlank() || busy) return
        busy = true
        scope.launch {
            val r = Nav.search(q.trim(), Funk.fix.value)
            failed = r == null; results = r.orEmpty(); busy = false
        }
    }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true, enabled = settings.mapData,
                placeholder = { Text(tr("Wohin?", "Where to?"), color = c.dim) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go() }),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.accent,
                    focusedBorderColor = c.accent, unfocusedBorderColor = c.dim))
            TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text(tr("Abbrechen", "Cancel"), color = c.accent, fontSize = 16.sp) }
        }
        if (!settings.mapData) {
            Text(tr("Navigation braucht das Netz. Unter Mehr „Kartendaten aus dem Netz laden“ einschalten.",
                "Navigation needs the network. Turn on \"Load map data from the network\" under More."), color = c.ink, fontSize = 16.sp)
            return@Column
        }
        when {
            busy -> Text(tr("Suche …", "Searching …"), color = c.dim, fontSize = 16.sp)
            failed -> Text(tr("Keine Verbindung.", "No connection."), color = c.ink, fontSize = 16.sp)
            results?.isEmpty() == true -> Text(tr("Nichts gefunden.", "Nothing found."), color = c.ink, fontSize = 16.sp)
        }
        LazyColumn(Modifier.weight(1f)) {
            home?.let { h ->
                item {
                    PlaceRow(h, c, "⌂", title = tr("Nach Hause", "Home"), action = "✕", actionLabel = tr("Zuhause löschen", "Forget home"),
                        onAction = { Funk.setNavHome(null) }) { Nav.start(h); onClose() }
                }
            }
            items(results.orEmpty()) { p ->
                val saved = p == home
                PlaceRow(p, c, null, action = "⌂", actionLabel = tr("Als Zuhause speichern", "Save as home"), actionOn = saved,
                    onAction = { Funk.setNavHome(p) }) { Nav.start(p); onClose() }
            }
            if (home == null) item {
                Text(tr("⌂ neben einem Treffer speichert ihn als Zuhause. Er bleibt nur auf diesem Handy.",
                    "⌂ next to a result saves it as home. It stays on this phone only."), color = c.dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        Text(tr("Suche über Photon (komoot, sieht den Suchbegriff und grob deinen Ort), Route über FOSSGIS (OpenStreetMap Deutschland, sieht Start und Ziel). In Logs und Exporte kommt davon nichts. Kartendaten © OpenStreetMap.",
            "Search by Photon (komoot, sees the query and roughly where you are), routes by FOSSGIS (OpenStreetMap Germany, sees start and destination). None of it goes into logs or exports. Map data © OpenStreetMap."),
            color = c.dim, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
