package com.anistream.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anistream.core.account.HistorySync
import com.anistream.core.common.Result
import com.anistream.core.trailer.TrailerInfo
import com.anistream.core.trailer.TrailerKey
import com.anistream.core.trailer.TrailerRepository
import com.anistream.domain.ContentPrefs
import com.anistream.domain.model.AnimeSearchResult
import com.anistream.domain.model.Channel
import com.anistream.domain.model.FavoriteAnime
import com.anistream.domain.model.MediaItem
import com.anistream.domain.model.MediaRow
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.Recommendation
import com.anistream.domain.model.WatchHistory
import com.anistream.domain.model.liveRequest
import com.anistream.domain.repository.AnidbRepository
import com.anistream.domain.repository.FavoriteRepository
import com.anistream.domain.repository.HomeContent
import com.anistream.domain.repository.LiveTvRepository
import com.anistream.domain.repository.MoviesRepository
import com.anistream.domain.repository.NowPlaying
import com.anistream.domain.repository.RecommendationsRepository
import com.anistream.domain.usecase.GetContinueWatchingUseCase
import com.anistream.domain.usecase.GetHomeContentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** What the trailer UI shows beyond a Home card: synopsis, genres, and the MAL id / year anime cards often lack. */
data class TitleExtra(val synopsis: String?, val genres: List<String>, val malId: Long?, val year: Int?)

data class HomeUiState(
    val animeLoading: Boolean = true,
    val mediaLoading: Boolean = true,
    /** anime and Movies & TV both, newest first */
    val continueWatching: List<WatchHistory> = emptyList(),
    val content: HomeContent = HomeContent(),
    /** Cinemeta/MovieBox rows (Popular movies, Popular series, …) */
    val mediaRows: List<MediaRow> = emptyList(),
    /** channels from the saved Live TV playlists; empty without one */
    val channels: List<Channel> = emptyList(),
    val error: String? = null,
    val mediaError: String? = null,
    /** ranked "For you" anime / movies & series from the Room cache; empty without history */
    val picks: List<Recommendation> = emptyList(),
    val mediaPicks: List<Recommendation> = emptyList(),
    /** ids in My List (favourites), for the hero's toggle */
    val myList: Set<String> = emptySet()
) {
    val isLoading: Boolean get() = animeLoading || mediaLoading

    fun hiding(hidden: Set<String>): HomeUiState {
        fun media(t: MediaType?) = (if (t == MediaType.SERIES) ContentPrefs.SERIES else ContentPrefs.MOVIES) !in hidden
        val noAnime = ContentPrefs.ANIME in hidden
        return copy(
            continueWatching = continueWatching.filter { if (it.kind == "anime") !noAnime else ContentPrefs.MOVIES !in hidden || ContentPrefs.SERIES !in hidden },
            content = if (noAnime) HomeContent() else content,
            picks = if (noAnime) emptyList() else picks,
            mediaRows = mediaRows.map { r -> r.copy(items = r.items.filter { media(it.type) }) }.filter { it.items.isNotEmpty() },
            mediaPicks = mediaPicks.filter { media(it.mediaType) },
            channels = if (ContentPrefs.LIVE in hidden) emptyList() else channels
        )
    }
}

