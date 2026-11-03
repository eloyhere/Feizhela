package com.feizhela.headband.recording

import com.feizhela.headband.bluetooth.BleConnectionState
import com.feizhela.headband.bluetooth.BleDevice
import com.feizhela.headband.bluetooth.BleEvent
import com.feizhela.headband.bluetooth.BleWriteType
import com.feizhela.headband.bluetooth.BluetoothRepository
import com.feizhela.headband.bluetooth.BluetoothSessionState
import com.feizhela.headband.bluetooth.RawBlePacket
import com.feizhela.headband.storage.AcquisitionGapEntity
import com.feizhela.headband.storage.RawPacketEntity
import com.feizhela.headband.storage.RecordingDao
import com.feizhela.headband.storage.RecordingSessionEntity
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the real [RecordingSessionRepository] batching pipeline with a synthetic, long
 * 104 Hz packet stream, without an Activity, a service, a real BLE link or the UI lifecycle.
 *
 * This is the "long simulated stream" evidence requested by framework.md Phase 2 (line 170):
 * every accepted packet must be archived exactly once, in sequence order, and a stopped
 * recording must flush the partial batch instead of losing the tail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedStreamTest {
    private val device = BleDevice("00:11:22:33:44:55", "Headband")
    private val serviceUuid = UUID.fromString("5aee1a8a-08de-11ed-861d-0242ac120002")
    private val characteristicUuid = UUID.fromString("405992d6-0cf2-11ed-861d-0242ac120002")
    private val otherServiceUuid = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val otherCharacteristicUuid = UUID.fromString("00000000-0000-0000-0000-0000000000bb")

    @Test
    fun continuous104HzStreamIsArchivedWithoutGapsAndStopFlushesResidual() =
        runTest(timeout = 60.seconds) {
            val dao = SimulatedRecordingDao()
            val bluetooth = SimulatedBluetoothRepository()
            val repository = RecordingSessionRepository(dao, bluetooth, backgroundScope)
            val sessionId = withTimeout(5_000) {
                repository.start(device, serviceUuid, characteristicUuid).getOrThrow()
            }

            // 3000 packets at 104 Hz, one virtual-time slice per notification. The repository's
            // 1 s idle flush timer never fires because virtual time is not advanced here, so the
            // only full batches written while recording are the 32-packet ones.
            repeat(PACKET_COUNT) { index ->
                bluetooth.emitPacket(RawBlePacket(serviceUuid, characteristicUuid, payload(index), timestamp(index)))
                runCurrent()
            }

            assertEquals(
                "Only complete batches may be written while the stream is still running",
                PACKET_COUNT / BATCH_SIZE,
                dao.insertedBatches.size,
            )
            assertEquals((PACKET_COUNT / BATCH_SIZE) * BATCH_SIZE, dao.insertedBatches.flatten().size)
            assertEquals(RecordingPhase.Recording, repository.state.value.phase)
            assertEquals("ACTIVE", dao.session(sessionId).status)

            withTimeout(5_000) { repository.stop() }

            val batches = dao.insertedBatches
            val expectedBatchCount = (PACKET_COUNT + BATCH_SIZE - 1) / BATCH_SIZE // ceil(3000 / 32) = 94
            assertEquals(expectedBatchCount, batches.size)
            assertEquals(PACKET_COUNT, batches.flatten().size)
            assertEquals(
                "sequence must be dense and contiguous from 0 to 2999",
                (0L until PACKET_COUNT).toList(),
                batches.flatten().map { it.sequence },
            )
            batches.flatten().forEachIndexed { index, packet ->
                assertEquals(sessionId, packet.sessionId)
                assertEquals(timestamp(index), packet.receivedAtMillis)
                assertArrayEquals(payload(index), packet.payload)
            }
            // 3000 % 32 == 24: stop() must have flushed exactly that leftover partial batch.
            val residual = PACKET_COUNT % BATCH_SIZE
            assertTrue("fixture expects a partial batch after the last full batch", residual > 0)
            assertEquals(residual, batches.last().size)
            batches.dropLast(1).forEach { batch -> assertEquals(BATCH_SIZE, batch.size) }

            assertEquals(RecordingPhase.Completed, repository.state.value.phase)
            assertEquals(PACKET_COUNT.toLong(), repository.state.value.packetCount)
            assertEquals("COMPLETED", dao.session(sessionId).status)
            assertNotNull(dao.session(sessionId).endedAtMillis)
            assertNull(dao.session(sessionId).endReason)
        }

    @Test
    fun packetsForOtherUuidsAreIgnoredAndDoNotConsumeSequence() = runTest(timeout = 30.seconds) {
        val dao = SimulatedRecordingDao()
        val bluetooth = SimulatedBluetoothRepository()
        val repository = RecordingSessionRepository(dao, bluetooth, backgroundScope)
        withTimeout(5_000) { repository.start(device, serviceUuid, characteristicUuid).getOrThrow() }

        val acceptedTimestamps = mutableListOf<Long>()
        var clock = 1_000L
        // Every accepted packet is preceded by notifications for a different service and a
        // different characteristic of the same service; both must be dropped before sequencing.
        repeat(10) { index ->
            bluetooth.emitPacket(
                RawBlePacket(otherServiceUuid, characteristicUuid, byteArrayOf(0x7F), clock++),
            )
            runCurrent()
            bluetooth.emitPacket(
                RawBlePacket(serviceUuid, otherCharacteristicUuid, byteArrayOf(0x7E), clock++),
            )
            runCurrent()
            acceptedTimestamps += clock
            bluetooth.emitPacket(
                RawBlePacket(serviceUuid, characteristicUuid, byteArrayOf(index.toByte()), clock++),
            )
            runCurrent()
        }

        withTimeout(5_000) { repository.stop() }

        val archived = dao.insertedBatches.flatten()
        assertEquals(10, archived.size)
        assertEquals((0L until 10L).toList(), archived.map { it.sequence })
        assertEquals(acceptedTimestamps, archived.map { it.receivedAtMillis })
        assertTrue(
            "foreign UUID notifications must never be archived",
            archived.none { it.payload.contentEquals(byteArrayOf(0x7F)) || it.payload.contentEquals(byteArrayOf(0x7E)) },
        )
        assertEquals(10L, repository.state.value.packetCount)
        assertEquals(RecordingPhase.Completed, repository.state.value.phase)
    }

    private fun timestamp(index: Int): Long = index * 1_000L / SAMPLE_RATE_HZ

    private fun payload(index: Int): ByteArray =
        byteArrayOf((index % 251).toByte(), (index / 251).toByte(), -1)

    private companion object {
        const val SAMPLE_RATE_HZ = 104
        const val PACKET_COUNT = 3_000
        const val BATCH_SIZE = 32
    }

    private class SimulatedRecordingDao : RecordingDao {
        private val sessions = MutableStateFlow<List<RecordingSessionEntity>>(emptyList())
        val insertedBatches = mutableListOf<List<RawPacketEntity>>()

        fun session(id: String): RecordingSessionEntity = sessions.value.single { it.id == id }

        override suspend fun createSession(session: RecordingSessionEntity) {
            check(sessions.value.none { it.id == session.id })
            sessions.value = sessions.value + session
        }

        override suspend fun insertPackets(packets: List<RawPacketEntity>) {
            insertedBatches += packets.map { it.copy(payload = it.payload.copyOf()) }
        }

        override suspend fun finishSession(
            id: String,
            status: String,
            endedAtMillis: Long,
            reason: String?,
        ) {
            sessions.value = sessions.value.map {
                if (it.id == id) it.copy(status = status, endedAtMillis = endedAtMillis, endReason = reason) else it
            }
        }

        override suspend fun interruptActiveSessions(endedAtMillis: Long, reason: String) {
            sessions.value = sessions.value.map {
                if (it.status == "ACTIVE") {
                    it.copy(status = "INTERRUPTED", endedAtMillis = endedAtMillis, endReason = reason)
                } else it
            }
        }

        override fun observeSessions(): Flow<List<RecordingSessionEntity>> = sessions

        override fun observeSession(id: String): Flow<RecordingSessionEntity?> =
            flowOf(sessions.value.firstOrNull { it.id == id })

        override suspend fun readPackets(
            sessionId: String,
            afterSequence: Long,
            limit: Int,
        ): List<RawPacketEntity> = insertedBatches.flatten()
            .filter { it.sessionId == sessionId && it.sequence > afterSequence }
            .sortedBy { it.sequence }
            .take(limit)

        override suspend fun deleteSession(id: String) {
            sessions.value = sessions.value.filterNot { it.id == id }
            val retained = insertedBatches.map { batch -> batch.filterNot { it.sessionId == id } }
            insertedBatches.clear()
            insertedBatches.addAll(retained.filter { it.isNotEmpty() })
        }

        // The long-stream test never disconnects, so no gap is expected. These overrides keep the
        // fake compilable against RecordingDao and prove that "no gap" stays observable as empty.
        override suspend fun insertGap(gap: AcquisitionGapEntity): Long = 0L

        override fun observeGaps(sessionId: String): Flow<List<AcquisitionGapEntity>> = flowOf(emptyList())

        override suspend fun readGaps(sessionId: String): List<AcquisitionGapEntity> = emptyList()

        override suspend fun deleteGaps(sessionId: String) = Unit

        override suspend fun countGaps(sessionId: String): Int = 0
    }

    private class SimulatedBluetoothRepository : BluetoothRepository {
        override val state = MutableStateFlow(BluetoothSessionState())
        override val discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
        override val rawPackets = MutableSharedFlow<RawBlePacket>(extraBufferCapacity = 256)
        private val controlEvents = MutableSharedFlow<BleEvent>(extraBufferCapacity = 64)
        override val events = controlEvents
        private val subscriptionRequestId = 7_000_000_001L
        private var lastSubscription: Pair<UUID, UUID>? = null

        override fun startScan(serviceUuids: Set<UUID>): Result<Unit> = Result.success(Unit)

        override fun stopScan() = Unit

        override fun connect(device: BleDevice): Result<Unit> {
            state.value = BluetoothSessionState(
                connection = BleConnectionState.Connected,
                connectedDevice = device,
                servicesDiscovered = true,
            )
            return Result.success(Unit)
        }

        override fun disconnect() {
            state.value = BluetoothSessionState(connection = BleConnectionState.Idle)
        }

        override fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> {
            lastSubscription = serviceUuid to characteristicUuid
            val (service, characteristic) = requireNotNull(lastSubscription)
            check(
                controlEvents.tryEmit(
                    BleEvent.SubscriptionCompleted(subscriptionRequestId, service, characteristic, true),
                ),
            )
            return Result.success(subscriptionRequestId)
        }

        fun emitPacket(packet: RawBlePacket) {
            check(rawPackets.subscriptionCount.value > 0) {
                "Recording must install its raw packet consumer before the stream starts"
            }
            check(rawPackets.tryEmit(packet)) { "Simulated stream buffer overflowed" }
        }

        override fun read(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> =
            Result.success(1L)

        override fun write(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            value: ByteArray,
            writeType: BleWriteType,
        ): Result<Long> = Result.success(2L)

        override fun close() {
            state.value = BluetoothSessionState(connection = BleConnectionState.Closed)
        }
    }
}
