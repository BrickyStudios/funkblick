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
import kotlinx.coroutines.delay

/**
 * Discreet mode (plan E): an ordinary-looking light map with your position and nothing else. No radar, no device dots,
 * no amber. Alerts keep coming as sound or vibration, notifications say only "Funkblick". Tap three times to leave.
 * Our own map style from OpenStreetMap; it does not copy any other app's look, name or icon.
 */
@Composable
fun DiscreetScreen(onExit: () -> Unit) {
    var taps by remember { mutableStateOf(listOf<Long>()) }
    var hint by remember { mutableStateOf(!Funk.discreetHintSeen()) }
    DisposableEffect(Unit) {
        Funk.discreet.value = true
        onDispose { Funk.discreet.value = false }
    }
    LaunchedEffect(hint) {
        if (hint) { delay(4000); hint = false; Funk.markDiscreetHintSeen() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFFF4F1EA))) {
        FunkMap(Modifier.fillMaxSize(), look = MapLook.LIGHT, follow = true)
        // catches every tap so the map stays put and three quick taps can end the mode
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures {
                val t = System.currentTimeMillis()
                taps = taps.filter { t - it < 900 } + t
                if (taps.size >= 3) onExit()
            }
        })
        if (hint) Text(
            tr("Dreimal schnell tippen zum Beenden", "Tap three times quickly to leave"),
            color = Color(0xFF202124), fontSize = 15.sp,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
