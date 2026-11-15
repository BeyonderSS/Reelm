package com.anistream.feature.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Text
import com.anistream.core.trailer.LivingBackdrop
import com.anistream.core.trailer.TrailerInfo
import com.anistream.core.trailer.TrailerKey
import com.anistream.core.trailer.TrailerRepository
import com.anistream.core.trailer.TrailerSurface
import com.anistream.core.trailer.rememberDwell
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import kotlinx.coroutines.delay

/** Rest this long on a title before its trailer plays (the countdown ring fills meanwhile). */
private const val DWELL_MS = 2_500L
/** Resolving a trailer (NewPipe) starts this far into the dwell, so a quick scroll never asks YouTube. */
private const val PREFETCH_MS = 700L

/** Media id → trailer lookup: "tt…" is an IMDb id; anything else ("mb:…") goes by title and year. */
internal fun mediaTrailerKey(id: String, title: String, year: Int?, series: Boolean): TrailerKey =
    if (id.startsWith("tt")) TrailerKey(imdb = id.substringBefore(':'))
    else TrailerKey(title = title, year = year, type = if (series) "series" else "movie")

/** Anime: MyAnimeList id when known (Jikan PVs), plus title/year so the server can find a logo. */
internal fun animeTrailerKey(mal: Long?, title: String, year: Int?) = TrailerKey(mal = mal, title = title, year = year, type = "series")

/**
 * Trailer preview on the TV Home, for the focused item [key]: [counting] while the ring fills, then [info] (non-null = playing,
 * or the living backdrop when there is no stream). Where it plays: inside the Continue-watching ticket ([inStrip], Running reel),
 * else behind the hero (House lights), or in the hero poster's window on low-end devices (Poster wakes up).
 */
@Stable
internal class TvPreview {
    var key by mutableStateOf<String?>(null)
    var counting by mutableStateOf(false)
    val ring = Animatable(0f)
    var info by mutableStateOf<TrailerInfo?>(null)
    /** first frame on screen (immediately for the living backdrop) */
    var shown by mutableStateOf(false)
    var inStrip by mutableStateOf(false)
    /** ▲ in House lights: sound on, letterbox bars, the home steps away */
    var big by mutableStateOf(false)
    var sound by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    /** stopped by Back, Left/Right or the trailer's end: stays off until focus moves to another item */
    var stoppedFor by mutableStateOf<String?>(null)

    val live get() = info != null

    fun reset() { counting = false; info = null; shown = false; big = false; sound = false; progress = 0f }
    fun stop() { stoppedFor = key; reset() }
}

internal val LocalTvPreview = staticCompositionLocalOf<TvPreview?> { null }

/**
 * Runs the dwell → trailer cycle for the focused item. [homeFocused] = focus is inside Home (not the rail); previews also wait for
 * window focus (no dialog), a resumed screen (no player on top) and [TrailerRepository.allowedNow] (mode, network, remote, motion).
 */
@Composable
internal fun TvPreviewDriver(p: TvPreview, focused: State<TvItem?>, homeFocused: State<Boolean>, noDialog: Boolean, repo: TrailerRepository) {
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val enabled = homeFocused.value && LocalWindowInfo.current.isWindowFocused && resumed && noDialog
    val item = focused.value
    val k = item?.key
    BackHandler(enabled = p.live) { if (p.big) { p.big = false; p.sound = false } else p.stop() }
    key(k) {
        val trailer = item?.trailer
        val allowed = remember { repo.allowedNow() }
        val armed = enabled && allowed && trailer != null && p.stoppedFor != k
        val ready = rememberDwell(armed, DWELL_MS)
        val info by produceState<TrailerInfo?>(null, armed) { if (armed && trailer != null) { delay(PREFETCH_MS); value = repo.get(trailer) } }
        LaunchedEffect(armed) {
            if (p.stoppedFor != k) p.stoppedFor = null
            p.key = k
            p.reset()
            p.inStrip = k?.startsWith("cw|") == true
            if (armed) {
                p.counting = true
                p.ring.snapTo(0f)
                p.ring.animateTo(1f, tween(DWELL_MS.toInt(), easing = LinearEasing))
                p.counting = false
            }
        }
        // the strip's art slot has no room for a slideshow: Running reel needs a real stream
        val usable = info?.takeIf { it.streamUrl != null || (!p.inStrip && it.backdrops.isNotEmpty()) }
        LaunchedEffect(ready, usable) {
            if (ready && armed && usable != null && p.info == null) { p.info = usable; p.shown = usable.streamUrl == null }
        }
    }
}

