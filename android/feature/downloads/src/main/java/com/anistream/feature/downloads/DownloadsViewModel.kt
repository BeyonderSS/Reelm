package com.anistream.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anistream.core.database.dao.DownloadDao
import com.anistream.domain.model.DownloadItem
import com.anistream.domain.model.DownloadState
import com.anistream.domain.repository.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.anistream.core.common.Result
import com.anistream.domain.repository.FavoriteRepository
import com.anistream.domain.repository.MoviesRepository
import com.anistream.domain.repository.WatchHistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadsUiState(
    val isLoading: Boolean = true,
    val downloads: List<DownloadItem> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val dao: DownloadDao,
    private val downloadRepository: DownloadRepository,
    private val history: WatchHistoryRepository,
    private val favorites: FavoriteRepository,
    private val movies: MoviesRepository
) : ViewModel() {

    // title -> poster found by lookup; "" = looked, nothing found
    private val found = MutableStateFlow<Map<String, String>>(emptyMap())

    // DownloadRepository only exposes a one-shot getAll(); the DAO Flow gives live progress
    val uiState: StateFlow<DownloadsUiState> = combine(dao.getAll(), found) { rows, posters ->
        DownloadsUiState(
            isLoading = false,
            downloads = rows.map {
                DownloadItem(
                    episodeId = it.episodeId,
                    animeTitle = it.animeTitle,
                    episodeNumber = it.episodeNumber,
                    quality = it.quality,
                    filePath = it.filePath,
                    fileSizeBytes = it.fileSizeBytes,
                    progress = it.progress,
                    state = runCatching { DownloadState.valueOf(it.state) }.getOrDefault(DownloadState.PENDING),
                    createdAt = it.createdAt,
                    subtitle = it.subtitle,
                    posterUrl = it.posterUrl ?: posters[it.animeTitle]?.ifEmpty { null },
                    stillUrl = it.stillUrl,
                    episodeTitle = it.episodeTitle
                )
            }
        )
    }
        .catch { emit(DownloadsUiState(isLoading = false, error = it.message ?: "Failed to load downloads")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    init {
        // rows saved without artwork (the list used to drop posterUrl too): look it up once per title and persist it
        viewModelScope.launch {
            dao.getAll().collect { rows ->
                rows.filter { it.posterUrl == null && it.animeTitle !in found.value }.distinctBy { it.animeTitle }.forEach { row ->
                    val title = row.animeTitle
                    found.value = found.value + (title to "")
                    val url = lookup(title, anime = row.episodeNumber > 0) ?: return@forEach
                    found.value = found.value + (title to url)
                    rows.filter { it.animeTitle == title && it.posterUrl == null }.forEach { downloadRepository.setPoster(it.episodeId, url) }
                }
            }
        }
    }

    private suspend fun lookup(title: String, anime: Boolean): String? {
        (history.getAll() as? Result.Success)?.data?.firstOrNull { it.animeTitle.equals(title, true) && it.posterUrl != null }?.let { return it.posterUrl }
        (favorites.getAll() as? Result.Success)?.data?.firstOrNull { it.animeTitle.equals(title, true) && it.posterUrl != null }?.let { return it.posterUrl }
        if (anime) return null
        return (movies.search(title) as? Result.Success)?.data?.firstOrNull { it.posterUrl != null }?.posterUrl
    }

    fun deleteDownload(episodeId: Long) {
        viewModelScope.launch { downloadRepository.deleteDownload(episodeId) }
    }

    fun cancelDownload(episodeId: Long) {
        viewModelScope.launch { downloadRepository.cancelDownload(episodeId) }
    }
}
