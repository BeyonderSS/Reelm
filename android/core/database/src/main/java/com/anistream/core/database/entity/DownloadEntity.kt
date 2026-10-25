package com.anistream.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val episodeId: Long,
    val animeTitle: String,
    val episodeNumber: Int,
    val quality: String,
    val filePath: String? = null,
    val fileSizeBytes: Long = 0,
    val progress: Float = 0f,
    val state: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis(),
    /** movies & TV: "S1 · E2" (null for anime and movies) */
    val subtitle: String? = null,
    val posterUrl: String? = null,
    /** episode still and name (TMDB / Kitsu), for the Downloads row */
    val stillUrl: String? = null,
    val episodeTitle: String? = null
)
