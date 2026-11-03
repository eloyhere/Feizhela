package com.feizhela.headband.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RecordingSessionEntity::class,
        RawPacketEntity::class,
        AcquisitionGapEntity::class,
        SignalEpochEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class RecordingDatabase : RoomDatabase() {
    abstract val recordingDao: RecordingDao
    abstract val signalEpochDao: SignalEpochDao

    companion object {
        @Volatile
        private var instance: RecordingDatabase? = null

        /**
         * Process-wide database; recovery marking is performed by the acquisition owner.
         *
         * Upgrades go through [MIGRATION_1_2] and [MIGRATION_2_3], preserving archived acquisition data.
         */
        fun open(context: Context): RecordingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RecordingDatabase::class.java,
                "recordings.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
