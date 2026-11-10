package io.github.brickystudios.funkblick

import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brickystudios.funkblick.core.tr
import io.github.brickystudios.funkblick.scan.Funk
import io.github.brickystudios.funkblick.scan.ScanService
import io.github.brickystudios.funkblick.scan.UpdateViewModel
import io.github.brickystudios.funkblick.ui.C
import io.github.brickystudios.funkblick.ui.CarScreen
import io.github.brickystudios.funkblick.ui.ChannelsScreen
import io.github.brickystudios.funkblick.ui.DeviceSheet
import io.github.brickystudios.funkblick.ui.DiscreetScreen
import io.github.brickystudios.funkblick.ui.FindScreen
import io.github.brickystudios.funkblick.ui.FunkTheme
import io.github.brickystudios.funkblick.ui.ListScreen
import io.github.brickystudios.funkblick.ui.LiveScreen
import io.github.brickystudios.funkblick.ui.LogScreen
import io.github.brickystudios.funkblick.ui.MoreScreen
import io.github.brickystudios.funkblick.ui.SetupScreen
import io.github.brickystudios.funkblick.ui.TrafficWarning
import io.github.brickystudios.funkblick.ui.UpdateDialog
import io.github.brickystudios.funkblick.ui.setupItems
import java.io.File

/** Full-screen modes on top of the tabs. */
private enum class Mode { TABS, CAR, DISCREET, CHANNELS }

class MainActivity : ComponentActivity() {
    private val updates: UpdateViewModel by viewModels()
    private val tab = mutableStateOf(0)
    private val mode = mutableStateOf(Mode.TABS)
    private val find = mutableStateOf<String?>(null) // device id in find mode
    private val findHelp = mutableStateOf(false)
    private val resumed = mutableStateOf(0) // bumps on every return to the app, so the setup list checks again

    private val askOnly = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumed.value++ }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        resumed.value++
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) ScanService.start(this)
        else Toast.makeText(this, tr("Ohne Standort-Erlaubnis gibt Android keine WLAN-Ergebnisse heraus.", "Without location permission Android hands out no Wi-Fi results."), Toast.LENGTH_LONG).show()
    }

    private val connectPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Funk.updateSettings { it.copy(carStart = ok) }
        if (!ok) Toast.makeText(this, tr("Ohne diese Erlaubnis kann Funkblick ein Auto nicht von Kopfhörern unterscheiden.", "Without this permission Funkblick can't tell a car from headphones."), Toast.LENGTH_LONG).show()
    }

    /** "Offer in the car" needs BLUETOOTH_CONNECT on Android 12+ to read what kind of device connected. */
    private fun enableCarStart() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            connectPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        else Funk.updateSettings { it.copy(carStart = true) }
    }

    private fun needed(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun start() {
        val missing = needed().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) ScanService.start(this) else permissions.launch(missing.toTypedArray())
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_CAR -> { mode.value = Mode.CAR; start() }
            ACTION_FIND -> intent.getStringExtra(EXTRA_ID)?.let { find.value = it; findHelp.value = true }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed.value++
    }

    companion object {
        const val ACTION_START = "io.github.brickystudios.funkblick.START"
        const val ACTION_CAR = "io.github.brickystudios.funkblick.CAR"
        const val ACTION_FIND = "io.github.brickystudios.funkblick.FIND"
        const val EXTRA_ID = "id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(C.ink.toArgb()), SystemBarStyle.dark(C.ink.toArgb()))
        super.onCreate(savedInstanceState)
        Funk.init(this)
        lifecycleScope.launch { Funk.refreshCameraData() } // weekly, whole Germany, no position sent
        if (savedInstanceState == null) handle(intent)
        setContent {
            FunkTheme {
                val running by Funk.running.collectAsStateWithLifecycle()
                val demo by Funk.demo.collectAsStateWithLifecycle()
                val update by updates.state.collectAsStateWithLifecycle()
                val devices by Funk.devices.collectAsStateWithLifecycle()
                val settings by Funk.settings.collectAsStateWithLifecycle()
                var tab by tab
                var mode by mode
                var find by find
                var open by rememberSaveable { mutableStateOf<String?>(null) }
                var warn by rememberSaveable { mutableStateOf(false) }
                val items = remember(resumed.value, settings.carStart) { setupItems(this@MainActivity, askOnly::launch, settings.carStart) }
                // null = shows by itself while something required is missing, true = opened from More, false = dismissed
                var setup by rememberSaveable { mutableStateOf<Boolean?>(null) }
                if (setup == true || (setup == null && items.any { it.required && !it.ok })) {
                    SetupScreen(items) { setup = false }
                    return@FunkTheme
                }
                var log by rememberSaveable { mutableStateOf<String?>(null) } // path of the old log on screen
                log?.let { path ->
                    BackHandler { log = null }
                    LogScreen(File(path)) { log = null }
                    return@FunkTheme
                }
                BackHandler(mode != Mode.TABS && mode != Mode.DISCREET) { mode = Mode.TABS }
                when (mode) {
                    Mode.CAR -> CarScreen(running, ::start, { warn = true }) { if (running) ScanService.stop(this@MainActivity); mode = Mode.TABS }
                    Mode.DISCREET -> DiscreetScreen { mode = Mode.TABS }
                    Mode.CHANNELS -> Box(Modifier.fillMaxSize().background(C.ink).statusBarsPadding()) { ChannelsScreen() }
                    Mode.TABS -> Column(Modifier.fillMaxSize().background(C.ink).statusBarsPadding()) {
                        Box(Modifier.weight(1f)) {
                            Crossfade(tab, label = "tab") { t ->
                                when (t) {
                                    0 -> LiveScreen(running, demo, ::start, { ScanService.stop(this@MainActivity) }, { Funk.loadDemo() },
                                        onOpen = { open = it.id }, onFind = { find = it; findHelp.value = true },
                                        onCar = { mode = Mode.CAR }, onDiscreet = { mode = Mode.DISCREET })
                                    1 -> ListScreen { open = it.id }
                                    else -> MoreScreen({ warn = true }, ::enableCarStart, onSetup = { setup = true },
                                        setupMissing = items.count { it.required && !it.ok }, onChannels = { mode = Mode.CHANNELS }, onOpenLog = { log = it.path })
                                }
                            }
                        }
                        Tabs(tab) { tab = it }
                    }
                }
                devices.firstOrNull { it.id == open }?.let { d -> DeviceSheet(d, onFind = { find = d.id; findHelp.value = false; open = null }) { open = null } }
                find?.let { FindScreen(it, ::start, onClose = { find = null }, help = findHelp.value) }
                if (warn) TrafficWarning({ Funk.updateSettings { it.copy(traffic = true) }; warn = false }, { warn = false })
                update?.let { UpdateDialog(it, updates::install, updates::later) }
            }
        }
    }
}

@Composable
private fun Tabs(selected: Int, onSelect: (Int) -> Unit) {
    val names = listOf("Live", tr("Liste", "List"), tr("Mehr", "More"))
    Row(Modifier.fillMaxWidth().background(C.panel).navigationBarsPadding()) {
        names.forEachIndexed { i, n ->
            val on = i == selected
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp).clickable(role = Role.Tab) { onSelect(i) }.padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(n, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) C.amber else C.dim, maxLines = 1)
                Box(Modifier.padding(top = 8.dp).width(if (on) 24.dp else 0.dp).height(2.dp).background(C.amber))
            }
        }
    }
}
