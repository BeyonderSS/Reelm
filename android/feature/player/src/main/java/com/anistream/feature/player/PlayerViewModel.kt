package com.anistream.feature.player

import com.anistream.core.common.LowEnd
import android.content.Context
import android.net.Uri
import com.anistream.core.account.Telemetry
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.anistream.core.common.Result
import com.anistream.domain.KidsBlockedException
import com.anistream.domain.KidsPolicy
import com.anistream.domain.model.AnimeDetail
import com.anistream.domain.model.Episode
import com.anistream.domain.model.SkipInterval
import com.anistream.domain.model.StreamSource
import com.anistream.domain.model.WatchHistory
import com.anistream.domain.usecase.pickSource
import com.anistream.domain.repository.AnidbRepository
import com.anistream.domain.repository.DownloadRepository
import com.anistream.domain.repository.SkipTimesRepository
import com.anistream.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

data class PlayerUiState(
    val isLoading: Boolean = true,
    val streamSource: StreamSource? = null,
    val availableQualities: List<StreamSource> = emptyList(),
    val selectedQuality: String = "best",
    /** "Auto" / "Pinned 1080p" on the entry the pick chose; "" after a manual pick */
    val autoNote: String = "",
    val autoQuality: String = "",
    val language: String = "sub",
    val error: String? = null,
    val animeSlug: String = "",
    val animeTitle: String = "",
    val episodeNumber: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val message: String? = null,
    val posterUrl: String? = null,
    val episodeNumbers: List<Int> = emptyList(),
    /** episodes below this number count as watched (from history) */
    val watchedBelow: Int = 0,
    /** current episode played to the end; the screen shows a countdown or end card */
    val ended: Boolean = false,
    val downloadStarted: Boolean = false,
    /** AniSkip intro/recap/credits for the current episode; empty = unknown (the fixed +85 s button stays) */
    val skips: List<SkipInterval> = emptyList()
)

