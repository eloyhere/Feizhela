package com.feizhela.headband.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.model.QualitySummary
import com.feizhela.headband.ui.util.percentLabel
import com.feizhela.headband.ui.util.qualityLabel
import com.feizhela.headband.ui.util.qualityReasonLabel
import com.feizhela.headband.ui.util.signalCheckLabel

/**
 * Quality gate summary: coverage, EOG/IMU checks and rejection reasons.
 *
 * It deliberately has no “electrode impedance” or clinical wording: only engineering rules that
 * the app actually evaluates are listed.
 */
@Composable
fun SignalQualitySummary(
    quality: QualitySummary,
    modifier: Modifier = Modifier,
    showNote: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            StatusPill(
                text = qualityLabel(quality.level),
                tone = qualityTone(quality.level),
            )
        }
        MetricRow(
            label = stringResource(R.string.quality_coverage_title),
            value = percentLabel(quality.coveragePercent),
        )
        MetricRow(
            label = stringResource(R.string.quality_eog_title),
            value = signalCheckLabel(quality.eogCheck),
        )
        MetricRow(
            label = stringResource(R.string.quality_imu_title),
            value = signalCheckLabel(quality.imuCheck),
        )
        MetricRow(
            label = stringResource(R.string.quality_evaluated_epochs),
            value = quality.evaluatedEpochs?.toString() ?: stringResource(R.string.value_unknown),
        )
        Text(
            text = stringResource(R.string.quality_reasons_title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (quality.reasons.isEmpty()) {
            Text(
                text = stringResource(R.string.quality_no_reasons),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            quality.reasons.forEach { reason ->
                Row {
                    Text(
                        text = "• ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = qualityReasonLabel(reason),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (showNote) {
            Text(
                text = stringResource(R.string.quality_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
