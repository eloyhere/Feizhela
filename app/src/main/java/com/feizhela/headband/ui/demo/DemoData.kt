package com.feizhela.headband.ui.demo

import com.feizhela.headband.ui.model.CandidateState
import com.feizhela.headband.ui.model.ConnectionUiState
import com.feizhela.headband.ui.model.ConnectionUiStatus
import com.feizhela.headband.ui.model.DataGapKind
import com.feizhela.headband.ui.model.DataGapUi
import com.feizhela.headband.ui.model.DeviceUi
import com.feizhela.headband.ui.model.DeviceUiState
import com.feizhela.headband.ui.model.DiaryEntry
import com.feizhela.headband.ui.model.DiaryFeeling
import com.feizhela.headband.ui.model.EegChartPoint
import com.feizhela.headband.ui.model.EogSpectrum
import com.feizhela.headband.ui.model.EpochVoteScores
import com.feizhela.headband.ui.model.HourBucket
import com.feizhela.headband.ui.model.ObservationEpoch
import com.feizhela.headband.ui.model.ObservationLabel
import com.feizhela.headband.ui.model.QualityLevel
import com.feizhela.headband.ui.model.QualityReason
import com.feizhela.headband.ui.model.QualitySummary
import com.feizhela.headband.ui.model.SaveState
import com.feizhela.headband.ui.model.SessionDetail
import com.feizhela.headband.ui.model.SessionMetrics
import com.feizhela.headband.ui.model.SessionStatus
import com.feizhela.headband.ui.model.SessionSummary
import com.feizhela.headband.ui.model.SignalCheckStatus
import com.feizhela.headband.ui.model.SpectrumPoint
import com.feizhela.headband.ui.model.TimeRangeUi
import com.feizhela.headband.ui.model.TonightPhase
import com.feizhela.headband.ui.model.TonightUiState
import com.feizhela.headband.ui.model.TrendSeries
import com.feizhela.headband.ui.model.TrendSeriesId
import java.util.Calendar
import java.util.Random
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Demo data for the whole UI.
 *
 * The data is generated once per app start and is **labelled as demo** everywhere it appears.
 * It intentionally models the awkward parts of a real night: 30 s epochs, disconnect/device gaps,
 * insufficient-coverage epochs, an unavailable-model segment, candidate windows and tree-vote
 * scores. It never contains “confirmed sleep stage”, objective sleep duration or a
 * calibrated-probability field, because the product cannot produce those.
 *
 * TODO(service-integration): replace this object with a UI repository/adapter that maps
 * `RecordingLibrary.observeSessions()` (read-only Room) into [SessionSummary] and loads detail
 * aggregates from a purpose-built query; keep this file for previews and UI tests.
 */
object DemoData {
    const val MODEL_VERSION = "feizhela-eog-rf-0.1"
    const val RULES_VERSION = "eog-rf-quality-causal-0.2"
    const val PROTOCOL_VERSION = "feizhela-ble-18B-v1"
    const val APP_VERSION = "1.0-demo"

    const val EPOCH_MILLIS = 30_000L
    const val SAMPLE_RATE_HZ = 104
    const val SAMPLES_PER_PACKET = 10

    /** Everything the UI needs before a real data layer exists. */
    data class DemoBundle(
        val loadedAtMillis: Long,
        val device: DeviceUiState,
        val tonight: TonightUiState,
        val sessions: List<SessionSummary>,
        val details: Map<String, SessionDetail>,
        val diary: DiaryEntry?,
    )

