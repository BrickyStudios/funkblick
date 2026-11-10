package io.github.brickystudios.funkblick.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brickystudios.funkblick.core.Category
import io.github.brickystudios.funkblick.core.Confidence
import io.github.brickystudios.funkblick.core.Device
import io.github.brickystudios.funkblick.core.LoggedAlert
import io.github.brickystudios.funkblick.core.Masker
import io.github.brickystudios.funkblick.core.Radio
import io.github.brickystudios.funkblick.core.Rule
import io.github.brickystudios.funkblick.core.RuleField
import io.github.brickystudios.funkblick.core.RuleKind
import io.github.brickystudios.funkblick.core.Security
import io.github.brickystudios.funkblick.core.Source
import io.github.brickystudios.funkblick.core.bandOf
import io.github.brickystudios.funkblick.core.channelOf
import io.github.brickystudios.funkblick.core.distanceMeters
import io.github.brickystudios.funkblick.core.hexOf
import io.github.brickystudios.funkblick.core.isFollowingVehicle
import io.github.brickystudios.funkblick.core.label
import io.github.brickystudios.funkblick.core.tr
import io.github.brickystudios.funkblick.core.tripStats
import io.github.brickystudios.funkblick.core.TrackPoint
import io.github.brickystudios.funkblick.scan.ScanService
import io.github.brickystudios.funkblick.scan.Funk
import io.github.brickystudios.funkblick.scan.Nav
import kotlinx.coroutines.delay

/** Ticks once a second so "3 s ago" and the session clock stay current. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    return now
}

fun kindColor(kind: String): Color = when (kind) {
    "tracker", "again", "speedcam" -> C.red
    "priority", "police" -> C.blue
    "camera" -> Category.CAMERA.color()
    "hacker" -> Category.HACKER.color()
    "fleet", "follow" -> Category.FLEET.color()
    else -> C.amber
}

/** One alert: a tinted surface, the sentence, when. Announced to TalkBack (plan 6C). */
@Composable
fun AlertRow(a: LoggedAlert, now: Long, onClick: (() -> Unit)? = null) {
    val col = kindColor(a.kind)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(col.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { liveRegion = if (a.kind == "tracker" || a.kind == "again" || a.kind == "priority") LiveRegionMode.Assertive else LiveRegionMode.Polite }
            .padding(16.dp),
    ) {
        Text(a.text, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(ago(a.time, now), style = data.copy(fontSize = 13.sp, color = col))
    }
}

@Composable
fun BigButton(text: String, filled: Boolean, color: Color = C.amber, modifier: Modifier = Modifier, height: Int = 56, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().heightIn(min = height.dp).clip(RoundedCornerShape(8.dp))
            .background(if (filled) color else color.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (filled) C.ink else color)
    }
}

/** What deserves attention: watched, trackers, public safety, cameras, vehicle routers, vehicles following you. */
fun notable(devices: List<Device>) = devices.filter {
    it.cls.mute == null && (it.cls.watch != null || it.cls.category.ordinal <= Category.FLEET.ordinal || it.isFollowingVehicle())
}.sortedWith(compareBy<Device>({ if (it.cls.watch != null) -1 else it.cls.category.ordinal }, { -it.rssi }))

private fun Device.alerting() = cls.watch != null || isFollowingVehicle() || cls.category == Category.PRIORITY || police != null ||
    (cls.category == Category.TRACKER && cls.tracker?.separated == true)

/** Second line of a device row: what it is, and the one thing that makes it notable. */
fun Device.whyLine(): String {
    val min = (lastSeen - firstSeen) / 60_000
    return when {
        cls.watch != null -> tr("Beobachtet, deine Regel", "Watched, your rule")
        police?.level == io.github.brickystudios.funkblick.core.PoliceLevel.PROBABLE -> tr("Wahrscheinlich Polizei", "Probably police")
        police != null -> tr("Polizei möglich", "Police possible")
        isFollowingVehicle() -> tr("Fährt seit $min min mit, an $places Orten", "With you for $min min, at $places places")
        cls.category == Category.TRACKER && cls.tracker?.separated == true -> if (min > 0) tr("Getrennt vom Besitzer, seit $min min da", "Away from its owner, around for $min min") else tr("Getrennt vom Besitzer", "Away from its owner")
        own -> tr("Dein Gerät", "Yours")
        security == Security.OPEN -> cls.category.label() + tr(", offen", ", open")
        else -> cls.category.label()
    }
}

