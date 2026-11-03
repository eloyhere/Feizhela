package com.feizhela.headband.ui.model

/**
 * Immutable UI models for the Feizhela observation app.
 *
 * These types are deliberately free of Android/Room/BLE imports so screens and charts can be
 * previewed and tested with plain data. Units, clock source and quality semantics are part of the
 * contract: no field here is a clinical measurement, and every "score" is a model tree-vote ratio.
 */

// ---------------------------------------------------------------------------------------------
// Connection / device
// ---------------------------------------------------------------------------------------------

/** Transport state as the UI needs it; mirrors the BLE repository without importing it. */
enum class ConnectionUiStatus { Disconnected, Scanning, Connecting, Connected, Unavailable, Error }

/**
 * One immutable connection snapshot.
 *
 * [lastPacketAtMillis] is the phone receive time of the most recent notification and
 * [lastError] carries the technical reason from the transport/service (shown verbatim, never
 * translated into a medical conclusion).
 */
data class ConnectionUiState(
    val status: ConnectionUiStatus = ConnectionUiStatus.Disconnected,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val lastPacketAtMillis: Long? = null,
    val lastError: String? = null,
) {
    val isConnected: Boolean get() = status == ConnectionUiStatus.Connected
    val isTransitioning: Boolean
        get() = status == ConnectionUiStatus.Connecting || status == ConnectionUiStatus.Scanning
}

/** A demo/real device row; [isDemo] is surfaced in the UI so demo data is never mistaken for BLE. */
data class DeviceUi(
    val address: String,
    val name: String?,
    val rssi: Int?,
    val isDemo: Boolean = true,
)

data class DeviceUiState(
    val connection: ConnectionUiState = ConnectionUiState(),
    val scanning: Boolean = false,
    val devices: List<DeviceUi> = emptyList(),
    /** Null means the protocol did not provide the value; the UI must show “未知”, never invent it. */
    val firmwareVersion: String? = null,
    val batteryPercent: Int? = null,
    val servicesDiscovered: Boolean = false,
    /** Raw preview is a placeholder until the real packet decoder is wired; null = no preview. */
    val rawPreview: List<Float>? = null,
    val lastError: String? = null,
    val isDemo: Boolean = true,
)

// ---------------------------------------------------------------------------------------------
// Quality
// ---------------------------------------------------------------------------------------------

enum class QualityLevel { Unknown, Good, Fair, Poor, Rejected }

/** Result of one engineering quality check; intentionally not "electrode impedance". */
enum class SignalCheckStatus { Unknown, Pass, Attention, Fail }

enum class QualityReason {
    LowCoverage,
    EogFlat,
    EogClipping,
    MotionArtifact,
    MissingSamples,
    CounterGap,
    BaselineNotReady,
    ModelUnavailable,
    ClockUnstable,
    DeviceGap,
}

data class QualitySummary(
    val level: QualityLevel = QualityLevel.Unknown,
    val coveragePercent: Float? = null,
    val eogCheck: SignalCheckStatus = SignalCheckStatus.Unknown,
    val imuCheck: SignalCheckStatus = SignalCheckStatus.Unknown,
    val reasons: List<QualityReason> = emptyList(),
    val evaluatedEpochs: Int? = null,
)

// ---------------------------------------------------------------------------------------------
// Observation epochs
// ---------------------------------------------------------------------------------------------

/**
 * Observation labels are research/observation tendencies, never confirmed sleep stages.
 * The three model classes are merged: awake, light (N1–N2 merged), other sleep (N3–N4–REM merged).
 */
enum class ObservationLabel {
    BaselineWake,
    LightTendency,
    OtherSleepTendency,
    Active,
    Insufficient,
    Disconnected,
    Unknown,
}

enum class CandidateState { None, Candidate, NeedsReview }

/** Random-forest tree-vote fractions for one epoch. Not calibrated probabilities. */
data class EpochVoteScores(
    val awake: Float,
    val light: Float,
    val otherSleep: Float,
) {
    val total: Float get() = awake + light + otherSleep
}

/** One 30 s observation window as persisted by the observation layer and rendered by the UI. */
data class ObservationEpoch(
    val index: Int,
    val startMillis: Long,
    val endMillis: Long,
    val label: ObservationLabel,
    val candidate: CandidateState = CandidateState.None,
    val receivedSamples: Int,
    val expectedSamples: Int,
    val coveragePercent: Float,
    val quality: QualityLevel,
    val reasons: List<QualityReason> = emptyList(),
    val votes: EpochVoteScores? = null,
    val imuActivityG: Float? = null,
    val eogStdAdc: Float? = null,
    val modelVersion: String? = null,
    val rulesVersion: String? = null,
) {
    val isGap: Boolean
        get() = label == ObservationLabel.Disconnected || quality == QualityLevel.Rejected && receivedSamples == 0

    val hasData: Boolean get() = expectedSamples > 0 && receivedSamples > 0
}

