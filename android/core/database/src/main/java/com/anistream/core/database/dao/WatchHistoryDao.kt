package com.anistream.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.anistream.core.database.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

/** Every query is scoped to a profile ("" = local-only). Deletes are tombstones so they can sync. */
@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND deleted = 0 ORDER BY lastWatchedTimestamp DESC")
    fun getAll(profileId: String): Flow<List<WatchHistoryEntity>>

    /**
     * Movies (media rows with lastEpisodeWatched = 0) qualify until finished: 95%, or 90% with ≤ 10 min left, since long
     * end credits left The Dark Knight at 94.96%. Same rule as the web's `finished` (web/src/lib/eps.ts).
     */
    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND deleted = 0 AND (lastEpisodeWatched > 0 OR (kind = 'media' AND watchProgress < 0.95 AND NOT (watchProgress >= 0.9 AND durationMs > 1200000 AND (1 - watchProgress) * durationMs <= 600000))) AND (:kind IS NULL OR kind = :kind) ORDER BY lastWatchedTimestamp DESC")
    fun getContinueWatching(profileId: String, kind: String? = null): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE animeSlug = :slug AND profileId = :profileId AND deleted = 0")
    suspend fun getBySlug(slug: String, profileId: String): WatchHistoryEntity?

    /** tombstones included: what the sync merge compares against */
    @Query("SELECT * FROM watch_history WHERE animeSlug = :slug AND profileId = :profileId")
    suspend fun getRaw(slug: String, profileId: String): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND kind = 'media' AND deleted = 0")
    suspend fun liveMedia(profileId: String): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND lastWatchedTimestamp > :since")
    suspend fun changedSince(profileId: String, since: Long): List<WatchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchHistoryEntity)

    @Query("UPDATE watch_history SET lastEpisodeWatched = :episode, watchProgress = :progress, lastWatchedTimestamp = :timestamp WHERE animeSlug = :slug AND profileId = :profileId AND deleted = 0")
    suspend fun updateProgress(slug: String, episode: Int, progress: Float, profileId: String, timestamp: Long = System.currentTimeMillis()): Int

    @Query("UPDATE watch_history SET deleted = 1, lastWatchedTimestamp = :timestamp WHERE animeSlug = :slug AND profileId = :profileId")
    suspend fun delete(slug: String, profileId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE watch_history SET deleted = 1, lastWatchedTimestamp = :timestamp WHERE profileId = :profileId")
    suspend fun clear(profileId: String, timestamp: Long = System.currentTimeMillis())

    /** first sign-in: the offline history becomes the profile's (rows it already has win) */
    @Query("UPDATE OR IGNORE watch_history SET profileId = :profileId WHERE profileId = ''")
    suspend fun adoptLocal(profileId: String)
}
