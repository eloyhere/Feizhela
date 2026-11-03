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
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSessionRepositoryTest {
    private val device = BleDevice("00:11:22:33:44:55", "Headband")
    private val serviceUuid = UUID.fromString("5aee1a8a-08de-11ed-861d-0242ac120002")
    private val characteristicUuid = UUID.fromString("405992d6-0cf2-11ed-861d-0242ac120002")

    @Test
    fun startWaitsForMatchingSubscriptionCompletion() = runTest(timeout = 10.seconds) {
        val dao = FakeRecordingDao()
        val bluetooth = FakeBluetoothRepository().apply { completeSubscriptionSynchronously = false }
        val repository = RecordingSessionRepository(dao, bluetooth, backgroundScope)
        val starting = async {
            withTimeout(5_000) { repository.start(device, serviceUuid, characteristicUuid) }
        }
        runCurrent()

        assertFalse(starting.isCompleted)
        assertEquals(RecordingPhase.Starting, repository.state.value.phase)
        assertEquals(serviceUuid to characteristicUuid, bluetooth.lastSubscription)
        bluetooth.completeSubscription(requestId = bluetooth.subscriptionRequestId + 1)
        runCurrent()
        assertFalse(starting.isCompleted)
        bluetooth.completeSubscription()
        runCurrent()

        val sessionId = starting.await().getOrThrow()
        assertEquals(RecordingPhase.Recording, repository.state.value.phase)
        assertEquals(sessionId, repository.state.value.sessionId)
        assertEquals("ACTIVE", dao.session(sessionId).status)
        assertEquals(device.address, dao.session(sessionId).deviceAddress)
        assertEquals(serviceUuid.toString(), dao.session(sessionId).serviceUuid)
        assertEquals(characteristicUuid.toString(), dao.session(sessionId).characteristicUuid)
        withTimeout(5_000) { repository.stop() }
    }

    @Test
    fun startReceivesCompletionEmittedSynchronouslyInsideSubscribe() = runTest(timeout = 10.seconds) {
        val dao = FakeRecordingDao()
        val bluetooth = FakeBluetoothRepository()
        val repository = RecordingSessionRepository(dao, bluetooth, backgroundScope)

        val sessionId = withTimeout(5_000) {
            repository.start(device, serviceUuid, characteristicUuid).getOrThrow()
        }

        assertEquals(RecordingPhase.Recording, repository.state.value.phase)
        assertEquals(sessionId, repository.state.value.sessionId)
        assertEquals(listOf("connect", "subscribe"), bluetooth.calls)
        assertTrue(bluetooth.subscriberPresentWhenSubscriptionCompleted)
        withTimeout(5_000) { repository.stop() }
    }

    @Test
    fun packetsAreBatchedAndStopFlushesThePartialBatch() = runTest(timeout = 10.seconds) {
        val dao = FakeRecordingDao()
        val bluetooth = FakeBluetoothRepository()
        val repository = RecordingSessionRepository(dao, bluetooth, backgroundScope)
        val sessionId = withTimeout(5_000) {
            repository.start(device, serviceUuid, characteristicUuid).getOrThrow()
        }
        repeat(33) { index ->
            bluetooth.emitPacket(
                RawBlePacket(serviceUuid, characteristicUuid, byteArrayOf(index.toByte(), -1), 1_000L + index),
            )
            runCurrent()
        }

        assertEquals(listOf(32), dao.insertedBatches.map { it.size })
        assertEquals(32L, repository.state.value.packetCount)
        withTimeout(5_000) { repository.stop() }

        assertEquals(listOf(32, 1), dao.insertedBatches.map { it.size })
        val packets = dao.insertedBatches.flatten()
        assertEquals((0L..32L).toList(), packets.map { it.sequence })
        packets.forEachIndexed { index, packet ->
            assertEquals(sessionId, packet.sessionId)
            assertEquals(1_000L + index, packet.receivedAtMillis)
            assertArrayEquals(byteArrayOf(index.toByte(), -1), packet.payload)
        }
        assertEquals(RecordingPhase.Completed, repository.state.value.phase)
        assertEquals(33L, repository.state.value.packetCount)
        assertEquals("COMPLETED", dao.session(sessionId).status)
        assertNotNull(dao.session(sessionId).endedAtMillis)
        assertNull(dao.session(sessionId).endReason)
        assertTrue(dao.calls.indexOfLast { it == "insertPackets" } < dao.calls.indexOf("finishSession"))
    }

    @Test
    fun connectFailureFinishesCreatedSessionAsFailed() = runTest(timeout = 10.seconds) {
        val dao = FakeRecordingDao()
        val bluetooth = FakeBluetoothRepository().apply {
            connectFailure = IOException("Bluetooth connection denied")
        }
        val repository = RecordingSessionRepository(dao, bluetooth, backgroundScope)

        val result = withTimeout(5_000) { repository.start(device, serviceUuid, characteristicUuid) }

        assertTrue(result.isFailure)
        assertEquals(RecordingPhase.Failed, repository.state.value.phase)
        val sessionId = requireNotNull(repository.state.value.sessionId)
        assertEquals("FAILED", dao.session(sessionId).status)
        assertNotNull(dao.session(sessionId).endedAtMillis)
        assertTrue(dao.session(sessionId).endReason.orEmpty().contains("Bluetooth connection denied"))
        assertEquals(dao.session(sessionId).endReason, repository.state.value.reason)
        assertFalse(bluetooth.calls.contains("subscribe"))
        assertTrue(bluetooth.calls.contains("disconnect"))
    }

    @Test
    fun startIgnoresPreConnectIdleUntilTheTransportReacts() = runTest(timeout = 10.seconds) {
        val dao = FakeRecordingDao()
        val bluetooth = FakeBluetoothRepository().apply { transportReactsAsynchronously = true }
        val repository = RecordingSessionRepository(dao, bluetooth, backgroundScope)

        val started = async { repository.start(device, serviceUuid, characteristicUuid) }
        runCurrent()
        // A real transport has not reacted yet, so the repository still reports its initial Idle.
        assertEquals(RecordingPhase.Starting, repository.state.value.phase)

        bluetooth.state.value = BluetoothSessionState(
            connection = BleConnectionState.Connecting,
            connectedDevice = device,
            servicesDiscovered = false,
        )
        runCurrent()
        assertEquals(
            "the pre-connect Idle must not be mistaken for a lost link",
            RecordingPhase.Starting,
            repository.state.value.phase,
        )

        bluetooth.state.value = BluetoothSessionState(
            connection = BleConnectionState.Connected,
            connectedDevice = device,
            servicesDiscovered = true,
        )
        runCurrent()

        val result = withTimeout(5_000) { started.await() }
        assertTrue(result.isSuccess)
        assertEquals(RecordingPhase.Recording, repository.state.value.phase)
        withTimeout(5_000) { repository.stop() }
    }

    @Test
    fun packetWriteFailureIsPersistedAndStopDoesNotOverwriteIt() = runTest(timeout = 10.seconds) {
        val dao = FakeRecordingDao().apply { insertFailure = IOException("Disk is full") }
        val bluetooth = FakeBluetoothRepository()
        val repository = RecordingSessionRepository(dao, bluetooth, backgroundScope)
        val sessionId = withTimeout(5_000) {
            repository.start(device, serviceUuid, characteristicUuid).getOrThrow()
        }
        bluetooth.emitPacket(RawBlePacket(serviceUuid, characteristicUuid, byteArrayOf(1), 10L))
        runCurrent()
        withTimeout(5_000) { repository.stop() }
        runCurrent()

        assertEquals(RecordingPhase.Failed, repository.state.value.phase)
        assertTrue(repository.state.value.reason.orEmpty().contains("Disk is full"))
        assertEquals("FAILED", dao.session(sessionId).status)
        assertEquals(repository.state.value.reason, dao.session(sessionId).endReason)
        assertTrue(dao.insertedBatches.isEmpty())
        assertTrue(bluetooth.calls.contains("disconnect"))
        assertFalse(dao.finishedStatuses.contains("COMPLETED"))
        withTimeout(5_000) { repository.stop() }
        assertEquals("FAILED", dao.session(sessionId).status)
    }

    @Test
    fun fullBatchWriteFailureStopsAnActiveRecording() = runTest(timeout = 10.seconds) {
        val dao = FakeRecordingDao().apply { insertFailure = IOException("Storage unavailable") }
        val bluetooth = FakeBluetoothRepository()
        val repository = RecordingSessionRepository(dao, bluetooth, backgroundScope)
        val sessionId = withTimeout(5_000) {
            repository.start(device, serviceUuid, characteristicUuid).getOrThrow()
        }
        repeat(32) { index ->
            bluetooth.emitPacket(RawBlePacket(serviceUuid, characteristicUuid, byteArrayOf(index.toByte()), index.toLong()))
            runCurrent()
        }

        assertEquals(RecordingPhase.Failed, repository.state.value.phase)
        assertTrue(repository.state.value.reason.orEmpty().contains("Storage unavailable"))
        assertEquals("FAILED", dao.session(sessionId).status)
        assertEquals(repository.state.value.reason, dao.session(sessionId).endReason)
        assertTrue(dao.insertedBatches.isEmpty())
        assertTrue(bluetooth.calls.contains("disconnect"))
        withTimeout(5_000) { repository.stop() }
        assertFalse(dao.finishedStatuses.contains("COMPLETED"))
    }

    @Test
    fun disconnectInterruptsRecordingAndPersistsItsReason() = runTest(timeout = 10.seconds) {
        val dao = FakeRecordingDao()
        val bluetooth = FakeBluetoothRepository()
        val repository = RecordingSessionRepository(dao, bluetooth, backgroundScope)
        val sessionId = withTimeout(5_000) {
            repository.start(device, serviceUuid, characteristicUuid).getOrThrow()
        }
        bluetooth.emitPacket(RawBlePacket(serviceUuid, characteristicUuid, byteArrayOf(7), 11L))
        runCurrent()

        bluetooth.loseConnection("Radio link lost")
        runCurrent()

        assertEquals(RecordingPhase.Interrupted, repository.state.value.phase)
        assertEquals(sessionId, repository.state.value.sessionId)
        assertTrue(repository.state.value.reason.orEmpty().isNotBlank())
        assertEquals("INTERRUPTED", dao.session(sessionId).status)
        assertEquals(repository.state.value.reason, dao.session(sessionId).endReason)
        assertNotNull(dao.session(sessionId).endedAtMillis)
        assertEquals(1L, repository.state.value.packetCount)
        assertArrayEquals(byteArrayOf(7), dao.insertedBatches.flatten().single().payload)
    }

    @Test
    fun interruptFlushesPacketsAndPreservesProvidedReason() = runTest(timeout = 10.seconds) {
        val dao = FakeRecordingDao()
        val bluetooth = FakeBluetoothRepository()
        val repository = RecordingSessionRepository(dao, bluetooth, backgroundScope)
        val sessionId = withTimeout(5_000) {
            repository.start(device, serviceUuid, characteristicUuid).getOrThrow()
        }
        bluetooth.emitPacket(RawBlePacket(serviceUuid, characteristicUuid, byteArrayOf(9), 12L))
        runCurrent()

        withTimeout(5_000) { repository.interrupt("SERVICE_SHUTDOWN") }

        assertEquals(RecordingPhase.Interrupted, repository.state.value.phase)
        assertEquals(1L, repository.state.value.packetCount)
        assertEquals("SERVICE_SHUTDOWN", repository.state.value.reason)
        assertEquals("INTERRUPTED", dao.session(sessionId).status)
        assertEquals("SERVICE_SHUTDOWN", dao.session(sessionId).endReason)
        assertEquals(1, dao.insertedBatches.flatten().size)
    }

    @Test
    fun recoverOnlyUpdatesActiveSessionsThroughDao() = runTest(timeout = 10.seconds) {
        val dao = FakeRecordingDao()
        dao.createSession(storedSession("active"))
        dao.createSession(storedSession("completed").copy(status = "COMPLETED", endedAtMillis = 2L))
        dao.calls.clear()
        val bluetooth = FakeBluetoothRepository()
        val repository = RecordingSessionRepository(dao, bluetooth, backgroundScope)

        withTimeout(5_000) { repository.recoverInterruptedSessions() }
        runCurrent()

        assertEquals(listOf("interruptActiveSessions"), dao.calls)
        assertTrue(bluetooth.calls.isEmpty())
        assertEquals(RecordingState(), repository.state.value)
        assertEquals("INTERRUPTED", dao.session("active").status)
        assertEquals("PROCESS_RESTART", dao.session("active").endReason)
        assertNotNull(dao.session("active").endedAtMillis)
        assertEquals("COMPLETED", dao.session("completed").status)
        assertEquals(2L, dao.session("completed").endedAtMillis)
    }

    private fun storedSession(id: String) = RecordingSessionEntity(
        id = id,
        deviceAddress = device.address,
        deviceName = device.name,
        serviceUuid = serviceUuid.toString(),
        characteristicUuid = characteristicUuid.toString(),
        startedAtMillis = 1L,
    )

    private class FakeRecordingDao : RecordingDao {
        private val sessions = MutableStateFlow<List<RecordingSessionEntity>>(emptyList())
        val calls = mutableListOf<String>()
        val insertedBatches = mutableListOf<List<RawPacketEntity>>()
        val finishedStatuses = mutableListOf<String>()
        var insertFailure: Throwable? = null

        fun session(id: String): RecordingSessionEntity = sessions.value.single { it.id == id }

        override suspend fun createSession(session: RecordingSessionEntity) {
            calls += "createSession"
            check(sessions.value.none { it.id == session.id })
            sessions.value = sessions.value + session
        }

        override suspend fun insertPackets(packets: List<RawPacketEntity>) {
            calls += "insertPackets"
            insertFailure?.let { throw it }
            insertedBatches += packets.map { it.copy(payload = it.payload.copyOf()) }
        }

        override suspend fun finishSession(id: String, status: String, endedAtMillis: Long, reason: String?) {
            calls += "finishSession"
            finishedStatuses += status
            sessions.value = sessions.value.map {
                if (it.id == id) it.copy(status = status, endedAtMillis = endedAtMillis, endReason = reason) else it
            }
        }

        override suspend fun interruptActiveSessions(endedAtMillis: Long, reason: String) {
            calls += "interruptActiveSessions"
            sessions.value = sessions.value.map {
                if (it.status == "ACTIVE") {
                    it.copy(status = "INTERRUPTED", endedAtMillis = endedAtMillis, endReason = reason)
                } else it
            }
        }

        override fun observeSessions(): Flow<List<RecordingSessionEntity>> {
            calls += "observeSessions"
            return sessions.map { rows -> rows.sortedWith(compareByDescending<RecordingSessionEntity> { it.startedAtMillis }.thenBy { it.id }) }
        }

        override fun observeSession(id: String): Flow<RecordingSessionEntity?> {
            calls += "observeSession"
            return sessions.map { rows -> rows.find { it.id == id } }
        }

        override suspend fun readPackets(sessionId: String, afterSequence: Long, limit: Int): List<RawPacketEntity> {
            calls += "readPackets"
            return insertedBatches.flatten()
                .filter { it.sessionId == sessionId && it.sequence > afterSequence }
                .sortedBy { it.sequence }
                .take(limit)
        }

        override suspend fun deleteSession(id: String) {
            calls += "deleteSession"
            sessions.value = sessions.value.filterNot { it.id == id }
            val retained = insertedBatches.map { batch -> batch.filterNot { it.sessionId == id } }
            insertedBatches.clear()
            insertedBatches.addAll(retained.filter { it.isNotEmpty() })
        }

        // Gap archive: recorded in memory so assertions can inspect what the repository persisted.
        private val gapRows = MutableStateFlow<List<AcquisitionGapEntity>>(emptyList())
        val gaps: List<AcquisitionGapEntity> get() = gapRows.value

        override suspend fun insertGap(gap: AcquisitionGapEntity): Long {
            calls += "insertGap"
            val stored = gap.copy(id = gapRows.value.size + 1L)
            gapRows.value = gapRows.value + stored
            return stored.id
        }

        override fun observeGaps(sessionId: String): Flow<List<AcquisitionGapEntity>> =
            gapRows.map { rows -> rows.filter { it.sessionId == sessionId } }

        override suspend fun readGaps(sessionId: String): List<AcquisitionGapEntity> {
            calls += "readGaps"
            return gapRows.value.filter { it.sessionId == sessionId }
        }

        override suspend fun deleteGaps(sessionId: String) {
            calls += "deleteGaps"
            gapRows.value = gapRows.value.filterNot { it.sessionId == sessionId }
        }

        override suspend fun countGaps(sessionId: String): Int {
            calls += "countGaps"
            return gapRows.value.count { it.sessionId == sessionId }
        }
    }

    private class FakeBluetoothRepository : BluetoothRepository {
        override val state = MutableStateFlow(BluetoothSessionState())
        override val discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
        override val rawPackets = MutableSharedFlow<RawBlePacket>(extraBufferCapacity = 64)
        val controlEvents = MutableSharedFlow<BleEvent>(extraBufferCapacity = 64)
        override val events = controlEvents
        val calls = mutableListOf<String>()
        val subscriptionRequestId = 9_000_000_001L
        var lastSubscription: Pair<UUID, UUID>? = null
            private set
        var completeSubscriptionSynchronously = true
        var subscriberPresentWhenSubscriptionCompleted = false
            private set
        var connectFailure: Throwable? = null

        /**
         * When true, connect() leaves the state at its initial Idle and the test drives the
         * Connecting/Connected transitions itself, which is how a real transport behaves: the
         * client's own events are reduced asynchronously through the repository's event channel.
         */
        var transportReactsAsynchronously = false

        override fun startScan(serviceUuids: Set<UUID>): Result<Unit> {
            calls += "startScan"
            return Result.success(Unit)
        }

        override fun stopScan() {
            calls += "stopScan"
        }

        override fun connect(device: BleDevice): Result<Unit> {
            calls += "connect"
            connectFailure?.let { return Result.failure(it) }
            if (transportReactsAsynchronously) return Result.success(Unit)
            state.value = BluetoothSessionState(
                connection = BleConnectionState.Connected,
                connectedDevice = device,
                servicesDiscovered = true,
            )
            return Result.success(Unit)
        }

        override fun disconnect() {
            calls += "disconnect"
            state.value = BluetoothSessionState(connection = BleConnectionState.Idle)
        }

        override fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> {
            calls += "subscribe"
            lastSubscription = serviceUuid to characteristicUuid
            if (completeSubscriptionSynchronously) completeSubscription()
            return Result.success(subscriptionRequestId)
        }

        fun completeSubscription(requestId: Long = subscriptionRequestId) {
            val (serviceUuid, characteristicUuid) = requireNotNull(lastSubscription)
            subscriberPresentWhenSubscriptionCompleted = controlEvents.subscriptionCount.value > 0
            check(controlEvents.tryEmit(BleEvent.SubscriptionCompleted(requestId, serviceUuid, characteristicUuid, true)))
        }

        fun emitPacket(packet: RawBlePacket) {
            check(rawPackets.subscriptionCount.value > 0) { "Recording must install its packet collector first" }
            check(rawPackets.tryEmit(packet))
        }

        fun loseConnection(reason: String) {
            state.value = BluetoothSessionState(
                connection = BleConnectionState.Idle,
                lastError = BleEvent.Error("connection", reason),
            )
            check(controlEvents.tryEmit(BleEvent.StateChanged(BleConnectionState.Idle)))
        }

        override fun read(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> {
            calls += "read"
            return Result.success(2L)
        }

        override fun write(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            value: ByteArray,
            writeType: BleWriteType,
        ): Result<Long> {
            calls += "write"
            return Result.success(3L)
        }

        override fun close() {
            calls += "close"
            state.value = BluetoothSessionState(connection = BleConnectionState.Closed)
        }
    }
}
