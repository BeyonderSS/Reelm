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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject

private const val START_TIMEOUT_MS = 40_000L
/** data is arriving (this much buffered) but no frame after [DECODE_TIMEOUT_MS]: the decoder, not the network */
private const val DECODE_TIMEOUT_MS = 12_000L
private const val DECODE_BUFFERED_MS = 5_000L
/** no first frame yet: move to a fast-starting source of the same height (the slow one stays listed and comes back last) */
private const val SLOW_SWITCH_MS = 6_000L
/** next-episode prefetch this close to the end when the credits aren't known (inside the 4 min stream cache) */
private const val NEXT_PREFETCH_MS = 3 * 60_000L
/** a provider's stand-in clip (NetMirror's "Too Many Requests") instead of the title: its fault, recorded as a failure */
private const val ERROR_PLACEHOLDER = 1_000_001 // PlaybackException custom range (≥ 1 000 000)
/** 4K step-up: when auto-pick wants [STEP_TO], start at [STEP_FROM] (fast, decodes everywhere) and move up once the higher one is buffered */
private const val STEP_TO = 2160
private const val STEP_FROM = 1080
/** after the first frame: a Play-on-TV handoff's seek lands first */
private const val STEP_SETTLE_MS = 2_000L
/** the background player starts this far ahead of the playing one (4K HLS took 4.4–6.1 s to start); doubles on each miss */
private const val STEP_AHEAD_MS = 8_000L
private const val STEP_GIVE_UP_MS = 30_000L
/** a resume point past this is worth a seekable source */
private const val RESUME_MIN_MS = 30_000L
/** watch party: other copies a joiner tries for the room's cut before settling */
private const val CUT_TRIES = 3
private const val NO_SKIP = "This source can't skip ahead — starting from the beginning"

/** how long auto-pick waits for the title's country (dub-safe pick) before ranking without it */
private const val ORIGIN_WAIT_MS = 600L
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

