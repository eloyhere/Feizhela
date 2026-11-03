package com.feizhela.headband.bluetooth

import com.feizhela.headband.bluetooth.protocol.FirmwarePacketDecoder
import com.feizhela.headband.bluetooth.protocol.Sample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwarePacketDecoderTest {
    @Test
    fun decodesExactlyTenSamplesWithUnsignedLittleEndianFields() {
        val bytes = ByteArray(FirmwarePacketDecoder.PACKET_SIZE_BYTES)
        val firstFields = intArrayOf(0xFF80, 0x00FF, 0xFF00, 0x0102, 0xABCD, 0xFFFF, 0x8000, 0x1234, 0xFEFF)
        firstFields.forEachIndexed { index, value -> putUInt16(bytes, index * 2, value) }
        for (sampleIndex in 1 until FirmwarePacketDecoder.SAMPLE_COUNT) {
            val offset = sampleIndex * FirmwarePacketDecoder.SAMPLE_SIZE_BYTES
            putUInt16(bytes, offset, sampleIndex)
        }

        val packet = FirmwarePacketDecoder.decode(bytes)

        assertEquals(10, packet.samples.size)
        assertEquals(
            Sample(0xFF80, 0x00FF, 0xFF00, 0x0102, 0xABCD, 0xFFFF, 0x8000, 0x1234, 0xFEFF),
            packet.samples[0],
        )
        assertEquals(9, packet.samples.last().counter)
        assertArrayEquals(bytes, packet.rawBytes)
    }

    @Test
    fun rejectsEveryLengthOtherThan180() {
        listOf(0, 179, 181).forEach { length ->
            assertThrows(IllegalArgumentException::class.java) {
                FirmwarePacketDecoder.decode(ByteArray(length))
            }
        }
        assertEquals(FirmwarePacketDecoder.PACKET_SIZE_BYTES, 180)
    }

    @Test
    fun retainsOpaqueAllOnesFieldsAndDefensivelyCopiesPacketData() {
        val input = ByteArray(FirmwarePacketDecoder.PACKET_SIZE_BYTES) { 0xFF.toByte() }

        val packet = FirmwarePacketDecoder.decode(input)
        input.fill(0)
        val exposedRaw = packet.rawBytes
        exposedRaw.fill(0)

        assertEquals(Sample(65535, 65535, 65535, 65535, 65535, 65535, 65535, 65535, 65535), packet.samples[0])
        assertTrue(packet.rawBytes.all { (it.toInt() and 0xFF) == 0xFF })
        assertNotSame(exposedRaw, packet.rawBytes)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (packet.samples as MutableList<Sample>).add(packet.samples[0])
        }
    }

    @Test
    fun sampleRejectsMalformedValuesInEveryField() {
        repeat(FirmwarePacketDecoder.FIELDS_PER_SAMPLE) { fieldIndex ->
            listOf(-1, 65536).forEach { invalid ->
                val fields = IntArray(FirmwarePacketDecoder.FIELDS_PER_SAMPLE)
                fields[fieldIndex] = invalid
                assertThrows(IllegalArgumentException::class.java) {
                    Sample(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7], fields[8])
                }
            }
        }
    }

    private fun putUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
