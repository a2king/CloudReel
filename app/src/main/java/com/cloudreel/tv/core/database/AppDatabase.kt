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
        SourceEntity::class,
        ShowEntity::class,
        PlayLinkEntity::class,
        ProgressEntity::class,
        LiveCategoryEntity::class,
        LiveChannelEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
}
