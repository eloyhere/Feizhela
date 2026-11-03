package com.feizhela.headband.simulation

import com.feizhela.headband.bluetooth.BleCharacteristic
import com.feizhela.headband.bluetooth.BleConnectionState
import com.feizhela.headband.bluetooth.BleDevice
import com.feizhela.headband.bluetooth.BleEvent
import com.feizhela.headband.bluetooth.BleWriteType
import com.feizhela.headband.bluetooth.BluetoothClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Address/name of the fake peripheral, chosen to match the documented simulation scenario. */
const val SIMULATED_DEVICE_ADDRESS: String = "AA:BB:CC:DD:EE:01"
const val SIMULATED_DEVICE_NAME: String = "Feizhela-Sim"

/** Service/characteristic used by the simulated firmware; NOTIFY is advertised below. */
val SIMULATED_SERVICE_UUID: UUID = UUID.fromString("6ba1c0de-0000-4000-8000-00000000aa00")
val SIMULATED_CHARACTERISTIC_UUID: UUID = UUID.fromString("6ba1c0de-0000-4000-8000-00000000aa01")

/**
 * Deterministic transport configuration. Delays are wall-clock milliseconds so the simulator
 * follows the same event ordering as [com.feizhela.headband.bluetooth.AndroidBluetoothClient].
 */
data class SimulatedTransportConfig(
    val firmware: FirmwareSimulatorConfig = FirmwareSimulatorConfig(),
    val deviceAddress: String = SIMULATED_DEVICE_ADDRESS,
    val deviceName: String = SIMULATED_DEVICE_NAME,
    val rssi: Int = -42,
    val scanResultDelayMillis: Long = 80L,
    val connectDelayMillis: Long = 200L,
    val serviceDiscoveryDelayMillis: Long = 120L,
    val subscriptionDelayMillis: Long = 120L,
    /** Report the CCCD write as failed and never start the notification stream. */
    val subscriptionFailure: Boolean = false,
    val disconnectDelayMillis: Long = 40L,
    val readDelayMillis: Long = 30L,
    val writeDelayMillis: Long = 30L,
    /** Emit a link-loss error and drop to Idle after this long; 0 keeps the link up. */
    val forceDisconnectAfterMillis: Long = 0L,
    /**
     * Cadence of 180-byte notifications. The firmware samples at 104 Hz and packs ten samples per
     * notification, so 96 ms per packet delivers 104 samples per second (≈10.4 packets/s).
     */
    val packetIntervalMillis: Long = 96L,
) {
    init {
        require(deviceAddress.isNotBlank()) { "deviceAddress must not be blank" }
        require(rssi <= 0) { "rssi must be a non-positive dBm value" }
        require(scanResultDelayMillis >= 0L) { "scanResultDelayMillis must be >= 0" }
        require(connectDelayMillis >= 0L) { "connectDelayMillis must be >= 0" }
        require(serviceDiscoveryDelayMillis >= 0L) { "serviceDiscoveryDelayMillis must be >= 0" }
        require(subscriptionDelayMillis >= 0L) { "subscriptionDelayMillis must be >= 0" }
        require(disconnectDelayMillis >= 0L) { "disconnectDelayMillis must be >= 0" }
        require(readDelayMillis >= 0L) { "readDelayMillis must be >= 0" }
        require(writeDelayMillis >= 0L) { "writeDelayMillis must be >= 0" }
        require(forceDisconnectAfterMillis >= 0L) { "forceDisconnectAfterMillis must be >= 0" }
        require(packetIntervalMillis > 0L) { "packetIntervalMillis must be > 0" }
    }
}

/**
 * A BLE transport that follows [BluetoothClient] semantics without touching Android BLE.
 *
 * Event order mirrors the real client: scan → Connecting → Connected → ServicesDiscovered →
 * SubscriptionCompleted → NotificationReceived. Request ids increase from 1 per client instance,
 * exactly like `AndroidBluetoothClient`. Every asynchronous completion is emitted from a coroutine,
 * never from the caller's stack, so consumers cannot accidentally depend on callback affinity.
 *
 * The implementation supports one active notification subscription, which is all the acquisition
 * pipeline uses. It is debug-only and has no permissions to grant.
 */
