package com.yingshicang.tv.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "source_config")
data class SourceConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val location: String,
    val enabled: Boolean,
    val priority: Int,
    val builtin: Boolean,
)

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey val mediaKey: String,
    val title: String,
    val poster: String?,
    val year: String?,
    val providerId: String,
    val sourceId: String,
    val refsJson: String,
    val lineIndex: Int,
    val episodeIndex: Int,
    val lineName: String,
    val episodeName: String,
    val playUrl: String,
    val headersJson: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
    val completed: Boolean,
)

@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey val mediaKey: String,
    val title: String,
    val poster: String?,
    val year: String?,
    val providerId: String,
    val sourceId: String,
    val refsJson: String,
    val addedAt: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val keyword: String,
    val updatedAt: Long,
)

data class LivePlaylistSummary(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "live_playlist")
data class LivePlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val content: String,
    val epg: String,
    val enabled: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "drive_account")
data class DriveAccountEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val name: String,
    val endpoint: String,
    val username: String,
    val secretCipher: String,
    val enabled: Boolean,
)
