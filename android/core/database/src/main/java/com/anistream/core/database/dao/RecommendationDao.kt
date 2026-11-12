package com.anistream.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.anistream.core.database.entity.RecommendationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecommendationDao {
    @Query("SELECT * FROM recommendations WHERE profileId = :profileId AND kind = :kind ORDER BY position")
    fun observe(profileId: String, kind: String): Flow<List<RecommendationEntity>>

    @Query("DELETE FROM recommendations WHERE profileId = :profileId AND kind = :kind")
    suspend fun deleteKind(profileId: String, kind: String)

    @Insert
    suspend fun insertAll(items: List<RecommendationEntity>)

    @Transaction
    suspend fun replace(profileId: String, kind: String, items: List<RecommendationEntity>) {
        deleteKind(profileId, kind)
        insertAll(items)
    }
}
