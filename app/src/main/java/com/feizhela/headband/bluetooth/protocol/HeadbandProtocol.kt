package com.feizhela.headband.bluetooth.protocol

import java.util.UUID

/**
 * Fixed GATT contract and wire format of the Feizhela headband firmware.
 *
 * These UUIDs are taken from the delivered firmware/app pair (`bluetooth_service.dart` in
 * 菲哲拉-1.1.0-交付) and are the only identifiers the acquisition pipeline may use. The data
 * characteristic carries 180-byte notifications: ten 18-byte samples of nine little-endian
 * uint16 fields (counter, EOG raw, pulse raw, accel x/y/z raw, gyro x/y/z raw) at the firmware's
 * nominal 104 Hz. Values are raw ADC/IMU counts; this object never converts them to physical
 * units. Motor/power characteristics exist in the firmware but the app keeps automated feedback
 * disabled, so only the identifiers are recorded here.
 */
object HeadbandProtocol {
    /** Firmware protocol schema recorded with every new session. */
    const val VERSION = "feizhela-ble-18B-v1"

    val SERVICE_UUID: UUID = UUID.fromString("5aee1a8a-08de-11ed-861d-0242ac120002")
    val DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("405992d6-0cf2-11ed-861d-0242ac120002")

    /** Present in firmware; reserved for the manual motor test. Never written by acquisition. */
    val MOTOR_CHARACTERISTIC_UUID: UUID = UUID.fromString("1aa00c0d-469a-426b-985c-8299084aed72")

    /** Present in firmware; reserved for power control. Never written by acquisition. */
    val POWER_CHARACTERISTIC_UUID: UUID = UUID.fromString("018ec2b5-7c82-7773-95e2-a5f374275f0b")

    /** Nominal firmware sampling rate; used for expected-sample and coverage maths. */
    const val SAMPLE_RATE_HZ = 104

    const val SAMPLES_PER_PACKET = 10
    const val BYTES_PER_SAMPLE = 18
    const val PACKET_SIZE_BYTES = SAMPLES_PER_PACKET * BYTES_PER_SAMPLE

    /** One 30 s observation epoch, as framed by the analysis pipeline. */
    const val EPOCH_SECONDS = 30
    const val SAMPLES_PER_EPOCH = SAMPLE_RATE_HZ * EPOCH_SECONDS

    /** Packets per second at the nominal rate (10.4 packets/s). */
    const val PACKETS_PER_SECOND = SAMPLE_RATE_HZ.toDouble() / SAMPLES_PER_PACKET
}
