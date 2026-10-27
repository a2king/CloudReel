package com.yingshicang.tv.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM source_config ORDER BY priority ASC")
    fun observeSources(): Flow<List<SourceConfigEntity>>

    @Query("SELECT * FROM source_config WHERE enabled = 1 ORDER BY priority ASC")
    suspend fun enabledSources(): List<SourceConfigEntity>

    @Query("SELECT * FROM source_config ORDER BY priority ASC")
    suspend fun allSources(): List<SourceConfigEntity>

    @Query("SELECT COUNT(*) FROM source_config")
    suspend fun sourceCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(entity: SourceConfigEntity)

    @Query("UPDATE source_config SET enabled = :enabled WHERE id = :id")
    suspend fun setSourceEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM source_config WHERE id = :id AND builtin = 0")
    suspend fun deleteCustomSource(id: String)

    @Query("SELECT * FROM play_history ORDER BY lastPlayedAt DESC LIMIT 30")
    fun observeHistory(): Flow<List<PlayHistoryEntity>>

    @Query("SELECT * FROM play_history WHERE mediaKey = :key LIMIT 1")
    suspend fun history(key: String): PlayHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(entity: PlayHistoryEntity)

    @Query("DELETE FROM play_history")
    suspend fun clearHistory()

    @Query("SELECT * FROM favorite ORDER BY addedAt DESC")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorite WHERE mediaKey = :key LIMIT 1")
    suspend fun favorite(key: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(entity: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE mediaKey = :key")
    suspend fun deleteFavorite(key: String)

    @Query("DELETE FROM favorite")
    suspend fun clearFavorites()

    @Query("SELECT * FROM search_history ORDER BY updatedAt DESC LIMIT 12")
    fun observeSearches(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearch(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history")
    suspend fun clearSearches()

    @Query("SELECT id, name, enabled, updatedAt FROM live_playlist ORDER BY updatedAt DESC")
    fun observePlaylistSummaries(): Flow<List<LivePlaylistSummary>>

    @Query("SELECT id, name, enabled, updatedAt FROM live_playlist WHERE enabled = 1 ORDER BY updatedAt DESC")
    suspend fun enabledPlaylistSummaries(): List<LivePlaylistSummary>

    @Query("SELECT * FROM live_playlist WHERE id = :id LIMIT 1")
    suspend fun playlist(id: String): LivePlaylistEntity?

    @Query("SELECT * FROM live_playlist ORDER BY updatedAt DESC")
    fun observePlaylists(): Flow<List<LivePlaylistEntity>>

    @Query("SELECT COUNT(*) FROM live_playlist")
    suspend fun playlistCount(): Int

    @Query("SELECT * FROM live_playlist WHERE enabled = 1 ORDER BY updatedAt DESC")
    suspend fun enabledPlaylists(): List<LivePlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(entity: LivePlaylistEntity)

    @Query("DELETE FROM live_playlist WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("SELECT * FROM drive_account ORDER BY name ASC")
    fun observeDrives(): Flow<List<DriveAccountEntity>>

    @Query("SELECT * FROM drive_account WHERE id = :id LIMIT 1")
    suspend fun drive(id: String): DriveAccountEntity?

    @Query("SELECT * FROM drive_account")
    suspend fun allDrives(): List<DriveAccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDrive(entity: DriveAccountEntity)

    @Query("DELETE FROM drive_account WHERE id = :id")
    suspend fun deleteDrive(id: String)
}
