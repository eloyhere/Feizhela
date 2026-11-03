package com.feizhela.headband.recording

import com.feizhela.headband.signal.Analyse
import com.feizhela.headband.storage.RecordingDao
import com.feizhela.headband.storage.SignalEpochDao
import com.feizhela.headband.storage.SignalEpochEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Follows committed Room input. This owner never subscribes to a BLE stream or writes raw packets. */
class SignalAnalysisRepository(
    private val recordings: RecordingDao,
    private val epochs: SignalEpochDao,
    private val estimate: (DoubleArray) -> Evidence = ::estimateEog,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = mutableState
    private var active: Cursor? = null
    private val finished = hashSetOf<String>()

    /** A conflated wakeup is sufficient: the serial reader always drains the durable archive. */
    fun follow(scope: CoroutineScope, recordingState: StateFlow<RecordingState>): Job = scope.launch {
        val wakeups = Channel<Unit>(Channel.CONFLATED)
        val commits = launch { recordingState.collect { wakeups.trySend(Unit) } }
        val sessions = launch { recordings.observeSessions().collect { wakeups.trySend(Unit) } }
        try {
            for (ignored in wakeups) {
                try {
                    reconcile()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Analysis/storage faults are observable here; they never fail the recording.
                    mutableState.value = mutableState.value.copy(lastError = error.message ?: error.javaClass.simpleName)
                    // Rebuild from the source on a subsequent commit; partially persisted epochs upsert.
                    active = null
                }
            }
        } finally {
            commits.cancel()
            sessions.cancel()
            wakeups.close()
        }
    }

    /** Startup replays terminal archives once per owner, repairing even a crash within a packet. */
    suspend fun reconcile() = mutex.withLock {
        val sessions = recordings.observeSessions().first()
        val existing = sessions.mapTo(hashSetOf()) { it.id }
        finished.retainAll(existing)
        if (active?.sessionId !in existing) active = null
        for (session in sessions.filter { it.status != "ACTIVE" && it.id !in finished }) {
            val cursor = active?.takeIf { it.sessionId == session.id } ?: Cursor(session.id)
            drain(cursor)
            cursor.assembler.finishSession { persist(cursor.sessionId, it) }
            finished += session.id
            if (active === cursor) active = null
        }
        val session = sessions.firstOrNull { it.status == "ACTIVE" } ?: return@withLock
        val cursor = active?.takeIf { it.sessionId == session.id } ?: Cursor(session.id).also { active = it }
        drain(cursor)
    }

    /** Used before service teardown, after the archive writer has flushed and marked terminal. */
    suspend fun drainFinal() {
        try {
            reconcile()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(lastError = error.message ?: error.javaClass.simpleName)
        }
    }

    private suspend fun drain(cursor: Cursor) = withContext(Dispatchers.Default) {
        while (true) {
            val page = recordings.readPackets(cursor.sessionId, cursor.afterSequence, PAGE_SIZE)
            if (page.isEmpty()) break
            for (packet in page) {
                cursor.assembler.accept(packet) { persist(cursor.sessionId, it) }
                cursor.afterSequence = packet.sequence
            }
            mutableState.value = mutableState.value.copy(
                sessionId = cursor.sessionId, throughPacketSequence = cursor.afterSequence,
            )
        }
    }

    private suspend fun persist(sessionId: String, window: SignalEpochAssembler.Window) {
        var epoch = SignalEpochEntity(
            sessionId = sessionId, epochIndex = window.epochIndex,
            startSampleIndex = window.startSampleIndex, startedAtMillis = window.startedAtMillis,
            firstPacketSequence = window.firstPacketSequence, lastPacketSequence = window.lastPacketSequence,
            receivedSamples = window.receivedSamples, quality = window.quality,
            reason = window.reason.ifEmpty { null }, algorithmVersion = ALGORITHM_VERSION, configJson = CONFIG_JSON,
        )
        window.eog?.let { raw ->
            val mean = raw.average()
            val centered = DoubleArray(raw.size) { raw[it] - mean }
            val energy = centered.sumOf { it * it }
            if (raw.any { it <= 0.0 || it >= 65535.0 } || energy <= 1e-12) {
                epoch = epoch.copy(quality = "REJECTED", reason = if (energy <= 1e-12) "FLAT_SIGNAL" else "ADC_RAIL")
            } else {
                try {
                    val result = withContext(Dispatchers.Default) { estimate(centered) }
                    require(result.residualRatio.isFinite()) { "Non-finite spectral residual" }
                    epoch = epoch.copy(
                        quality = "SPECTRAL_ONLY", reason = "PHYSIOLOGICAL_QUALITY_UNCALIBRATED",
                        componentsJson = result.componentsJson, analysisWindow = result.analysisWindow,
                        residualRatio = result.residualRatio, verified = result.verified,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    epoch = epoch.copy(quality = "ANALYSIS_ERROR", reason = error.message ?: error.javaClass.simpleName)
                }
            }
        }
        epochs.upsertEpoch(epoch)
        mutableState.value = mutableState.value.copy(
            sessionId = sessionId, lastEpochIndex = epoch.epochIndex, lastQuality = epoch.quality, lastError = null,
        )
    }

    data class Evidence(val componentsJson: String, val analysisWindow: Int, val residualRatio: Double, val verified: Boolean)
    data class AnalysisState(
        val sessionId: String? = null,
        val throughPacketSequence: Long = -1,
        val lastEpochIndex: Long = -1,
        val lastQuality: String? = null,
        val lastError: String? = null,
    )
    private class Cursor(val sessionId: String) {
        var afterSequence = -1L
        val assembler = SignalEpochAssembler()
    }

    companion object {
        const val ALGORITHM_VERSION = "analyse-calendar-eog-v1"
        const val CONFIG_JSON = "{\"rawRateHz\":104,\"analysisRateHz\":20,\"epochSamples\":3120,\"components\":3,\"center\":\"per-epoch-mean\",\"resampling\":\"FIR-Lanczos\",\"counterPolicy\":\"modulo-half-range-segment-v1\",\"qualityGate\":\"coverage-continuity-flat-adc-rails-v1\",\"units\":\"raw-ADC-counts\",\"timestamp\":\"receive-anchored-estimate\"}"
        private const val PAGE_SIZE = 128
        private fun estimateEog(centered: DoubleArray): Evidence {
            val result = Analyse.estimateFromRaw(centered, 104.0, 20.0, 3)
            val components = result.tones.joinToString(prefix = "[", postfix = "]") { tone ->
                val amplitude = tone.amplitude
                val frequency = tone.omega() / (2.0 * Math.PI)
                val phase = tone.phase.toRadian().value
                require(amplitude.isFinite() && frequency.isFinite() && phase.isFinite()) { "Non-finite spectral component" }
                "{\"amplitude\":$amplitude,\"frequencyHz\":$frequency,\"phaseRadians\":$phase}"
            }
            return Evidence(components, result.analysisWindow, result.residualRatio, result.isVerified)
        }
    }
}