/** The countdown ring (top-right of the focused ticket or poster) while the dwell runs. */
internal fun Modifier.dwellRing(key: String, p: TvPreview?, size: Dp, inset: Dp): Modifier = if (p == null) this else drawWithContent {
    drawContent()
    if (!p.counting || p.key != key) return@drawWithContent
    val r = size.toPx() / 2
    val sw = r * 2 * 3.5f / 36f
    val c = Offset(this.size.width - inset.toPx() - r, inset.toPx() + r)
    drawCircle(Bg.badge, r + sw, c)
    drawCircle(Ink.faint, r - sw / 2, c, style = Stroke(sw))
    drawArc(Accent.main, -90f, 360f * p.ring.value, false, c - Offset(r - sw / 2, r - sw / 2), Size(2 * r - sw, 2 * r - sw), style = Stroke(sw, cap = StrokeCap.Round))
}

/** House lights: the trailer full-bleed behind the hero, in place of the backdrop glow, under the mockup's left and bottom shade. */
@Composable
internal fun HouseLights(p: TvPreview, bigness: State<Float>) {
    val info = p.info ?: return
    if (p.inStrip || LocalLowEnd.current) return
    Box(Modifier.fillMaxSize()) {
        if (info.streamUrl != null) TrailerSurface(
            info, playing = true, soundOn = p.sound, modifier = Modifier.fillMaxSize(),
            onFirstFrame = { p.shown = true }, onProgress = { p.progress = it }, onEnded = { p.stop() }
        ) else FadeIn { LivingBackdrop(info.backdrops, Modifier.fillMaxSize()) }
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = 1f - 0.85f * bigness.value }
                .background(Brush.horizontalGradient(0f to Night, 0.18f to Night, 0.3f to Night.copy(alpha = 0.86f), 0.58f to Night.copy(alpha = 0.25f), 0.75f to Color.Transparent))
                .background(Brush.verticalGradient(0.38f to Color.Transparent, 0.58f to Night.copy(alpha = 0.6f), 0.78f to Night, 1f to Night))
        )
    }
}

/** The trailer inside a frame (the low-end hero poster's window, the reel's art slot), or the living backdrop when it has no stream. */
@Composable
internal fun PreviewFrame(p: TvPreview, info: TrailerInfo, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (info.streamUrl != null) TrailerSurface(
            info, playing = true, soundOn = p.sound, modifier = Modifier.fillMaxSize(),
            onFirstFrame = { p.shown = true }, onProgress = { p.progress = it }, onEnded = { p.stop() }
        ) else LivingBackdrop(info.backdrops, Modifier.fillMaxSize())
    }
}

@Composable
private fun FadeIn(content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(600)) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }) { content() }
}

/** Letterbox bars (full screen) and the remote hint row "OK Play / ▲ … / Back Stop" while a trailer is on screen. */
@Composable
internal fun PreviewOverlay(p: TvPreview, bigness: State<Float>, tall: State<Boolean>) {
    val s = uiScale()
    fun d(n: Float) = (n * s).dp
    Box(Modifier.fillMaxSize().drawBehind {
        val h = d(43.2f).toPx() * bigness.value
        if (h > 0f) {
            drawRect(Color.Black, Offset.Zero, Size(size.width, h))
            drawRect(Color.Black, Offset(0f, size.height - h), Size(size.width, h))
        }
    }) {
        val house = !p.inStrip && !LocalLowEnd.current
        val a by animateFloatAsState(if (p.live && p.shown) 1f else 0f, tween(400), label = "hint")
        if (a > 0f) Row(
            Modifier.align(if (p.big) Alignment.BottomEnd else Alignment.TopEnd)
                .padding(end = d(38.4f), top = if (p.big) 0.dp else d(if (tall.value) 292f else 228f), bottom = if (p.big) d(57.6f) else 0.dp)
                .graphicsLayer { alpha = a },
            horizontalArrangement = Arrangement.spacedBy(d(15.4f)), verticalAlignment = Alignment.CenterVertically
        ) {
            Hint("OK", "Play", s)
            Hint("▲", if (p.sound) "Sound off" else if (house) "Full screen with sound" else "Sound on", s)
            Hint("Back", "Stop", s)
        }
    }
}

@Composable
private fun Hint(key: String, label: String, s: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            key, maxLines = 1,
            style = TvType.body.copy(fontSize = (11.5f * s).sp, lineHeight = (15f * s).sp, fontWeight = FontWeight.SemiBold, color = Ink.high),
            modifier = Modifier.background(Ink.high.copy(alpha = 0.14f), androidx.compose.foundation.shape.RoundedCornerShape((4.8f * s).dp)).padding(horizontal = (5.8f * s).dp, vertical = (1f * s).dp)
        )
        Text(label, maxLines = 1, modifier = Modifier.padding(start = (4.8f * s).dp),
            style = TvType.body.copy(fontSize = (12.5f * s).sp, lineHeight = (16f * s).sp, color = Ink.muted))
    }
}
