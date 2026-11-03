package com.feizhela.headband.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [MIGRATION_1_2] and [MIGRATION_2_3] on a real SQLite file: an archived v1
 * database is opened through [RecordingDatabase] (version 3), which runs the full migration
 * chain and validates against the compiled v3 entity schema before any query runs.
 *
 * The historical v1 fixture is created by hand. Room still performs `onValidateSchema`, and
 * PRAGMA assertions below independently inspect the resulting columns, foreign keys and indices.
 */
@RunWith(AndroidJUnit4::class)
class RecordingMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var opened: RecordingDatabase? = null

    @Before
    fun seedVersion1Database() {
        closeDatabase()
        val file = context.getDatabasePath(DATABASE_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(V1_RECORDING_SESSIONS)
            db.execSQL(V1_RECORDING_SESSIONS_INDEX)
            db.execSQL(V1_RAW_PACKETS)
            db.execSQL(V1_RAW_PACKETS_INDEX)
            db.execSQL(
                "INSERT INTO `recording_sessions` (`id`, `deviceAddress`, `deviceName`, `serviceUuid`, " +
                    "`characteristicUuid`, `startedAtMillis`, `endedAtMillis`, `status`, `endReason`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    V1_SESSION_ID,
                    DEVICE_ADDRESS,
                    DEVICE_NAME,
                    SERVICE_UUID,
                    CHARACTERISTIC_UUID,
                    V1_STARTED_AT_MILLIS,
                    V1_ENDED_AT_MILLIS,
                    "INTERRUPTED",
                    "PROCESS_RESTART",
                ),
            )
            (0L..2L).forEach { sequence ->
                db.execSQL(
                    "INSERT INTO `raw_packets` (`sessionId`, `sequence`, `receivedAtMillis`, `payload`) " +
                        "VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(
                        V1_SESSION_ID,
                        sequence,
                        V1_STARTED_AT_MILLIS + sequence,
                        byteArrayOf(sequence.toByte(), 1, -1),
                    ),
                )
            }
            db.version = 1
        }
    }

    @After
    fun closeDatabase() {
        opened?.close()
        opened = null
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrationKeepsVersion1RowsAndAddsNullTraceabilityColumns() = runBlocking {
        val dao = openVersion3().recordingDao

        val session = dao.observeSession(V1_SESSION_ID).first()!!
        assertEquals(DEVICE_ADDRESS, session.deviceAddress)
        assertEquals(DEVICE_NAME, session.deviceName)
        assertEquals(SERVICE_UUID, session.serviceUuid)
        assertEquals(CHARACTERISTIC_UUID, session.characteristicUuid)
        assertEquals(V1_STARTED_AT_MILLIS, session.startedAtMillis)
        assertEquals(V1_ENDED_AT_MILLIS, session.endedAtMillis)
        assertEquals("INTERRUPTED", session.status)
        assertEquals("PROCESS_RESTART", session.endReason)
        // A v1 archive predates provenance capture: the columns exist but stay unknown.
        assertNull(session.protocolVersion)
        assertNull(session.firmwareVersion)
        assertNull(session.appVersion)
        assertNull(session.modelVersion)
        assertEquals(listOf(V1_SESSION_ID), dao.observeSessions().first().map { it.id })

        val packets = dao.readPackets(V1_SESSION_ID, afterSequence = -1, limit = 10)
        assertEquals(listOf(0L, 1L, 2L), packets.map { it.sequence })
        assertEquals(V1_STARTED_AT_MILLIS, packets.first().receivedAtMillis)
        assertArrayEquals(byteArrayOf(0, 1, -1), packets.first().payload)
        assertArrayEquals(byteArrayOf(2, 1, -1), packets.last().payload)
    }

    @Test
    fun versionThreeSessionsRoundTripProvenanceColumns() = runBlocking {
        val dao = openVersion3().recordingDao

        dao.createSession(versionedSession(V2_SESSION_ID))

        val stored = dao.observeSession(V2_SESSION_ID).first()!!
        assertEquals("feizhela-ble/2", stored.protocolVersion)
        assertEquals("fw-2.4.1", stored.firmwareVersion)
        assertEquals("1.0.0-debug", stored.appVersion)
        assertEquals("rf-2026.10", stored.modelVersion)
        // The v1 row is still intact next to the new one.
        assertNotNull(dao.observeSession(V1_SESSION_ID).first())
        assertEquals(2, dao.observeSessions().first().size)
    }

    @Test
    fun migratedDatabaseStoresGapsAndCascadesThemWithTheirSession() = runBlocking {
        val dao = openVersion3().recordingDao
        dao.createSession(session(SECOND_SESSION_ID))

        val disconnect = dao.insertGap(
            gap(
                sessionId = V1_SESSION_ID,
                kind = "DISCONNECT",
                startedAtMillis = 1_200,
                endedAtMillis = 1_500,
                droppedEstimate = 31,
                detail = "gatt 133",
            ),
        )
        val overflow = dao.insertGap(
            gap(sessionId = V1_SESSION_ID, kind = "BUFFER_OVERFLOW", startedAtMillis = 1_200),
        )
        val deviceGap = dao.insertGap(
            gap(sessionId = SECOND_SESSION_ID, kind = "DEVICE_GAP", startedAtMillis = 900),
        )

        // Equal startedAtMillis falls back to the insertion (id) order; an unrecovered gap keeps
        // endedAtMillis/droppedEstimate/detail null.
        val gaps = dao.readGaps(V1_SESSION_ID)
        assertEquals(listOf(disconnect, overflow), gaps.map { it.id })
        assertEquals("DISCONNECT", gaps[0].kind)
        assertEquals(1_500L, gaps[0].endedAtMillis)
        assertEquals(31L, gaps[0].droppedEstimate)
        assertEquals("gatt 133", gaps[0].detail)
        assertNull(gaps[1].endedAtMillis)
        assertNull(gaps[1].droppedEstimate)
        assertNull(gaps[1].detail)
        assertEquals(2, dao.countGaps(V1_SESSION_ID))
        assertEquals(listOf(disconnect, overflow), dao.observeGaps(V1_SESSION_ID).first().map { it.id })
        assertEquals(1, dao.countGaps(SECOND_SESSION_ID))

        // The migrated foreign key rejects gaps that point at no session.
        try {
            dao.insertGap(gap(sessionId = "missing", kind = "DEVICE_GAP", startedAtMillis = 10))
            fail("Gaps without a recording session must be rejected")
        } catch (_: SQLiteConstraintException) {
            // Foreign keys are enabled by Room for the migrated database too.
        }

        dao.deleteSession(V1_SESSION_ID)
        assertEquals(0, dao.countGaps(V1_SESSION_ID))
        assertEquals(emptyList<AcquisitionGapEntity>(), dao.readGaps(V1_SESSION_ID))
        assertEquals(emptyList<AcquisitionGapEntity>(), dao.observeGaps(V1_SESSION_ID).first())
        assertEquals(listOf(deviceGap), dao.readGaps(SECOND_SESSION_ID).map { it.id })

        dao.deleteGaps(SECOND_SESSION_ID)
        assertEquals(0, dao.countGaps(SECOND_SESSION_ID))
        assertNotNull(dao.observeSession(SECOND_SESSION_ID).first())
    }

    @Test
    fun migratedSchemaMatchesTheVersion3Schema() = runBlocking {
        val database = openVersion3()
        // Room validates the migrated schema before this query can run.
        assertEquals(listOf(V1_SESSION_ID), database.recordingDao.observeSessions().first().map { it.id })
        database.close()
        opened = null

        val file = context.getDatabasePath(DATABASE_NAME)
        // Re-open the file outside Room so the assertions read the on-disk schema, not a cached one.
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(3, db.version)
            assertEquals(V3_SIGNAL_EPOCH_COLUMNS, columns(db, "signal_epochs"))
            assertEquals(
                foreignKeys(db, "acquisition_gaps"),
                foreignKeys(db, "signal_epochs"),
            )
            assertEquals(
                mapOf("index_signal_epochs_sessionId_startSampleIndex" to listOf("sessionId", "startSampleIndex")),
                createdIndices(db, "signal_epochs"),
            )
            assertEquals(V2_ACQUISITION_GAP_COLUMNS, columns(db, "acquisition_gaps"))
            assertEquals(V2_RECORDING_SESSION_COLUMNS, columns(db, "recording_sessions"))
            assertEquals(V1_RAW_PACKET_COLUMNS, columns(db, "raw_packets"))

            assertEquals(
                listOf(
                    ForeignKeySpec(
                        targetTable = "recording_sessions",
                        from = "sessionId",
                        to = "id",
                        onUpdate = "NO ACTION",
                        onDelete = "CASCADE",
                    ),
                ),
                foreignKeys(db, "acquisition_gaps"),
            )

            // Room's expected index name for Index(value = ["sessionId", "startedAtMillis"]).
            assertEquals(
                mapOf("index_acquisition_gaps_sessionId_startedAtMillis" to listOf("sessionId", "startedAtMillis")),
                createdIndices(db, "acquisition_gaps"),
            )
            // Indices that already existed in v1 must survive the upgrade untouched.
            assertEquals(
                mapOf("index_recording_sessions_startedAtMillis" to listOf("startedAtMillis")),
                createdIndices(db, "recording_sessions"),
            )
            assertEquals(
                mapOf("index_raw_packets_sessionId_receivedAtMillis" to listOf("sessionId", "receivedAtMillis")),
                createdIndices(db, "raw_packets"),
            )
        }
    }

    private fun openVersion3(): RecordingDatabase =
        Room.databaseBuilder(context, RecordingDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
            .also { opened = it }

    private fun session(id: String) = RecordingSessionEntity(
        id = id,
        deviceAddress = DEVICE_ADDRESS,
        deviceName = DEVICE_NAME,
        serviceUuid = SERVICE_UUID,
        characteristicUuid = CHARACTERISTIC_UUID,
        startedAtMillis = 5_000,
    )

    private fun versionedSession(id: String): RecordingSessionEntity = session(id).copy(
        protocolVersion = "feizhela-ble/2",
        firmwareVersion = "fw-2.4.1",
        appVersion = "1.0.0-debug",
        modelVersion = "rf-2026.10",
    )

    private fun gap(
        sessionId: String,
        kind: String,
        startedAtMillis: Long,
        endedAtMillis: Long? = null,
        droppedEstimate: Long? = null,
        detail: String? = null,
    ) = AcquisitionGapEntity(
        sessionId = sessionId,
        kind = kind,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        droppedEstimate = droppedEstimate,
        detail = detail,
    )

    private fun columns(db: SQLiteDatabase, table: String): List<ColumnSpec> =
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ColumnSpec(
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0,
                            primaryKey = cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                        ),
                    )
                }
            }
        }

    private fun foreignKeys(db: SQLiteDatabase, table: String): List<ForeignKeySpec> =
        db.rawQuery("PRAGMA foreign_key_list(`$table`)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ForeignKeySpec(
                            targetTable = cursor.getString(cursor.getColumnIndexOrThrow("table")),
                            from = cursor.getString(cursor.getColumnIndexOrThrow("from")),
                            to = cursor.getString(cursor.getColumnIndexOrThrow("to")),
                            onUpdate = cursor.getString(cursor.getColumnIndexOrThrow("on_update")),
                            onDelete = cursor.getString(cursor.getColumnIndexOrThrow("on_delete")),
                        ),
                    )
                }
            }
        }

    /** Only indices created by `CREATE INDEX` (origin `c`); SQLite-owned ones cannot be schema facts. */
    private fun createdIndices(db: SQLiteDatabase, table: String): Map<String, List<String>> {
        val names = db.rawQuery("PRAGMA index_list(`$table`)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("origin")) == "c") {
                        add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
            }
        }
        return names.associateWith { index -> indexColumns(db, index) }
    }

    private fun indexColumns(db: SQLiteDatabase, index: String): List<String> =
        db.rawQuery("PRAGMA index_info(`$index`)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        }
}

