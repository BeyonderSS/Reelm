package com.anistream.feature.tv

import com.anistream.core.common.LowEnd
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.em
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.anistream.core.ui.theme.DisplayHeavy
import com.anistream.domain.model.MediaStream
import com.anistream.domain.AudioLanguage
import com.anistream.feature.player.audioLanguages
import com.anistream.feature.player.qualityChip
import com.anistream.core.media.GuideEp
import com.anistream.feature.player.SubPosition
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.text.format.DateFormat
import java.util.Date
import com.anistream.feature.player.CREDITS_NEXT_MS
import com.anistream.feature.player.UP_NEXT_MS
import com.anistream.feature.player.isCredits
import com.anistream.feature.player.rememberSkipFresh
import com.anistream.feature.player.CountdownRing
import com.anistream.feature.player.activeSkip
import com.anistream.feature.player.skipLabel
import com.anistream.domain.model.SkipInterval
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.anistream.feature.player.PlaybackSpeeds
import com.anistream.feature.player.Resize
import com.anistream.feature.player.SleepOptions
import com.anistream.feature.player.SubtitleDelays
import com.anistream.feature.player.SubtitleScales
import com.anistream.feature.player.VideoSurface
import com.anistream.feature.player.audioOptions
import com.anistream.feature.player.hasSubtitles
import com.anistream.feature.player.rememberPlayerTweaks
import com.anistream.feature.player.rememberTracks
import com.anistream.feature.player.selectAudio
import com.anistream.feature.player.sleepLabel
import com.anistream.feature.player.speedLabel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.feature.player.MediaPlayerViewModel
import com.anistream.feature.player.PlayerViewModel
import com.anistream.feature.party.PartyViewModel
import com.anistream.feature.party.animePayload
import com.anistream.feature.party.mediaPayload
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.Locale

private enum class Panel { NONE, SETTINGS, STRIP }

/** What distinguishes one source on its ticket stub. */
internal class SrcInfo(val name: String, val quality: String, val size: String?, val tags: List<String>, val flags: List<String>, val note: String? = null)

/** One option group in the ↑ settings panel. */
internal class SettingGroup<T>(val title: String, val options: List<T>, val label: (T) -> String, val selected: (T) -> Boolean, val pick: (T) -> Unit) {
    /** sources render as stubs instead of chips */
    var stub: ((T) -> SrcInfo?)? = null
    /** picking keeps the panel open (a toggle, not a choice of what to play) */
    var stay = false
    fun current(): String? = options.firstOrNull(selected)?.let(label)
}

/** What the player knows about the episode, for the pause marquee. All optional. */
internal class EpisodeMeta(
    /** amber line: "The Mentalist, season 5, episode 2" */
    val show: String? = null,
    val name: String? = null,
    val synopsis: String? = null,
    val runtimeMin: Int? = null,
    /** "2012-10-07" from TMDB / Kitsu */
    val airDate: String? = null,
    val nextName: String? = null,
    val nextStill: String? = null
)

private val TabNames = listOf("Watch", "Subtitles", "Picture", "Playback", "Together", "Device")
private fun tabOf(title: String) = when (title) {
    "Source", "Playing from", "Show all sources", "Quality", "Audio", "Audio language", "Audio track" -> 0
    "Subtitles", "Subtitle size", "Subtitle delay", "Position" -> 1
    "Picture", "Speed" -> 2
    "Sleep timer", "Auto-skip intro and recap" -> 3
    "Watch together" -> 4
    else -> 5
}

/** ↓ episode strip: numbers, current, and a picker. */
internal class EpisodeStrip(val numbers: List<Int>, val current: Int, val watchedBelow: Int, val pick: (Int) -> Unit)

/**
 * D-pad player chrome shared by the anime and media players (both ViewModels own their ExoPlayer).
 * OK play/pause · ←/→ seek ±10 s, accelerating while held · ↑ settings · ↓ episodes · Back hides controls first.
 * Controls auto-hide after 4 s of no input while playing.
 */
