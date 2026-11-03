package com.feizhela.headband.simulation

import android.content.Context
import android.os.SystemClock
import com.feizhela.headband.bluetooth.BleDevice
import com.feizhela.headband.bluetooth.DefaultBluetoothRepository
import com.feizhela.headband.bluetooth.FlowBluetoothDataSource
import com.feizhela.headband.bluetooth.TransportProvider
import com.feizhela.headband.recording.RecordingPhase
import com.feizhela.headband.recording.RecordingSessionRepository
import com.feizhela.headband.storage.RawPacketEntity
import com.feizhela.headband.storage.RecordingDao
import com.feizhela.headband.storage.RecordingDatabase
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of one simulated acquisition, including the durable Room row that was written. */
data class SimulationResult(
    val scenarioName: String,
    val sessionId: String?,
    val phase: RecordingPhase,
    val reason: String?,
    val sessionStatus: String?,
    val sessionEndReason: String?,
    val notificationsEmitted: Int,
    val notificationsReceived: Int,
    val packetsPersisted: Long,
    val requestedDurationMillis: Long,
)

/**
 * Runs the real acquisition pipeline against a [SimulatedBluetoothClient]:
 *
 * ```
 * SimulatedBluetoothClient → FlowBluetoothDataSource → DefaultBluetoothRepository
 *   → RecordingSessionRepository → real Room database (recordings.db)
 * ```
 *
 * The transport factory is installed for the duration of the run and reset afterwards. A
 * `rawPackets` collector is started and awaited before `connect`, because
 * [DefaultBluetoothRepository] treats a missing consumer as a fatal transport error. The simulated
 * client never touches Android BLE, so this works on an x86 emulator such as MuMu.
 *
 * The harness never deletes rows: cleanup belongs to the caller, which can delete exactly the
 * session it created.
 */
object SimulationRun {
    suspend fun runRecording(
        context: Context,
        scenario: SimulationScenario,
        durationMillis: Long,
    ): SimulationResult = withContext(Dispatchers.IO) {
        require(durationMillis > 0L) { "durationMillis must be positive" }
        val appContext = context.applicationContext
        val client = scenario.createClient()
        TransportProvider.factory = { client }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receivedPackets = AtomicInteger(0)
        var bluetooth: DefaultBluetoothRepository? = null
        var controller: RecordingSessionRepository? = null
        var rawConsumer: Job? = null

        try {
            val transport = TransportProvider.create(appContext)
            check(transport === client) {
                "Simulated transport is not active; TransportProvider only honours debug builds"
            }
            val database = RecordingDatabase.open(appContext)
            val dao: RecordingDao = if (scenario.storageWriteDelayMillis > 0L) {
                SlowRecordingDao(database.recordingDao, scenario.storageWriteDelayMillis)
            } else {
                database.recordingDao
            }
            val repository = DefaultBluetoothRepository(FlowBluetoothDataSource(transport), scope)
            bluetooth = repository

            // Must be subscribed before connect: the repository fails notifications without it.
            val rawSubscribed = CompletableDeferred<Unit>()
            val consumer = scope.launch(start = CoroutineStart.LAZY) {
                repository.rawPackets
                    .onSubscription { rawSubscribed.complete(Unit) }
                    .collect { receivedPackets.incrementAndGet() }
            }
            rawConsumer = consumer
            consumer.start()
            withTimeout(RAW_CONSUMER_TIMEOUT_MILLIS) { rawSubscribed.await() }

            val recording = RecordingSessionRepository(dao, repository, scope)
            controller = recording
            val startResult = recording.start(
                BleDevice(SIMULATED_DEVICE_ADDRESS, SIMULATED_DEVICE_NAME, SIMULATION_RSSI),
                SIMULATED_SERVICE_UUID,
                SIMULATED_CHARACTERISTIC_UUID,
            )
            val sessionId = startResult.getOrNull() ?: recording.state.value.sessionId

            if (startResult.isSuccess) {
                awaitTerminalOrDeadline(recording, durationMillis)
            }

            val state = recording.state.value
            val row = sessionId?.let { id ->
                runCatching { withTimeoutOrNull(ROW_READ_TIMEOUT_MILLIS) { dao.observeSession(id).first() } }
                    .getOrNull()
            }
            SimulationResult(
                scenarioName = scenario.name,
                sessionId = sessionId,
                phase = state.phase,
                reason = state.reason,
                sessionStatus = row?.status,
                sessionEndReason = row?.endReason,
                notificationsEmitted = client.notificationCount,
                notificationsReceived = receivedPackets.get(),
                packetsPersisted = state.packetCount,
                requestedDurationMillis = durationMillis,
            )
        } finally {
            withContext(NonCancellable) {
                val state = controller?.state?.value
                if (state != null && state.phase in ACTIVE_PHASES) {
                    runCatching { controller?.interrupt("SIMULATION_ABORTED") }
                }
                runCatching { bluetooth?.disconnect() }
                runCatching { bluetooth?.close() }
                rawConsumer?.cancel()
                TransportProvider.reset()
                scope.cancel()
            }
        }
    }

    /**
     * Waits for a terminal recording state. A healthy recording is stopped at the requested
     * deadline; an injected link loss terminates early. The extra grace period only covers an
     * unexpectedly slow start/stop, never extends the requested recording duration.
     */
    private suspend fun awaitTerminalOrDeadline(
        controller: RecordingSessionRepository,
        durationMillis: Long,
    ) {
        val deadline = SystemClock.elapsedRealtime() + durationMillis
        while (true) {
            val state = controller.state.value
            if (state.phase in TERMINAL_PHASES) return
            val now = SystemClock.elapsedRealtime()
            if (state.phase == RecordingPhase.Recording) {
                if (now >= deadline) {
                    controller.stop()
                    return
                }
                delay(minOf(deadline - now, POLL_INTERVAL_MILLIS))
            } else {
                if (now >= deadline + STARTUP_GRACE_MILLIS) {
                    controller.stop()
                    return
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /** Wraps the real DAO and slows only batch inserts, simulating a slow storage device. */
    private class SlowRecordingDao(
        private val delegate: RecordingDao,
        private val batchDelayMillis: Long,
    ) : RecordingDao by delegate {
        override suspend fun insertPackets(packets: List<RawPacketEntity>) {
            delay(batchDelayMillis)
            delegate.insertPackets(packets)
        }
    }

    private const val SIMULATION_RSSI = -42
    private const val POLL_INTERVAL_MILLIS = 25L
    private const val STARTUP_GRACE_MILLIS = 5_000L
    private const val RAW_CONSUMER_TIMEOUT_MILLIS = 5_000L
    private const val ROW_READ_TIMEOUT_MILLIS = 2_000L

    private val TERMINAL_PHASES = setOf(
        RecordingPhase.Completed,
        RecordingPhase.Interrupted,
        RecordingPhase.Failed,
    )
    private val ACTIVE_PHASES = setOf(
        RecordingPhase.Starting,
        RecordingPhase.Recording,
        RecordingPhase.Stopping,
    )
}