private data class ColumnSpec(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val primaryKey: Int,
)

private data class ForeignKeySpec(
    val targetTable: String,
    val from: String,
    val to: String,
    val onUpdate: String,
    val onDelete: String,
)

// The v1 schema exactly as Room exported it to
// app/schemas/com.feizhela.headband.storage.RecordingDatabase/1.json.
private const val V1_RECORDING_SESSIONS =
    "CREATE TABLE IF NOT EXISTS `recording_sessions` (`id` TEXT NOT NULL, `deviceAddress` TEXT NOT NULL, " +
        "`deviceName` TEXT, `serviceUuid` TEXT NOT NULL, `characteristicUuid` TEXT NOT NULL, " +
        "`startedAtMillis` INTEGER NOT NULL, `endedAtMillis` INTEGER, `status` TEXT NOT NULL, " +
        "`endReason` TEXT, PRIMARY KEY(`id`))"

private const val V1_RECORDING_SESSIONS_INDEX =
    "CREATE INDEX IF NOT EXISTS `index_recording_sessions_startedAtMillis` " +
        "ON `recording_sessions` (`startedAtMillis`)"

private const val V1_RAW_PACKETS =
    "CREATE TABLE IF NOT EXISTS `raw_packets` (`sessionId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, " +
        "`receivedAtMillis` INTEGER NOT NULL, `payload` BLOB NOT NULL, PRIMARY KEY(`sessionId`, `sequence`), " +
        "FOREIGN KEY(`sessionId`) REFERENCES `recording_sessions`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE )"

