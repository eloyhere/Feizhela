package com.feizhela.headband.bluetooth

import com.feizhela.headband.bluetooth.protocol.FirmwarePacketDecoder
import com.feizhela.headband.bluetooth.protocol.SampleCounterTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SampleCounterTrackerTest {
    @Test
    fun acceptsAnyFirstCounterAndRecognizesContinuousAndWrap() {
        val tracker = SampleCounterTracker()
        assertEquals(SampleCounterTracker.Result.First(65534), tracker.observe(65534))
        assertEquals(SampleCounterTracker.Result.Continuous(65535, 65534), tracker.observe(65535))
        assertEquals(SampleCounterTracker.Result.Wrap(0, 65535), tracker.observe(0))
        assertEquals(SampleCounterTracker.Result.Continuous(1, 0), tracker.observe(1))
    }

    @Test
    fun reportsForwardMissingCountsIncludingAcrossWrap() {
        val tracker = SampleCounterTracker()
        tracker.observe(100)
        assertEquals(SampleCounterTracker.Result.Missing(104, 100, 3), tracker.observe(104))
        tracker.reset()
        tracker.observe(65534)
        assertEquals(SampleCounterTracker.Result.Missing(2, 65534, 3), tracker.observe(2))
        assertEquals(SampleCounterTracker.Result.Continuous(3, 2), tracker.observe(3))
    }

    @Test
    fun duplicateDoesNotAdvanceCounterBaseline() {
        val tracker = SampleCounterTracker()
        tracker.observe(17)
        assertEquals(SampleCounterTracker.Result.Duplicate(17, 17), tracker.observe(17))
        assertEquals(SampleCounterTracker.Result.Continuous(18, 17), tracker.observe(18))
    }

    @Test
    fun backwardOrPossibleDeviceResetRebasesWithoutInventingDrops() {
        val tracker = SampleCounterTracker()
        tracker.observe(1000)
        assertEquals(
            SampleCounterTracker.Result.BackwardOrAmbiguousReset(0, 1000, 64536),
            tracker.observe(0),
        )
        assertEquals(SampleCounterTracker.Result.Continuous(1, 0), tracker.observe(1))
        assertEquals(
            SampleCounterTracker.Result.BackwardOrAmbiguousReset(0, 1, 65535),
            tracker.observe(0),
        )
    }

    @Test
    fun halfRangeBoundaryHasExplicitDeterministicPolicy() {
        val tracker = SampleCounterTracker()
        tracker.observe(0)
        assertEquals(SampleCounterTracker.Result.Missing(32767, 0, 32766), tracker.observe(32767))
        tracker.reset()
        tracker.observe(0)
        assertEquals(
            SampleCounterTracker.Result.BackwardOrAmbiguousReset(32768, 0, 32768),
            tracker.observe(32768),
        )
    }

    @Test
    fun newSessionResetAndIndependentTrackersStartFresh() {
        val tracker = SampleCounterTracker()
        tracker.observe(200)
        tracker.reset()
        assertEquals(SampleCounterTracker.Result.First(5), tracker.observe(5))
        assertEquals(SampleCounterTracker.Result.First(5), SampleCounterTracker().observe(5))
        assertEquals(SampleCounterTracker.Result.Continuous(6, 5), tracker.observe(6))
    }

    @Test
    fun invalidCounterCannotCorruptPreviousBaseline() {
        val tracker = SampleCounterTracker()
        tracker.observe(7)
        listOf(-1, 65536).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { tracker.observe(value) }
        }
        assertEquals(SampleCounterTracker.Result.Continuous(8, 7), tracker.observe(8))
    }

    @Test
    fun tracksEverySampleAcrossPacketBoundariesWithoutPacketIndexAssumptions() {
        val tracker = SampleCounterTracker()
        val results = listOf(counterPacket(65530), counterPacket(4)).flatMap { bytes ->
            FirmwarePacketDecoder.decode(bytes).samples.map { tracker.observe(it.counter) }
        }

        assertEquals(20, results.size)
        assertEquals(SampleCounterTracker.Result.First(65530), results.first())
        assertEquals(SampleCounterTracker.Result.Wrap(0, 65535), results[6])
        assertEquals(SampleCounterTracker.Result.Continuous(4, 3), results[10])
        assertEquals(SampleCounterTracker.Result.Continuous(13, 12), results.last())
    }

    private fun counterPacket(firstCounter: Int): ByteArray =
        ByteArray(FirmwarePacketDecoder.PACKET_SIZE_BYTES).also { bytes ->
            repeat(FirmwarePacketDecoder.SAMPLE_COUNT) { index ->
                val counter = (firstCounter + index) and 0xFFFF
                val offset = index * FirmwarePacketDecoder.SAMPLE_SIZE_BYTES
                bytes[offset] = (counter and 0xFF).toByte()
                bytes[offset + 1] = (counter ushr 8).toByte()
            }
        }
}
