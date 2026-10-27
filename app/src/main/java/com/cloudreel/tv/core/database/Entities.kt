package com.cloudreel.tv.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
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

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "api_url") val apiUrl: String,
    val type: String,
    val enabled: Boolean,
    val priority: Int,
    @ColumnInfo(name = "last_ok_at") val lastOkAt: Long,
    @ColumnInfo(name = "fail_count") val failCount: Int,
)

@Entity(
    tableName = "shows",
    indices = [
        Index(value = ["tmdb_id"], unique = true),
        Index(value = ["normalized_title", "year", "media_type"], unique = true),
    ],
)
data class ShowEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "tmdb_id") val tmdbId: String?,
    val title: String,
    @ColumnInfo(name = "normalized_title") val normalizedTitle: String,
    val year: Int?,
    @ColumnInfo(name = "media_type") val mediaType: String,
    val genres: String,
    val rating: Double?,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String?,
    val overview: String,
    @ColumnInfo(name = "release_date") val releaseDate: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "play_links",
    indices = [Index(value = ["show_id"])],
)
data class PlayLinkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "show_id") val showId: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val label: String,
    @ColumnInfo(name = "drive_type") val driveType: String?,
    val episodes: String,
    @ColumnInfo(name = "raw_vod_id") val rawVodId: String,
    @ColumnInfo(name = "quality_hint") val qualityHint: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "progress",
    indices = [Index(value = ["show_id", "episode_key"], unique = true)],
)
data class ProgressEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "show_id") val showId: String,
    @ColumnInfo(name = "episode_key") val episodeKey: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "play_link_id") val playLinkId: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "live_categories")
data class LiveCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(
    tableName = "live_channels",
    indices = [Index(value = ["category_id"])],
)
data class LiveChannelEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val name: String,
    val urls: String,
    @ColumnInfo(name = "epg_hint") val epgHint: String?,
)
