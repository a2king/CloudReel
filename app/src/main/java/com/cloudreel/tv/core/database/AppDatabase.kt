package com.cloudreel.tv.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceConfigEntity::class,
        PlayHistoryEntity::class,
        FavoriteEntity::class,
        SearchHistoryEntity::class,
        LivePlaylistEntity::class,
        DriveAccountEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
}
