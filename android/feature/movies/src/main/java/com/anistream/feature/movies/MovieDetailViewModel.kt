package com.anistream.feature.movies

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anistream.core.common.Result
import com.anistream.domain.model.FavoriteAnime
import com.anistream.domain.model.MediaDetail
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.PlaybackRequest
import com.anistream.domain.model.WatchHistory
import com.anistream.domain.model.mediaSlug
import com.anistream.domain.repository.FavoriteRepository
import com.anistream.domain.repository.MoviesRepository
import com.anistream.domain.repository.NowPlaying
import com.anistream.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where "Continue" picks up: season 0 / episode 0 for a movie. */
data class Resume(val season: Int, val episode: Int)

data class MovieDetailUiState(
    val isLoading: Boolean = true,
    val detail: MediaDetail? = null,
    val error: String? = null,
    val isFavorite: Boolean = false,
    val resume: Resume? = null,
    val selectedSeason: Int = 1,
    val resolving: Boolean = false,
    /** set when no in-app stream exists: the screen offers "Watch in web player" for this target */
    val webFallback: Resume? = null,
    /** per-episode progress of this title ("s1e2" -> p/at) */
    val eps: Map<String, com.anistream.domain.model.EpProgress> = emptyMap()
)

sealed interface DetailEvent {
    data object OpenPlayer : DetailEvent
    /** [web] non-null: offer "Watch in web player" for that target alongside the message */
    data class Message(val text: String, val web: Resume? = null) : DetailEvent
    data class OpenWebPlayer(val imdbId: String, val season: Int, val episode: Int) : DetailEvent
}

private val KeySuffix = Regex(":s(\\d+)e(\\d+)$")

