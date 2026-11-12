package com.anistream.core.database.entity

import androidx.room.Entity

/**
 * A cached "For you" pick of one profile ([profileId], "" = signed out), in ranked [position] order per [kind] ("anime" | "media").
 * [seed] = rank (by weight, 0 = strongest) of the watched title it is shown [because] of.
 *
 * Migration SQL (must match Room's generated schema exactly):
 * ```
 * CREATE TABLE IF NOT EXISTS `recommendations` (`profileId` TEXT NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, `title` TEXT NOT NULL, `posterUrl` TEXT, `year` INTEGER, `rating` REAL, `mediaType` TEXT, `because` TEXT, `seed` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `kind`, `id`))
 * ```
 */
@Entity(tableName = "recommendations", primaryKeys = ["profileId", "kind", "id"])
data class RecommendationEntity(
    val profileId: String,
    val kind: String,
    val id: String,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val rating: Double?,
    /** "MOVIE" | "SERIES" for media, null for anime */
    val mediaType: String?,
    val because: String?,
    val seed: Int,
    val position: Int
)
