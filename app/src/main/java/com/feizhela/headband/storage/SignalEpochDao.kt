package com.feizhela.headband.storage

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SignalEpochDao {
    @Upsert
    suspend fun upsertEpoch(epoch: SignalEpochEntity)

    @Query("SELECT * FROM signal_epochs WHERE sessionId = :sessionId ORDER BY epochIndex ASC")
    suspend fun readEpochs(sessionId: String): List<SignalEpochEntity>

    @Query("SELECT * FROM signal_epochs WHERE sessionId = :sessionId ORDER BY epochIndex ASC")
    fun observeEpochs(sessionId: String): Flow<List<SignalEpochEntity>>

    /** Coverage and analysis totals per session, without loading epoch rows into the list layer. */
    @Query(
        """
        SELECT sessionId,
               COUNT(*) AS epochCount,
               SUM(receivedSamples) AS receivedSamples,
               SUM(expectedSamples) AS expectedSamples,
               SUM(CASE WHEN componentsJson IS NOT NULL THEN 1 ELSE 0 END) AS analyzedEpochCount
        FROM signal_epochs
        GROUP BY sessionId
        """,
    )
    fun observeEpochTotals(): Flow<List<SessionEpochTotals>>

    @Query("DELETE FROM signal_epochs WHERE sessionId = :sessionId")
    suspend fun deleteEpochs(sessionId: String)

    /** Terminal captures with raw input and no analysis attempt for the requested algorithm. */
    @Query(
        """
        SELECT session.id FROM recording_sessions AS session
        WHERE session.status != 'ACTIVE'
          AND EXISTS (SELECT 1 FROM raw_packets AS packet WHERE packet.sessionId = session.id)
          AND NOT EXISTS (
              SELECT 1 FROM signal_epochs AS epoch
              WHERE epoch.sessionId = session.id AND epoch.algorithmVersion = :algorithmVersion
          )
        ORDER BY session.startedAtMillis ASC, session.id ASC
        """,
    )
    suspend fun pendingSessionIds(algorithmVersion: String): List<String>
}
