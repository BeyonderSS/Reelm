package com.anistream.domain.repository

import com.anistream.core.common.Result
import com.anistream.domain.model.Addon
import com.anistream.domain.model.Channel
import com.anistream.domain.model.MediaDetail
import com.anistream.domain.model.MediaItem
import com.anistream.domain.model.MediaRow
import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.PlaybackRequest
import com.anistream.domain.model.Playlist
import com.anistream.domain.model.Recommendation
import com.anistream.domain.model.SkipInterval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Movies & TV: Cinemeta for browse rows, MovieBox for search/detail/streams, enabled Stremio addons for extra streams. */
interface MoviesRepository {
    /** Home rows (Popular movies, Popular series, Featured, a few genres …). */
    suspend fun home(): Result<List<MediaRow>>

    /** "See all" for a row's catalogKey, paged by [skip] items. */
    suspend fun catalog(catalogKey: String, skip: Int = 0): Result<List<MediaItem>>

    suspend fun search(query: String, page: Int = 1): Result<List<MediaItem>>

    /** Accepts any MediaItem.id; a Cinemeta "tt…" id is matched to its MovieBox subject (title + year + type). */
    suspend fun detail(id: String): Result<MediaDetail>

    /** All playable sources, best first. Movies: season = episode = 0. */
    suspend fun streams(id: String, season: Int = 0, episode: Int = 0): Result<List<MediaStream>>

    /** IMDb "tt…" id for a MediaItem.id, or null when there is none (web-player fallback). */
    suspend fun imdbId(mediaId: String): String? = mediaId.takeIf { it.startsWith("tt") }
}

interface AddonRepository {
    fun addons(): Flow<List<Addon>>
    /** Fetches and validates the manifest before saving. */
    suspend fun add(manifestUrl: String): Result<Addon>
    suspend fun remove(id: String): Result<Unit>
    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit>
}

interface LiveTvRepository {
    fun playlists(): Flow<List<Playlist>>
    suspend fun addPlaylist(name: String, url: String): Result<Playlist>
    suspend fun removePlaylist(id: Long): Result<Unit>
    /** Channels from every saved playlist, deduplicated by stream URL. Cached in memory unless [refresh]. */
    suspend fun channels(refresh: Boolean = false): Result<List<Channel>>
}

/** On-device "For you" picks (TMDB for movies/series, hianime for anime), cached in Room and refreshed daily. */
interface RecommendationsRepository {
    /** kind "anime" | "media"; empty without watch history or before the first refresh */
    fun observe(kind: String): Flow<List<Recommendation>>
    suspend fun refresh(): Result<Unit>
}

/** AniSkip intro/recap/credits times; empty when unknown or offline. */
interface SkipTimesRepository {
    suspend fun skipTimes(malId: Long, episode: Int): List<SkipInterval>
}

/**
 * Hand-off between a screen that resolved streams and the generic media player.
 * ponytail: in-memory, so process death mid-playback returns to the previous screen; persist if that bites.
 */
object NowPlaying {
    @Volatile
    var request: PlaybackRequest? = null

    /**
     * Sources that answered after [MoviesRepository.streams] returned (slow networks): the player appends them to the
     * request whose `mediaId:season:episode` matches [LateStreams.key]. Replay covers the gap before the player subscribes.
     */
    val late = MutableSharedFlow<LateStreams>(replay = 16, extraBufferCapacity = 16)
    fun lateKey(id: String, season: Int, episode: Int) = "$id:$season:$episode"
}

data class LateStreams(val key: String, val streams: List<MediaStream>)
