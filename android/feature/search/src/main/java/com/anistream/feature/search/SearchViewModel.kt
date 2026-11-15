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
import com.anistream.domain.usecase.band
import com.anistream.domain.usecase.titleScore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val error: String? = null,
    /** [SCOPE_ANIME], [SCOPE_MEDIA] or [SCOPE_ALL] (the default) */
    val scope: Int = SCOPE_ALL,
    val mediaPopular: List<AnimeSearchResult> = emptyList(),
    val isMediaPopularLoading: Boolean = true
) {
    val visibleResults: List<AnimeSearchResult>
        get() = typeFilter?.let { f -> results.filter { it.type.equals(f, ignoreCase = true) } } ?: results

    /** What the idle grid shows for the scope; All alternates anime and movies & TV. */
    val shownPopular: List<AnimeSearchResult>
        get() = when (scope) {
            SCOPE_ANIME -> popular
            SCOPE_MEDIA -> mediaPopular
            else -> (0 until maxOf(popular.size, mediaPopular.size)).flatMap { i -> listOfNotNull(popular.getOrNull(i)?.copy(type = ANIME_TYPE), mediaPopular.getOrNull(i)) }
        }
    val shownPopularLoading: Boolean
        get() = when (scope) { SCOPE_ANIME -> isPopularLoading; SCOPE_MEDIA -> isMediaPopularLoading; else -> isPopularLoading && isMediaPopularLoading }

    /** Which detail page a card opens: by tab, and in All by the card's kind. */
    fun isMedia(r: AnimeSearchResult) = scope == SCOPE_MEDIA || (scope == SCOPE_ALL && r.type != ANIME_TYPE)
}

const val SCOPE_ANIME = 0
const val SCOPE_MEDIA = 1
/** Anime and movies & TV together; anime cards carry type [ANIME_TYPE] so the kind shows on the card. */
const val SCOPE_ALL = 2
const val ANIME_TYPE = "Anime"

/** Search tabs in screen order (label to scope): only kinds "What you watch" shows, and no tabs when just one kind is left. */
fun searchTabs(): List<Pair<String, Int>> {
    val anime = ContentPrefs.shows(ContentPrefs.ANIME)
    val media = ContentPrefs.showsMoviesTab()
    return if (anime && media) listOf("All" to SCOPE_ALL, "Anime" to SCOPE_ANIME, "Movies & TV" to SCOPE_MEDIA) else emptyList()
}