@OptIn(UnstableApi::class)
@Composable
private fun TvPlayerChrome(
    player: Player,
    title: String,
    subtitle: String?,
    isLive: Boolean,
    loading: Boolean,
    error: String?,
    message: String?,
    onMessageShown: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onExit: () -> Unit,
    settings: List<SettingGroup<*>>,
    strip: EpisodeStrip?,
    upNext: String?,
    ended: Boolean,
    onUpNext: () -> Unit,
    onCancelUpNext: () -> Unit,
    /** per-title key for remembered speed / picture / subtitle size */
    tweaksKey: String,
    onWebPlayer: (() -> Unit)? = null,
    /** art for the up-next card (poster) */
    upNextImage: String? = null,
    /** AniSkip / TheIntroDB intervals: a skip chip inside each; at credits with [upNext], a "Next episode" countdown */
    skips: List<SkipInterval> = emptyList(),
    /** offer the "Auto-skip intro and recap" setting */
    skipSetting: Boolean = false,
    /** what is playing, for "Watch together" and for telling the room when it changes; null = not shareable (live) */
    partyPayload: JsonObject? = null,
    meta: EpisodeMeta = EpisodeMeta(),
    /** candidate streams, for the "Audio language" choices */
    streams: List<MediaStream> = emptyList(),
    onAudioLanguage: ((String?) -> Unit)? = null
) {
    val party: PartyViewModel = hiltViewModel()
    var partyOpen by remember { mutableStateOf(false) }
    val partyFocus = remember { FocusRequester() }
    var visible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var poke by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    val rootFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val reduce = LocalReduceMotion.current
    val tweaks = rememberPlayerTweaks(tweaksKey)
    val tracks = rememberTracks(player)
    val introFocus = remember { FocusRequester() }
    var introFocused by remember { mutableStateOf(false) }
    var creditsDismissed by remember { mutableStateOf<SkipInterval?>(null) }
    var creditsFocused by remember { mutableStateOf(false) }
    var skipToast by remember { mutableStateOf<String?>(null) }
    val autoSkipped = remember(title, subtitle) { mutableSetOf<SkipInterval>() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    var lowEnd by remember { mutableStateOf(LowEnd.isOn(context)) }
    var paused by remember { mutableStateOf(false) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    val nextFocus = remember { FocusRequester() }
    val creditsFocus = remember { FocusRequester() }
    var nextFocused by remember { mutableStateOf(false) }
    val room by party.client.room.collectAsStateWithLifecycle()

    val audio = audioOptions(tracks)
    val subs = hasSubtitles(tracks)
    val languages = if (onAudioLanguage == null) emptyList() else audioLanguages(streams, tracks)
    val preferredLang by AudioLanguage.preferred.collectAsStateWithLifecycle()
    // the Watch tab reads audio language → quality → source
    val allSettings = listOf(
        SettingGroup("Audio language", if (languages.size > 1) listOf<String?>(null) + languages else emptyList(), { it?.let(AudioLanguage::name) ?: "Any" }, { it == preferredLang }) { onAudioLanguage?.invoke(it) }
    ) + settings + listOf(
        SettingGroup("Picture", Resize.entries, { it.label }, { it == tweaks.resize }, tweaks::updateResize),
        SettingGroup("Speed", if (isLive) emptyList() else PlaybackSpeeds, ::speedLabel, { it == tweaks.speed }) { tweaks.updateSpeed(player, it) },
        SettingGroup("Audio track", if (audio.size > 1) audio else emptyList(), { it.label }, { it.selected }) { selectAudio(player, it) },
        SettingGroup("Subtitle size", if (subs) SubtitleScales else emptyList(), { listOf("S", "M", "L", "XL").getOrElse(SubtitleScales.indexOf(it)) { "M" } }, { it == tweaks.subScale }, tweaks::updateSubScale),
        SettingGroup("Position", if (subs) SubPosition.entries else emptyList(), { it.label }, { it == tweaks.subPosition }, tweaks::updateSubPosition),
        SettingGroup("Subtitle delay", if (subs) SubtitleDelays else emptyList(), { if (it == 0L) "0" else "+${(it / 1000f).toString().removeSuffix(".0")} s" }, { it == tweaks.subDelayMs }) { tweaks.subDelayMs = it },
        SettingGroup("Sleep timer", SleepOptions, ::sleepLabel, { it == tweaks.sleep }, tweaks::updateSleep),
        SettingGroup("Auto-skip intro and recap", if (skipSetting) listOf(true, false) else emptyList(), { if (it) "On" else "Off" }, { it == tweaks.autoSkip }, tweaks::updateAutoSkip),
        SettingGroup("Watch together", if (partyPayload != null && party.client.enabled) listOf(true) else emptyList(), { "Start or manage a party" }, { room != null }) { partyOpen = true },
        SettingGroup("Low-end mode (applies on restart)", listOf(true, false), { if (it) "On" else "Off" }, { it == lowEnd }) { lowEnd = it; LowEnd.set(context, it) }
    )
    val hasSettings = allSettings.any { it.options.isNotEmpty() }
    val skipNow = if (!isLive && error == null && !ended) activeSkip(skips, position) else null
    val skipFresh = rememberSkipFresh(skipNow)
    val creditsCard = skipNow != null && skipNow.isCredits && upNext != null && creditsDismissed != skipNow && !loading &&
        panel == Panel.NONE && !partyOpen
    // the chip: 8 s, then only with the controls; credits without a next episode (movies, last episode) get none
    val chipAvailable = panel == Panel.NONE && skipNow != null && !creditsCard && !(skipNow.isCredits && upNext == null)
    val showIntro = chipAvailable && (skipFresh || visible || introFocused)
    var focusChip by remember { mutableStateOf(false) }
    val skipText = skipNow?.let { skipLabel(it.type) } ?: ""
    val upNextShown = ended && upNext != null
    // the paused marquee: not while seeking (the ramp pauses/buffers), live, ended, or with another panel up
    val marquee = paused && !isLive && !loading && error == null && !upNextShown && !partyOpen && panel != Panel.STRIP &&
        now - lastSeekAt > 1500
    val nextCard = marquee && strip != null && upNext != null && !ended
    LaunchedEffect(nextCard) { if (!nextCard && nextFocused) runCatching { rootFocus.requestFocus() } }

    fun show() { visible = true; poke++ }
    fun closePanel() { panel = Panel.NONE; poke++; runCatching { rootFocus.requestFocus() } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) onStop() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            playing = player.isPlaying
            paused = !player.playWhenReady && player.playbackState == Player.STATE_READY
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    LaunchedEffect(poke, panel, playing, partyOpen) {
        if (panel == Panel.NONE && playing && !partyOpen) { delay(4000); visible = false }
    }
    LaunchedEffect(panel) {
        if (panel == Panel.STRIP) { withFrameNanos { }; runCatching { panelFocus.requestFocus() } }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(partyOpen) { if (partyOpen) { withFrameNanos { }; runCatching { partyFocus.requestFocus() } } }
    LaunchedEffect(showIntro) { if (!showIntro && introFocused) runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(creditsCard) { if (!creditsCard && creditsFocused) runCatching { rootFocus.requestFocus() } }
    // ↓ while the chip was hidden: it comes back with the controls, then takes focus
    LaunchedEffect(focusChip, showIntro) { if (focusChip && showIntro) { withFrameNanos { }; runCatching { introFocus.requestFocus() }; focusChip = false } }
    LaunchedEffect(message) { if (message != null) { delay(3500); onMessageShown() } }
    // AniSkip auto-skip: intro/recap once per interval, with a short toast
    LaunchedEffect(skipNow, tweaks.autoSkip) {
        val s = skipNow ?: return@LaunchedEffect
        if (tweaks.autoSkip && s.isIntroOrRecap && autoSkipped.add(s)) {
            player.seekTo(s.endMs)
            position = s.endMs
            skipToast = if (s.type == "recap") "Skipped recap" else "Skipped intro"
        }
    }
    LaunchedEffect(skipToast) { if (skipToast != null) { delay(2000); skipToast = null } }

    BackHandler(enabled = upNextShown) { onCancelUpNext() }
    BackHandler(enabled = !upNextShown && (panel != Panel.NONE || visible) && error == null) {
        if (panel != Panel.NONE) closePanel() else visible = false
    }

    BackHandler(enabled = partyOpen && panel == Panel.NONE) { partyOpen = false; closePanel() }
    // Back on the credits card = "Watch credits"
    BackHandler(enabled = creditsCard && creditsFocused) { creditsDismissed = skipNow }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                // the up-next card's buttons take the D-pad
                if (panel != Panel.NONE || partyOpen || error != null || upNextShown) { poke++; return@onPreviewKeyEvent false }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Back must not re-show the controls: it did, and BackHandler then hid them, so Back toggled forever
                if (e.key == Key.Back || e.key == Key.Escape) return@onPreviewKeyEvent false
                // the credits card moves between its buttons with ←/→
                if (creditsFocused && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) return@onPreviewKeyEvent false
                // the skip-intro chip (reached with ↓) takes OK; ↑ backs out; anything else acts as usual
                val fromIntro = introFocused || nextFocused || creditsFocused
                if (fromIntro) {
                    when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> return@onPreviewKeyEvent false
                        Key.DirectionUp -> { runCatching { rootFocus.requestFocus() }; show(); return@onPreviewKeyEvent true }
                        else -> runCatching { rootFocus.requestFocus() }
                    }
                }
                // ponytail: linear ramp on key repeat (10 s → 60 s per step); tune here if it feels slow on long films
                val step = 10_000L * (1 + e.nativeKeyEvent.repeatCount / 5).coerceAtMost(6)
                fun seek(delta: Long) {
                    if (isLive) return
                    val max = if (duration > 0) duration else Long.MAX_VALUE
                    lastSeekAt = System.currentTimeMillis()
                    player.seekTo((player.currentPosition + delta).coerceIn(0L, max))
                    position = player.currentPosition
                }
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.Spacebar -> {
                        if (player.isPlaying) player.pause() else player.play()
                        playing = player.isPlaying
                    }
                    Key.MediaPlay -> player.play()
                    Key.MediaPause -> player.pause()
                    Key.DirectionLeft, Key.MediaRewind -> seek(-step)
                    Key.DirectionRight, Key.MediaFastForward -> seek(step)
                    Key.DirectionUp -> if (hasSettings) panel = Panel.SETTINGS
                    Key.DirectionDown -> when {
                        creditsCard && !fromIntro -> runCatching { creditsFocus.requestFocus() }
                        chipAvailable && !marquee && !fromIntro -> focusChip = true
                        nextCard && !fromIntro -> runCatching { nextFocus.requestFocus() }
                        strip != null && strip.numbers.isNotEmpty() -> panel = Panel.STRIP
                    }
                    else -> { show(); return@onPreviewKeyEvent false }
                }
                show()
                true
            }
            .focusRequester(rootFocus)
            .focusable()
    ) {
        VideoSurface(
            player = player,
            tweaks = tweaks,
            create = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    keepScreenOn = true
                    isFocusable = false
                }
            },
            modifier = Modifier.fillMaxSize(),
            // captions would run over the pause marquee's next-episode card
            showCaptions = !marquee
        )
        com.anistream.feature.party.PartyOverlay(player, Modifier.align(Alignment.TopEnd), payload = partyPayload)
        val cq = maxWidth / 100
        if (marquee) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xD106060A), 0.55f to Color(0x9906060A), 1f to Color(0xCC06060A))))
            PauseMarquee(
                cq, meta.show, meta.name ?: title, meta.synopsis,
                listOfNotNull(
                    (meta.runtimeMin ?: (duration / 60_000).toInt().takeIf { it > 0 })?.let { "$it min" },
                    airLabel(meta.airDate),
                    ((duration - position) / 60_000).toInt().takeIf { duration > 0 && it > 0 }?.let { "$it min left" }
                ),
                Modifier.align(Alignment.TopStart).padding(start = cq * 5, top = cq * 6)
            )
            room?.members?.takeIf { it.isNotEmpty() }?.let { m ->
                PartyChip(cq, m.map { it.name }, Modifier.align(Alignment.BottomStart).padding(start = cq * 5, bottom = cq * 16))
            }
            if (nextCard) NextCard(cq, upNext.orEmpty(), meta.nextName, meta.nextStill ?: upNextImage, onUpNext, nextFocus,
                { nextFocused = it }, Modifier.align(Alignment.BottomStart).padding(start = cq * 5, bottom = cq * 8.5f))
        }

        if (loading && error == null) {
            Text("Loading…", style = TvType.title, modifier = Modifier.align(Alignment.Center).background(Bg.badge, Radius.pill).padding(horizontal = 24.dp, vertical = 10.dp))
        }

        AnimatedVisibility(
            visible = visible || panel != Panel.NONE || partyOpen || error != null,
            enter = if (reduce) EnterTransition.None else fadeIn(),
            exit = if (reduce) ExitTransition.None else fadeOut()
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!marquee) Column(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Bg.base.copy(alpha = 0.85f), Color.Transparent)))
                        .padding(horizontal = Overscan, vertical = 32.dp)
                ) {
                    Text(title, style = TvType.headline, maxLines = 1)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, style = TvType.body)
                }
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Bg.base.copy(alpha = 0.92f))))
                        .padding(horizontal = Overscan, vertical = 32.dp)
                ) {
                    when (panel) {
                        Panel.SETTINGS -> Unit
                        Panel.STRIP -> if (strip != null) StripPanel(strip, panelFocus) { closePanel() }
                        Panel.NONE -> Unit
                    }
                    if (partyOpen && partyPayload != null) TvPartyPanel(partyPayload, partyFocus, onClose = { partyOpen = false; closePanel() })
                    if (panel == Panel.STRIP) Spacer(Modifier.height(20.dp))
                    // scrub line: play state, the bar with its knob, "14:12 / 42:53"
                    val timeStyle = TvType.body.copy(color = Color(0xFFDAD6E4), fontSize = (cq.value * 1.4f).sp, lineHeight = (cq.value * 2f).sp, fontFeatureSettings = "tnum")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(cq * 1.6f)) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = Ink.high, modifier = Modifier.size(cq * 2.2f))
                        if (isLive) {
                            Text("Live", style = TvType.body.copy(color = Accent.onLive), modifier = Modifier.background(Accent.live, Radius.pill).padding(horizontal = 12.dp, vertical = 2.dp))
                        } else {
                            val f = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                            BoxWithConstraints(Modifier.weight(1f).height(cq * 1.4f), contentAlignment = Alignment.CenterStart) {
                                Box(Modifier.fillMaxWidth().height(cq * 0.45f).background(Color(0x38ECEAF2), Radius.pill))
                                Box(Modifier.fillMaxWidth(f).height(cq * 0.45f).background(Accent.main, Radius.pill))
                                Box(Modifier.offset(x = maxWidth * f - cq * 0.7f).size(cq * 1.4f).background(Accent.main, CircleShape))
                            }
                            Text("${formatTime(position)} / ${formatTime(duration)}", style = timeStyle)
                        }
                    }
                    if (panel == Panel.NONE && !marquee) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            Text(
                                listOfNotNull(
                                    "↑ Settings".takeIf { hasSettings },
                                    if (showIntro) "↓ $skipText" else if (nextCard) "↓ Next episode" else "↓ Episodes".takeIf { strip != null }
                                ).joinToString("    "),
                                style = TvType.body.copy(color = Ink.muted),
                                modifier = Modifier.weight(1f)
                            )
                            val fmt = remember(context) { DateFormat.getTimeFormat(context) }
                            val left = duration - position
                            Text(
                                listOfNotNull(
                                    fmt.format(Date(now)),
                                    if (!isLive && duration > 0) "Ends at ${fmt.format(Date(now + (left / tweaks.speed).toLong()))}" else null
                                ).joinToString("     "),
                                style = TvType.body.copy(color = Ink.body, fontFeatureSettings = "tnum")
                            )
                        }
                    }
                }
            }
        }

        if (panel == Panel.SETTINGS) {
            SettingsTicket(
                allSettings, cq, room?.members?.size, { closePanel() },
                Modifier.align(Alignment.TopEnd).padding(end = cq * 2.5f, top = cq * 2.5f, bottom = cq * 9).width(cq * 40).fillMaxHeight(),
                // paused: Left from the tabs reaches the next-episode card
                leftOut = if (nextCard) nextFocus else null
            )
        }

        // the pause marquee owns the screen; the chip returns with playback
        if (showIntro && !marquee) {
            TvPill(
                skipText,
                onClick = {
                    val s = skipNow
                    if (s != null) player.seekTo(if (duration > 0) s.endMs.coerceAtMost(duration) else s.endMs)
                    position = player.currentPosition
                    lastSeekAt = System.currentTimeMillis()
                    show()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Overscan, bottom = if (visible) 150.dp else 40.dp)
                    .focusRequester(introFocus)
                    .onFocusChanged { introFocused = it.isFocused }
            )
        }

        if (error != null) {
            val retryFocus = remember { FocusRequester() }
            LaunchedEffect(error) { withFrameNanos { }; runCatching { retryFocus.requestFocus() } }
            Column(
                Modifier.align(Alignment.Center).background(Bg.raised, Radius.xl).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(error, style = TvType.title)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvPill("Retry", onRetry, modifier = Modifier.focusRequester(retryFocus))
                    if (settings.any { it.options.size > 1 }) TvPill("Change source", { panel = Panel.SETTINGS })
                    if (onWebPlayer != null) TvPill("Try web player", onWebPlayer)
                    TvPill("Back", onExit)
                }
            }
        }

        if (upNextShown) {
            UpNextCard(upNextImage, upNext.orEmpty(), title, onUpNext, onCancelUpNext, Modifier.align(Alignment.BottomEnd).padding(Overscan))
        }
        if (creditsCard) {
            UpNextCard(
                meta.nextStill ?: upNextImage, upNext.orEmpty(), meta.nextName ?: title, onUpNext, { creditsDismissed = skipNow },
                Modifier.align(Alignment.BottomEnd).padding(end = Overscan, bottom = if (visible) 150.dp else 40.dp).onFocusChanged { creditsFocused = it.hasFocus },
                cancelLabel = "Watch credits", countdownMs = CREDITS_NEXT_MS, playFocus = creditsFocus
            )
        }
        val note = message ?: skipToast
        if (note != null) {
            Text(
                note,
                style = TvType.body.copy(color = Ink.high),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp).background(Bg.overlay, Radius.pill).padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }
    }
}

