package com.feizhela.headband.ui.components.charts

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.feizhela.headband.ui.model.ObservationLabel

/**
 * Semantic colors for every chart in the app.
 *
 * Charts must not read [androidx.compose.material3.MaterialTheme.colorScheme] directly for
 * observation states: the mapping from label to color lives in exactly one place so the timeline,
 * stage bar and legends cannot drift apart. Every state is also distinguishable without color
 * (text label, hatch pattern or icon), so this palette is never the only information channel.
 */
@Immutable
data class ChartPalette(
    val baselineWake: Color,
    val lightTendency: Color,
    val otherSleepTendency: Color,
    val active: Color,
    val insufficient: Color,
    val disconnected: Color,
    val unknown: Color,
    val grid: Color,
    val axis: Color,
    val gap: Color,
    val series: Color,
    val seriesSecondary: Color,
    val threshold: Color,
    val selection: Color,
    val chartSurface: Color,
    val candidateOutline: Color,
) {
    fun labelColor(label: ObservationLabel): Color = when (label) {
        ObservationLabel.BaselineWake -> baselineWake
        ObservationLabel.LightTendency -> lightTendency
        ObservationLabel.OtherSleepTendency -> otherSleepTendency
        ObservationLabel.Active -> active
        ObservationLabel.Insufficient -> insufficient
        ObservationLabel.Disconnected -> disconnected
        ObservationLabel.Unknown -> unknown
    }
}

val LightChartPalette = ChartPalette(
    baselineWake = Color(0xFFA9761F),
    lightTendency = Color(0xFF3F5D8C),
    otherSleepTendency = Color(0xFF2E6F61),
    active = Color(0xFFB5531B),
    insufficient = Color(0xFF6F7580),
    disconnected = Color(0xFFB3261E),
    unknown = Color(0xFFD5D9E0),
    grid = Color(0x1F1B1F24),
    axis = Color(0xFF5C6169),
    gap = Color(0xFF8E939B),
    series = Color(0xFF3F5D8C),
    seriesSecondary = Color(0xFF6A5A8C),
    threshold = Color(0xFFB3261E),
    selection = Color(0xFF1B1F24),
    chartSurface = Color(0xFFF1F3F7),
    candidateOutline = Color(0xFF1B1F24),
)

val DarkChartPalette = ChartPalette(
    baselineWake = Color(0xFFE0B05A),
    lightTendency = Color(0xFFA6C2E8),
    otherSleepTendency = Color(0xFF83CBB8),
    active = Color(0xFFF0A06A),
    insufficient = Color(0xFFA3A9B2),
    disconnected = Color(0xFFF2B8B5),
    unknown = Color(0xFF3A4049),
    grid = Color(0x33E3E6EB),
    axis = Color(0xFFC2C7D0),
    gap = Color(0xFF8C919A),
    series = Color(0xFFA6C2E8),
    seriesSecondary = Color(0xFFC9BDE6),
    threshold = Color(0xFFFFB4AB),
    selection = Color(0xFFE3E6EB),
    chartSurface = Color(0xFF1B2027),
    candidateOutline = Color(0xFFE3E6EB),
)

val LocalChartPalette = staticCompositionLocalOf { LightChartPalette }
