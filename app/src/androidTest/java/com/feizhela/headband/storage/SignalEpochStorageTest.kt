package com.feizhela.headband.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignalEpochStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var opened: RecordingDatabase? = null

    @After
    fun closeDatabase() {
        opened?.close()
        opened = null
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
    }

    @Test
    fun epochsRoundTripInIndexOrderAndUpsertReplacesAllAnalysisFields() = runBlocking {
        val database = openInMemory()
        val dao = database.signalEpochDao
        database.recordingDao.createSession(session("first"))
        database.recordingDao.createSession(session("other"))
        assertEquals(emptyList<SignalEpochEntity>(), dao.readEpochs("first"))
        assertEquals(emptyList<SignalEpochEntity>(), dao.observeEpochs("first").first())
        val complete = epoch("first", 1).copy(
            startSampleIndex = 3120,
            startedAtMillis = 31_000,
            firstPacketSequence = 100,
            lastPacketSequence = 199,
            expectedSamples = 3000,
            rawSamplingRate = 100.0,
            analysisSamplingRate = 25.0,
            componentsJson = "[{\"amplitude\":2.5,\"frequencyHz\":1.2,\"phaseRadians\":-0.4}]",
            analysisWindow = 600,
            residualRatio = 0.125,
            verified = true,
        )
        dao.upsertEpoch(epoch("first", 2).copy(startSampleIndex = 0))
        dao.upsertEpoch(complete)
        dao.upsertEpoch(epoch("other", 1))
        assertEquals(listOf(1L, 2L), dao.readEpochs("first").map { it.epochIndex })
        assertEquals(complete, dao.readEpochs("first").first())
        assertEquals(dao.readEpochs("first"), dao.observeEpochs("first").first())

        val revised = complete.copy(verified = false)
        val observedUpdate = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) {
                dao.observeEpochs("first").first { it.firstOrNull() == revised }
            }
        }
        dao.upsertEpoch(revised)
        assertEquals(revised, observedUpdate.await().first())

        val failed = complete.copy(
            receivedSamples = 73,
            quality = "ERROR",
            reason = "analysis failed",
            componentsJson = null,
            analysisWindow = null,
            residualRatio = null,
            verified = null,
        )
        dao.upsertEpoch(failed)
        assertEquals(listOf(failed, epoch("first", 2).copy(startSampleIndex = 0)), dao.readEpochs("first"))
        assertEquals(listOf(1L), dao.readEpochs("other").map { it.epochIndex })

        dao.deleteEpochs("first")
        assertEquals(emptyList<SignalEpochEntity>(), dao.readEpochs("first"))
        assertEquals(emptyList<SignalEpochEntity>(), dao.observeEpochs("first").first())
        assertNotNull(database.recordingDao.observeSession("first").first())
        assertEquals(listOf(1L), dao.readEpochs("other").map { it.epochIndex })
    }

    @Test
    fun partialInvalidAndErrorEpochsKeepNullAnalysisAndInputDefaults() = runBlocking {
        val database = openInMemory()
        database.recordingDao.createSession(session("first"))
        val rows = listOf("PARTIAL", "INVALID", "ERROR").mapIndexed { index, quality ->
            epoch("first", index.toLong()).copy(
                receivedSamples = 16,
                quality = quality,
                reason = "input incomplete",
            )
        }
        rows.forEach { database.signalEpochDao.upsertEpoch(it) }
        assertEquals(rows, database.signalEpochDao.readEpochs("first"))
        rows.forEach { row ->
            assertEquals(3120, row.expectedSamples)
            assertEquals(104.0, row.rawSamplingRate, 0.0)
            assertEquals(20.0, row.analysisSamplingRate, 0.0)
            assertNull(row.componentsJson)
            assertNull(row.analysisWindow)
            assertNull(row.residualRatio)
            assertNull(row.verified)
        }
    }

    @Test
    fun epochsRejectOrphansAndCascadeOnlyWithTheirOwningSession() = runBlocking {
        val database = openInMemory()
        val dao = database.signalEpochDao
        database.recordingDao.createSession(session("first"))
        database.recordingDao.createSession(session("other"))
        dao.upsertEpoch(epoch("first", 0))
        dao.upsertEpoch(epoch("other", 0))
        try {
            dao.upsertEpoch(epoch("missing", 0))
            fail("An epoch without a recording session must be rejected")
        } catch (_: SQLiteConstraintException) {
            // Room enables the declared foreign key for in-memory databases as well.
        }
        database.recordingDao.deleteSession("first")
        assertEquals(emptyList<SignalEpochEntity>(), dao.readEpochs("first"))
        assertEquals(listOf(epoch("other", 0)), dao.readEpochs("other"))
    }

    @Test
    fun pendingSessionsRequireTerminalRawCaptureAndNoAttemptForRequestedAlgorithm() = runBlocking {
        val database = openInMemory()
        val recordingDao = database.recordingDao
        val dao = database.signalEpochDao
        listOf("active", "empty", "pending", "older-algorithm", "attempted").forEach { id ->
            recordingDao.createSession(session(id))
            if (id != "active") recordingDao.finishSession(id, "INTERRUPTED", 2_000, "STOPPED")
            if (id != "empty") recordingDao.insertPackets(listOf(packet(id, 0)))
        }
        dao.upsertEpoch(epoch("older-algorithm", 0).copy(algorithmVersion = "previous"))
        dao.upsertEpoch(epoch("attempted", 0).copy(quality = "INVALID", reason = "bad input"))
        assertEquals(listOf("older-algorithm", "pending"), dao.pendingSessionIds(ALGORITHM_VERSION))
        assertEquals(listOf("attempted", "pending"), dao.pendingSessionIds("previous"))
    }

    @Test
    fun migrationFromVersion2PreservesPacketsSessionsAndGapsAndPassesRoomValidation() = runBlocking {
        seedArchive(version = 2)
        val database = openMigrated()
        assertMigratedArchive(database)
        val stored = database.recordingDao.observeSession(ARCHIVE_SESSION_ID).first()!!
        assertEquals("protocol-v2", stored.protocolVersion)
        assertEquals("firmware-v2", stored.firmwareVersion)
        assertEquals("app-v2", stored.appVersion)
        assertEquals("model-v2", stored.modelVersion)
        val gap = database.recordingDao.readGaps(ARCHIVE_SESSION_ID).single()
        assertEquals("DISCONNECT", gap.kind)
        assertEquals(1_200L, gap.startedAtMillis)
        assertEquals(1_300L, gap.endedAtMillis)
        assertEquals(10L, gap.droppedEstimate)
        assertEquals("archived gap", gap.detail)
        assertMigratedEpochForeignKey(database)
        assertEquals(emptyList<AcquisitionGapEntity>(), database.recordingDao.readGaps(ARCHIVE_SESSION_ID))
    }

    @Test
    fun migrationChainFromVersion1PreservesPacketsAndPassesRoomValidation() = runBlocking {
        seedArchive(version = 1)
        val database = openMigrated()
        assertMigratedArchive(database)
        val stored = database.recordingDao.observeSession(ARCHIVE_SESSION_ID).first()!!
        assertNull(stored.protocolVersion)
        assertNull(stored.firmwareVersion)
        assertNull(stored.appVersion)
        assertNull(stored.modelVersion)
        assertEquals(emptyList<AcquisitionGapEntity>(), database.recordingDao.readGaps(ARCHIVE_SESSION_ID))
        assertMigratedEpochForeignKey(database)
    }

    private fun openInMemory(): RecordingDatabase =
        Room.inMemoryDatabaseBuilder(context, RecordingDatabase::class.java)
            .build()
            .also { opened = it }

    private fun openMigrated(): RecordingDatabase =
        Room.databaseBuilder(context, RecordingDatabase::class.java, MIGRATION_DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
            .also { opened = it }

    /** Use actual exported historical schemas; opening with Room validates against compiled v3. */
    private fun seedArchive(version: Int) {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        val schema = instrumentation.context.assets.open(
            "com.feizhela.headband.storage.RecordingDatabase/$version.json",
        ).bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(MIGRATION_DATABASE_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val tableName = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
                val indices = entity.getJSONArray("indices")
                for (indexNumber in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(indexNumber).getString("createSql")
                            .replace("\${TABLE_NAME}", tableName),
                    )
                }
            }
            val setupQueries = schema.getJSONArray("setupQueries")
            for (index in 0 until setupQueries.length()) db.execSQL(setupQueries.getString(index))
            db.execSQL(
                "INSERT INTO recording_sessions " +
                    "(id, deviceAddress, deviceName, serviceUuid, characteristicUuid, " +
                    "startedAtMillis, endedAtMillis, status, endReason) " +
                    "VALUES (?, 'address', 'Headband', 'service', 'characteristic', " +
                    "1000, 2000, 'INTERRUPTED', 'DISCONNECT')",
                arrayOf<Any>(ARCHIVE_SESSION_ID),
            )
            for (sequence in 0L..2L) {
                val packet = packet(ARCHIVE_SESSION_ID, sequence)
                db.execSQL(
                    "INSERT INTO raw_packets (sessionId, sequence, receivedAtMillis, payload) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(packet.sessionId, packet.sequence, packet.receivedAtMillis, packet.payload),
                )
            }
            if (version == 2) {
                db.execSQL(
                    "UPDATE recording_sessions SET protocolVersion = 'protocol-v2', " +
                        "firmwareVersion = 'firmware-v2', appVersion = 'app-v2', modelVersion = 'model-v2'",
                )
                db.execSQL(
                    "INSERT INTO acquisition_gaps (sessionId, kind, startedAtMillis, endedAtMillis, " +
                        "droppedEstimate, detail) VALUES (?, 'DISCONNECT', 1200, 1300, 10, 'archived gap')",
                    arrayOf<Any>(ARCHIVE_SESSION_ID),
                )
            }
            db.version = version
        }
    }

    private suspend fun assertMigratedArchive(database: RecordingDatabase) {
        // The first open/query runs Room's generated schema validation after the migration.
        val stored = database.recordingDao.observeSession(ARCHIVE_SESSION_ID).first()!!
        assertEquals("INTERRUPTED", stored.status)
        assertEquals("DISCONNECT", stored.endReason)
        assertEquals(1_000L, stored.startedAtMillis)
        assertEquals(2_000L, stored.endedAtMillis)
        val packets = database.recordingDao.readPackets(ARCHIVE_SESSION_ID, -1, 10)
        assertEquals(listOf(0L, 1L, 2L), packets.map { it.sequence })
        packets.forEach { storedPacket ->
            val expected = packet(ARCHIVE_SESSION_ID, storedPacket.sequence)
            assertEquals(expected.receivedAtMillis, storedPacket.receivedAtMillis)
            assertArrayEquals(expected.payload, storedPacket.payload)
        }
        assertEquals(3, database.openHelper.writableDatabase.version)
        assertEquals(emptyList<SignalEpochEntity>(), database.signalEpochDao.readEpochs(ARCHIVE_SESSION_ID))
        val row = epoch(ARCHIVE_SESSION_ID, 0)
        database.signalEpochDao.upsertEpoch(row)
        assertEquals(listOf(row), database.signalEpochDao.readEpochs(ARCHIVE_SESSION_ID))
    }

    private suspend fun assertMigratedEpochForeignKey(database: RecordingDatabase) {
        try {
            database.signalEpochDao.upsertEpoch(epoch("missing", 0))
            fail("Migrated epochs must reject missing sessions")
        } catch (_: SQLiteConstraintException) {
            // The migrated table must enforce the same FK as a newly created table.
        }
        database.recordingDao.deleteSession(ARCHIVE_SESSION_ID)
        assertEquals(emptyList<SignalEpochEntity>(), database.signalEpochDao.readEpochs(ARCHIVE_SESSION_ID))
        assertEquals(emptyList<RawPacketEntity>(), database.recordingDao.readPackets(ARCHIVE_SESSION_ID, -1, 10))
    }

    private fun session(id: String) = RecordingSessionEntity(
        id = id,
        deviceAddress = "address",
        deviceName = "Headband",
        serviceUuid = "service",
        characteristicUuid = "characteristic",
        startedAtMillis = 1_000,
    )

    private fun packet(sessionId: String, sequence: Long) = RawPacketEntity(
        sessionId = sessionId,
        sequence = sequence,
        receivedAtMillis = 1_000 + sequence,
        payload = byteArrayOf(sequence.toByte(), 1, -1),
    )

    private fun epoch(sessionId: String, epochIndex: Long) = SignalEpochEntity(
        sessionId = sessionId,
        epochIndex = epochIndex,
        startSampleIndex = epochIndex * 3120,
        startedAtMillis = 1_000 + epochIndex * 30_000,
        firstPacketSequence = epochIndex * 100,
        lastPacketSequence = epochIndex * 100 + 99,
        receivedSamples = 3120,
        quality = "VALID",
        algorithmVersion = ALGORITHM_VERSION,
        configJson = "{\"windowSeconds\":30}",
    )

    private companion object {
        const val ALGORITHM_VERSION = "spectral-v1"
        const val MIGRATION_DATABASE_NAME = "signal-epoch-migration-test.db"
        const val ARCHIVE_SESSION_ID = "archive"
    }
}
