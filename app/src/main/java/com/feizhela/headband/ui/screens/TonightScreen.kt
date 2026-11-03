package com.feizhela.headband.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.components.ConnectionStatusBar
import com.feizhela.headband.ui.components.DemoDataNotice
import com.feizhela.headband.ui.components.EvidenceNotice
import com.feizhela.headband.ui.components.MetricRow
import com.feizhela.headband.ui.components.SectionCard
import com.feizhela.headband.ui.components.SignalQualitySummary
import com.feizhela.headband.ui.components.StatusPill
import com.feizhela.headband.ui.components.StatusTone
import com.feizhela.headband.ui.components.qualityTone
import com.feizhela.headband.ui.model.ConnectionUiStatus
import com.feizhela.headband.ui.model.SaveState
import com.feizhela.headband.ui.model.TonightPhase
import com.feizhela.headband.ui.model.TonightUiState
import com.feizhela.headband.ui.util.durationLabel
import com.feizhela.headband.ui.util.percentLabel
import com.feizhela.headband.ui.util.packetsLabel
import com.feizhela.headband.ui.util.qualityLabel
import com.feizhela.headband.ui.util.saveStateLabel
import com.feizhela.headband.ui.util.tonightFailureLabel
import com.feizhela.headband.ui.util.tonightPhaseLabel

/**
 * “Tonight” screen: device/acquisition state, live counters, quality, candidates and the
 * start/stop/save lifecycle. The screen only renders [TonightUiState] and emits callbacks.
 *
 * Distinct states are shown for “connected but not recording”, “recording”, “interrupted /
 * incomplete data”, “save failed”. A disconnected link never renders as recording.
 */