/** This stream as a watch-party `source` descriptor: joiners try the same copy first (cuts differ between copies). [durationMs] once it plays. */
fun partySource(s: MediaStream, durationMs: Long? = null) =
    com.anistream.feature.party.sourceOf(SourceRank.provider(s), s.qualityLabel, android.net.Uri.parse(s.url).host, s.label(), durationMs, s.codec)

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
    private val skipTimes: com.anistream.domain.repository.MediaSkipTimesRepository,
    private val party: com.anistream.feature.party.PartyClient
) : ViewModel() {

    private val lowEnd = LowEnd.isOn(context)
    private fun ordered(r: PlaybackRequest) = if (lowEnd) r.copy(streams = lowEndOrder(r.streams)) else r

    private val appContext = context
    private fun pinKey(r: PlaybackRequest) = r.historyKey ?: r.mediaId ?: r.title
    private fun prefLang(r: PlaybackRequest) = AudioLanguage.preferred.value?.takeIf { l -> r.streams.any { l in it.languages } }
    /** the title's country (MovieBox/Cinemeta detail) for [SourceRank.origin]; null until known */
    private var country: String? = null
    private fun origin(r: PlaybackRequest) = SourceRank.origin(country, r.streams)
    /** Connection-aware auto-pick (honouring [pin]) among the preferred language's streams: decides the height only. */
    private fun autoHeight(r: PlaybackRequest, pin: Int?): Int {
        val lang = prefLang(r)
        return r.streams[bestOf(r, r.streams.indices.filter { lang == null || lang in r.streams[it].languages }, pin)].height
    }

    /** Auto-pick: the height from [autoHeight], then the most reliable source of it. */
    private fun pick(r: PlaybackRequest): Int {
        stepTo = 0
        if (r.isLive || r.streams.size < 2) { choose(r, r.streams[0].height); return 0 }
        com.anistream.core.account.HandoffHint.pickSource(r.streams.map { it.label() })?.let { choose(r, r.streams[it].height); return it } // Play on TV: the phone's source
        // watch party: the copy the room plays first (if it fails, the usual fallback runs and the overlay says it's a different copy)
        party.room.value?.takeIf { com.anistream.feature.party.samePayload(it.payload, com.anistream.feature.party.mediaPayload(r)) }?.source
            ?.let { com.anistream.feature.party.matchSource(it, r.streams.map { s -> partySource(s) }) }
            ?.takeIf { canPlay(r.streams[it]) && !undecodable(r.streams[it]) } // one this device can't decode: find the same cut instead (wrongCut)
            ?.let { choose(r, r.streams[it].height); return it }
        val pin = QualityPick.pinned(appContext, pinKey(r))
        val want = autoHeight(r, pin)
        // 4K starts slowly (and some never do): start at 1080p and step up in the background (see stepUp)
        val up = pin == null && want >= STEP_TO && canStepUp(r)
        val i = choose(r, if (up) STEP_FROM else want)
        if (up && r.streams[i].height < want) stepTo = want
        android.util.Log.d("Player", "auto: wants ${want}p, starts ${r.streams[i].height}p${if (stepTo > 0) ", steps up to ${stepTo}p" else ""}")
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
    /** left for a faster source before their first frame: still working, so tried again once everything else has been */
    private val slowLeft = mutableSetOf<String>()
    /** the source the viewer picked by hand: never left for being slow */
    private var picked: String? = null
    /** watch-party joiner: other copies tried for the room's cut */
    private var cutTries = 0

    /**
     * Watch-party joiner on another copy than the room's: when its length is more than 3 s off the room's (another cut),
     * try the next source, up to [CUT_TRIES] times. The copy left stays in the fallback list, as a slow starter does.
     * ponytail: probes by playing, since sources don't list their length; settles on the last copy tried.
     */
    private fun wrongCut(dur: Long): Boolean {
        val s = _uiState.value
        val r = s.request ?: return false
        val stream = s.stream ?: return false
        if (startedAt == 0L || dur <= 0 || picked != null || cutTries >= CUT_TRIES) return false
        val want = party.room.value?.takeIf { com.anistream.feature.party.samePayload(it.payload, com.anistream.feature.party.mediaPayload(r)) }?.source ?: return false
        val roomMs = com.anistream.feature.party.ms(want["durationMs"]) ?: return false
        if (com.anistream.feature.party.sameSource(want, partySource(stream)) || com.anistream.feature.party.sameCut(want, partySource(stream, dur))) return false
        tried += stream.url; slowLeft += stream.url
        val next = nextUntried(r) ?: return false.also { tried -= stream.url; slowLeft -= stream.url }
        cutTries++
        android.util.Log.d("Player", "party: ${stream.label()} runs ${dur / 1000}s, the room's cut ${roomMs / 1000}s: trying ${r.streams[next].label()}")
        play(next, player.currentPosition)
        return true
    }
    private fun isTried(i: Int) = _uiState.value.request?.streams?.getOrNull(i)?.url in tried
    private fun order(r: PlaybackRequest, height: Int) =
        SourceRank.fallbackOrder(r.streams, prefLang(r), height, SourceRank.load(appContext), System.currentTimeMillis(), canPlay, undecodable, origin(r), pendingResume != null || player.currentPosition > RESUME_MIN_MS,
            com.anistream.domain.model.Mirrors.of(r.mediaId)?.let(com.anistream.domain.model.Mirrors::source))
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
        stopStepUp()
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
        ?.let { SourceRank.fallbackOrder(it.streams, null, Int.MAX_VALUE, SourceRank.load(appContext), System.currentTimeMillis(), canPlay, undecodable, origin(it)) }.orEmpty()

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
        stopStepUp()
        val i = choose(r, autoHeight(r, QualityPick.pinned(appContext, pinKey(r))))
        play(i, if (s.isLive) null else player.currentPosition)
        _uiState.update { it.copy(message = "Switched to ${r.streams[i].label()}") }
    }

    private val _uiState = MutableStateFlow(MediaPlayerUiState(request = NowPlaying.request?.let(::ordered), showAllSources = SourceRank.showAll(context)))
    val uiState: StateFlow<MediaPlayerUiState> = _uiState.asStateFlow()

    private var pendingResume: Float? = null
    private var switchedAt = 0L
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

    private val swap = SwapPlayer(buildPlayer(context))
    /** what the screens, remote and party drive; the ExoPlayer behind it changes when the 4K step-up lands */
    val player: Player = swap.player.apply {
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        val s = _uiState.value
                        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
                        // before isLoading clears, so no progress is saved from it
                        if (startedAt > 0 && !s.isLive && SourceRank.isPlaceholder(dur, expectedMin, s.request?.season == 0))
                            return handleError(PlaybackException("Provider served a placeholder clip (${dur / 60_000} min, expected ${expectedMin ?: "a movie"})", null, ERROR_PLACEHOLDER))
                        if (wrongCut(dur)) return
                        _uiState.update { it.copy(isLoading = false) }
                        if (startedAt > 0) {
                            track("play")
                            track("source_used", "ttff" to System.currentTimeMillis() - startedAt)
                            android.util.Log.d("Player", "first frame ${System.currentTimeMillis() - startedAt} ms after load: ${_uiState.value.stream?.label()}")
                            if (!_uiState.value.isLive) SourceRank.record(appContext, _uiState.value.stream, ok = true, ttff = System.currentTimeMillis() - startedAt)
                            startedAt = 0
                            stepUp()
                        }
                        applyResume()
                        if (switchedAt > RESUME_MIN_MS && !player.isCurrentMediaItemSeekable) _uiState.update { it.copy(message = NO_SKIP) }
                        switchedAt = 0
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
            val detail = request.mediaId?.takeIf { !request.isLive }?.let { id -> viewModelScope.async { movies.detail(id).getOrNull()?.country.also { country = it } } }
            viewModelScope.launch {
                // kids: deep links, party joins and history can all land here, so check the title again
                val blocked = KidsPolicy.active && if (request.isLive) !KidsPolicy.channel(request.subtitle)
                    else request.mediaId?.let { movies.detail(it).exceptionOrNull() is KidsBlockedException } == true
                if (blocked) return@launch _uiState.update { it.copy(request = null, isLoading = false, error = KidsPolicy.BLOCKED) }
                recordStart(request)
                withTimeoutOrNull(ORIGIN_WAIT_MS) { detail?.await() } // dub-safe pick knows the title's country (usually cached by the detail page)
                android.util.Log.d("Player", "sources: ${request.streams.size} ${request.streams.groupingBy { "${SourceRank.provider(it)}:${it.height}" }.eachCount()}")
                // watch party joiner: give the first player up to 15 s to tell the room which copy it plays (web does the same)
                party.room.value?.takeIf { it.source == null && it.members.size > 1 && com.anistream.feature.party.samePayload(it.payload, com.anistream.feature.party.mediaPayload(request)) }
                    ?.let { withTimeoutOrNull(12_000) { party.room.first { r -> r?.source != null || r == null } } }
                val first = pick(request)
                play(first, null)
                // a mirror title: say where it plays from (its mirror, or the source that stood in for it)
                com.anistream.domain.model.Mirrors.of(request.mediaId)?.let { p ->
                    val s = request.streams[first]
                    val m = com.anistream.domain.model.Mirrors.source(p)
                    _uiState.update { it.copy(message = if (s.sourceName == m) "Playing from the $m" else "Playing from ${s.label()} instead of the $m") }
                }
            }
            loadSkips(request)
            // slow networks: sources that answered after playback started are appended (indices stay valid for fallback)
            if (!request.isLive) viewModelScope.launch { NowPlaying.late.collect(::mergeLate) }
            viewModelScope.launch {
                while (true) {
                    delay(10_000)
                    if (player.isPlaying) { saveProgress(); prefetchNext() }
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
        android.util.Log.d("Player", "sources: ${next.streams.size} (+${fresh.size} late) ${next.streams.groupingBy { "${SourceRank.provider(it)}:${it.height}" }.eachCount()}")
        val ranOut = s.error?.startsWith("Playback failed") == true // every earlier source failed: try the newcomers
        _uiState.update { it.copy(request = next, error = if (ranOut) null else it.error, isLoading = ranOut || it.isLoading) }
        if (ranOut) nextUntried(next)?.let { play(it, player.currentPosition.takeIf { p -> p > 0 }) }
    }

    private fun play(index: Int, startMs: Long?) {
        val request = _uiState.value.request ?: return
        val stream = request.streams.getOrNull(index) ?: return
        stepJob?.cancel() // a source change mid step-up: the new one gets its own after its first frame
        val source = mediaSource(request, stream)
        // live: no position = start at the live edge
        startedAt = System.currentTimeMillis()
        switchedAt = startMs ?: 0 // a source switch mid-play: a source that can't seek restarts at 0:00 (see STATE_READY)
        if (request.isLive || startMs == null) swap.exo.setMediaSource(source) else swap.exo.setMediaSource(source, startMs)
        _uiState.update { it.copy(streamIndex = index, isLoading = true, error = null, ended = false) }
        // subtitles: the file's own tracks (MKV/MP4/HLS) and the sideloaded ones are all Media3 text tracks; the selector
        // turns on one in the viewer's language (or English) and the Subtitles menu lists every one (see textOptions)
        // audio: a multi-audio file starts in the viewer's language, else the title's own (not the "Dual Audio" release's default Hindi)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredTextLanguages(*listOf(Locale.getDefault().language, "en").distinct().toTypedArray())
            .setPreferredAudioLanguage(AudioLanguage.preferred.value ?: origin(request).lang)
            .build()
        player.prepare()
        player.playWhenReady = true
        watchStart(request, index, stream)
    }

    private fun mediaSource(request: PlaybackRequest, stream: MediaStream): androidx.media3.exoplayer.source.MediaSource {
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
        return (if (request.isLive || inferMimeType(stream)?.startsWith("application/") == true) DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(appContext, http))
            else progressiveSources(appContext, http)).setLoadErrorHandlingPolicy(FailFast4xx).createMediaSource(item) // hosts that ignore Range: see NoRange.kt
    }

    /**
     * Some files load forever without an error (Google CDN MKVs whose index ExoPlayer can't reach): after 40 s
     * without a first frame, fall back as if it had failed. startedAt is cleared on the first READY.
     */
    private fun watchStart(request: PlaybackRequest, index: Int, stream: MediaStream) {
        startWatch?.cancel()
        if (!request.isLive) startWatch = viewModelScope.launch {
            fun waiting() = _uiState.value.request.let { r ->
                startedAt > 0 && _uiState.value.streamIndex == index && !isTried(index) && r != null && order(r, target).any { it != index && !isTried(it) }
            }
            delay(SLOW_SWITCH_MS)
            // a mirror title's own mirror (HLS, ~6 s to a first frame) is the viewer's choice too: only an error or the timeouts below leave it
            val chosen = stream.url == picked || com.anistream.domain.model.Mirrors.of(request.mediaId)?.let(com.anistream.domain.model.Mirrors::source) == stream.sourceName
            if (waiting() && !chosen && player.totalBufferedDuration < DECODE_BUFFERED_MS) _uiState.value.request?.let { r ->
                val stats = SourceRank.load(appContext)
                order(r, target).firstOrNull { val o = r.streams[it]
                    // never trade a source this device surely decodes for one it may not
                    it != index && !isTried(it) && o.height == stream.height && !SourceRank.slowStart(o, stats) && !undecodable(o) && (canPlay(o) || !canPlay(stream)) &&
                        SourceRank.keepsAudio(o, stream, origin(r)) }?.let { next -> // nor the title's audio for a dub
                    android.util.Log.d("Player", "slow start: no frame in ${SLOW_SWITCH_MS} ms from ${stream.label()}, trying ${r.streams[next].label()}")
                    SourceRank.record(appContext, stream, ttff = SLOW_SWITCH_MS) // teaches slowStart about this host, without a failure
                    tried += stream.url
                    slowLeft += stream.url
                    play(next, player.currentPosition)
                    return@launch
                }
            }
            // the Fire TV Stick's MTK decoder takes 10-bit HEVC, buffers it and never outputs a frame
            delay(DECODE_TIMEOUT_MS - SLOW_SWITCH_MS)
            if (waiting() && player.totalBufferedDuration >= DECODE_BUFFERED_MS) return@launch handleError(PlaybackException(
                "No first frame in ${DECODE_TIMEOUT_MS / 1000} s with ${player.totalBufferedDuration / 1000} s buffered", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            delay(START_TIMEOUT_MS - DECODE_TIMEOUT_MS)
            if (waiting()) handleError(PlaybackException("No first frame in ${START_TIMEOUT_MS / 1000} s", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        }
    }

    /** the height auto-pick wanted while a [STEP_FROM] source plays first; 0 = no step-up (hand-picked, done, or given up) */
    private var stepTo = 0
    private var stepJob: kotlinx.coroutines.Job? = null

    private fun stopStepUp() { stepTo = 0; stepJob?.cancel() }

    /** Never on low-end devices (can't decode 4K), metered/Data Saver networks, downloads or live. */
    private fun canStepUp(r: PlaybackRequest): Boolean = !lowEnd && !r.isLive && r.streams.any { it.url.startsWith("http") } && // a download is its one local file
        (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.isActiveNetworkMetered != true

    /**
     * 4K step-up, after the first frame of the [STEP_FROM] start: a second, muted ExoPlayer (no surface) loads the best
     * [stepTo] source a few seconds ahead of the playing position; when the playing one reaches that point it takes over
     * (surface, listeners, volume, speed, audio and subtitle language) and the old one is released. Gives up silently
     * after [STEP_GIVE_UP_MS], on any error, or when the source can't seek; the quality picker still lists every height.
     */
    private fun stepUp() {
        val s = _uiState.value
        val r = s.request ?: return
        val cur = s.stream ?: return
        if (stepTo == 0 || cur.height >= stepTo || stepJob?.isActive == true) return
        val from = s.streamIndex
        val o = origin(r)
        val ci = order(r, stepTo).firstOrNull { val c = r.streams[it]
            c.height == stepTo && c.url.startsWith("http") && !isTried(it) && !undecodable(c) && canPlay(c) && SourceRank.keepsAudio(c, cur, o) }
            ?: return run { android.util.Log.d("Player", "step-up: no ${stepTo}p source this device decodes in the same audio"); stepTo = 0 }
        val c = r.streams[ci]
        stepJob = viewModelScope.launch {
            delay(STEP_SETTLE_MS)
            // a remote handoff still landing, or the network turned metered: stay
            if (com.anistream.core.account.HandoffHint.positionMs != null || !canStepUp(r)) return@launch run { stepTo = 0 }
            val t0 = System.currentTimeMillis()
            val next = buildPlayer(appContext)
            try {
                val old = swap.exo
                next.volume = 0f
                next.trackSelectionParameters = carried(old)
                var ahead = STEP_AHEAD_MS
                var at = old.currentPosition + ahead
                next.setMediaSource(mediaSource(r, c), at)
                next.prepare()
                val ready = withTimeoutOrNull(STEP_GIVE_UP_MS) {
                    while (true) {
                        next.playerError?.let { throw it }
                        val lead = at - old.currentPosition
                        if (next.playbackState == Player.STATE_READY) {
                            if (!next.isCurrentMediaItemSeekable) throw IllegalStateException("not seekable")
                            if (old.isPlaying && lead <= 30) break
                        }
                        // the playing one passed the point (slow load) or was seeked away: try further ahead
                        if (lead < -200 || lead > ahead + 5_000) { ahead = minOf(ahead * 2, 30_000); at = old.currentPosition + ahead; next.seekTo(at) }
                        delay(if (lead > 1_000) 100 else 10)
                    }
                }
                if (ready == null) throw IllegalStateException("not ready in ${STEP_GIVE_UP_MS / 1000} s")
                val dur = next.duration.takeIf { it != C.TIME_UNSET } ?: 0
                if (SourceRank.isPlaceholder(dur, expectedMin, r.season == 0)) throw IllegalStateException("placeholder clip")
                val f = next.videoFormat
                if (f != null && SourceRank.isMain10(f.codecs, f.sampleMimeType, f.colorInfo?.colorTransfer.let { it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG }) && !LowEnd.hasHevcMain10)
                    throw IllegalStateException("10-bit")
                val oldPos = old.currentPosition
                next.setPlaybackParameters(old.playbackParameters)
                next.volume = old.volume
                next.playWhenReady = old.playWhenReady
                swap.swap(next).release()
                stepTo = 0
                target = c.height
                android.util.Log.d("Player", "step-up: ${cur.height}p → ${c.label()} after ${System.currentTimeMillis() - t0} ms, at ${next.currentPosition} ms (old at $oldPos ms)")
                _uiState.update { it.copy(streamIndex = ci, autoIndex = if (it.autoIndex == from) ci else it.autoIndex, message = "Switched to ${if (c.height >= 2160) "4K" else "${c.height}p"}") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                next.release(); throw e
            } catch (e: Exception) {
                next.release()
                stepTo = 0
                android.util.Log.d("Player", "step-up to ${c.label()} given up after ${System.currentTimeMillis() - t0} ms: ${e.message}")
            }
        }
    }

    /** The playing instance's settings for the next one: its audio and subtitle language (or subtitles off), without per-file overrides. */
    private fun carried(old: ExoPlayer): androidx.media3.common.TrackSelectionParameters {
        fun lang(type: Int) = old.currentTracks.groups.firstOrNull { it.type == type && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let { g.getTrackFormat(it).language } }
        return old.trackSelectionParameters.buildUpon().clearOverrides()
            .apply { lang(C.TRACK_TYPE_AUDIO)?.let { setPreferredAudioLanguage(it) } }
            .apply { lang(C.TRACK_TYPE_TEXT)?.let { setPreferredTextLanguage(it) } }
            .build()
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
        s.stream?.url?.let { tried += it; slowLeft -= it }
        if (nextUntried(request) == null) { tried -= slowLeft; slowLeft.clear() } // the slow starters that were left behind still play
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
            picked = it.url
            stopStepUp()
            choose(r, it.height) // the fallback chain continues from its height
            _uiState.update { u -> u.copy(quality = it.height) }
        } }
        play(index, if (_uiState.value.isLive) null else player.currentPosition)
    }

    fun retry() = tried.clear().also { slowLeft.clear(); stopStepUp() }.let { play(_uiState.value.streamIndex, if (_uiState.value.isLive) null else player.currentPosition) }

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
        val at = (fraction * duration).toLong()
        if (!player.isCurrentMediaItemSeekable) { if (at > RESUME_MIN_MS) _uiState.update { it.copy(message = NO_SKIP) }; return }
        player.seekTo(at)
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
                    tried.clear(); slowLeft.clear()
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

    private var prefetchedNext = ""

    /** At the credits (or the last [NEXT_PREFETCH_MS]), look the next episode up so the countdown's Play finds it ready. */
    private fun prefetchNext() {
        val s = _uiState.value
        val r = s.request ?: return
        val id = r.mediaId ?: return
        val dur = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
        if (!s.hasNextEpisode || s.isLoading) return
        val credits = s.skips.firstOrNull { it.isCredits }?.startMs
        if ((credits == null || player.currentPosition < credits) && dur - player.currentPosition > NEXT_PREFETCH_MS) return
        val key = NowPlaying.lateKey(id, r.season, r.episode + 1)
        if (key == prefetchedNext) return
        prefetchedNext = key
        movies.prefetch(id, r.season, r.episode + 1)
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
        stepJob?.cancel() // leaving the screen: no second player loading in the background
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
