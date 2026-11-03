package com.feizhela.headband.simulation

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Configuration for [FirmwarePacketSimulator].
 *
 * The defaults match the delivered firmware baseline from `framework.md` §3: 104 Hz sampling and
 * 180-byte notifications that carry ten 18-byte samples, i.e. about 10.4 notifications per second.
 * Every field is deterministic; the same seed always produces the same waveform.
 */
data class FirmwareSimulatorConfig(
    /** Device sample rate. Ten samples per packet means 10.4 packets per second at 104 Hz. */
    val sampleRateHz: Int = 104,
    /** Seed for the deterministic accelerometer/gyroscope noise source. */
    val seed: Long = 0x5EED_5EEDL,
    /** First 16-bit sample counter in the first generated packet; useful to exercise wrap. */
    val counterStart: Int = 0,
    /** Inject an EOG deflection every N samples (0 disables the injected pulse). */
    val eogPulseEverySamples: Int = 520,
    /** Drop every Nth generated packet (a gap in what the phone receives); 0 disables. */
    val dropEveryNPackets: Int = 0,
    /** Inject a counter discontinuity after every packet instead of every Nth packet. */
    val counterJump: Boolean = false,
    /** Skip [counterJumpSize] counter values after every Nth packet; 0 disables. */
    val counterJumpEveryNPackets: Int = 0,
    /** Size of the counter discontinuity injected by [counterJumpEveryNPackets]. */
    val counterJumpSize: Int = 23,
    /** Truncate every generated packet to 9 samples instead of every Nth packet. */
    val shortPacket: Boolean = false,
    /** Emit only 9 of the 10 samples every Nth packet (162 instead of 180 bytes); 0 disables. */
    val shortPacketEveryNPackets: Int = 0,
    /** Bit-flip one EOG word in every packet instead of every Nth packet. */
    val corruptSample: Boolean = false,
    /** Bit-flip one EOG word every Nth packet without changing the framing; 0 disables. */
    val corruptSampleEveryNPackets: Int = 0,
    /** Stop producing packets after this many milliseconds of link time; 0 never stalls. */
    val stallAfterMillis: Long = 0L,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(counterStart in 0..COUNTER_MASK) { "counterStart must be a uint16" }
        require(eogPulseEverySamples >= 0) { "eogPulseEverySamples must be >= 0" }
        require(dropEveryNPackets >= 0) { "dropEveryNPackets must be >= 0" }
        require(counterJumpEveryNPackets >= 0) { "counterJumpEveryNPackets must be >= 0" }
        require(counterJumpSize >= 0) { "counterJumpSize must be >= 0" }
        require(shortPacketEveryNPackets >= 0) { "shortPacketEveryNPackets must be >= 0" }
        require(corruptSampleEveryNPackets >= 0) { "corruptSampleEveryNPackets must be >= 0" }
        require(stallAfterMillis >= 0L) { "stallAfterMillis must be >= 0" }
    }

    private companion object {
        const val COUNTER_MASK = 0xFFFF
    }
}

/**
 * Generates the firmware baseline notification payload without any Android BLE dependency.
 *
 * Layout (little-endian `uint16`, exactly [PACKET_BYTES] = 10 × 18 bytes):
 * `counter, eog, pulseAdc, accelX, accelY, accelZ, gyroX, gyroY, gyroZ` per sample.
 *
 * The simulator only produces bytes; it never parses or interprets them. Counter continuity,
 * framing and fault injection are deliberate test controls, not protocol logic.
 */
