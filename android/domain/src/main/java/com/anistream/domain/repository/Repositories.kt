package com.anistream.domain.repository

import com.anistream.core.common.Result
import com.anistream.domain.model.AnimeDetail
import com.anistream.domain.model.AnimeSearchResult
import com.anistream.domain.model.Episode
import com.anistream.domain.model.Genre
import com.anistream.domain.model.ScheduleEntry
import com.anistream.domain.model.StreamSource
import com.anistream.domain.model.WatchHistory

/** hianime.at scraper (kept the old name to avoid churn across modules). */
interface AnidbRepository {
    suspend fun searchAnime(query: String, page: Int = 1): Result<List<AnimeSearchResult>>
    suspend fun getAnimeDetail(slug: String): Result<AnimeDetail>
    suspend fun getEpisodes(slug: String): Result<List<Episode>>
    /** language is "sub" or "dub". Every variant carries referer + subtitleUrl. */
    suspend fun resolveStream(episodeId: Long, language: String = "sub"): Result<List<StreamSource>>
    suspend fun getHomeContent(): Result<HomeContent>
    /** path is a hianime list page: "top-airing", "most-popular", "most-favorite", "movie", "tv", "genres/<slug>" */
    suspend fun browseCategory(path: String, page: Int = 1): Result<List<AnimeSearchResult>>
    suspend fun getGenres(): Result<List<Genre>>
    /** next 7 days, dayOfWeek = English full day name ("Monday") */
    suspend fun getSchedule(): Result<List<ScheduleEntry>>
}

data class HomeContent(
    val trending: List<AnimeSearchResult> = emptyList(),
    val topAiring: List<AnimeSearchResult> = emptyList(),
    val popular: List<AnimeSearchResult> = emptyList(),
    val favorites: List<AnimeSearchResult> = emptyList(),
    val completed: List<AnimeSearchResult> = emptyList(),
    val recentEpisodes: List<AnimeSearchResult> = emptyList()
)

interface WatchHistoryRepository {
    suspend fun getAll(): Result<List<WatchHistory>>
    /** kind: "anime" | "media" | null for both */
    suspend fun getContinueWatching(kind: String? = null): Result<List<WatchHistory>>
    suspend fun getBySlug(slug: String): Result<WatchHistory?>
    suspend fun save(history: WatchHistory): Result<Unit>
    suspend fun updateProgress(slug: String, episode: Int, progress: Float, durationMs: Long = 0): Result<Unit>
    suspend fun delete(slug: String): Result<Unit>
    suspend fun clear(): Result<Unit>
}

interface FavoriteRepository {
    suspend fun getAll(): Result<List<com.anistream.domain.model.FavoriteAnime>>
    suspend fun isFavorite(slug: String): Result<Boolean>
    /** by canonical key, so a title saved elsewhere under another id still counts */
    suspend fun isSaved(favorite: com.anistream.domain.model.FavoriteAnime): Result<Boolean>
    suspend fun toggle(favorite: com.anistream.domain.model.FavoriteAnime): Result<Boolean>
    suspend fun add(favorite: com.anistream.domain.model.FavoriteAnime): Result<Unit>
    suspend fun remove(slug: String): Result<Unit>
}

interface DownloadRepository {
    suspend fun getAll(): Result<List<com.anistream.domain.model.DownloadItem>>
    /** stream is the chosen quality variant (carries referer/subtitles); runs in the background, progress lands in the DB */
    suspend fun startDownload(stream: StreamSource, animeSlug: String, title: String, episodeNumber: Int, posterUrl: String? = null, stillUrl: String? = null, episodeTitle: String? = null): Result<Unit>
    /**
     * Movies & TV: a direct file (stream.isDirectFile) saved as "<title>.mp4" plus the first subtitle as ".srt".
     * key = the history key ("mb:<id>" or "mb:<id>:s1e2"); subtitle e.g. "S1 · E2", null for movies.
     */
    suspend fun startMediaDownload(stream: com.anistream.domain.model.MediaStream, key: String, title: String, subtitle: String?, posterUrl: String? = null, stillUrl: String? = null, episodeTitle: String? = null): Result<Unit> =
        Result.error(UnsupportedOperationException("Media downloads not supported"))
    suspend fun cancelDownload(episodeId: Long): Result<Unit>
    /** backfills artwork for rows saved without a poster */
    suspend fun setPoster(episodeId: Long, url: String) {}
    suspend fun deleteDownload(episodeId: Long): Result<Unit>
}
