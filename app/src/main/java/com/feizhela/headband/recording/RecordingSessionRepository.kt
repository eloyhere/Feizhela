package com.feizhela.headband.recording

import com.feizhela.headband.bluetooth.BleBufferOverflowException
import com.feizhela.headband.bluetooth.BleConnectionState
import com.feizhela.headband.bluetooth.BleDevice
import com.feizhela.headband.bluetooth.BleEvent
import com.feizhela.headband.bluetooth.BluetoothRepository
import com.feizhela.headband.storage.AcquisitionGapEntity
import com.feizhela.headband.storage.RawPacketEntity
import com.feizhela.headband.storage.RecordingDao
import com.feizhela.headband.storage.RecordingSessionEntity
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Recording lifecycle and durable raw-packet batching; payload bytes are never interpreted here.
 *
 * [RecordingState.metrics] exposes write-backlog, batch-latency and acquisition-gap counters. They
 * are sampled per flushed batch (never per packet), so observing them does not multiply state
 * emissions on the acquisition path.
 */
class RecordingSessionRepository(
    private val dao: RecordingDao,
    private val bluetooth: BluetoothRepository,
    private val scope: CoroutineScope,
    /** Protocol schema recorded on each new session; null when the caller cannot report one. */
    private val protocolVersion: String? = null,
    /** App version recorded on each new session; null when the caller cannot report one. */
    private val appVersion: String? = null,
) : AutoCloseable {
    private val lifecycleMutex = Mutex()
    private val mutableState = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = mutableState.asStateFlow()

    private var packetChannel: Channel<QueuedPacket>? = null
    private var packetCollector: Job? = null
    private var writer: Job? = null
    private var connectionMonitor: Job? = null
    private var sessionId: String? = null
    private var sessionStartedAtMillis: Long? = null
    private var sequence = 0L
    private var acceptingPackets = false
    private var closed = false
    private val storageFailed = AtomicBoolean(false)

    // Write-backlog telemetry. Atomics keep the per-packet acquisition path free of StateFlow
    // writes; [RecordingState.metrics] is only refreshed when a batch is flushed.
    private val acceptedPackets = AtomicLong(0)
    private val writtenPackets = AtomicLong(0)
    private val maxQueuedPacketsObserved = AtomicInteger(0)
    private var lastBatchWriteMillis = 0L
    private var maxBatchWriteMillis = 0L
    private var knownGapCount = 0
    private var knownDroppedEstimate = 0L
    private var lastGapRefreshNanos = 0L

    /** Captured by the connection monitor so it can be persisted before finishSession. */
    @Volatile
    private var pendingGap: PendingGap? = null

    suspend fun start(
        device: BleDevice,
        serviceUuid: UUID,
        characteristicUuid: UUID,
    ): Result<String> = lifecycleMutex.withLock {
        if (closed) return@withLock Result.failure(IllegalStateException("Recording repository is closed"))
        if (mutableState.value.phase in setOf(RecordingPhase.Starting, RecordingPhase.Recording, RecordingPhase.Stopping)) {
            return@withLock Result.failure(IllegalStateException("A recording is already active"))
        }

        val id = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        try {
            dao.createSession(
                RecordingSessionEntity(
                    id = id,
                    deviceAddress = device.address,
                    deviceName = device.name,
                    serviceUuid = serviceUuid.toString(),
                    characteristicUuid = characteristicUuid.toString(),
                    startedAtMillis = startedAt,
                    protocolVersion = protocolVersion,
                    appVersion = appVersion,
                ),
            )
        } catch (error: Throwable) {
            mutableState.value = RecordingState(
                phase = RecordingPhase.Failed,
                sessionId = id,
                reason = reasonOf(error),
                startedAtMillis = startedAt,
            )
            return@withLock Result.failure(error)
        }

        activeServiceUuid = serviceUuid.toString()
        activeCharacteristicUuid = characteristicUuid.toString()
        beginSession(id, startedAt)
        mutableState.value = RecordingState(
            phase = RecordingPhase.Starting,
            sessionId = id,
            startedAtMillis = startedAt,
        )
        try {
            withTimeout(START_TIMEOUT_MILLIS) {
                val subscriptionEvents = Channel<BleEvent.SubscriptionCompleted>(Channel.UNLIMITED)
                val subscriptionCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    bluetooth.events.collect { event ->
                        if (event is BleEvent.SubscriptionCompleted) subscriptionEvents.send(event)
                    }
                }
                try {
                    val connectResult = bluetooth.connect(device)
                    connectResult.getOrThrow()
                    // The repository can still publish the pre-connect Idle state until the
                    // transport's own events are reduced, so require an actual reaction to
                    // connect() before treating Idle as a lost link.
                    bluetooth.state.first { session ->
                        session.connection != BleConnectionState.Idle || session.lastError != null
                    }
                    bluetooth.state.first { session ->
                        session.servicesDiscovered || session.lastError != null ||
                            session.connection == BleConnectionState.Idle || session.connection == BleConnectionState.Closed
                    }.let { session ->
                        if (!session.servicesDiscovered) {
                            error(session.lastError?.message ?: "BLE disconnected before services were ready")
                        }
                    }

                    // Enable archiving before requesting the subscription: the first notifications
                    // can arrive between the CCCD write and our completion event, and losing those
                    // samples would leave a silent hole at the start of every session.
                    acceptingPackets = true
                    val requestId = bluetooth.subscribe(serviceUuid, characteristicUuid).getOrThrow()
                    val completion = subscriptionEvents.receiveUntil(
                        timeoutMillis = START_TIMEOUT_MILLIS,
                        terminal = {
                            bluetooth.state.value.connection in setOf(BleConnectionState.Idle, BleConnectionState.Closed)
                        },
                    ) { it.requestId == requestId }
                    if (!completion.success) {
                        error(completion.message ?: "BLE notification subscription failed (status=${completion.status})")
                    }
                    check(!storageFailed.get()) { "Raw packet writer failed during startup" }
                    acceptingPackets = true
                    mutableState.value = RecordingState(
                        phase = RecordingPhase.Recording,
                        sessionId = id,
                        startedAtMillis = startedAt,
                    )
                    startConnectionMonitor()
                    Result.success(id)
                } finally {
                    subscriptionCollector.cancel()
                    subscriptionEvents.close()
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                failStartup(id, "Recording start was cancelled", error)
                throw error
            }
            failStartup(id, reasonOf(error), error)
            Result.failure(error)
        }
    }

    suspend fun stop() = lifecycleMutex.withLock {
        val id = sessionId ?: return@withLock
        if (mutableState.value.phase !in setOf(RecordingPhase.Recording, RecordingPhase.Starting)) return@withLock
        mutableState.value = mutableState.value.copy(phase = RecordingPhase.Stopping)
        acceptingPackets = false
        bluetooth.disconnect()
        stopCollectorsAndFlush()
        if (storageFailed.get()) return@withLock
        refreshGapMetrics(id)
        runCatching {
            dao.finishSession(id, "COMPLETED", System.currentTimeMillis(), null)
        }.onSuccess {
            mutableState.value = RecordingState(
                RecordingPhase.Completed,
                id,
                sequence,
                metrics = metricsSnapshot(),
                startedAtMillis = sessionStartedAtMillis,
            )
            clearSession()
        }.onFailure { error -> persistFailure(id, error) }
    }

    suspend fun interrupt(reason: String) = lifecycleMutex.withLock {
        val id = sessionId ?: return@withLock
        if (mutableState.value.phase !in setOf(RecordingPhase.Recording, RecordingPhase.Starting)) return@withLock
        // A transport gap must be durable before finishSession makes the session terminal, so it
        // is visible to recovery/export of the same session. Gap failures never block the interrupt.
        mutableState.value = mutableState.value.copy(phase = RecordingPhase.Stopping)
        persistPendingGap(id, reason)
        acceptingPackets = false
        bluetooth.disconnect()
        stopCollectorsAndFlush()
        if (storageFailed.get()) return@withLock
        refreshGapMetrics(id)
        runCatching {
            dao.finishSession(id, "INTERRUPTED", System.currentTimeMillis(), reason)
        }.onSuccess {
            mutableState.value = RecordingState(
                RecordingPhase.Interrupted,
                id,
                sequence,
                reason,
                metrics = metricsSnapshot(),
                startedAtMillis = sessionStartedAtMillis,
            )
            clearSession()
        }.onFailure { error -> persistFailure(id, error, reason) }
    }

    suspend fun recoverInterruptedSessions() = lifecycleMutex.withLock {
        dao.interruptActiveSessions(System.currentTimeMillis(), "PROCESS_RESTART")
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.launch {
            runCatching { interrupt("REPOSITORY_CLOSED") }
            bluetooth.disconnect()
        }
    }

    private fun beginSession(id: String, startedAtMillis: Long) {
        sessionId = id
        sessionStartedAtMillis = startedAtMillis
        sequence = 0
        storageFailed.set(false)
        acceptingPackets = false
        resetMetrics()
        val packets = Channel<QueuedPacket>(PACKET_CHANNEL_CAPACITY)
        packetChannel = packets
        packetCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                bluetooth.rawPackets.collect { packet ->
                    if (acceptingPackets && packet.serviceUuid.toString() == activeServiceUuid &&
                        packet.characteristicUuid.toString() == activeCharacteristicUuid
                    ) {
                        val packetSequence = sequence
                        sequence += 1
                        val queued = QueuedPacket(
                            sequence = packetSequence,
                            receivedAtMillis = packet.receivedAtMillis,
                            payload = packet.payload.copyOf(),
                            enqueuedAtNanos = System.nanoTime(),
                        )
                        val accepted = acceptedPackets.incrementAndGet()
                        try {
                            packets.send(queued)
                        } catch (error: Throwable) {
                            acceptedPackets.decrementAndGet()
                            throw error
                        }
                        // Cheap counter only: never publish state from the per-packet path.
                        observeQueuedPackets(
                            (accepted - writtenPackets.get()).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error !is CancellationException) scope.launch { failActiveStorage(error) }
            }
        }
        writer = scope.launch { writeBatches(id, packets) }
    }

    private var activeServiceUuid: String = ""
    private var activeCharacteristicUuid: String = ""

    private suspend fun writeBatches(id: String, packets: Channel<QueuedPacket>) {
        val batch = ArrayList<RawPacketEntity>(BATCH_SIZE)
        var oldestEnqueuedAtNanos = 0L
        try {
            while (true) {
                val next = if (batch.isEmpty()) {
                    packets.receiveCatching().getOrNull() ?: break
                } else {
                    withTimeoutOrNull(FLUSH_INTERVAL_MILLIS) { packets.receiveCatching().getOrNull() }
                }
                if (next == null) {
                    flush(id, batch, oldestEnqueuedAtNanos)
                    oldestEnqueuedAtNanos = 0L
                    if (packets.isClosedForReceive) break
                    continue
                }
                if (batch.isEmpty()) oldestEnqueuedAtNanos = next.enqueuedAtNanos
                batch += RawPacketEntity(id, next.sequence, next.receivedAtMillis, next.payload)
                if (batch.size >= BATCH_SIZE) {
                    flush(id, batch, oldestEnqueuedAtNanos)
                    oldestEnqueuedAtNanos = 0L
                }
            }
            flush(id, batch, oldestEnqueuedAtNanos)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            storageFailed.set(true)
            acceptingPackets = false
            mutableState.value = RecordingState(
                RecordingPhase.Failed,
                id,
                sequence,
                reasonOf(error),
                metrics = metricsSnapshot(),
                startedAtMillis = sessionStartedAtMillis,
            )
            bluetooth.disconnect()
            runCatching { dao.finishSession(id, "FAILED", System.currentTimeMillis(), reasonOf(error)) }
            packetCollector?.cancel()
            packets.cancel()
        }
    }

    private suspend fun flush(
        id: String,
        batch: ArrayList<RawPacketEntity>,
        oldestEnqueuedAtNanos: Long,
    ) {
        if (batch.isEmpty()) return
        val flushedCount = batch.size
        val startedAtNanos = System.nanoTime()
        val oldestQueuedAgeMillis = if (oldestEnqueuedAtNanos > 0L) {
            ((startedAtNanos - oldestEnqueuedAtNanos) / NANOS_PER_MILLI).coerceAtLeast(0L)
        } else {
            0L
        }
        dao.insertPackets(batch.toList())
        lastBatchWriteMillis = ((System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI).coerceAtLeast(0L)
        if (lastBatchWriteMillis > maxBatchWriteMillis) maxBatchWriteMillis = lastBatchWriteMillis
        writtenPackets.addAndGet(flushedCount.toLong())
        batch.clear()
        refreshGapCountIfDue(id)
        mutableState.value = mutableState.value.copy(
            packetCount = mutableState.value.packetCount + flushedCount,
            metrics = metricsSnapshot(oldestQueuedAgeMillis),
        )
    }

    /**
     * A lost link or transport error during Recording must become INTERRUPTED, never stay ACTIVE.
     * The interrupt is dispatched separately so it can queue on the lifecycle mutex instead of
     * deadlocking the caller that already holds it.
     */
    private fun startConnectionMonitor() {
        connectionMonitor?.cancel()
        connectionMonitor = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bluetooth.state.collect { session ->
                if (mutableState.value.phase != RecordingPhase.Recording) return@collect
                val lost = session.connection == BleConnectionState.Idle ||
                    session.connection == BleConnectionState.Closed ||
                    session.lastError != null
                if (!lost) return@collect
                val reason = session.lastError?.message?.takeIf { it.isNotBlank() } ?: "BLE_DISCONNECTED"
                val overflow = session.lastError?.cause is BleBufferOverflowException ||
                    session.lastError?.operation == "eventStream"
                val now = System.currentTimeMillis()
                // The interrupt that follows consumes this and persists it before finishSession.
                pendingGap = PendingGap(
                    kind = if (overflow) GAP_KIND_BUFFER_OVERFLOW else GAP_KIND_DISCONNECT,
                    startedAtMillis = now,
                    endedAtMillis = now,
                    detail = reason,
                )
                scope.launch { interrupt(reason) }
            }
        }
    }

    private suspend fun stopCollectorsAndFlush() {
        connectionMonitor?.cancel()
        connectionMonitor = null
        packetCollector?.cancel()
        packetCollector?.join()
        packetChannel?.close()
        writer?.join()
        packetCollector = null
        writer = null
        packetChannel = null
    }

    private suspend fun failStartup(id: String, reason: String, error: Throwable) {
        acceptingPackets = false
        bluetooth.disconnect()
        stopCollectorsAndFlush()
        if (!storageFailed.get()) {
            runCatching { dao.finishSession(id, "FAILED", System.currentTimeMillis(), reason) }
        }
        mutableState.value = RecordingState(
            RecordingPhase.Failed,
            id,
            sequence,
            reason,
            metrics = metricsSnapshot(),
            startedAtMillis = sessionStartedAtMillis,
        )
        clearSession()
    }

    private suspend fun failActiveStorage(error: Throwable) {
        lifecycleMutex.withLock {
            val id = sessionId ?: return@withLock
            storageFailed.set(true)
            acceptingPackets = false
            bluetooth.disconnect()
            packetChannel?.close()
            packetCollector?.cancel()
            runCatching { dao.finishSession(id, "FAILED", System.currentTimeMillis(), reasonOf(error)) }
            mutableState.value = RecordingState(
                RecordingPhase.Failed,
                id,
                sequence,
                reasonOf(error),
                metrics = metricsSnapshot(),
                startedAtMillis = sessionStartedAtMillis,
            )
        }
    }

    private suspend fun persistFailure(id: String, error: Throwable, reason: String? = null) {
        val failureReason = "${reason?.let { "$it: " } ?: ""}${reasonOf(error)}"
        storageFailed.set(true)
        acceptingPackets = false
        bluetooth.disconnect()
        runCatching { dao.finishSession(id, "FAILED", System.currentTimeMillis(), failureReason) }
        mutableState.value = RecordingState(
            RecordingPhase.Failed,
            id,
            sequence,
            failureReason,
            metrics = metricsSnapshot(),
            startedAtMillis = sessionStartedAtMillis,
        )
        clearSession()
    }

    private fun clearSession() {
        connectionMonitor?.cancel()
        connectionMonitor = null
        sessionId = null
        sessionStartedAtMillis = null
        packetChannel = null
        packetCollector = null
        writer = null
        acceptingPackets = false
        pendingGap = null
    }

    private fun resetMetrics() {
        acceptedPackets.set(0)
        writtenPackets.set(0)
        maxQueuedPacketsObserved.set(0)
        lastBatchWriteMillis = 0
        maxBatchWriteMillis = 0
        knownGapCount = 0
        knownDroppedEstimate = 0
        // Force the first post-start batch to sample the gap count without depending on the
        // absolute value of the monotonic clock.
        lastGapRefreshNanos = System.nanoTime() - GAP_REFRESH_INTERVAL_NANOS
        pendingGap = null
    }

    /**
     * Persists a gap captured by the connection monitor. Failures are swallowed so that a broken
     * gap write can never block the INTERRUPTED transition or session finalisation.
     */
    private suspend fun persistPendingGap(id: String, fallbackDetail: String) {
        val gap = pendingGap ?: return
        pendingGap = null
        runCatching {
            dao.insertGap(
                AcquisitionGapEntity(
                    sessionId = id,
                    kind = gap.kind,
                    startedAtMillis = gap.startedAtMillis,
                    endedAtMillis = gap.endedAtMillis,
                    droppedEstimate = gap.droppedEstimate,
                    detail = gap.detail ?: fallbackDetail,
                ),
            )
        }.onSuccess {
            gap.droppedEstimate?.let { estimate -> knownDroppedEstimate += estimate }
        }
    }

    /** Samples the durable gap count at most once per second during batch writes. */
    private suspend fun refreshGapCountIfDue(id: String) {
        val now = System.nanoTime()
        if (now - lastGapRefreshNanos < GAP_REFRESH_INTERVAL_NANOS) return
        lastGapRefreshNanos = now
        runCatching { dao.countGaps(id) }.onSuccess { knownGapCount = it }
    }

    /** Authoritative refresh at session end; also sums estimates written by any gap producer. */
    private suspend fun refreshGapMetrics(id: String) {
        runCatching { dao.countGaps(id) }.onSuccess { knownGapCount = it }
        runCatching { dao.readGaps(id) }.onSuccess { gaps ->
            knownDroppedEstimate = gaps.sumOf { it.droppedEstimate ?: 0L }
        }
    }

    private fun observeQueuedPackets(depth: Int) {
        while (true) {
            val current = maxQueuedPacketsObserved.get()
            if (depth <= current || maxQueuedPacketsObserved.compareAndSet(current, depth)) return
        }
    }

    private fun metricsSnapshot(oldestQueuedAgeMillis: Long = 0L): RecordingMetrics {
        val written = writtenPackets.get()
        val queued = (acceptedPackets.get() - written).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return RecordingMetrics(
            queuedPackets = queued,
            maxQueuedPackets = maxQueuedPacketsObserved.get(),
            writtenPackets = written,
            oldestQueuedAgeMillis = oldestQueuedAgeMillis,
            lastBatchWriteMillis = lastBatchWriteMillis,
            maxBatchWriteMillis = maxBatchWriteMillis,
            // Not fabricated: this repository has no Context or database path, and the DAO
            // exposes no size query. The construction site must supply a size provider later.
            databaseBytes = 0L,
            gapCount = knownGapCount,
            droppedEstimate = knownDroppedEstimate,
        )
    }

    private suspend fun Channel<BleEvent.SubscriptionCompleted>.receiveUntil(
        timeoutMillis: Long,
        terminal: () -> Boolean,
        predicate: (BleEvent.SubscriptionCompleted) -> Boolean,
    ): BleEvent.SubscriptionCompleted = withTimeout(timeoutMillis) {
        while (true) {
            if (terminal()) error("BLE disconnected while enabling notifications")
            val event = receive()
            if (predicate(event)) return@withTimeout event
        }
        error("Unreachable")
    }

    private fun reasonOf(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

    private data class QueuedPacket(
        val sequence: Long,
        val receivedAtMillis: Long,
        val payload: ByteArray,
        val enqueuedAtNanos: Long,
    )

    private data class PendingGap(
        val kind: String,
        val startedAtMillis: Long,
        val endedAtMillis: Long,
        val detail: String?,
        val droppedEstimate: Long? = null,
    )

    companion object {
        private const val BATCH_SIZE = 32
        private const val FLUSH_INTERVAL_MILLIS = 1_000L
        private const val START_TIMEOUT_MILLIS = 30_000L
        private const val PACKET_CHANNEL_CAPACITY = 128
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val GAP_REFRESH_INTERVAL_NANOS = 1_000_000_000L
        private const val GAP_KIND_DISCONNECT = "DISCONNECT"
        private const val GAP_KIND_BUFFER_OVERFLOW = "BUFFER_OVERFLOW"
    }
}

enum class RecordingPhase { Idle, Starting, Recording, Stopping, Completed, Interrupted, Failed }

data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.Idle,
    val sessionId: String? = null,
    val packetCount: Long = 0,
    val reason: String? = null,
    /** Write-backlog/gap telemetry for this session; refreshed per flushed batch. */
    val metrics: RecordingMetrics = RecordingMetrics(),
    /** Wall-clock start of this session; survives terminal phases so the UI can show its span. */
    val startedAtMillis: Long? = null,
)