/** A device as a calm row: dot, name, why it matters, signal on the right (plan 4A). */
@Composable
fun DeviceRow(d: Device, m: Masker, now: Long, time: String? = null, onClick: () -> Unit) {
    val col = if (d.police != null) C.blue else d.cls.category.color()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (d.alerting()) col.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (d.radio == Radio.WIFI) Dot(col, 10) else Box(Modifier.width(10.dp).height(10.dp).background(col))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name(d) ?: d.cls.tracker?.kind?.label ?: tr("Ohne Namen", "No name"), color = C.text, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.whyLine(), color = if (d.alerting()) col else C.dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("${d.rssi} dBm", style = data)
            Text(time ?: ago(d.lastSeen, now), style = data.copy(fontSize = 13.sp, color = C.dim))
        }
    }
}

/** What is switched off right now that stops alerts (plan 2E). Checked every second while scanning. */
private fun missing(c: Context): Pair<String, Intent>? {
    val lm = c.getSystemService(LocationManager::class.java)
    val wifi = c.applicationContext.getSystemService(WifiManager::class.java)
    val bt = c.getSystemService(BluetoothManager::class.java)?.adapter
    return when {
        lm != null && !LocationManagerCompat.isLocationEnabled(lm) -> tr("Standort aus: keine WLAN-Scans", "Location off: no Wi-Fi scans") to Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        bt != null && !bt.isEnabled && Funk.settings.value.ble -> tr("Bluetooth aus: keine Tracker-Warnung", "Bluetooth off: no tracker alerts") to ScanService.radioOn(c, bt = true)
        wifi != null && !wifi.isWifiEnabled && !wifi.isScanAlwaysAvailable -> tr("WLAN aus: keine WLAN-Scans", "Wi-Fi off: no Wi-Fi scans") to ScanService.radioOn(c, bt = false)
        else -> null
    }
}

/** Map chip "All devices": also devices without a position, like the radar. Outside the screen so tabs and car mode keep it. */
private var all by mutableStateOf(false)

