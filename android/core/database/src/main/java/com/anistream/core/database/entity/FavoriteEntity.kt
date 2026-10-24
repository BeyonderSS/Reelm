package com.anistream.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/** One row per (title, profile); profileId "" = local-only (signed out). [addedTimestamp] is the row's updatedAt for sync; [deleted] = tombstone. */
@Entity(tableName = "favorites", primaryKeys = ["animeSlug", "profileId"])
data class FavoriteEntity(
    val animeSlug: String,
    val malId: Long? = null,
    val animeTitle: String,
    val posterUrl: String? = null,
    val score: Double? = null,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val totalEpisodes: Int = 0,
    val lastEpisodeWatched: Int = 0,
    @ColumnInfo(defaultValue = "''") val profileId: String = "",
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    /** "anime" | "media" */
    @ColumnInfo(defaultValue = "'anime'") val kind: String = "anime",
    /** "movie" | "series" for media */
    val mediaType: String? = null,
    val year: Int? = null
)
