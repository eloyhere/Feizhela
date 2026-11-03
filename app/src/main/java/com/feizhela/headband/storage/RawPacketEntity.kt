package com.feizhela.headband.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "raw_packets",
    primaryKeys = ["sessionId", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "receivedAtMillis"])],
)
data class RawPacketEntity(
    val sessionId: String,
    val sequence: Long,
    val receivedAtMillis: Long,
    val payload: ByteArray,
)
