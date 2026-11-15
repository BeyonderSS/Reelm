package com.anistream.core.trailer

import android.content.Context
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import com.anistream.core.common.LowEnd
import com.anistream.core.ui.components.ArtworkImage
import com.anistream.core.ui.components.SegmentedControl
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Space
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

private const val CAP_MS = 30_000L
private const val FADE_MS = 600

/**
 * The one ExoPlayer every TrailerSurface shares; released with the last surface. Accessed on the main thread only, but built on a
 * background thread ahead of time ([prewarm], from [rememberDwell]): a cold ExoPlayer build blocked the UI ~55 ms on the TV emulator
 * right as the trailer faded in. ExoPlayer supports building off its application thread (calls wait for the constructor).
 */
private object Shared {
    var player: ExoPlayer? = null
    var factory: DefaultMediaSourceFactory? = null
    private var warming: Thread? = null
    var users = 0
    /** surface currently showing video; snapshot state so the others hide themselves */
    var owner by mutableStateOf<Any?>(null)
    private val main = Handler(Looper.getMainLooper())
    private val idleRelease = Runnable { if (users == 0) releaseNow() }

    /** Any thread: the player's setters must run on main, see [adopt]. */
    @OptIn(UnstableApi::class)
    private fun build(context: Context) = ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build() to DefaultMediaSourceFactory(context)

    private fun adopt(context: Context, built: Pair<ExoPlayer, DefaultMediaSourceFactory>): ExoPlayer {
        val p = built.first
        val cap = if (LowEnd.isOn(context)) 854 to 480 else 1280 to 720 // only matters for the HLS fallback
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(cap.first, cap.second).build()
        p.volume = 0f
        player = p
        factory = built.second
        return p
    }

    /** Build the player in the background if none exists; an unused one is released after a while. */
    fun prewarm(context: Context) {
        if (player != null || warming != null) return
        val app = context.applicationContext
        warming = Thread {
            val built = runCatching { build(app) }.getOrNull()
            main.post {
                warming = null
                if (built == null) return@post
                if (player != null) { built.first.release(); return@post }
                adopt(app, built)
                if (users == 0) main.postDelayed(idleRelease, IDLE_MS)
            }
        }.apply { name = "trailer-prewarm"; start() }
    }

    fun acquire(context: Context): ExoPlayer {
        users++
        main.removeCallbacks(idleRelease)
        return player ?: context.applicationContext.let { adopt(it, build(it)) }
    }

    fun release() {
        if (--users > 0) return
        releaseNow()
    }

    private fun releaseNow() {
        player?.release()
        player = null
        factory = null
        owner = null
    }

    private const val IDLE_MS = 20_000L
}

/** Center-crop the video into the view (TextureView stretches by default). */
private fun TextureView.crop(size: VideoSize) {
    if (size.width == 0 || size.height == 0 || width == 0 || height == 0) return
    val vw = size.width * size.pixelWidthHeightRatio
    val s = max(width / vw, height / size.height.toFloat())
    setTransform(Matrix().apply { setScale(vw * s / width, size.height * s / height, width / 2f, height / 2f) })
}

