package com.feizhela.headband.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.components.ChartEmptyState
import com.feizhela.headband.ui.components.DataGapBadge
import com.feizhela.headband.ui.components.DemoDataNotice
import com.feizhela.headband.ui.components.EmptyState
import com.feizhela.headband.ui.components.EvidenceNotice
import com.feizhela.headband.ui.components.MetricRow
import com.feizhela.headband.ui.components.SectionCard
import com.feizhela.headband.ui.components.SessionMetricRow
import com.feizhela.headband.ui.components.SignalQualitySummary
import com.feizhela.headband.ui.components.StatusPill
import com.feizhela.headband.ui.components.StatusTone
import com.feizhela.headband.ui.components.charts.EogSpectrumChart
import com.feizhela.headband.ui.components.charts.EpochBarChart
import com.feizhela.headband.ui.components.charts.LocalChartPalette
import com.feizhela.headband.ui.components.charts.ObservationTimeline
import com.feizhela.headband.ui.components.charts.StageProportionBar
import com.feizhela.headband.ui.components.charts.TrendLineChart
import com.feizhela.headband.ui.model.CandidateState
import com.feizhela.headband.ui.model.DataGapUi
import com.feizhela.headband.ui.model.EpochVoteScores
import com.feizhela.headband.ui.model.ObservationEpoch
import com.feizhela.headband.ui.model.SessionDetail
import com.feizhela.headband.ui.model.SessionStatus
import com.feizhela.headband.ui.model.TrendSeriesId
import com.feizhela.headband.ui.util.countLabel
import com.feizhela.headband.ui.util.dataGapKindLabel
import com.feizhela.headband.ui.util.durationLabel
import com.feizhela.headband.ui.util.formatClock
import com.feizhela.headband.ui.util.formatClockSeconds
import com.feizhela.headband.ui.util.formatDateTime
import com.feizhela.headband.ui.util.observationLabel
import com.feizhela.headband.ui.util.percentLabel
import com.feizhela.headband.ui.util.percentValue
import com.feizhela.headband.ui.util.qualityLabel
import com.feizhela.headband.ui.util.qualityReasonLabel
import com.feizhela.headband.ui.util.sessionStatusLabel
import java.util.Locale