// ---------------------------------------------------------------------------------------------
// Gaps / sessions
// ---------------------------------------------------------------------------------------------

enum class DataGapKind { Disconnect, BufferOverflow, SubscriptionLost, ServiceRestart, DeviceGap, Unknown }

data class DataGapUi(
    val id: Long,
    val kind: DataGapKind,
    val startMillis: Long,
    val endMillis: Long?,
    val droppedEstimate: Long? = null,
    val detail: String? = null,
) {
    val durationMillis: Long? get() = endMillis?.let { (it - startMillis).coerceAtLeast(0L) }
    val isOpen: Boolean get() = endMillis == null
}

data class TimeRangeUi(val startMillis: Long, val endMillis: Long)

enum class SessionStatus { Active, Completed, Interrupted, Failed, Unknown }

data class SessionMetrics(
    val recordedSpanMillis: Long? = null,
    val sampleCount: Long? = null,
    val packetCount: Long? = null,
    val coveragePercent: Float? = null,
    val candidateWindowCount: Int? = null,
    val disconnectedMillis: Long? = null,
)

data class SessionSummary(
    val sessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val deviceName: String?,
    val deviceAddress: String,
    val status: SessionStatus,
    val metrics: SessionMetrics = SessionMetrics(),
    val gapCount: Int = 0,
    /** True when this row is demo data and must be labelled as such in the UI. */
    val isDemo: Boolean = true,
) {
    val durationMillis: Long?
        get() = endedAtMillis?.let { (it - startedAtMillis).coerceAtLeast(0L) }
}

// ---------------------------------------------------------------------------------------------
// Charts
// ---------------------------------------------------------------------------------------------

/** One prepared chart sample. [value] == null marks a hole; the line must not be bridged. */
data class EegChartPoint(val timeMillis: Long, val value: Float?)

enum class TrendSeriesId { EogStdAdc, ImuActivityG, ModelLightVote }

/**
 * Prepared series for [com.feizhela.headband.ui.components.charts.TrendLineChart].
 * Titles/units are passed by the screen so no user-facing copy lives in the model.
 */
data class TrendSeries(
    val id: TrendSeriesId,
    val points: List<EegChartPoint>,
    val gaps: List<TimeRangeUi> = emptyList(),
    val threshold: Float? = null,
    val yMin: Float? = null,
    val yMax: Float? = null,
    val modelAvailable: Boolean = true,
)

data class HourBucket(
    val startMillis: Long,
    val endMillis: Long,
    val epochCount: Int,
    val observedEpochs: Int,
    val coveragePercent: Float,
    val candidateEpochs: Int,
    val insufficientEpochs: Int,
)

data class StageCount(val label: ObservationLabel, val epochCount: Int)

data class SpectrumPoint(val frequencyHz: Float, val powerNormalized: Float)

/**
 * Prepared EOG spectrum. The Canvas draws it; it never runs an FFT itself.
 * Rejected spectra must not draw a zero-filled curve, so [quality] gates the rendering.
 */
data class EogSpectrum(
    val epochIndex: Int,
    val epochStartMillis: Long,
    val points: List<SpectrumPoint>,
    val windowSeconds: Int = 30,
    val frequencyResolutionHz: Float = 1f / 30f,
    val quality: QualityLevel = QualityLevel.Good,
    val rejectionReasons: List<QualityReason> = emptyList(),
) {
    val isRejected: Boolean
        get() = quality == QualityLevel.Rejected || quality == QualityLevel.Poor
}

data class SessionDetail(
    val summary: SessionSummary,
    val metrics: SessionMetrics,
    val epochs: List<ObservationEpoch>,
    val gaps: List<DataGapUi>,
    val hourly: List<HourBucket>,
    val trendSeries: List<TrendSeries>,
    val spectrum: EogSpectrum? = null,
    val rejectedSpectrum: EogSpectrum? = null,
    val quality: QualitySummary = QualitySummary(),
    val protocolVersion: String? = null,
    val firmwareVersion: String? = null,
    val appVersion: String? = null,
    val modelVersion: String? = null,
    val rulesVersion: String? = null,
    val modelAvailable: Boolean = true,
) {
    val stageCounts: List<StageCount>
        get() = epochs
            .groupingBy { it.label }
            .eachCount()
            .map { (label, count) -> StageCount(label, count) }
            .sortedBy { it.label.ordinal }
}

