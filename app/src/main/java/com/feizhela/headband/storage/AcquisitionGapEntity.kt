package com.feizhela.headband.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One durable hole in the raw packet stream of a [RecordingSessionEntity].
 *
 * The row records what the phone knows about the missing span rather than pretending the capture
 * was continuous: [startedAtMillis] is the first moment the stream was known to be incomplete and
 * [endedAtMillis] is when it was observed flowing again. A gap stays open (`endedAtMillis == null`)
 * when the session ended before recovery, so an interrupted night remains reconstructible after a
 * process restart. Rows are deleted with their session through [ForeignKey.CASCADE].
 */
@Entity(
    tableName = "acquisition_gaps",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "startedAtMillis"])],
)
data class AcquisitionGapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    /** BUFFER_OVERFLOW / DISCONNECT / SUBSCRIPTION_LOST / SERVICE_RESTART / DEVICE_GAP */
    val kind: String,
    val startedAtMillis: Long,
    /** null 表示截至会话结束仍未恢复。 */
    val endedAtMillis: Long? = null,
    /** 估算丢失包数；null 表示无法估算。 */
    val droppedEstimate: Long? = null,
    val detail: String? = null,
)
