package com.feizhela.headband.bluetooth

import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDataSourceTest {
    private val serviceUuid = UUID.fromString("5aee1a8a-08de-11ed-861d-0242ac120002")
    private val characteristicUuid = UUID.fromString("405992d6-0cf2-11ed-861d-0242ac120002")

    @Test(timeout = 5_000)
    fun notificationBytesAreIsolatedWhenClientReusesItsBuffer() = runBlocking {
        val client = FakeBluetoothClient()
        val source = FlowBluetoothDataSource(client, capacity = 2)
        try {
            val original = byteArrayOf(0, 1, -1)
            client.emit(notification(original, receivedAtMillis = 123L))
            original[0] = 9
            client.emit(notification(original, receivedAtMillis = 456L))
            original.fill(42)
            source.close()

            val received = source.events.toList().map { it as BleEvent.NotificationReceived }

            assertEquals(2, received.size)
            assertArrayEquals(byteArrayOf(0, 1, -1), received[0].value)
            assertArrayEquals(byteArrayOf(9, 1, -1), received[1].value)
            assertEquals(listOf(123L, 456L), received.map { it.receivedAtMillis })
            received.forEach {
                assertEquals(serviceUuid, it.serviceUuid)
                assertEquals(characteristicUuid, it.characteristicUuid)
                assertNotSame(original, it.value)
            }
            received[0].value[1] = 7
            assertArrayEquals(byteArrayOf(42, 42, 42), original)
            assertArrayEquals(byteArrayOf(9, 1, -1), received[1].value)
        } finally {
            source.close()
        }
    }

    @Test(timeout = 5_000)
    fun bufferOverflowClosesClientAndRejectsSubsequentOperations() = runBlocking {
        val client = FakeBluetoothClient()
        val source = FlowBluetoothDataSource(client, capacity = 2)
        try {
            val first = notification(byteArrayOf(1))
            val second = notification(byteArrayOf(2))
            client.emit(first)
            client.emit(second)
            assertEquals(0, client.closeCalls)
            client.emit(notification(byteArrayOf(3)))

            assertEquals(1, client.closeCalls)
            assertClosed(source.startScan(setOf(serviceUuid)))
            assertClosed(source.connect(BleDevice("00:11:22:33:44:55", "Headband")))
            assertClosed(source.subscribe(serviceUuid, characteristicUuid))
            assertClosed(source.read(serviceUuid, characteristicUuid))
            assertClosed(source.write(serviceUuid, characteristicUuid, byteArrayOf(1)))
            // These Unit-returning operations are no-ops after closure, not Result failures.
            source.stopScan()
            source.disconnect()
            assertTrue(client.operationCalls.isEmpty())

            // Keep the fake listener installed on client.close() to exercise a late callback.
            client.emit(notification(byteArrayOf(4)))
            val buffered = mutableListOf<BleEvent>()
            val failure = runCatching { source.events.toList(buffered) }.exceptionOrNull()

            assertTrue(failure is BleBufferOverflowException)
            assertEquals(2, buffered.size)
            assertArrayEquals(first.value, (buffered[0] as BleEvent.NotificationReceived).value)
            assertArrayEquals(second.value, (buffered[1] as BleEvent.NotificationReceived).value)
            source.close()
            source.close()
            assertEquals(1, client.closeCalls)
        } finally {
            source.close()
        }
    }

    @Test(timeout = 5_000)
    fun closeIsIdempotentAndCompletesEventFlow() = runBlocking {
        val client = FakeBluetoothClient()
        val source = FlowBluetoothDataSource(client)
        assertTrue(client.hasListener)

        source.close()
        source.close()
        client.emit(notification(byteArrayOf(1)))

        assertEquals(1, client.closeCalls)
        assertEquals(1, client.listenerClearCalls)
        assertFalse(client.hasListener)
        assertTrue(source.events.toList().isEmpty())
        assertClosed(source.subscribe(serviceUuid, characteristicUuid))
        assertTrue(client.operationCalls.isEmpty())
    }

    @Test(timeout = 5_000)
    fun subscribeReturnsClientRequestIdAndForwardsItsCompletion() = runBlocking {
        val client = FakeBluetoothClient()
        val source = FlowBluetoothDataSource(client)
        try {
            val requestId = source.subscribe(serviceUuid, characteristicUuid).getOrThrow()

            assertEquals(9_000_000_001L, requestId)
            assertEquals(serviceUuid to characteristicUuid, client.lastSubscription)
            assertEquals(listOf("subscribe"), client.operationCalls)
            val completion = BleEvent.SubscriptionCompleted(
                requestId = requestId,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                success = true,
            )
            client.emit(completion)
            assertEquals(completion, source.events.first())
        } finally {
            source.close()
        }
    }

    private fun assertClosed(result: Result<*>) {
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    private fun notification(value: ByteArray, receivedAtMillis: Long = 1L) =
        BleEvent.NotificationReceived(serviceUuid, characteristicUuid, value, receivedAtMillis)

    private class FakeBluetoothClient : BluetoothClient {
        private var listener: ((BleEvent) -> Unit)? = null
        val hasListener: Boolean
            get() = listener != null
        var closeCalls = 0
            private set
        var listenerClearCalls = 0
            private set
        val operationCalls = mutableListOf<String>()
        var lastSubscription: Pair<UUID, UUID>? = null
            private set

        override fun setListener(listener: ((BleEvent) -> Unit)?) {
            if (listener == null) listenerClearCalls++
            this.listener = listener
        }

        fun emit(event: BleEvent) {
            listener?.invoke(event)
        }

        override fun startScan(serviceUuids: Set<UUID>): Result<Unit> {
            operationCalls += "startScan"
            return Result.success(Unit)
        }

        override fun stopScan() {
            operationCalls += "stopScan"
        }

        override fun connect(device: BleDevice): Result<Unit> {
            operationCalls += "connect"
            return Result.success(Unit)
        }

        override fun disconnect() {
            operationCalls += "disconnect"
        }

        override fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> {
            operationCalls += "subscribe"
            lastSubscription = serviceUuid to characteristicUuid
            return Result.success(9_000_000_001L)
        }

        override fun read(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> {
            operationCalls += "read"
            return Result.success(74L)
        }

        override fun write(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            value: ByteArray,
            writeType: BleWriteType,
        ): Result<Long> {
            operationCalls += "write"
            return Result.success(75L)
        }

        override fun close() {
            closeCalls++
        }
    }
}
