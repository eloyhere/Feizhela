package com.feizhela.headband.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.components.ChartEmptyState
import com.feizhela.headband.ui.components.LegendPattern
import com.feizhela.headband.ui.components.LegendSwatch
import com.feizhela.headband.ui.model.EegChartPoint
import com.feizhela.headband.ui.model.TrendSeries
import com.feizhela.headband.ui.model.TrendSeriesId
import java.util.Locale
import kotlin.math.abs

/**
 * Single-series trend chart for EOG/IMU metrics and model vote scores.
 *
 * - Y axis has a unit label; X axis is local time.
 * - Holes (null values or a time jump larger than ~2.5 typical intervals) break the line; the
 *   chart never connects across missing data and never smooths the signal.
 * - Gaps are shaded; an optional threshold is drawn dashed and labelled “阈值”, never “accuracy”.
 * - All downsampling/envelope preparation happens before this composable.
 */
@Composable
fun TrendLineChart(
    series: TrendSeries,
    title: String,
    unitLabel: String,
    modifier: Modifier = Modifier,
    thresholdText: String? = null,
) {
    val values = series.points.mapNotNull { it.value }
    val hasData = values.isNotEmpty()
    val palette = LocalChartPalette.current
    val scheme = MaterialTheme.colorScheme
    val axisPaint = rememberAxisPaint(palette.axis)
    val formatTime = rememberTimeFormatter()

    val seriesColor = when (series.id) {
        TrendSeriesId.ModelLightVote -> palette.seriesSecondary
        else -> palette.series
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendSwatch(color = seriesColor, pattern = LegendPattern.Solid)
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = unitLabel,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (!hasData || !series.modelAvailable) {
            ChartEmptyState(
                title = if (!series.modelAvailable) {
                    stringResource(R.string.chart_model_unavailable)
                } else {
                    stringResource(R.string.chart_empty_title)
                },
                description = stringResource(R.string.chart_empty_body),
                minHeight = 120,
            )
            return@Column
        }

        val firstTime = series.points.first().timeMillis
        val lastTime = series.points.last().timeMillis
        val span = (lastTime - firstTime).coerceAtLeast(1L)
        val autoMax = (values.maxOrNull() ?: 1f).toDouble()
        val threshold = series.threshold
        val yMax = series.yMax ?: niceCeiling(
            (autoMax * 1.1).coerceAtLeast((threshold ?: 0f) * 1.15).toFloat(),
        )
        val yMin = series.yMin ?: 0f
        val range = (yMax - yMin).coerceAtLeast(1e-6f)
        val minValue = values.minOrNull() ?: 0f
        val maxValue = values.maxOrNull() ?: 0f
        val minLabel = String.format(Locale.getDefault(), "%.2f", minValue)
        val maxLabel = String.format(Locale.getDefault(), "%.2f", maxValue)
        val description = stringResource(
            R.string.chart_trend_cd,
            title,
            series.points.size,
            "$minLabel $unitLabel",
            "$maxLabel $unitLabel",
        )
        val typicalDelta = typicalInterval(series.points)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .padding(top = 8.dp)
                .semantics { contentDescription = description },
        ) {
            val leftPad = 46.dp.toPx()
            val rightPad = 8.dp.toPx()
            val topPad = 6.dp.toPx()
            val bottomPad = 22.dp.toPx()
            val chartWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)
            val chartHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)

            fun xFor(time: Long): Float =
                leftPad + ((time - firstTime).toFloat() / span.toFloat()) * chartWidth

            fun yFor(value: Float): Float =
                topPad + (1f - (value - yMin) / range) * chartHeight

            // Gap bands first, behind grid and series.
            clipRect(leftPad, topPad, leftPad + chartWidth, topPad + chartHeight) {
                series.gaps.forEach { gap ->
                    val startX = xFor(gap.startMillis).coerceIn(leftPad, leftPad + chartWidth)
                    val endX = xFor(gap.endMillis).coerceIn(leftPad, leftPad + chartWidth)
                    if (endX > startX) {
                        drawRect(
                            color = palette.gap.copy(alpha = 0.18f),
                            topLeft = Offset(startX, topPad),
                            size = Size(endX - startX, chartHeight),
                        )
                        drawHatch(
                            left = startX,
                            top = topPad,
                            right = endX,
                            bottom = topPad + chartHeight,
                            color = palette.gap.copy(alpha = 0.45f),
                            strokeWidth = 1.dp.toPx(),
                            spacing = 8.dp.toPx(),
                        )
                    }
                }
            }

            // Horizontal grid + Y labels.
            val ticks = 3
            for (index in 0..ticks) {
                val value = yMin + range * index / ticks
                val y = yFor(value)
                drawLine(
                    color = palette.grid,
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + chartWidth, y),
                    strokeWidth = 1.dp.toPx(),
                )
                drawChartText(
                    text = axisLabel(value, yMax),
                    x = leftPad - 6.dp.toPx(),
                    y = y + 3.dp.toPx(),
                    paint = axisPaint,
                    align = android.graphics.Paint.Align.RIGHT,
                )
            }

            // X axis time labels.
            drawLine(
                color = palette.axis,
                start = Offset(leftPad, topPad + chartHeight),
                end = Offset(leftPad + chartWidth, topPad + chartHeight),
                strokeWidth = 1.dp.toPx(),
            )
            timeTicks(firstTime, lastTime, maxTicks = 3).forEach { tick ->
                val x = xFor(tick)
                drawChartText(
                    text = formatTime(tick),
                    x = x,
                    y = size.height - 4.dp.toPx(),
                    paint = axisPaint,
                    align = android.graphics.Paint.Align.CENTER,
                )
            }

            // Threshold line (candidate rule or activity gate), clearly not a probability.
            if (threshold != null && threshold in yMin..yMax) {
                val y = yFor(threshold)
                drawLine(
                    color = palette.threshold,
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + chartWidth, y),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                        0f,
                    ),
                )
                if (thresholdText != null) {
                    drawChartText(
                        text = thresholdText,
                        x = leftPad + 4.dp.toPx(),
                        y = (y - 4.dp.toPx()).coerceAtLeast(topPad + 8.dp.toPx()),
                        paint = axisPaint,
                    )
                }
            }

            // Series: break on nulls and on time jumps larger than 2.5 typical intervals.
            clipRect(leftPad, topPad, leftPad + chartWidth, topPad + chartHeight) {
                var path: Path? = null
                var previous: EegChartPoint? = null
                series.points.forEach { point ->
                    val value = point.value
                    val previousPoint = previous
                    val jump = previousPoint != null &&
                        abs(point.timeMillis - previousPoint.timeMillis) > typicalDelta * 2.5
                    if (value == null) {
                        path?.let { drawPath(it, seriesColor, style = Stroke(width = 2.dp.toPx())) }
                        path = null
                        previous = point
                        return@forEach
                    }
                    val x = xFor(point.timeMillis)
                    val y = yFor(value)
                    if (path == null || jump) {
                        path?.let { drawPath(it, seriesColor, style = Stroke(width = 2.dp.toPx())) }
                        path = Path().apply { moveTo(x, y) }
                    } else {
                        path?.lineTo(x, y)
                    }
                    previous = point
                }
                path?.let { drawPath(it, seriesColor, style = Stroke(width = 2.dp.toPx())) }
            }
        }

        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.chart_time_axis_local),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            if (series.id == TrendSeriesId.ModelLightVote) {
                Text(
                    text = stringResource(R.string.chart_vote_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Median-ish spacing between consecutive samples, used only to detect holes for drawing. */
private fun typicalInterval(points: List<EegChartPoint>): Long {
    if (points.size < 2) return 30_000L
    val deltas = points.zipWithNext { first, second -> second.timeMillis - first.timeMillis }
        .filter { it > 0 }
        .sorted()
    if (deltas.isEmpty()) return 30_000L
    return deltas[deltas.size / 2].coerceAtLeast(1L)
}
