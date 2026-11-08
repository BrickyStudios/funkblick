package io.github.brickystudios.funkblick.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.brickystudios.funkblick.core.tr

/** One thing Funkblick needs. required = without it the scan gives nothing; the rest makes it better. */
class SetupItem(val title: String, val why: String, val ok: Boolean, val required: Boolean, val fix: String, val action: () -> Unit)

private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

private fun open(c: Context, action: String, pkg: Boolean = false) = runCatching {
    c.startActivity(Intent(action).apply { if (pkg) data = Uri.parse("package:${c.packageName}") })
}

/** Checked fresh every time the app comes back to the front, so returning from Android's settings updates the list. */
fun setupItems(c: Context, ask: (Array<String>) -> Unit, carStart: Boolean): List<SetupItem> {
    val lm = c.getSystemService(LocationManager::class.java)
    val wifi = c.applicationContext.getSystemService(WifiManager::class.java)
    val bt = c.getSystemService(BluetoothManager::class.java)?.adapter
    val power = c.getSystemService(PowerManager::class.java)
    val items = mutableListOf(
        SetupItem(tr("Standort: genau", "Location: precise"),
            tr("Android gibt WLAN-Ergebnisse nur an Apps mit Standort-Erlaubnis heraus. Dazu Tempo und Blitzer-Abstand. Der Standort verlässt das Handy nicht.",
                "Android only hands Wi-Fi results to apps with location permission. Also speed and speed camera distance. Your location never leaves the phone."),
            granted(c, Manifest.permission.ACCESS_FINE_LOCATION), true, tr("Erlauben", "Allow")) {
            ask(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        SetupItem(tr("Standortdienst an", "Location services on"),
            tr("Ist der Standort im Schnellmenü aus, liefert Android keine WLAN-Scans, auch mit Erlaubnis nicht.",
                "With location switched off in quick settings, Android returns no Wi-Fi scans, even with the permission."),
            lm != null && LocationManagerCompat.isLocationEnabled(lm), true, tr("Öffnen", "Open")) { open(c, Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
    )
    if (Build.VERSION.SDK_INT >= 31) items += SetupItem(tr("Geräte in der Nähe", "Nearby devices"),
        tr("Für den Bluetooth-Scan: Tracker, Kopfhörer, Bodycams. Funkblick verbindet sich nie mit etwas.",
            "For the Bluetooth scan: trackers, headphones, body cams. Funkblick never connects to anything."),
        granted(c, Manifest.permission.BLUETOOTH_SCAN), true, tr("Erlauben", "Allow")) { ask(arrayOf(Manifest.permission.BLUETOOTH_SCAN)) }
    if (Build.VERSION.SDK_INT >= 33) items += SetupItem(tr("Mitteilungen", "Notifications"),
        tr("Für Warnungen bei gesperrtem Bildschirm und die Anzeige, dass der Scan läuft.", "For alerts while the screen is locked and the note that the scan is running."),
        granted(c, Manifest.permission.POST_NOTIFICATIONS), true, tr("Erlauben", "Allow")) { ask(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
    items += SetupItem(tr("WLAN an", "Wi-Fi on"),
        tr("Oder „WLAN-Suche“ in den Standort-Einstellungen. Verbunden sein musst du nicht.", "Or \"Wi-Fi scanning\" in the location settings. You don't need to be connected."),
        wifi != null && (wifi.isWifiEnabled || wifi.isScanAlwaysAvailable), true, tr("Öffnen", "Open")) {
        open(c, if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
    }
    items += SetupItem(tr("Bluetooth an", "Bluetooth on"),
        tr("Ohne Bluetooth keine Tracker-Warnung.", "No tracker alerts without Bluetooth."),
        bt?.isEnabled == true, true, tr("Öffnen", "Open")) { open(c, Settings.ACTION_BLUETOOTH_SETTINGS) }
    items += SetupItem(tr("Akku-Optimierung aus", "Battery optimization off"),
        tr("Empfohlen. Sonst kann Android den Scan im Hintergrund anhalten, etwa in der Tasche oder im Auto.",
            "Recommended. Otherwise Android may pause the scan in the background, in a pocket or in the car."),
        power?.isIgnoringBatteryOptimizations(c.packageName) == true, false, tr("Öffnen", "Open")) {
        open(c, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
    if (carStart && Build.VERSION.SDK_INT >= 31) items += SetupItem(tr("Bluetooth-Geräte erkennen", "Recognise Bluetooth devices"),
        tr("Für „Im Auto anbieten“: unterscheidet das Auto von Kopfhörern.", "For \"Offer in the car\": tells the car from headphones."),
        granted(c, Manifest.permission.BLUETOOTH_CONNECT), false, tr("Erlauben", "Allow")) { ask(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }
    items += SetupItem(tr("App-Einstellungen", "App settings"),
        tr("GrapheneOS: Die Berechtigung „Netzwerk“ braucht Funkblick nur für Updates von GitHub. Alles andere geht ohne.",
            "GrapheneOS: Funkblick only needs the \"Network\" permission for updates from GitHub. Everything else works without it."),
        true, false, tr("Öffnen", "Open")) { open(c, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg = true) }
    return items
}

@Composable
fun SetupScreen(items: List<SetupItem>, onDone: () -> Unit) {
    val missing = items.count { it.required && !it.ok }
    Column(Modifier.fillMaxSize().background(C.ink).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Funkblick", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.amber)
        Text(tr("Einrichtung", "Setup"), color = C.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (missing == 0) tr("Alles bereit. Die markierten Punkte sind Empfehlungen.", "All set. The rest are recommendations.")
            else tr("Noch $missing ${if (missing == 1) "Punkt" else "Punkte"}, damit der Scan etwas findet. Ein Tipp auf den Knopf, dann zurück in die App.",
                "$missing more ${if (missing == 1) "step" else "steps"} so the scan finds something. Tap the button, then come back."),
            color = C.dim, fontSize = 15.sp,
        )
        for (i in items) Panel(accent = if (!i.ok && i.required) C.red else null) {
            Row(verticalAlignment = Alignment.Top) {
                val col = when { i.ok -> C.amber; i.required -> C.red; else -> C.dim }
                Box(Modifier.padding(top = 2.dp).size(26.dp).clip(CircleShape).background(col.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Text(if (i.ok) "✓" else if (i.required) "!" else "·", color = col, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(i.title, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (!i.required) Text(tr("optional", "optional"), style = label)
                    }
                    Text(i.why, color = C.dim, fontSize = 13.sp, lineHeight = 18.sp)
                    if (!i.ok || !i.required) TextButton(i.action) { Text(i.fix, color = if (i.ok) C.dim else C.amber) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        BigButton(if (missing == 0) tr("Los geht's", "Let's go") else tr("Später", "Later"), filled = missing == 0, onClick = onDone)
        Text(tr("Diese Liste findest du jederzeit unter Mehr, Einrichtung.", "You'll find this list any time under More, Setup."), color = C.dim, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth())
    }
}