/** Movies and series ride the anime poster card: it only needs id/title/poster/type/score/year. */
fun MediaItem.asCard() = AnimeSearchResult(
    slug = id,
    title = title,
    posterUrl = posterUrl,
    type = if (type == MediaType.SERIES) "Series" else "Movie",
    score = rating,
    year = year
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getContinueWatching: GetContinueWatchingUseCase,
    private val getHomeContent: GetHomeContentUseCase,
    private val movies: MoviesRepository,
    private val liveTv: LiveTvRepository,
    private val favorites: FavoriteRepository,
    private val history: com.anistream.domain.repository.WatchHistoryRepository,
    private val anime: AnidbRepository,
    val trailers: TrailerRepository,
    recommendations: RecommendationsRepository,
    sync: HistorySync
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    /** [_uiState] with the profile's "What you watch" choice applied, so toggling it updates the screen at once. */
    val uiState: StateFlow<HomeUiState> = combine(_uiState, ContentPrefs.hidden) { s, hidden -> if (hidden.isEmpty()) s else s.hiding(hidden) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value)

    init {
        loadContent()
        // a history pull from the account changes Continue Watching
        viewModelScope.launch { sync.pulled.collect { refreshContinueWatching() } }
        viewModelScope.launch {
            recommendations.observe("anime").collect { picks -> _uiState.update { it.copy(picks = picks) } }
        }
        viewModelScope.launch {
            recommendations.observe("media").collect { picks -> _uiState.update { it.copy(mediaPicks = picks) } }
        }
    }

    fun loadContent() {
        _uiState.update { it.copy(animeLoading = true, mediaLoading = true, error = null, mediaError = null) }
        viewModelScope.launch {
            val result = getHomeContent()
            val content = result.getOrNull()
            _uiState.update {
                it.copy(
                    animeLoading = false,
                    content = content ?: it.content,
                    error = if (content == null) result.exceptionOrNull()?.message ?: "Couldn't load anime" else null
                )
            }
        }
        viewModelScope.launch {
            when (val r = movies.home()) {
                is Result.Success -> _uiState.update { s -> s.copy(mediaLoading = false, mediaRows = r.data.filter { it.items.isNotEmpty() }) }
                is Result.Error -> _uiState.update { it.copy(mediaLoading = false, mediaError = r.message ?: "Couldn't load movies") }
                Result.Loading -> Unit
            }
        }
        viewModelScope.launch {
            // only fetch when there is a playlist; the repository keeps the parsed list in memory
            if (liveTv.playlists().first().isEmpty()) return@launch
            val channels = (liveTv.channels() as? Result.Success)?.data.orEmpty()
            _uiState.update { it.copy(channels = channels.take(CHANNELS_ON_HOME)) }
        }
        refreshContinueWatching()
    }

    fun removeHistory(slug: String) {
        viewModelScope.launch { history.delete(slug); refreshContinueWatching() }
    }

    /** Called on every resume so progress saved by the player shows up immediately. */
    fun refreshContinueWatching() {
        viewModelScope.launch {
            val list = getContinueWatching(kind = null).getOrNull() ?: return@launch
            _uiState.update { it.copy(continueWatching = list) }
        }
        viewModelScope.launch {
            val ids = favorites.getAll().getOrNull()?.map { it.animeSlug }?.toSet() ?: return@launch
            _uiState.update { it.copy(myList = ids) }
        }
    }

    fun toggleMyList(item: AnimeSearchResult) {
        viewModelScope.launch {
            val added = favorites.toggle(
                FavoriteAnime(animeSlug = item.slug, animeTitle = item.title, posterUrl = item.posterUrl, score = item.score)
            ).getOrNull() ?: return@launch
            _uiState.update { it.copy(myList = if (added) it.myList + item.slug else it.myList - item.slug) }
        }
    }

    private val extras = ConcurrentHashMap<String, Deferred<TitleExtra?>>()

    /** Detail-page extras for a Home title, fetched once per id and shared by the hero, Hold to project and the reel. */
    suspend fun extra(id: String, media: Boolean): TitleExtra? = extras.getOrPut(id) {
        // IO: AnidbHttpClient.getHtml reads the response body on the caller's thread
        viewModelScope.async(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (media) movies.detail(id).getOrNull()?.let { TitleExtra(it.description, it.genres, null, it.year) }
                else anime.getAnimeDetail(id).getOrNull()?.let { TitleExtra(it.synopsis, it.genres.map { g -> g.name }, it.malId, it.year) }
            }.getOrNull()
        }
    }.await()

    private suspend fun trailerKey(card: AnimeSearchResult, media: Boolean): TrailerKey {
        // anime cards rarely carry a MAL id or year: the detail has them
        val e = if (media || (card.malId != null && card.year != null)) null else extra(card.slug, false)
        return trailerKeyFor(card.slug, card.title, card.year ?: e?.year, card.type, media, card.malId ?: e?.malId)
    }

    suspend fun trailer(card: AnimeSearchResult, media: Boolean): TrailerInfo? = trailers.get(trailerKey(card, media))
    suspend fun logo(card: AnimeSearchResult, media: Boolean): String? = trailers.logo(trailerKey(card, media))

    private val _reelReady = MutableStateFlow<Set<String>>(emptySet())
    /** ids of Tonight's reel candidates that resolved to a playable trailer */
    val reelReady: StateFlow<Set<String>> = _reelReady.asStateFlow()
    private var reelJob: Job? = null

    /** Resolve reel candidates one by one (cached in TrailerRepository, so the feed starts instantly). */
    fun probeReel(items: List<Pair<AnimeSearchResult, Boolean>>) {
        reelJob?.cancel()
        reelJob = viewModelScope.launch {
            for ((card, media) in items) {
                if (card.slug in _reelReady.value) continue
                if (trailer(card, media)?.streamUrl != null) _reelReady.update { it + card.slug }
            }
        }
    }

    fun playChannel(channel: Channel) {
        NowPlaying.request = channel.liveRequest()
    }

    private companion object {
        const val CHANNELS_ON_HOME = 20
    }
}
