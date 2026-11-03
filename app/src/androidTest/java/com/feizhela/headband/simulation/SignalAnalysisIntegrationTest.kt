package com.feizhela.headband.simulation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feizhela.headband.bluetooth.BleDevice
import com.feizhela.headband.bluetooth.DefaultBluetoothRepository
import com.feizhela.headband.bluetooth.FlowBluetoothDataSource
import com.feizhela.headband.bluetooth.TransportProvider
import com.feizhela.headband.recording.AcquisitionService
import com.feizhela.headband.recording.RecordingPhase
import com.feizhela.headband.recording.RecordingState
import com.feizhela.headband.recording.RecordingSessionRepository
import com.feizhela.headband.recording.SignalAnalysisRepository
import com.feizhela.headband.storage.RawPacketEntity
import com.feizhela.headband.storage.RecordingDatabase
import com.feizhela.headband.storage.RecordingSessionEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignalAnalysisIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: RecordingDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, RecordingDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        TransportProvider.reset()
        database.close()
    }

    @Test
    fun assemblesFullEpochAndPartialTailAndReplayDoesNotDuplicate() = runBlocking {
        val id = seedCapture(packetCount = 314)
        val recordingDao = database.recordingDao
        val epochDao = database.signalEpochDao
        val analysis = SignalAnalysisRepository(recordingDao, epochDao)

        analysis.reconcile()
        val initial = epochDao.readEpochs(id)
        assertEquals(listOf(0L, 1L), initial.map { it.epochIndex })
        val complete = initial[0]
        assertEquals(0L, complete.startSampleIndex)
        assertEquals(3120, complete.expectedSamples)
        assertEquals(3120, complete.receivedSamples)
        assertEquals("SPECTRAL_ONLY", complete.quality)
        assertNotNull(complete.componentsJson)
        val components = JSONArray(requireNotNull(complete.componentsJson))
        assertTrue("default Analyse output must contain spectral tones", components.length() > 0)
        assertTrue("spectral evidence must expose frequencies", JSONObject(components.getString(0)).has("frequencyHz"))
        assertTrue("spectral evidence must expose amplitudes", JSONObject(components.getString(0)).has("amplitude"))

        val tail = initial[1]
        assertEquals(3120L, tail.startSampleIndex)
        assertEquals(20, tail.receivedSamples)
        assertEquals("INCOMPLETE", tail.quality)
        assertTrue(tail.reason.orEmpty().contains("PARTIAL_WINDOW"))
        assertEquals(314, recordingDao.readPackets(id, -1L, 400).size)

        // A new owner simulates process restart and replays the same terminal archive.
        SignalAnalysisRepository(recordingDao, epochDao).reconcile()
        val replayed = epochDao.readEpochs(id)
        assertEquals(initial, replayed)
    }

    @Test
    fun deviceCounterGapInvalidatesAffectedEpochButKeepsRawArchive() = runBlocking {
        val id = seedCapture(packetCount = 314, config = FirmwareSimulatorConfig(counterJumpEveryNPackets = 20))
        val before = database.recordingDao.readPackets(id, -1L, 400)
        SignalAnalysisRepository(database.recordingDao, database.signalEpochDao).reconcile()

        val epochs = database.signalEpochDao.readEpochs(id)
        assertTrue("a counter discontinuity must produce an epoch", epochs.isNotEmpty())
        assertTrue("counter gaps must not be presented as spectral data", epochs.any { it.quality == "REJECTED" })
        assertTrue(epochs.any { it.reason.orEmpty().contains("MISSING_DEVICE_SAMPLES") })
        val after = database.recordingDao.readPackets(id, -1L, 400)
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (left, right) ->
            assertEquals(left.sequence, right.sequence)
            assertTrue(left.payload.contentEquals(right.payload))
        }
    }

    @Test
    fun throwingEstimatorPersistsAnalysisErrorAndRetainsRawPackets() = runBlocking {
        val id = seedCapture(packetCount = 314)
        val before = database.recordingDao.readPackets(id, -1L, 400)
        val analysis = SignalAnalysisRepository(database.recordingDao, database.signalEpochDao) {
            throw IllegalStateException("injected estimator failure")
        }

        analysis.reconcile()

        val epochs = database.signalEpochDao.readEpochs(id)
        assertEquals("ANALYSIS_ERROR", epochs.first().quality)
        assertEquals("injected estimator failure", epochs.first().reason)
        assertEquals(3120, epochs.first().receivedSamples)
        assertTrue(epochs.first().componentsJson == null)
        val after = database.recordingDao.readPackets(id, -1L, 400)
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (left, right) -> assertTrue(left.payload.contentEquals(right.payload)) }
    }

    @Test(timeout = 30_000)
    fun blockedAnalysisDoesNotBlockSimulatedRecordingCommitOrStop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val estimatorEntered = CountDownLatch(1)
        val releaseEstimator = CountDownLatch(1)
        val client = SimulatedBluetoothClient(
            SimulatedTransportConfig(
                firmware = FirmwareSimulatorConfig(eogPulseEverySamples = 0),
                packetIntervalMillis = 1,
                scanResultDelayMillis = 0,
                connectDelayMillis = 0,
                serviceDiscoveryDelayMillis = 0,
                subscriptionDelayMillis = 0,
                disconnectDelayMillis = 0,
            ),
        )
        val bluetooth = DefaultBluetoothRepository(FlowBluetoothDataSource(client), scope)
        val recording = RecordingSessionRepository(database.recordingDao, bluetooth, scope)
        val analysis = SignalAnalysisRepository(database.recordingDao, database.signalEpochDao) {
            estimatorEntered.countDown()
            check(releaseEstimator.await(10, TimeUnit.SECONDS)) { "test did not release injected estimator" }
            SignalAnalysisRepository.Evidence("[]", 600, 0.5, false)
        }
        val analysisJob = analysis.follow(scope, recording.state)
        try {
            val sessionId = recording.start(client.device, SIMULATED_SERVICE_UUID, SIMULATED_CHARACTERISTIC_UUID).getOrThrow()
            assertTrue("simulated packets should reach one complete epoch", estimatorEntered.await(12, TimeUnit.SECONDS))

            // The estimator is blocked on Dispatchers.Default while the independent recording writer
            // continues to flush its batches and complete the lifecycle transition.
            withTimeout(5_000) {
                while (database.recordingDao.readPackets(sessionId, -1L, 500).size < 312) delay(10)
            }
            val packets = database.recordingDao.readPackets(sessionId, -1L, 500)
            assertTrue("raw packets must commit while estimation is blocked", packets.size >= 312)
            recording.stop()
            assertEquals(RecordingPhase.Completed, recording.state.value.phase)
            assertEquals("COMPLETED", database.recordingDao.observeSession(sessionId).first()!!.status)
        } finally {
            releaseEstimator.countDown()
            if (recording.state.value.phase == RecordingPhase.Recording) recording.stop()
            analysisJob.cancel()
            bluetooth.close()
            scope.cancel()
        }
    }

    @Test(timeout = 45_000)
    fun acquisitionServiceOwnsSimulatedRecordingAndAnalysisLifecycle() = runBlocking {
        val serviceContext = context
        val client = SimulatedBluetoothClient(
            SimulatedTransportConfig(
                firmware = FirmwareSimulatorConfig(eogPulseEverySamples = 0),
                packetIntervalMillis = 1,
                scanResultDelayMillis = 0,
                connectDelayMillis = 0,
                serviceDiscoveryDelayMillis = 0,
                subscriptionDelayMillis = 0,
                disconnectDelayMillis = 0,
            ),
        )
        TransportProvider.factory = { client }
        val connected = java.util.concurrent.CountDownLatch(1)
        var binder: AcquisitionService.LocalBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as AcquisitionService.LocalBinder
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val serviceIntent = Intent(serviceContext, AcquisitionService::class.java)
        var bound = false
        var sessionId: String? = null
        try {
            AcquisitionService.startForeground(serviceContext)
            bound = serviceContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            assertTrue("service bind should be accepted", bound)
            assertTrue("service binder should connect", connected.await(5, TimeUnit.SECONDS))
            val serviceBinder = requireNotNull(binder)
            val startJob = serviceBinder.startRecording(
                BleDevice(SIMULATED_DEVICE_ADDRESS, SIMULATED_DEVICE_NAME),
                SIMULATED_SERVICE_UUID,
                SIMULATED_CHARACTERISTIC_UUID,
            )
            withTimeout(10_000) { startJob.join() }
            @Suppress("UNCHECKED_CAST")
            val state = serviceBinder.state as kotlinx.coroutines.flow.StateFlow<RecordingState>
            withTimeout(10_000) { state.first { it.phase == RecordingPhase.Recording } }
            sessionId = state.value.sessionId
            assertNotNull(sessionId)

            withTimeout(20_000) {
                while (databaseForApp().recordingDao.readPackets(requireNotNull(sessionId), -1L, 400).size < 314) {
                    delay(10)
                }
            }
            withTimeout(10_000) { serviceBinder.stopRecording().join() }
            assertEquals(RecordingPhase.Completed, state.value.phase)
            val id = requireNotNull(sessionId)
            val packetCount = databaseForApp().recordingDao.readPackets(id, -1L, 10_000).size
            // stopRecording flushes raw input; the independent analysis follower catches up later.
            val epochs = withTimeout(15_000) {
                databaseForApp().signalEpochDao.observeEpochs(id).first { rows ->
                    rows.sumOf { it.receivedSamples } == packetCount * 10
                }
            }
            assertEquals((epochs.indices).map { it.toLong() }, epochs.map { it.epochIndex })
            assertEquals(3120, epochs[0].receivedSamples)
            assertEquals("SPECTRAL_ONLY", epochs[0].quality)
            val remainder = packetCount * 10 % 3120
            if (remainder > 0) {
                assertEquals(remainder, epochs.last().receivedSamples)
                assertEquals("INCOMPLETE", epochs.last().quality)
            }
        } finally {
            if (bound) runCatching { serviceContext.unbindService(connection) }
            serviceContext.stopService(serviceIntent)
            sessionId?.let { runCatching { databaseForApp().recordingDao.deleteSession(it) } }
            TransportProvider.reset()
        }
    }

    private fun databaseForApp(): RecordingDatabase = RecordingDatabase.open(context)

    private suspend fun seedCapture(
        packetCount: Int,
        config: FirmwareSimulatorConfig = FirmwareSimulatorConfig(),
    ): String {
        val id = "signal-${System.nanoTime()}"
        database.recordingDao.createSession(
            RecordingSessionEntity(
                id = id,
                deviceAddress = "simulated",
                deviceName = "FirmwarePacketSimulator",
                serviceUuid = SIMULATED_SERVICE_UUID.toString(),
                characteristicUuid = SIMULATED_CHARACTERISTIC_UUID.toString(),
                startedAtMillis = 1_000L,
            ),
        )
        val simulator = FirmwarePacketSimulator(config)
        val packets = List(packetCount) { sequence ->
            RawPacketEntity(
                sessionId = id,
                sequence = sequence.toLong(),
                receivedAtMillis = 1_000L + sequence * 96L,
                payload = requireNotNull(simulator.nextPacket()),
            )
        }
        database.recordingDao.insertPackets(packets)
        database.recordingDao.finishSession(id, "COMPLETED", 2_000L, null)
        return id
    }
}
