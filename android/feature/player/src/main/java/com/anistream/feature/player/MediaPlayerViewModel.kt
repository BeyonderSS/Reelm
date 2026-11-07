package com.anistream.feature.player

import com.anistream.core.common.LowEnd
import android.content.Context
import android.net.Uri
import com.anistream.core.account.Telemetry
import androidx.annotation.OptIn
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
import com.anistream.domain.AudioLanguage
import com.anistream.domain.KidsBlockedException
import com.anistream.domain.KidsPolicy
import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.PlaybackRequest
import com.anistream.domain.model.WatchHistory
import com.anistream.domain.usecase.canPlayLowEnd
import com.anistream.domain.usecase.canPlayPhone
import com.anistream.domain.usecase.pickStream
import com.anistream.domain.repository.DownloadRepository
import com.anistream.domain.repository.MoviesRepository
import com.anistream.domain.repository.LateStreams
import com.anistream.domain.repository.NowPlaying
import com.anistream.domain.model.localStream
import com.anistream.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

data class MediaPlayerUiState(
    val request: PlaybackRequest? = null,
    val streamIndex: Int = 0,
    /** the stream the auto-pick chose, and why ("Auto" / "Pinned 1080p"); null when none */
    val autoIndex: Int = -1,
    val autoNote: String = "",
    /** index into the current stream's subtitles; -1 = off */
    val subtitleIndex: Int = -1,
    val isLoading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    /** played to the end; the screen shows the next-episode countdown or the finished card */
    val ended: Boolean = false,
    val downloadStarted: Boolean = false,
    /** IMDb id resolved for the web-player fallback; the screen navigates and calls [MediaPlayerViewModel.webPlayerOpened] */
    val webImdbId: String? = null
) {
    /** Movies/episodes only: live TV has no IMDb id. */
    val canWebPlay: Boolean get() = request?.let { it.mediaId != null && !it.isLive } == true
    val stream: MediaStream? get() = request?.streams?.getOrNull(streamIndex)
    val isLive: Boolean get() = request?.isLive == true
    val hasNextEpisode: Boolean
        get() = request?.let { it.mediaId != null && it.season > 0 && it.episode > 0 && it.seasonEpisodeCount > it.episode } == true
}

/** "1080p · HEVC · 2.1 GB · Hindi · English" (no source name). */
internal fun MediaStream.detailLabel(): String = (listOfNotNull(
    qualityLabel.takeIf { it.isNotBlank() },
    codec?.takeIf { it.isNotBlank() }?.uppercase(),
    sizeBytes?.takeIf { it > 0 }?.let { formatBytes(it) }
) + languages.map(AudioLanguage::name)).joinToString(" · ")

/** "MovieBox · 1080p · HEVC · 2.1 GB" */
internal fun MediaStream.label(): String = listOf(sourceName, detailLabel()).filter { it.isNotBlank() }.joinToString(" · ")

private fun formatBytes(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else "${mb.toLong()} MB"
}

/** Explicit MIME wins; otherwise sniff the URL path (.m3u8 → HLS, .mpd → DASH), else let ExoPlayer treat it as progressive. */
internal fun inferMimeType(stream: MediaStream): String? {
    stream.mimeType?.let { return it }
    val path = Uri.parse(stream.url).path.orEmpty().lowercase()
    return when {
        path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        else -> null
    }
}