@Composable
fun LiveScreen(running: Boolean, demo: Boolean, onStart: () -> Unit, onStop: () -> Unit, onDemo: () -> Unit,
               onOpen: (Device) -> Unit, onFind: (String) -> Unit, onCar: () -> Unit, onDiscreet: () -> Unit) {
    val context = LocalContext.current
    val devices by Funk.devices.collectAsStateWithLifecycle()
    val alerts by Funk.alerts.collectAsStateWithLifecycle()
    val diag by Funk.diag.collectAsStateWithLifecycle()
    val settings by Funk.settings.collectAsStateWithLifecycle()
    val fix by Funk.fix.collectAsStateWithLifecycle()
    val track by Funk.track.collectAsStateWithLifecycle()
    val navOn = Nav.state.collectAsStateWithLifecycle().value != null
    val now = rememberNow()
    val m = remember(settings.safeScreen) { Funk.screenMasker() }
    val top = notable(devices)
    // 1A: the map once GPS knows where you are and you move; a hand-picked view holds until the next scan
    var manual by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(diag.serviceSince) { manual = null }
    val moved = track.size > 1 && distanceMeters(track.first().lat, track.first().lon, track.last().lat, track.last().lon) >= 50
    val auto = fix != null && ((fix?.speedKmh ?: 0f) > 8f || moved)
    val showMap = (manual ?: auto) && fix != null
    val miss = if (running) remember(now / 1000) { missing(context) } else null
    val hasSession = !running && !demo && devices.isNotEmpty()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Funkblick", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.amber)
                Spacer(Modifier.weight(1f))
                when {
                    running -> Text(duration(now - diag.serviceSince), style = data.copy(color = C.amber))
                    demo -> Text("Demo", fontSize = 14.sp, color = C.dim)
                    else -> Text(tr("Aus", "Off"), fontSize = 14.sp, color = C.dim)
                }
            }
        }
        // left car or discreet mode while a route runs: the turn and the way to end it stay in reach
        if (navOn) item { NavBanner(light = false) }
        if (miss != null) item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.red.copy(alpha = 0.14f))
                .clickable(role = Role.Button) { runCatching { context.startActivity(miss.second) } }.heightIn(min = 48.dp).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(miss.first, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(tr("Einschalten", "Turn on"), color = C.red, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (fix != null || running) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Radar", !showMap) { manual = false }
                Chip(if (fix == null) tr("Karte, warte auf GPS", "Map, waiting for GPS") else tr("Karte", "Map"), showMap) { if (fix != null) manual = true }
                if (showMap) Chip(tr("Alle Geräte", "All devices"), all) { all = !all }
            }
        }
        item {
            if (showMap) FunkMap(Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(8.dp)), nearby = all, onTap = { id ->
                devices.firstOrNull { it.id == id }?.let(onOpen)
            }, onFollowLost = {})
            else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Radar(devices, running || demo, Modifier.widthIn(max = 400.dp).fillMaxWidth(), onTap = onOpen)
            }
        }
        if (!showMap && devices.isNotEmpty()) item { Legend(devices) }
        alerts.firstOrNull()?.takeIf { now - it.time < 180_000 }?.let { a ->
            item(key = "alert-${a.time}") {
                AlertRow(a, now, onClick = if (a.kind == "tracker" || a.kind == "again") ({ devices.firstOrNull { it.address == a.address }?.let { onFind(it.id) } }) else null)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // the demo shows the modes too, so they can be tried before the first scan
                if (running || demo) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(tr("Auto-Modus", "Car mode"), filled = false, modifier = Modifier.weight(1f), onClick = onCar)
                    BigButton(tr("Diskret", "Discreet"), filled = false, color = C.dim, modifier = Modifier.weight(1f), onClick = onDiscreet)
                }
                // up here, not below the list: the first drives showed nobody found it down there
                if (running) BigButton(tr("Scan stoppen", "Stop scan"), filled = false, color = C.text, onClick = onStop)
                if (!running) {
                    if (hasSession) Recap(devices, alerts, track.map { TrackPoint(it.lat, it.lon, it.time) })
                    BigButton(tr("Scan starten", "Start scan"), filled = true, onClick = onStart)
                    if (!demo && !hasSession) TextAction(tr("Erst die Demo ansehen", "See the demo first"), onClick = onDemo)
                }
            }
        }
        if (devices.isNotEmpty()) {
            item { Header(if (top.isEmpty()) tr("Stärkste Signale", "Strongest signals") else tr("Auffällig", "Notable"), trailing = if (top.isEmpty()) null else "${top.size}") }
            val rows = if (top.isEmpty()) devices.sortedByDescending { it.rssi }.take(5) else top.take(12)
            items(rows, key = { it.id }) { d -> DeviceRow(d, m, now) { if (d.cls.category == Category.TRACKER) onFind(d.id) else onOpen(d) } }
            if (top.isEmpty()) item { Text(tr("Nichts Auffälliges in Reichweite.", "Nothing notable in range."), color = C.dim, fontSize = 14.sp) }
        }
    }
}

