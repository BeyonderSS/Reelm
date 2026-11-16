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
import androidx.media3.common.Tracks
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

private const val START_TIMEOUT_MS = 40_000L
/** data is arriving (this much buffered) but no frame after [DECODE_TIMEOUT_MS]: the decoder, not the network */
private const val DECODE_TIMEOUT_MS = 12_000L
private const val DECODE_BUFFERED_MS = 5_000L
/** a provider's stand-in clip (NetMirror's "Too Many Requests") instead of the title: its fault, recorded as a failure */
private const val ERROR_PLACEHOLDER = 1_000_001 // PlaybackException custom range (≥ 1 000 000)
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

data class MediaPlayerUiState(
    val request: PlaybackRequest? = null,
    val streamIndex: Int = 0,
    /** the stream the auto-pick chose, and why ("Auto" / "Pinned 1080p"); null when none */
    val autoIndex: Int = -1,
    val autoNote: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    /** played to the end; the screen shows the next-episode countdown or the finished card */
    val ended: Boolean = false,
    val downloadStarted: Boolean = false,
    /** IMDb id resolved for the web-player fallback; the screen navigates and calls [MediaPlayerViewModel.webPlayerOpened] */
    val webImdbId: String? = null,
    /** TheIntroDB intro / recap / credits / preview for this title; empty = unknown (no skip button) */
    val skips: List<com.anistream.domain.model.SkipInterval> = emptyList(),
    /** quality chip: the height asked for, 0 = Auto */
    val quality: Int = 0,
    /** "1080p didn't load — playing 720p" while that fallback plays */
    val note: String = "",
    /** "Show all sources" (advanced, per device): the full list instead of the quality chips' current source */
    val showAllSources: Boolean = false
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

/** Media3 MIME for a sideloaded subtitle: the source's type ("text/vtt", "vtt", "ass" …), else the URL's extension, else SRT. */
internal fun subtitleMime(mime: String, url: String = ""): String {
    val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    val m = mime.lowercase()
    return when {
        "vtt" in m || ext == "vtt" -> MimeTypes.TEXT_VTT
        "ttml" in m || "dfxp" in m || ext == "ttml" || ext == "dfxp" -> MimeTypes.APPLICATION_TTML
        "ssa" in m || m == "ass" || m.endsWith("/ass") || m.endsWith("-ass") || ext == "ass" || ext == "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

/** ISO code for a subtitle language that sources sometimes give by name ("English" → "en"); codes pass through. */
internal fun subtitleLanguage(lang: String): String {
    val l = lang.trim()
    if (l.length <= 3) return l.lowercase()
    val name = l.substringBefore(' ').substringBefore('(').lowercase()
    return Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(Locale.ENGLISH).lowercase() == name }?.language ?: l
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
    private val downloads: DownloadRepository,
    private val skipTimes: com.anistream.domain.repository.MediaSkipTimesRepository
) : ViewModel() {

    private val lowEnd = LowEnd.isOn(context)
    private fun ordered(r: PlaybackRequest) = if (lowEnd) r.copy(streams = lowEndOrder(r.streams)) else r

    private val appContext = context
    private fun pinKey(r: PlaybackRequest) = r.historyKey ?: r.mediaId ?: r.title
    private fun prefLang(r: PlaybackRequest) = AudioLanguage.preferred.value?.takeIf { l -> r.streams.any { l in it.languages } }
    /** Connection-aware auto-pick (honouring [pin]) among the preferred language's streams: decides the height only. */
    private fun autoHeight(r: PlaybackRequest, pin: Int?): Int {
        val lang = prefLang(r)
        return r.streams[bestOf(r, r.streams.indices.filter { lang == null || lang in r.streams[it].languages }, pin)].height
    }

    /** Auto-pick: the height from [autoHeight], then the most reliable source of it. */
    private fun pick(r: PlaybackRequest): Int {
        if (r.isLive || r.streams.size < 2) { choose(r, r.streams[0].height); return 0 }
        com.anistream.core.account.HandoffHint.pickSource(r.streams.map { it.label() })?.let { choose(r, r.streams[it].height); return it } // Play on TV: the phone's source
        val pin = QualityPick.pinned(appContext, pinKey(r))
        val i = choose(r, autoHeight(r, pin))
        val note = if (pin != null) "Pinned ${pin}p" else "Auto"
        _uiState.update { it.copy(autoIndex = i, quality = if (pin != null) r.streams[i].height else 0, autoNote = prefLang(r)?.let { l -> "$note · ${AudioLanguage.name(l)}" } ?: note) }
        return i
    }

    // without HEVC Main10 (emulators, older phones) HEVC goes last within its height: unlabelled web HEVC is mostly 10-bit
    private val canPlay: (MediaStream) -> Boolean = if (lowEnd) ::canPlayLowEnd else { s ->
        canPlayPhone(s) && (LowEnd.hasHevcMain10 || listOfNotNull(s.codec, s.qualityLabel).joinToString(" ").let { !LowEnd.isHevc(it) && !LowEnd.isHighBitDepth(it) })
    }
    /** 10-bit HEVC on a device without Main10 (Fire TV Stick 2, emulators): after every decodable source of any height */
    private val undecodable: (MediaStream) -> Boolean = { SourceRank.likelyUndecodable(it, LowEnd.hasHevcMain10) }
    private val device = if (LowEnd.eligible) "TV" else "device"
    /**
     * Fallback target height, and the urls of the sources that failed this episode. Keyed by url, not index, and kept across
     * picks and late merges, so a chain never loops back to a source that already failed (a Fire TV once alternated two dead
     * 1080p sources for 5 minutes); only Retry or an explicit source pick gives a failed source another go.
     */
    private var target = 0
    private val tried = mutableSetOf<String>()
    private fun isTried(i: Int) = _uiState.value.request?.streams?.getOrNull(i)?.url in tried
    private fun order(r: PlaybackRequest, height: Int) =
        SourceRank.fallbackOrder(r.streams, prefLang(r), height, SourceRank.load(appContext), System.currentTimeMillis(), canPlay, undecodable)
    /** best-ranked untried source of [height] (or below); fallbacks continue from there */
    private fun choose(r: PlaybackRequest, height: Int): Int {
        target = height
        _uiState.update { it.copy(note = "") }
        return order(r, height).let { SourceRank.nextUntried(r.streams, it, tried) ?: it.first() }
    }
    /** next untried source in fallback order (late sources count too) */
    private fun nextUntried(r: PlaybackRequest) = SourceRank.nextUntried(r.streams, order(r, target), tried)

    /** Quality chip (0 = Auto): remembered like a picked source, plays the best-ranked source of that height. */
    fun selectQuality(height: Int) {
        val s = _uiState.value
        val r = s.request?.takeIf { !it.isLive && it.streams.isNotEmpty() } ?: return
        QualityPick.pin(appContext, pinKey(r), height)
        val i = choose(r, if (height > 0) height else autoHeight(r, null))
        val h = r.streams[i].height
        // every source of that height is 10-bit HEVC: say so and play the best one this device decodes
        val note = if (height <= 0 || h == height) "" else if (height in unsupportedHeights()) "${height}p here is 10-bit HEVC, which this $device can't play — playing ${h}p"
            else "${height}p didn't load — playing ${h}p" // every source of it already failed
        _uiState.update { it.copy(quality = height, note = note, message = note.ifEmpty { null }) }
        if (i != s.streamIndex || s.error != null) play(i, player.currentPosition)
    }

    /** Quality chips, (height, count) best first, for the preferred language; none for live. */
    fun qualities(): List<Pair<Int, Int>> = _uiState.value.request?.takeIf { !it.isLive }?.let { SourceRank.qualities(it.streams, prefLang(it)) }.orEmpty()

    /** Chip heights whose every source is likely undecodable here ("Not supported on this TV"). */
    fun unsupportedHeights(): Set<Int> = _uiState.value.request?.takeIf { !it.isLive }?.let { SourceRank.undecodableHeights(it.streams, prefLang(it), undecodable) }.orEmpty()

    /** "Show all sources": every source, best height first, ranked within it. */
    fun allSourcesOrder(): List<Int> = _uiState.value.request
        ?.let { SourceRank.fallbackOrder(it.streams, null, Int.MAX_VALUE, SourceRank.load(appContext), System.currentTimeMillis(), canPlay, undecodable) }.orEmpty()

    fun setShowAllSources(on: Boolean) {
        SourceRank.setShowAll(appContext, on)
        _uiState.update { it.copy(showAllSources = on) }
    }

    private fun bestOf(r: PlaybackRequest, indices: List<Int>, pin: Int?): Int =
        indices[pickStream(indices.map { r.streams[it] }, QualityPick.bandwidth(appContext), pin) { (if (lowEnd) canPlayLowEnd(it) else canPlayPhone(it)) && !undecodable(it) }]

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
        val i = choose(r, autoHeight(r, QualityPick.pinned(appContext, pinKey(r))))
        play(i, if (s.isLive) null else player.currentPosition)
        _uiState.update { it.copy(message = "Switched to ${r.streams[i].label()}") }
    }

    private val _uiState = MutableStateFlow(MediaPlayerUiState(request = NowPlaying.request?.let(::ordered), showAllSources = SourceRank.showAll(context)))
    val uiState: StateFlow<MediaPlayerUiState> = _uiState.asStateFlow()

    private var pendingResume: Float? = null
    /** 10-bit video formats skipped as undecodable here (see onTracksChanged) */
    private val skipped = mutableSetOf<androidx.media3.common.Format>()
    /** the title's runtime in minutes (an earlier play's duration, else TMDB/Cinemeta) for the placeholder-clip check */
    private var expectedMin: Int? = null
    /** a stall right after a seek is the seek's own buffering, not the host's */
    private var lastSeekAt = 0L
    private var stalledIndex = -1
    /** when the current stream was handed to the player; 0 once it rendered (time to first frame) */
    private var startedAt = 0L
    private var startWatch: kotlinx.coroutines.Job? = null
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
                        val s = _uiState.value
                        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
                        // before isLoading clears, so no progress is saved from it
                        if (startedAt > 0 && !s.isLive && SourceRank.isPlaceholder(dur, expectedMin, s.request?.season == 0))
                            return handleError(PlaybackException("Provider served a placeholder clip (${dur / 60_000} min, expected ${expectedMin ?: "a movie"})", null, ERROR_PLACEHOLDER))
                        _uiState.update { it.copy(isLoading = false) }
                        if (startedAt > 0) {
                            track("play")
                            track("source_used", "ttff" to System.currentTimeMillis() - startedAt)
                            if (!_uiState.value.isLive) SourceRank.record(appContext, _uiState.value.stream, ok = true, ttff = System.currentTimeMillis() - startedAt)
                            startedAt = 0
                        }
                        applyResume()
                    }
                    Player.STATE_BUFFERING -> _uiState.value.let { s ->
                        if (startedAt == 0L && !s.isLive && stalledIndex != s.streamIndex && System.currentTimeMillis() - lastSeekAt > 3000) {
                            stalledIndex = s.streamIndex
                            SourceRank.record(appContext, s.stream, stall = true)
                        }
                    }
                    Player.STATE_ENDED -> if (!_uiState.value.isLive) {
                        persist(1f, push = true)
                        _uiState.update { it.copy(ended = true) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // the skipped 10-bit file's own decoder error can land after the next source started: not that source's fault
                val f = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat
                if (f != null && skipped.any { it.codecs == f.codecs && it.sampleMimeType == f.sampleMimeType && it.width == f.width }) return
                handleError(error)
            }

            // a 10-bit video track this device can't output (no Main10, or the renderer says unsupported): next source now, not after the watchdog
            override fun onTracksChanged(tracks: Tracks) {
                val index = _uiState.value.streamIndex
                if (_uiState.value.isLive || startedAt == 0L || isTried(index)) return
                val g = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return
                val t = (0 until g.length).first(g::isTrackSelected)
                val f = g.getTrackFormat(t)
                val hdr = f.colorInfo?.colorTransfer.let { it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG }
                if (SourceRank.isMain10(f.codecs, f.sampleMimeType, hdr) && (!g.isTrackSupported(t) || (lowEnd && !LowEnd.hasHevcMain10)))
                    viewModelScope.launch {
                        skipped += f
                        if (_uiState.value.streamIndex != index || isTried(index)) return@launch
                        player.stop() // releases the stuck decoder here, where a failure to disable it is only logged, not blamed on the next source
                        handleError(PlaybackException(
                            "${f.codecs ?: f.sampleMimeType} (10-bit) can't be decoded here", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES))
                    }
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) lastSeekAt = System.currentTimeMillis()
            }
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
            loadSkips(request)
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
        if (ranOut) nextUntried(next)?.let { play(it, player.currentPosition.takeIf { p -> p > 0 }) }
    }

    private fun play(index: Int, startMs: Long?) {
        val request = _uiState.value.request ?: return
        val stream = request.streams.getOrNull(index) ?: return
        val userAgent = stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: DEFAULT_USER_AGENT
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(stream.headers.filterKeys { !it.equals("User-Agent", true) })
        val english = stream.subtitles.indexOfFirst { it.languageCode.startsWith("en", true) || it.label.startsWith("English", true) }
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .apply { inferMimeType(stream)?.let { setMimeType(it) } }
            .setSubtitleConfigurations(stream.subtitles.mapIndexed { i, t ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(t.url))
                    .setId("sub$i")
                    .setMimeType(subtitleMime(t.mimeType, t.url))
                    .setLanguage(subtitleLanguage(t.languageCode))
                    .setLabel(t.label)
                    .setSelectionFlags(if (i == english) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            })
            .apply { if (request.isLive) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build()) }
            .build()
        // DefaultDataSource reads file:// too, so downloads play here with the same controls
        val source = (if (request.isLive || inferMimeType(stream)?.startsWith("application/") == true) DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(appContext, http))
            else progressiveSources(appContext, http)).setLoadErrorHandlingPolicy(FailFast4xx).createMediaSource(item) // hosts that ignore Range: see NoRange.kt
        // live: no position = start at the live edge
        startedAt = System.currentTimeMillis()
        if (request.isLive || startMs == null) player.setMediaSource(source) else player.setMediaSource(source, startMs)
        _uiState.update { it.copy(streamIndex = index, isLoading = true, error = null, ended = false) }
        // subtitles: the file's own tracks (MKV/MP4/HLS) and the sideloaded ones are all Media3 text tracks; the selector
        // turns on one in the viewer's language (or English) and the Subtitles menu lists every one (see textOptions)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredTextLanguages(*listOf(Locale.getDefault().language, "en").distinct().toTypedArray())
            .build()
        player.prepare()
        player.playWhenReady = true
        // Some files load forever without an error (Google CDN MKVs whose index ExoPlayer can't reach): after 40 s
        // without a first frame, fall back as if it had failed. startedAt is cleared on the first READY.
        startWatch?.cancel()
        if (!request.isLive) startWatch = viewModelScope.launch {
            fun waiting() = _uiState.value.request.let { r ->
                startedAt > 0 && _uiState.value.streamIndex == index && !isTried(index) && r != null && order(r, target).any { it != index && !isTried(it) }
            }
            // the Fire TV Stick's MTK decoder takes 10-bit HEVC, buffers it and never outputs a frame
            delay(DECODE_TIMEOUT_MS)
            if (waiting() && player.totalBufferedDuration >= DECODE_BUFFERED_MS) return@launch handleError(PlaybackException(
                "No first frame in ${DECODE_TIMEOUT_MS / 1000} s with ${player.totalBufferedDuration / 1000} s buffered", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            delay(START_TIMEOUT_MS - DECODE_TIMEOUT_MS)
            if (waiting()) handleError(PlaybackException("No first frame in ${START_TIMEOUT_MS / 1000} s", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        }
    }

    private fun handleError(error: PlaybackException) {
        val decoder = error.errorCode / 1000 == 4 // a decoder this device lacks: not the provider's fault
        track("play_error", "error" to "${error.errorCodeName}: ${error.cause?.message ?: error.message}",
            "detail" to if (decoder) "decoder" else if (error.errorCode == ERROR_PLACEHOLDER) "placeholder" else if (error.errorCode / 1000 == 2) "network" else "other")
        val s = _uiState.value
        val request = s.request ?: return
        if (s.isLive && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        if (!s.isLive && !decoder) SourceRank.record(appContext, s.stream, fail = true)
        s.stream?.url?.let { tried += it }
        val next = nextUntried(request)
        if (next != null) {
            val h = request.streams[next].height
            val note = if (target > 0 && h < target && (s.stream?.height ?: 0) >= target) "${target}p ${if (decoder) "won't decode on this $device" else "didn't load"} — playing ${if (h > 0) "${h}p" else "another source"}" else null
            play(next, if (s.isLive) null else player.currentPosition)
            val switched = (if (decoder) "This $device couldn't decode this file — playing " else if (error.errorCode == ERROR_PLACEHOLDER)
                "The provider served a placeholder clip — playing " else "Switched to ") + request.streams[next].label()
            _uiState.update { it.copy(message = note ?: switched, note = note ?: it.note) }
            return
        }
        _uiState.update { it.copy(isLoading = false, error = "Playback failed (${error.errorCodeName}): every source was tried") }
    }

    /** Source sheet pick. VOD keeps its position. */
    fun selectStream(index: Int) {
        if (index == _uiState.value.streamIndex && _uiState.value.error == null) return
        _uiState.value.request?.let { r -> r.streams.getOrNull(index)?.let {
            if (!r.isLive) QualityPick.pin(appContext, pinKey(r), it.height)
            tried -= it.url // an explicit pick gets another go
            choose(r, it.height) // the fallback chain continues from its height
            _uiState.update { u -> u.copy(quality = it.height) }
        } }
        play(index, if (_uiState.value.isLive) null else player.currentPosition)
    }

    fun retry() = tried.clear().let { play(_uiState.value.streamIndex, if (_uiState.value.isLive) null else player.currentPosition) }

    /** Upsert the history row so Continue Watching sees it; resume when it's the same season/episode. */
    private suspend fun recordStart(request: PlaybackRequest) {
        val key = request.historyKey
        expectedMin = null
        if (!request.isLive) request.mediaId?.let { id -> viewModelScope.launch {
            val m = SourceRank.runtimeMinutes(movies.detail(id).getOrNull()?.runtime)
            if (expectedMin == null && _uiState.value.request?.episode == request.episode) expectedMin = m
        } }
        if (request.isLive || key == null) return
        val existing = history.getBySlug(key).getOrNull()
        existing?.takeIf { it.season == request.season && it.lastEpisodeWatched == request.episode && it.durationMs >= 20 * 60_000L }
            ?.let { expectedMin = (it.durationMs / 60_000).toInt() }
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
        val r = _uiState.value.request ?: return
        if (_uiState.value.hasNextEpisode) playEpisode(r.season, r.episode + 1)
    }

    /** Another episode of the playing series (the remote's episode picker); another season looks up that season's length. */
    fun playEpisode(season: Int, episode: Int) {
        val r = _uiState.value.request ?: return
        val mediaId = r.mediaId ?: return
        if (r.isLive || r.season <= 0 || season <= 0 || episode <= 0 || (season == r.season && episode == r.episode)) return
        saveProgress()
        _uiState.update { it.copy(ended = false, isLoading = true, error = null) }
        viewModelScope.launch {
            val count = if (season == r.season) r.seasonEpisodeCount
                else (movies.detail(mediaId) as? Result.Success)?.data?.seasons?.firstOrNull { it.number == season }?.episodeCount ?: 0
            val local = downloads.getAll().getOrNull().orEmpty()
                .firstOrNull { it.animeTitle == r.title && it.subtitle == "S$season · E$episode" }?.localStream()
            when (val res = if (local != null) Result.success(listOf(local)) else movies.streams(mediaId, season, episode)) {
                is Result.Success -> {
                    if (res.data.isEmpty()) {
                        _uiState.update { it.copy(isLoading = false, error = "No streams for episode $episode") }
                        return@launch
                    }
                    val next = ordered(r.copy(streams = res.data, season = season, episode = episode, subtitle = "S$season · E$episode", seasonEpisodeCount = count))
                    NowPlaying.request = next
                    tried.clear()
                    _uiState.update { it.copy(request = next, downloadStarted = false, skips = emptyList()) }
                    loadSkips(next)
                    recordStart(next)
                    play(pick(next), null)
                }
                is Result.Error -> _uiState.update { it.copy(isLoading = false, error = res.message ?: "Couldn't load episode $episode") }
                is Result.Loading -> {}
            }
        }
    }

    fun dismissEnd() = _uiState.update { it.copy(ended = false) }

    private fun loadSkips(r: PlaybackRequest) {
        val id = r.mediaId?.takeIf { !r.isLive } ?: return
        viewModelScope.launch {
            val tt = movies.imdbId(id) ?: return@launch
            val skips = skipTimes.skipTimes(tt, r.season, r.episode)
            _uiState.update { if (it.request?.season == r.season && it.request.episode == r.episode) it.copy(skips = skips) else it }
        }
    }

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
        // reaching the end credits finishes the title, however long they run
        val credits = _uiState.value.skips.firstOrNull { it.isCredits }?.startMs
        val pos = player.currentPosition
        persist(if (credits != null && pos >= credits) 1f else (pos.toFloat() / duration).coerceIn(0f, 1f), push, duration)
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
