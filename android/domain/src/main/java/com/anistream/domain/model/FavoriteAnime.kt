package com.anistream.domain.model

/** Canonical sync key, the same scheme as history: `anime:<slug>` / `media:<movie|series>:<normtitle>`. */
fun favKey(kind: String, slug: String, mediaType: String?, title: String) =
    if (kind == "media") "media:" + mediaSlug(mediaType == "series", title) else "anime:$slug"

data class FavoriteAnime(
    val animeSlug: String,
    val malId: Long? = null,
    val animeTitle: String,
    val posterUrl: String? = null,
    val score: Double? = null,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val totalEpisodes: Int = 0,
    val lastEpisodeWatched: Int = 0,
    /** "anime" | "media" (movies & TV) */
    val kind: String = "anime",
    /** "movie" | "series" when [kind] is media */
    val mediaType: String? = null,
    val year: Int? = null
) {
    val key get() = favKey(kind, animeSlug, mediaType, animeTitle)
}