private const val V1_RAW_PACKETS_INDEX =
    "CREATE INDEX IF NOT EXISTS `index_raw_packets_sessionId_receivedAtMillis` " +
        "ON `raw_packets` (`sessionId`, `receivedAtMillis`)"

private val V2_ACQUISITION_GAP_COLUMNS = listOf(
    ColumnSpec(name = "id", type = "INTEGER", notNull = true, primaryKey = 1),
    ColumnSpec(name = "sessionId", type = "TEXT", notNull = true, primaryKey = 0),
    ColumnSpec(name = "kind", type = "TEXT", notNull = true, primaryKey = 0),
    ColumnSpec(name = "startedAtMillis", type = "INTEGER", notNull = true, primaryKey = 0),
    ColumnSpec(name = "endedAtMillis", type = "INTEGER", notNull = false, primaryKey = 0),
    ColumnSpec(name = "droppedEstimate", type = "INTEGER", notNull = false, primaryKey = 0),
    ColumnSpec(name = "detail", type = "TEXT", notNull = false, primaryKey = 0),
)

private val V2_RECORDING_SESSION_COLUMNS = listOf(
    ColumnSpec(name = "id", type = "TEXT", notNull = true, primaryKey = 1),
    ColumnSpec(name = "deviceAddress", type = "TEXT", notNull = true, primaryKey = 0),
    ColumnSpec(name = "deviceName", type = "TEXT", notNull = false, primaryKey = 0),
    ColumnSpec(name = "serviceUuid", type = "TEXT", notNull = true, primaryKey = 0),
    ColumnSpec(name = "characteristicUuid", type = "TEXT", notNull = true, primaryKey = 0),
    ColumnSpec(name = "startedAtMillis", type = "INTEGER", notNull = true, primaryKey = 0),
    ColumnSpec(name = "endedAtMillis", type = "INTEGER", notNull = false, primaryKey = 0),
    ColumnSpec(name = "status", type = "TEXT", notNull = true, primaryKey = 0),
    ColumnSpec(name = "endReason", type = "TEXT", notNull = false, primaryKey = 0),
    ColumnSpec(name = "protocolVersion", type = "TEXT", notNull = false, primaryKey = 0),
    ColumnSpec(name = "firmwareVersion", type = "TEXT", notNull = false, primaryKey = 0),
    ColumnSpec(name = "appVersion", type = "TEXT", notNull = false, primaryKey = 0),
    ColumnSpec(name = "modelVersion", type = "TEXT", notNull = false, primaryKey = 0),
)