/** History's catalog-independent media key ([mediaSlug]), not something the catalog can open directly. */
fun isCanonicalKey(id: String) = id.startsWith("movie:") || id.startsWith("series:")

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val movies: MoviesRepository,
    private val favorites: FavoriteRepository,
    private val history: WatchHistoryRepository
) : ViewModel() {

    private var id: String = savedStateHandle.get<String>("id").orEmpty()

    private val _uiState = MutableStateFlow(MovieDetailUiState())
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    private val _events = Channel<DetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            if (isCanonicalKey(id)) id = resolveCanonical(id) ?: id
            when (val r = movies.detail(id)) {
                is Result.Success -> {
                    val d = r.data
                    id = d.id
                    _uiState.update {
                        it.copy(
                            isLoading = false, detail = d, selectedSeason = d.seasons.firstOrNull()?.number ?: 1,
                            webFallback = if (d.playable) null else defaultTarget(d)
                        )
                    }
                    _uiState.update { it.copy(isFavorite = favorites.isSaved(d.asFavorite()).getOrNull() ?: false) }
                    refreshHistory()
                }
                is Result.Error -> _uiState.update { it.copy(isLoading = false, error = r.message ?: "Failed to load") }
                Result.Loading -> Unit
            }
        }
    }

    /**
     * A history key ("series:breakingbad") is not a catalog id. Use the row's stored id, else find the title by name
     * (same normalized title first, else the first result of the right type) and remember the id on the row.
     */
    private suspend fun resolveCanonical(key: String): String? {
        val row = history.getBySlug(key).getOrNull()
        row?.mediaId?.let { return it }
        val series = key.startsWith("series:")
        val title = row?.animeTitle ?: return null
        val hits = movies.search(title).getOrNull().orEmpty().filter { (it.type == MediaType.SERIES) == series }
        val hit = hits.firstOrNull { mediaSlug(series, it.title) == key } ?: hits.firstOrNull() ?: return null
        history.save(row.copy(mediaId = hit.id))
        return hit.id
    }

    /** Same title in another audio language: reload this screen with the variant's id. */
    fun selectVariant(variantId: String) {
        if (variantId == id) return
        id = variantId
        _uiState.value = MovieDetailUiState()
        load()
    }

    fun selectSeason(n: Int) = _uiState.update { it.copy(selectedSeason = n) }

    /** Accepts history saved under the plain id or the "id:sXeY" episode form, newest wins. */
    fun refreshHistory() {
        val d = _uiState.value.detail ?: return
        viewModelScope.launch {
            val all = history.getAll().getOrNull().orEmpty()
            val h: WatchHistory = all
                .filter { it.animeSlug == mediaSlug(d.type == MediaType.SERIES, d.title) || it.mediaId == d.id || it.animeSlug == d.id || it.animeSlug.startsWith(d.id + ":s") }
                .maxByOrNull { it.lastWatchedTimestamp } ?: run {
                _uiState.update { it.copy(resume = null, eps = emptyMap()) }
                return@launch
            }
            val m = KeySuffix.find(h.animeSlug)
            val resume = if (d.type == MediaType.MOVIE) {
                Resume(0, 0).takeIf { h.watchProgress in 0.01f..0.9f }
            } else {
                val season = h.season.takeIf { it > 0 } ?: m?.groupValues?.get(1)?.toInt() ?: d.seasons.firstOrNull()?.number ?: 1
                val ep = m?.groupValues?.get(2)?.toInt() ?: h.lastEpisodeWatched.coerceAtLeast(1)
                val count = d.seasons.firstOrNull { it.number == season }?.episodeCount ?: 0
                when {
                    h.watchProgress < 0.9f -> Resume(season, ep)
                    count == 0 || ep < count -> Resume(season, ep + 1)
                    else -> d.seasons.firstOrNull { it.number > season }?.let { Resume(it.number, 1) }
                }
            }
            _uiState.update { it.copy(resume = resume, eps = h.eps, selectedSeason = if (resume != null && resume.season > 0) resume.season else it.selectedSeason) }
        }
    }

    fun play(season: Int, episode: Int) {
        val d = _uiState.value.detail ?: return
        if (_uiState.value.resolving) return
        viewModelScope.launch {
            _uiState.update { it.copy(resolving = true) }
            val streams = movies.streams(d.id, season, episode)
            _uiState.update { it.copy(resolving = false) }
            val list = streams.getOrNull().orEmpty()
            if (list.isEmpty()) {
                val web = Resume(season, episode)
                _uiState.update { it.copy(webFallback = web) }
                _events.send(DetailEvent.Message(if (streams is Result.Error) streams.message ?: "No sources available" else "No sources available", web))
                return@launch
            }
            NowPlaying.request = PlaybackRequest(
                historyKey = mediaSlug(d.type == MediaType.SERIES, d.title),
                mediaId = d.id,
                title = d.title,
                subtitle = if (episode > 0) "S$season · E$episode" else null,
                posterUrl = d.posterUrl,
                streams = list,
                season = season,
                episode = episode,
                seasonEpisodeCount = d.seasons.firstOrNull { it.number == season }?.episodeCount ?: 0
            )
            _events.send(DetailEvent.OpenPlayer)
        }
    }

    /** Last resort: the web player, keyed by IMDb id. */
    fun openWebPlayer(target: Resume) {
        val d = _uiState.value.detail ?: return
        viewModelScope.launch {
            val tt = movies.imdbId(d.id)
            _events.send(
                if (tt == null) DetailEvent.Message("The web player can't find this title")
                else DetailEvent.OpenWebPlayer(tt, target.season, target.episode)
            )
        }
    }

    private fun MediaDetail.asFavorite() = FavoriteAnime(
        animeSlug = id, animeTitle = title, posterUrl = posterUrl, score = rating,
        kind = "media", mediaType = if (type == MediaType.SERIES) "series" else "movie", year = year
    )

    private fun defaultTarget(d: MediaDetail) =
        if (d.type == MediaType.SERIES) Resume(d.seasons.firstOrNull()?.number ?: 1, 1) else Resume(0, 0)

    fun toggleFavorite() {
        val d = _uiState.value.detail ?: return
        viewModelScope.launch {
            val r = favorites.toggle(d.asFavorite())
            if (r is Result.Success) _uiState.update { it.copy(isFavorite = r.data) }
        }
    }
}