@Composable
fun TonightScreen(
    state: TonightUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSave: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenSessionDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val phaseDescription = when {
        state.phase == TonightPhase.Idle && state.connection.status == ConnectionUiStatus.Connected ->
            stringResource(R.string.tonight_state_connected_idle)

        state.phase == TonightPhase.Idle -> stringResource(R.string.tonight_state_disconnected_idle)
        state.phase == TonightPhase.Starting -> stringResource(R.string.tonight_state_starting)
        state.phase == TonightPhase.Recording -> stringResource(R.string.tonight_state_recording)
        state.phase == TonightPhase.Stopping -> stringResource(R.string.tonight_state_stopping)
        state.phase == TonightPhase.Completed -> stringResource(R.string.tonight_state_completed)
        state.phase == TonightPhase.Interrupted -> stringResource(R.string.tonight_state_interrupted)
        else -> stringResource(R.string.tonight_state_failed)
    }
    val hasStarted = state.startedAtMillis != null
    val spanText = if (hasStarted) {
        durationLabel(state.recordedSpanMillis)
    } else {
        stringResource(R.string.value_not_measured)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("tonight_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("intro") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.tonight_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("connection") {
            ConnectionStatusBar(
                connection = state.connection,
                phaseLabel = tonightPhaseLabel(state.phase),
                onReconnect = onOpenDevice,
                onOpenDevice = onOpenDevice,
            )
        }

        item("session") {
            SectionCard(
                title = stringResource(R.string.tonight_card_session),
                subtitle = phaseDescription,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        text = tonightPhaseLabel(state.phase),
                        tone = when (state.phase) {
                            TonightPhase.Recording -> StatusTone.Success
                            TonightPhase.Starting, TonightPhase.Stopping -> StatusTone.Info
                            TonightPhase.Completed -> StatusTone.Success
                            TonightPhase.Interrupted -> StatusTone.Warning
                            TonightPhase.Failed -> StatusTone.Danger
                            TonightPhase.Idle -> StatusTone.Neutral
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    if (state.phase == TonightPhase.Completed || state.phase == TonightPhase.Interrupted) {
                        StatusPill(
                            text = saveStateLabel(state.saveState),
                            tone = when (state.saveState) {
                                SaveState.Saved -> StatusTone.Success
                                SaveState.Failed -> StatusTone.Danger
                                SaveState.Saving -> StatusTone.Info
                                else -> StatusTone.Warning
                            },
                        )
                    }
                }
                MetricRow(
                    label = stringResource(R.string.tonight_metric_span),
                    value = spanText,
                )
                MetricRow(
                    label = stringResource(R.string.tonight_metric_packets),
                    value = packetsLabel(if (hasStarted) state.packetCount else null),
                )
                MetricRow(
                    label = stringResource(R.string.tonight_metric_gaps),
                    value = if (hasStarted) {
                        stringResource(R.string.unit_count, state.gapCount.toString())
                    } else {
                        stringResource(R.string.value_not_measured)
                    },
                )
                MetricRow(
                    label = stringResource(R.string.tonight_metric_coverage),
                    value = if (hasStarted) {
                        percentLabel(state.coveragePercent)
                    } else {
                        stringResource(R.string.value_not_measured)
                    },
                )
                MetricRow(
                    label = stringResource(R.string.detail_metric_samples),
                    value = if (hasStarted) state.sampleCount?.toString() ?: stringResource(R.string.value_unknown) else stringResource(R.string.value_not_measured),
                )
                if (state.failure != null) {
                    SurfaceError(text = tonightFailureLabel(state.failure))
                    if (state.technicalDetail != null) {
                        Text(
                            text = stringResource(R.string.connection_error_detail, state.technicalDetail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.phase == TonightPhase.Interrupted) {
                    Text(
                        text = stringResource(R.string.tonight_data_incomplete_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Actions(
                    state = state,
                    onStart = onStart,
                    onStop = onStop,
                    onSave = onSave,
                    onOpenDevice = onOpenDevice,
                    onOpenSessionDetail = onOpenSessionDetail,
                )
                if (state.phase == TonightPhase.Completed || state.phase == TonightPhase.Interrupted) {
                    Text(
                        text = stringResource(R.string.tonight_save_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.tonight_hint_keep_foreground),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("quality") {
            SectionCard(title = stringResource(R.string.tonight_card_quality)) {
                if (hasStarted) {
                    SignalQualitySummary(quality = state.quality)
                } else {
                    Text(
                        text = stringResource(R.string.quality_no_reasons),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MetricRow(
                        label = stringResource(R.string.tonight_metric_quality),
                        value = qualityLabel(state.quality.level),
                    )
                }
            }
        }

        item("candidate") {
            SectionCard(title = stringResource(R.string.tonight_card_candidate)) {
                if (state.candidateNeedsReview) {
                    StatusPill(
                        text = stringResource(R.string.tonight_candidate_pending, state.candidateWindowCount),
                        tone = StatusTone.Warning,
                        icon = Icons.Filled.Warning,
                    )
                } else {
                    StatusPill(
                        text = stringResource(R.string.tonight_candidate_none),
                        tone = StatusTone.Neutral,
                    )
                }
                Text(
                    text = stringResource(R.string.tonight_candidate_definition),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("evidence") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EvidenceNotice()
                Text(
                    text = stringResource(R.string.tonight_demo_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Actions(
    state: TonightUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSave: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenSessionDetail: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.phase) {
            TonightPhase.Recording -> {
                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stop_recording"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.action_stop_recording))
                }
            }

            TonightPhase.Starting, TonightPhase.Stopping -> {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(tonightPhaseLabel(state.phase))
                }
            }

            TonightPhase.Completed, TonightPhase.Interrupted -> {
                when (state.saveState) {
                    SaveState.Saving -> Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.status_saving))
                    }

                    SaveState.Saved -> {
                        Text(
                            text = stringResource(R.string.tonight_state_saved),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        OutlinedButton(
                            onClick = onStart,
                            modifier = Modifier.fillMaxWidth().testTag("new_recording"),
                        ) { Text(stringResource(R.string.action_new_demo)) }
                        val sessionId = state.completedSessionId
                        if (sessionId != null) {
                            OutlinedButton(
                                onClick = { onOpenSessionDetail(sessionId) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("open_completed_session"),
                            ) {
                                Text(stringResource(R.string.action_view_session))
                            }
                        }
                    }

                    else -> {
                        if (state.saveState == SaveState.Failed) {
                            Text(
                                text = stringResource(R.string.tonight_state_save_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(
                            onClick = onSave,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("save_session"),
                        ) {
                            Text(stringResource(R.string.action_save_session))
                        }
                    }
                }
            }

            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("start_recording"),
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_start_recording))
                    }
                    OutlinedButton(onClick = onOpenDevice, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_open_device))
                    }
                }
                if (state.failure != null) {
                    Text(
                        text = tonightFailureLabel(state.failure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SurfaceError(text: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 2.dp),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}
