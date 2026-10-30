package com.anistream.feature.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anistream.core.common.Result
import com.anistream.domain.KidsPolicy
import com.anistream.domain.model.AnimeSearchResult
import com.anistream.domain.model.MediaItem
import com.anistream.domain.model.MediaRow
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.Recommendation
import com.anistream.domain.model.WatchHistory
import com.anistream.domain.repository.RecommendationsRepository
import com.anistream.domain.repository.MoviesRepository
import com.anistream.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Movies reuse the anime poster card: the card only needs id/title/poster/type/score/year. */
internal fun MediaItem.asCard() = AnimeSearchResult(
    slug = id,
    title = title,
    posterUrl = posterUrl,
    type = if (type == MediaType.SERIES) "Series" else "Movie",
    score = rating,
    year = year
)

/** True for watch-history / favourite keys that belong to Movies & TV rather than anime. */
fun isMediaKey(key: String) = key.startsWith("mb:") || key.startsWith("tmdb:") || (key.length > 2 && key.startsWith("tt") && key[2].isDigit()) || isCanonicalKey(key)

data class MoviesHomeUiState(
    val isLoading: Boolean = true,
    val rows: List<MediaRow> = emptyList(),
    val continueWatching: List<WatchHistory> = emptyList(),
    val error: String? = null,
    /** ranked "For you" movies/series (TMDB) from the Room cache; empty without history */
    val picks: List<Recommendation> = emptyList()
)

/** A pick as a browsable item: ids are "tt…", so taps open the regular detail. */
fun Recommendation.asMediaItem() = MediaItem(
    id = id, type = mediaType ?: MediaType.MOVIE, title = title, posterUrl = posterUrl, year = year, rating = rating
)

@HiltViewModel
class MoviesHomeViewModel @Inject constructor(
    private val movies: MoviesRepository,
    private val history: WatchHistoryRepository,
    recommendations: RecommendationsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoviesHomeUiState())
    val uiState: StateFlow<MoviesHomeUiState> = _uiState.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            recommendations.observe("media").collect { picks -> _uiState.update { it.copy(picks = picks) } }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val r = movies.home()) {
                is Result.Success -> _uiState.update { s -> s.copy(isLoading = false, rows = r.data.filter { it.items.isNotEmpty() }) }
                is Result.Error -> _uiState.update { it.copy(isLoading = false, error = r.message ?: "Failed to load") }
                Result.Loading -> Unit
            }
        }
    }

    fun removeHistory(slug: String) {
        viewModelScope.launch { history.delete(slug); refreshContinueWatching() }
    }

    fun refreshContinueWatching() {
        viewModelScope.launch {
            val list = history.getContinueWatching("media").getOrNull().orEmpty()
            _uiState.update { it.copy(continueWatching = list) }
        }
    }
}

data class CatalogUiState(
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class MoviesCatalogViewModel @Inject constructor(
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val movies: MoviesRepository
) : ViewModel() {

    val key: String = savedStateHandle.get<String>("key").orEmpty()
    val title: String = savedStateHandle.get<String>("title").orEmpty()
    private var job: kotlinx.coroutines.Job? = null
    /** Cinemeta's skip counts raw items, so it can't be derived from the (possibly filtered) list size. */
    private var rawSkip = 0

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    init { reload() }

    fun reload() {
        job?.cancel()
        _uiState.value = CatalogUiState()
        rawSkip = 0
        loadMore()
    }

    fun loadMore() {
        val s = _uiState.value
        if (job?.isActive == true || s.endReached) return
        job = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = it.items.isNotEmpty()) }
            var r = movies.catalog(key, rawSkip)
            var tries = 0
            while (r is Result.Success) {
                rawSkip += r.data.size
                if (!KidsPolicy.active) break
                val kept = r.data.filter { KidsPolicy.media(it.genres) }
                // a page that filters to nothing must not look like the end; read on (up to 5 pages)
                if (kept.isNotEmpty() || r.data.isEmpty() || ++tries >= 5) { r = Result.success(kept); break }
                r = movies.catalog(key, rawSkip)
            }
            when (r) {
                is Result.Success -> _uiState.update { st ->
                    val merged = (st.items + r.data).distinctBy { it.id }
                    st.copy(items = merged, isLoading = false, isLoadingMore = false, endReached = merged.size == st.items.size)
                }
                is Result.Error -> _uiState.update { st ->
                    st.copy(isLoading = false, isLoadingMore = false, endReached = st.items.isNotEmpty(), error = if (st.items.isEmpty()) r.message ?: "Failed to load" else null)
                }
                Result.Loading -> Unit
            }
        }
    }
}
