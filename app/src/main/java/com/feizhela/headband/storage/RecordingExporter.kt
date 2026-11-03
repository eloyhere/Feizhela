package com.feizhela.headband.storage

import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Streaming export of one archived recording session.
 *
 * The caller owns the [OutputStream] (a SAF `ContentResolver.openOutputStream` result in
 * production and a [java.io.ByteArrayOutputStream] in tests): this class never closes the
 * sink, it only writes and flushes. Payload bytes are exported verbatim as hexadecimal;
 * nothing in this layer decodes, validates or interprets the device protocol.
 */
class RecordingExporter(
    private val dao: RecordingDao,
    private val epochDao: SignalEpochDao? = null,
) {
    /**
     * Exports every packet of [sessionId] as CSV, in ascending `sequence` order and with
     * `afterSequence` cursor pagination so the whole archive is never loaded at once.
     *
     * On success returns the number of data rows written (the header and the column line
     * are not counted). A missing session fails before a single byte is written. IO and
     * database errors are returned as [Result.failure]; because the sink is append-only,
     * bytes already delivered to it are not rolled back, which the failure message states.
     */
    suspend fun exportSessionCsv(
        sessionId: String,
        sink: OutputStream,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): Result<Long> {
        if (pageSize <= 0) {
            return Result.failure(
                IllegalArgumentException("pageSize must be positive, was $pageSize; no bytes were written"),
            )
        }
        val session = try {
            dao.observeSession(sessionId).first()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return Result.failure(
                IOException("Unable to read session '$sessionId' before export; no bytes were written", error),
            )
        }
        if (session == null) {
            return Result.failure(
                IllegalStateException(
                    "Recording session '$sessionId' does not exist; no bytes were written",
                ),
            )
        }

        val writer = BufferedWriter(OutputStreamWriter(sink, Charsets.UTF_8))
        var rowCount = 0L
        return try {
            writer.write(commentHeader(session))
            writer.write("\n")
            writer.write(COLUMN_HEADER)
            writer.write("\n")

            var afterSequence = SEQUENCE_CURSOR_START
            while (true) {
                val page = dao.readPackets(sessionId, afterSequence, pageSize)
                if (page.isEmpty()) break
                for (packet in page) {
                    writer.write(packet.sequence.toString())
                    writer.write(",")
                    writer.write(packet.receivedAtMillis.toString())
                    writer.write(",")
                    writer.write(packet.payload.toLowercaseHex())
                    writer.write("\n")
                    rowCount++
                }
                afterSequence = page.last().sequence
            }
            writer.flush()
            Result.success(rowCount)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(
                IOException(
                    "CSV export of session '$sessionId' failed after $rowCount data row(s) were generated; " +
                        "bytes already written to the sink are not rolled back",
                    error,
                ),
            )
        }
    }

    /**
     * Exports metadata, acquisition gaps and optional versioned spectral epoch evidence as JSON.
     * Per-packet rows and raw bytes are absent. The summary grows with the number of epochs.
     * Stored config/components JSON is retained verbatim to preserve analysis provenance.
     *
     * All reads and JSON construction finish before the sink is touched. A missing session,
     * failed read or serialization failure writes nothing. These reads are not an atomic snapshot
     * of an actively recording session. On success returns [Result.success].
     */
    suspend fun exportSessionSummaryJson(
        sessionId: String,
        sink: OutputStream,
    ): Result<Unit> {
        val session = try {
            dao.observeSession(sessionId).first()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return Result.failure(
                IOException("Unable to read session '$sessionId' before summary export; no bytes were written", error),
            )
        }
        if (session == null) {
            return Result.failure(
                IllegalStateException(
                    "Recording session '$sessionId' does not exist; no bytes were written",
                ),
            )
        }
        val gaps = try {
            dao.readGaps(sessionId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return Result.failure(
                IOException("Unable to read acquisition gaps for '$sessionId'; no bytes were written", error),
            )
        }

        val json = try {
            val epochs = epochDao?.readEpochs(sessionId).orEmpty()
            summaryJson(session, gaps, epochs).toString()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return Result.failure(
                IOException("Unable to prepare analysis summary for '$sessionId'; no bytes were written", error),
            )
        }
        return try {
            sink.write(json.toByteArray(Charsets.UTF_8))
            sink.flush()
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(
                IOException(
                    "Summary JSON export of session '$sessionId' failed; " +
                        "bytes already written to the sink are not rolled back",
                    error,
                ),
            )
        }
    }

    private fun commentHeader(session: RecordingSessionEntity): String = buildString {
        append("# exportSchema=")
        append(EXPORT_SCHEMA_VERSION)
        append(",sessionId=")
        append(csvField(session.id))
        append(",deviceAddress=")
        append(csvField(session.deviceAddress))
        append(",startedAtMillis=")
        append(session.startedAtMillis)
        append(",status=")
        append(csvField(session.status))
        append(",unit=millis")
        append(",payload=hex")
        append(",source=raw_packets")
        append(",protocolVersion=")
        append(csvField(session.protocolVersion.orEmpty()))
        append(",firmwareVersion=")
        append(csvField(session.firmwareVersion.orEmpty()))
        append(",appVersion=")
        append(csvField(session.appVersion.orEmpty()))
        append(",modelVersion=")
        append(csvField(session.modelVersion.orEmpty()))
    }

    private fun summaryJson(
        session: RecordingSessionEntity,
        gaps: List<AcquisitionGapEntity>,
        epochs: List<SignalEpochEntity>,
    ): JSONObject = JSONObject().apply {
        put("exportSchema", SUMMARY_EXPORT_SCHEMA_VERSION)
        put("kind", "recording-session-summary")
        put("unit", "millis")
        put(
            "session",
            JSONObject().apply {
                put("id", session.id)
                put("deviceAddress", session.deviceAddress)
                putNullable("deviceName", session.deviceName)
                put("serviceUuid", session.serviceUuid)
                put("characteristicUuid", session.characteristicUuid)
                put("startedAtMillis", session.startedAtMillis)
                putNullable("endedAtMillis", session.endedAtMillis)
                put("status", session.status)
                putNullable("endReason", session.endReason)
                putNullable("protocolVersion", session.protocolVersion)
                putNullable("firmwareVersion", session.firmwareVersion)
                putNullable("appVersion", session.appVersion)
                putNullable("modelVersion", session.modelVersion)
            },
        )
        put(
            "gaps",
            JSONArray().apply {
                gaps.forEach { gap ->
                    put(
                        JSONObject().apply {
                            put("id", gap.id)
                            put("sessionId", gap.sessionId)
                            put("kind", gap.kind)
                            put("startedAtMillis", gap.startedAtMillis)
                            putNullable("endedAtMillis", gap.endedAtMillis)
                            putNullable("droppedEstimate", gap.droppedEstimate)
                            putNullable("detail", gap.detail)
                        },
                    )
                }
            },
        )
        put("gapCount", gaps.size)
        // Availability means stored spectral outputs exist, never a clinical quality verdict.
        put("analysisStoreAvailable", epochDao != null)
        put("analysisAvailable", epochs.any { it.componentsJson != null })
        put("analysisKind", "spectral-only")
        put("epochCount", epochs.size)
        put("analyzedEpochCount", epochs.count { it.componentsJson != null })
        put("qualityCounts", JSONObject().apply {
            epochs.groupingBy { it.quality }.eachCount().toSortedMap().forEach { (quality, count) ->
                put(quality, count)
            }
        })
        put("epochs", JSONArray().apply {
            epochs.forEach { epoch ->
                put(JSONObject().apply {
                    put("sessionId", epoch.sessionId)
                    put("epochIndex", epoch.epochIndex)
                    put("startSampleIndex", epoch.startSampleIndex)
                    put("startedAtMillis", epoch.startedAtMillis)
                    put("firstPacketSequence", epoch.firstPacketSequence)
                    put("lastPacketSequence", epoch.lastPacketSequence)
                    put("receivedSamples", epoch.receivedSamples)
                    put("expectedSamples", epoch.expectedSamples)
                    put("quality", epoch.quality)
                    putNullable("reason", epoch.reason)
                    put("rawSamplingRate", epoch.rawSamplingRate)
                    put("analysisSamplingRate", epoch.analysisSamplingRate)
                    put("algorithmVersion", epoch.algorithmVersion)
                    put("configJson", epoch.configJson)
                    putNullable("componentsJson", epoch.componentsJson)
                    putNullable("analysisWindow", epoch.analysisWindow)
                    putNullable("residualRatio", epoch.residualRatio)
                    putNullable("verified", epoch.verified)
                    put("analysisAvailable", epoch.componentsJson != null)
                })
            }
        })
    }

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }

    /** Quotes a header field only when it would otherwise break the comma-separated comment. */
    private fun csvField(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}

/** Lowercase hexadecimal of the raw archived bytes; empty payload stays empty. */
internal fun ByteArray.toLowercaseHex(): String {
    val digits = "0123456789abcdef"
    val characters = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xFF
        characters[index * 2] = digits[value ushr 4]
        characters[index * 2 + 1] = digits[value and 0x0F]
    }
    return String(characters)
}

/** Fixed CSV columns; the leading `#` line carries export provenance instead. */
private const val COLUMN_HEADER = "sequence,receivedAtMillis,payloadHex"

/** Bumped only when the column meaning or header fields change. */
private const val EXPORT_SCHEMA_VERSION = 1

/** v2 adds optional spectral evidence and explicit analysis availability to the JSON summary. */
private const val SUMMARY_EXPORT_SCHEMA_VERSION = 2

private const val DEFAULT_PAGE_SIZE = 512

/** Packet sequences are per-session and start at zero, so the exclusive cursor starts below it. */
private const val SEQUENCE_CURSOR_START = -1L