/** "Watch together" on TV: start a party for [payload] (the phone flow), then show its code large with Leave. */
@Composable
private fun TvPartyPanel(payload: JsonObject, focus: FocusRequester, onClose: () -> Unit, vm: PartyViewModel = hiltViewModel()) {
    val room by vm.client.room.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val r = room
    // the button under focus changes when the room appears
    LaunchedEffect(r == null) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (r == null) {
            TvPill(if (busy) "Creating room…" else "Start a party", {
                busy = true; failed = false
                scope.launch { failed = vm.create(payload) == null; busy = false }
            }, enabled = !busy, modifier = Modifier.focusRequester(focus))
            if (failed) Text("Couldn't create a room", style = TvType.body.copy(color = Accent.danger), modifier = Modifier.padding(top = 12.dp))
        } else {
            var codeFocused by remember { mutableStateOf(false) }
            Text(
                r.code.toList().joinToString(" "),
                style = TvType.display.copy(letterSpacing = 6.sp),
                modifier = Modifier.onFocusChanged { codeFocused = it.isFocused }.focusable()
                    .background(if (codeFocused) Bg.overlay else Color.Transparent, Radius.md).padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Text("${r.members.size} watching. Friends enter this code under Reelm, Join party.", style = TvType.body)
            Spacer(Modifier.height(16.dp))
            TvPill("Leave", { vm.client.leave(); onClose() }, modifier = Modifier.focusRequester(focus))
        }
    }
}

