package io.github.brickystudios.funkblick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.brickystudios.funkblick.R
import io.github.brickystudios.funkblick.core.Category
import io.github.brickystudios.funkblick.core.tr

// Design tokens (plan D5). Night-vision amber: the app is used in a dark car, amber disturbs dark adaptation less than
// white. Every text colour on ink reaches 4.5:1 or more (plan 6A); there is no faint grey any more.
object C {
    val ink = Color(0xFF0D0C0A)
    val panel = Color(0xFF16140F) // surface
    val panel2 = Color(0xFF1F1B14) // raised surface (sheets, pressed)
    val line = Color(0xFF332D22)
    val text = Color(0xFFF2E8D5)
    val dim = Color(0xFFA39780) // text-2, 7:1 on ink
    val amber = Color(0xFFFFB020)
    val red = Color(0xFFFF4D5E)
    val blue = Color(0xFF6E95F0)
}

/** Kind colours, 15 % calmer than v1 so only real alerts shout (plan D5). */
fun Category.color(): Color = when (this) {
    Category.TRACKER -> C.red
    Category.PRIORITY -> C.blue
    Category.CAMERA -> Color(0xFFD27BE8)
    Category.FLEET -> Color(0xFFF0904E)
    Category.CAR -> Color(0xFF8CBFE8)
    Category.PUBLIC_WIFI -> Color(0xFFE8CC8A)
    Category.INFRA -> Color(0xFFB3A68F)
    Category.HOME -> Color(0xFFE3DCCF)
    Category.PERSONAL -> Color(0xFFA996EE)
    Category.UNKNOWN -> Color(0xFF8A8172)
}

/** Short label for chips and the legend. */
fun Category.short(): String = when (this) {
    Category.TRACKER -> "Tracker"
    Category.PRIORITY -> tr("Behörde", "Police/EMS")
    Category.CAMERA -> tr("Kamera", "Camera")
    Category.FLEET -> tr("Fahrzeug-Router", "Vehicle router")
    Category.CAR -> tr("Auto", "Car")
    Category.PUBLIC_WIFI -> tr("Öffentlich", "Public")
    Category.INFRA -> tr("Firma", "Business")
    Category.HOME -> tr("Zuhause", "Home")
    Category.PERSONAL -> tr("Persönlich", "Personal")
    Category.UNKNOWN -> tr("Unbekannt", "Unknown")
}

// Type (plan 4G): IBM Plex Sans for text, IBM Plex Mono only for measured values (4C), Chakra Petch for a few headings
// and the big numbers of car mode. Scale 13/14/16/20/28/40.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun plex(w: Int) = Font(R.font.ibmplexsans_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Sans = FontFamily(plex(400), plex(500), plex(600))
val Mono = FontFamily(Font(R.font.ibmplexmono_regular, FontWeight.Normal), Font(R.font.ibmplexmono_medium, FontWeight.Medium))
val Display = FontFamily(Font(R.font.chakrapetch_medium, FontWeight.Medium), Font(R.font.chakrapetch_bold, FontWeight.Bold))

/** Labels: the body face, a step smaller, sentence case, untracked (Hallmark floor 9). */
val label = TextStyle(fontFamily = Sans, fontSize = 13.sp, color = C.dim)
/** Measured values: dBm, km/h, metres, times, addresses. */
val data = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = C.text)

@Composable
fun FunkTheme(content: @Composable () -> Unit) {
    val t = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.amber, onPrimary = C.ink, secondary = C.amber, background = C.ink, onBackground = C.text,
            surface = C.panel, onSurface = C.text, surfaceVariant = C.panel2, onSurfaceVariant = C.dim,
            surfaceContainer = C.panel, surfaceContainerHigh = C.panel2, surfaceContainerHighest = C.panel2,
            outline = C.line, outlineVariant = C.line, error = C.red,
        ),
        typography = Typography(
            displayLarge = t.displayLarge.copy(fontFamily = Display), displayMedium = t.displayMedium.copy(fontFamily = Display),
            headlineMedium = t.headlineMedium.copy(fontFamily = Display), titleLarge = t.titleLarge.copy(fontFamily = Sans),
            titleMedium = t.titleMedium.copy(fontFamily = Sans), bodyLarge = t.bodyLarge.copy(fontFamily = Sans),
            bodyMedium = t.bodyMedium.copy(fontFamily = Sans), bodySmall = t.bodySmall.copy(fontFamily = Sans, fontSize = 13.sp),
            labelLarge = t.labelLarge.copy(fontFamily = Sans), labelMedium = t.labelMedium.copy(fontFamily = Sans, fontSize = 13.sp),
            labelSmall = t.labelSmall.copy(fontFamily = Sans, fontSize = 13.sp),
        ),
        content = content,
    )
}

/** Section heading: says what the area is, closer to its content than to what came before. */
@Composable
fun Header(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, color = C.text, fontSize = 16.sp, fontFamily = Sans, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = data.copy(fontSize = 13.sp, color = C.dim))
    }
}

/** A quiet surface. No border: an accent tints the background instead (plan 4A). */
@Composable
fun Panel(modifier: Modifier = Modifier, accent: Color? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(accent?.copy(alpha = 0.12f) ?: C.panel)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp)
    ) { content() }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))

@Composable
fun Dot(color: Color, size: Int = 8) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))

/** Filter chip: 40 dp to look at, 48 dp to hit (plan 6E). */
@Composable
fun Chip(text: String, selected: Boolean, color: Color = C.amber, onClick: () -> Unit) {
    Box(Modifier.minimumInteractiveComponentSize().clickable(role = Role.Checkbox, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) color.copy(alpha = 0.18f) else C.panel)
                .heightIn(min = 40.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (color != C.amber) Dot(color, 8)
            Text(text, fontFamily = Sans, fontSize = 14.sp, color = if (selected) C.text else C.dim)
        }
    }
}

@Composable
fun Toggle(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = C.text, fontSize = 16.sp)
            if (sub != null) Text(sub, color = C.dim, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Switch(checked, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedThumbColor = C.ink, checkedTrackColor = C.amber, uncheckedThumbColor = C.dim, uncheckedTrackColor = C.panel2, uncheckedBorderColor = C.line,
        ))
    }
}

/** A plain text action with a verb, 48 dp tall (replaces "open →" links, Hallmark floor 10). */
@Composable
fun TextAction(text: String, color: Color = C.amber, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp).width(0.dp))

fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> tr("vor $s s", "${s}s ago")
        s < 3600 -> tr("vor ${s / 60} min", "${s / 60} min ago")
        else -> tr("vor ${s / 3600} h", "${s / 3600} h ago")
    }
}

fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
