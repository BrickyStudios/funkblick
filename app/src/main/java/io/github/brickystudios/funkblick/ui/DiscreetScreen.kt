package io.github.brickystudios.funkblick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.brickystudios.funkblick.core.tr
import io.github.brickystudios.funkblick.scan.Funk
import io.github.brickystudios.funkblick.scan.Nav
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * Discreet mode (plan E): an ordinary-looking light map with your position, a route if you set one, and nothing else. No radar, no device dots,
 * no amber. Alerts keep coming as sound or vibration, notifications say only "Funkblick". Tap three times to leave.
 * Our own map style from OpenStreetMap; it does not copy any other app's look, name or icon.
 */
@Composable
fun DiscreetScreen(onExit: () -> Unit) {
    var taps by remember { mutableStateOf(listOf<Long>()) }
    var hint by remember { mutableStateOf(!Funk.discreetHintSeen()) }
    var searching by remember { mutableStateOf(false) }
    val nav by Nav.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        Funk.discreet.value = true
        onDispose { Funk.discreet.value = false }
    }
    LaunchedEffect(hint) {
        if (hint) { delay(4000); hint = false; Funk.markDiscreetHintSeen() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFFF4F1EA))) {
        FunkMap(Modifier.fillMaxSize(), look = MapLook.LIGHT, follow = true)
        // catches every tap so the map stays put and three quick taps can end the mode; not under the search, taps would fall through
        if (!searching) Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures {
                val t = System.currentTimeMillis()
                taps = taps.filter { t - it < 900 } + t
                if (taps.size >= 3) onExit()
            }
        })
        NavBanner(light = true, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp))
        // an ordinary nav app has a place to type where to go; ours sits at the bottom, in the app's own shape
        if (nav == null) Text(tr("Wohin?", "Where to?"), color = Color(0xFF5A5F66), fontSize = 17.sp,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)).background(Color.White).clickable(role = Role.Button) { searching = true }
                .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 16.dp))
        if (hint) Text(
            tr("Dreimal schnell tippen zum Beenden", "Tap three times quickly to leave"),
            color = Color(0xFF202124), fontSize = 15.sp,
            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (searching) DestinationSearch(light = true) { searching = false }
    }
}
