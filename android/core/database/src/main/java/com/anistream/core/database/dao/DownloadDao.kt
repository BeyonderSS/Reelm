package com.anistream.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.anistream.core.database.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE episodeId = :episodeId")
    suspend fun getById(episodeId: Long): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("UPDATE downloads SET progress = :progress, state = :state WHERE episodeId = :episodeId")
    suspend fun updateProgress(episodeId: Long, progress: Float, state: String)

    @Query("UPDATE downloads SET posterUrl = :url WHERE episodeId = :episodeId")
    suspend fun setPoster(episodeId: Long, url: String)

    @Query("DELETE FROM downloads WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: Long)
}