/** All: one list, best title match first whatever the kind (same 0.05 bands as the movies ranking, which keeps its order inside a band; anime first on a tie). */
internal fun mergeKinds(query: String, anime: List<AnimeSearchResult>, media: List<AnimeSearchResult>) =
    (anime + media).sortedByDescending { band(titleScore(query, it.title)) }

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
        // search opens on All each time (not remembered across launches); "What you watch" narrows it to the kind still shown
        if (_uiState.value.scope != SCOPE_ANIME) loadMediaPopular()
        viewModelScope.launch { ContentPrefs.hidden.collect { onScopeChange(fit(_uiState.value.scope)) } }
    }

    private fun fit(scope: Int) = when {
        !ContentPrefs.shows(ContentPrefs.ANIME) -> SCOPE_MEDIA
        !ContentPrefs.showsMoviesTab() -> SCOPE_ANIME
        else -> scope
    }

    fun onScopeChange(scope: Int) {
        if (scope == _uiState.value.scope) return
        _uiState.update { it.copy(scope = scope, typeFilter = null, results = emptyList()) }
        if (scope != SCOPE_ANIME) loadMediaPopular()
        onQueryChange(_uiState.value.query)
    }

    private fun loadMediaPopular() {
        if (!mediaPopularRequested) {
            mediaPopularRequested = true
            viewModelScope.launch {
                val first = movies.home().getOrNull()?.firstOrNull()?.items.orEmpty().filter { ContentPrefs.shows(it.type) }.map { it.asCard() }.distinctBy { it.slug }
                _uiState.update { it.copy(mediaPopular = first, isMediaPopularLoading = false) }
            }
        }
    }

    /** Both kinds land in the same poster grid; movie ids ride in [AnimeSearchResult.slug]. */
    /** One kind's page and the last page read (a kids profile may skip pages that filter to nothing). */
    private suspend fun search(side: Int, query: String, page: Int): Pair<Result<List<AnimeSearchResult>>, Int> =
        if (side == SCOPE_MEDIA) KidsPolicy.loadKept(page, { m: MediaItem -> KidsPolicy.media(m.genres) && ContentPrefs.shows(m.type) }) { movies.search(query, it) }
            .let { (r, p) -> r.map { list -> list.map { it.asCard() } } to p }
        else KidsPolicy.loadKept(page, KidsPolicy::animeCard) { repository.searchAnime(query, it) }
            .let { (r, p) -> (if (_uiState.value.scope == SCOPE_ALL) r.map { l -> l.map { it.copy(type = ANIME_TYPE) } } else r) to p }

    private fun sides() = when (_uiState.value.scope) { SCOPE_ALL -> listOf(SCOPE_ANIME, SCOPE_MEDIA); else -> listOf(_uiState.value.scope) }
    private fun combine(query: String, lists: List<List<AnimeSearchResult>?>) =
        if (lists.size == 2) mergeKinds(query, lists[0].orEmpty(), lists[1].orEmpty()) else lists[0].orEmpty()

    // per kind (index = SCOPE_ANIME / SCOPE_MEDIA): last page read and whether it ran out
    private val pages = IntArray(2)
    private val ends = BooleanArray(2)

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
            // All runs both searches at once and shows each kind as it lands; a kind that fails just drops out
            val q = query.trim()
            val sides = sides()
            val got = arrayOfNulls<List<AnimeSearchResult>>(sides.size)
            var done = 0
            var err: String? = null
            coroutineScope {
                sides.forEachIndexed { i, side ->
                    launch {
                        val (r, p) = search(side, q, 1)
                        pages[side] = p
                        ends[side] = r.getOrNull().isNullOrEmpty()
                        if (r is Result.Error) err = r.message ?: "Search failed" else got[i] = r.getOrNull().orEmpty().distinctBy { it.slug }
                        done++
                        val list = combine(q, got.toList())
                        _uiState.update {
                            it.copy(
                                results = list,
                                isLoading = done < sides.size && list.isEmpty(),
                                endReached = sides.all { s -> ends[s] },
                                error = if (got.all { g -> g == null } && done == sides.size) err else null
                            )
                        }
                    }
                }
            }
            if (got.any { it != null }) _uiState.update { s ->
                // typing "d", "de", "dea" must not leave three recents: drop earlier prefixes of this query
                s.copy(recentSearches = (listOf(q) + s.recentSearches.filterNot { q.startsWith(it, ignoreCase = true) }).take(10)).also { saveRecents(it.recentSearches) }
            }
        }
    }

    fun loadNextPage() {
        val s = _uiState.value
        if (s.isLoading || s.isLoadingMore || s.endReached || s.query.isBlank()) return
        val q = s.query.trim()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            // the next page of each kind that has more, ranked together and added under what is already shown
            val sides = sides()
            val more = coroutineScope {
                sides.map { side ->
                    async {
                        if (ends[side]) return@async null
                        val (r, readTo) = search(side, q, pages[side] + 1)
                        pages[side] = readTo
                        val page = r.getOrNull()
                        // a failed page, or one that adds nothing new, means this kind is past its end
                        if (page == null || page.all { p -> _uiState.value.results.any { it.slug == p.slug } }) ends[side] = true
                        page
                    }
                }.awaitAll()
            }
            _uiState.update { st ->
                st.copy(results = (st.results + combine(q, more)).distinctBy { it.slug }, isLoadingMore = false, endReached = sides.all { ends[it] })
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

private const val KEY_RECENTS = "recents"