class SimulatedBluetoothClient(
    private val config: SimulatedTransportConfig = SimulatedTransportConfig(),
) : BluetoothClient {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextRequestId = AtomicLong(1)
    private val emittedNotifications = AtomicInteger(0)

    @Volatile
    private var listener: ((BleEvent) -> Unit)? = null

    @Volatile
    private var state = BleConnectionState.Idle

    @Volatile
    private var closed = false

    /** Invalidates every coroutine belonging to an older connection attempt. */
    private var generation = 0L
    private var connectedDevice: BleDevice? = null
    private var servicesDiscovered = false

    @Volatile
    private var enabledSubscription: Pair<UUID, UUID>? = null

    @Volatile
    private var lastPayload: ByteArray? = null

    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var subscriptionJob: Job? = null
    private var notificationJob: Job? = null
    private var disconnectJob: Job? = null
    private var notificationActive = false
    private val pendingSubscriptions = mutableListOf<PendingRequest>()
    private val pendingReads = mutableListOf<PendingRequest>()
    private val pendingWrites = mutableListOf<PendingRequest>()

    val device: BleDevice = BleDevice(config.deviceAddress, config.deviceName, config.rssi)

    /** Number of notifications actually handed to the listener; dropped packets are excluded. */
    val notificationCount: Int get() = emittedNotifications.get()

    val isClosed: Boolean get() = closed

    override fun setListener(listener: ((BleEvent) -> Unit)?) {
        this.listener = listener
    }

    override fun startScan(serviceUuids: Set<UUID>): Result<Unit> = runCatching {
        ensureOpen()
        check(state == BleConnectionState.Idle) { "BLE client must be idle before scanning" }
        changeState(BleConnectionState.Scanning)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(config.scanResultDelayMillis)
            if (state == BleConnectionState.Scanning) emit(BleEvent.DeviceFound(device))
        }
        synchronized(lock) { scanJob = job }
        job.start()
        Unit
    }.onFailure { emitError("scan", it) }

    override fun stopScan() {
        val job = synchronized(lock) { scanJob.also { scanJob = null } }
        job?.cancel()
        if (state == BleConnectionState.Scanning) changeState(BleConnectionState.Idle)
    }

    override fun connect(device: BleDevice): Result<Unit> = runCatching {
        ensureOpen()
        require(device.address.isNotBlank()) { "Device address is empty" }
        stopScan()
        failPendingSubscriptions("Simulated connection was replaced")
        failPendingRequests("Simulated connection was replaced")

        val expectedGeneration = synchronized(lock) {
            check(!closed) { "Bluetooth client is closed" }
            cancelConnectionWorkLocked()
            generation += 1
            connectedDevice = device
            servicesDiscovered = false
            enabledSubscription = null
            lastPayload = null
            generation
        }
        changeState(BleConnectionState.Connecting)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            establishConnection(device, expectedGeneration)
        }
        synchronized(lock) { connectionJob = job }
        job.start()
        Unit
    }.onFailure {
        synchronized(lock) { connectedDevice = null }
        failPendingSubscriptions("Simulated connection could not be established")
        changeState(BleConnectionState.Idle)
        emitError("connect", it)
    }

    override fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> = runCatching {
        ensureOpen()
        check(state == BleConnectionState.Connecting || state == BleConnectionState.Connected) {
            "BLE device is not connecting or connected"
        }
        val requestId = nextRequestId.getAndIncrement()
        val key = serviceUuid to characteristicUuid
        val alreadyEnabled = synchronized(lock) {
            check(!closed) { "Bluetooth client is closed" }
            if (enabledSubscription == key) {
                true
            } else {
                pendingSubscriptions += PendingRequest(requestId, serviceUuid, characteristicUuid)
                false
            }
        }
        if (alreadyEnabled) {
            scope.launch {
                emit(BleEvent.SubscriptionCompleted(requestId, serviceUuid, characteristicUuid, success = true))
            }
        } else {
            scheduleSubscriptionCompletion(requestId, serviceUuid, characteristicUuid)
        }
        requestId
    }.onFailure { emitError("subscribe", it) }

    override fun read(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> = runCatching {
        ensureOpen()
        ensureGattReady()
        val requestId = nextRequestId.getAndIncrement()
        val expectedGeneration = synchronized(lock) {
            pendingReads += PendingRequest(requestId, serviceUuid, characteristicUuid)
            generation
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(config.readDelayMillis)
            val value = synchronized(lock) {
                pendingReads.removeAll { it.requestId == requestId }
                val stillCurrent = !closed && generation == expectedGeneration && state == BleConnectionState.Connected
                if (stillCurrent) lastPayload?.copyOf() ?: ByteArray(0) else null
            }
            if (value != null) {
                emit(BleEvent.ReadCompleted(requestId, serviceUuid, characteristicUuid, value = value))
            } else {
                emit(
                    BleEvent.ReadCompleted(
                        requestId,
                        serviceUuid,
                        characteristicUuid,
                        value = null,
                        message = "Simulated read failed: connection is no longer ready",
                    ),
                )
            }
        }
        job.start()
        requestId
    }.onFailure { emitError("read", it) }

    override fun write(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: BleWriteType,
    ): Result<Long> = runCatching {
        ensureOpen()
        ensureGattReady()
        val requestId = nextRequestId.getAndIncrement()
        val expectedGeneration = synchronized(lock) {
            pendingWrites += PendingRequest(requestId, serviceUuid, characteristicUuid)
            generation
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(config.writeDelayMillis)
            val accepted = synchronized(lock) {
                pendingWrites.removeAll { it.requestId == requestId }
                !closed && generation == expectedGeneration && state == BleConnectionState.Connected
            }
            emit(
                BleEvent.WriteCompleted(
                    requestId = requestId,
                    serviceUuid = serviceUuid,
                    characteristicUuid = characteristicUuid,
                    success = accepted,
                    message = if (accepted) null else "Simulated write failed: connection is no longer ready",
                ),
            )
        }
        job.start()
        requestId
    }.onFailure { emitError("write", it) }

    override fun disconnect() {
        val shouldDisconnect = synchronized(lock) {
            if (closed || state == BleConnectionState.Idle || state == BleConnectionState.Closed ||
                state == BleConnectionState.Disconnecting
            ) {
                false
            } else {
                generation += 1
                notificationActive = false
                notificationJob?.cancel()
                notificationJob = null
                subscriptionJob?.cancel()
                subscriptionJob = null
                connectionJob?.cancel()
                connectionJob = null
                disconnectJob?.cancel()
                disconnectJob = null
                true
            }
        }
        if (!shouldDisconnect) return

        failPendingSubscriptions("Simulated connection closed before subscription completed")
        failPendingRequests("Simulated connection closed before the operation completed")
        changeState(BleConnectionState.Disconnecting)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(config.disconnectDelayMillis)
            synchronized(lock) {
                connectedDevice = null
                servicesDiscovered = false
                enabledSubscription = null
            }
            if (!closed) changeState(BleConnectionState.Idle)
        }
        synchronized(lock) { disconnectJob = job }
        job.start()
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                generation += 1
                scanJob?.cancel()
                connectionJob?.cancel()
                subscriptionJob?.cancel()
                notificationJob?.cancel()
                disconnectJob?.cancel()
                scanJob = null
                connectionJob = null
                subscriptionJob = null
                notificationJob = null
                disconnectJob = null
                notificationActive = false
                connectedDevice = null
                servicesDiscovered = false
                enabledSubscription = null
                true
            }
        }
        if (!shouldClose) return

        failPendingSubscriptions("Simulated client closed before subscription completed")
        failPendingRequests("Simulated client closed before the operation completed")
        changeState(BleConnectionState.Closed)
        listener = null
        scope.cancel()
    }

    private suspend fun establishConnection(device: BleDevice, expectedGeneration: Long) {
        delay(config.connectDelayMillis)
        if (!isCurrentConnection(expectedGeneration)) return
        changeState(BleConnectionState.Connected)
        delay(config.serviceDiscoveryDelayMillis)
        if (!isCurrentConnection(expectedGeneration) || state != BleConnectionState.Connected) return
        synchronized(lock) {
            if (closed || generation != expectedGeneration) return
            servicesDiscovered = true
        }
        emit(
            BleEvent.ServicesDiscovered(
                device = device,
                characteristics = listOf(
                    BleCharacteristic(
                        serviceUuid = SIMULATED_SERVICE_UUID,
                        characteristicUuid = SIMULATED_CHARACTERISTIC_UUID,
                        properties = NOTIFY_PROPERTIES,
                    ),
                ),
            ),
        )
        startNotificationPumpIfReady()
        scheduleForcedDisconnect(expectedGeneration)
    }

    private fun scheduleSubscriptionCompletion(
        requestId: Long,
        serviceUuid: UUID,
        characteristicUuid: UUID,
    ) {
        val expectedGeneration = synchronized(lock) { generation }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(config.subscriptionDelayMillis)
            val outcome = synchronized(lock) {
                val pending = pendingSubscriptions.removeAll { it.requestId == requestId }
                when {
                    !pending || closed || generation != expectedGeneration -> SubscriptionOutcome.CLOSED
                    state != BleConnectionState.Connected && state != BleConnectionState.Connecting ->
                        SubscriptionOutcome.CLOSED
                    config.subscriptionFailure -> SubscriptionOutcome.FAILED
                    else -> {
                        enabledSubscription = serviceUuid to characteristicUuid
                        SubscriptionOutcome.ENABLED
                    }
                }
            }
            when (outcome) {
                SubscriptionOutcome.ENABLED -> {
                    emit(BleEvent.SubscriptionCompleted(requestId, serviceUuid, characteristicUuid, success = true))
                    startNotificationPumpIfReady()
                }
                SubscriptionOutcome.FAILED -> {
                    emit(
                        BleEvent.SubscriptionCompleted(
                            requestId = requestId,
                            serviceUuid = serviceUuid,
                            characteristicUuid = characteristicUuid,
                            success = false,
                            status = SIMULATED_GATT_ERROR,
                            message = "Simulated GATT subscription was rejected",
                        ),
                    )
                    emit(
                        BleEvent.Error(
                            operation = "subscribe",
                            message = "Simulated GATT subscription was rejected",
                            status = SIMULATED_GATT_ERROR,
                        ),
                    )
                }
                SubscriptionOutcome.CLOSED -> {
                    emit(
                        BleEvent.SubscriptionCompleted(
                            requestId = requestId,
                            serviceUuid = serviceUuid,
                            characteristicUuid = characteristicUuid,
                            success = false,
                            message = "Simulated connection was closed before subscription completed",
                        ),
                    )
                }
            }
        }
        synchronized(lock) { subscriptionJob = job }
        job.start()
    }

    private fun startNotificationPumpIfReady() {
        var expectedGeneration = 0L
        var key: Pair<UUID, UUID>? = null
        var job: Job? = null
        synchronized(lock) {
            if (closed || state != BleConnectionState.Connected || !servicesDiscovered) return
            if (notificationActive) return
            key = enabledSubscription ?: return
            notificationActive = true
            expectedGeneration = generation
            job = scope.launch(start = CoroutineStart.LAZY) {
                emitNotifications(expectedGeneration, key!!)
            }
            notificationJob = job
        }
        job?.start()
    }

    private suspend fun emitNotifications(expectedGeneration: Long, key: Pair<UUID, UUID>) {
        val simulator = FirmwarePacketSimulator(config.firmware)
        val startedAtNanos = System.nanoTime()
        var nextDueMillis = 0L
        try {
            while (true) {
                if (!isCurrentConnection(expectedGeneration)) return
                val elapsedMillis = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI
                if (simulator.isStalledAt(elapsedMillis)) return
                val payload = simulator.nextPacket()
                if (payload != null) {
                    lastPayload = payload
                    emittedNotifications.incrementAndGet()
                    emit(
                        BleEvent.NotificationReceived(
                            serviceUuid = key.first,
                            characteristicUuid = key.second,
                            value = payload.copyOf(),
                            receivedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                }
                nextDueMillis += config.packetIntervalMillis
                val sleepMillis = nextDueMillis - (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI
                if (sleepMillis > 0L) delay(sleepMillis)
            }
        } finally {
            synchronized(lock) {
                if (generation == expectedGeneration) notificationActive = false
            }
        }
    }

    private fun scheduleForcedDisconnect(expectedGeneration: Long) {
        if (config.forceDisconnectAfterMillis <= 0L) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(config.forceDisconnectAfterMillis)
            if (!isCurrentConnection(expectedGeneration) || state != BleConnectionState.Connected) return@launch
            dropLink()
        }
        synchronized(lock) { disconnectJob = job }
        job.start()
    }

    /** Simulates an over-the-air link loss: terminal error plus a transition to Idle. */
    private fun dropLink() {
        val dropped = synchronized(lock) {
            if (closed || state != BleConnectionState.Connected) {
                false
            } else {
                generation += 1
                servicesDiscovered = false
                enabledSubscription = null
                notificationActive = false
                notificationJob?.cancel()
                notificationJob = null
                subscriptionJob?.cancel()
                subscriptionJob = null
                connectionJob?.cancel()
                connectionJob = null
                disconnectJob?.cancel()
                disconnectJob = null
                connectedDevice = null
                true
            }
        }
        if (!dropped) return
        changeState(BleConnectionState.Idle)
        failPendingSubscriptions("Simulated BLE link was lost")
        failPendingRequests("Simulated BLE link was lost")
        emit(BleEvent.Error("gatt", "Simulated BLE link was lost", SIMULATED_GATT_ERROR))
    }

    private fun failPendingSubscriptions(message: String) {
        val pending = synchronized(lock) {
            pendingSubscriptions.toList().also { pendingSubscriptions.clear() }
        }
        pending.forEach { request ->
            emit(
                BleEvent.SubscriptionCompleted(
                    requestId = request.requestId,
                    serviceUuid = request.serviceUuid,
                    characteristicUuid = request.characteristicUuid,
                    success = false,
                    message = message,
                ),
            )
        }
    }

    private fun failPendingRequests(message: String) {
        val reads = synchronized(lock) { pendingReads.toList().also { pendingReads.clear() } }
        val writes = synchronized(lock) { pendingWrites.toList().also { pendingWrites.clear() } }
        reads.forEach { request ->
            emit(
                BleEvent.ReadCompleted(
                    requestId = request.requestId,
                    serviceUuid = request.serviceUuid,
                    characteristicUuid = request.characteristicUuid,
                    value = null,
                    message = message,
                ),
            )
        }
        writes.forEach { request ->
            emit(
                BleEvent.WriteCompleted(
                    requestId = request.requestId,
                    serviceUuid = request.serviceUuid,
                    characteristicUuid = request.characteristicUuid,
                    success = false,
                    message = message,
                ),
            )
        }
    }

    private fun cancelConnectionWorkLocked() {
        connectionJob?.cancel()
        subscriptionJob?.cancel()
        notificationJob?.cancel()
        disconnectJob?.cancel()
        connectionJob = null
        subscriptionJob = null
        notificationJob = null
        disconnectJob = null
        notificationActive = false
        pendingSubscriptions.clear()
        pendingReads.clear()
        pendingWrites.clear()
    }

    private fun isCurrentConnection(expectedGeneration: Long): Boolean = synchronized(lock) {
        !closed && generation == expectedGeneration && state != BleConnectionState.Closed
    }

    private fun ensureOpen() {
        check(!closed) { "Bluetooth client is closed" }
    }

    private fun ensureGattReady() {
        check(state == BleConnectionState.Connected && synchronized(lock) { servicesDiscovered }) {
            "BLE device is not ready for GATT operations"
        }
    }

    private fun changeState(next: BleConnectionState) {
        if (closed && next != BleConnectionState.Closed) return
        if (state == next) return
        state = next
        emit(BleEvent.StateChanged(next))
    }

    private fun emit(event: BleEvent) {
        listener?.invoke(event)
    }

    private fun emitError(operation: String, error: Throwable) {
        emit(
            BleEvent.Error(
                operation = operation,
                message = error.message ?: error::class.java.simpleName,
                cause = error,
            ),
        )
    }

    private data class PendingRequest(
        val requestId: Long,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
    )

    private enum class SubscriptionOutcome { ENABLED, FAILED, CLOSED }

    companion object {
        /** BluetoothGatt.GATT_ERROR; the simulator reports it like a failed CCCD descriptor write. */
        const val SIMULATED_GATT_ERROR: Int = 133

        /** PROPERTY_READ | PROPERTY_WRITE | PROPERTY_NOTIFY. */
        private const val NOTIFY_PROPERTIES: Int = 0x1A
        private const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
