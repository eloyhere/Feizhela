package com.feizhela.headband.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: persist acquisition gaps and the traceability metadata of a session.
 *
 * Every statement below is written in the exact form Room 2.7 emits for the v2 entities, so the
 * migrated file ends up with the same columns, primary key, foreign key and index that Room exports
 * to `app/schemas/com.feizhela.headband.storage.RecordingDatabase/2.json` - and therefore passes the
 * `onValidateSchema` check Room runs right after the migration:
 *
 * - `acquisition_gaps` is a new table with an autoincrementing surrogate `id`, the
 *   `sessionId -> recording_sessions(id)` foreign key deleted with `CASCADE`, and the
 *   `index_acquisition_gaps_sessionId_startedAtMillis` index.
 * - `recording_sessions` only gains nullable `TEXT` columns, so no existing row has to move and
 *   the v1 `index_recording_sessions_startedAtMillis` index keeps covering the table.
 * - `raw_packets` is untouched; archived payloads stay addressable by `(sessionId, sequence)`.
 *
 * SQLite cannot add a foreign key or an index through `ALTER TABLE`, hence the gaps table is
 * created from scratch while the session table is extended in place.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `acquisition_gaps` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`startedAtMillis` INTEGER NOT NULL, " +
                "`endedAtMillis` INTEGER, " +
                "`droppedEstimate` INTEGER, " +
                "`detail` TEXT, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `recording_sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_acquisition_gaps_sessionId_startedAtMillis` " +
                "ON `acquisition_gaps` (`sessionId`, `startedAtMillis`)",
        )
        db.execSQL("ALTER TABLE `recording_sessions` ADD COLUMN `protocolVersion` TEXT")
        db.execSQL("ALTER TABLE `recording_sessions` ADD COLUMN `firmwareVersion` TEXT")
        db.execSQL("ALTER TABLE `recording_sessions` ADD COLUMN `appVersion` TEXT")
        db.execSQL("ALTER TABLE `recording_sessions` ADD COLUMN `modelVersion` TEXT")
    }
}

/** v2 -> v3: add spectral epochs without rewriting existing sessions, gaps or packet payloads. */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `signal_epochs` (" +
                "`sessionId` TEXT NOT NULL, " +
                "`epochIndex` INTEGER NOT NULL, " +
                "`startSampleIndex` INTEGER NOT NULL, " +
                "`startedAtMillis` INTEGER NOT NULL, " +
                "`firstPacketSequence` INTEGER NOT NULL, " +
                "`lastPacketSequence` INTEGER NOT NULL, " +
                "`receivedSamples` INTEGER NOT NULL, " +
                "`expectedSamples` INTEGER NOT NULL, " +
                "`quality` TEXT NOT NULL, " +
                "`reason` TEXT, " +
                "`rawSamplingRate` REAL NOT NULL, " +
                "`analysisSamplingRate` REAL NOT NULL, " +
                "`algorithmVersion` TEXT NOT NULL, " +
                "`configJson` TEXT NOT NULL, " +
                "`componentsJson` TEXT, " +
                "`analysisWindow` INTEGER, " +
                "`residualRatio` REAL, " +
                "`verified` INTEGER, " +
                "PRIMARY KEY(`sessionId`, `epochIndex`), " +
                "FOREIGN KEY(`sessionId`) REFERENCES `recording_sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_signal_epochs_sessionId_startSampleIndex` " +
                "ON `signal_epochs` (`sessionId`, `startSampleIndex`)",
        )
    }
}
