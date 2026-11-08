package io.github.brickystudios.funkblick.ui

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brickystudios.funkblick.core.Rule
import io.github.brickystudios.funkblick.core.RuleField
import io.github.brickystudios.funkblick.core.RuleKind
import io.github.brickystudios.funkblick.core.aiReport
import io.github.brickystudios.funkblick.core.isValid
import io.github.brickystudios.funkblick.core.label
import io.github.brickystudios.funkblick.core.toCsv
import io.github.brickystudios.funkblick.core.toJson
import io.github.brickystudios.funkblick.core.tr
import io.github.brickystudios.funkblick.scan.Funk
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hands a file to Android's share sheet (mail, Signal, files, an AI app ...). */
fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, file.name))
}

fun shareText(context: Context, name: String, text: String, mime: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, name).apply { writeText(text) }
    shareFile(context, f, mime)
}

private fun stamp() = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoreScreen(onEnableTraffic: () -> Unit, onEnableCarStart: () -> Unit, onSetup: () -> Unit, setupMissing: Int, onChannels: () -> Unit) {
    val context = LocalContext.current
    val s by Funk.settings.collectAsStateWithLifecycle()
    val rules by Funk.rules.collectAsStateWithLifecycle()
    val devices by Funk.devices.collectAsStateWithLifecycle()
    val diag by Funk.diag.collectAsStateWithLifecycle()
    val running by Funk.running.collectAsStateWithLifecycle()
    var addRule by remember { mutableStateOf(false) }
    var logsVersion by remember { mutableIntStateOf(0) }
    val logs = remember(logsVersion, running) { Funk.logs() }
    val now = rememberNow()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(tr("Mehr", "More"), fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.amber)
        Spacer(Modifier.height(12.dp))
        BigButton(
            if (setupMissing == 0) tr("Einrichtung prüfen: alles bereit", "Check setup: all set") else tr("Einrichtung: $setupMissing fehlt", "Setup: $setupMissing missing"),
            filled = false, color = if (setupMissing == 0) C.dim else C.red, onClick = onSetup,
        )
        Gap(8)
        BigButton(tr("WLAN-Kanäle für deinen Router", "Wi-Fi channels for your router"), filled = false, color = C.dim, onClick = onChannels)

        Header(tr("Warnungen", "Alerts"))
        Toggle(tr("Ton", "Sound"), tr("Eigener Ton für jede Art von Warnung", "Its own signal for every kind of alert"), s.sound) { v -> Funk.updateSettings { it.copy(sound = v) } }
        Toggle(tr("Sprachansage", "Voice"), tr("„Blitzer voraus“, „Tracker ist bei dir“ …", "\"Speed camera ahead\", \"tracker with you\" ..."), s.voice) { v -> Funk.updateSettings { it.copy(voice = v) } }
        Toggle(tr("Vibration", "Vibration"), null, s.vibrate) { v -> Funk.updateSettings { it.copy(vibrate = v) } }
        Toggle(tr("Tracker-Schutz", "Tracker protection"), tr("Warnt, wenn ein AirTag, SmartTag, Tile & Co. über 10 Minuten mit dir unterwegs ist", "Warns when an AirTag, SmartTag, Tile & co. stays with you for over 10 minutes"), s.trackers) { v -> Funk.updateSettings { it.copy(trackers = v) } }
        Toggle(tr("Kameras", "Cameras"), tr("Warnt bei Kameras mit eigenem WLAN, etwa in der Ferienwohnung", "Warns about cameras running their own Wi-Fi, e.g. in a holiday flat"), s.cameras) { v -> Funk.updateSettings { it.copy(cameras = v) } }
        Toggle(tr("Verkehrs-Hinweise", "Traffic alerts"), tr("Blitzer, mögliche Behörden- und Fahrzeugtechnik. Nur für Beifahrer (§ 23 Abs. 1c StVO)", "Speed cameras, possible public safety and vehicle gear. Passengers only (StVO § 23 (1c))"), s.traffic) { v ->
            if (v) onEnableTraffic() else Funk.updateSettings { it.copy(traffic = false) }
        }

        Toggle(tr("Im Auto anbieten", "Offer in the car"), tr("Verbindet sich das Handy per Bluetooth mit einem Auto, startet ein Tipp auf die Mitteilung den Auto-Modus. Dazu gibt es eine Kachel in den Schnelleinstellungen.",
            "When the phone connects to a car over Bluetooth, one tap on the notification starts car mode. There is also a quick settings tile."), s.carStart) { v ->
            if (v) onEnableCarStart() else Funk.updateSettings { it.copy(carStart = false) }
        }

        Header(tr("Datenschutz", "Privacy"))
        Toggle(tr("Sicherer Modus für Exporte und Logs", "Safe mode for exports and logs"), tr("Kürzt Adressen auf den Hersteller-Teil, versteckt Namen von Heimnetzen und Handys, rundet Positionen auf ca. 1 km", "Cuts addresses to the maker part, hides names of home networks and phones, rounds positions to about 1 km"), s.safeMode) { v -> Funk.updateSettings { it.copy(safeMode = v) } }
        Toggle(tr("Bildschirm schwärzen", "Mask the screen"), tr("Dasselbe auf dem Bildschirm, für Screenshots", "The same on screen, for screenshots"), s.safeScreen) { v -> Funk.updateSettings { it.copy(safeScreen = v) } }
        Toggle(tr("Logs speichern", "Keep logs"), tr("Jede Sitzung landet als JSON auf dem Handy, nur auf dem Handy", "Every session is saved as JSON on the phone, only there"), s.log) { v -> Funk.updateSettings { it.copy(log = v) } }
        Toggle(tr("Kartendaten aus dem Netz laden", "Load map data from the network"), tr("Jeder Kartenabruf verrät dem Kartenserver grob, wo du bist. Aus: nur offline gespeicherte Gebiete.",
            "Every map request tells the map server roughly where you are. Off: only areas saved for offline use."), s.mapData) { v ->
            Funk.updateSettings { it.copy(mapData = v) }
            org.maplibre.android.MapLibre.setConnected(v)
        }
        Toggle("Bluetooth", tr("Aus = nur WLAN, spart Akku", "Off = Wi-Fi only, saves battery"), s.ble) { v -> Funk.updateSettings { it.copy(ble = v) } }

        Header(tr("Eigene Regeln", "Your rules"), trailing = "${rules.size}")
        Text(tr("Beobachten = Warnung, sobald es auftaucht. Stumm = nie warnen. Stumm gewinnt.", "Watch = alert as soon as it shows up. Mute = never alert. Mute wins."), color = C.dim, fontSize = 13.sp)
        for (r in rules) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (r.kind == RuleKind.WATCH) tr("Beobachten", "Watch") else tr("Stumm", "Mute"), color = if (r.kind == RuleKind.WATCH) C.amber else C.dim, fontSize = 14.sp, modifier = Modifier.widthIn(min = 88.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(r.value, style = data.copy(fontSize = 14.sp))
                Text(r.field.label() + if (r.note.isNotBlank()) ", ${r.note}" else "", color = C.dim, fontSize = 13.sp)
            }
            TextButton({ Funk.setRules(rules - r) }) { Text(tr("Löschen", "Delete"), color = C.red) }
        }
        Spacer(Modifier.height(6.dp))
        BigButton(tr("Regel hinzufügen", "Add rule"), filled = false) { addRule = true }

        Header(tr("Export dieser Sitzung", "Export this session"), trailing = tr("${devices.size} Geräte", "${devices.size} devices"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("CSV", false) { shareText(context, "funkblick-${stamp()}.csv", toCsv(Funk.devices.value, Funk.masker()), "text/csv") }
            Chip("JSON", false) { shareText(context, "funkblick-${stamp()}.json", toJson(Funk.devices.value, Funk.meta(), Funk.masker(), Funk.alerts.value.reversed()), "application/json") }
            Chip(tr("KI-Bericht", "AI debrief"), false) { shareText(context, "funkblick-${stamp()}.md", aiReport(Funk.devices.value, Funk.meta(), Funk.masker()), "text/plain") }
            val track = { Funk.track.value.map { io.github.brickystudios.funkblick.core.TrackPoint(it.lat, it.lon, it.time) } }
            Chip(tr("GPX für Organic Maps", "GPX for Organic Maps"), false) {
                shareText(context, "funkblick-${stamp()}.gpx", io.github.brickystudios.funkblick.core.toGpx(Funk.devices.value, track(), Funk.alerts.value.reversed(), Funk.masker()), "application/gpx+xml")
            }
            Chip("GeoJSON", false) {
                shareText(context, "funkblick-${stamp()}.geojson", io.github.brickystudios.funkblick.core.toGeoJson(Funk.devices.value, track(), Funk.alerts.value.reversed(), Funk.masker()), "application/geo+json")
            }
        }
        Text(tr("Der KI-Bericht ist Text zum Einfügen in ChatGPT, Claude, Gemini oder ein lokales Modell. Funkblick selbst schickt nichts an eine KI.",
            "The AI debrief is text to paste into ChatGPT, Claude, Gemini or a local model. Funkblick itself sends nothing to any AI."), color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))

        Header(tr("Karte", "Map"))
        var offline by remember { mutableStateOf<String?>(null) }
        Text(tr("Speichert die Karte im Umkreis von etwa 25 km, damit sie ohne Netz läuft. Braucht 20 bis 80 MB.",
            "Saves the map within about 25 km so it works without network. Takes 20 to 80 MB."), color = C.dim, fontSize = 14.sp, lineHeight = 20.sp)
        TextAction(offline ?: tr("Karte der Umgebung offline speichern", "Save the map around you for offline use")) {
            val f = Funk.fix.value ?: run { offline = tr("Erst braucht es eine GPS-Position: Scan starten.", "Needs a GPS position first: start the scan."); return@TextAction }
            offline = tr("Lädt …", "Loading …")
            saveOfflineRegion(context, f.lat, f.lon, { p -> offline = tr("Lädt ${(p * 100).toInt()} %", "Loading ${(p * 100).toInt()} %") }) { ok ->
                offline = if (ok) tr("Offline gespeichert", "Saved for offline use") else tr("Speichern ging nicht, später nochmal", "Couldn't save, try again later")
            }
        }

        Header("Logs", trailing = "${logs.size}")
        if (logs.isNotEmpty()) BigButton(tr("Alle Logs in Downloads speichern", "Save all logs to Downloads"), filled = false) {
            val n = Funk.exportLogsToDownloads()
            android.widget.Toast.makeText(context, when {
                n < 0 -> tr("Geht erst ab Android 10. Nutze Teilen.", "Needs Android 10 or newer. Use Share.")
                else -> tr("$n Logs in Download/Funkblick gespeichert.", "$n logs saved to Download/Funkblick.")
            }, android.widget.Toast.LENGTH_LONG).show()
        }
        if (logs.isEmpty()) Text(tr("Noch keine Logs. Sie entstehen beim Scannen, jede Minute und beim Stoppen.", "No logs yet. They are written while scanning, every minute and on stop."), color = C.dim, fontSize = 13.sp)
        for (f in logs.take(30)) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.nameWithoutExtension.removePrefix("funkblick-").replace('_', ' '), style = data.copy(fontSize = 14.sp))
                Text("${f.length() / 1024} KB", color = C.dim, fontSize = 13.sp)
            }
            TextButton({ shareFile(context, f, "application/json") }) { Text(tr("Teilen", "Share"), color = C.amber) }
            TextButton({ f.delete(); logsVersion++ }) { Text(tr("Löschen", "Delete"), color = C.red) }
        }

        Header(tr("Tracker gefunden?", "Found a tracker?"))
        Text(tr("1. Ruhig bleiben, das Gerät nicht wegwerfen: es ist ein Beweismittel.\n2. Signal beobachten: wird es stärker, wenn du Taschen, Jacke oder Auto absuchst? AirTags piepen nach einiger Zeit getrennt vom Besitzer.\n3. Fühlst du dich bedroht, ruf die Polizei. Beratung, kostenlos und anonym:",
            "1. Stay calm and keep the device: it is evidence.\n2. Watch the signal: does it get stronger while you check bags, jacket or car? AirTags beep after a while away from their owner.\n3. If you feel threatened, call the police. Free and anonymous help in Germany:"),
            color = C.text, fontSize = 14.sp, lineHeight = 20.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            for ((n, t) in listOf("110" to tr("Polizei", "Police"), "116016" to tr("Hilfetelefon Gewalt gegen Frauen", "Violence against women helpline"), "116006" to tr("Weisser Ring (Opferhilfe)", "Weisser Ring (victim support)")))
                Chip("$t, ${n.chunked(3).joinToString(" ")}", false, C.red) { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))) }
        }

        Header(tr("Diagnose", "Diagnostics"))
        val lm = context.getSystemService(LocationManager::class.java)
        val locOn = lm != null && LocationManagerCompat.isLocationEnabled(lm)
        val rows = listOf(
            "Build" to Funk.build(),
            "System" to "${Funk.os} ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL}",
            tr("Standortdienst", "Location services") to if (locOn) tr("an", "on") else tr("AUS: WLAN-Scan liefert nichts", "OFF: Wi-Fi scan returns nothing"),
            tr("Dienst läuft seit", "Service running") to if (running) duration(now - diag.serviceSince) else "–",
            tr("WLAN-Scans", "Wi-Fi scans") to tr("${diag.wifiRequested} angefragt, ${diag.wifiThrottled} gedrosselt", "${diag.wifiRequested} requested, ${diag.wifiThrottled} throttled"),
            tr("WLAN zuletzt", "Wi-Fi last") to (diag.lastWifi.takeIf { it > 0 }?.let { ago(it, now) + ", ${diag.wifiResults} Netze" } ?: "–"),
            "Bluetooth" to when {
                diag.bleRunning -> tr("läuft", "running") + if (diag.bleFiltered) tr(" (Bildschirm aus: nur Tracker)", " (screen off: trackers only)") else ""
                diag.bleError != 0 -> tr("Fehler ${diag.bleError}", "error ${diag.bleError}")
                else -> tr("aus", "off")
            },
            tr("BLE-Pakete", "BLE adverts") to "${diag.bleAdverts}" + (diag.lastBle.takeIf { it > 0 }?.let { ", " + ago(it, now) } ?: ""),
            tr("GPS-Positionen", "GPS fixes") to "${diag.fixes}",
            tr("Töne gespielt", "Sounds played") to "${diag.sounds}",
            tr("Regeln", "Rules") to "${rules.size}",
            tr("Hersteller-Register", "Maker registry") to tr("${Funk.ouiSize} Präfixe (IEEE)", "${Funk.ouiSize} prefixes (IEEE)"),
            tr("Blitzer-Daten", "Speed cameras") to tr("${Funk.speedCams.size} feste Blitzer (OSM)", "${Funk.speedCams.size} fixed cameras (OSM)") +
                (Funk.speedCamsUpdated().takeIf { it > 0 }?.let { ", " + tr("Region aktualisiert ", "region refreshed ") + ago(it, now) } ?: ""),
        )
        for ((k, v) in rows) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(k, color = C.dim, fontSize = 13.sp, modifier = Modifier.widthIn(min = 140.dp))
            Text(v, style = data.copy(fontSize = 13.sp))
        }
        Text(tr("Android erlaubt 4 WLAN-Scans in 2 Minuten, also etwa alle 30 s. Im Auto ist das zu langsam: in den Entwickleroptionen die WLAN-Scan-Drosselung ausschalten, dann scannt Funkblick alle 10 s. Läuft der Scan im Hintergrund nicht durch, Akku-Optimierung für Funkblick abschalten.",
            "Android allows 4 Wi-Fi scans per 2 minutes, about one every 30 s. Too slow in a car: switch off Wi-Fi scan throttling in the developer options, then Funkblick scans every 10 s. If scanning stops in the background, turn off battery optimization for Funkblick."),
            color = C.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
        TextAction(tr("Akku-Optimierung öffnen", "Open battery optimization")) { runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }

        Header(tr("Über", "About"))
        Text(tr("Funkblick ist freie Software (GPL-3.0). Hersteller-Daten: IEEE Registration Authority. Blitzer: © OpenStreetMap-Mitwirkende, ODbL. Stimmen: Piper TTS mit Thorsten-Voice (CC0) und LJ Speech (gemeinfrei). Tracker-Formate nach dem Forschungsprojekt AirGuard (TU Darmstadt). Kein Projekt von Apple, Samsung, Google, Blitzer.de oder SØPHIA.",
            "Funkblick is free software (GPL-3.0). Maker data: IEEE Registration Authority. Speed cameras: © OpenStreetMap contributors, ODbL. Voices: Piper TTS with Thorsten-Voice (CC0) and LJ Speech (public domain). Tracker formats from the AirGuard research project (TU Darmstadt). Not affiliated with Apple, Samsung, Google, Blitzer.de or SØPHIA."),
            color = C.dim, fontSize = 13.sp, lineHeight = 18.sp)
        TextAction(tr("Quellcode auf GitHub öffnen", "Open the source on GitHub")) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BrickyStudios/funkblick"))) }
        Spacer(Modifier.height(24.dp))
    }

    if (addRule) RuleDialog({ Funk.setRules(rules + it); addRule = false }, { addRule = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(onSave: (Rule) -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(RuleKind.WATCH) }
    var field by remember { mutableStateOf(RuleField.NAME_CONTAINS) }
    var value by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val rule = Rule(kind, field, value.trim(), note.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.panel,
        title = { Text(tr("Neue Regel", "New rule"), color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(tr("Beobachten", "Watch"), kind == RuleKind.WATCH) { kind = RuleKind.WATCH }
                    Chip(tr("Stumm", "Mute"), kind == RuleKind.MUTE, C.dim) { kind = RuleKind.MUTE }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in RuleField.entries) Chip(f.label(), field == f) { field = f }
                }
                OutlinedTextField(value, { value = it }, singleLine = true, label = {
                    Text(when (field) {
                        RuleField.ADDRESS -> "AA:BB:CC:DD:EE:FF"
                        RuleField.PREFIX -> "00:30:44"
                        RuleField.VENDOR -> "Cradlepoint"
                        else -> tr("z. B. WIFIonICE", "e.g. WIFIonICE")
                    })
                }, isError = value.isNotBlank() && !rule.isValid())
                OutlinedTextField(note, { note = it }, singleLine = true, label = { Text(tr("Notiz (optional)", "Note (optional)")) })
            }
        },
        confirmButton = { TextButton({ onSave(rule) }, enabled = rule.isValid()) { Text(tr("Speichern", "Save"), color = if (rule.isValid()) C.amber else C.dim) } },
        dismissButton = { TextButton(onDismiss) { Text(tr("Abbrechen", "Cancel"), color = C.dim) } },
    )
}
