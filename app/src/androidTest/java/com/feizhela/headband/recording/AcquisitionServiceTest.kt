package com.feizhela.headband.recording

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feizhela.headband.storage.RecordingDao
import com.feizhela.headband.storage.RecordingDatabase
import com.feizhela.headband.storage.RecordingSessionEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Verifies the acquisition owner without any BLE device and without any Activity:
 *
 * 1. the service starts, keeps a started-service lifetime and exposes a binder with an idle state;
 * 2. a durable ACTIVE session left behind by a dead process is marked INTERRUPTED with
 *    endReason = PROCESS_RESTART as soon as a fresh service instance starts (framework.md line 188).
 *
 * The tests touch the real on-device `recordings.db`: they only insert/delete their own fixed
 * [TEST_SESSION_ID] row and never call clearAllTables or delete another session. Starting the real
 * service does execute its production recovery, which rewrites every other row that is still
 * ACTIVE - that is the behaviour under test, so run these tests only on a test device.
 *
 * No Bluetooth or notification permission is requested here and no device is connected. A failed
 * foreground start is reported as a test failure with the original exception, never skipped.
 *
 * Method order is fixed on purpose: [existingActiveSessionIsRecoveredAsInterruptedOnServiceStart]
 * needs a freshly created service instance, so it runs before any test that has already started
 * the service and could still be tearing it down.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AcquisitionServiceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dao: RecordingDao

    @Before
    fun openDatabase() {
        dao = RecordingDatabase.open(context).recordingDao
    }

    @Test(timeout = 30_000)
    fun existingActiveSessionIsRecoveredAsInterruptedOnServiceStart() = runBlocking {
        // Only our own fixed row: delete before and after so a crashed earlier run cannot poison
        // the CREATED primary key and cannot leave test data behind on the device.
        deleteTestSession()
        var bound = false
        val connection = BinderConnection()
        try {
            dao.createSession(
                RecordingSessionEntity(
                    id = TEST_SESSION_ID,
                    deviceAddress = "00:11:22:33:44:55",
                    deviceName = "acquisition-service-test",
                    serviceUuid = "00000000-0000-0000-0000-0000000000aa",
                    characteristicUuid = "00000000-0000-0000-0000-0000000000bb",
                    startedAtMillis = System.currentTimeMillis(),
                    status = "ACTIVE",
                ),
            )
            assertEquals(
                "The row inserted by this test must start ACTIVE",
                "ACTIVE",
                dao.observeSession(TEST_SESSION_ID).first()?.status,
            )

            startServiceOrFail()
            bound = bindServiceOrFail(connection)
            assertTrue("bindService returned false for AcquisitionService", bound)
            assertTrue(
                "AcquisitionService did not bind within $BIND_TIMEOUT_MILLIS ms",
                connection.awaitConnected(BIND_TIMEOUT_MILLIS),
            )
            // Binding creates the instance, but its onCreate recovery runs on the IO dispatcher.
            requireBinder(connection)
            val recovered = awaitRecoveredSession()

            assertEquals("INTERRUPTED", recovered.status)
            assertEquals("PROCESS_RESTART", recovered.endReason)
            val endedAt = recovered.endedAtMillis
                ?: throw AssertionError("Recovery must persist an end timestamp")
            assertTrue(
                "endedAtMillis must not predate the session",
                endedAt >= recovered.startedAtMillis,
            )
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
            context.stopService(serviceIntent())
            deleteTestSession()
        }
    }

    @Test(timeout = 20_000)
    fun serviceStartsAndBindsWithoutAnyBluetoothDevice() {
        var bound = false
        val connection = BinderConnection()
        try {
            startServiceOrFail()
            bound = bindServiceOrFail(connection)
            assertTrue("bindService returned false for AcquisitionService", bound)
            assertTrue(
                "AcquisitionService did not bind within $BIND_TIMEOUT_MILLIS ms",
                connection.awaitConnected(BIND_TIMEOUT_MILLIS),
            )
            val binder = requireBinder(connection)
            @Suppress("UNCHECKED_CAST")
            val state = binder.state as StateFlow<RecordingState>

            assertEquals("A freshly started service must be idle", RecordingPhase.Idle, state.value.phase)
            assertNull("No session may exist before any BLE device connects", state.value.sessionId)
            assertEquals(0L, state.value.packetCount)
            assertNull("An idle service must not report a failure reason", state.value.reason)
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
            context.stopService(serviceIntent())
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    private fun startServiceOrFail() {
        try {
            AcquisitionService.startForeground(context)
        } catch (error: Exception) {
            throw AssertionError(
                "AcquisitionService.startForeground(context) failed before any BLE device was involved: " +
                    "${error::class.java.name}: ${error.message}. This test deliberately requests no " +
                    "Bluetooth or notification permission; on API 31+ the app must be allowed to start a " +
                    "foreground service and, on API 34+, the connectedDevice type needs a granted " +
                    "BLUETOOTH_CONNECT (or equivalent) permission.",
                error,
            )
        }
    }

    private fun bindServiceOrFail(connection: BinderConnection): Boolean =
        try {
            context.bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE)
        } catch (error: Exception) {
            throw AssertionError(
                "bindService(AcquisitionService) failed: ${error::class.java.name}: ${error.message}",
                error,
            )
        }

    private fun requireBinder(connection: BinderConnection): AcquisitionService.LocalBinder {
        val service = connection.binder
            ?: throw AssertionError("onServiceConnected delivered no binder; was the service started?")
        return service as? AcquisitionService.LocalBinder
            ?: throw AssertionError(
                "Bound binder is ${service::class.java.name}, expected AcquisitionService.LocalBinder",
            )
    }

    private suspend fun awaitRecoveredSession(): RecordingSessionEntity {
        val deadline = SystemClock.elapsedRealtime() + RECOVERY_TIMEOUT_MILLIS
        var latest: RecordingSessionEntity? = null
        while (true) {
            latest = dao.observeSession(TEST_SESSION_ID).first()
            if (latest?.status == "INTERRUPTED" && latest.endReason == "PROCESS_RESTART") return latest
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AssertionError(
                    "Session $TEST_SESSION_ID was not recovered as INTERRUPTED/PROCESS_RESTART within " +
                        "$RECOVERY_TIMEOUT_MILLIS ms (latest status=${latest?.status}, " +
                        "endReason=${latest?.endReason}). onCreate recovery only runs for a newly " +
                        "created service instance; make sure no previous AcquisitionService instance " +
                        "is still alive (force-stop the app before running).",
                )
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    private suspend fun deleteTestSession() {
        try {
            dao.deleteSession(TEST_SESSION_ID)
        } catch (_: Throwable) {
            // Best effort: cleanup must not hide the original assertion failure.
        }
    }

    private fun serviceIntent(): Intent = Intent(context, AcquisitionService::class.java)

    private class BinderConnection : ServiceConnection {
        private val connected = CountDownLatch(1)

        @Volatile
        var binder: IBinder? = null
            private set

        fun awaitConnected(timeoutMillis: Long): Boolean =
            connected.await(timeoutMillis, TimeUnit.MILLISECONDS)

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    private companion object {
        const val TEST_SESSION_ID = "test-acquisition-service-recovery"
        const val BIND_TIMEOUT_MILLIS = 5_000L
        const val RECOVERY_TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}
