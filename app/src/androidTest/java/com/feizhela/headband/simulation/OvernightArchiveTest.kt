package com.feizhela.headband.simulation

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feizhela.headband.bluetooth.*
import com.feizhela.headband.bluetooth.protocol.FirmwarePacketDecoder
import com.feizhela.headband.recording.*
import com.feizhela.headband.storage.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, accelerated eight-hour data-volume check. Real recording writer, on-disk Room and
 * estimator; synthetic repository input uses backpressure rather than emulating GATT timing.
 * Run with `-e overnight true`. This does not establish eight hours of Android background survival.
 */
@RunWith(AndroidJUnit4::class)
class OvernightArchiveTest {
    @Test(timeout = 600_000)
    fun eightHoursSurviveAnalysisOwnerReplacementAndDatabaseReopen() = runBlocking<Unit> {
        assumeTrue("Pass -e overnight true for the accelerated volume check",
            InstrumentationRegistry.getArguments().getString("overnight") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "overnight-${UUID.randomUUID()}.db"
        var database = open(context, databaseName)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val input = ArchiveInput()
        val maxAnalysisPage = AtomicInteger()
        var reads = 0L
        fun observedDao(): RecordingDao = object : RecordingDao by database.recordingDao {
            override suspend fun readPackets(sessionId: String, afterSequence: Long, limit: Int): List<RawPacketEntity> {
                maxAnalysisPage.updateAndGet { maxOf(it, limit) }
                reads++
                return database.recordingDao.readPackets(sessionId, afterSequence, limit)
            }
        }
        val recording = RecordingSessionRepository(database.recordingDao, input, scope)
        var analysis = SignalAnalysisRepository(observedDao(), database.signalEpochDao)
        var follower = analysis.follow(scope, recording.state)
        val start = SystemClock.elapsedRealtime()
        val digest = MessageDigest.getInstance("SHA-256")
        val simulatorConfig = FirmwareSimulatorConfig(eogPulseEverySamples = 0)
        val simulator = FirmwarePacketSimulator(simulatorConfig)
        var highestHeap = 0L
        var ownerRestartMillis = 0L
        try {
            withTimeout(540_000) {
                val id = recording.start(input.device, SIMULATED_SERVICE_UUID, SIMULATED_CHARACTERISTIC_UUID).getOrThrow()
                for (sequence in 0 until PACKETS) {
                    val bytes = requireNotNull(simulator.nextPacket())
                    digest.update(bytes)
                    // Exact nominal 104 Hz device timeline; emission is intentionally accelerated.
                    input.packets.emit(RawBlePacket(SIMULATED_SERVICE_UUID, SIMULATED_CHARACTERISTIC_UUID,
                        bytes, START_MILLIS + (sequence * 10L + 9) * 1000 / 104))
                    if ((sequence + 1) % 512 == 0) {
                        recording.state.first { it.packetCount >= sequence + 1L || it.phase == RecordingPhase.Failed }
                        check(recording.state.value.phase == RecordingPhase.Recording) { recording.state.value.toString() }
                        val runtime = Runtime.getRuntime()
                        highestHeap = maxOf(highestHeap, runtime.totalMemory() - runtime.freeMemory())
                    }
                    if (sequence + 1 == PACKETS / 2) {
                        follower.cancelAndJoin()
                        val restartAt = SystemClock.elapsedRealtime()
                        analysis = SignalAnalysisRepository(observedDao(), database.signalEpochDao)
                        // A new owner reconstructs the active cursor and idempotently rewrites earlier epochs.
                        analysis.reconcile()
                        ownerRestartMillis = SystemClock.elapsedRealtime() - restartAt
                        follower = analysis.follow(scope, recording.state)
                    }
                }
                recording.state.first { it.packetCount >= (PACKETS / 32 * 32).toLong() }
                recording.stop()
                val archivedAt = SystemClock.elapsedRealtime()
                assertEquals(RecordingPhase.Completed, recording.state.value.phase)
                assertEquals(PACKETS.toLong(), recording.state.value.packetCount)
                assertEquals(0, database.recordingDao.countGaps(id))
                database.signalEpochDao.observeEpochs(id).first { it.size == EPOCHS }
                // Serialize against the follower to guarantee its final state is committed.
                analysis.reconcile()
                assertNull(analysis.state.value.lastError)
                val analyzedAt = SystemClock.elapsedRealtime()
                val epochs = database.signalEpochDao.readEpochs(id)
                assertEquals(EPOCHS, epochs.size)
                epochs.forEachIndexed { index, epoch ->
                    assertEquals(index.toLong(), epoch.epochIndex)
                    assertEquals(index * 3120L, epoch.startSampleIndex)
                    assertEquals(START_MILLIS + index * 30_000L, epoch.startedAtMillis)
                    assertEquals(3120, epoch.receivedSamples)
                    assertEquals("SPECTRAL_ONLY", epoch.quality)
                    assertNotNull(epoch.componentsJson)
                    assertTrue(requireNotNull(epoch.residualRatio).isFinite())
                }
                assertTrue("analysis must keep bounded pages", maxAnalysisPage.get() <= 128)
                val expectedHash = digest.digest().hex()
                assertEquals(expectedHash, verifyArchive(database.recordingDao, id, simulatorConfig))
                follower.cancelAndJoin()
                recording.close()
                val diskBytes = databaseBytes(context, databaseName)
                database.close()
                database = open(context, databaseName)
                assertEquals("COMPLETED", database.recordingDao.observeSession(id).first()!!.status)
                assertEquals(epochs, database.signalEpochDao.readEpochs(id))
                assertEquals(expectedHash, verifyArchive(database.recordingDao, id, simulatorConfig))
                val replayAt = SystemClock.elapsedRealtime()
                // A fresh terminal owner replays the entire archive once, including already stored output.
                SignalAnalysisRepository(database.recordingDao, database.signalEpochDao).reconcile()
                val replayMillis = SystemClock.elapsedRealtime() - replayAt
                assertEquals(epochs, database.signalEpochDao.readEpochs(id))
                val report = JSONObject()
                    .put("test", "accelerated-eight-hour-volume")
                    .put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("packets", PACKETS).put("samples", PACKETS * 10).put("epochs", EPOCHS)
                    .put("rawPayloadBytes", PACKETS * 180L).put("sha256", expectedHash)
                    .put("recordingAndConcurrentAnalysisMillis", archivedAt - start)
                    .put("completeAnalysisMillis", analyzedAt - start)
                    .put("activeOwnerReconstructionMillis", ownerRestartMillis)
                    .put("terminalReplayMillis", replayMillis)
                    .put("databaseAndWalBytesBeforeClose", diskBytes)
                    .put("sampledJavaHeapHighBytes", highestHeap)
                    .put("analysisReadPageLimit", maxAnalysisPage.get()).put("analysisPageReads", reads)
                    .put("maxWriterBatchMillis", recording.state.value.metrics.maxBatchWriteMillis)
                    .put("wallClockEightHours", false).put("gattTransportExercised", false)
                    .put("passed", true)
                val reportFile = File(requireNotNull(context.getExternalFilesDir(null)), "overnight-validation.json")
                reportFile.writeText(report.toString(2))
                println("OVERNIGHT_REPORT ${report}")
            }
        } finally {
            follower.cancelAndJoin()
            runCatching { recording.close() }
            scope.cancel()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun verifyArchive(dao: RecordingDao, id: String, config: FirmwareSimulatorConfig): String {
        val simulator = FirmwarePacketSimulator(config)
        val digest = MessageDigest.getInstance("SHA-256")
        var sequence = 0L
        var previous = -1
        while (true) {
            val page = dao.readPackets(id, sequence - 1, 512)
            if (page.isEmpty()) break
            for (packet in page) {
                assertEquals(sequence, packet.sequence)
                assertEquals(START_MILLIS + (sequence * 10 + 9) * 1000 / 104, packet.receivedAtMillis)
                assertArrayEquals(requireNotNull(simulator.nextPacket()), packet.payload)
                for (sample in FirmwarePacketDecoder.decode(packet.payload).samples) {
                    if (previous >= 0) assertEquals((previous + 1) and 65535, sample.counter)
                    previous = sample.counter
                }
                digest.update(packet.payload)
                sequence++
            }
        }
        assertEquals(PACKETS.toLong(), sequence)
        return digest.digest().hex()
    }

    private fun open(context: Context, name: String): RecordingDatabase =
        Room.databaseBuilder(context, RecordingDatabase::class.java, name).build()
    private fun databaseBytes(context: Context, name: String): Long =
        listOf("", "-wal", "-shm").sumOf { File(context.getDatabasePath(name).path + it).length() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Paced input at the repository boundary; deliberately does not claim a BLE transport test. */
    private class ArchiveInput : BluetoothRepository {
        val device = BleDevice(SIMULATED_DEVICE_ADDRESS, "overnight-volume-input")
        override val state = MutableStateFlow(BluetoothSessionState())
        override val discoveredDevices = MutableStateFlow(emptyList<BleDevice>())
        val packets = MutableSharedFlow<RawBlePacket>()
        override val rawPackets = packets.asSharedFlow()
        private val control = MutableSharedFlow<BleEvent>(extraBufferCapacity = 4)
        override val events = control.asSharedFlow()
        override fun startScan(serviceUuids: Set<UUID>) = Result.success(Unit)
        override fun stopScan() = Unit
        override fun connect(device: BleDevice): Result<Unit> {
            state.value = BluetoothSessionState(BleConnectionState.Connected, device, true)
            return Result.success(Unit)
        }
        override fun disconnect() { state.value = BluetoothSessionState() }
        override fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> {
            check(control.tryEmit(BleEvent.SubscriptionCompleted(1, serviceUuid, characteristicUuid, true)))
            return Result.success(1)
        }
        override fun read(serviceUuid: UUID, characteristicUuid: UUID) = Result.success(2L)
        override fun write(serviceUuid: UUID, characteristicUuid: UUID, value: ByteArray, writeType: BleWriteType) = Result.success(3L)
        override fun close() { state.value = BluetoothSessionState(connection = BleConnectionState.Closed) }
    }

    private companion object {
        const val PACKETS = 8 * 60 * 60 * 104 / 10
        const val EPOCHS = 8 * 60 * 2
        const val START_MILLIS = 1_000_000L
    }
}
