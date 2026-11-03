package com.feizhela.headband.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.components.ChartEmptyState
import com.feizhela.headband.ui.components.ChartLegend
import com.feizhela.headband.ui.components.ChartLegendItem
import com.feizhela.headband.ui.components.LegendPattern
import com.feizhela.headband.ui.model.HourBucket
import com.feizhela.headband.ui.util.percentValue
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Hourly coverage bars (one bar per hour, one statistic per bar: mean epoch coverage).
 *
 * Bar height encodes coverage only; the category thresholds are also stated in the legend and
 * low bars additionally get a hatch pattern. A missing hour (no epochs at all) is skipped, so the
 * chart cannot imply data that does not exist.
 */
@Composable
fun EpochBarChart(
    buckets: List<HourBucket>,
    modifier: Modifier = Modifier,
    onBucketSelected: ((HourBucket) -> Unit)? = null,
) {
    if (buckets.isEmpty()) {
        ChartEmptyState(modifier = modifier)
        return
    }
    val palette = LocalChartPalette.current
    val scheme = MaterialTheme.colorScheme
    val goodColor = scheme.primary
    val fairColor = scheme.tertiary
    val poorColor = scheme.error
    val axisPaint = rememberAxisPaint(palette.axis)
    val formatTime = rememberTimeFormatter()
    val minCoverage = buckets.minOf { it.coveragePercent }
    val maxCoverage = buckets.maxOf { it.coveragePercent }
    val description = stringResource(
        R.string.chart_hourly_cd,
        buckets.size,
        percentValue(minCoverage),
        percentValue(maxCoverage),
    )
    val labelEvery = ceil(buckets.size / 6.0).toInt().coerceAtLeast(1)
    val barUnitLabel = stringResource(R.string.chart_bar_unit)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .semantics { contentDescription = description }
                .pointerInput(buckets, onBucketSelected) {
                    if (onBucketSelected != null) {
                        detectTapGestures { offset ->
                            val leftPad = 38.dp.toPx()
                            val rightPad = 8.dp.toPx()
                            val width = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                            val relative = ((offset.x - leftPad) / width).coerceIn(0f, 0.9999f)
                            val index = (relative * buckets.size).toInt()
                            buckets.getOrNull(index)?.let(onBucketSelected)
                        }
                    }
                },
        ) {
            val leftPad = 38.dp.toPx()
            val rightPad = 8.dp.toPx()
            val topPad = 8.dp.toPx()
            val bottomPad = 24.dp.toPx()
            val chartWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)
            val chartHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)
            val slot = chartWidth / buckets.size

            // Y grid at 0/50/100 %.
            listOf(0f, 50f, 100f).forEach { percent ->
                val y = topPad + (1f - percent / 100f) * chartHeight
                drawLine(
                    color = if (percent == 0f) palette.axis else palette.grid,
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + chartWidth, y),
                    strokeWidth = 1.dp.toPx(),
                )
                drawChartText(
                    text = percent.roundToInt().toString(),
                    x = leftPad - 6.dp.toPx(),
                    y = y + 3.dp.toPx(),
                    paint = axisPaint,
                    align = android.graphics.Paint.Align.RIGHT,
                )
            }
            drawChartText(
                text = barUnitLabel,
                x = leftPad,
                y = size.height - 2.dp.toPx(),
                paint = axisPaint,
            )

            clipRect(leftPad, topPad, leftPad + chartWidth, topPad + chartHeight) {
                buckets.forEachIndexed { index, bucket ->
                    val barWidth = slot * 0.66f
                    val left = leftPad + index * slot + (slot - barWidth) / 2f
                    val ratio = (bucket.coveragePercent / 100f).coerceIn(0f, 1f)
                    val height = chartHeight * ratio
                    val top = topPad + chartHeight - height
                    val color = when {
                        bucket.coveragePercent >= 99f -> goodColor
                        bucket.coveragePercent >= 95f -> fairColor
                        else -> poorColor
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left, top),
                        size = Size(barWidth.coerceAtLeast(1f), height.coerceAtLeast(1f)),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    )
                    if (bucket.coveragePercent < 95f) {
                        drawHatch(
                            left = left,
                            top = top,
                            right = left + barWidth,
                            bottom = topPad + chartHeight,
                            color = scheme.onSurface.copy(alpha = 0.55f),
                            strokeWidth = 1.dp.toPx(),
                            spacing = 4.dp.toPx(),
                        )
                    }
                }
            }

            // X labels: every Nth hour, always including the first and last.
            buckets.forEachIndexed { index, bucket ->
                val show = index % labelEvery == 0 || index == buckets.lastIndex
                if (show) {
                    val center = leftPad + index * slot + slot / 2f
                    drawChartText(
                        text = formatTime(bucket.startMillis),
                        x = center,
                        y = size.height - 8.dp.toPx(),
                        paint = axisPaint,
                        align = android.graphics.Paint.Align.CENTER,
                    )
                }
            }
        }

        ChartLegend(
            items = listOf(
                ChartLegendItem(
                    label = stringResource(R.string.chart_hourly_legend_good),
                    color = goodColor,
                    pattern = LegendPattern.Solid,
                ),
                ChartLegendItem(
                    label = stringResource(R.string.chart_hourly_legend_fair),
                    color = fairColor,
                    pattern = LegendPattern.Solid,
                ),
                ChartLegendItem(
                    label = stringResource(R.string.chart_hourly_legend_poor),
                    color = poorColor,
                    pattern = LegendPattern.Hatched,
                ),
            ),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
