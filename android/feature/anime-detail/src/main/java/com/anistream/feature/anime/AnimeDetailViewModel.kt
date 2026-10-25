package com.anistream.feature.anime

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anistream.core.common.Result
import com.anistream.domain.model.AnimeDetail
import com.anistream.core.ui.components.DownloadOption
import com.anistream.domain.model.DownloadState
import com.anistream.domain.model.Episode
import com.anistream.domain.model.StreamSource
import com.anistream.domain.model.FavoriteAnime
import com.anistream.domain.model.WatchHistory
import com.anistream.domain.repository.AnidbRepository
import com.anistream.domain.repository.DownloadRepository
import com.anistream.domain.repository.FavoriteRepository
import com.anistream.domain.repository.WatchHistoryRepository
import com.anistream.domain.usecase.GetAnimeDetailUseCase
import com.anistream.domain.usecase.GetEpisodesUseCase
import com.anistream.domain.usecase.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnimeDetailUiState(
    val isLoading: Boolean = true,
    val anime: AnimeDetail? = null,
    val episodes: List<Episode> = emptyList(),
    val episodesError: String? = null,
    val isFavorite: Boolean = false,
    val history: WatchHistory? = null,
    val error: String? = null,
    val sheetOpen: Boolean = false,
    val options: List<DownloadOption>? = null,
    val selectedId: String? = null,
    val picked: Set<Int> = emptySet(),
    val downloaded: Set<Int> = emptySet(),
    val activeEpisode: Int? = null,
    val activeProgress: Float = 0f,
    val queued: Int = 0,
    val message: String? = null
)

