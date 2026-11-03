package com.feizhela.headband.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One acquisition epoch and its optional spectral analysis, retained with its recording session.
 *
 * [startedAtMillis] estimates the epoch start from acquisition timing. [startSampleIndex] is the
 * session-relative raw sample position. Analysis outputs remain null for invalid, partial or failed
 * epochs, while [quality], [reason] and the input provenance still describe what was received.
 * JSON is stored verbatim; [componentsJson], when present, is an array of objects containing
 * `amplitude`, `frequencyHz` and `phaseRadians`.
 */
@Entity(
    tableName = "signal_epochs",
    primaryKeys = ["sessionId", "epochIndex"],
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "startSampleIndex"])],
)
data class SignalEpochEntity(
    val sessionId: String,
    val epochIndex: Long,
    val startSampleIndex: Long,
    val startedAtMillis: Long,
    val firstPacketSequence: Long,
    val lastPacketSequence: Long,
    val receivedSamples: Int,
    val expectedSamples: Int = 3120,
    val quality: String,
    val reason: String? = null,
    val rawSamplingRate: Double = 104.0,
    val analysisSamplingRate: Double = 20.0,
    val algorithmVersion: String,
    val configJson: String,
    val componentsJson: String? = null,
    val analysisWindow: Int? = null,
    val residualRatio: Double? = null,
    val verified: Boolean? = null,
)