/**
 * Owns the ExoPlayer so playback survives rotation and the screen stays a thin view.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val anidb: AnidbRepository,
    private val history: WatchHistoryRepository,
    private val sync: com.anistream.core.account.HistorySync,
    private val downloads: DownloadRepository,
    private val skipTimes: SkipTimesRepository
) : ViewModel() {

    private val animeSlug: String = savedStateHandle.get<String>("slug") ?: ""
    private val startEpisode: Int = when (val v = savedStateHandle.get<Any>("episode")) {
        is Int -> v
        is String -> v.toIntOrNull()
        else -> null
    } ?: 1

    private val _uiState = MutableStateFlow(
        PlayerUiState(animeSlug = animeSlug, animeTitle = animeSlug, episodeNumber = startEpisode)
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var episodes: List<Episode> = emptyList()
    private var detail: AnimeDetail? = null
    private var pendingResume: Float? = null
    private var loadJob: Job? = null
    /** when the current stream was handed to the player; 0 once it rendered (time to first frame) */
    private var startedAt = 0L

    private val lowEnd = LowEnd.isOn(context)
    private val appContext = context

    val player: ExoPlayer = buildPlayer(context).apply {
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (startedAt > 0) {
                            val s = _uiState.value
                            Telemetry.track("play", "title" to s.animeTitle, "kind" to "anime", "episode" to s.episodeNumber, "source" to "anime", "quality" to s.streamSource?.qualityLabel)
                            Telemetry.track("source_used", "source" to "anime", "host" to s.streamSource?.m3u8Url?.let { Uri.parse(it).host }, "ttff" to System.currentTimeMillis() - startedAt)
                            startedAt = 0
                        }
                        applyResume()
                    }
                    Player.STATE_ENDED -> {
                        persist(_uiState.value.episodeNumber, 1f, push = true)
                        _uiState.update { it.copy(ended = true) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _uiState.update { it.copy(error = "Playback failed (${error.errorCodeName})") }
                val s = _uiState.value
                Telemetry.track("play_error", "title" to s.animeTitle, "source" to "anime", "host" to s.streamSource?.m3u8Url?.let { Uri.parse(it).host },
                    "quality" to s.streamSource?.qualityLabel, "error" to "${error.errorCodeName}: ${error.cause?.message ?: error.message}")
            }
        })
    }

    private var ticks = 0

    init {
        load()
        viewModelScope.launch {
            while (true) {
                delay(10_000)
                if (player.isPlaying) saveProgress()
                if (player.isPlaying && ++ticks % 6 == 0) _uiState.value.let { s ->
                    Telemetry.track("heartbeat", "title" to s.animeTitle, "kind" to "anime", "episode" to s.episodeNumber, "source" to "anime",
                        "quality" to s.streamSource?.qualityLabel, "position" to player.currentPosition / 1000, "duration" to player.duration / 1000)
                }
            }
        }
    }

    /** keepPosition: same episode, different source (language switch / retry) */
    fun load(keepPosition: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val keepAt = if (keepPosition) player.currentPosition else null
            _uiState.update { it.copy(isLoading = true, error = null, ended = false) }

            if (episodes.isEmpty()) {
                val detailCall = async { anidb.getAnimeDetail(animeSlug) }
                when (val r = anidb.getEpisodes(animeSlug)) {
                    is Result.Success -> episodes = r.data.sortedBy { it.number }
                    is Result.Error -> return@launch fail(r.message ?: "Couldn't load episodes")
                    is Result.Loading -> {}
                }
                val detailResult = detailCall.await()
                if (detailResult.exceptionOrNull() is KidsBlockedException) return@launch fail(KidsPolicy.BLOCKED)
                val d = detailResult.getOrNull()
                detail = d
                _uiState.update {
                    it.copy(
                        animeTitle = d?.title?.takeIf { t -> t.isNotBlank() } ?: it.animeTitle,
                        posterUrl = d?.posterUrl,
                        episodeNumbers = episodes.map { e -> e.number }
                    )
                }
            }

            val state = _uiState.value
            val number = state.episodeNumber
            val episode = episodes.firstOrNull { it.number == number }
                ?: return@launch fail("Episode $number not found")
            val variants = when (val r = anidb.resolveStream(episode.id, state.language)) {
                is Result.Success -> r.data.sorted()
                is Result.Error -> emptyList<StreamSource>().also { if (state.language != "dub") return@launch fail(r.message ?: "Couldn't resolve stream") }
                is Result.Loading -> return@launch
            }
            if (variants.isEmpty()) {
                if (state.language != "dub") return@launch fail("No streams found")
                // dubs usually lag behind subs, so fall back instead of dead-ending on an error
                _uiState.update { it.copy(language = "sub", message = "Dub isn't out for episode $number yet, playing Sub") }
                loadJob = null
                return@launch load(keepPosition)
            }
            val pin = QualityPick.pinned(appContext, animeSlug)
            val stream = variants.firstOrNull { it.qualityLabel == state.selectedQuality && keepAt != null }
                ?: variants[pickSource(variants, QualityPick.bandwidth(appContext), pin) { !lowEnd || it.height <= LowEnd.maxHeight }]

            if (keepAt == null) recordStart(number)
            play(stream, keepAt)
            val malId = stream.malId
            if (malId != null && state.skips.isEmpty()) viewModelScope.launch {
                val skips = skipTimes.skipTimes(malId, number)
                _uiState.update { if (it.episodeNumber == number) it.copy(skips = skips) else it }
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    availableQualities = variants,
                    streamSource = stream,
                    selectedQuality = stream.qualityLabel,
                    autoNote = if (pin != null) "Pinned ${pin}p" else "Auto",
                    autoQuality = if (keepAt != null) it.autoQuality else stream.qualityLabel,
                    hasPrevious = episodes.any { e -> e.number < number },
                    hasNext = episodes.any { e -> e.number > number }
                )
            }
        }
    }

    private fun fail(message: String) {
        player.stop()
        _uiState.update { it.copy(isLoading = false, error = message) }
    }

    private fun play(stream: StreamSource, startMs: Long?) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        stream.referer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val subtitles = listOfNotNull(stream.subtitleUrl?.let {
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(it))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage("en")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        })
        val item = MediaItem.Builder()
            .setUri(stream.m3u8Url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setSubtitleConfigurations(subtitles)
            .build()
        startedAt = System.currentTimeMillis()
        player.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item), startMs ?: 0L)
        player.prepare()
        player.playWhenReady = true
    }

    /** Upsert the history row so Continue Watching sees it, and pick up a resume point for this episode. */
    private suspend fun recordStart(number: Int) {
        val existing = history.getBySlug(animeSlug).getOrNull()
        val progress = existing?.watchProgress ?: 0f
        val watched = existing?.lastEpisodeWatched ?: 0
        _uiState.update { it.copy(watchedBelow = maxOf(it.watchedBelow, watched)) }
        pendingResume = if (existing?.lastEpisodeWatched == number && progress in 0.02f..0.95f) progress else null
        val d = detail
        history.save(
            WatchHistory(
                animeSlug = animeSlug,
                animeTitle = _uiState.value.animeTitle,
                malId = d?.malId ?: existing?.malId,
                posterUrl = d?.posterUrl ?: existing?.posterUrl,
                totalEpisodes = d?.episodes ?: d?.subEpisodes ?: episodes.size,
                lastEpisodeWatched = number,
                watchProgress = pendingResume ?: 0f,
                isFavorite = existing?.isFavorite ?: false,
                anidbSlug = existing?.anidbSlug
            )
        )
    }

    private fun applyResume() {
        val fraction = pendingResume ?: return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        pendingResume = null
        player.seekTo((fraction * duration).toLong())
    }

    fun selectQuality(label: String) {
        val stream = _uiState.value.availableQualities.firstOrNull { it.qualityLabel == label } ?: return
        play(stream, player.currentPosition)
        QualityPick.pin(appContext, animeSlug, stream.height)
        _uiState.update { it.copy(selectedQuality = label, streamSource = stream, error = null) }
    }

    fun setLanguage(language: String) {
        if (language == _uiState.value.language) return
        _uiState.update { it.copy(language = language) }
        load(keepPosition = true)
    }

    fun retry() = load(keepPosition = true)

    fun nextEpisode() = goTo(episodes.firstOrNull { it.number > _uiState.value.episodeNumber })
    fun previousEpisode() = goTo(episodes.lastOrNull { it.number < _uiState.value.episodeNumber })
    fun playEpisode(number: Int) {
        if (number != _uiState.value.episodeNumber) goTo(episodes.firstOrNull { it.number == number })
    }

    /** Cancel on the "Next episode in N" overlay. */
    fun dismissEnd() = _uiState.update { it.copy(ended = false) }

    private fun goTo(episode: Episode?) {
        episode ?: return
        saveProgress()
        pendingResume = null
        _uiState.update { it.copy(episodeNumber = episode.number, ended = false, downloadStarted = false, skips = emptyList()) }
        load()
    }

    fun download() {
        val s = _uiState.value
        val stream = s.streamSource ?: return
        viewModelScope.launch {
            val r = downloads.startDownload(stream, animeSlug, s.animeTitle, s.episodeNumber, s.posterUrl ?: history.getBySlug(animeSlug).getOrNull()?.posterUrl)
            val msg = if (r is Result.Error) "Download failed: ${r.message}" else "Download started (${stream.qualityLabel})"
            _uiState.update { it.copy(message = msg, downloadStarted = r !is Result.Error) }
        }
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    fun onStop() {
        player.pause()
        saveProgress(push = true)
    }

    private fun saveProgress(push: Boolean = false) {
        val duration = player.duration
        if (_uiState.value.isLoading || duration == C.TIME_UNSET || duration <= 0) return
        persist(_uiState.value.episodeNumber, (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f), push, duration)
    }

    // outlives viewModelScope so the final save in onCleared still lands
    // push = leaving/finishing: sync right after the write instead of waiting for the 5 s debounce a dying process never reaches
    private fun persist(episode: Int, fraction: Float, push: Boolean = false, durationMs: Long = 0) {
        CoroutineScope(Dispatchers.IO).launch { history.updateProgress(animeSlug, episode, fraction, durationMs); if (push) runCatching { sync.push() } }
    }

    override fun onCleared() {
        saveProgress(push = true)
        player.release()
    }
}