@HiltViewModel
class AnimeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getAnimeDetail: GetAnimeDetailUseCase,
    private val getEpisodes: GetEpisodesUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val favoriteRepository: FavoriteRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val anidb: AnidbRepository,
    private val downloads: DownloadRepository,
    private val guide: com.anistream.core.media.EpisodeGuide
) : ViewModel() {

    val slug: String = savedStateHandle.get<String>("slug") ?: ""

    private val _uiState = MutableStateFlow(AnimeDetailUiState())
    val uiState: StateFlow<AnimeDetailUiState> = _uiState.asStateFlow()

    init {
        loadAnimeDetail()
        viewModelScope.launch {
            val fav = favoriteRepository.isFavorite(slug).getOrNull() ?: false
            _uiState.update { it.copy(isFavorite = fav) }
        }
    }

    fun loadAnimeDetail() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, episodesError = null) }
            val episodesDeferred = async { getEpisodes(slug) }
            val detailResult = getAnimeDetail(slug)
            val episodesResult = episodesDeferred.await()
            val detail = detailResult.getOrNull()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    anime = detail,
                    episodes = episodesResult.getOrNull() ?: emptyList(),
                    episodesError = episodesResult.exceptionOrNull()?.let { e -> e.message ?: "Failed to load episodes" },
                    error = if (detail == null) detailResult.exceptionOrNull()?.message ?: "Failed to load" else null
                )
            }
            refreshDownloads()
        }
    }

    /** Called on resume so the Continue button reflects progress made in the player. */
    fun refreshHistory() {
        viewModelScope.launch {
            val h = watchHistoryRepository.getBySlug(slug).getOrNull()
            _uiState.update { it.copy(history = h) }
        }
    }

    fun toggleFavorite() {
        val anime = _uiState.value.anime ?: return
        viewModelScope.launch {
            val result = toggleFavorite(
                FavoriteAnime(
                    animeSlug = slug,
                    animeTitle = anime.title,
                    posterUrl = anime.posterUrl,
                    score = anime.score,
                    totalEpisodes = anime.episodes ?: _uiState.value.episodes.size,
                    lastEpisodeWatched = _uiState.value.history?.lastEpisodeWatched ?: 0
                )
            )
            if (result is Result.Success) {
                _uiState.update { it.copy(isFavorite = result.data) }
            }
        }
    }

    private var pollJob: Job? = null
    private var optionsJob: Job? = null
    private val variantCache = mutableMapOf<Int, List<StreamSource>>()

    private suspend fun variants(ep: Int): List<StreamSource> = variantCache.getOrPut(ep) {
        val id = _uiState.value.episodes.firstOrNull { it.number == ep }?.id ?: return emptyList()
        (anidb.resolveStream(id, "sub").getOrNull() ?: emptyList()).sorted()
    }

    private suspend fun refreshDownloads() {
        val title = _uiState.value.anime?.title ?: return
        val rows = downloads.getAll().getOrNull().orEmpty()
            .filter { it.animeTitle == title && it.subtitle == null && it.episodeNumber > 0 }
        val live = rows.filter { it.state == DownloadState.PENDING || it.state == DownloadState.DOWNLOADING }
        val active = live.firstOrNull { it.state == DownloadState.DOWNLOADING } ?: live.firstOrNull()
        _uiState.update {
            it.copy(
                downloaded = rows.filter { r -> r.state == DownloadState.COMPLETED }.map { r -> r.episodeNumber }.toSet(),
                activeEpisode = active?.episodeNumber,
                activeProgress = active?.progress ?: 0f,
                queued = (live.size - 1).coerceAtLeast(0)
            )
        }
        if (live.isNotEmpty() && pollJob?.isActive != true) {
            pollJob = viewModelScope.launch {
                do {
                    delay(1500)
                    refreshDownloads()
                } while (_uiState.value.activeEpisode != null)
            }
        }
    }

    fun openSheet(nextUp: Int) {
        _uiState.update { it.copy(sheetOpen = true, picked = setOf(nextUp), selectedId = null, options = null) }
        loadOptions(nextUp)
    }

    fun closeSheet() = _uiState.update { it.copy(sheetOpen = false) }

    fun select(id: String) = _uiState.update { it.copy(selectedId = id) }

    fun setPicked(picked: Set<Int>) {
        val old = _uiState.value.picked.minOrNull()
        _uiState.update { it.copy(picked = picked) }
        picked.minOrNull()?.takeIf { it != old }?.let(::loadOptions)
    }

    private fun loadOptions(ep: Int) {
        optionsJob?.cancel()
        optionsJob = viewModelScope.launch {
            _uiState.update { it.copy(options = null) }
            val opts = variants(ep).distinctBy { it.qualityLabel }
                .map { DownloadOption(it.qualityLabel, it.qualityLabel, "Stream", null, best = false) }
                .mapIndexed { i, o -> o.copy(best = i == 0) }
            _uiState.update { s -> s.copy(options = opts, selectedId = s.selectedId?.takeIf { id -> opts.any { it.id == id } } ?: opts.firstOrNull()?.id) }
        }
    }

    fun confirmDownload() {
        val s = _uiState.value
        val anime = s.anime ?: return
        val label = s.selectedId ?: return
        val eps = s.picked.sorted()
        if (eps.isEmpty()) return
        val target = label.filter { it.isDigit() }.toIntOrNull() ?: 0
        _uiState.update { it.copy(sheetOpen = false, message = "Downloading ${eps.size} episode${if (eps.size == 1) "" else "s"}") }
        viewModelScope.launch {
            val names = runCatching { guide.anime(anime.title, anime.titleJapanese, anime.year, anime.episodes ?: eps.last()) }.getOrDefault(emptyList()).associateBy { it.n }
            for (ep in eps) {
                val v = variants(ep)
                val pick = v.firstOrNull { it.qualityLabel == label }
                    ?: v.filter { it.height <= target }.maxByOrNull { it.height }
                    ?: v.firstOrNull() ?: continue
                downloads.startDownload(pick, slug, anime.title, ep, anime.posterUrl ?: anime.bannerUrl, names[ep]?.still, names[ep]?.title)
            }
            refreshDownloads()
        }
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
