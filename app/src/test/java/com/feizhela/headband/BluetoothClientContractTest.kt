package com.feizhela.headband

import com.feizhela.headband.bluetooth.BleConnectionState
import com.feizhela.headband.bluetooth.BleEvent
import com.feizhela.headband.bluetooth.BleWriteType
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BluetoothClientContractTest {
    @Test
    fun notificationEventCarriesRawBytesAndSourceIdentity() {
        val service = UUID.fromString("5aee1a8a-08de-11ed-861d-0242ac120002")
        val characteristic = UUID.fromString("405992d6-0cf2-11ed-861d-0242ac120002")
        val payload = byteArrayOf(0, 1, 2, 3)
        val event = BleEvent.NotificationReceived(service, characteristic, payload, 1234L)

        assertEquals(service, event.serviceUuid)
        assertEquals(characteristic, event.characteristicUuid)
        assertArrayEquals(payload, event.value)
        assertEquals(1234L, event.receivedAtMillis)
    }

    @Test
    fun writeCompletionSeparatesTransportSuccessFromStatus() {
        val event = BleEvent.WriteCompleted(
            requestId = 7L,
            serviceUuid = UUID.randomUUID(),
            characteristicUuid = UUID.randomUUID(),
            success = false,
            status = 133,
            message = "connection lost",
        )

        assertEquals(7L, event.requestId)
        assertFalse(event.success)
        assertEquals(133, event.status)
        assertEquals("connection lost", event.message)
    }

    @Test
    fun connectionStatesAndWriteModesAreStableTransportValues() {
        assertEquals(BleConnectionState.Idle, BleConnectionState.valueOf("Idle"))
        assertEquals(BleConnectionState.Connected, BleConnectionState.valueOf("Connected"))
        assertEquals(BleWriteType.WithResponse, BleWriteType.valueOf("WithResponse"))
    }
}