internal fun subtitleMime(mime: String): String = when {
    mime.contains("vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
    mime.contains("ass", ignoreCase = true) || mime.contains("ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
    else -> MimeTypes.APPLICATION_SUBRIP
}

/**
 * Generic player for Movies & TV, Stremio addon streams and Live TV. Reads [NowPlaying.request] once.
 * Owns the ExoPlayer so playback survives rotation.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class MediaPlayerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val movies: MoviesRepository,
    private val history: WatchHistoryRepository,
    private val sync: com.anistream.core.account.HistorySync,
    private val downloads: DownloadRepository
) : ViewModel() {

    private val lowEnd = LowEnd.isOn(context)
    private fun ordered(r: PlaybackRequest) = if (lowEnd) r.copy(streams = lowEndOrder(r.streams)) else r

    private val appContext = context
    private fun pinKey(r: PlaybackRequest) = r.historyKey ?: r.mediaId ?: r.title
    /** Auto-pick; among the streams in the preferred [AudioLanguage] when any are. */
    private fun pick(r: PlaybackRequest): Int {
        if (r.isLive || r.streams.size < 2) return 0
        val pin = QualityPick.pinned(appContext, pinKey(r))
        val lang = AudioLanguage.preferred.value?.takeIf { l -> r.streams.any { l in it.languages } }
        val i = bestOf(r, r.streams.indices.filter { lang == null || lang in r.streams[it].languages }, pin)
        val note = if (pin != null) "Pinned ${pin}p" else "Auto"
        _uiState.update { it.copy(autoIndex = i, autoNote = lang?.let { l -> "$note · ${AudioLanguage.name(l)}" } ?: note) }
        return i
    }

    private fun bestOf(r: PlaybackRequest, indices: List<Int>, pin: Int?): Int =
        indices[pickStream(indices.map { r.streams[it] }, QualityPick.bandwidth(appContext), pin, if (lowEnd) ::canPlayLowEnd else ::canPlayPhone)]

    /**
     * "Audio language" pick (null = Any), saved as the device preference. The playing file's own track is used when it
     * has that language; otherwise the best source in it takes over at the same position.
     */
    fun selectAudioLanguage(code: String?) {
        AudioLanguage.set(code)
        val lang = AudioLanguage.preferred.value
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).setPreferredAudioLanguage(lang).build()
        val s = _uiState.value
        val r = s.request ?: return
        if (lang == null || lang in trackLanguages(player.currentTracks) || lang in s.stream?.languages.orEmpty()) return
        val indices = r.streams.indices.filter { lang in r.streams[it].languages }
        if (indices.isEmpty()) return _uiState.update { it.copy(message = "No ${AudioLanguage.name(lang)} source for this title") }
        val i = bestOf(r, indices, QualityPick.pinned(appContext, pinKey(r)))
        play(i, if (s.isLive) null else player.currentPosition)
        _uiState.update { it.copy(message = "Switched to ${r.streams[i].label()}") }
    }

    private val _uiState = MutableStateFlow(MediaPlayerUiState(request = NowPlaying.request?.let(::ordered)))
    val uiState: StateFlow<MediaPlayerUiState> = _uiState.asStateFlow()

    private var pendingResume: Float? = null
    /** stream index that already auto-switched away after an error, so a failure chain only walks forward */
    private var fellBackFrom = -1
    /** when the current stream was handed to the player; 0 once it rendered (time to first frame) */
    private var startedAt = 0L
    private var ticks = 0

    /** Admin telemetry with what's playing; codec matters for the HEVC / Dolby failure counts. */
    private fun track(kind: String, vararg extra: Pair<String, Any?>) {
        val s = _uiState.value
        val r = s.request ?: return
        val stream = r.streams.getOrNull(s.streamIndex)
        Telemetry.track(kind, "title" to r.title, "key" to r.historyKey, "kind" to if (r.isLive) "live" else if (r.season > 0) "series" else "movie",
            "episode" to r.episode.takeIf { it > 0 }, "source" to stream?.sourceName, "quality" to stream?.qualityLabel, "codec" to stream?.codec,
            "host" to stream?.url?.let { Uri.parse(it).host }, *extra)
    }

    val player: ExoPlayer = buildPlayer(context).apply {
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        _uiState.update { it.copy(isLoading = false) }
                        if (startedAt > 0) {
                            track("play")
                            track("source_used", "ttff" to System.currentTimeMillis() - startedAt)
                            startedAt = 0
                        }
                        applyResume()
                    }
                    Player.STATE_ENDED -> if (!_uiState.value.isLive) {
                        persist(1f, push = true)
                        _uiState.update { it.copy(ended = true) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
    }

    init {
        val request = _uiState.value.request
        if (request == null || request.streams.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, error = "Nothing to play") }
        } else {
            viewModelScope.launch {
                // kids: deep links, party joins and history can all land here, so check the title again
                val blocked = KidsPolicy.active && if (request.isLive) !KidsPolicy.channel(request.subtitle)
                    else request.mediaId?.let { movies.detail(it).exceptionOrNull() is KidsBlockedException } == true
                if (blocked) return@launch _uiState.update { it.copy(request = null, isLoading = false, error = KidsPolicy.BLOCKED) }
                recordStart(request)
                play(pick(request), null)
            }
            // slow networks: sources that answered after playback started are appended (indices stay valid for fallback)
            if (!request.isLive) viewModelScope.launch { NowPlaying.late.collect(::mergeLate) }
            viewModelScope.launch {
                while (true) {
                    delay(10_000)
                    if (player.isPlaying) saveProgress()
                    if (player.isPlaying && ++ticks % 6 == 0) track("heartbeat", "position" to player.currentPosition / 1000, "duration" to player.duration / 1000)
                }
            }
        }
    }

    private fun mergeLate(ev: LateStreams) {
        val s = _uiState.value
        val r = s.request ?: return
        val id = r.mediaId ?: return
        if (ev.key != NowPlaying.lateKey(id, r.season, r.episode)) return
        val fresh = ev.streams.filter { m -> r.streams.none { it.url == m.url } }
        if (fresh.isEmpty()) return
        val next = r.copy(streams = r.streams + if (lowEnd) lowEndOrder(fresh) else fresh)
        NowPlaying.request = next
        val ranOut = s.error?.startsWith("Playback failed") == true // every earlier source failed: try the newcomers
        _uiState.update { it.copy(request = next, error = if (ranOut) null else it.error, isLoading = ranOut || it.isLoading) }
        if (ranOut) play(r.streams.size, player.currentPosition.takeIf { it > 0 })
    }

    private fun play(index: Int, startMs: Long?) {
        val request = _uiState.value.request ?: return
        val stream = request.streams.getOrNull(index) ?: return
        val userAgent = stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: DEFAULT_USER_AGENT
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(stream.headers.filterKeys { !it.equals("User-Agent", true) })
        val subtitleIndex = stream.subtitles.indexOfFirst { it.languageCode.startsWith("en", true) || it.label.startsWith("English", true) }
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .apply { inferMimeType(stream)?.let { setMimeType(it) } }
            .setSubtitleConfigurations(stream.subtitles.mapIndexed { i, t ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(t.url))
                    .setId("sub$i")
                    .setMimeType(subtitleMime(t.mimeType))
                    .setLanguage(t.languageCode)
                    .setLabel(t.label)
                    .build()
            })
            .apply { if (request.isLive) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build()) }
            .build()
        // DefaultDataSource reads file:// too, so downloads play here with the same controls
        val source = DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(appContext, http)).createMediaSource(item)
        // live: no position = start at the live edge
        startedAt = System.currentTimeMillis()
        if (request.isLive || startMs == null) player.setMediaSource(source) else player.setMediaSource(source, startMs)
        _uiState.update { it.copy(streamIndex = index, isLoading = true, error = null, ended = false) }
        applySubtitle(stream, subtitleIndex)
        player.prepare()
        player.playWhenReady = true
    }

    private fun handleError(error: PlaybackException) {
        track("play_error", "error" to "${error.errorCodeName}: ${error.cause?.message ?: error.message}")
        val s = _uiState.value
        val request = s.request ?: return
        if (s.isLive && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        val next = s.streamIndex + 1
        if (fellBackFrom != s.streamIndex && next < request.streams.size) {
            fellBackFrom = s.streamIndex
            play(next, if (s.isLive) null else player.currentPosition)
            _uiState.update { it.copy(message = "Switched to ${request.streams[next].label()}") }
            return
        }
        _uiState.update { it.copy(isLoading = false, error = "Playback failed (${error.errorCodeName})") }
    }

    /** Source sheet pick. VOD keeps its position. */
    fun selectStream(index: Int) {
        if (index == _uiState.value.streamIndex && _uiState.value.error == null) return
        _uiState.value.request?.let { r -> if (!r.isLive) r.streams.getOrNull(index)?.let { QualityPick.pin(appContext, pinKey(r), it.height) } }
        play(index, if (_uiState.value.isLive) null else player.currentPosition)
    }

    fun retry() = play(_uiState.value.streamIndex, if (_uiState.value.isLive) null else player.currentPosition)

    /** -1 = off */
    fun selectSubtitle(index: Int) {
        val stream = _uiState.value.stream ?: return
        applySubtitle(stream, index)
    }

    // ponytail: picks by language, so two tracks with the same language code select the first; use a TrackSelectionOverride if that bites
    private fun applySubtitle(stream: MediaStream, index: Int) {
        val track = stream.subtitles.getOrNull(index)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)
            .setPreferredTextLanguage(track?.languageCode)
            .build()
        _uiState.update { it.copy(subtitleIndex = if (track == null) -1 else index) }
    }

    /** Upsert the history row so Continue Watching sees it; resume when it's the same season/episode. */
    private suspend fun recordStart(request: PlaybackRequest) {
        val key = request.historyKey
        if (request.isLive || key == null) return
        val existing = history.getBySlug(key).getOrNull()
        val progress = existing?.watchProgress ?: 0f
        pendingResume = if (
            existing != null && existing.season == request.season &&
            existing.lastEpisodeWatched == request.episode && progress in 0.02f..0.95f
        ) progress else null
        history.save(historyRow(request, pendingResume ?: 0f))
    }

    private fun historyRow(r: PlaybackRequest, fraction: Float, durationMs: Long = 0) = WatchHistory(
        animeSlug = r.historyKey ?: "",
        animeTitle = r.title,
        posterUrl = r.posterUrl,
        totalEpisodes = r.seasonEpisodeCount,
        lastEpisodeWatched = r.episode,
        watchProgress = fraction,
        kind = "media",
        season = r.season,
        mediaId = r.mediaId,
        subtitle = r.subtitle,
        durationMs = durationMs
    )

    private fun applyResume() {
        val fraction = pendingResume ?: return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        pendingResume = null
        player.seekTo((fraction * duration).toLong())
    }

    /** Countdown finished / "Play now": resolve the next episode's streams and play them. */
    fun nextEpisode() {
        val s = _uiState.value
        val r = s.request ?: return
        val mediaId = r.mediaId ?: return
        if (!s.hasNextEpisode) return
        saveProgress()
        val episode = r.episode + 1
        _uiState.update { it.copy(ended = false, isLoading = true, error = null) }
        viewModelScope.launch {
            val local = downloads.getAll().getOrNull().orEmpty()
                .firstOrNull { it.animeTitle == r.title && it.subtitle == "S${r.season} · E$episode" }?.localStream()
            when (val res = if (local != null) Result.success(listOf(local)) else movies.streams(mediaId, r.season, episode)) {
                is Result.Success -> {
                    if (res.data.isEmpty()) {
                        _uiState.update { it.copy(isLoading = false, error = "No streams for episode $episode") }
                        return@launch
                    }
                    val next = ordered(r.copy(streams = res.data, episode = episode, subtitle = "S${r.season} · E$episode"))
                    NowPlaying.request = next
                    fellBackFrom = -1
                    _uiState.update { it.copy(request = next, downloadStarted = false) }
                    recordStart(next)
                    play(pick(next), null)
                }
                is Result.Error -> _uiState.update { it.copy(isLoading = false, error = res.message ?: "Couldn't load episode $episode") }
                is Result.Loading -> {}
            }
        }
    }

    fun dismissEnd() = _uiState.update { it.copy(ended = false) }

    fun download() {
        val s = _uiState.value
        val r = s.request ?: return
        val key = r.historyKey ?: r.mediaId ?: r.title
        val stream = s.stream?.takeIf { it.isDirectFile } ?: r.streams.firstOrNull { it.isDirectFile }
        if (stream == null) {
            _uiState.update { it.copy(message = "This source can't be downloaded") }
            return
        }
        viewModelScope.launch {
            // keyed per episode so S1E1 and S1E2 don't overwrite each other
            val downloadKey = if (r.episode > 0) "$key:s${r.season}e${r.episode}" else key
            val res = downloads.startMediaDownload(stream, downloadKey, r.title, r.subtitle, r.posterUrl ?: r.historyKey?.let { history.getBySlug(it).getOrNull()?.posterUrl })
            val msg = if (res is Result.Error) "Download failed: ${res.message}" else "Downloading ${stream.label()}"
            _uiState.update { it.copy(message = msg, downloadStarted = res !is Result.Error) }
        }
    }

    /** Last resort: hand the title to the web player. */
    fun openWebPlayer() {
        val mediaId = _uiState.value.request?.mediaId ?: return
        player.pause()
        viewModelScope.launch {
            val tt = movies.imdbId(mediaId) ?: mediaId.takeIf { it.startsWith("tmdb:") }
            _uiState.update {
                if (tt == null) it.copy(message = "The web player can't find this title") else it.copy(webImdbId = tt)
            }
        }
    }

    fun webPlayerOpened() = _uiState.update { it.copy(webImdbId = null) }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    fun onStop() {
        player.pause()
        saveProgress(push = true)
    }

    private fun saveProgress(push: Boolean = false) {
        val duration = player.duration
        if (_uiState.value.isLoading || duration == C.TIME_UNSET || duration <= 0) return
        persist((player.currentPosition.toFloat() / duration).coerceIn(0f, 1f), push, duration)
    }

    // outlives viewModelScope so the final save in onCleared still lands
    // push = leaving/finishing: sync right after the write instead of waiting for the 5 s debounce a dying process never reaches
    private fun persist(fraction: Float, push: Boolean = false, durationMs: Long = 0) {
        val r = _uiState.value.request ?: return
        if (r.isLive || r.historyKey == null) return
        CoroutineScope(Dispatchers.IO).launch { history.save(historyRow(r, fraction, durationMs)); if (push) runCatching { sync.push() } }
    }

    override fun onCleared() {
        saveProgress(push = true)
        player.release()
    }
}