/** Next-episode preview: art under the countdown ring, "Episode N", Play now (focused) / Cancel. The ring runs the countdown. */
@Composable
private fun UpNextCard(
    imageUrl: String?, episodeLabel: String, title: String, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier,
    cancelLabel: String = "Cancel", countdownMs: Long = UP_NEXT_MS, playFocus: FocusRequester = remember { FocusRequester() }
) {
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { playFocus.requestFocus() } }
    Row(
        modifier.background(Bg.raised, Radius.xl).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(240.dp).aspectRatio(16f / 9f).clip(Radius.lg).background(Bg.overlay)
            )
            Box(Modifier.matchParentSize().clip(Radius.lg).background(Bg.base.copy(alpha = 0.45f)))
            CountdownRing(onDone = onPlay, size = 64.dp, textStyle = TvType.title, durationMs = countdownMs)
        }
        Column {
            Text("Up next", style = TvType.body.copy(color = Ink.muted))
            Text(episodeLabel, style = TvType.headline, maxLines = 1)
            Text(title, style = TvType.body, maxLines = 1, modifier = Modifier.widthIn(max = 360.dp))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvPill("Play now", onPlay, modifier = Modifier.focusRequester(playFocus))
                TvPill(cancelLabel, onCancel)
            }
        }
    }
}