class FirmwarePacketSimulator(
    private val config: FirmwareSimulatorConfig = FirmwareSimulatorConfig(),
) {
    private var nextCounter: Int = config.counterStart
    private var packetIndex: Int = 0
    private var sampleIndex: Long = 0L
    private var noiseState: Long = config.seed

    fun reset() {
        nextCounter = config.counterStart
        packetIndex = 0
        sampleIndex = 0L
        noiseState = config.seed
    }

    /**
     * True once the configured stall point has been reached. A stalled link stays connected but
     * produces no more notifications, which is different from a dropped packet.
     */
    fun isStalledAt(elapsedMillis: Long): Boolean =
        config.stallAfterMillis > 0L && elapsedMillis >= config.stallAfterMillis

    /**
     * Produces the next 180-byte payload, or `null` when this packet is intentionally dropped.
     * Counters advance by ten samples either way, so a dropped packet is indistinguishable from an
     * over-the-air loss to every consumer downstream.
     */
    fun nextPacket(): ByteArray? {
        val index = packetIndex++
        val payload = buildPayload()
        var emitted: ByteArray? = payload

        if (every(index, config.dropEveryNPackets)) {
            emitted = null
        } else {
            if (config.shortPacket || every(index, config.shortPacketEveryNPackets)) {
                emitted = payload.copyOf(PACKET_BYTES - SAMPLE_BYTES)
            }
            if (config.corruptSample || every(index, config.corruptSampleEveryNPackets)) {
                emitted = emitted!!.also { corruptSample(it) }
            }
        }

        if (config.counterJump || every(index, config.counterJumpEveryNPackets)) {
            nextCounter = (nextCounter + config.counterJumpSize) and COUNTER_MASK
        }
        return emitted
    }

    private fun buildPayload(): ByteArray {
        val payload = ByteArray(PACKET_BYTES)
        repeat(SAMPLES_PER_PACKET) { slot ->
            val offset = slot * SAMPLE_BYTES
            val counter = nextCounter
            nextCounter = (nextCounter + 1) and COUNTER_MASK
            val seconds = sampleIndex.toDouble() / config.sampleRateHz

            putWord(payload, offset, counter)
            putWord(payload, offset + 2, eogAt(seconds))
            putWord(payload, offset + 4, pulseAt(seconds))
            putWord(payload, offset + 6, (motion(seconds, 0.35, 240.0) + gravity() + noise(35)).toInt())
            putWord(payload, offset + 8, (motion(seconds + 1.7, 0.27, 180.0) + noise(35)).toInt())
            putWord(payload, offset + 10, (gravity() + motion(seconds + 0.9, 0.20, 160.0) + noise(35)).toInt())
            putWord(payload, offset + 12, noise(24))
            putWord(payload, offset + 14, noise(24))
            putWord(payload, offset + 16, noise(24))
            sampleIndex++
        }
        return payload
    }

    /** Low-frequency EOG plus an optionally injected, smoothly shaped deflection. */
    private fun eogAt(seconds: Double): Int {
        val baseline = 32768.0 + 1_400.0 * sin(TWO_PI * 0.16 * seconds)
        if (config.eogPulseEverySamples <= 0) return clampWord(baseline)
        val phase = (sampleIndex % config.eogPulseEverySamples).toDouble()
        val width = (config.sampleRateHz * 0.12).coerceAtLeast(1.0) * 3.0
        val injection = if (phase < width) 2_600.0 * sin(PI * phase / width) else 0.0
        return clampWord(baseline + injection)
    }

    /** Raw pulse ADC around 1 Hz; it is a waveform, not a validated heart-rate value. */
    private fun pulseAt(seconds: Double): Int {
        val wave = 6_500.0 * sin(TWO_PI * seconds) + 1_800.0 * sin(2.0 * TWO_PI * seconds)
        return clampWord(32768.0 + wave + noise(40))
    }

    private fun motion(seconds: Double, hertz: Double, amplitude: Double): Double =
        amplitude * sin(TWO_PI * hertz * seconds)

    /** One gravity vector component in raw ADC counts (about 4096 counts per g). */
    private fun gravity(): Double = 4_096.0

    private fun noise(amplitude: Int): Int {
        noiseState = noiseState * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
        val unit = ((noiseState ushr 40).toInt() and 0xFFFF) - 32_768
        return (unit.toLong() * amplitude / 32_768L).toInt()
    }

    private fun corruptSample(payload: ByteArray) {
        // Flip one 16-bit EOG word; framing and every other field stay readable for decoders.
        val offset = 4 * SAMPLE_BYTES + 2
        if (offset + 1 >= payload.size) return
        payload[offset] = (payload[offset].toInt() xor 0xFF).toByte()
        payload[offset + 1] = (payload[offset + 1].toInt() xor 0xFF).toByte()
    }

    private fun clampWord(value: Double): Int = value.roundToInt().coerceIn(0, COUNTER_MASK)

    private fun putWord(bytes: ByteArray, offset: Int, value: Int) {
        val word = value and COUNTER_MASK
        bytes[offset] = (word and 0xFF).toByte()
        bytes[offset + 1] = ((word ushr 8) and 0xFF).toByte()
    }

    private fun every(index: Int, interval: Int): Boolean =
        interval > 0 && (index + 1) % interval == 0

    companion object {
        const val SAMPLE_RATE_HZ: Int = 104
        const val SAMPLES_PER_PACKET: Int = 10
        const val SAMPLE_BYTES: Int = 18
        const val PACKET_BYTES: Int = SAMPLES_PER_PACKET * SAMPLE_BYTES
        const val COUNTER_MASK: Int = 0xFFFF
        private const val TWO_PI: Double = 6.283185307179586
    }
}
