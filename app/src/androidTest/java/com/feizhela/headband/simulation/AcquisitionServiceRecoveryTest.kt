package com.feizhela.headband.simulation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feizhela.headband.bluetooth.BleDevice
import com.feizhela.headband.bluetooth.BleEvent
import com.feizhela.headband.bluetooth.BluetoothClient
import com.feizhela.headband.bluetooth.TransportProvider
import com.feizhela.headband.recording.AcquisitionService
import com.feizhela.headband.recording.RecordingPhase
import com.feizhela.headband.recording.RecordingState
import com.feizhela.headband.storage.RawPacketEntity
import com.feizhela.headband.storage.RecordingDatabase
import com.feizhela.headband.storage.SignalEpochEntity
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android service ownership with the debug transport, using only this test's session rows. */
@RunWith(AndroidJUnit4::class)
class AcquisitionServiceRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val database: RecordingDatabase get() = RecordingDatabase.open(context)
    private val intent: Intent get() = Intent(context, AcquisitionService::class.java)

    @Test(timeout = 90_000)
    fun stopServiceInterruptsRecordingAndFreshOwnerPreservesArchiveWithoutResumingBle() = runBlocking {
        val clients = CopyOnWriteArrayList<ObservedTransport>()
        val bindings = mutableListOf<Binding>()
        val ownedSessionIds = linkedSetOf<String>()
        TransportProvider.factory = { ObservedTransport().also { clients += it } }
        try {
            val firstBinding = startAndBind().also { bindings += it }
            assertEquals("A fresh service must create our debug transport", 1, clients.size)
            val firstClient = clients.single()
            val firstBinder = firstBinding.binder
            val firstState = recordingState(firstBinder)
            startRecording(firstBinder)
            val recording = withTimeout(WAIT_MILLIS) {
                firstState.first { it.phase == RecordingPhase.Recording }
            }
            val interruptedId = requireNotNull(recording.sessionId)
            ownedSessionIds += interruptedId

            // Select a boundary from actual transport progress, not a scheduled packet count. Stop
            // forwarding at a partial epoch, then await the recorder's durable acknowledgement.
            // This keeps the terminal-tail assertion deterministic even on a heavily loaded device.
            val firstPayloads = firstClient.pauseAfterFullEpochAndPartialTail()
            withTimeout(WAIT_MILLIS) {
                firstState.first { it.packetCount == firstPayloads.size.toLong() }
            }
            assertEquals(RecordingPhase.Recording, firstState.value.phase)
            assertEquals("ACTIVE", database.recordingDao.observeSession(interruptedId).first()!!.status)

            firstBinding.unbind()
            assertTrue("The recording must still own a started-service lifetime after unbind", context.stopService(intent))
            val interrupted = withTimeout(WAIT_MILLIS) {
                database.recordingDao.observeSession(interruptedId).first { it?.status == "INTERRUPTED" }
            }!!
            assertEquals("SERVICE_DESTROYED", interrupted.endReason)
            assertTrue(requireNotNull(interrupted.endedAtMillis) >= interrupted.startedAtMillis)
            // onDestroy launches asynchronous finalization: Android main-thread idleness alone
            // does not prove that the recording writer, analysis, and transport have finished.
            firstClient.awaitClosed()
            val terminalState = firstState.value
            assertEquals(RecordingPhase.Interrupted, terminalState.phase)
            assertEquals("SERVICE_DESTROYED", terminalState.reason)
            assertEquals(firstPayloads.size.toLong(), terminalState.packetCount)
            assertEquals(0, terminalState.metrics.queuedPackets)
            assertEquals(terminalState.packetCount, terminalState.metrics.writtenPackets)
            val archivedPackets = readAllPackets(interruptedId)
            assertArchive(interruptedId, firstPayloads, archivedPackets)
            val archivedEpochs = awaitEpochCoverage(interruptedId, archivedPackets.size)
            assertEpochCoverage(archivedPackets, archivedEpochs)

            val freshBinding = startAndBind().also { bindings += it }
            assertEquals("Recreation must request a new transport", 2, clients.size)
            val freshClient = clients.last()
            val freshBinder = freshBinding.binder
            assertNotSame("Rebinding must not return the destroyed service's binder", firstBinder, freshBinder)
            assertNotSame(firstClient, freshClient)
            assertTrue(firstClient.simulator.isClosed)
            assertFalse(freshClient.simulator.isClosed)

            // This idle no-op awaits onCreate's recovery without connecting a device. Analysis
            // progress is conflated and can move to another archived session, so it is not a
            // reliable startup barrier when the test device already contains other captures.
            withTimeout(WAIT_MILLIS) { freshBinder.stopRecording().join() }
            instrumentation.waitForIdleSync()
            val freshState = recordingState(freshBinder)
            assertEquals(RecordingPhase.Idle, freshState.value.phase)
            assertNull(freshState.value.sessionId)
            assertNull(freshState.value.reason)
            assertEquals(0L, freshState.value.packetCount)
            assertEquals("Fresh service must not scan automatically", 0, freshClient.scanCalls.get())
            assertEquals("Fresh service must not reconnect automatically", 0, freshClient.connectCalls.get())
            assertEquals("Fresh service must not resubscribe automatically", 0, freshClient.subscribeCalls.get())
            assertEquals(0, freshClient.simulator.notificationCount)
            assertEquals(interrupted, database.recordingDao.observeSession(interruptedId).first())
            assertSameArchive(archivedPackets, readAllPackets(interruptedId))
            assertEquals(archivedEpochs, database.signalEpochDao.readEpochs(interruptedId))

            // An idle recreated owner remains usable; the new capture has its own UUID and starts
            // its packet sequence at zero rather than appending to the interrupted archive.
            startRecording(freshBinder)
            val newRecording = withTimeout(WAIT_MILLIS) {
                freshState.first { it.phase == RecordingPhase.Recording }
            }
            val newId = requireNotNull(newRecording.sessionId)
            ownedSessionIds += newId
            assertTrue(interruptedId != newId)
            val newPayloads = freshClient.pauseAfterFullEpochAndPartialTail()
            withTimeout(WAIT_MILLIS) {
                freshState.first { it.packetCount == newPayloads.size.toLong() }
            }
            withTimeout(WAIT_MILLIS) { freshBinder.stopRecording().join() }
            assertEquals(RecordingPhase.Completed, freshState.value.phase)
            assertEquals("COMPLETED", database.recordingDao.observeSession(newId).first()!!.status)
            val newPackets = readAllPackets(newId)
            assertArchive(newId, newPayloads, newPackets)
            // Joining stopRecording only flushes the recorder. Await independent analysis too.
            assertEpochCoverage(newPackets, awaitEpochCoverage(newId, newPackets.size))
            assertSameArchive(archivedPackets, readAllPackets(interruptedId))
            assertEquals(archivedEpochs, database.signalEpochDao.readEpochs(interruptedId))

            freshBinding.unbind()
            assertTrue(context.stopService(intent))
            freshClient.awaitClosed()
        } finally {
            withContext(NonCancellable) {
                // Capture IDs from our own binders even when an assertion fails during startup.
                bindings.forEach { binding ->
                    recordingState(binding.binder).value.sessionId?.let { ownedSessionIds += it }
                    binding.unbind()
                }
                context.stopService(intent)
                try {
                    // Never delete rows while this service may still flush or analyze their input.
                    clients.forEach { it.awaitClosed() }
                    ownedSessionIds.forEach { database.recordingDao.deleteSession(it) }
                } finally {
                    TransportProvider.reset()
                }
            }
        }
    }

    private suspend fun startAndBind(): Binding {
        val binding = Binding()
        AcquisitionService.startForeground(context)
        binding.bound = context.bindService(intent, binding, Context.BIND_AUTO_CREATE)
        assertTrue("AcquisitionService bind must be accepted", binding.bound)
        try {
            binding.binder = withTimeout(WAIT_MILLIS) { binding.connected.await() }
            return binding
        } catch (error: Throwable) {
            binding.unbind()
            throw error
        }
    }

    private suspend fun startRecording(binder: AcquisitionService.LocalBinder) {
        withTimeout(WAIT_MILLIS) {
            binder.startRecording(
                BleDevice(SIMULATED_DEVICE_ADDRESS, SIMULATED_DEVICE_NAME),
                SIMULATED_SERVICE_UUID,
                SIMULATED_CHARACTERISTIC_UUID,
            ).join()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun recordingState(binder: AcquisitionService.LocalBinder): StateFlow<RecordingState> =
        binder.state as StateFlow<RecordingState>

    private suspend fun readAllPackets(id: String): List<RawPacketEntity> {
        val packets = mutableListOf<RawPacketEntity>()
        while (true) {
            val page = database.recordingDao.readPackets(id, packets.lastOrNull()?.sequence ?: -1L, PAGE_SIZE)
            if (page.isEmpty()) return packets
            packets += page
        }
    }

    private suspend fun awaitEpochCoverage(id: String, packetCount: Int): List<SignalEpochEntity> =
        withTimeout(WAIT_MILLIS) {
            database.signalEpochDao.observeEpochs(id).first { epochs ->
                epochs.sumOf { it.receivedSamples.toLong() } == packetCount.toLong() * SAMPLES_PER_PACKET
            }
        }

    private fun assertArchive(id: String, payloads: List<ByteArray>, packets: List<RawPacketEntity>) {
        assertEquals(payloads.size, packets.size)
        val expected = FirmwarePacketSimulator(FIRMWARE)
        packets.forEachIndexed { index, packet ->
            assertEquals(id, packet.sessionId)
            assertEquals("Archive sequences must be contiguous from zero", index.toLong(), packet.sequence)
            assertEquals(FirmwarePacketSimulator.PACKET_BYTES, packet.payload.size)
            assertArrayEquals("Transport payload $index must survive unchanged", payloads[index], packet.payload)
            assertArrayEquals("Firmware counter/waveform continuity at packet $index", expected.nextPacket(), packet.payload)
        }
    }

    private fun assertSameArchive(before: List<RawPacketEntity>, after: List<RawPacketEntity>) {
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (left, right) ->
            assertEquals(left.sessionId, right.sessionId)
            assertEquals(left.sequence, right.sequence)
            assertEquals(left.receivedAtMillis, right.receivedAtMillis)
            assertArrayEquals(left.payload, right.payload)
        }
    }

    private fun assertEpochCoverage(packets: List<RawPacketEntity>, epochs: List<SignalEpochEntity>) {
        val sampleCount = packets.size.toLong() * SAMPLES_PER_PACKET
        val remainder = (sampleCount % EPOCH_SAMPLES).toInt()
        assertTrue("The capture must contain a full epoch and a partial terminal tail", sampleCount > EPOCH_SAMPLES && remainder > 0)
        assertEquals((sampleCount + EPOCH_SAMPLES - 1) / EPOCH_SAMPLES, epochs.size.toLong())
        assertEquals(sampleCount, epochs.sumOf { it.receivedSamples.toLong() })
        epochs.forEachIndexed { index, epoch ->
            val firstSample = index.toLong() * EPOCH_SAMPLES
            assertEquals(index.toLong(), epoch.epochIndex)
            assertEquals(firstSample, epoch.startSampleIndex)
            assertEquals(firstSample / SAMPLES_PER_PACKET, epoch.firstPacketSequence)
            assertEquals((firstSample + epoch.receivedSamples - 1) / SAMPLES_PER_PACKET, epoch.lastPacketSequence)
            assertEquals(EPOCH_SAMPLES, epoch.expectedSamples)
            if (index < epochs.lastIndex) {
                assertEquals(EPOCH_SAMPLES, epoch.receivedSamples)
                assertEquals("SPECTRAL_ONLY", epoch.quality)
            }
        }
        assertEquals(remainder, epochs.last().receivedSamples)
        assertEquals("INCOMPLETE", epochs.last().quality)
        assertTrue(epochs.last().reason.orEmpty().contains("PARTIAL_WINDOW"))
        assertEquals(packets.last().sequence, epochs.last().lastPacketSequence)
    }

    private inner class Binding : ServiceConnection {
        val connected = CompletableDeferred<AcquisitionService.LocalBinder>()
        lateinit var binder: AcquisitionService.LocalBinder
        var bound = false

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val local = service as? AcquisitionService.LocalBinder
            if (local != null) connected.complete(local)
            else connected.completeExceptionally(AssertionError("Unexpected acquisition binder: $service"))
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit

        fun unbind() {
            if (bound) {
                context.unbindService(this)
                bound = false
            }
        }
    }

    /** Observes the transport callback; the recorder remains the only rawPackets subscriber. */
    private class ObservedTransport(
        val simulator: SimulatedBluetoothClient = SimulatedBluetoothClient(
            SimulatedTransportConfig(
                firmware = FIRMWARE,
                packetIntervalMillis = 2,
                scanResultDelayMillis = 0,
                connectDelayMillis = 0,
                serviceDiscoveryDelayMillis = 0,
                subscriptionDelayMillis = 0,
                disconnectDelayMillis = 0,
            ),
        ),
    ) : BluetoothClient by simulator {
        private val lock = Any()
        private val closed = CompletableDeferred<Unit>()
        private val payloads = mutableListOf<ByteArray>()
        private var paused = false
        val scanCalls = AtomicInteger()
        val connectCalls = AtomicInteger()
        val subscribeCalls = AtomicInteger()

        override fun setListener(listener: ((BleEvent) -> Unit)?) {
            simulator.setListener(if (listener == null) null else { event ->
                synchronized(lock) {
                    if (event !is BleEvent.NotificationReceived) listener(event)
                    else if (!paused) {
                        payloads += event.value.copyOf()
                        listener(event)
                    }
                }
            })
        }

        override fun startScan(serviceUuids: Set<UUID>): Result<Unit> {
            scanCalls.incrementAndGet()
            return simulator.startScan(serviceUuids)
        }

        override fun connect(device: BleDevice): Result<Unit> {
            connectCalls.incrementAndGet()
            return simulator.connect(device)
        }

        override fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> {
            subscribeCalls.incrementAndGet()
            return simulator.subscribe(serviceUuid, characteristicUuid)
        }

        suspend fun pauseAfterFullEpochAndPartialTail(): List<ByteArray> = withTimeout(WAIT_MILLIS) {
            while (true) {
                val captured = synchronized(lock) {
                    val samples = payloads.size.toLong() * SAMPLES_PER_PACKET
                    if (samples > EPOCH_SAMPLES && samples % EPOCH_SAMPLES != 0L) {
                        paused = true
                        payloads.map { it.copyOf() }
                    } else null
                }
                if (captured != null) return@withTimeout captured
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        }

        override fun close() {
            try {
                simulator.close()
            } finally {
                closed.complete(Unit)
            }
        }

        suspend fun awaitClosed() {
            withTimeout(WAIT_MILLIS) { closed.await() }
        }
    }

    private companion object {
        const val WAIT_MILLIS = 15_000L
        const val PAGE_SIZE = 128
        const val EPOCH_SAMPLES = 3120
        const val SAMPLES_PER_PACKET = FirmwarePacketSimulator.SAMPLES_PER_PACKET
        val FIRMWARE = FirmwareSimulatorConfig(eogPulseEverySamples = 0)
    }
}
