package com.feizhela.headband.bluetooth.protocol

/** Strict decoder for the firmware's fixed 10-sample BLE notification payload. */
object FirmwarePacketDecoder {
    const val SAMPLE_COUNT = 10
    const val FIELDS_PER_SAMPLE = 9
    const val BYTES_PER_FIELD = 2
    const val SAMPLE_SIZE_BYTES = FIELDS_PER_SAMPLE * BYTES_PER_FIELD
    const val PACKET_SIZE_BYTES = SAMPLE_COUNT * SAMPLE_SIZE_BYTES

    /** Decodes exactly one firmware packet; no partial or trailing bytes are accepted. */
    fun decode(bytes: ByteArray): Packet {
        require(bytes.size == PACKET_SIZE_BYTES) {
            "Expected exactly $PACKET_SIZE_BYTES bytes, got ${bytes.size}"
        }
        val samples = ArrayList<Sample>(SAMPLE_COUNT)
        var offset = 0
        repeat(SAMPLE_COUNT) {
            val fields = IntArray(FIELDS_PER_SAMPLE)
            repeat(FIELDS_PER_SAMPLE) { fieldIndex ->
                fields[fieldIndex] = (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8)
                offset += BYTES_PER_FIELD
            }
            samples += Sample(
                counter = fields[0],
                eogRaw = fields[1],
                pulseRaw = fields[2],
                accelXRaw = fields[3],
                accelYRaw = fields[4],
                accelZRaw = fields[5],
                gyroXRaw = fields[6],
                gyroYRaw = fields[7],
                gyroZRaw = fields[8],
            )
        }
        return Packet(samples, bytes)
    }
}

/** All values are unsigned 16-bit wire values represented as Int; no units are inferred. */
data class Sample(
    val counter: Int,
    val eogRaw: Int,
    val pulseRaw: Int,
    val accelXRaw: Int,
    val accelYRaw: Int,
    val accelZRaw: Int,
    val gyroXRaw: Int,
    val gyroYRaw: Int,
    val gyroZRaw: Int,
) {
    init {
        require(listOf(counter, eogRaw, pulseRaw, accelXRaw, accelYRaw, accelZRaw, gyroXRaw, gyroYRaw, gyroZRaw)
            .all { it in 0..MAX_UNSIGNED_16 }) { "Sample fields must be unsigned 16-bit values" }
    }

    companion object {
        const val MAX_UNSIGNED_16 = 0xFFFF
    }
}

/** Immutable decoded packet. Raw payload access always returns a defensive copy. */
class Packet internal constructor(samples: List<Sample>, rawBytes: ByteArray) {
    val samples: List<Sample> = java.util.Collections.unmodifiableList(ArrayList(samples))
    private val payload = rawBytes.copyOf()

    val rawBytes: ByteArray
        get() = payload.copyOf()

    init {
        require(this.samples.size == FirmwarePacketDecoder.SAMPLE_COUNT)
        require(payload.size == FirmwarePacketDecoder.PACKET_SIZE_BYTES)
    }
}