/** Muted ExoPlayer surface. Shows nothing (transparent) until the first frame renders, then fades in 600 ms; calls onEnded after 30 s or at the end. One shared ExoPlayer instance app-wide (released when no TrailerSurface is composed). */
@OptIn(UnstableApi::class)
@Composable
fun TrailerSurface(
    info: TrailerInfo,
    playing: Boolean,
    soundOn: Boolean,
    modifier: Modifier = Modifier,
    onFirstFrame: () -> Unit = {},
    onProgress: (Float) -> Unit = {},
    onEnded: () -> Unit = {},
) {
    val context = LocalContext.current
    val token = remember { Any() }
    val player = remember { Shared.acquire(context) }
    val started by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = playing && started.isAtLeast(Lifecycle.State.STARTED) && info.streamUrl != null
    var texture by remember { mutableStateOf<TextureView?>(null) }
    var rendered by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf<String?>(null) } // url this surface last prepared
    val sound by rememberUpdatedState(soundOn)
    val firstFrame by rememberUpdatedState(onFirstFrame)
    val progress by rememberUpdatedState(onProgress)
    val end by rememberUpdatedState(onEnded)
    val isActive by rememberUpdatedState(active)
    val mine = Shared.owner === token

    SideEffect { if (mine) player.volume = if (sound) 1f else 0f }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (Shared.owner === token && !rendered) { rendered = true; firstFrame() }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) { if (Shared.owner === token) texture?.crop(videoSize) }
            override fun onPlayerError(error: PlaybackException) {
                // a cancel tears the surface down under the decoder ("setSurface() ... Released state"): not a failed trailer
                if (Shared.owner === token && isActive && !ended) { ended = true; end() }
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            if (Shared.owner === token) { player.stop(); player.clearVideoSurface(); Shared.owner = null }
            Shared.release()
        }
    }

    LaunchedEffect(active, info.streamUrl, texture) {
        val url = info.streamUrl ?: return@LaunchedEffect
        val view = texture ?: return@LaunchedEffect
        if (loaded != url) {
            rendered = false
            ended = false
            if (Shared.owner === token) { player.stop(); Shared.owner = null }
        }
        if (!active || ended) {
            if (Shared.owner === token) player.pause()
            return@LaunchedEffect
        }
        if (Shared.owner !== token) {
            Shared.owner = token
            loaded = url
            rendered = false
            player.setVideoTextureView(view)
            val factory = Shared.factory ?: DefaultMediaSourceFactory(context)
            val video = factory.createMediaSource(MediaItem.fromUri(url))
            player.setMediaSource(info.audioUrl?.let { MergingMediaSource(video, factory.createMediaSource(MediaItem.fromUri(it))) } ?: video, info.startAtMs)
            player.prepare()
        }
        if (player.playerError != null) player.prepare() // an error swallowed while cancelled: retry on resume
        player.volume = if (sound) 1f else 0f
        player.playWhenReady = true
        while (Shared.owner === token) {
            val left = if (player.duration > 0) player.duration - info.startAtMs else CAP_MS
            val total = min(CAP_MS, left.coerceAtLeast(1))
            val elapsed = player.currentPosition - info.startAtMs
            progress((elapsed.toFloat() / total).coerceIn(0f, 1f))
            if (player.playbackState == Player.STATE_ENDED || elapsed >= total) {
                player.pause()
                ended = true
                end()
                break
            }
            delay(250)
        }
    }

    val alpha by animateFloatAsState(if (mine && rendered && !ended) 1f else 0f, tween(FADE_MS), label = "trailerFade")
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = false
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    if (Shared.owner === token) (v as TextureView).crop(player.videoSize)
                }
                texture = this
            }
        },
        modifier = modifier.clipToBounds().alpha(alpha),
    )
}

/** Fallback when streamUrl is null: slow Ken Burns pan/crossfade across backdrops (Coil). */
@Composable
fun LivingBackdrop(urls: List<String>, modifier: Modifier = Modifier) {
    var i by remember(urls) { mutableIntStateOf(0) }
    LaunchedEffect(urls) {
        while (urls.size > 1) { delay(8_000); i = (i + 1) % urls.size }
    }
    val drift = rememberInfiniteTransition(label = "kenBurns")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    Box(modifier.clipToBounds().background(Bg.base)) {
        Crossfade(urls.getOrNull(i), animationSpec = tween(1_200), label = "backdrop") { url ->
            if (url != null) ArtworkImage(url, null, Modifier.fillMaxSize().graphicsLayer {
                val p = drift.value
                scaleX = 1.04f + 0.08f * p
                scaleY = scaleX
                translationX = (p - 0.5f) * size.width * 0.04f
            })
        }
    }
}

/** Dwell helper: returns true once `active` has stayed true for `delayMs`. */
@Composable
fun rememberDwell(active: Boolean, delayMs: Long): Boolean {
    var done by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(active, delayMs) {
        done = false
        // a dwell means a trailer is likely next: have the player ready so starting it doesn't stall the UI
        if (active) { Shared.prewarm(context); delay(delayMs); done = true }
    }
    return active && done
}

/** Settings row: "Preview trailers" with On / Wi-Fi only / Off. Phone and TV settings screens add it. */
@Composable
fun PreviewTrailersSetting(repo: TrailerRepository) {
    val mode by repo.mode.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text("Preview trailers", style = AppType.bodyStrong)
        Text("Muted trailers play when you rest on a title. Wi-Fi only skips mobile data and Data Saver.", style = AppType.caption)
        SegmentedControl(listOf("On", "Wi-Fi only", "Off"), mode.ordinal, { repo.setMode(PreviewMode.entries[it]) })
    }
}
