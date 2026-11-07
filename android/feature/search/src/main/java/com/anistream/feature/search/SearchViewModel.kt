package com.anistream.feature.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anistream.core.common.Result
import com.anistream.domain.ContentPrefs
import com.anistream.domain.KidsPolicy
import com.anistream.domain.model.AnimeSearchResult
import com.anistream.domain.model.MediaItem
import com.anistream.domain.model.MediaType
import com.anistream.domain.repository.AnidbRepository
import com.anistream.domain.repository.MoviesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<AnimeSearchResult> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val popular: List<AnimeSearchResult> = emptyList(),
    val isPopularLoading: Boolean = true,
    val typeFilter: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val page: Int = 1,
    val error: String? = null,
    /** 0 = anime, 1 = movies & TV */
    val scope: Int = 0,
    val mediaPopular: List<AnimeSearchResult> = emptyList(),
    val isMediaPopularLoading: Boolean = true
) {
    val visibleResults: List<AnimeSearchResult>
        get() = typeFilter?.let { f -> results.filter { it.type.equals(f, ignoreCase = true) } } ?: results
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AnidbRepository,
    private val movies: MoviesRepository,
    @ApplicationContext context: Context
) : ViewModel() {

    private val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(SearchUiState(recentSearches = prefs.getString(KEY_RECENTS, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()))
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val popular = repository.browseCategory("most-popular").getOrNull().orEmpty().filter { !KidsPolicy.active || KidsPolicy.animeCard(it) }.distinctBy { it.slug }
            _uiState.update { it.copy(popular = popular, isPopularLoading = false) }
        }
    }

    private var mediaPopularRequested = false

    init {
        // the Anime | Movies & TV choice survives restarts
        onScopeChange(prefs.getInt(KEY_SCOPE, 0))
        // "What you watch": land on a scope that is still shown, now and whenever the choice changes
        viewModelScope.launch {
            ContentPrefs.hidden.collect {
                if (!ContentPrefs.shows(ContentPrefs.ANIME) && _uiState.value.scope == 0) onScopeChange(1)
                else if (!ContentPrefs.showsMoviesTab() && _uiState.value.scope == 1) onScopeChange(0)
            }
        }
    }

    fun onScopeChange(scope: Int) {
        if (scope == _uiState.value.scope) return
        prefs.edit().putInt(KEY_SCOPE, scope).apply()
        _uiState.update { it.copy(scope = scope, typeFilter = null, results = emptyList()) }
        if (scope == 1 && !mediaPopularRequested) {
            mediaPopularRequested = true
            viewModelScope.launch {
                val first = movies.home().getOrNull()?.firstOrNull()?.items.orEmpty().filter { ContentPrefs.shows(it.type) }.map { it.asCard() }.distinctBy { it.slug }
                _uiState.update { it.copy(mediaPopular = first, isMediaPopularLoading = false) }
            }
        }
        onQueryChange(_uiState.value.query)
    }

    /** Both scopes land in the same poster grid; movie ids ride in [AnimeSearchResult.slug]. */
    /** Returns the page's items and the last page read (a kids profile may skip pages that filter to nothing). */
    private suspend fun search(query: String, page: Int): Pair<Result<List<AnimeSearchResult>>, Int> =
        if (_uiState.value.scope == 1) KidsPolicy.loadKept(page, { m: MediaItem -> KidsPolicy.media(m.genres) && ContentPrefs.shows(m.type) }) { movies.search(query, it) }
            .let { (r, p) -> r.map { list -> list.map { it.asCard() } } to p }
        else KidsPolicy.loadKept(page, KidsPolicy::animeCard) { repository.searchAnime(query, it) }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), isLoading = false, error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            delay(400)
            when (val result = search(query.trim(), 1).first) {
                is Result.Success -> _uiState.update { s ->
                    s.copy(
                        results = result.data.distinctBy { it.slug },
                        isLoading = false,
                        page = 1,
                        endReached = result.data.isEmpty(),
                        // typing "d", "de", "dea" must not leave three recents: drop earlier prefixes of this query
                        recentSearches = (listOf(query.trim()) + s.recentSearches.filterNot { query.trim().startsWith(it, ignoreCase = true) }).take(10)
                    ).also { saveRecents(it.recentSearches) }
                }
                is Result.Error -> _uiState.update { it.copy(isLoading = false, error = result.message ?: "Search failed") }
                is Result.Loading -> {}
            }
        }
    }

    fun loadNextPage() {
        val s = _uiState.value
        if (s.isLoading || s.isLoadingMore || s.endReached || s.query.isBlank()) return
        val next = s.page + 1
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val (moreResult, readTo) = search(s.query.trim(), next)
            val more = moreResult.getOrNull()
            _uiState.update { st ->
                if (more == null) {
                    st.copy(isLoadingMore = false, endReached = true)
                } else {
                    val merged = (st.results + more).distinctBy { it.slug }
                    st.copy(
                        results = merged,
                        page = readTo,
                        isLoadingMore = false,
                        // a page that adds nothing new means we're past the end
                        endReached = merged.size == st.results.size
                    )
                }
            }
        }
    }

    fun onClearQuery() = onQueryChange("")

    fun onTypeFilterChange(type: String?) = _uiState.update { it.copy(typeFilter = type) }

    fun onRecentSearchClick(query: String) = onQueryChange(query)

    fun onRemoveRecent(query: String) {
        _uiState.update { it.copy(recentSearches = it.recentSearches - query) }
        saveRecents(_uiState.value.recentSearches)
    }

    fun onClearRecents() {
        _uiState.update { it.copy(recentSearches = emptyList()) }
        saveRecents(emptyList())
    }

    private fun saveRecents(list: List<String>) = prefs.edit().putString(KEY_RECENTS, list.joinToString("\n")).apply()
}

private fun MediaItem.asCard() = AnimeSearchResult(
    slug = id,
    title = title,
    posterUrl = posterUrl,
    type = if (type == MediaType.SERIES) "Series" else "Movie",
    score = rating,
    year = year
)

private const val KEY_SCOPE = "scope"
private const val KEY_RECENTS = "recents"
