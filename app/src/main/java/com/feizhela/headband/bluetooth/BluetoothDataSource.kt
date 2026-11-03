package com.feizhela.headband.bluetooth

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Callback-to-stream adapter. Exactly one long-lived owner must collect [events]. */
interface BluetoothDataSource : AutoCloseable {
    val events: Flow<BleEvent>
    fun startScan(serviceUuids: Set<UUID> = emptySet()): Result<Unit>
    fun stopScan()
    fun connect(device: BleDevice): Result<Unit>
    fun disconnect()
    fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long>
    fun read(serviceUuid: UUID, characteristicUuid: UUID): Result<Long>
    fun write(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: BleWriteType = BleWriteType.WithResponse,
    ): Result<Long>
}

class BleBufferOverflowException : IllegalStateException("BLE event buffer exhausted; acquisition interrupted")

/**
 * Uses a bounded channel deliberately. A full channel is a data-integrity failure, so this source
 * closes itself and the underlying transport instead of dropping raw packets silently.
 */
class FlowBluetoothDataSource(
    private val client: BluetoothClient,
    capacity: Int = 512,
) : BluetoothDataSource {
    private val closed = AtomicBoolean(false)
    private val channel = Channel<BleEvent>(capacity.also { require(it > 0) })

    init {
        client.setListener { event ->
            if (closed.get()) return@setListener
            val snapshot = when (event) {
                is BleEvent.NotificationReceived -> event.copy(value = event.value.copyOf())
                is BleEvent.ReadCompleted -> event.copy(value = event.value?.copyOf())
                else -> event
            }
            if (channel.trySend(snapshot).isFailure && closed.compareAndSet(false, true)) {
                channel.close(BleBufferOverflowException())
                client.close()
            }
        }
    }

    private val collected = AtomicBoolean(false)
    override val events: Flow<BleEvent> = flow {
        check(collected.compareAndSet(false, true)) { "BLE events allow exactly one collector" }
        try {
            for (event in channel) emit(event)
        } finally {
            close()
        }
    }

    override fun startScan(serviceUuids: Set<UUID>): Result<Unit> = ensureOpen { client.startScan(serviceUuids) }
    override fun stopScan() { if (!closed.get()) client.stopScan() }
    override fun connect(device: BleDevice): Result<Unit> = ensureOpen { client.connect(device) }
    override fun disconnect() { if (!closed.get()) client.disconnect() }
    override fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> =
        ensureOpen { client.subscribe(serviceUuid, characteristicUuid) }
    override fun read(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> =
        ensureOpen { client.read(serviceUuid, characteristicUuid) }
    override fun write(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: BleWriteType,
    ): Result<Long> = ensureOpen { client.write(serviceUuid, characteristicUuid, value.copyOf(), writeType) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.setListener(null)
        client.close()
        channel.close()
    }

    private fun <T> ensureOpen(block: () -> Result<T>): Result<T> =
        if (closed.get()) Result.failure(IllegalStateException("Bluetooth data source is closed")) else block()
}
