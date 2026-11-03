package com.feizhela.headband.bluetooth

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Device-level repository, owned by the acquisition service. [rawPackets] is a live stream:
 * its storage consumer must be installed before connecting. Missing/slow consumers fail the
 * connection explicitly. Durable replay is provided by the recording repository, not this stream.
 * [events] carries control events only; notifications are delivered exclusively via [rawPackets].
 */
interface BluetoothRepository : AutoCloseable {
    val state: StateFlow<BluetoothSessionState>
    val discoveredDevices: StateFlow<List<BleDevice>>
    val discoveredCharacteristics: StateFlow<List<BleCharacteristic>>
    val rawPackets: SharedFlow<RawBlePacket>
    val events: SharedFlow<BleEvent>
    fun startScan(serviceUuids: Set<UUID> = emptySet()): Result<Unit>
    fun stopScan()
    fun connect(device: BleDevice): Result<Unit>
    fun disconnect()
    fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long>
    fun read(serviceUuid: UUID, characteristicUuid: UUID): Result<Long>
    fun write(serviceUuid: UUID, characteristicUuid: UUID, value: ByteArray,
              writeType: BleWriteType = BleWriteType.WithResponse): Result<Long>
}

data class BluetoothSessionState(
    val connection: BleConnectionState = BleConnectionState.Idle,
    val connectedDevice: BleDevice? = null,
    val servicesDiscovered: Boolean = false,
    val activeSubscriptions: Set<Pair<UUID, UUID>> = emptySet(),
    val lastError: BleEvent.Error? = null,
)

/** Owns its bytes; one subscriber cannot mutate another subscriber's raw recording. */
class RawBlePacket(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    payload: ByteArray,
    val receivedAtMillis: Long,
) {
    private val bytes = payload.copyOf()
    val payload: ByteArray get() = bytes.copyOf()
}

class DefaultBluetoothRepository(
    private val source: BluetoothDataSource,
    scope: CoroutineScope,
    eventCapacity: Int = 512,
) : BluetoothRepository {
    private val lock = Any()
    private var closed = false
    private val mutableState = MutableStateFlow(BluetoothSessionState())
    private val mutableDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    private val mutableCharacteristics = MutableStateFlow<List<BleCharacteristic>>(emptyList())
    private val mutableRawPackets = MutableSharedFlow<RawBlePacket>(extraBufferCapacity = eventCapacity.also { require(it > 0) })
    private val mutableEvents = MutableSharedFlow<BleEvent>(extraBufferCapacity = eventCapacity)
    override val state = mutableState.asStateFlow()
    override val discoveredDevices = mutableDevices.asStateFlow()
    override val discoveredCharacteristics = mutableCharacteristics.asStateFlow()
    override val rawPackets = mutableRawPackets.asSharedFlow()
    override val events = mutableEvents.asSharedFlow()

    private val collector: Job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            source.events.collect { event ->
                when (event) {
                    is BleEvent.DeviceFound -> mutableDevices.update { devices ->
                        val index = devices.indexOfFirst { it.address == event.device.address }
                        if (index >= 0) devices.toMutableList().also { it[index] = event.device }
                        else (devices + event.device).takeLast(256)
                    }
                    is BleEvent.StateChanged -> mutableState.update {
                        it.copy(connection = event.state,
                            connectedDevice = if (event.state in setOf(BleConnectionState.Idle, BleConnectionState.Closed)) null else it.connectedDevice,
                            servicesDiscovered = it.servicesDiscovered && event.state == BleConnectionState.Connected,
                            activeSubscriptions = if (event.state == BleConnectionState.Connected) it.activeSubscriptions else emptySet())
                    }
                    is BleEvent.ServicesDiscovered -> {
                        mutableCharacteristics.value = event.characteristics
                        mutableState.update { it.copy(connectedDevice = event.device, servicesDiscovered = true) }
                    }
                    is BleEvent.SubscriptionCompleted -> mutableState.update {
                        if (event.success) it.copy(activeSubscriptions = it.activeSubscriptions + (event.serviceUuid to event.characteristicUuid))
                        else it.copy(lastError = BleEvent.Error("subscribe", event.message ?: "Subscription failed", event.status))
                    }
                    is BleEvent.Error -> mutableState.update { it.copy(lastError = event) }
                    is BleEvent.NotificationReceived -> {
                        check(mutableRawPackets.subscriptionCount.value > 0) { "Raw packet consumer is missing" }
                        if (!mutableRawPackets.tryEmit(RawBlePacket(event.serviceUuid, event.characteristicUuid, event.value, event.receivedAtMillis))) {
                            throw BleBufferOverflowException()
                        }
                    }
                    else -> Unit
                }
                if (event !is BleEvent.NotificationReceived && !mutableEvents.tryEmit(event)) throw BleBufferOverflowException()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = BleEvent.Error("eventStream", error.message ?: "BLE stream failed", cause = error)
            mutableState.update { it.copy(lastError = failure) }
            mutableEvents.tryEmit(failure)
        } finally {
            close()
        }
    }

    init {
        collector.invokeOnCompletion { close() }
        collector.start()
    }

    override fun startScan(serviceUuids: Set<UUID>) = withOpen { source.startScan(serviceUuids) }
    override fun stopScan() = synchronized(lock) { if (!closed) source.stopScan() }
    override fun connect(device: BleDevice): Result<Unit> = withOpen {
        // Publish the accepted command immediately. The client's own Connecting/Connected events
        // arrive asynchronously through the event channel, so without this optimistic transition a
        // caller waiting on [state] could observe the pre-connect Idle state and conclude that the
        // link died before service discovery even started.
        mutableState.update {
            it.copy(
                connection = BleConnectionState.Connecting,
                connectedDevice = device,
                servicesDiscovered = false,
                lastError = null,
            )
        }
        source.connect(device).onFailure { error ->
            mutableState.update { state ->
                state.copy(
                    connection = BleConnectionState.Idle,
                    connectedDevice = null,
                    lastError = BleEvent.Error(
                        operation = "connect",
                        message = error.message ?: error::class.java.simpleName,
                        cause = error,
                    ),
                )
            }
        }
    }
    override fun disconnect() = synchronized(lock) { if (!closed) source.disconnect() }
    override fun subscribe(serviceUuid: UUID, characteristicUuid: UUID) = withOpen { source.subscribe(serviceUuid, characteristicUuid) }
    override fun read(serviceUuid: UUID, characteristicUuid: UUID) = withOpen { source.read(serviceUuid, characteristicUuid) }
    override fun write(serviceUuid: UUID, characteristicUuid: UUID, value: ByteArray, writeType: BleWriteType) =
        withOpen { source.write(serviceUuid, characteristicUuid, value.copyOf(), writeType) }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try { source.close() } finally {
                mutableState.update { it.copy(connection = BleConnectionState.Closed, connectedDevice = null,
                    servicesDiscovered = false, activeSubscriptions = emptySet()) }
                mutableCharacteristics.value = emptyList()
                collector.cancel()
            }
        }
    }

    private fun <T> withOpen(block: () -> Result<T>): Result<T> = synchronized(lock) {
        if (closed) Result.failure(IllegalStateException("Bluetooth repository is closed")) else block()
    }
}