    fun load(nowMillis: Long = System.currentTimeMillis()): DemoBundle {
        val completedStart = alignToMinute(nowMillis - 9L * HOUR)
        val completed = buildSession(
            sessionId = "demo-night-8h",
            startMillis = completedStart,
            durationMillis = 8L * HOUR,
            deviceName = "菲哲拉头带 · 演示",
            deviceAddress = "DE:MO:00:00:00:01",
            status = SessionStatus.Completed,
            seed = 20261103L,
            gapPlan = listOf(244..247, 612..613, 806..808, 934..935),
            unknownPlan = listOf(742..744, 868..869),
            activeBias = 1f,
        )
        val interruptedStart = alignToMinute(nowMillis - 50L * HOUR)
        val interrupted = buildSession(
            sessionId = "demo-night-interrupted",
            startMillis = interruptedStart,
            durationMillis = 2L * HOUR + 13L * MINUTE,
            deviceName = "菲哲拉头带 · 演示",
            deviceAddress = "DE:MO:00:00:00:01",
            status = SessionStatus.Interrupted,
            seed = 771203L,
            gapPlan = listOf(78..81, 252..265),
            unknownPlan = emptyList(),
            activeBias = 1.4f,
            openTailGap = true,
        )
        val olderStart = alignToMinute(nowMillis - 100L * HOUR)
        val older = buildSession(
            sessionId = "demo-night-older",
            startMillis = olderStart,
            durationMillis = 5L * HOUR + 12L * MINUTE,
            deviceName = "菲哲拉头带 · 演示",
            deviceAddress = "DE:MO:00:00:00:01",
            status = SessionStatus.Completed,
            seed = 5150L,
            gapPlan = listOf(96..99, 402..404),
            unknownPlan = listOf(300..302),
            activeBias = 0.8f,
        )
        val details = listOf(completed, interrupted, older).associateBy { it.summary.sessionId }
        val sessions = details.values.map { it.summary }.sortedByDescending { it.startedAtMillis }

        val device = deviceState(nowMillis)
        val tonight = TonightUiState(
            phase = TonightPhase.Idle,
            connection = device.connection,
            saveState = SaveState.NotSaved,
            isDemo = true,
        )
        return DemoBundle(
            loadedAtMillis = nowMillis,
            device = device,
            tonight = tonight,
            sessions = sessions,
            details = details,
            diary = demoDiary(nowMillis),
        )
    }

    fun deviceState(nowMillis: Long = System.currentTimeMillis()): DeviceUiState {
        val devices = listOf(
            DeviceUi(
                address = "DE:MO:00:00:00:01",
                name = "菲哲拉头带 · 演示",
                rssi = -47,
                isDemo = true,
            ),
            DeviceUi(
                address = "DE:MO:00:00:00:02",
                name = "头带样例 A",
                rssi = -68,
                isDemo = true,
            ),
            DeviceUi(
                address = "DE:MO:00:00:00:03",
                name = null,
                rssi = -82,
                isDemo = true,
            ),
        )
        val connected = devices.first()
        return DeviceUiState(
            connection = ConnectionUiState(
                status = ConnectionUiStatus.Connected,
                deviceName = connected.name,
                deviceAddress = connected.address,
                lastPacketAtMillis = nowMillis - 45_000L,
            ),
            scanning = false,
            devices = devices,
            // The protocol does not expose firmware/battery yet: keep null, render “未知”.
            firmwareVersion = null,
            batteryPercent = null,
            servicesDiscovered = true,
            rawPreview = null,
            lastError = null,
            isDemo = true,
        )
    }

    fun demoDiary(nowMillis: Long): DiaryEntry {
        val sleepStart = timeOnPreviousNight(nowMillis, hour = 23, minute = 15)
        val finalWake = sleepStart + 7L * HOUR + 25L * MINUTE
        return DiaryEntry(
            sleepStartMillis = sleepStart,
            finalWakeMillis = finalWake,
            awakenings = 2,
            awakeMinutes = 25,
            feeling = DiaryFeeling.Fair,
            note = "夜间被楼下噪音吵醒一次。",
            updatedAtMillis = nowMillis,
        )
    }