private val V3_SIGNAL_EPOCH_COLUMNS = listOf(
    ColumnSpec(name = "sessionId", type = "TEXT", notNull = true, primaryKey = 1),
    ColumnSpec(name = "epochIndex", type = "INTEGER", notNull = true, primaryKey = 2),
    ColumnSpec(name = "startSampleIndex", type = "INTEGER", notNull = true, primaryKey = 0),
    ColumnSpec(name = "startedAtMillis", type = "INTEGER", notNull = true, primaryKey = 0),
    ColumnSpec(name = "firstPacketSequence", type = "INTEGER", notNull = true, primaryKey = 0),
    ColumnSpec(name = "lastPacketSequence", type = "INTEGER", notNull = true, primaryKey = 0),
    ColumnSpec(name = "receivedSamples", type = "INTEGER", notNull = true, primaryKey = 0),
    ColumnSpec(name = "expectedSamples", type = "INTEGER", notNull = true, primaryKey = 0),
    ColumnSpec(name = "quality", type = "TEXT", notNull = true, primaryKey = 0),
    ColumnSpec(name = "reason", type = "TEXT", notNull = false, primaryKey = 0),
    ColumnSpec(name = "rawSamplingRate", type = "REAL", notNull = true, primaryKey = 0),
    ColumnSpec(name = "analysisSamplingRate", type = "REAL", notNull = true, primaryKey = 0),
    ColumnSpec(name = "algorithmVersion", type = "TEXT", notNull = true, primaryKey = 0),
    ColumnSpec(name = "configJson", type = "TEXT", notNull = true, primaryKey = 0),
    ColumnSpec(name = "componentsJson", type = "TEXT", notNull = false, primaryKey = 0),
    ColumnSpec(name = "analysisWindow", type = "INTEGER", notNull = false, primaryKey = 0),
    ColumnSpec(name = "residualRatio", type = "REAL", notNull = false, primaryKey = 0),
    ColumnSpec(name = "verified", type = "INTEGER", notNull = false, primaryKey = 0),
)

private val V1_RAW_PACKET_COLUMNS = listOf(
    ColumnSpec(name = "sessionId", type = "TEXT", notNull = true, primaryKey = 1),
    ColumnSpec(name = "sequence", type = "INTEGER", notNull = true, primaryKey = 2),
    ColumnSpec(name = "receivedAtMillis", type = "INTEGER", notNull = true, primaryKey = 0),
    ColumnSpec(name = "payload", type = "BLOB", notNull = true, primaryKey = 0),
)

private const val DATABASE_NAME = "recording-migration-test.db"
private const val V1_SESSION_ID = "v1-session"
private const val V2_SESSION_ID = "v2-session"
private const val SECOND_SESSION_ID = "second-session"
private const val DEVICE_ADDRESS = "00:11:22:33:44:55"
private const val DEVICE_NAME = "Headband"
private const val SERVICE_UUID = "00000000-0000-0000-0000-000000000001"
private const val CHARACTERISTIC_UUID = "00000000-0000-0000-0000-000000000002"
private const val V1_STARTED_AT_MILLIS = 1_000L
private const val V1_ENDED_AT_MILLIS = 2_500L
