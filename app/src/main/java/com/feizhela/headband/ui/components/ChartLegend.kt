package com.feizhela.headband.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R

/** Marker style for a legend entry; patterns are drawn in addition to colour. */
enum class LegendPattern { Solid, Hatched, Dashed }

data class ChartLegendItem(
    val label: String,
    val color: Color,
    val pattern: LegendPattern = LegendPattern.Solid,
    /** Optional count/percentage rendered after the label. */
    val valueText: String? = null,
)

/**
 * Legend for canvas charts. Two entries per row keep large-font layouts readable on narrow
 * phones; long labels wrap instead of being truncated.
 */
@Composable
fun ChartLegend(
    items: List<ChartLegendItem>,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items.chunked(2).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                            .semantics(mergeDescendants = true) { },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LegendSwatch(color = item.color, pattern = item.pattern)
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (item.valueText != null) {
                                Text(
                                    text = item.valueText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun LegendSwatch(color: Color, pattern: LegendPattern, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(16.dp)) {
        val corner = 3.dp.toPx()
        val strokeWidth = 1.5.dp.toPx()
        when (pattern) {
            LegendPattern.Solid -> drawRoundRect(
                color = color,
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            )

            LegendPattern.Hatched -> {
                drawRoundRect(
                    color = color,
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                    style = Stroke(width = strokeWidth),
                )
                var x = -size.height
                while (x < size.width) {
                    drawLine(
                        color = color,
                        start = Offset(x, size.height),
                        end = Offset(x + size.height, 0f),
                        strokeWidth = strokeWidth,
                    )
                    x += 5.dp.toPx()
                }
            }

            LegendPattern.Dashed -> drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = strokeWidth * 2f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                    0f,
                ),
            )
        }
    }
}

/**
 * Standard “no data” panel for a chart. It is used for empty lists, unavailable model output and
 * rejected spectra, so the chart never fabricates a flat zero line.
 */
@Composable
fun ChartEmptyState(
    modifier: Modifier = Modifier,
    title: String? = null,
    description: String? = null,
    minHeight: Int = 120,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(minHeight.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title ?: stringResource(R.string.chart_empty_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = description ?: stringResource(R.string.chart_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.chart_canvas_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
    }
}