    private fun timeOnPreviousNight(nowMillis: Long, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    // -----------------------------------------------------------------------------------------
    // Generation
    // -----------------------------------------------------------------------------------------

    private fun buildSession(
        sessionId: String,
        startMillis: Long,
        durationMillis: Long,
        deviceName: String,
        deviceAddress: String,
        status: SessionStatus,
        seed: Long,
        gapPlan: List<IntRange>,
        unknownPlan: List<IntRange>,
        activeBias: Float,
        openTailGap: Boolean = false,
    ): SessionDetail {
        val random = Random(seed)
        val epochCount = max(1, (durationMillis / EPOCH_MILLIS).toInt())
        val endMillis = startMillis + epochCount * EPOCH_MILLIS
        val labels = buildLabels(random, epochCount, gapPlan, unknownPlan, activeBias)
        val epochs = mutableListOf<ObservationEpoch>()

        for (index in 0 until epochCount) {
            val epochStart = startMillis + index * EPOCH_MILLIS
            val label = labels[index]
            val inGap = gapPlan.any { index in it }
            val expectedSamples = SAMPLE_RATE_HZ * (EPOCH_MILLIS / 1000L).toInt()
            val coverage = when {
                inGap -> 0f
                label == ObservationLabel.Insufficient -> 84f + random.nextFloat() * 13f
                random.nextFloat() < 0.08f -> 95.5f + random.nextFloat() * 3.8f
                else -> 99.2f + random.nextFloat() * 0.8f
            }
            val receivedSamples = (expectedSamples * coverage / 100f).roundToInt()
            val reasons = mutableListOf<QualityReason>()
            val quality = when {
                inGap -> {
                    reasons += QualityReason.DeviceGap
                    reasons += QualityReason.MissingSamples
                    QualityLevel.Rejected
                }

                label == ObservationLabel.Insufficient -> {
                    reasons += QualityReason.LowCoverage
                    reasons += QualityReason.MissingSamples
                    QualityLevel.Rejected
                }

                label == ObservationLabel.Active -> {
                    reasons += QualityReason.MotionArtifact
                    QualityLevel.Poor
                }

                label == ObservationLabel.Unknown -> {
                    reasons += QualityReason.ModelUnavailable
                    QualityLevel.Unknown
                }

                coverage < 99.5f -> {
                    reasons += QualityReason.MissingSamples
                    if (random.nextInt(6) == 0) reasons += QualityReason.EogClipping
                    QualityLevel.Fair
                }

                random.nextInt(9) == 0 -> {
                    reasons += QualityReason.EogClipping
                    QualityLevel.Fair
                }

                else -> QualityLevel.Good
            }
            val votable = !inGap &&
                label != ObservationLabel.Active &&
                label != ObservationLabel.Unknown &&
                label != ObservationLabel.Insufficient &&
                quality != QualityLevel.Rejected
            val votes = if (votable) votesFor(label, random) else null
            val imu = when {
                inGap -> null
                label == ObservationLabel.Active -> 0.05f + random.nextFloat() * 0.11f
                else -> 0.008f + random.nextFloat() * 0.016f
            }
            val eogStd = if (inGap) null else {
                val base = 2700f + random.nextFloat() * 900f
                if (label == ObservationLabel.Active) base * 1.8f else base
            }
            epochs += ObservationEpoch(
                index = index,
                startMillis = epochStart,
                endMillis = epochStart + EPOCH_MILLIS,
                label = label,
                receivedSamples = receivedSamples,
                expectedSamples = expectedSamples,
                coveragePercent = coverage,
                quality = quality,
                reasons = reasons,
                votes = votes,
                imuActivityG = imu,
                eogStdAdc = eogStd,
                modelVersion = if (label == ObservationLabel.Unknown) null else MODEL_VERSION,
                rulesVersion = RULES_VERSION,
            )
        }

        val candidateWindowCount = markCandidates(epochs, random)
        val expectedTotal = epochCount.toLong() * (SAMPLE_RATE_HZ * 30)
        val receivedTotal = epochs.sumOf { it.receivedSamples.toLong() }
        val overallCoverage = if (expectedTotal == 0L) null else {
            (receivedTotal.toFloat() / expectedTotal.toFloat() * 100f)
        }
        val gaps = buildGaps(epochs, gapPlan, openTailGap)
        val disconnectedMillis = gaps.sumOf { gap ->
            gap.durationMillis ?: ((endMillis - gap.startMillis).coerceAtLeast(0L))
        }
        val metrics = SessionMetrics(
            recordedSpanMillis = endMillis - startMillis,
            sampleCount = receivedTotal,
            packetCount = receivedTotal / SAMPLES_PER_PACKET,
            coveragePercent = overallCoverage,
            candidateWindowCount = candidateWindowCount,
            disconnectedMillis = disconnectedMillis,
        )
        val summary = SessionSummary(
            sessionId = sessionId,
            startedAtMillis = startMillis,
            endedAtMillis = endMillis,
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            status = status,
            metrics = metrics,
            gapCount = gaps.size,
            isDemo = true,
        )
        val quality = qualitySummary(epochs, overallCoverage, gaps)
        val spectrumEpoch = epochs.firstOrNull {
            it.label == ObservationLabel.LightTendency && it.candidate != CandidateState.None
        } ?: epochs.firstOrNull { it.votes != null }
        val rejectedEpoch = epochs.firstOrNull { it.label == ObservationLabel.Active }
            ?: epochs.firstOrNull { it.quality == QualityLevel.Rejected }

        return SessionDetail(
            summary = summary,
            metrics = metrics,
            epochs = epochs,
            gaps = gaps,
            hourly = hourlyBuckets(epochs),
            trendSeries = trendSeries(epochs, gaps, endMillis),
            spectrum = spectrumEpoch?.let { spectrumFor(it, random, rejected = false) },
            rejectedSpectrum = rejectedEpoch?.let { spectrumFor(it, random, rejected = true) },
            quality = quality,
            protocolVersion = PROTOCOL_VERSION,
            firmwareVersion = null,
            appVersion = APP_VERSION,
            modelVersion = MODEL_VERSION,
            rulesVersion = RULES_VERSION,
            modelAvailable = epochs.any { it.votes != null },
        )
    }

    private fun buildLabels(
        random: Random,
        epochCount: Int,
        gapPlan: List<IntRange>,
        unknownPlan: List<IntRange>,
        activeBias: Float,
    ): List<ObservationLabel> {
        val labels = mutableListOf<ObservationLabel>()
        var first = true
        while (labels.size < epochCount) {
            val label = if (first) ObservationLabel.BaselineWake else pickLabel(random, activeBias)
            val runLength = if (first) 8 else 2 + random.nextInt(12)
            repeat(minOf(runLength, epochCount - labels.size)) { labels += label }
            first = false
        }
        gapPlan.forEach { range ->
            for (index in range) {
                if (index in labels.indices) labels[index] = ObservationLabel.Disconnected
            }
        }
        unknownPlan.forEach { range ->
            for (index in range) {
                if (index in labels.indices && labels[index] != ObservationLabel.Disconnected) {
                    labels[index] = ObservationLabel.Unknown
                }
            }
        }
        return labels
    }

    private fun pickLabel(random: Random, activeBias: Float): ObservationLabel {
        val roll = random.nextInt(100)
        val activeThreshold = (9 * activeBias).roundToInt()
        return when {
            roll < 12 -> ObservationLabel.BaselineWake
            roll < 52 -> ObservationLabel.LightTendency
            roll < 68 -> ObservationLabel.OtherSleepTendency
            roll < 68 + activeThreshold -> ObservationLabel.Active
            roll < 86 + activeThreshold -> ObservationLabel.Insufficient
            else -> ObservationLabel.Unknown
        }
    }

    private fun votesFor(label: ObservationLabel, random: Random): EpochVoteScores {
        var awake: Float
        var light: Float
        var other: Float
        when (label) {
            ObservationLabel.BaselineWake -> {
                awake = 0.55f + random.nextFloat() * 0.3f
                light = 0.05f + random.nextFloat() * 0.25f
                other = 0.05f + random.nextFloat() * 0.15f
            }

            ObservationLabel.LightTendency -> {
                light = 0.5f + random.nextFloat() * 0.35f
                awake = 0.05f + random.nextFloat() * 0.25f
                other = 0.05f + random.nextFloat() * 0.2f
            }

            ObservationLabel.OtherSleepTendency -> {
                other = 0.5f + random.nextFloat() * 0.35f
                light = 0.08f + random.nextFloat() * 0.25f
                awake = 0.03f + random.nextFloat() * 0.15f
            }

            else -> {
                awake = 0.4f
                light = 0.3f
                other = 0.3f
            }
        }
        val total = awake + light + other
        awake /= total
        light /= total
        other /= total
        return EpochVoteScores(awake = awake, light = light, otherSleep = other)
    }

    /** Candidate = at least three consecutive qualifying light-tendency epochs (demo rule). */
    private fun markCandidates(epochs: MutableList<ObservationEpoch>, random: Random): Int {
        var windows = 0
        var index = 0
        while (index < epochs.size) {
            val start = index
            val qualifying = {
                val epoch = epochs[start]
                epoch.label == ObservationLabel.LightTendency &&
                    (epoch.votes?.light ?: 0f) >= 0.6f &&
                    epoch.quality != QualityLevel.Rejected
            }
            if (!qualifying()) {
                index += 1
                continue
            }
            var end = start
            while (end < epochs.size) {
                val epoch = epochs[end]
                val ok = epoch.label == ObservationLabel.LightTendency &&
                    (epoch.votes?.light ?: 0f) >= 0.6f &&
                    epoch.quality != QualityLevel.Rejected
                if (!ok) break
                end += 1
            }
            if (end - start >= 3) {
                windows += 1
                val state = if (windows % 3 == 0 || random.nextInt(4) == 0) {
                    CandidateState.NeedsReview
                } else {
                    CandidateState.Candidate
                }
                for (position in start until end) {
                    epochs[position] = epochs[position].copy(candidate = state)
                }
            }
            index = max(end, start + 1)
        }
        return windows
    }

    private fun buildGaps(
        epochs: List<ObservationEpoch>,
        gapPlan: List<IntRange>,
        openTailGap: Boolean,
    ): List<DataGapUi> {
        var id = 1L
        return gapPlan.mapIndexed { position, range ->
            val safe = range.filter { it in epochs.indices }
            val first = epochs[safe.firstOrNull() ?: 0]
            val last = epochs[safe.lastOrNull() ?: (epochs.size - 1)]
            val isTailOpen = openTailGap && position == gapPlan.lastIndex
            val kind = when {
                position == 1 -> DataGapKind.DeviceGap
                position == 2 -> DataGapKind.BufferOverflow
                else -> DataGapKind.Disconnect
            }
            DataGapUi(
                id = id++,
                kind = kind,
                startMillis = first.startMillis,
                endMillis = if (isTailOpen) null else last.endMillis,
                droppedEstimate = ((last.endMillis - first.startMillis) / EPOCH_MILLIS *
                    (SAMPLE_RATE_HZ * 30 / SAMPLES_PER_PACKET)).coerceAtLeast(1L),
                detail = when (kind) {
                    DataGapKind.DeviceGap -> "DEVICE_GAP"
                    DataGapKind.BufferOverflow -> "BUFFER_OVERFLOW"
                    else -> "BLE_DISCONNECTED"
                },
            )
        }
    }

    private fun hourlyBuckets(epochs: List<ObservationEpoch>): List<HourBucket> {
        if (epochs.isEmpty()) return emptyList()
        return epochs.groupBy { startOfHour(it.startMillis) }
            .toSortedMap()
            .map { (hourStart, group) ->
                val observed = group.count { it.receivedSamples > 0 }
                HourBucket(
                    startMillis = hourStart,
                    endMillis = hourStart + HOUR,
                    epochCount = group.size,
                    observedEpochs = observed,
                    coveragePercent = group.map { it.coveragePercent }.average().toFloat(),
                    candidateEpochs = group.count { it.candidate != CandidateState.None },
                    insufficientEpochs = group.count { it.label == ObservationLabel.Insufficient },
                )
            }
    }

    private fun startOfHour(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun trendSeries(
        epochs: List<ObservationEpoch>,
        gaps: List<DataGapUi>,
        sessionEndMillis: Long,
    ): List<TrendSeries> {
        val gapRanges = gaps.map { gap ->
            TimeRangeUi(gap.startMillis, gap.endMillis ?: sessionEndMillis)
        }
        val eog = epochs.map { EegChartPoint(it.startMillis, it.eogStdAdc) }
        val imu = epochs.map { EegChartPoint(it.startMillis, it.imuActivityG) }
        val votes = epochs.map { EegChartPoint(it.startMillis, it.votes?.light) }
        return listOf(
            TrendSeries(
                id = TrendSeriesId.EogStdAdc,
                points = eog,
                gaps = gapRanges,
                threshold = null,
                yMin = 0f,
            ),
            TrendSeries(
                id = TrendSeriesId.ImuActivityG,
                points = imu,
                gaps = gapRanges,
                threshold = 0.035f,
                yMin = 0f,
            ),
            TrendSeries(
                id = TrendSeriesId.ModelLightVote,
                points = votes,
                gaps = gapRanges,
                threshold = 0.60f,
                yMin = 0f,
                yMax = 1f,
                modelAvailable = epochs.any { it.votes != null },
            ),
        )
    }

    private fun qualitySummary(
        epochs: List<ObservationEpoch>,
        coverage: Float?,
        gaps: List<DataGapUi>,
    ): QualitySummary {
        val clipping = epochs.count { QualityReason.EogClipping in it.reasons }
        val active = epochs.count { it.label == ObservationLabel.Active }
        val evaluated = epochs.count { it.votes != null }
        val reasons = buildList {
            if (active * 100 / max(epochs.size, 1) > 4) add(QualityReason.MotionArtifact)
            if (gaps.isNotEmpty()) add(QualityReason.DeviceGap)
            if (clipping > 0) add(QualityReason.EogClipping)
            if (evaluated < epochs.size) add(QualityReason.MissingSamples)
        }
        return QualitySummary(
            level = when {
                coverage == null -> QualityLevel.Unknown
                coverage >= 99f -> QualityLevel.Good
                coverage >= 96f -> QualityLevel.Fair
                else -> QualityLevel.Poor
            },
            coveragePercent = coverage,
            eogCheck = when {
                clipping == 0 -> SignalCheckStatus.Pass
                clipping < 6 -> SignalCheckStatus.Attention
                else -> SignalCheckStatus.Fail
            },
            imuCheck = if (active * 100 / max(epochs.size, 1) > 6) {
                SignalCheckStatus.Attention
            } else {
                SignalCheckStatus.Pass
            },
            reasons = reasons,
            evaluatedEpochs = evaluated,
        )
    }

    private fun spectrumFor(
        epoch: ObservationEpoch,
        random: Random,
        rejected: Boolean,
    ): EogSpectrum {
        if (rejected) {
            return EogSpectrum(
                epochIndex = epoch.index,
                epochStartMillis = epoch.startMillis,
                points = emptyList(),
                quality = QualityLevel.Rejected,
                rejectionReasons = listOf(QualityReason.MotionArtifact, QualityReason.EogClipping),
            )
        }
        val points = (0 until 120).map { index ->
            val frequency = MIN_SPECTRUM_HZ +
                index * (MAX_SPECTRUM_HZ - MIN_SPECTRUM_HZ) / 119f
            val lowPeak = 0.55f * exp(-((frequency - 1.1f) * (frequency - 1.1f)) / 0.28f)
            val secondPeak = 0.22f * exp(-((frequency - 3.4f) * (frequency - 3.4f)) / 1.4f)
            val floor = 0.05f + random.nextFloat() * 0.03f
            SpectrumPoint(
                frequencyHz = frequency,
                powerNormalized = (lowPeak + secondPeak + floor).coerceIn(0f, 1f),
            )
        }
        return EogSpectrum(
            epochIndex = epoch.index,
            epochStartMillis = epoch.startMillis,
            points = points,
            quality = QualityLevel.Good,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Small helpers
    // -----------------------------------------------------------------------------------------

    private const val MINUTE = 60_000L
    private const val HOUR = 60L * MINUTE

    private const val MIN_SPECTRUM_HZ = 0.1f
    private const val MAX_SPECTRUM_HZ = 10f

    private fun alignToMinute(millis: Long): Long = millis - millis % MINUTE
}
