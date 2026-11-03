package com.feizhela.headband.recording

import com.feizhela.headband.storage.RawPacketEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalEpochAssemblerTest {
    @Test
    fun counterWrap65535ToZeroProducesACompleteReadyEpoch() = runBlocking {
        val assembler = SignalEpochAssembler()
        val windows = assemble(assembler, counters(65_000, SignalEpochAssembler.SAMPLES_PER_EPOCH))

        assertEquals(1, windows.size)
        val epoch = windows.single()
        assertEquals("READY", epoch.quality)
        assertEquals(SignalEpochAssembler.SAMPLES_PER_EPOCH, epoch.receivedSamples)
        assertEquals(SignalEpochAssembler.SAMPLES_PER_EPOCH, epoch.eog!!.size)
        assertEquals(0L, epoch.startSampleIndex)
        assertFalse(epoch.reason.contains("MISSING_DEVICE_SAMPLES"))
        assertFalse(epoch.reason.contains("COUNTER_RESET_OR_BACKWARD"))
    }

    @Test
    fun duplicateCounterMarksWindowRejectedWithoutInventingASample() = runBlocking {
        val assembler = SignalEpochAssembler()
        val input = counters(100, SignalEpochAssembler.SAMPLES_PER_EPOCH + 10).toMutableList()
        input[11] = input[10]
        val windows = assemble(assembler, input)

        val epoch = windows.first()
        assertEquals("REJECTED", epoch.quality)
        assertEquals(SignalEpochAssembler.SAMPLES_PER_EPOCH - 1, epoch.receivedSamples)
        assertTrue(epoch.reason.contains("DUPLICATE_COUNTER"))
        assertNull(epoch.eog)
    }

    @Test
    fun backwardCounterStartsFreshSegmentAtNextEpochBoundary() = runBlocking {
        val assembler = SignalEpochAssembler()
        val first = counters(1_000, 10)
        val reset = counters(0, SignalEpochAssembler.SAMPLES_PER_EPOCH)
        val windows = assemble(assembler, first + reset)

        assertEquals(2, windows.size)
        assertEquals("INCOMPLETE", windows[0].quality)
        assertEquals(10, windows[0].receivedSamples)
        assertTrue(windows[0].reason.contains("COUNTER_RESET_OR_BACKWARD"))
        assertEquals(0L, windows[0].startSampleIndex)
        assertEquals("REJECTED", windows[1].quality)
        assertEquals(SignalEpochAssembler.SAMPLES_PER_EPOCH.toLong(), windows[1].startSampleIndex)
        assertEquals(SignalEpochAssembler.SAMPLES_PER_EPOCH, windows[1].receivedSamples)
        assertTrue(windows[1].reason.contains("SEGMENT_AFTER_COUNTER_RESET"))
    }

    @Test
    fun malformedPacketRejectsTheAffectedEpoch() = runBlocking {
        val assembler = SignalEpochAssembler()
        val packets = packets(counters(500, 100))
        val malformed = RawPacketEntity("session", 10, RECEIVE_STEP * 10, byteArrayOf(1, 2, 3))
        val rest = packets(counters(600, SignalEpochAssembler.SAMPLES_PER_EPOCH - 100), 11)
        val windows = mutableListOf<SignalEpochAssembler.Window>()
        (packets.take(10) + malformed + rest).forEach { packet ->
            assembler.accept(packet) { windows += it }
        }
        assembler.finishSession { windows += it }

        val epoch = windows.single()
        assertEquals("REJECTED", epoch.quality)
        assertEquals(SignalEpochAssembler.SAMPLES_PER_EPOCH, epoch.receivedSamples)
        assertTrue(epoch.reason.contains("MALFORMED_PACKET"))
        assertNull(epoch.eog)
    }

    @Test
    fun largeForwardGapEmitsEveryMissingOnlyBucketAsRejected() = runBlocking {
        val assembler = SignalEpochAssembler()
        val initial = counters(0, 10)
        val afterGap = counters(10_000, 10)
        val windows = assemble(assembler, initial + afterGap)

        assertEquals(4, windows.size)
        assertEquals("REJECTED", windows[0].quality)
        assertEquals(10, windows[0].receivedSamples)
        assertTrue(windows[0].reason.contains("MISSING_DEVICE_SAMPLES"))
        windows.subList(1, 3).forEachIndexed { index, epoch ->
            assertEquals((index + 1).toLong(), epoch.epochIndex)
            assertEquals((index + 1) * SignalEpochAssembler.SAMPLES_PER_EPOCH.toLong(), epoch.startSampleIndex)
            assertEquals("REJECTED", epoch.quality)
            assertEquals(0, epoch.receivedSamples)
            assertTrue(epoch.reason.contains("MISSING_DEVICE_SAMPLES"))
            assertNull(epoch.eog)
        }
        assertEquals("INCOMPLETE", windows.last().quality)
        assertEquals(10, windows.last().receivedSamples)
    }

    @Test
    fun forwardJumpCanBeginPartwayThroughAnEpochAndNextWindowStartsOnBoundary() = runBlocking {
        val assembler = SignalEpochAssembler()
        val sampleCounters = counters(0, 1_000) + counters(1_005, 2_120)
        val windows = assemble(assembler, sampleCounters)

        assertEquals(2, windows.size)
        val first = windows.first()
        assertEquals("REJECTED", first.quality)
        assertEquals(3_115, first.receivedSamples)
        assertEquals(0L, first.startSampleIndex)
        assertTrue(first.reason.contains("MISSING_DEVICE_SAMPLES"))
        assertNull(first.eog)
        assertEquals("INCOMPLETE", windows.last().quality)
        assertEquals(5, windows.last().receivedSamples)
        assertEquals(SignalEpochAssembler.SAMPLES_PER_EPOCH.toLong(), windows.last().startSampleIndex)
    }

    @Test
    fun finishSessionEmitsIncompleteTailWithReceivedSampleCount() = runBlocking {
        val assembler = SignalEpochAssembler()
        val windows = assemble(assembler, counters(7, 40))

        val tail = windows.single()
        assertEquals("INCOMPLETE", tail.quality)
        assertEquals(40, tail.receivedSamples)
        assertEquals(0L, tail.startSampleIndex)
        assertTrue(tail.reason.contains("PARTIAL_WINDOW"))
        assertNull(tail.eog)
    }

    @Test
    fun ambiguousReceiveTimesRejectSegmentsWithoutFabricatingSamples() = runBlocking {
        for (delta in listOf(-1L, 32768L * 1000 / 104)) {
            val assembler = SignalEpochAssembler()
            val input = packets(counters(10, 20)).toMutableList()
            input[1] = input[1].copy(receivedAtMillis = input[0].receivedAtMillis + delta)
            val windows = mutableListOf<SignalEpochAssembler.Window>()
            input.forEach { assembler.accept(it) { row -> windows += row } }
            assembler.finishSession { windows += it }
            assertEquals(2, windows.size)
            assertEquals(listOf(10, 10), windows.map { it.receivedSamples })
            assertTrue(windows[0].reason.contains("AMBIGUOUS_RECEIVE_TIME"))
            assertTrue(windows[1].reason.contains("SEGMENT_AFTER_AMBIGUOUS_TIME"))
            assertEquals(input[1].receivedAtMillis - 9L * 1000 / 104, windows[1].startedAtMillis)
            assertTrue(windows.all { it.eog == null })
        }
    }

    @Test
    fun malformedOnlyArchiveRetainsReceiveTimeAndZeroSampleEvidence() = runBlocking {
        val assembler = SignalEpochAssembler()
        val windows = mutableListOf<SignalEpochAssembler.Window>()
        assembler.accept(RawPacketEntity("session", 0, 42_000, byteArrayOf(1))) { windows += it }
        assembler.finishSession { windows += it }
        assertEquals(42_000L, windows.single().startedAtMillis)
        assertEquals(0, windows.single().receivedSamples)
        assertTrue(windows.single().reason.contains("MALFORMED_PACKET"))
        assertNull(windows.single().eog)
    }

    private suspend fun assemble(
        assembler: SignalEpochAssembler,
        counters: List<Int>,
    ): List<SignalEpochAssembler.Window> {
        val windows = mutableListOf<SignalEpochAssembler.Window>()
        packets(counters).forEach { packet -> assembler.accept(packet) { windows += it } }
        assembler.finishSession { windows += it }
        return windows
    }

    private fun packets(
        counters: List<Int>,
        firstSequence: Long = 0,
    ): List<RawPacketEntity> = counters.chunked(SAMPLES_PER_PACKET).mapIndexed { packetIndex, values ->
        require(values.size == SAMPLES_PER_PACKET) { "test payloads must contain exactly ten samples" }
        RawPacketEntity(
            sessionId = "session",
            sequence = firstSequence + packetIndex,
            receivedAtMillis = 1_000L + (firstSequence + packetIndex) * RECEIVE_STEP,
            payload = payload(values),
        )
    }

    private fun payload(counters: List<Int>): ByteArray = ByteArray(PACKET_BYTES).also { bytes ->
        counters.forEachIndexed { index, counter ->
            val offset = index * SAMPLE_BYTES
            putWord(bytes, offset, counter and 0xFFFF)
            putWord(bytes, offset + 2, 20_000 + ((index * 37) % 20_000))
            for (field in 2 until 9) putWord(bytes, offset + field * 2, 1_000 + field)
        }
    }

    private fun counters(first: Int, count: Int): List<Int> =
        List(count) { index -> (first + index) and 0xFFFF }

    private fun putWord(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private companion object {
        const val SAMPLES_PER_PACKET = 10
        const val SAMPLE_BYTES = 18
        const val PACKET_BYTES = SAMPLES_PER_PACKET * SAMPLE_BYTES
        const val RECEIVE_STEP = 96L
    }
}
