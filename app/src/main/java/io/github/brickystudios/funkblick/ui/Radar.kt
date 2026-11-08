package io.github.brickystudios.funkblick.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.brickystudios.funkblick.core.Category
import io.github.brickystudios.funkblick.core.Device
import io.github.brickystudios.funkblick.core.Radio
import io.github.brickystudios.funkblick.core.tr
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** -30 dBm sits next to you, -100 dBm on the edge. Signal strength is no distance meter, so the rings say dBm. */
private fun radius(rssi: Int) = ((-rssi - 30) / 70f).coerceIn(0.05f, 1f)

/** Phones can't tell direction, so the angle is just a stable spot per device. */
private fun angle(id: String) = ((id.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()

/** Android's "remove animations": the sweep stands still (plan 6G). */
@Composable
fun reducedMotion(): Boolean {
    val c = LocalContext.current
    return remember { Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

@Composable
fun Radar(devices: List<Device>, live: Boolean, modifier: Modifier = Modifier, labels: Boolean = true, onTap: (Device) -> Unit = {}) {
    val still = reducedMotion()
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 360f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "sweep",
    )
    val measurer = rememberTextMeasurer()
    val now = System.currentTimeMillis()
    val notable = devices.count { it.cls.category.ordinal <= Category.FLEET.ordinal }
    val summary = tr("Radar, ${devices.count { it.radio == Radio.WIFI }} WLAN, ${devices.count { it.radio == Radio.BLE }} Bluetooth, $notable auffällig",
        "Radar, ${devices.count { it.radio == Radio.WIFI }} Wi-Fi, ${devices.count { it.radio == Radio.BLE }} Bluetooth, $notable notable")
    Canvas(
        modifier.aspectRatio(1f).semantics { contentDescription = summary }.pointerInput(devices) {
            detectTapGestures { p ->
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = size.width / 2f * 0.92f
                fun at(d: Device): Offset {
                    val a = Math.toRadians(angle(d.id).toDouble()); val rr = r * radius(d.rssi)
                    return Offset(c.x + rr * cos(a).toFloat(), c.y + rr * sin(a).toFloat())
                }
                devices.minByOrNull { val o = at(it); hypot(o.x - p.x, o.y - p.y) }?.let { d ->
                    val o = at(d)
                    if (hypot(o.x - p.x, o.y - p.y) < 32.dp.toPx()) onTap(d)
                }
            }
        }
    ) {
        val c = center
        val r = size.minDimension / 2f * 0.92f
        val px = 1.dp.toPx()
        val moving = live && !still
        val angleNow = if (moving) sweep else 300f
        for ((dbm, a) in listOf(-50 to 0.18f, -70 to 0.18f, -90 to 0.18f, -100 to 0.4f)) {
            val rr = r * radius(dbm)
            drawCircle(C.amber.copy(alpha = a), rr, c, style = Stroke(px))
            if (labels && dbm != -100) drawText(measurer, "$dbm", Offset(c.x + rr * 0.71f + 4 * px, c.y - rr * 0.71f - 16 * px),
                style = data.copy(fontSize = 13.sp, color = C.dim))
        }
        drawLine(C.amber.copy(alpha = 0.12f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), px)
        drawLine(C.amber.copy(alpha = 0.12f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), px)
        for (i in 0 until 72) { // compass ticks
            val a = Math.toRadians(i * 5.0)
            val inner = if (i % 6 == 0) r * 0.94f else r * 0.97f
            drawLine(C.amber.copy(alpha = if (i % 6 == 0) 0.5f else 0.2f), Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()), px)
        }
        // the sweep is the only thing allowed to glow: it shows the scan is running (plan 4E)
        if (moving) rotate(angleNow, c) {
            drawArc(
                Brush.sweepGradient(0f to Color.Transparent, 0.82f to Color.Transparent, 1f to C.amber.copy(alpha = 0.3f), center = c),
                startAngle = -66f, sweepAngle = 66f, useCenter = true, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r),
            )
            drawLine(C.amber.copy(alpha = 0.85f), c, Offset(c.x + r, c.y), 1.5f * px)
        }
        for (d in devices.sortedByDescending { it.cls.category.ordinal }) { // important ones last, on top
            val a = angle(d.id)
            val rr = r * radius(d.rssi)
            val p = Offset(c.x + rr * cos(Math.toRadians(a.toDouble())).toFloat(), c.y + rr * sin(Math.toRadians(a.toDouble())).toFloat())
            val since = ((angleNow - a) % 360f + 360f) % 360f
            val phosphor = if (moving) 1f - since / 360f * 0.6f else 0.9f
            val fresh = (1f - (now - d.lastSeen) / 180_000f).coerceIn(0.35f, 1f)
            val alpha = (phosphor * fresh * if (d.cls.mute != null) 0.35f else 1f).coerceIn(0.2f, 1f)
            val col = d.cls.category.color()
            val s = if (d.cls.category.ordinal <= Category.FLEET.ordinal || d.cls.watch != null) 5.5f * px else 3.8f * px
            if (d.radio == Radio.WIFI) drawCircle(col.copy(alpha = alpha), s, p)
            else drawRect(col.copy(alpha = alpha), Offset(p.x - s, p.y - s), Size(2 * s, 2 * s))
            if (d.cls.watch != null) drawCircle(C.amber.copy(alpha = alpha), s * 2.1f, p, style = Stroke(1.5f * px))
        }
        drawCircle(C.amber, 3.5f * px, c)
    }
}

/** Legend with counts, only for kinds present. Circle = Wi-Fi, square = Bluetooth. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Legend(devices: List<Device>) {
    val counts = devices.groupingBy { it.cls.category }.eachCount()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (cat in Category.entries) counts[cat]?.let { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(cat.color(), 8)
                Spacer(Modifier.width(8.dp))
                Text(cat.short(), fontSize = 13.sp, color = C.dim)
                Spacer(Modifier.width(4.dp))
                Text("$n", style = data.copy(fontSize = 13.sp, color = C.dim))
            }
        }
    }
}

@Composable
fun SignalBars(rssi: Int, color: Color) {
    val level = ((rssi + 100) / 14).coerceIn(0, 5)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..5) Box(Modifier.width(4.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(1.dp)).background(if (i <= level) color else C.line))
    }
}
