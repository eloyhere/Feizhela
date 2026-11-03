package com.feizhela.headband.storage

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingDatabaseTest {
    private lateinit var database: RecordingDatabase
    private lateinit var dao: RecordingDao

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RecordingDatabase::class.java,
        ).build()
        dao = database.recordingDao
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun batchReadIsOrderedPagedAndScopedToSession() = runBlocking {
        dao.createSession(session("first"))
        dao.createSession(session("other"))
        dao.insertPackets(
            listOf(
                packet("first", 3),
                packet("first", 0),
                packet("other", 1),
                packet("first", 2),
                packet("first", 1),
            ),
        )

        val firstPage = dao.readPackets("first", afterSequence = -1, limit = 2)
        assertEquals(listOf(0L, 1L), firstPage.map { it.sequence })
        assertArrayEquals(byteArrayOf(0, 1, -1), firstPage.first().payload)
        assertEquals(1_000L, firstPage.first().receivedAtMillis)
        val nextPage = dao.readPackets("first", afterSequence = 1, limit = 2)
        assertEquals(listOf(2L, 3L), nextPage.map { it.sequence })
        assertEquals(emptyList<RawPacketEntity>(), dao.readPackets("first", 3, 2))
        assertEquals(listOf(1L), dao.readPackets("other", -1, 2).map { it.sequence })
    }

    @Test
    fun duplicatePacketRollsBackTheWholeBatch() = runBlocking {
        dao.createSession(session("first"))
        dao.insertPackets(listOf(packet("first", 1)))
        try {
            dao.insertPackets(listOf(packet("first", 2), packet("first", 1)))
            fail("A duplicate sequence must fail instead of overwriting archived bytes")
        } catch (_: SQLiteConstraintException) {
            // The earlier item in the failed batch must also be rolled back.
        }

        assertEquals(listOf(1L), dao.readPackets("first", -1, 10).map { it.sequence })
    }

    @Test
    fun deletingSessionCascadesPacketsAndOrphansAreRejected() = runBlocking {
        dao.createSession(session("first"))
        dao.createSession(session("other"))
        dao.insertPackets(listOf(packet("first", 0), packet("other", 0)))

        dao.deleteSession("first")

        assertNull(dao.observeSession("first").first())
        assertEquals(emptyList<RawPacketEntity>(), dao.readPackets("first", -1, 10))
        assertEquals(listOf(0L), dao.readPackets("other", -1, 10).map { it.sequence })
        try {
            dao.insertPackets(listOf(packet("missing", 0)))
            fail("Packets without a recording session must be rejected")
        } catch (_: SQLiteConstraintException) {
            // Foreign keys must be enabled by Room.
        }
    }

    @Test
    fun recoveryInterruptsOnlyActiveSessionsAndDoesNotRewriteTerminalMetadata() = runBlocking {
        dao.createSession(session("active"))
        dao.createSession(session("stopped"))
        dao.createSession(session("interrupted"))
        dao.finishSession("stopped", "COMPLETED", 2_000, "USER_STOPPED")
        dao.finishSession("interrupted", "INTERRUPTED", 2_100, "DISCONNECTED")

        dao.interruptActiveSessions(3_000, "PROCESS_RESTART")
        // Recovery is safe to repeat; already terminal sessions keep their original metadata.
        dao.interruptActiveSessions(4_000, "SECOND_RECOVERY")

        val active = dao.observeSession("active").first()!!
        assertEquals("INTERRUPTED", active.status)
        assertEquals(3_000L, active.endedAtMillis)
        assertEquals("PROCESS_RESTART", active.endReason)
        val stopped = dao.observeSession("stopped").first()!!
        assertEquals("COMPLETED", stopped.status)
        assertEquals(2_000L, stopped.endedAtMillis)
        assertEquals("USER_STOPPED", stopped.endReason)
        val interrupted = dao.observeSession("interrupted").first()!!
        assertEquals(2_100L, interrupted.endedAtMillis)
        assertEquals("DISCONNECTED", interrupted.endReason)
    }

    @Test
    fun sessionFlowsReflectWritesAndUseStableNewestFirstOrder() = runBlocking {
        assertEquals(emptyList<RecordingSessionEntity>(), dao.observeSessions().first())
        assertNull(dao.observeSession("missing").first())
        dao.createSession(session("old", startedAtMillis = 1_000))
        dao.createSession(session("new-b", startedAtMillis = 2_000))
        dao.createSession(session("new-a", startedAtMillis = 2_000))
        val sessions = withTimeout(5_000) {
            dao.observeSessions().first { it.size == 3 }
        }
        assertEquals(listOf("new-a", "new-b", "old"), sessions.map { it.id })

        dao.finishSession("new-a", "COMPLETED", 3_000, null)
        val finished = withTimeout(5_000) {
            dao.observeSession("new-a").first { it?.status == "COMPLETED" }
        }!!
        assertEquals(3_000L, finished.endedAtMillis)
        assertNull(finished.endReason)
    }

    private fun session(id: String, startedAtMillis: Long = 1_000) = RecordingSessionEntity(
        id = id,
        deviceAddress = "00:11:22:33:44:55",
        deviceName = "Headband",
        serviceUuid = "00000000-0000-0000-0000-000000000001",
        characteristicUuid = "00000000-0000-0000-0000-000000000002",
        startedAtMillis = startedAtMillis,
    )

    private fun packet(sessionId: String, sequence: Long) = RawPacketEntity(
        sessionId = sessionId,
        sequence = sequence,
        receivedAtMillis = 1_000 + sequence,
        payload = byteArrayOf(sequence.toByte(), 1, -1),
    )
}
