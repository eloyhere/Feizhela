package com.feizhela.headband.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable acquisition metadata; packet payloads remain opaque to the storage layer. */
@Entity(
    tableName = "recording_sessions",
    indices = [Index(value = ["startedAtMillis"])],
)
data class RecordingSessionEntity(
    @PrimaryKey val id: String,
    val deviceAddress: String,
    val deviceName: String?,
    val serviceUuid: String,
    val characteristicUuid: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val status: String = "ACTIVE",
    val endReason: String? = null,
    // Provenance captured at session start; null when the component could not report a version.
    val protocolVersion: String? = null,
    val firmwareVersion: String? = null,
    val appVersion: String? = null,
    val modelVersion: String? = null,
)
