package com.feizhela.headband.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.model.SessionMetrics
import com.feizhela.headband.ui.util.durationLabel
import com.feizhela.headband.ui.util.percentLabel
import com.feizhela.headband.ui.util.packetsLabel
import androidx.compose.ui.res.stringResource

/**
 * One label/value line, optionally with a hint. Values are passed as formatted strings (the
 * screen owns units/resources), which keeps this row free of domain logic.
 */
@Composable
fun MetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    valueContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
        if (valueContent != null) {
            valueContent()
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Standard five-metric session summary: recorded span, samples, coverage, candidate windows and
 * disconnection time. “Recorded span” is deliberately not labelled as sleep duration.
 */
@Composable
fun SessionMetricRow(
    metrics: SessionMetrics,
    modifier: Modifier = Modifier,
    showCandidates: Boolean = true,
    showPackets: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MetricRow(
            label = stringResource(R.string.detail_metric_span),
            value = durationLabel(metrics.recordedSpanMillis),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        MetricRow(
            label = stringResource(R.string.detail_metric_samples),
            value = metrics.sampleCount?.toString() ?: stringResource(R.string.value_unknown),
        )
        if (showPackets) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            MetricRow(
                label = stringResource(R.string.detail_metric_packets),
                value = packetsLabel(metrics.packetCount),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        MetricRow(
            label = stringResource(R.string.detail_metric_coverage),
            value = percentLabel(metrics.coveragePercent),
        )
        if (showCandidates) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            MetricRow(
                label = stringResource(R.string.detail_metric_candidates),
                value = metrics.candidateWindowCount?.let {
                    stringResource(R.string.unit_count, it.toString())
                } ?: stringResource(R.string.value_unknown),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        MetricRow(
            label = stringResource(R.string.detail_metric_disconnected),
            value = durationLabel(metrics.disconnectedMillis),
        )
    }
}
