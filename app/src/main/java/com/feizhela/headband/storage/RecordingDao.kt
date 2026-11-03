package com.feizhela.headband.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun createSession(session: RecordingSessionEntity)

    /** Inserts the batch atomically; duplicate keys or an unknown session fail the transaction. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPackets(packets: List<RawPacketEntity>)

    @Query(
        """
        UPDATE recording_sessions
        SET status = :status, endedAtMillis = :endedAtMillis, endReason = :reason
        WHERE id = :id
        """,
    )
    suspend fun finishSession(id: String, status: String, endedAtMillis: Long, reason: String?)

    @Query(
        """
        UPDATE recording_sessions
        SET status = 'INTERRUPTED', endedAtMillis = :endedAtMillis, endReason = :reason
        WHERE status = 'ACTIVE'
        """,
    )
    suspend fun interruptActiveSessions(endedAtMillis: Long, reason: String)

    @Query("SELECT * FROM recording_sessions ORDER BY startedAtMillis DESC, id ASC")
    fun observeSessions(): Flow<List<RecordingSessionEntity>>

    /** Packet totals for every session; one aggregate row per session, never payload bytes. */
    @Query("SELECT sessionId, COUNT(*) AS packetCount FROM raw_packets GROUP BY sessionId")
    fun observePacketTotals(): Flow<List<SessionPacketTotals>>

    /**
     * Gap totals for every session. An unfinished gap contributes only its start time so the
     * mapper can measure it against the session end/current time.
     */
    @Query(
        """
        SELECT sessionId,
               COUNT(*) AS gapCount,
               SUM(CASE WHEN endedAtMillis IS NOT NULL
                        THEN endedAtMillis - startedAtMillis ELSE 0 END) AS closedGapMillis,
               MIN(CASE WHEN endedAtMillis IS NULL THEN startedAtMillis END) AS earliestOpenStartMillis
        FROM acquisition_gaps
        GROUP BY sessionId
        """,
    )
    fun observeGapTotals(): Flow<List<SessionGapTotals>>

    @Query("DELETE FROM recording_sessions")
    suspend fun deleteAllSessions()

    @Query("SELECT * FROM recording_sessions WHERE id = :id LIMIT 1")
    fun observeSession(id: String): Flow<RecordingSessionEntity?>

    /** Returns packets in ascending sequence order; afterSequence is an exclusive cursor. */
    @Query(
        """
        SELECT * FROM raw_packets
        WHERE sessionId = :sessionId AND sequence > :afterSequence
        ORDER BY sequence ASC
        LIMIT :limit
        """,
    )
    suspend fun readPackets(
        sessionId: String,
        afterSequence: Long,
        limit: Int,
    ): List<RawPacketEntity>

    @Query("DELETE FROM recording_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    /** Records one interruption of the packet stream for the session. */
    @Insert
    suspend fun insertGap(gap: AcquisitionGapEntity): Long

    /** Gaps in observation order; unclosed gaps (`endedAtMillis == null`) belong to the tail. */
    @Query("SELECT * FROM acquisition_gaps WHERE sessionId = :sessionId ORDER BY startedAtMillis ASC, id ASC")
    fun observeGaps(sessionId: String): Flow<List<AcquisitionGapEntity>>

    @Query("SELECT * FROM acquisition_gaps WHERE sessionId = :sessionId ORDER BY startedAtMillis ASC, id ASC")
    suspend fun readGaps(sessionId: String): List<AcquisitionGapEntity>

    @Query("DELETE FROM acquisition_gaps WHERE sessionId = :sessionId")
    suspend fun deleteGaps(sessionId: String)

    @Query("SELECT COUNT(*) FROM acquisition_gaps WHERE sessionId = :sessionId")
    suspend fun countGaps(sessionId: String): Int
}
