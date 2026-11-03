package com.feizhela.headband.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingExportTest {
    private lateinit var database: RecordingDatabase
    private lateinit var dao: RecordingDao
    private lateinit var exporter: RecordingExporter
    private lateinit var library: RecordingLibrary

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RecordingDatabase::class.java,
        ).build()
        dao = database.recordingDao
        exporter = RecordingExporter(dao)
        library = RecordingLibrary(dao)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun csvExportIsSequenceOrderedPagedAndUsesLowercaseHex() = runBlocking {
        dao.createSession(session("csv", startedAtMillis = 1_700_000_000_000))
        dao.finishSession("csv", "COMPLETED", 1_700_000_100_000, null)
        // Inserted out of order; paging with pageSize = 2 forces three readPackets rounds.
        dao.insertPackets(
            listOf(
                packet("csv", 3, byteArrayOf(0xFF.toByte(), 0x80.toByte())),
                packet("csv", 0, byteArrayOf(0x00, 0x7F)),
                packet("csv", 4, byteArrayOf(0x01, 0x02)),
                packet("csv", 2, byteArrayOf(0x0F)),
                packet("csv", 1, byteArrayOf(0xAB.toByte(), 0xCD.toByte())),
            ),
        )

        val sink = ByteArrayOutputStream()
        val result = exporter.exportSessionCsv("csv", sink, pageSize = 2)

        assertTrue(result.exceptionOrNull()?.toString().orEmpty(), result.isSuccess)
        assertEquals(5L, result.getOrThrow())
        val lines = String(sink.toByteArray(), Charsets.UTF_8).lines()
        assertTrue("export must start with a comment header", lines[0].startsWith("#"))
        listOf(
            "exportSchema=1",
            "sessionId=csv",
            "deviceAddress=00:11:22:33:44:55",
            "startedAtMillis=1700000000000",
            "status=COMPLETED",
            "unit=millis",
            "payload=hex",
            "source=raw_packets",
            "protocolVersion=ble-1",
            "firmwareVersion=fw-2",
            "appVersion=1.0.0",
            "modelVersion=model-3",
        ).forEach { field ->
            assertTrue("header is missing '$field': ${lines[0]}", lines[0].contains(field))
        }
        assertEquals("sequence,receivedAtMillis,payloadHex", lines[1])
        assertEquals(
            listOf(
                "0,1000,007f",
                "1,1001,abcd",
                "2,1002,0f",
                "3,1003,ff80",
                "4,1004,0102",
            ),
            lines.drop(2).filter { it.isNotEmpty() },
        )
    }

    @Test
    fun emptySessionExportsHeaderOnlyAndZeroRows() = runBlocking {
        dao.createSession(session("empty"))

        val sink = ByteArrayOutputStream()
        val result = exporter.exportSessionCsv("empty", sink)

        assertTrue(result.exceptionOrNull()?.toString().orEmpty(), result.isSuccess)
        assertEquals(0L, result.getOrThrow())
        val lines = String(sink.toByteArray(), Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("#"))
        assertTrue(lines[0].contains("sessionId=empty"))
        assertEquals("sequence,receivedAtMillis,payloadHex", lines[1])
    }

    @Test
    fun missingSessionFailsWithoutWritingPartialFile() = runBlocking {
        val sink = ByteArrayOutputStream()

        val result = exporter.exportSessionCsv("does-not-exist", sink)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertTrue(
            "message must identify the missing session: ${error?.message}",
            error?.message?.contains("does-not-exist") == true,
        )
        assertEquals("no bytes may be written for a missing session", 0, sink.size())
    }

    @Test
    fun summaryJsonContainsMetadataAndGapsButNoPacketPayload() = runBlocking {
        dao.createSession(session("summary"))
        dao.finishSession("summary", "INTERRUPTED", 5_000, "BLE_DISCONNECTED")
        dao.insertGap(
            gap(
                sessionId = "summary",
                kind = "PACKET_DROP",
                startedAtMillis = 1_500,
                endedAtMillis = 1_800,
                droppedEstimate = 12,
                detail = "counter jump",
            ),
        )
        dao.insertGap(
            gap(
                sessionId = "summary",
                kind = "SUBSCRIBER_OVERFLOW",
                startedAtMillis = 2_000,
            ),
        )
        assertEquals(2, dao.countGaps("summary"))

        val sink = ByteArrayOutputStream()
        val result = exporter.exportSessionSummaryJson("summary", sink)

        assertTrue(result.exceptionOrNull()?.toString().orEmpty(), result.isSuccess)
        val raw = String(sink.toByteArray(), Charsets.UTF_8)
        assertFalse("summary must not embed per-packet payloads", raw.contains("payload", ignoreCase = true))

        val json = JSONObject(raw)
        val session = json.getJSONObject("session")
        assertEquals("summary", session.getString("id"))
        assertEquals("INTERRUPTED", session.getString("status"))
        assertEquals("BLE_DISCONNECTED", session.getString("endReason"))
        assertEquals(5_000L, session.getLong("endedAtMillis"))
        assertEquals("ble-1", session.getString("protocolVersion"))
        assertEquals("model-3", session.getString("modelVersion"))
        assertEquals(2, json.getInt("gapCount"))

        val gaps = json.getJSONArray("gaps")
        assertEquals(2, gaps.length())
        val parsed = (0 until gaps.length()).map { gaps.getJSONObject(it) }
        val drop = parsed.first { it.getString("kind") == "PACKET_DROP" }
        assertEquals("summary", drop.getString("sessionId"))
        assertEquals(1_500L, drop.getLong("startedAtMillis"))
        assertEquals(1_800L, drop.getLong("endedAtMillis"))
        assertEquals(12L, drop.getLong("droppedEstimate"))
        assertEquals("counter jump", drop.getString("detail"))
        val overflow = parsed.first { it.getString("kind") == "SUBSCRIBER_OVERFLOW" }
        assertTrue(overflow.isNull("endedAtMillis"))
        assertTrue(overflow.isNull("droppedEstimate"))
        assertTrue(overflow.isNull("detail"))
    }

    @Test
    fun summaryExportsOrderedEpochEvidenceAndQualityCountsWithoutChangingRawPackets() = runBlocking {
        dao.createSession(session("epochs"))
        dao.createSession(session("other"))
        val rawPacket = packet("epochs", 0, byteArrayOf(0, 127, -1))
        dao.insertPackets(listOf(rawPacket))
        val spectral = epoch("epochs", 0).copy(
            quality = "SPECTRAL_ONLY",
            reason = "PHYSIOLOGICAL_QUALITY_UNCALIBRATED",
            componentsJson = "[{\"amplitude\":2.0,\"frequencyHz\":0.8,\"phaseRadians\":-0.2}]",
            analysisWindow = 600,
            residualRatio = 0.125,
            verified = false,
        )
        val partial = epoch("epochs", 1).copy(receivedSamples = 47, quality = "INCOMPLETE", reason = "PARTIAL_WINDOW")
        database.signalEpochDao.upsertEpoch(partial)
        database.signalEpochDao.upsertEpoch(epoch("other", 0))
        database.signalEpochDao.upsertEpoch(spectral)
        val sink = ByteArrayOutputStream()

        val result = RecordingExporter(dao, database.signalEpochDao).exportSessionSummaryJson("epochs", sink)

        assertTrue(result.exceptionOrNull()?.toString().orEmpty(), result.isSuccess)
        val json = JSONObject(sink.toString("UTF-8"))
        assertEquals(2, json.getInt("exportSchema"))
        assertTrue(json.getBoolean("analysisStoreAvailable"))
        assertTrue(json.getBoolean("analysisAvailable"))
        assertEquals("spectral-only", json.getString("analysisKind"))
        assertEquals(2, json.getInt("epochCount"))
        assertEquals(1, json.getInt("analyzedEpochCount"))
        assertEquals(1, json.getJSONObject("qualityCounts").getInt("SPECTRAL_ONLY"))
        assertEquals(1, json.getJSONObject("qualityCounts").getInt("INCOMPLETE"))
        val epochs = json.getJSONArray("epochs")
        assertEquals(2, epochs.length())
        val first = epochs.getJSONObject(0)
        assertEquals("epochs", first.getString("sessionId"))
        assertEquals(0L, first.getLong("epochIndex"))
        assertEquals(spectral.startSampleIndex, first.getLong("startSampleIndex"))
        assertEquals(spectral.startedAtMillis, first.getLong("startedAtMillis"))
        assertEquals(spectral.firstPacketSequence, first.getLong("firstPacketSequence"))
        assertEquals(spectral.lastPacketSequence, first.getLong("lastPacketSequence"))
        assertEquals(3120, first.getInt("receivedSamples"))
        assertEquals(3120, first.getInt("expectedSamples"))
        assertEquals("SPECTRAL_ONLY", first.getString("quality"))
        assertEquals(spectral.reason, first.getString("reason"))
        assertEquals(104.0, first.getDouble("rawSamplingRate"), 0.0)
        assertEquals(20.0, first.getDouble("analysisSamplingRate"), 0.0)
        assertEquals(spectral.algorithmVersion, first.getString("algorithmVersion"))
        assertEquals(spectral.configJson, first.getString("configJson"))
        assertEquals(spectral.componentsJson, first.getString("componentsJson"))
        assertEquals(600, first.getInt("analysisWindow"))
        assertEquals(0.125, first.getDouble("residualRatio"), 0.0)
        assertFalse(first.getBoolean("verified"))
        assertTrue(first.getBoolean("analysisAvailable"))
        val second = epochs.getJSONObject(1)
        assertEquals(1L, second.getLong("epochIndex"))
        assertEquals(47, second.getInt("receivedSamples"))
        assertEquals("PARTIAL_WINDOW", second.getString("reason"))
        listOf("componentsJson", "analysisWindow", "residualRatio", "verified").forEach { field ->
            assertTrue("partial evidence must keep $field null", second.isNull(field))
        }
        assertFalse(second.getBoolean("analysisAvailable"))
        assertFalse(sink.toString("UTF-8").contains("payload", ignoreCase = true))
        org.junit.Assert.assertArrayEquals(rawPacket.payload, dao.readPackets("epochs", -1, 10).single().payload)
    }

    @Test
    fun summaryDistinguishesUnwiredEmptyAndRejectedAnalysis() = runBlocking {
        dao.createSession(session("availability"))
        database.signalEpochDao.upsertEpoch(epoch("availability", 0).copy(quality = "REJECTED", reason = "COUNTER_RESET"))
        val unwiredSink = ByteArrayOutputStream()
        exporter.exportSessionSummaryJson("availability", unwiredSink).getOrThrow()
        val unwired = JSONObject(unwiredSink.toString("UTF-8"))
        assertFalse(unwired.getBoolean("analysisStoreAvailable"))
        assertFalse(unwired.getBoolean("analysisAvailable"))
        assertEquals(0, unwired.getInt("epochCount"))
        assertEquals(0, unwired.getJSONArray("epochs").length())

        val wired = RecordingExporter(dao, database.signalEpochDao)
        val rejectedSink = ByteArrayOutputStream()
        wired.exportSessionSummaryJson("availability", rejectedSink).getOrThrow()
        val rejected = JSONObject(rejectedSink.toString("UTF-8"))
        assertTrue(rejected.getBoolean("analysisStoreAvailable"))
        assertFalse(rejected.getBoolean("analysisAvailable"))
        assertEquals(1, rejected.getInt("epochCount"))
        assertEquals(0, rejected.getInt("analyzedEpochCount"))
        assertEquals(1, rejected.getJSONObject("qualityCounts").getInt("REJECTED"))

        dao.createSession(session("empty-analysis"))
        val emptySink = ByteArrayOutputStream()
        wired.exportSessionSummaryJson("empty-analysis", emptySink).getOrThrow()
        val empty = JSONObject(emptySink.toString("UTF-8"))
        assertTrue(empty.getBoolean("analysisStoreAvailable"))
        assertFalse(empty.getBoolean("analysisAvailable"))
        assertEquals(0, empty.getInt("epochCount"))
        assertEquals(0, empty.getJSONObject("qualityCounts").length())
    }

    @Test
    fun summaryEpochReadFailureAndMissingSessionWriteNothing() = runBlocking {
        dao.createSession(session("failure"))
        val failedDao = object : SignalEpochDao by database.signalEpochDao {
            override suspend fun readEpochs(sessionId: String): List<SignalEpochEntity> =
                throw java.io.IOException("epoch read failed")
        }
        val wired = RecordingExporter(dao, failedDao)
        val sink = ByteArrayOutputStream()
        assertTrue(wired.exportSessionSummaryJson("failure", sink).isFailure)
        assertEquals(0, sink.size())
        assertTrue(wired.exportSessionSummaryJson("missing", sink).exceptionOrNull() is IllegalStateException)
        assertEquals(0, sink.size())
    }

    @Test
    fun libraryEpochFlowTracksRoomUpdatesAndSessionCascade() = runBlocking {
        dao.createSession(session("observed"))
        dao.createSession(session("other"))
        assertTrue(library.observeEpochs("observed").first().isEmpty())
        val wired = RecordingLibrary(dao, database.signalEpochDao)
        assertTrue(wired.observeEpochs("observed").first().isEmpty())
        val later = epoch("observed", 1)
        database.signalEpochDao.upsertEpoch(later)
        database.signalEpochDao.upsertEpoch(epoch("other", 0))
        assertEquals(listOf(later), wired.observeEpochs("observed").first())
        val earlier = epoch("observed", 0)
        val update = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { wired.observeEpochs("observed").first { it.size == 2 } }
        }
        database.signalEpochDao.upsertEpoch(earlier)
        assertEquals(listOf(earlier, later), update.await())
        wired.deleteSession("observed").getOrThrow()
        assertTrue(wired.observeEpochs("observed").first().isEmpty())
        assertEquals(listOf(epoch("other", 0)), wired.observeEpochs("other").first())
    }

    @Test
    fun deleteSessionCascadesToPacketsAndGapsAndIsRepeatable() = runBlocking {
        dao.createSession(session("first"))
        dao.createSession(session("other"))
        dao.insertPackets(
            listOf(
                packet("first", 0),
                packet("first", 1),
                packet("first", 2),
                packet("other", 0),
            ),
        )
        dao.insertGap(gap("first", "PACKET_DROP", 1_500))
        dao.insertGap(gap("other", "PACKET_DROP", 1_500))

        assertEquals(3L, library.packetCount("first"))
        val deletion = library.deleteSession("first")
        assertTrue(deletion.exceptionOrNull()?.toString(), deletion.isSuccess)
        assertEquals("packet count before deletion is the audit value", 3L, deletion.getOrThrow())

        assertNull(dao.observeSession("first").first())
        assertEquals(emptyList<RawPacketEntity>(), dao.readPackets("first", afterSequence = -1, limit = 10))
        assertTrue(dao.readGaps("first").isEmpty())
        assertEquals(0, dao.countGaps("first"))
        assertTrue(dao.observeGaps("first").first().isEmpty())
        assertTrue(library.observeGaps("first").first().isEmpty())

        // Cascade must be scoped to the deleted session.
        assertNotNull(dao.observeSession("other").first())
        assertEquals(1, dao.readPackets("other", afterSequence = -1, limit = 10).size)
        assertEquals(1, dao.readGaps("other").size)
        assertEquals(1, dao.countGaps("other"))

        val repeated = library.deleteSession("first")
        assertTrue(repeated.exceptionOrNull()?.toString(), repeated.isSuccess)
        assertEquals(0L, repeated.getOrThrow())
    }

    private fun epoch(sessionId: String, index: Long) = SignalEpochEntity(
        sessionId = sessionId,
        epochIndex = index,
        startSampleIndex = index * 3120,
        startedAtMillis = 1_000 + index * 30_000,
        firstPacketSequence = index * 312,
        lastPacketSequence = index * 312 + 311,
        receivedSamples = 3120,
        quality = "INCOMPLETE",
        algorithmVersion = "spectral-export-test-v1",
        configJson = "{\"epochSamples\":3120,\"components\":3}",
    )

    private fun session(
        id: String,
        startedAtMillis: Long = 1_000,
    ) = RecordingSessionEntity(
        id = id,
        deviceAddress = "00:11:22:33:44:55",
        deviceName = "Headband",
        serviceUuid = "00000000-0000-0000-0000-000000000001",
        characteristicUuid = "00000000-0000-0000-0000-000000000002",
        startedAtMillis = startedAtMillis,
        protocolVersion = "ble-1",
        firmwareVersion = "fw-2",
        appVersion = "1.0.0",
        modelVersion = "model-3",
    )

    private fun packet(
        sessionId: String,
        sequence: Long,
        payload: ByteArray = byteArrayOf(sequence.toByte(), 1, -1),
    ) = RawPacketEntity(
        sessionId = sessionId,
        sequence = sequence,
        receivedAtMillis = 1_000 + sequence,
        payload = payload,
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
}
