package com.feizhela.headband.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.components.ChartEmptyState
import com.feizhela.headband.ui.model.EogSpectrum
import com.feizhela.headband.ui.util.percentValue
import com.feizhela.headband.ui.util.qualityReasonLabel
import java.util.Locale

/**
 * EOG spectrum inspection for one prepared 30 s window.
 *
 * - X axis is 0.1–10 Hz (research feature range), Y axis is normalized power.
 * - The window length, frequency resolution and window function are stated on the chart.
 * - Rejected epochs are masked with a hatch and a reason; a zero-filled curve is never drawn,
 *   because that would look like real low energy.
 * - The FFT/Goertzel computation is not done here; [EogSpectrum.points] must arrive prepared.
 */
@Composable
fun EogSpectrumChart(
    spectrum: EogSpectrum?,
    modifier: Modifier = Modifier,
) {
    if (spectrum == null) {
        ChartEmptyState(modifier = modifier)
        return
    }
    val palette = LocalChartPalette.current
    val scheme = MaterialTheme.colorScheme
    val axisPaint = rememberAxisPaint(palette.axis)
    val spectrumColor = palette.series
    val rejectionText = stringResource(
        R.string.chart_spectrum_rejected,
        spectrum.rejectionReasons.map { qualityReasonLabel(it) }.joinToString("、"),
    )
    val windowText = stringResource(
        R.string.chart_spectrum_window,
        String.format(Locale.getDefault(), "%.3f", spectrum.frequencyResolutionHz),
    )
    val peak = spectrum.points.maxOfOrNull { it.powerNormalized } ?: 0f
    val description = if (spectrum.isRejected) {
        rejectionText
    } else {
        stringResource(R.string.chart_spectrum_cd, spectrum.points.size, percentValue(peak * 100f))
    }
    val frequencyAxis = stringResource(R.string.chart_axis_frequency)
    val yUnit = stringResource(R.string.chart_spectrum_y_unit)
    val rejectedLabel = stringResource(R.string.status_rejected)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = windowText,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = yUnit,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(178.dp)
                .padding(top = 6.dp)
                .semantics { contentDescription = description },
        ) {
            val leftPad = 42.dp.toPx()
            val rightPad = 10.dp.toPx()
            val topPad = 8.dp.toPx()
            val bottomPad = 26.dp.toPx()
            val chartWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)
            val chartHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)
            val minHz = 0.1f
            val maxHz = 10f

            fun xFor(frequencyHz: Float): Float =
                leftPad + ((frequencyHz - minHz) / (maxHz - minHz)) * chartWidth

            fun yFor(power: Float): Float =
                topPad + (1f - power.coerceIn(0f, 1f)) * chartHeight

            // Y grid 0 / 0.5 / 1.0.
            listOf(0f, 0.5f, 1f).forEach { power ->
                val y = yFor(power)
                drawLine(
                    color = if (power == 0f) palette.axis else palette.grid,
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + chartWidth, y),
                    strokeWidth = 1.dp.toPx(),
                )
                drawChartText(
                    text = String.format(Locale.getDefault(), "%.1f", power),
                    x = leftPad - 6.dp.toPx(),
                    y = y + 3.dp.toPx(),
                    paint = axisPaint,
                    align = android.graphics.Paint.Align.RIGHT,
                )
            }

            // X grid: 0.1, 2, 4, 6, 8, 10 Hz.
            listOf(0.1f, 2f, 4f, 6f, 8f, 10f).forEach { frequency ->
                val x = xFor(frequency)
                drawLine(
                    color = palette.grid,
                    start = Offset(x, topPad),
                    end = Offset(x, topPad + chartHeight),
                    strokeWidth = 1.dp.toPx(),
                )
                drawChartText(
                    text = if (frequency < 1f) "0.1" else frequency.toInt().toString(),
                    x = x,
                    y = topPad + chartHeight + 14.dp.toPx(),
                    paint = axisPaint,
                    align = when (frequency) {
                        0.1f -> android.graphics.Paint.Align.LEFT
                        10f -> android.graphics.Paint.Align.RIGHT
                        else -> android.graphics.Paint.Align.CENTER
                    },
                )
            }
            drawChartText(
                text = frequencyAxis,
                x = leftPad + chartWidth,
                y = size.height - 2.dp.toPx(),
                paint = axisPaint,
                align = android.graphics.Paint.Align.RIGHT,
            )

            if (spectrum.isRejected) {
                clipRect(leftPad, topPad, leftPad + chartWidth, topPad + chartHeight) {
                    drawRect(
                        color = palette.unknown.copy(alpha = 0.6f),
                        topLeft = Offset(leftPad, topPad),
                        size = Size(chartWidth, chartHeight),
                    )
                    drawHatch(
                        left = leftPad,
                        top = topPad,
                        right = leftPad + chartWidth,
                        bottom = topPad + chartHeight,
                        color = palette.disconnected.copy(alpha = 0.7f),
                        strokeWidth = 1.5.dp.toPx(),
                        spacing = 10.dp.toPx(),
                    )
                }
                drawChartText(
                    text = rejectedLabel,
                    x = leftPad + chartWidth / 2f,
                    y = topPad + chartHeight / 2f,
                    paint = axisPaint,
                    align = android.graphics.Paint.Align.CENTER,
                )
            } else {
                val points = spectrum.points
                    .filter { it.frequencyHz in minHz..maxHz }
                    .sortedBy { it.frequencyHz }
                if (points.isNotEmpty()) {
                    val area = Path().apply {
                        moveTo(xFor(points.first().frequencyHz), yFor(0f))
                        points.forEach { point ->
                            lineTo(xFor(point.frequencyHz), yFor(point.powerNormalized))
                        }
                        lineTo(xFor(points.last().frequencyHz), yFor(0f))
                        close()
                    }
                    clipRect(leftPad, topPad, leftPad + chartWidth, topPad + chartHeight) {
                        drawPath(area, spectrumColor.copy(alpha = 0.20f))
                    }
                    val line = Path().apply {
                        moveTo(xFor(points.first().frequencyHz), yFor(points.first().powerNormalized))
                        points.drop(1).forEach { point ->
                            lineTo(xFor(point.frequencyHz), yFor(point.powerNormalized))
                        }
                    }
                    clipRect(leftPad, topPad, leftPad + chartWidth, topPad + chartHeight) {
                        drawPath(line, spectrumColor, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
        }
        Text(
            text = if (spectrum.isRejected) rejectionText else stringResource(R.string.chart_spectrum_range),
            style = MaterialTheme.typography.bodySmall,
            color = if (spectrum.isRejected) scheme.error else scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(R.string.chart_spectrum_source),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}