private val TicketBg = Color(0xF0101016)  // rgba(16,16,22,.94)
private val Hairline = Color(0x2EECEAF2)

/** Dashed vertical stub dividers (cheap: one drawLine). */
private fun Modifier.dashedRight(cq: Dp) = drawBehind {
    drawLine(Hairline, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = cq.toPx() * 0.16f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(cq.toPx() * 0.5f, cq.toPx() * 0.4f)))
}
private fun Modifier.dashedLeft(cq: Dp) = drawBehind {
    drawLine(Color(0x40ECEAF2), Offset(0f, 0f), Offset(0f, size.height), strokeWidth = cq.toPx() * 0.32f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(cq.toPx() * 0.5f, cq.toPx() * 0.4f)))
}

/** A group's current value as the tab says it: "Auto-skip on", "Low-end off"; an unset sleep timer says nothing. */
private fun tabValue(g: SettingGroup<*>): String? {
    val v = g.current() ?: return null
    return when {
        g.title == "Auto-skip intro and recap" -> "Auto-skip ${v.lowercase()}"
        g.title == "Sleep timer" -> if (v == "Off") null else "Sleep $v"
        g.title.startsWith("Low-end") -> "Low-end ${v.lowercase()}"
        else -> v
    }
}

/** Sections whose name differs on the ticket from the setting's own title. */
private fun heading(title: String) = when (title) {
    "Subtitles" -> "Track"; "Subtitle size" -> "Size"; "Subtitle delay" -> "Delay"; else -> title
}

/**
 * The side ticket: a tab stub per group (name + current values), a dashed divider, then the group's options.
 * ↑/↓ moves between tabs, → enters the options, ← returns to the tabs.
 */
