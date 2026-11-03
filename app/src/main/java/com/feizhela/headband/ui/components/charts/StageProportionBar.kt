package com.feizhela.headband.ui.components.charts

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.components.ChartEmptyState
import com.feizhela.headband.ui.components.ChartLegend
import com.feizhela.headband.ui.components.ChartLegendItem
import com.feizhela.headband.ui.components.LegendPattern
import com.feizhela.headband.ui.model.ObservationLabel
import com.feizhela.headband.ui.model.StageCount
import com.feizhela.headband.ui.util.observationLabel
import com.feizhela.headband.ui.util.percentValue
import java.util.Locale

/**
 * 100% stacked observation-label composition.
 *
 * Unknown/insufficient/disconnected epochs are grouped into one segment that is visually separated
 * (gap + hatch) and is never presented as a sleep stage. The legend states raw counts and shares,
 * and a summary line reports how much of the session actually has an observation label.
 */
@Composable
fun StageProportionBar(
    counts: List<StageCount>,
    modifier: Modifier = Modifier,
) {
    val total = counts.sumOf { it.epochCount }
    if (total == 0) {
        ChartEmptyState(modifier = modifier)
        return
    }
    val palette = LocalChartPalette.current
    val scheme = MaterialTheme.colorScheme
    val unknownLabels = setOf(
        ObservationLabel.Unknown,
        ObservationLabel.Insufficient,
        ObservationLabel.Disconnected,
    )
    val known = counts
        .filter { it.label !in unknownLabels && it.epochCount > 0 }
        .sortedBy { it.label.ordinal }
    val unknownCount = total - known.sumOf { it.epochCount }
    val knownCount = total - unknownCount
    val knownPercent = knownCount.toFloat() / total.toFloat() * 100f

    val legendItems = known.map { stage ->
        ChartLegendItem(
            label = observationLabel(stage.label),
            color = palette.labelColor(stage.label),
            pattern = LegendPattern.Solid,
            valueText = stringResource(
                R.string.chart_stage_value,
                stage.epochCount,
                percentValue(stage.epochCount.toFloat() / total.toFloat() * 100f),
            ),
        )
    }.toMutableList()
    if (unknownCount > 0) {
        legendItems += ChartLegendItem(
            label = stringResource(R.string.chart_stage_unknown),
            color = palette.insufficient,
            pattern = LegendPattern.Hatched,
            valueText = String.format(Locale.getDefault(), "%d / %d", unknownCount, total),
        )
    }
    val description = stringResource(
        R.string.chart_stage_desc,
        legendItems.joinToString("；") { item ->
            item.valueText?.let { "${item.label} $it" } ?: item.label
        },
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(top = 4.dp)
                .semantics { contentDescription = description },
        ) {
            val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            drawRoundRect(
                color = palette.chartSurface,
                size = size,
                cornerRadius = corner,
            )
            val separator = 2.dp.toPx()
            var x = 0f
            clipRect(0f, 0f, size.width, size.height) {
                known.forEach { stage ->
                    val fraction = stage.epochCount.toFloat() / total.toFloat()
                    val width = (size.width * fraction - separator).coerceAtLeast(1f)
                    drawRect(
                        color = palette.labelColor(stage.label),
                        topLeft = Offset(x, 0f),
                        size = Size(width, size.height),
                    )
                    x += size.width * fraction
                }
                if (unknownCount > 0) {
                    val fraction = unknownCount.toFloat() / total.toFloat()
                    val left = x + 3.dp.toPx()
                    val width = (size.width * fraction - 3.dp.toPx()).coerceAtLeast(1f)
                    drawRect(
                        color = palette.unknown,
                        topLeft = Offset(left, 0f),
                        size = Size(width, size.height),
                    )
                    drawHatch(
                        left = left,
                        top = 0f,
                        right = left + width,
                        bottom = size.height,
                        color = palette.insufficient,
                        strokeWidth = 1.dp.toPx(),
                        spacing = 5.dp.toPx(),
                    )
                }
            }
        }
        Text(
            text = stringResource(
                R.string.chart_stage_known_share,
                percentValue(knownPercent),
                knownCount,
                total,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        ChartLegend(
            items = legendItems,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
