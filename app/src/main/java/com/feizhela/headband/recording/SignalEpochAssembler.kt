package com.feizhela.headband.recording

import com.feizhela.headband.bluetooth.protocol.FirmwarePacketDecoder
import com.feizhela.headband.bluetooth.protocol.SampleCounterTracker
import com.feizhela.headband.storage.RawPacketEntity

/** Bounded, single-owner framing on the device-counter timeline, never the archive packet index. */
internal class SignalEpochAssembler {
    private val counters = SampleCounterTracker()
    private var epochIndex = 0L
    private var position = 0L
    private var windowStart = 0L
    private var anchorTime: Long? = null
    private var firstPacket = -1L
    private var lastPacket = -1L
    private var previousReceiveTime: Long? = null
    private var occupied = 0
    private var received = 0
    private var samples = DoubleArray(SAMPLES_PER_EPOCH)
    private val reasons = linkedSetOf<String>()

    suspend fun accept(packet: RawPacketEntity, emit: suspend (Window) -> Unit) {
        val decoded = try {
            FirmwarePacketDecoder.decode(packet.payload)
        } catch (_: IllegalArgumentException) {
            reasons += "MALFORMED_PACKET"
            if (anchorTime == null) anchorTime = packet.receivedAtMillis
            if (firstPacket < 0) firstPacket = packet.sequence
            lastPacket = packet.sequence
            return
        }
        // A counter-only unwrap is ambiguous beyond half its range. Receive time is only a
        // conservative rejection aid; wall-clock changes never supply missing samples.
        val receiveDelta = previousReceiveTime?.let { packet.receivedAtMillis - it }
        val uncertainTime = receiveDelta != null && (receiveDelta < 0 || receiveDelta >= HALF_COUNTER_MILLIS)
        previousReceiveTime = packet.receivedAtMillis
        if (uncertainTime) {
            reasons += "AMBIGUOUS_RECEIVE_TIME"
            if (occupied > 0 || firstPacket >= 0) emit(finish("INCOMPLETE"))
            counters.reset()
            position = ((position + SAMPLES_PER_EPOCH - 1) / SAMPLES_PER_EPOCH) * SAMPLES_PER_EPOCH
            windowStart = position
            anchorTime = packet.receivedAtMillis - 9L * 1000 / RAW_RATE - (position * 1000 / RAW_RATE)
            reasons += "SEGMENT_AFTER_AMBIGUOUS_TIME"
        }
        if (anchorTime == null) anchorTime = packet.receivedAtMillis - 9L * 1000 / RAW_RATE
        for ((sampleIndex, sample) in decoded.samples.withIndex()) {
            when (val continuity = counters.observe(sample.counter)) {
                is SampleCounterTracker.Result.Missing -> {
                    reasons += "MISSING_DEVICE_SAMPLES"
                    repeat(continuity.droppedCount) {
                        reasons += "MISSING_DEVICE_SAMPLES"
                        touch(packet.sequence)
                        advance(emit)
                    }
                }
                is SampleCounterTracker.Result.Duplicate -> {
                    reasons += "DUPLICATE_COUNTER"
                    touch(packet.sequence)
                    continue
                }
                is SampleCounterTracker.Result.BackwardOrAmbiguousReset -> {
                    reasons += "COUNTER_RESET_OR_BACKWARD"
                    if (occupied > 0 || firstPacket >= 0) emit(finish("INCOMPLETE"))
                    position = ((position + SAMPLES_PER_EPOCH - 1) / SAMPLES_PER_EPOCH) * SAMPLES_PER_EPOCH
                    windowStart = position
                    anchorTime = packet.receivedAtMillis - (9L - sampleIndex) * 1000 / RAW_RATE -
                        (position * 1000 / RAW_RATE)
                    reasons += "SEGMENT_AFTER_COUNTER_RESET"
                }
                else -> Unit
            }
            touch(packet.sequence)
            samples[received++] = sample.eogRaw.toDouble()
            advance(emit)
        }
    }

    suspend fun finishSession(emit: suspend (Window) -> Unit) {
        if (occupied > 0 || firstPacket >= 0) emit(finish("INCOMPLETE"))
    }

    private fun touch(sequence: Long) {
        if (firstPacket < 0) firstPacket = sequence
        lastPacket = sequence
    }

    private suspend fun advance(emit: suspend (Window) -> Unit) {
        occupied++
        position++
        if (occupied == SAMPLES_PER_EPOCH) emit(finish(if (reasons.isEmpty()) "READY" else "REJECTED"))
    }

    private fun finish(quality: String): Window {
        val result = Window(
            epochIndex++, windowStart, (anchorTime ?: 0L) + windowStart * 1000 / RAW_RATE,
            firstPacket, lastPacket, received, quality,
            (reasons + if (occupied < SAMPLES_PER_EPOCH) setOf("PARTIAL_WINDOW") else emptySet()).joinToString(";"),
            if (quality == "READY" && received == SAMPLES_PER_EPOCH) samples else null,
        )
        occupied = 0
        received = 0
        firstPacket = -1L
        lastPacket = -1L
        reasons.clear()
        windowStart = position
        samples = DoubleArray(SAMPLES_PER_EPOCH)
        return result
    }

    internal data class Window(
        val epochIndex: Long,
        val startSampleIndex: Long,
        val startedAtMillis: Long,
        val firstPacketSequence: Long,
        val lastPacketSequence: Long,
        val receivedSamples: Int,
        val quality: String,
        val reason: String,
        val eog: DoubleArray?,
    )

    companion object {
        const val RAW_RATE = 104L
        const val SAMPLES_PER_EPOCH = 3120
        private const val HALF_COUNTER_MILLIS = 32768L * 1000 / RAW_RATE
    }
}
