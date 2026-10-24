package com.anistream.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.anistream.core.database.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE profileId = :profile AND deleted = 0 ORDER BY addedTimestamp DESC")
    fun getAll(profile: String): Flow<List<FavoriteEntity>>

    /** every row incl. tombstones, for key matching and sync */
    @Query("SELECT * FROM favorites WHERE profileId = :profile")
    suspend fun rows(profile: String): List<FavoriteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE animeSlug = :slug AND profileId = :profile AND deleted = 0)")
    suspend fun isFavorite(slug: String, profile: String): Boolean

    @Query("SELECT * FROM favorites WHERE profileId = :profile AND addedTimestamp > :since")
    suspend fun changedSince(profile: String, since: Long): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    /** removal keeps a tombstone so sync can tell the other devices */
    @Query("UPDATE favorites SET deleted = 1, addedTimestamp = :now WHERE animeSlug = :slug AND profileId = :profile")
    suspend fun tombstone(slug: String, profile: String, now: Long)

    /** first sign-in: offline favourites become the profile's (rows it already has win) */
    @Query("UPDATE OR IGNORE favorites SET profileId = :profile WHERE profileId = ''")
    suspend fun adoptLocal(profile: String)
}
