package com.feizhela.headband.simulation

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feizhela.headband.bluetooth.TransportProvider
import com.feizhela.headband.recording.RecordingPhase
import com.feizhela.headband.storage.RawPacketEntity
import com.feizhela.headband.storage.RecordingDao
import com.feizhela.headband.storage.RecordingDatabase
import com.feizhela.headband.storage.RecordingSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end simulation tests on a real device/emulator: the debug transport feeds the real
 * repository pipeline and the real Room database (`recordings.db`), with no BLE hardware and no
 * Bluetooth permissions. Every test creates a fresh UUID session and deletes only that session.
 *
 * Run all: `adb shell am instrument -w -e class
 * com.feizhela.headband.simulation.SimulatedAcquisitionTest
 * com.feizhela.headband.test/androidx.test.runner.AndroidJUnitRunner`.
 *
 * Manual scenario: append `#manualScenarioFromInstrumentationArguments -e scenario=<name>
 * -e durationMillis=<ms>` to the class argument. The manual entry is skipped without `scenario`.
 */
@RunWith(AndroidJUnit4::class)
class SimulatedAcquisitionTest {
    private lateinit var context: Context
    private lateinit var dao: RecordingDao
    private val sessionsToDelete = mutableListOf<String>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dao = RecordingDatabase.open(context).recordingDao
    }

    @After
    fun tearDown() = runBlocking {
        // Only this test's own sessions are removed; packets/gaps cascade in Room and nothing else
        // in the shared database is touched (no clearAllTables, no recovery marking).
        sessionsToDelete.forEach { id -> runCatching { dao.deleteSession(id) } }
        sessionsToDelete.clear()
        TransportProvider.reset()
    }

    @Test(timeout = 30_000)
    fun healthyRecordingPersistsContiguousRoomRows() = runBlocking {
        val result = run(SimulationScenarios.healthy(), durationMillis = 3_000)

        assertEquals(RecordingPhase.Completed, result.phase)
        assertEquals("COMPLETED", result.sessionStatus)
        val sessionId = result.sessionId
        assertNotNull("healthy run must create a durable session", sessionId)
        val id = sessionId!!

        val session = awaitSession(id)
        assertEquals("COMPLETED", session.status)
        assertNotNull(session.endedAtMillis)
        assertNull(session.endReason)

        val packets = readAllPackets(id)
        assertTrue("healthy recording must persist raw packets", packets.isNotEmpty())
        assertEquals("state packet count must match durable rows", packets.size.toLong(), result.packetsPersisted)
        assertEquals(
            "archived sequence must be dense from 0",
            (0 until packets.size).map { it.toLong() },
            packets.map { it.sequence },
        )
        packets.forEach { packet ->
            assertEquals("each notification is 180 bytes = 10 x 18", 180, packet.payload.size)
        }
    }

    @Test(timeout = 30_000)
    fun midSessionDisconnectIsInterruptedAndKeepsReceivedPackets() = runBlocking {
        val result = run(SimulationScenarios.disconnectMidSession(), durationMillis = 4_000)

        assertEquals(RecordingPhase.Interrupted, result.phase)
        assertFalse("an interruption needs a reason", result.reason.isNullOrBlank())
        val sessionId = result.sessionId
        assertNotNull("interrupted run must keep its session id", sessionId)
        val id = sessionId!!

        val session = awaitSession(id)
        assertEquals("INTERRUPTED", session.status)
        assertNotNull(session.endedAtMillis)
        assertFalse("Room must record why the link ended", session.endReason.isNullOrBlank())

        val packets = readAllPackets(id)
        assertTrue("packets received before the link loss must survive", packets.isNotEmpty())
        assertEquals(packets.size.toLong(), result.packetsPersisted)
        assertEquals(
            (0 until packets.size).map { it.toLong() },
            packets.map { it.sequence },
        )
        packets.forEach { assertEquals(180, it.payload.size) }
        assertTrue(
            "more notifications may have been received than accepted, never fewer",
            result.notificationsReceived >= packets.size,
        )
    }

    @Test(timeout = 30_000)
    fun rejectedSubscriptionFailsSessionWithoutFakingPackets() = runBlocking {
        val result = run(SimulationScenarios.subscriptionFailure(), durationMillis = 2_000)

        assertEquals(RecordingPhase.Failed, result.phase)
        assertEquals(0, result.notificationsEmitted)
        assertEquals(0, result.notificationsReceived)
        assertEquals(0L, result.packetsPersisted)
        val sessionId = result.sessionId
        assertNotNull("failed start still owns the session row it created", sessionId)
        val id = sessionId!!

        val session = awaitSession(id)
        assertEquals("FAILED", session.status)
        assertNotNull(session.endedAtMillis)
        val reason = session.endReason
        assertFalse(reason.isNullOrBlank())
        assertTrue(
            "failure reason must describe the rejected subscription, got: $reason",
            reason!!.contains("subscription", ignoreCase = true),
        )
        assertTrue(
            "a rejected subscription must not be reported as a successful recording with packets",
            readAllPackets(id).isEmpty(),
        )
    }

    @Test(timeout = 30_000)
    fun firmwareGeneratorCoversCounterWrap() {
        val simulator = FirmwarePacketSimulator(
            FirmwareSimulatorConfig(counterStart = 0xFFFF - 15, eogPulseEverySamples = 0),
        )
        val counters = mutableListOf<Int>()
        repeat(3) { packetIndex ->
            val payload = simulator.nextPacket()
            assertNotNull("packet $packetIndex must be generated", payload)
            val bytes = payload!!
            assertEquals("firmware baseline payload size", 180, bytes.size)
            counters += readCounters(bytes)
        }

        assertEquals(30, counters.size)
        assertEquals("last counter before wrap", 0xFFFF, counters[15])
        assertEquals("first counter after wrap", 0, counters[16])
        counters.zipWithNext().forEach { (current, next) ->
            assertEquals("counter increments by one sample", (current + 1) and 0xFFFF, next)
        }
    }

    /**
     * Manual MuMu entry point. It is skipped during a normal suite run and only executes when
     * `-e scenario=<name>` is supplied. `-e keepSession true` leaves the row for inspection.
     */
    @Test(timeout = 30_000)
    fun manualScenarioFromInstrumentationArguments() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val requested = args.getString("scenario")
        assumeTrue("Pass -e scenario=<name> to run a manual simulation", requested != null)
        val scenario = SimulationScenarios.byName(requested!!)
            ?: error(
                "Unknown scenario '$requested'; known: " +
                    SimulationScenarios.all.joinToString { it.name },
            )
        val duration = args.getString("durationMillis")?.toLongOrNull() ?: scenario.durationMillis
        val keepSession = args.getString("keepSession")?.toBooleanStrictOrNull() ?: false

        val result = run(scenario, duration, cleanUp = !keepSession)
        println("SIMULATION_RESULT scenario=${scenario.name} result=$result")

        assertNotNull("manual run must create a session", result.sessionId)
        assertTrue(
            "manual run must reach a terminal phase, got ${result.phase}",
            result.phase in setOf(RecordingPhase.Completed, RecordingPhase.Interrupted, RecordingPhase.Failed),
        )
    }

    private suspend fun run(
        scenario: SimulationScenario,
        durationMillis: Long,
        cleanUp: Boolean = true,
    ): SimulationResult {
        val result = SimulationRun.runRecording(context, scenario, durationMillis)
        if (cleanUp) result.sessionId?.let { sessionsToDelete += it }
        return result
    }

    private suspend fun awaitSession(id: String): RecordingSessionEntity =
        withTimeout(5_000) {
            dao.observeSession(id).first { it != null }!!
        }

    private suspend fun readAllPackets(sessionId: String): List<RawPacketEntity> {
        val packets = mutableListOf<RawPacketEntity>()
        var cursor = -1L
        while (true) {
            val page = dao.readPackets(sessionId, afterSequence = cursor, limit = PAGE_SIZE)
            if (page.isEmpty()) break
            packets += page
            cursor = page.last().sequence
            if (page.size < PAGE_SIZE) break
        }
        return packets
    }

    /** Test-side decoding only: each sample's first field is the little-endian uint16 counter. */
    private fun readCounters(payload: ByteArray): List<Int> = buildList {
        repeat(FirmwarePacketSimulator.SAMPLES_PER_PACKET) { sample ->
            val offset = sample * FirmwarePacketSimulator.SAMPLE_BYTES
            add(
                (payload[offset].toInt() and 0xFF) or
                    ((payload[offset + 1].toInt() and 0xFF) shl 8),
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 200
    }
}
