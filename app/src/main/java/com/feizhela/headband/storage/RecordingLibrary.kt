package com.feizhela.headband.storage

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Read/delete facade over the archived recordings.
 *
 * This layer adds no protocol meaning: it exposes Room flows, counts rows for audit, and
 * delegates deletion to the DAO so the foreign-key cascade owns packet removal.
 */
class RecordingLibrary(
    private val dao: RecordingDao,
    private val epochDao: SignalEpochDao? = null,
) {
    fun observeSessions(): Flow<List<RecordingSessionEntity>> = dao.observeSessions()

    fun observePacketTotals(): Flow<List<SessionPacketTotals>> = dao.observePacketTotals()

    fun observeGapTotals(): Flow<List<SessionGapTotals>> = dao.observeGapTotals()

    /** Live per-session coverage totals, or an empty flow when the analysis store is absent. */
    fun observeEpochTotals(): Flow<List<SessionEpochTotals>> =
        epochDao?.observeEpochTotals() ?: flowOf(emptyList())

    fun observeSession(sessionId: String): Flow<RecordingSessionEntity?> =
        dao.observeSession(sessionId)

    /** Live acquisition gaps of one session; updates arrive through Room invalidation. */
    fun observeGaps(sessionId: String): Flow<List<AcquisitionGapEntity>> =
        dao.observeGaps(sessionId)

    /** Live spectral evidence, or an empty flow when the optional analysis store is not wired. */
    fun observeEpochs(sessionId: String): Flow<List<SignalEpochEntity>> =
        epochDao?.observeEpochs(sessionId) ?: flowOf(emptyList())

    /**
     * Deletes the session and, through the entity's `ON DELETE CASCADE` foreign key, its
     * raw packets, acquisition gaps and signal epochs. Returns the packet count read immediately before
     * deletion for audit purposes.
     *
     * The count is collected with paged [RecordingDao.readPackets] calls because the DAO
     * has no `countPackets` query; this reads payloads page by page and is therefore O(n)
     * for the session. Deleting an already-deleted (or never existing) session is a no-op
     * and succeeds with `0`. Cancellation propagates; other failures are returned as
     * [Result.failure] and the message warns that the delete may or may not have applied.
     */
    suspend fun deleteSession(sessionId: String): Result<Long> = try {
        val packetCount = countPacketsPaged(sessionId)
        dao.deleteSession(sessionId)
        Result.success(packetCount)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(
            IOException(
                "Deleting session '$sessionId' failed; the session may or may not still exist",
                error,
            ),
        )
    }

    /**
     * Counts the archived packets of one session. The count is paged through
     * [RecordingDao.readPackets] because no cheaper `countPackets` query exists yet; a DAO
     * addition can replace the body without changing this signature. Database errors
     * propagate as exceptions, matching the non-[Result] contract.
     */
    suspend fun packetCount(sessionId: String): Long = countPacketsPaged(sessionId)

    /**
     * Deletes every archived session. Foreign keys cascade to packets, gaps and epochs, so one
     * statement is enough; failures are returned instead of thrown, like [deleteSession].
     */
    suspend fun deleteAllSessions(): Result<Unit> = try {
        dao.deleteAllSessions()
        Result.success(Unit)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(IOException("Deleting all sessions failed", error))
    }

    private suspend fun countPacketsPaged(sessionId: String): Long {
        var afterSequence = SEQUENCE_CURSOR_START
        var total = 0L
        while (true) {
            val page = dao.readPackets(sessionId, afterSequence, COUNT_PAGE_SIZE)
            if (page.isEmpty()) return total
            total += page.size
            afterSequence = page.last().sequence
        }
    }

    private companion object {
        /** Matches the exporter's page size; small enough to keep one page of payloads in memory. */
        const val COUNT_PAGE_SIZE = 512

        /** Per-session sequences start at zero, so the exclusive cursor starts below it. */
        const val SEQUENCE_CURSOR_START = -1L
    }
}