/** The last session once the scan stopped (plan 2G, F1, F4): numbers, makers and every alert in order. The route is on the map above. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Recap(devices: List<Device>, alerts: List<LoggedAlert>, track: List<TrackPoint>, title: String? = null) {
    val s = remember(devices, track) { tripStats(devices, track) } ?: return
    val drive = s.km >= 1
    val clock = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    @Composable
    fun Num(k: String, v: String, warn: Boolean = false) = Column(Modifier.widthIn(min = 72.dp)) {
        Text(k, style = label)
        Text(v, style = data.copy(fontSize = 18.sp, color = if (warn) C.red else C.text))
    }
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title ?: if (drive) tr("Letzte Fahrt", "Last drive") else tr("Letzte Sitzung", "Last session"), color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Num(tr("Dauer", "Time"), duration(s.millis))
                if (drive) Num(tr("Strecke", "Distance"), "%.1f km".format(s.km))
                Num(tr("Geräte", "Devices"), "${s.devices}")
                s.perKm?.let { Num(tr("pro km", "per km"), "$it") }
                s.openPercent?.let { Num(tr("WLAN offen", "Open Wi-Fi"), "$it %") }
                Num(tr("Auto-WLANs", "Car Wi-Fi"), "${s.carWifi}")
                Num(tr("Fuhren mit", "Followed"), "${s.followed}", warn = s.followed > 0)
                if (s.police > 0) Num(tr("Behörde?", "Police?"), "${s.police}")
                if (s.trackers > 0) Num(tr("Tracker", "Trackers"), "${s.trackers}", warn = true)
            }
            if (s.makers.isNotEmpty()) Text(
                tr("Häufigste Hersteller: ", "Most common makers: ") + s.makers.joinToString(", ") { "${it.first} ${it.second}" },
                color = C.dim, fontSize = 14.sp,
            )
            if (alerts.isNotEmpty()) {
                Text(tr("Warnungen der Reihe nach", "Alerts in order"), style = label)
                for (a in alerts.sortedBy { it.time }.takeLast(if (title != null) 200 else 30)) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clock.format(java.util.Date(a.time)), style = data.copy(fontSize = 13.sp, color = C.dim), modifier = Modifier.width(56.dp))
                    Dot(kindColor(a.kind), 8)
                    Spacer(Modifier.width(8.dp))
                    Text(a.text, color = C.text, fontSize = 14.sp)
                }
            } else Text(tr("Keine Warnungen.", "No alerts."), color = C.dim, fontSize = 14.sp)
        }
    }
}

private enum class Filter { ALL, NOTABLE, TRACKER, PRIORITY, CAMERA, FLEET, CAR, PUBLIC, HOME, WATCHED, WIFI, BLE }

@Composable
fun ListScreen(onOpen: (Device) -> Unit) {
    val devices by Funk.devices.collectAsStateWithLifecycle()
    val settings by Funk.settings.collectAsStateWithLifecycle()
    val m = remember(settings.safeScreen) { Funk.screenMasker() }
    val now = rememberNow()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val shown = devices.filter {
        when (filter) {
            Filter.ALL -> true
            Filter.NOTABLE -> it in notable(devices)
            Filter.TRACKER -> it.cls.category == Category.TRACKER
            Filter.PRIORITY -> it.cls.category == Category.PRIORITY
            Filter.CAMERA -> it.cls.category == Category.CAMERA
            Filter.FLEET -> it.cls.category == Category.FLEET || it.isFollowingVehicle()
            Filter.CAR -> it.cls.category == Category.CAR
            Filter.PUBLIC -> it.cls.category == Category.PUBLIC_WIFI
            Filter.HOME -> it.cls.category == Category.HOME
            Filter.WATCHED -> it.cls.watch != null || it.cls.mute != null
            Filter.WIFI -> it.radio == Radio.WIFI
            Filter.BLE -> it.radio == Radio.BLE
        }
    }.sortedByDescending { it.rssi }
    Column {
        Text(tr("Alle Geräte", "All devices"), fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.amber,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
            val chips = listOf(
                Filter.ALL to (tr("Alle", "All") to C.amber), Filter.NOTABLE to (tr("Auffällig", "Notable") to C.amber),
                Filter.TRACKER to (Category.TRACKER.short() to Category.TRACKER.color()), Filter.PRIORITY to (Category.PRIORITY.short() to Category.PRIORITY.color()),
                Filter.CAMERA to (Category.CAMERA.short() to Category.CAMERA.color()), Filter.FLEET to (Category.FLEET.short() to Category.FLEET.color()),
                Filter.CAR to (Category.CAR.short() to Category.CAR.color()), Filter.PUBLIC to (Category.PUBLIC_WIFI.short() to Category.PUBLIC_WIFI.color()),
                Filter.HOME to (Category.HOME.short() to Category.HOME.color()), Filter.WATCHED to (tr("Regeln", "Rules") to C.amber),
                Filter.WIFI to (tr("WLAN", "Wi-Fi") to C.amber), Filter.BLE to ("Bluetooth" to C.amber),
            )
            items(chips) { (f, lc) -> Chip(lc.first, filter == f, lc.second) { filter = f } }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item { Text(tr("${shown.size} von ${devices.size}, stärkstes Signal zuerst", "${shown.size} of ${devices.size}, strongest first"), style = label, modifier = Modifier.padding(bottom = 8.dp)) }
            items(shown, key = { it.id }) { d -> DeviceRow(d, m, now) { onOpen(d) }; Divider() }
            if (shown.isEmpty()) item { Text(if (devices.isEmpty()) tr("Noch nichts gesehen. Starte den Scan auf Live.", "Nothing seen yet. Start the scan on Live.") else tr("Nichts in diesem Filter.", "Nothing in this filter."), color = C.dim, fontSize = 15.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceSheet(d: Device, onFind: () -> Unit, past: Boolean = false, onDismiss: () -> Unit) {
    val settings by Funk.settings.collectAsStateWithLifecycle()
    val rules by Funk.rules.collectAsStateWithLifecycle()
    val m = remember(settings.safeScreen) { Funk.screenMasker() }
    val col = d.cls.category.color()
    val hex = hexOf(d.address) ?: ""
    val own = rules.filter { (it.field == RuleField.ADDRESS && hexOf(it.value) == hex) || (it.field == RuleField.PREFIX && hex.startsWith(it.value.filter(Char::isLetterOrDigit).uppercase())) ||
        (it.field == RuleField.NAME_EXACT && d.name?.trim().equals(it.value, ignoreCase = true)) }
    fun add(kind: RuleKind, field: RuleField, value: String) {
        Funk.setRules(rules + Rule(kind, field, value, note = m.name(d) ?: d.cls.vendor ?: ""))
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.panel) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.cls.category.label(), color = col, fontSize = 14.sp)
            Text(m.name(d) ?: d.cls.tracker?.kind?.label ?: tr("Ohne Namen", "No name"), color = C.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(d.whyLine(), color = C.dim, fontSize = 15.sp)
            d.police?.let { Text(it.why, color = C.blue, fontSize = 15.sp, lineHeight = 21.sp) }
            if (!past) Funk.seenBefore(d).takeIf { it.isNotEmpty() }?.let { Text(tr("Schon gesehen: ", "Seen before: ") + it.take(3).joinToString(" · ") { t -> sessionLabel(t) }, color = C.amber, fontSize = 15.sp) }
            Gap(12)
            // an old log: the device is long gone, and its address may be shortened (safe mode), so no finding and no rules
            if (!past) BigButton(tr("Peilen: hingehen und finden", "Find it: walk towards it"), filled = true, onClick = onFind)
            Header(tr("Daten", "Details"))
            val rows = listOf(
                tr("Funk", "Radio") to if (d.radio == Radio.WIFI) tr("WLAN-Zugangspunkt", "Wi-Fi access point") else "Bluetooth LE",
                tr("Adresse", "Address") to m.address(d.address) + if (d.cls.randomAddress) tr(" (zufällig)", " (random)") else "",
                tr("Hersteller", "Maker") to (d.cls.vendor ?: tr("unbekannt", "unknown")),
                tr("Signal", "Signal") to "${d.rssi} dBm, max ${d.rssiMax}",
                tr("Kanal", "Channel") to (d.freqMhz.takeIf { it > 0 }?.let { "${channelOf(it)} (${bandOf(it)?.label ?: ""}, $it MHz)" } ?: "–"),
                tr("Sicherheit", "Security") to (d.security?.name ?: "–"),
                tr("Zuerst", "First seen") to if (past) sessionLabel(d.firstSeen) else ago(d.firstSeen),
                tr("Zuletzt", "Last seen") to if (past) sessionLabel(d.lastSeen) else ago(d.lastSeen),
                tr("Gesehen", "Seen") to "${d.seenCount}×" + if (d.places > 0) tr(", an ${d.places} Orten", ", at ${d.places} places") else "",
                tr("Position", "Position") to (d.pos?.let { tr("geschätzt, ±${it.radiusM.toInt()} m aus ${it.points} Messungen", "estimated, ±${it.radiusM.toInt()} m from ${it.points} readings") }
                    ?: tr("noch zu wenig Messpunkte", "not enough readings yet")),
            )
            for ((k, v) in rows) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(k, color = C.dim, fontSize = 14.sp, modifier = Modifier.widthIn(min = 112.dp))
                Text(v, style = data)
            }
            Header(tr("Warum diese Einordnung", "Why this classification"))
            for (e in d.cls.evidence) Row(Modifier.padding(vertical = 4.dp)) {
                Text(when (e.source) {
                    Source.REGISTRY -> tr("Register", "Registry"); Source.SIGNATURE -> tr("Hersteller", "Maker"); Source.PAYLOAD -> tr("Funk", "Radio")
                    Source.NAME -> "Name"; Source.RULE -> tr("Regel", "Rule")
                }, color = if (e.confidence == Confidence.HIGH) C.amber else C.dim, fontSize = 13.sp, modifier = Modifier.widthIn(min = 88.dp).padding(top = 2.dp))
                Text(e.text, color = C.text, fontSize = 15.sp, lineHeight = 21.sp)
            }
            if (d.cls.evidence.isEmpty()) Text(tr("Keine Hinweise: Zufallsadresse ohne Herstellerdaten.", "No clues: random address without maker data."), color = C.dim, fontSize = 15.sp)
            if (d.cls.category == Category.PRIORITY || d.cls.category == Category.FLEET || d.cls.category == Category.CAR)
                Text(tr("Ein Hersteller-Treffer sagt, wer die Hardware gebaut hat, nicht wer sie betreibt.",
                    "A maker match says who built the hardware, not who operates it."), color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            if (!past) Header(tr("Regeln", "Rules"))
            if (!past) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip(tr("Gerät beobachten", "Watch device"), false) { add(RuleKind.WATCH, RuleField.ADDRESS, d.address) }
                Chip(tr("Gerät stumm", "Mute device"), false, C.dim) { add(RuleKind.MUTE, RuleField.ADDRESS, d.address) }
                if (!d.cls.randomAddress && hex.length == 12) Chip(tr("Hersteller beobachten", "Watch maker"), false) { add(RuleKind.WATCH, RuleField.PREFIX, hex.take(6)) }
                if (d.name != null) Chip(tr("Name beobachten", "Watch name"), false) { add(RuleKind.WATCH, RuleField.NAME_EXACT, d.name) }
                // you saw it was police: from now on it counts as "probably". Needs something that stays: the address, else the name
                if (d.cls.police == null) when {
                    d.radio == Radio.WIFI || !d.cls.randomAddress -> Chip(tr("Als Polizei markieren", "Mark as police"), false, C.blue) { add(RuleKind.POLICE, RuleField.ADDRESS, d.address) }
                    d.name != null -> Chip(tr("Als Polizei markieren", "Mark as police"), false, C.blue) { add(RuleKind.POLICE, RuleField.NAME_EXACT, d.name) }
                }
                for (r in own) Chip(tr("Regel löschen: ", "Delete rule: ") + "${r.kind.label()}, ${r.field.label()}", false, C.red) { Funk.setRules(rules - r); onDismiss() }
            }
        }
    }
}