@kotlin.OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsTicket(groups: List<SettingGroup<*>>, cq: Dp, partySize: Int?, close: () -> Unit, modifier: Modifier, leftOut: FocusRequester? = null) {
    val byTab = TabNames.indices.map { i -> groups.filter { tabOf(it.title) == i && it.options.isNotEmpty() } }
    val shown = byTab.indices.filter { byTab[it].isNotEmpty() }
    if (shown.isEmpty()) return
    var active by remember { mutableIntStateOf(shown.first()) }
    if (active !in shown) active = shown.first()
    val tabReq = remember { List(TabNames.size) { FocusRequester() } }
    val bodyReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { tabReq[shown.first()].requestFocus() } }
    fun sp(n: Float) = (cq.value * n).sp
    Row(modifier.clip(RoundedCornerShape(cq * 1.4f)).background(TicketBg)) {
        Column(
            Modifier.width(cq * 12).fillMaxHeight().background(Color(0x08FFFFFF)).dashedRight(cq)
                .focusProperties { exit = { if (it == FocusDirection.Right) bodyReq else if (it == FocusDirection.Left && leftOut != null) leftOut else FocusRequester.Default } }.focusGroup()
                .verticalScroll(rememberScrollState()).padding(horizontal = cq * 1f, vertical = cq * 1.8f),
            verticalArrangement = Arrangement.spacedBy(cq * 0.5f)
        ) {
            shown.forEach { i ->
                // "English, M, 0": the tab's current values (Position is left to the body)
                val value = if (i == 4) (partySize?.let { "$it watching" } ?: "Off")
                else byTab[i].filter { it.title != "Position" }.mapNotNull(::tabValue).take(3).joinToString(", ")
                val on = i == active
                Surface(
                    onClick = { runCatching { bodyReq.requestFocus() } },
                    modifier = Modifier.fillMaxWidth().focusRequester(tabReq[i]).onFocusChanged { if (it.isFocused) active = i },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(cq * 0.7f)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (on) Accent.main else Color.Transparent, contentColor = if (on) Accent.on else Color(0xFFCFCBD9),
                        focusedContainerColor = Accent.main, focusedContentColor = Accent.on
                    ),
                    border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(BorderStroke(cq * 0.2f, Ink.high), inset = cq * 0.2f)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = cq * 1f, vertical = cq * 0.8f)) {
                        Text(TabNames[i], style = TvType.body.copy(color = Color.Unspecified, fontSize = sp(1.15f), lineHeight = sp(1.78f), fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1)
                        if (value.isNotBlank()) Text(value, style = TvType.body.copy(color = if (on) Color(0xFF4A3818) else Color(0xFFA3A1AE), fontSize = sp(0.9f), lineHeight = sp(1.4f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .focusProperties { exit = { if (it == FocusDirection.Left) tabReq[active] else FocusRequester.Default } }.focusGroup()
                .verticalScroll(rememberScrollState()).padding(horizontal = cq * 1.6f, vertical = cq * 1.8f),
            verticalArrangement = Arrangement.spacedBy(cq * 0.8f)
        ) {
            Text(TabNames[active], modifier = Modifier.padding(bottom = cq * 0.4f), style = TvType.headline.copy(fontFamily = DisplayHeavy, fontWeight = FontWeight.ExtraBold, fontSize = sp(1.9f), lineHeight = sp(2.2f)))
            val gs = byTab[active]
            gs.forEachIndexed { gi, g -> GroupBody(g, if (gs.size > 1 || g.title != TabNames[active] || g.stub != null) heading(g.title) else null, if (gi == 0) bodyReq else null, cq) { if ((active == 0 || active == 4) && !g.stay) close() } }
        }
    }
}

/** A choice chip on the ticket: soft amber when chosen, solid amber under focus. */
@Composable
private fun OptChip(text: String, selected: Boolean, cq: Dp, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick, modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(percent = 50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0x38F2B45A) else Color(0x12FFFFFF), contentColor = if (selected) Accent.main else Ink.high,
            focusedContainerColor = Accent.main, focusedContentColor = Accent.on
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(text, maxLines = 1, modifier = Modifier.padding(horizontal = cq * 1f, vertical = cq * 0.5f),
            style = TvType.body.copy(color = Color.Unspecified, fontSize = (cq.value * 1.05f).sp, lineHeight = (cq.value * 1.4f).sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal))
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> GroupBody(g: SettingGroup<T>, heading: String?, focus: FocusRequester?, cq: Dp, done: () -> Unit) {
    if (heading != null) Text(heading, modifier = Modifier.padding(top = cq * 0.4f), style = TvType.body.copy(color = Color(0xFFA3A1AE), fontSize = (cq.value * 1.05f).sp, lineHeight = (cq.value * 1.4f).sp))
    val first = g.options.indexOfFirst(g.selected).coerceAtLeast(0)
    val stub = g.stub
    if (stub != null) {
        Column(verticalArrangement = Arrangement.spacedBy(cq * 0.8f)) {
            g.options.forEachIndexed { i, o ->
                val info = stub(o)
                val mod = if (i == first && focus != null) Modifier.focusRequester(focus) else Modifier
                if (info == null) OptChip(g.label(o), g.selected(o), cq, { g.pick(o); done() }, mod)
                else SourceStub(info, g.selected(o), { g.pick(o); done() }, cq, mod)
            }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(cq * 0.5f), verticalArrangement = Arrangement.spacedBy(cq * 0.5f)) {
            g.options.forEachIndexed { i, o ->
                OptChip(g.label(o), g.selected(o), cq, { g.pick(o); done() }, if (i == first && focus != null) Modifier.focusRequester(focus) else Modifier)
            }
        }
    }
}

/** A source on its stub, one compact row: quality, then name (and "Playing") over one line of size / codec / audio tags. */
@Composable
private fun SourceStub(i: SrcInfo, playing: Boolean, onClick: () -> Unit, cq: Dp, modifier: Modifier) {
    fun sp(n: Float) = (cq.value * n).sp
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(cq * 0.9f)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = Ink.high, focusedContainerColor = Color.Transparent, focusedContentColor = Ink.high),
        border = ClickableSurfaceDefaults.border(
            border = if (playing) androidx.tv.material3.Border(BorderStroke(cq * 0.22f, Accent.main)) else androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(BorderStroke(cq * 0.22f, Ink.high))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF3A3029), Color(0xFF121219)))).dashedLeft(cq).padding(horizontal = cq * 1.1f, vertical = cq * 0.55f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(cq * 1f)
        ) {
            Text(i.quality.ifBlank { "?" }, modifier = Modifier.widthIn(min = cq * 4.6f), style = TvType.headline.copy(fontFamily = DisplayHeavy, fontWeight = FontWeight.ExtraBold, fontSize = sp(1.5f), lineHeight = sp(1.7f)), maxLines = 1)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(cq * 0.8f)) {
                    Text(i.name, style = TvType.body.copy(color = Ink.high, fontWeight = FontWeight.SemiBold, fontSize = sp(1.1f), lineHeight = sp(1.4f)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (playing) Text("Playing", style = TvType.body.copy(color = Color(0xFF6FD39A), fontSize = sp(0.85f), fontWeight = FontWeight.SemiBold), maxLines = 1)
                }
                val line = listOfNotNull(i.size) + i.tags + i.flags
                if (line.isNotEmpty()) Text(line.joinToString(" · "), style = TvType.body.copy(color = Color(0xFFA3A1AE), fontSize = sp(0.85f), lineHeight = sp(1.15f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                i.note?.let { Text(it, style = TvType.body.copy(color = Accent.main, fontSize = sp(0.85f), lineHeight = sp(1.15f)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** "Oct 7, 2012" from "2012-10-07"; null when missing or unparseable. */
internal fun airLabel(iso: String?): String? = iso?.let {
    runCatching { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.US).format(java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it)!!) }.getOrNull()
}

/** Pause marquee: amber show line, huge episode title (first word solid, rest outline), synopsis, chips. */
@Composable
private fun PauseMarquee(cq: Dp, show: String?, name: String, synopsis: String?, chips: List<String>, modifier: Modifier) {
    val strokePx = with(LocalDensity.current) { (cq * 0.12f).toPx().coerceAtLeast(1.5f) }
    fun sp(n: Float) = (cq.value * n).sp
    val giant = buildAnnotatedString {
        append(name.substringBefore(' '))
        val rest = name.substringAfter(' ', "")
        if (rest.isNotEmpty()) { append(' '); withStyle(SpanStyle(color = Ink.high.copy(alpha = 0.55f), drawStyle = Stroke(strokePx))) { append(rest) } }
    }
    Column(modifier.width(cq * 46)) {
        if (show != null) Text(show, style = TvType.title.copy(color = Accent.main, fontSize = sp(1.4f), lineHeight = sp(1.9f), fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(cq * 1f))
        Text(
            giant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TvType.display.copy(fontFamily = DisplayHeavy, fontWeight = FontWeight.ExtraBold, fontSize = sp(6.4f), lineHeight = sp(6.4f * 0.88f), letterSpacing = (-0.015f).em)
        )
        if (!synopsis.isNullOrBlank()) {
            Spacer(Modifier.height(cq * 1.2f))
            Text(synopsis, style = TvType.body.copy(color = Color(0xFFCFCBD9), fontSize = sp(1.2f), lineHeight = sp(1.8f)), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = cq * 40))
        }
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(cq * 1.4f))
            Row(horizontalArrangement = Arrangement.spacedBy(cq * 0.6f)) {
                chips.forEach { Text(it, style = TvType.body.copy(color = Color(0xFFD9D6E2), fontSize = sp(1f), lineHeight = sp(1.4f)), modifier = Modifier.background(Color(0x17FFFFFF), Radius.pill).padding(horizontal = cq * 0.9f, vertical = cq * 0.3f)) }
            }
        }
    }
}

/** "2 watching" with overlapping initials, above the next-episode card. */
@Composable
private fun PartyChip(cq: Dp, members: List<String>, modifier: Modifier) {
    Row(modifier.background(Color(0xCC101016), Radius.pill).padding(start = cq * 0.5f, end = cq * 0.9f, top = cq * 0.4f, bottom = cq * 0.4f), verticalAlignment = Alignment.CenterVertically) {
        members.take(6).forEachIndexed { i, m ->
            Box(Modifier.offset(x = -cq * 0.9f * i).size(cq * 2.2f).background(Color(0xFF101016), CircleShape).padding(cq * 0.15f).background(Color(0xFF2A2340), CircleShape), contentAlignment = Alignment.Center) {
                Text(m.take(1).uppercase(), style = TvType.body.copy(fontSize = (cq.value * 1.1f).sp, lineHeight = (cq.value * 1.3f).sp))
            }
        }
        Text("${members.size} watching", modifier = Modifier.offset(x = -cq * 0.9f * (members.take(6).size - 1)).padding(start = cq * 0.6f),
            style = TvType.body.copy(color = Color(0xFFDAD6E4), fontSize = (cq.value * 1.05f).sp, lineHeight = (cq.value * 1.4f).sp))
    }
}

/** Next-episode card on the pause screen: still, "Next, episode N", name. OK opens it. */
@Composable
private fun NextCard(cq: Dp, label: String, name: String?, image: String?, onClick: () -> Unit, focus: FocusRequester, onFocus: (Boolean) -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(cq * 30).focusRequester(focus).onFocusChanged { onFocus(it.isFocused) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(cq * 0.9f)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x12FFFFFF), contentColor = Ink.high, focusedContainerColor = Color(0x2EFFFFFF), focusedContentColor = Ink.high
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(BorderStroke(cq * 0.22f, Accent.main))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(Modifier.padding(cq * 0.9f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(cq)) {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.width(cq * 9).aspectRatio(16f / 9f).clip(RoundedCornerShape(cq * 0.5f)).background(Bg.overlay))
            Column(Modifier.weight(1f)) {
                Text("Next, ${label.replaceFirstChar { it.lowercase() }}", style = TvType.body.copy(color = Color(0xFFA3A1AE), fontSize = (cq.value * 0.95f).sp, lineHeight = (cq.value * 1.3f).sp), maxLines = 1)
                if (!name.isNullOrBlank()) Text(name, style = TvType.title.copy(fontFamily = DisplayHeavy, fontWeight = FontWeight.ExtraBold, fontSize = (cq.value * 1.35f).sp, lineHeight = (cq.value * 1.6f).sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StripPanel(strip: EpisodeStrip, focus: FocusRequester, close: () -> Unit) {
    PillRow("Episodes", strip.numbers, { "Episode $it" }, { it == strip.current || it < strip.watchedBelow }, { strip.pick(it); close() },
        initial = strip.numbers.indexOf(strip.current).coerceAtLeast(0), firstFocus = focus, inset = 8.dp)
}

private fun formatTime(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, t % 3600 / 60, t % 60) else String.format(Locale.US, "%d:%02d", t / 60, t % 60)
}

@Composable
fun TvAnimePlayerScreen(onExit: () -> Unit, vm: PlayerViewModel = hiltViewModel(), guideVm: EpisodeGuideViewModel = hiltViewModel()) {
    val s by vm.uiState.collectAsStateWithLifecycle()
    val next = s.episodeNumbers.firstOrNull { it > s.episodeNumber }
    val guide by produceState(emptyMap<Int, GuideEp>(), s.animeTitle, s.episodeNumbers.size) {
        if (s.animeTitle.isNotBlank()) value = guideVm.guide.anime(s.animeTitle, null, null, s.episodeNumbers.size.takeIf { it > 0 } ?: 20).associateBy { it.n }
    }
    val nextEp = next?.let { guide[it] }
    TvRemoteBridge(vm.player, s.animeTitle.takeIf { it != s.animeSlug }.orEmpty(), "Episode ${s.episodeNumber}", s.posterUrl, s.hasNext, s.hasPrevious, vm::nextEpisode, vm::previousEpisode, onExit)
    TvPlayerChrome(
        player = vm.player,
        title = s.animeTitle,
        subtitle = "Episode ${s.episodeNumber}  ${if (s.language == "dub") "Dub" else "Sub"}",
        isLive = false,
        loading = s.isLoading,
        error = s.error,
        message = s.message,
        onMessageShown = vm::messageShown,
        onRetry = vm::retry,
        onStop = vm::onStop,
        onExit = onExit,
        settings = listOf(
            SettingGroup("Audio", listOf("sub", "dub"), { if (it == "dub") "Dub" else "Sub" }, { it == s.language }, vm::setLanguage),
            SettingGroup("Quality", s.availableQualities.map { it.qualityLabel }, { if (it == s.autoQuality) "$it · ${s.autoNote}" else it }, { it == s.selectedQuality }, vm::selectQuality)
        ),
        strip = EpisodeStrip(s.episodeNumbers, s.episodeNumber, s.watchedBelow, vm::playEpisode),
        upNext = next?.let { "Episode $it" }.takeIf { s.hasNext },
        ended = s.ended,
        onUpNext = vm::nextEpisode,
        onCancelUpNext = vm::dismissEnd,
        tweaksKey = s.animeSlug,
        upNextImage = s.posterUrl,
        skips = s.skips,
        skipSetting = true,
        partyPayload = s.animeSlug.takeIf { it.isNotBlank() }?.let { animePayload(it, s.animeTitle, s.episodeNumber, s.posterUrl) },
        meta = EpisodeMeta(
            show = "${s.animeTitle}, episode ${s.episodeNumber}",
            name = guide[s.episodeNumber]?.title?.takeIf { it.isNotBlank() } ?: "Episode ${s.episodeNumber}",
            synopsis = guide[s.episodeNumber]?.overview, runtimeMin = guide[s.episodeNumber]?.runtime, airDate = guide[s.episodeNumber]?.airDate,
            nextName = nextEp?.title, nextStill = nextEp?.still
        )
    )
}


@Composable
fun TvMediaPlayerScreen(
    onExit: () -> Unit,
    guideVm: EpisodeGuideViewModel = hiltViewModel(),
    /** (imdbId, season, episode) */
    onWebPlayer: (String, Int, Int) -> Unit,
    vm: MediaPlayerViewModel = hiltViewModel()
) {
    val s by vm.uiState.collectAsStateWithLifecycle()
    val r = s.request
    LaunchedEffect(s.webImdbId) {
        val tt = s.webImdbId ?: return@LaunchedEffect
        vm.webPlayerOpened()
        onWebPlayer(tt, r?.season ?: 0, r?.episode ?: 0)
    }
    val streams = r?.streams.orEmpty()
    val quals = vm.qualities()
    val guide by produceState(emptyMap<Int, GuideEp>(), r?.mediaId, r?.season) {
        if (r != null && r.mediaId != null && r.season > 0 && r.episode > 0)
            value = guideVm.guide.season(r.mediaId!!, r.title, null, r.season).associateBy { it.n }
    }
    val ge = r?.episode?.let { guide[it] }
    val nextEp = r?.episode?.let { guide[it + 1] }
    val subs = s.stream?.subtitles.orEmpty()
    TvRemoteBridge(vm.player, r?.title.orEmpty(), listOfNotNull(r?.subtitle, ge?.title?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null }, r?.posterUrl, s.hasNextEpisode, false, vm::nextEpisode, {}, onExit)
    TvPlayerChrome(
        player = vm.player,
        title = r?.title.orEmpty(),
        subtitle = r?.subtitle,
        isLive = s.isLive,
        loading = s.isLoading,
        error = s.error,
        message = s.message,
        onMessageShown = vm::messageShown,
        onRetry = vm::retry,
        onStop = vm::onStop,
        onExit = onExit,
        settings = listOf(
            SettingGroup("Quality", quals.takeIf { it.isNotEmpty() }?.let { listOf(0) + it.map { q -> q.first } }.orEmpty(),
                { h -> qualityChip(h, quals, s.stream?.height) }, { it == s.quality }, vm::selectQuality),
            // WEB_SOURCE = last-resort web player, after the real sources; without "Show all sources" just the playing one
            SettingGroup(
                if (s.showAllSources || streams.size < 2) "Source" else "Playing from",
                (if (s.showAllSources) vm.allSourcesOrder() else listOfNotNull(s.streamIndex.takeIf { it in streams.indices })) + listOfNotNull(WEB_SOURCE.takeIf { s.canWebPlay }),
                { i -> if (i == WEB_SOURCE) "Web player" else streams[i].let { listOf(it.sourceName, it.qualityLabel).filter(String::isNotBlank).joinToString(" ") + if (i == s.autoIndex) " · ${s.autoNote}" else "" } },
                { it == s.streamIndex },
                { i -> if (i == WEB_SOURCE) vm.openWebPlayer() else vm.selectStream(i) }
            ).apply { stub = { i -> streams.getOrNull(i)?.let { srcInfo(it, if (i == s.streamIndex) s.note else null) } } },
            SettingGroup("Show all sources", if (streams.size > 1) listOf(false, true) else emptyList(), { if (it) "On" else "Off" }, { it == s.showAllSources }, vm::setShowAllSources)
                .apply { stay = true },
            SettingGroup("Subtitles", if (subs.isEmpty()) emptyList() else listOf(-1) + subs.indices, { i -> if (i < 0) "Off" else subs[i].label }, { it == s.subtitleIndex }, vm::selectSubtitle)
        ),
        // MediaPlayerViewModel can only advance one episode, so the strip offers exactly that
        strip = if (s.hasNextEpisode && r != null) EpisodeStrip(listOf(r.episode + 1), -1, 0) { vm.nextEpisode() } else null,
        upNext = if (s.hasNextEpisode && r != null) "Episode ${r.episode + 1}" else null,
        ended = s.ended,
        onUpNext = vm::nextEpisode,
        onCancelUpNext = vm::dismissEnd,
        tweaksKey = r?.historyKey ?: r?.title ?: "media",
        onWebPlayer = if (s.canWebPlay) vm::openWebPlayer else null,
        skips = s.skips,
        skipSetting = !s.isLive,
        streams = streams,
        onAudioLanguage = vm::selectAudioLanguage,
        upNextImage = r?.posterUrl,
        partyPayload = r?.takeIf { !s.isLive }?.let(::mediaPayload),
        meta = if (r == null) EpisodeMeta() else EpisodeMeta(
            show = if (r.season > 0 && r.episode > 0) "${r.title}, season ${r.season}, episode ${r.episode}" else null,
            name = if (r.episode > 0) ge?.title?.takeIf { it.isNotBlank() } ?: "Episode ${r.episode}" else r.title,
            synopsis = ge?.overview, runtimeMin = ge?.runtime, airDate = ge?.airDate, nextName = nextEp?.title, nextStill = nextEp?.still
        )
    )
}

private const val WEB_SOURCE = -1

/** [note]: "1080p didn't load — playing 720p" on the source that took over */
private fun srcInfo(st: MediaStream, note: String? = null): SrcInfo {
    val t = "${st.sourceName} ${st.qualityLabel} ${st.url}".lowercase()
    val mb = (st.sizeBytes ?: 0) / 1_048_576.0
    // the height leads the stub; the rest of the label (Vega's server, "VCloud") goes with the name
    val server = st.qualityLabel.replace(Regex("""\b\d{3,4}p\b"""), "").trim().takeIf { st.height > 0 && it.isNotBlank() && !it.equals("auto", true) }
    return SrcInfo(
        listOfNotNull(st.sourceName.ifBlank { "Source" }, server).joinToString(" · "), if (st.height > 0) "${st.height}p" else st.qualityLabel,
        st.sizeBytes?.takeIf { it > 0 }?.let { if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else "${mb.toLong()} MB" },
        listOfNotNull(st.codec?.takeIf { it.isNotBlank() }?.uppercase(), "HDR".takeIf { "hdr" in t }) +
            listOfNotNull(st.languages.takeIf { it.isNotEmpty() }?.joinToString(" · ", transform = AudioLanguage::name)),
        listOfNotNull(
            "Dolby".takeIf { listOf("dolby", "ddp", "truehd", "eac3").any { it in t } },
            "Atmos".takeIf { "atmos" in t }
        ),
        note?.takeIf { it.isNotBlank() }
    )
}