/**
 * Session detail: metrics, observation timeline, stage composition, hourly coverage, trends,
 * spectrum, gaps, quality and versions.
 *
 * Every chart receives prepared data; taps open the epoch sheet. Export/delete are explicit UI
 * placeholders with consequence dialogs — this screen never touches Room or the file system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    detail: SessionDetail?,
    modifier: Modifier = Modifier,
    onExport: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    if (detail == null) {
        EmptyState(
            modifier = modifier.fillMaxSize(),
            title = stringResource(R.string.detail_delete_missing),
            description = stringResource(R.string.records_empty_body),
        )
        return
    }
    var selectedEpoch by remember(detail.summary.sessionId) { mutableStateOf<ObservationEpoch?>(null) }
    var cursorIndex by rememberSaveable(detail.summary.sessionId) { mutableStateOf(0) }
    var showRejectedSpectrum by remember(detail.summary.sessionId) { mutableStateOf(false) }
    var showExportDialog by rememberSaveable(detail.summary.sessionId) { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable(detail.summary.sessionId) { mutableStateOf(false) }
    val summary = detail.summary

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("session_detail_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = formatDateTime(summary.startedAtMillis),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StatusPill(
                        text = sessionStatusLabel(summary.status),
                        tone = when (summary.status) {
                            SessionStatus.Completed -> StatusTone.Success
                            SessionStatus.Interrupted -> StatusTone.Warning
                            SessionStatus.Failed -> StatusTone.Danger
                            else -> StatusTone.Neutral
                        },
                    )
                }
                Text(
                    text = stringResource(
                        R.string.detail_range,
                        formatDateTime(summary.startedAtMillis),
                        summary.endedAtMillis?.let { formatDateTime(it) }
                            ?: stringResource(R.string.value_unknown),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = (summary.deviceName ?: stringResource(R.string.device_name_unknown)) +
                        " · " + summary.deviceAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (summary.isDemo) {
                    DemoDataNotice()
                }
            }
        }

        item("summary") {
            SectionCard(title = stringResource(R.string.detail_summary_title)) {
                SessionMetricRow(metrics = detail.metrics, showPackets = true)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DataGapBadge(
                        count = summary.gapCount,
                        durationMillis = detail.metrics.disconnectedMillis,
                    )
                }
            }
        }

        item("timeline") {
            SectionCard(
                title = stringResource(R.string.detail_timeline_title),
                subtitle = stringResource(R.string.detail_timeline_hint),
            ) {
                if (detail.epochs.isEmpty()) {
                    ChartEmptyState(
                        title = stringResource(R.string.detail_no_epochs),
                        description = stringResource(R.string.detail_no_epochs_body),
                    )
                    if (summary.isDemo) {
                        Text(
                            text = stringResource(R.string.detail_demo_summary_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    ObservationTimeline(
                        epochs = detail.epochs,
                        selectedEpochIndex = detail.epochs.getOrNull(cursorIndex)?.index,
                        onEpochSelected = {
                            cursorIndex = detail.epochs.indexOf(it).coerceAtLeast(0)
                            selectedEpoch = it
                        },
                    )
                    // Equivalent controls let keyboard and TalkBack users open any epoch.
                    val cursor = cursorIndex.coerceIn(detail.epochs.indices)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { cursorIndex = cursor - 1 }, enabled = cursor > 0,
                            modifier = Modifier.weight(1f).testTag("previous_epoch"),
                        ) { Text(stringResource(R.string.chart_prev_epoch)) }
                        OutlinedButton(
                            onClick = { cursorIndex = cursor + 1 }, enabled = cursor < detail.epochs.lastIndex,
                            modifier = Modifier.weight(1f).testTag("next_epoch"),
                        ) { Text(stringResource(R.string.chart_next_epoch)) }
                    }
                    OutlinedButton(
                        onClick = { selectedEpoch = detail.epochs[cursor] },
                        modifier = Modifier.fillMaxWidth().testTag("open_epoch_detail"),
                    ) {
                        Text(stringResource(R.string.detail_open_epoch, cursor + 1,
                            formatClockSeconds(detail.epochs[cursor].startMillis)))
                    }
                    selectedEpoch?.let { epoch ->
                        Text(
                            text = stringResource(
                                R.string.detail_timeline_selection_hint,
                                formatClockSeconds(epoch.startMillis) + " · " + observationLabel(epoch.label),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        item("stage") {
            SectionCard(
                title = stringResource(R.string.detail_stage_title),
                subtitle = stringResource(R.string.detail_stage_hint),
            ) {
                if (detail.epochs.isEmpty()) {
                    ChartEmptyState()
                } else {
                    StageProportionBar(counts = detail.stageCounts)
                }
            }
        }

        item("hourly") {
            SectionCard(
                title = stringResource(R.string.detail_hourly_title),
                subtitle = stringResource(R.string.detail_hourly_hint),
            ) {
                if (detail.hourly.isEmpty()) {
                    ChartEmptyState()
                } else {
                    EpochBarChart(buckets = detail.hourly)
                }
            }
        }

        item("trends") {
            SectionCard(title = stringResource(R.string.detail_trend_title)) {
                if (detail.trendSeries.isEmpty()) {
                    ChartEmptyState()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        val eog = detail.trendSeries.firstOrNull { it.id == TrendSeriesId.EogStdAdc }
                        val imu = detail.trendSeries.firstOrNull { it.id == TrendSeriesId.ImuActivityG }
                        val vote = detail.trendSeries.firstOrNull { it.id == TrendSeriesId.ModelLightVote }
                        if (eog != null) {
                            TrendLineChart(
                                series = eog,
                                title = stringResource(R.string.detail_trend_eog_title),
                                unitLabel = stringResource(R.string.detail_trend_unit_adc),
                            )
                        }
                        if (imu != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            TrendLineChart(
                                series = imu,
                                title = stringResource(R.string.detail_trend_imu_title),
                                unitLabel = stringResource(R.string.detail_trend_unit_g),
                                thresholdText = stringResource(R.string.chart_threshold, "0.035"),
                            )
                        }
                        if (vote != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            TrendLineChart(
                                series = vote,
                                title = stringResource(R.string.detail_trend_vote_title),
                                unitLabel = stringResource(R.string.detail_trend_unit_vote),
                                thresholdText = stringResource(R.string.chart_threshold, "0.60"),
                            )
                        }
                    }
                }
            }
        }

        item("spectrum") {
            SectionCard(
                title = stringResource(R.string.detail_spectrum_title),
                subtitle = detail.spectrum?.let {
                    stringResource(R.string.detail_spectrum_epoch, formatClock(it.epochStartMillis))
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showRejectedSpectrum,
                        onClick = { showRejectedSpectrum = false },
                        enabled = detail.spectrum != null,
                        label = { Text(stringResource(R.string.detail_spectrum_valid)) },
                    )
                    FilterChip(
                        selected = showRejectedSpectrum,
                        onClick = { showRejectedSpectrum = true },
                        enabled = detail.rejectedSpectrum != null,
                        label = { Text(stringResource(R.string.detail_spectrum_rejected)) },
                    )
                }
                val chosen = if (showRejectedSpectrum) detail.rejectedSpectrum else detail.spectrum
                EogSpectrumChart(spectrum = chosen)
            }
        }

        item("gaps") {
            SectionCard(title = stringResource(R.string.detail_gaps_title)) {
                if (detail.gaps.isEmpty()) {
                    Text(
                        text = if (summary.gapCount > 0) stringResource(R.string.detail_gaps_summary_only, summary.gapCount) else stringResource(R.string.detail_gaps_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    detail.gaps.forEachIndexed { index, gap ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        GapRow(gap)
                    }
                }
            }
        }

        item("quality") {
            SectionCard(title = stringResource(R.string.detail_quality_title)) {
                SignalQualitySummary(quality = detail.quality)
            }
        }

        item("versions") {
            SectionCard(title = stringResource(R.string.detail_versions_title)) {
                MetricRow(
                    label = stringResource(R.string.detail_version_protocol),
                    value = detail.protocolVersion ?: stringResource(R.string.detail_versions_missing),
                )
                MetricRow(
                    label = stringResource(R.string.detail_version_firmware),
                    value = detail.firmwareVersion ?: stringResource(R.string.device_unknown),
                )
                MetricRow(
                    label = stringResource(R.string.detail_version_app),
                    value = detail.appVersion ?: stringResource(R.string.detail_versions_missing),
                )
                MetricRow(
                    label = stringResource(R.string.detail_version_model),
                    value = detail.modelVersion ?: stringResource(R.string.detail_versions_missing),
                )
                MetricRow(
                    label = stringResource(R.string.detail_version_rules),
                    value = detail.rulesVersion ?: stringResource(R.string.detail_versions_missing),
                )
            }
        }

        item("actions") {
            SectionCard(title = stringResource(R.string.detail_actions_title)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showExportDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_session"),
                    ) {
                        Text(stringResource(R.string.action_export))
                    }
                    Button(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("delete_session"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(stringResource(R.string.action_delete))
                    }
                }
                Text(
                    text = stringResource(R.string.detail_actions_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("evidence") {
            EvidenceNotice(modelAvailable = detail.modelAvailable)
        }
    }

    selectedEpoch?.let { epoch ->
        ModalBottomSheet(
            onDismissRequest = { selectedEpoch = null },
            modifier = Modifier.testTag("epoch_detail_sheet"),
        ) {
            EpochDetailContent(epoch = epoch, onClose = { selectedEpoch = null })
        }
    }

    if (showExportDialog) {
        ConfirmDialog(
            title = stringResource(R.string.detail_export_dialog_title),
            body = stringResource(R.string.detail_export_dialog_body),
            confirmLabel = stringResource(R.string.action_export_placeholder),
            destructive = false,
            onConfirm = {
                showExportDialog = false
                onExport()
            },
            onDismiss = { showExportDialog = false },
        )
    }
    if (showDeleteDialog) {
        ConfirmDialog(
            title = stringResource(R.string.detail_delete_dialog_title),
            body = stringResource(R.string.detail_delete_dialog_body),
            confirmLabel = stringResource(R.string.action_confirm_delete),
            destructive = true,
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun GapRow(gap: DataGapUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(
                text = dataGapKindLabel(gap.kind),
                tone = StatusTone.Warning,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = durationLabel(gap.durationMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(
                R.string.detail_gap_time,
                formatClockSeconds(gap.startMillis),
                gap.endMillis?.let { formatClockSeconds(it) } ?: stringResource(R.string.data_gap_open),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = gap.droppedEstimate?.let {
                stringResource(R.string.data_gap_dropped, it.toString())
            } ?: stringResource(R.string.data_gap_dropped_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (gap.detail != null) {
            Text(
                text = gap.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EpochDetailContent(epoch: ObservationEpoch, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.detail_epoch_sheet_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(
                R.string.detail_epoch_time_seconds,
                formatClockSeconds(epoch.startMillis),
                formatClockSeconds(epoch.endMillis),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                text = observationLabel(epoch.label),
                tone = when (epoch.label) {
                    com.feizhela.headband.ui.model.ObservationLabel.BaselineWake -> StatusTone.Success
                    com.feizhela.headband.ui.model.ObservationLabel.LightTendency -> StatusTone.Info
                    com.feizhela.headband.ui.model.ObservationLabel.OtherSleepTendency -> StatusTone.Info
                    com.feizhela.headband.ui.model.ObservationLabel.Active -> StatusTone.Warning
                    com.feizhela.headband.ui.model.ObservationLabel.Insufficient -> StatusTone.Danger
                    com.feizhela.headband.ui.model.ObservationLabel.Disconnected -> StatusTone.Danger
                    com.feizhela.headband.ui.model.ObservationLabel.Unknown -> StatusTone.Neutral
                },
            )
            StatusPill(text = qualityLabel(epoch.quality), tone = StatusTone.Neutral)
        }
        MetricRow(
            label = stringResource(R.string.detail_epoch_samples),
            value = "${epoch.receivedSamples} / ${epoch.expectedSamples}",
        )
        MetricRow(
            label = stringResource(R.string.detail_epoch_coverage),
            value = percentLabel(epoch.coveragePercent),
        )
        MetricRow(
            label = stringResource(R.string.detail_epoch_quality),
            value = qualityLabel(epoch.quality),
        )
        MetricRow(
            label = stringResource(R.string.detail_epoch_candidate),
            value = when (epoch.candidate) {
                CandidateState.None -> stringResource(R.string.label_not_candidate)
                CandidateState.Candidate -> stringResource(R.string.label_candidate)
                CandidateState.NeedsReview -> stringResource(R.string.status_candidate)
            },
        )
        MetricRow(
            label = stringResource(R.string.detail_epoch_imu),
            value = epoch.imuActivityG?.let {
                String.format(Locale.getDefault(), "%.3f g", it)
            } ?: stringResource(R.string.value_unknown),
        )
        MetricRow(
            label = stringResource(R.string.detail_epoch_eog),
            value = epoch.eogStdAdc?.let {
                String.format(Locale.getDefault(), "%.0f ADC std", it)
            } ?: stringResource(R.string.value_unknown),
        )
        val versionText = listOfNotNull(epoch.modelVersion, epoch.rulesVersion).joinToString(" / ")
        MetricRow(
            label = stringResource(R.string.detail_epoch_model),
            value = if (versionText.isBlank()) {
                stringResource(R.string.detail_versions_missing)
            } else {
                versionText
            },
        )

        Text(
            text = stringResource(R.string.detail_epoch_reasons),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (epoch.reasons.isEmpty()) {
            Text(
                text = stringResource(R.string.quality_no_reasons),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            epoch.reasons.forEach { reason ->
                Text(
                    text = "• " + qualityReasonLabel(reason),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text(
            text = stringResource(R.string.detail_epoch_votes),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        val votes = epoch.votes
        if (votes == null) {
            Text(
                text = stringResource(R.string.detail_epoch_no_votes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            VoteBar(votes = votes)
            MetricRow(
                label = stringResource(R.string.detail_epoch_vote_awake),
                value = percentLabel(votes.awake * 100f),
            )
            MetricRow(
                label = stringResource(R.string.detail_epoch_vote_light),
                value = percentLabel(votes.light * 100f),
            )
            MetricRow(
                label = stringResource(R.string.detail_epoch_vote_other),
                value = percentLabel(votes.otherSleep * 100f),
            )
            Text(
                text = stringResource(R.string.detail_epoch_vote_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (epoch.label == com.feizhela.headband.ui.model.ObservationLabel.Disconnected ||
            epoch.label == com.feizhela.headband.ui.model.ObservationLabel.Insufficient
        ) {
            Text(
                text = stringResource(R.string.detail_epoch_gap_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_close))
        }
    }
}

@Composable
private fun VoteBar(votes: EpochVoteScores) {
    val palette = LocalChartPalette.current
    val total = (votes.total).takeIf { it > 0f } ?: 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(6.dp)),
    ) {
        Box(
            modifier = Modifier
                .weight((votes.awake / total).coerceAtLeast(0.001f))
                .fillMaxHeight()
                .background(palette.baselineWake),
        )
        Box(
            modifier = Modifier
                .weight((votes.light / total).coerceAtLeast(0.001f))
                .fillMaxHeight()
                .background(palette.lightTendency),
        )
        Box(
            modifier = Modifier
                .weight((votes.otherSleep / total).coerceAtLeast(0.001f))
                .fillMaxHeight()
                .background(palette.otherSleepTendency),
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(text = body, textAlign = TextAlign.Start)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