// ---------------------------------------------------------------------------------------------
// Tonight
// ---------------------------------------------------------------------------------------------

enum class TonightPhase { Idle, Starting, Recording, Stopping, Completed, Interrupted, Failed }

enum class SaveState { NotSaved, Pending, Saving, Saved, Failed }

/** Stable failure categories so the UI can map them to resources instead of raw service strings. */
enum class TonightFailure {
    NoDevice,
    ConnectionLost,
    ServiceUnavailable,
    StorageWriteFailed,
    InsufficientData,
    SaveFailed,
}

data class TonightUiState(
    val phase: TonightPhase = TonightPhase.Idle,
    val connection: ConnectionUiState = ConnectionUiState(),
    val startedAtMillis: Long? = null,
    val recordedSpanMillis: Long = 0L,
    val packetCount: Long = 0L,
    val sampleCount: Long? = null,
    val gapCount: Int = 0,
    val coveragePercent: Float? = null,
    val quality: QualitySummary = QualitySummary(),
    val candidateWindowCount: Int = 0,
    val saveState: SaveState = SaveState.NotSaved,
    val failure: TonightFailure? = null,
    /** Raw reason from the service/repository, shown verbatim for diagnosis. */
    val technicalDetail: String? = null,
    val completedSessionId: String? = null,
    val isDemo: Boolean = true,
) {
    val candidateNeedsReview: Boolean get() = candidateWindowCount > 0
    val isRecording: Boolean get() = phase == TonightPhase.Recording
}

// ---------------------------------------------------------------------------------------------
// Diary (self-reported, never merged with device observation)
// ---------------------------------------------------------------------------------------------

enum class DiarySource { SelfReported }

enum class DiaryFeeling { VeryPoor, Poor, Fair, Good, VeryGood }

/**
 * User-entered sleep diary. Times are absolute epoch millis derived from local date + HH:mm.
 * Everything here is `self_reported`; it is never treated as a device measurement.
 */
data class DiaryEntry(
    val sleepStartMillis: Long?,
    val finalWakeMillis: Long?,
    val awakenings: Int?,
    val awakeMinutes: Int?,
    val feeling: DiaryFeeling?,
    val note: String = "",
    val updatedAtMillis: Long,
    val source: DiarySource = DiarySource.SelfReported,
) {
    /** Self-reported time in bed; may be null when a time is missing. */
    val timeInBedMillis: Long?
        get() {
            val start = sleepStartMillis ?: return null
            val wake = finalWakeMillis ?: return null
            val span = wake - start
            val day = 24L * 60L * 60L * 1000L
            return if (span <= 0) span + day else span
        }

    /** Self-reported sleep = time in bed − awake minutes. Clearly not a device measurement. */
    val selfReportedSleepMillis: Long?
        get() {
            val inBed = timeInBedMillis ?: return null
            val awake = (awakeMinutes ?: 0).coerceAtLeast(0) * 60_000L
            return (inBed - awake).coerceAtLeast(0L)
        }
}

// ---------------------------------------------------------------------------------------------
// Filters
// ---------------------------------------------------------------------------------------------

enum class SessionStatusFilter { All, Completed, Interrupted, Failed }

enum class SessionDateFilter { All, Last24Hours, Last7Days, Older }

fun SessionSummary.matchesFilter(
    statusFilter: SessionStatusFilter,
    dateFilter: SessionDateFilter,
    nowMillis: Long,
): Boolean {
    val statusMatches = when (statusFilter) {
        SessionStatusFilter.All -> true
        SessionStatusFilter.Completed -> status == SessionStatus.Completed
        SessionStatusFilter.Interrupted -> status == SessionStatus.Interrupted
        SessionStatusFilter.Failed -> status == SessionStatus.Failed
    }
    if (!statusMatches) return false
    val day = 24L * 60L * 60L * 1000L
    val age = nowMillis - startedAtMillis
    return when (dateFilter) {
        SessionDateFilter.All -> true
        SessionDateFilter.Last24Hours -> age in 0..day
        SessionDateFilter.Last7Days -> age in 0..(7 * day)
        SessionDateFilter.Older -> age > 7 * day
    }
}
