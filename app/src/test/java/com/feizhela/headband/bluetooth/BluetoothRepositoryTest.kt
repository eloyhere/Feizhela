package com.feizhela.headband.bluetooth

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothRepositoryTest {
    private val serviceUuid = UUID.fromString("5aee1a8a-08de-11ed-861d-0242ac120002")
    private val characteristicUuid = UUID.fromString("405992d6-0cf2-11ed-861d-0242ac120002")

    @Test
    fun subscriptionCompletionIsBroadcastAfterStateUpdate() = runTest {
        val source = FakeBluetoothDataSource()
        val repository = DefaultBluetoothRepository(source, backgroundScope)
        val observed = mutableListOf<Pair<BleEvent, Set<Pair<UUID, UUID>>>>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            repository.events.collect { event ->
                observed += event to repository.state.value.activeSubscriptions
            }
        }
        runCurrent()

        val completion = BleEvent.SubscriptionCompleted(
            requestId = 17L,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            success = true,
        )
        source.send(completion)
        runCurrent()

        assertEquals(listOf(completion), observed.map { it.first })
        assertEquals(setOf(serviceUuid to characteristicUuid), observed.single().second)
        assertEquals(setOf(serviceUuid to characteristicUuid), repository.state.value.activeSubscriptions)
        repository.close()
        runCurrent()
    }

    @Test
    fun notificationWithoutRawConsumerReportsFailureAndClosesSource() = runTest {
        val source = FakeBluetoothDataSource()
        val repository = DefaultBluetoothRepository(source, backgroundScope)
        val observed = mutableListOf<BleEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            repository.events.collect { observed += it }
        }
        runCurrent()

        source.send(notification(1))
        runCurrent()

        val failure = repository.state.value.lastError
        assertNotNull(failure)
        assertEquals("eventStream", failure?.operation)
        assertTrue(failure?.message.orEmpty().contains("consumer", ignoreCase = true))
        assertTrue(observed.contains(failure!!))
        assertTrue(observed.none { it is BleEvent.NotificationReceived })
        assertClosed(repository, source)
    }

    @Test
    fun cancellingOwnerScopeReleasesSourceAndStopsCollection() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val source = FakeBluetoothDataSource()
        val repository = DefaultBluetoothRepository(source, owner)
        runCurrent()
        assertEquals(1, source.activeCollectors)

        owner.cancel()
        runCurrent()

        assertClosed(repository, source)
        assertNull(repository.state.value.lastError)
    }

    @Test
    fun cancellingOwnerBeforeFirstDispatchStillReleasesSource() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val source = FakeBluetoothDataSource()
        val repository = DefaultBluetoothRepository(source, owner)

        owner.cancel()
        runCurrent()

        assertClosed(repository, source)
    }

    @Test
    fun explicitCloseCancelsCollectionEvenWhenSourceCloseDoesNotCompleteFlow() = runTest {
        val source = FakeBluetoothDataSource()
        val repository = DefaultBluetoothRepository(source, backgroundScope)
        runCurrent()
        assertEquals(1, source.activeCollectors)

        repository.close()
        repository.close()
        runCurrent()

        assertClosed(repository, source)
        assertNull(repository.state.value.lastError)
        assertTrue(repository.connect(BleDevice("01:02:03:04:05:06", "headband")).isFailure)
        assertEquals(0, source.connectCalls)
    }

    @Test
    fun slowRawConsumerInterruptsAcquisitionWhenFiniteBufferFills() = runTest {
        val source = FakeBluetoothDataSource()
        val repository = DefaultBluetoothRepository(source, backgroundScope, eventCapacity = 1)
        val consumerBlocked = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val received = mutableListOf<Int>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            repository.rawPackets.collect { packet ->
                received += packet.payload.single().toInt()
                consumerBlocked.complete(Unit)
                releaseConsumer.await()
            }
        }
        runCurrent()
        source.send(notification(1))
        runCurrent()
        assertTrue(consumerBlocked.isCompleted)
        assertEquals(listOf(1), received)

        // One packet fits while the consumer handles packet 1; the next must fail explicitly.
        source.send(notification(2))
        runCurrent()
        assertEquals(0, source.closeCalls)
        assertNull(repository.state.value.lastError)
        source.send(notification(3))
        runCurrent()

        assertTrue(repository.state.value.lastError?.cause is BleBufferOverflowException)
        assertClosed(repository, source)
        releaseConsumer.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2), received)
    }

    @Test
    fun normalSourceCompletionClosesRepositoryWithoutAnError() = runTest {
        val source = FakeBluetoothDataSource()
        val repository = DefaultBluetoothRepository(source, backgroundScope)
        runCurrent()
        assertEquals(1, source.activeCollectors)

        source.complete()
        runCurrent()

        assertClosed(repository, source)
        assertNull(repository.state.value.lastError)
    }

    @Test
    fun rawPacketCopiesInputAndReturnsIndependentPayloadArrays() {
        val original = byteArrayOf(1, 2, 3)
        val packet = RawBlePacket(serviceUuid, characteristicUuid, original, 123L)
        original[0] = 99
        val firstRead = packet.payload
        firstRead[1] = 99

        assertArrayEquals(byteArrayOf(1, 2, 3), packet.payload)
        assertEquals(serviceUuid, packet.serviceUuid)
        assertEquals(characteristicUuid, packet.characteristicUuid)
        assertEquals(123L, packet.receivedAtMillis)
    }

    @Test
    fun rawSubscribersCannotMutateEachOthersPacketBytes() = runTest {
        val source = FakeBluetoothDataSource()
        val repository = DefaultBluetoothRepository(source, backgroundScope)
        val firstObserved = CompletableDeferred<ByteArray>()
        val secondObserved = CompletableDeferred<ByteArray>()
        val mutationDone = CompletableDeferred<Unit>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            repository.rawPackets.collect { packet ->
                val bytes = packet.payload
                bytes[0] = 99
                firstObserved.complete(packet.payload)
                mutationDone.complete(Unit)
            }
        }
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            repository.rawPackets.collect { packet ->
                mutationDone.await()
                secondObserved.complete(packet.payload)
            }
        }
        runCurrent()

        source.send(notification(7))
        runCurrent()

        assertTrue(firstObserved.isCompleted)
        assertTrue(secondObserved.isCompleted)
        assertArrayEquals(byteArrayOf(7), firstObserved.await())
        assertArrayEquals(byteArrayOf(7), secondObserved.await())
        assertNull(repository.state.value.lastError)
        repository.close()
        runCurrent()
    }

    private fun notification(value: Int) = BleEvent.NotificationReceived(
        serviceUuid = serviceUuid,
        characteristicUuid = characteristicUuid,
        value = byteArrayOf(value.toByte()),
        receivedAtMillis = value.toLong(),
    )

    private fun assertClosed(repository: BluetoothRepository, source: FakeBluetoothDataSource) {
        assertEquals(1, source.closeCalls)
        assertEquals(0, source.activeCollectors)
        assertEquals(BleConnectionState.Closed, repository.state.value.connection)
        assertNull(repository.state.value.connectedDevice)
        assertFalse(repository.state.value.servicesDiscovered)
        assertTrue(repository.state.value.activeSubscriptions.isEmpty())
    }

    private class FakeBluetoothDataSource : BluetoothDataSource {
        private val incoming = Channel<BleEvent>(Channel.UNLIMITED)
        var closeCalls = 0
            private set
        var activeCollectors = 0
            private set
        var connectCalls = 0
            private set

        override val events: Flow<BleEvent> = flow {
            activeCollectors++
            try {
                for (event in incoming) emit(event)
            } finally {
                activeCollectors--
            }
        }

        suspend fun send(event: BleEvent) = incoming.send(event)
        fun complete() { incoming.close() }

        // Deliberately independent of flow completion: the repository owns cancellation.
        override fun close() { closeCalls++ }
        override fun startScan(serviceUuids: Set<UUID>): Result<Unit> = Result.success(Unit)
        override fun stopScan() = Unit
        override fun connect(device: BleDevice): Result<Unit> {
            connectCalls++
            return Result.success(Unit)
        }
        override fun disconnect() = Unit
        override fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> = Result.success(1L)
        override fun read(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> = Result.success(2L)
        override fun write(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            value: ByteArray,
            writeType: BleWriteType,
        ): Result<Long> = Result.success(3L)
    }
}
