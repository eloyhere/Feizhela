package com.feizhela.headband.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.feizhela.headband.R
import com.feizhela.headband.ui.model.ConnectionUiStatus
import com.feizhela.headband.ui.model.DataGapKind
import com.feizhela.headband.ui.model.DiaryFeeling
import com.feizhela.headband.ui.model.ObservationLabel
import com.feizhela.headband.ui.model.QualityLevel
import com.feizhela.headband.ui.model.QualityReason
import com.feizhela.headband.ui.model.SaveState
import com.feizhela.headband.ui.model.SessionStatus
import com.feizhela.headband.ui.model.SignalCheckStatus
import com.feizhela.headband.ui.model.TonightFailure
import com.feizhela.headband.ui.model.TonightPhase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Time formatting helpers. java.time is avoided because minSdk 24 has no core desugaring. */
private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60L * MINUTE_MILLIS

fun formatClock(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

fun formatClockSeconds(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

fun formatDay(millis: Long): String =
    SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(millis))

fun formatDateTime(millis: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(millis))

fun formatTimeWithZone(millis: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
    return formatter.format(Date(millis))
}

/** Raw numeric value for a percentage without the unit, e.g. "97.4" or "—". */
fun percentValue(value: Float?): String =
    if (value == null) "—" else String.format(Locale.getDefault(), "%.1f", value)

fun durationParts(millis: Long?): Pair<Long, Long>? {
    if (millis == null) return null
    val safe = millis.coerceAtLeast(0L)
    return (safe / HOUR_MILLIS) to ((safe % HOUR_MILLIS) / MINUTE_MILLIS)
}

@Composable
fun durationLabel(millis: Long?): String {
    if (millis == null) return stringResource(R.string.value_unknown)
    val safe = millis.coerceAtLeast(0L)
    if (safe < MINUTE_MILLIS) return stringResource(R.string.duration_less_than_minute)
    val (hours, minutes) = durationParts(safe) ?: (0L to 0L)
    return when {
        hours > 0 && minutes > 0 -> stringResource(R.string.duration_hours_minutes, hours.toInt(), minutes.toInt())
        hours > 0 -> stringResource(R.string.duration_hours, hours.toInt())
        else -> stringResource(R.string.duration_minutes, minutes.toInt())
    }
}

@Composable
fun percentLabel(value: Float?): String =
    if (value == null) stringResource(R.string.value_unknown)
    else stringResource(R.string.unit_percent, percentValue(value))

@Composable
fun packetsLabel(value: Long?): String =
    if (value == null) stringResource(R.string.value_unknown)
    else stringResource(R.string.unit_packets, value.toString())

@Composable
fun countLabel(value: Int?): String =
    if (value == null) stringResource(R.string.value_unknown)
    else stringResource(R.string.unit_count, value.toString())

@Composable
fun minutesLabel(value: Int?): String =
    if (value == null) stringResource(R.string.value_unknown)
    else stringResource(R.string.unit_minutes, value)

@Composable
fun qualityLabel(level: QualityLevel): String = stringResource(
    when (level) {
        QualityLevel.Good -> R.string.quality_level_good
        QualityLevel.Fair -> R.string.quality_level_fair
        QualityLevel.Poor -> R.string.quality_level_poor
        QualityLevel.Rejected -> R.string.quality_level_rejected
        QualityLevel.Unknown -> R.string.quality_level_unknown
    },
)

@Composable
fun signalCheckLabel(status: SignalCheckStatus): String = stringResource(
    when (status) {
        SignalCheckStatus.Pass -> R.string.status_ok
        SignalCheckStatus.Attention -> R.string.status_attention
        SignalCheckStatus.Fail -> R.string.status_rejected
        SignalCheckStatus.Unknown -> R.string.status_unknown
    },
)

@Composable
fun qualityReasonLabel(reason: QualityReason): String = stringResource(
    when (reason) {
        QualityReason.LowCoverage -> R.string.quality_reason_low_coverage
        QualityReason.EogFlat -> R.string.quality_reason_eog_flat
        QualityReason.EogClipping -> R.string.quality_reason_eog_clipping
        QualityReason.MotionArtifact -> R.string.quality_reason_motion
        QualityReason.MissingSamples -> R.string.quality_reason_missing
        QualityReason.CounterGap -> R.string.quality_reason_counter_gap
        QualityReason.BaselineNotReady -> R.string.quality_reason_baseline
        QualityReason.ModelUnavailable -> R.string.quality_reason_model_unavailable
        QualityReason.ClockUnstable -> R.string.quality_reason_clock
        QualityReason.DeviceGap -> R.string.quality_reason_device_gap
    },
)

@Composable
fun observationLabel(label: ObservationLabel): String = stringResource(
    when (label) {
        ObservationLabel.BaselineWake -> R.string.label_baseline_wake
        ObservationLabel.LightTendency -> R.string.label_light_tendency
        ObservationLabel.OtherSleepTendency -> R.string.label_other_sleep_tendency
        ObservationLabel.Active -> R.string.label_active
        ObservationLabel.Insufficient -> R.string.label_insufficient
        ObservationLabel.Disconnected -> R.string.label_disconnected
        ObservationLabel.Unknown -> R.string.label_unknown
    },
)

@Composable
fun sessionStatusLabel(status: SessionStatus): String = stringResource(
    when (status) {
        SessionStatus.Active -> R.string.status_active
        SessionStatus.Completed -> R.string.status_completed
        SessionStatus.Interrupted -> R.string.status_interrupted
        SessionStatus.Failed -> R.string.status_failed
        SessionStatus.Unknown -> R.string.records_status_unknown
    },
)

@Composable
fun connectionStatusLabel(status: ConnectionUiStatus): String = stringResource(
    when (status) {
        ConnectionUiStatus.Disconnected -> R.string.status_disconnected
        ConnectionUiStatus.Scanning -> R.string.status_scanning
        ConnectionUiStatus.Connecting -> R.string.status_connecting
        ConnectionUiStatus.Connected -> R.string.status_connected
        ConnectionUiStatus.Unavailable -> R.string.status_unavailable
        ConnectionUiStatus.Error -> R.string.top_bar_connection_error
    },
)

@Composable
fun tonightPhaseLabel(phase: TonightPhase): String = stringResource(
    when (phase) {
        TonightPhase.Idle -> R.string.phase_idle
        TonightPhase.Starting -> R.string.phase_starting
        TonightPhase.Recording -> R.string.phase_recording
        TonightPhase.Stopping -> R.string.phase_stopping
        TonightPhase.Completed -> R.string.phase_completed
        TonightPhase.Interrupted -> R.string.phase_interrupted
        TonightPhase.Failed -> R.string.phase_failed
    },
)

@Composable
fun saveStateLabel(state: SaveState): String = stringResource(
    when (state) {
        SaveState.NotSaved -> R.string.status_pending_save
        SaveState.Pending -> R.string.status_pending_save
        SaveState.Saving -> R.string.status_saving
        SaveState.Saved -> R.string.status_saved
        SaveState.Failed -> R.string.status_save_failed
    },
)

@Composable
fun tonightFailureLabel(failure: TonightFailure): String = stringResource(
    when (failure) {
        TonightFailure.NoDevice -> R.string.tonight_error_start_without_device
        TonightFailure.ConnectionLost -> R.string.tonight_state_interrupted
        TonightFailure.ServiceUnavailable -> R.string.tonight_state_failed
        TonightFailure.StorageWriteFailed -> R.string.tonight_state_save_failed
        TonightFailure.InsufficientData -> R.string.tonight_error_no_data
        TonightFailure.SaveFailed -> R.string.tonight_state_save_failed
    },
)

@Composable
fun feelingLabel(feeling: DiaryFeeling): String = stringResource(
    when (feeling) {
        DiaryFeeling.VeryPoor -> R.string.diary_feeling_1
        DiaryFeeling.Poor -> R.string.diary_feeling_2
        DiaryFeeling.Fair -> R.string.diary_feeling_3
        DiaryFeeling.Good -> R.string.diary_feeling_4
        DiaryFeeling.VeryGood -> R.string.diary_feeling_5
    },
)

@Composable
fun dataGapKindLabel(kind: DataGapKind): String = stringResource(
    when (kind) {
        DataGapKind.Disconnect -> R.string.data_gap_kind_disconnect
        DataGapKind.BufferOverflow -> R.string.data_gap_kind_buffer
        DataGapKind.SubscriptionLost -> R.string.data_gap_kind_subscription
        DataGapKind.ServiceRestart -> R.string.data_gap_kind_restart
        DataGapKind.DeviceGap -> R.string.data_gap_kind_device
        DataGapKind.Unknown -> R.string.data_gap_kind_unknown
    },
)
