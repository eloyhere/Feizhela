package com.feizhela.headband.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.components.ChartLegend
import com.feizhela.headband.ui.components.ChartLegendItem
import com.feizhela.headband.ui.components.LegendPattern
import com.feizhela.headband.ui.model.ObservationEpoch
import com.feizhela.headband.ui.model.ObservationLabel
import com.feizhela.headband.ui.util.observationLabel

/**
 * Whole-night observation timeline: one cell per 30 s epoch.
 *
 * - Colour plus hatching distinguishes labels; gaps are never bridged or filled with a label.
 * - Top dark marks show candidate windows; a selected cell gets an outline.
 * - Tapping a cell reports the epoch to the caller (which opens the epoch detail sheet).
 * - TalkBack gets a summary plus previous/next-epoch custom actions.
 *
 * The component performs no aggregation, FFT or database access; [epochs] must be prepared
 * upstream (a viewport/downsampled window for very long nights is a caller responsibility).
 */
@Composable
fun ObservationTimeline(
    epochs: List<ObservationEpoch>,
    modifier: Modifier = Modifier,
    selectedEpochIndex: Int? = null,
    onEpochSelected: ((ObservationEpoch) -> Unit)? = null,
    showLegend: Boolean = true,
) {
    if (epochs.isEmpty()) {
        com.feizhela.headband.ui.components.ChartEmptyState(modifier = modifier)
        return
    }
    val palette = LocalChartPalette.current
    val scheme = MaterialTheme.colorScheme
    val axisPaint = rememberAxisPaint(palette.axis)
    val formatTime = rememberTimeFormatter()
    val start = epochs.first().startMillis
    val end = epochs.last().endMillis
    val gapCount = epochs.count { it.label == ObservationLabel.Disconnected }
    val description = stringResource(
        R.string.chart_timeline_cd,
        epochs.size,
        formatTime(start),
        formatTime(end),
        gapCount,
    )
    val prevLabel = stringResource(R.string.chart_prev_epoch)
    val nextLabel = stringResource(R.string.chart_next_epoch)
    val ticks = timeTicks(start, end, maxTicks = 4)

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(98.dp)
                .semantics {
                    contentDescription = description
                    if (onEpochSelected != null) {
                        customActions = listOf(
                            CustomAccessibilityAction(prevLabel) {
                                val current = selectedEpochIndex ?: 0
                                onEpochSelected(epochs[(current - 1).coerceIn(0, epochs.lastIndex)])
                                true
                            },
                            CustomAccessibilityAction(nextLabel) {
                                val current = selectedEpochIndex ?: -1
                                onEpochSelected(epochs[(current + 1).coerceIn(0, epochs.lastIndex)])
                                true
                            },
                        )
                    }
                }
                .pointerInput(epochs, onEpochSelected) {
                    if (onEpochSelected != null) {
                        detectTapGestures { offset ->
                            val index = ((offset.x / size.width) * epochs.size)
                                .toInt()
                                .coerceIn(0, epochs.lastIndex)
                            onEpochSelected(epochs[index])
                        }
                    }
                },
        ) {
            val axisHeight = 20.dp.toPx()
            val barTop = 8.dp.toPx()
            val barBottom = size.height - axisHeight
            val barHeight = (barBottom - barTop).coerceAtLeast(1f)
            val width = size.width
            val step = width / epochs.size
            val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())

            drawRoundRect(
                color = palette.chartSurface,
                topLeft = Offset(0f, barTop),
                size = Size(width, barHeight),
                cornerRadius = corner,
            )
            clipRect(0f, barTop, width, barBottom) {
                epochs.forEachIndexed { index, epoch ->
                    val left = index * step
                    val right = left + step
                    if (epoch.label == ObservationLabel.Disconnected) {
                        drawRect(
                            color = palette.unknown,
                            topLeft = Offset(left, barTop),
                            size = Size((right - left).coerceAtLeast(0.5f), barHeight),
                        )
                        drawHatch(
                            left = left,
                            top = barTop,
                            right = right,
                            bottom = barBottom,
                            color = palette.disconnected,
                            strokeWidth = 1.dp.toPx(),
                            spacing = 5.dp.toPx(),
                        )
                    } else {
                        drawRect(
                            color = palette.labelColor(epoch.label),
                            topLeft = Offset(left, barTop),
                            size = Size((right - left).coerceAtLeast(0.5f), barHeight),
                        )
                        if (epoch.candidate != com.feizhela.headband.ui.model.CandidateState.None) {
                            drawRect(
                                color = palette.candidateOutline,
                                topLeft = Offset(left, barTop),
                                size = Size((right - left).coerceAtLeast(1.5f), 3.dp.toPx()),
                            )
                        }
                    }
                }
                val selected = selectedEpochIndex?.takeIf { it in epochs.indices }
                if (selected != null) {
                    drawRect(
                        color = palette.selection,
                        topLeft = Offset(selected * step, barTop),
                        size = Size(step.coerceAtLeast(2f), barHeight),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            drawLine(
                color = palette.grid,
                start = Offset(0f, barBottom),
                end = Offset(width, barBottom),
                strokeWidth = 1.dp.toPx(),
            )
            ticks.forEach { tick ->
                val x = ((tick - start).toFloat() / (end - start).toFloat()) * width
                drawLine(
                    color = palette.axis,
                    start = Offset(x, barBottom),
                    end = Offset(x, barBottom + 4.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
                drawChartText(
                    text = formatTime(tick),
                    x = x,
                    y = size.height - 2.dp.toPx(),
                    paint = axisPaint,
                    align = android.graphics.Paint.Align.CENTER,
                )
            }
            drawChartText(
                text = formatTime(start),
                x = 0f,
                y = size.height - 2.dp.toPx(),
                paint = axisPaint,
            )
            drawChartText(
                text = formatTime(end),
                x = width,
                y = size.height - 2.dp.toPx(),
                paint = axisPaint,
                align = android.graphics.Paint.Align.RIGHT,
            )
        }
        if (showLegend) {
            val presentLabels = epochs.map { it.label }.distinct().sortedBy { it.ordinal }
            val items = presentLabels.map { label ->
                ChartLegendItem(
                    label = observationLabel(label),
                    color = palette.labelColor(label),
                    pattern = if (label == ObservationLabel.Disconnected) {
                        LegendPattern.Hatched
                    } else {
                        LegendPattern.Solid
                    },
                    valueText = epochs.count { it.label == label }.toString(),
                )
            }
            ChartLegend(
                items = items,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.chart_candidate_marker),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = stringResource(R.string.chart_time_axis_local),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}
