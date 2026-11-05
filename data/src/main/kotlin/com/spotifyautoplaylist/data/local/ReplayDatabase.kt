package com.spotifyautoplaylist.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 1. Every schema change needs a new version, an explicit `Migration`, a migration test and the
 * new exported schema in `data/schemas/`. No destructive fallback is configured.
 */
@Database(
    entities = [
        AccountEntity::class, TrackEntity::class, PlayEventEntity::class, QualificationEntity::class,
        ManagedPlaylistEntity::class, PlaylistOutboxEntity::class, SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ReplayDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun history(): HistoryDao
    abstract fun qualifications(): QualificationDao
    abstract fun playlists(): PlaylistDao
    abstract fun syncState(): SyncStateDao

    companion object {
        const val FILE_NAME = "replay.db"

        /** Create once per process. */
        fun create(context: Context): ReplayDatabase =
            Room.databaseBuilder(context.applicationContext, ReplayDatabase::class.java, FILE_NAME).build()
    }
}
