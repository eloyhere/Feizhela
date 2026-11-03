package com.feizhela.headband.bluetooth

import java.util.UUID

/** A BLE peripheral discovered by the client. */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int? = null,
)

enum class BleWriteType {
    WithResponse,
    WithoutResponse,
}

enum class BleConnectionState {
    Idle,
    Scanning,
    Connecting,
    Connected,
    Disconnecting,
    Closed,
}

data class BleCharacteristic(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val properties: Int,
)

sealed interface BleEvent {
    data class StateChanged(val state: BleConnectionState) : BleEvent

    data class DeviceFound(val device: BleDevice) : BleEvent

    data class ServicesDiscovered(
        val device: BleDevice,
        val characteristics: List<BleCharacteristic>,
    ) : BleEvent

    data class NotificationReceived(
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val value: ByteArray,
        val receivedAtMillis: Long,
    ) : BleEvent

    data class SubscriptionCompleted(
        val requestId: Long,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val success: Boolean,
        val status: Int? = null,
        val message: String? = null,
    ) : BleEvent

    data class ReadCompleted(
        val requestId: Long,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val value: ByteArray?,
        val status: Int? = null,
        val message: String? = null,
    ) : BleEvent

    data class WriteCompleted(
        val requestId: Long,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val success: Boolean,
        val status: Int? = null,
        val message: String? = null,
    ) : BleEvent

    data class Error(
        val operation: String,
        val message: String,
        val status: Int? = null,
        val cause: Throwable? = null,
    ) : BleEvent
}

/**
 * Transport-level BLE API. It intentionally knows nothing about a device's packet format.
 * Callback thread affinity is implementation-defined; listeners should hand off UI work.
 */
interface BluetoothClient : AutoCloseable {
    fun setListener(listener: ((BleEvent) -> Unit)?)

    fun startScan(serviceUuids: Set<UUID> = emptySet()): Result<Unit>

    fun stopScan()

    fun connect(device: BleDevice): Result<Unit>

    fun disconnect()

    /** Enables notifications for a characteristic after a successful connection. */
    fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long>

    /** Reads one characteristic value; the result arrives as a [BleEvent.ReadCompleted]. */
    fun read(serviceUuid: UUID, characteristicUuid: UUID): Result<Long>

    /** Writes one value to a characteristic; packet encoding belongs outside this API. */
    fun write(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: BleWriteType = BleWriteType.WithResponse,
    ): Result<Long>

    override fun close()
}
