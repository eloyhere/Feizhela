package com.feizhela.headband.ui.components.charts

import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Shared drawing primitives for the Canvas charts.
 *
 * These helpers only format and draw; they never read a database, run an FFT or downsample.
 * Charts receive already-prepared points from the screen/state layer.
 */

/** Axis label paint that respects the system font scale (sp -> px through the local density). */
@Composable
fun rememberAxisPaint(
    color: Color,
    fontSizeSp: Float = 10f,
): Paint {
    val density = LocalDensity.current
    val colorArgb = color.toArgb()
    return remember(colorArgb, fontSizeSp, density.fontScale, density.density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = colorArgb
            textSize = with(density) { fontSizeSp.sp.toPx() }
        }
    }
}

fun DrawScope.drawChartText(
    text: String,
    x: Float,
    y: Float,
    paint: Paint,
    align: Paint.Align = Paint.Align.LEFT,
) {
    paint.textAlign = align
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

/** Rounded “nice” upper bound so axis ticks do not jump between similar nights. */
fun niceCeiling(value: Float): Float {
    if (value <= 0f) return 1f
    val exponent = floor(log10(value.toDouble())).toInt()
    val magnitude = 10.0.pow(exponent).toFloat()
    val normalized = value / magnitude
    val nice = when {
        normalized <= 1f -> 1f
        normalized <= 2f -> 2f
        normalized <= 2.5f -> 2.5f
        normalized <= 5f -> 5f
        else -> 10f
    }
    return nice * magnitude
}

fun axisLabel(value: Float, max: Float): String {
    val decimals = when {
        max >= 100f -> 0
        max >= 10f -> 0
        max >= 1f -> 1
        else -> 2
    }
    return String.format(Locale.getDefault(), "%.${decimals}f", value)
}

/**
 * Aligned local-time ticks. Aligns to epoch multiples of the interval; that matches local hours in
 * most time zones and keeps labels stable between recompositions.
 */
fun timeTicks(startMillis: Long, endMillis: Long, maxTicks: Int = 4): List<Long> {
    if (endMillis <= startMillis) return emptyList()
    val minute = 60_000L
    val candidates = longArrayOf(
        15 * minute,
        30 * minute,
        60 * minute,
        2 * 60 * minute,
        3 * 60 * minute,
        6 * 60 * minute,
        12 * 60 * minute,
        24 * 60 * minute,
    )
    val span = endMillis - startMillis
    val interval = candidates.firstOrNull { span / it <= maxTicks } ?: candidates.last()
    var tick = startMillis - startMillis % interval + interval
    val ticks = mutableListOf<Long>()
    while (tick < endMillis && ticks.size < maxTicks + 2) {
        if (tick > startMillis) ticks += tick
        tick += interval
    }
    return ticks
}

/** Formatter factory bound to the current locale; formatters are not thread-safe, so remember. */
@Composable
fun rememberTimeFormatter(): (Long) -> String {
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    return remember(formatter) { { millis -> formatter.format(Date(millis)) } }
}

/** Diagonal hatch used for gaps/unknown so those states are readable without colour. */
fun DrawScope.drawHatch(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    color: Color,
    strokeWidth: Float,
    spacing: Float,
) {
    if (right <= left || bottom <= top) return
    val height = bottom - top
    var x = left - height
    while (x < right) {
        val startX = x
        val startY = bottom
        val endX = x + height
        val endY = top
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(startX, startY),
            end = androidx.compose.ui.geometry.Offset(endX, endY),
            strokeWidth = strokeWidth,
        )
        x += spacing
    }
}
