package com.anistream.feature.player

import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Rational
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.media3.common.VideoSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.anistream.core.ui.theme.Accent
import com.anistream.domain.AudioLanguage
import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.SkipInterval
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Picture modes; [aspect] non-null forces that ratio (video stretched into a centred box). */
enum class Resize(val label: String, val mode: Int, val aspect: Float? = null) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    WIDE("16:9", AspectRatioFrameLayout.RESIZE_MODE_FILL, 16f / 9f),
    STANDARD("4:3", AspectRatioFrameLayout.RESIZE_MODE_FILL, 4f / 3f)
}

val PlaybackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
fun speedLabel(v: Float) = if (v % 1f == 0f) "${v.toInt()}x" else "${v}x"

val SubtitleScales = listOf(0.75f, 1f, 1.3f, 1.6f)
fun subtitleScaleLabel(v: Float) = when (v) { 0.75f -> "Small"; 1f -> "Normal"; 1.3f -> "Large"; else -> "Huge" }

// ponytail: delay only shifts subtitles later (cues are re-posted); showing them earlier needs a custom text renderer
val SubtitleDelays = listOf(0L, 250L, 500L, 1000L, 2000L, 3000L, 5000L)
fun delayLabel(ms: Long) = if (ms == 0L) "None" else "+%.2g s".format(Locale.US, ms / 1000f)

/** Where captions sit; [bottomFraction] feeds SubtitleView.setBottomPaddingFraction (TV overscan eats the bottom edge). */
enum class SubPosition(val label: String, val bottomFraction: Float) {
    BOTTOM("Bottom", 0.08f), RAISED("Raised", 0.12f), HIGHER("Higher", 0.2f), TOP("Top", 0.78f)
}

fun isTvDevice(ctx: Context) = ctx.packageManager.run { hasSystemFeature(PackageManager.FEATURE_LEANBACK) || hasSystemFeature("amazon.hardware.fire_tv") }

const val SLEEP_END_OF_EPISODE = -1
val SleepOptions = listOf(0, 15, 30, 60, SLEEP_END_OF_EPISODE)
fun sleepLabel(m: Int) = when (m) { 0 -> "Off"; SLEEP_END_OF_EPISODE -> "End of episode"; else -> "$m min" }

const val INTRO_WINDOW_MS = 5 * 60_000L
const val INTRO_SKIP_MS = 85_000L

/** Button label for an AniSkip interval type. */
fun skipLabel(type: String) = when (type) {
    "recap" -> "Skip recap"
    "ed", "mixed-ed" -> "Skip credits"
    else -> "Skip intro"
}

/** The AniSkip interval playing at [positionMs], if any. */
fun activeSkip(skips: List<SkipInterval>, positionMs: Long): SkipInterval? =
    skips.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 500 }

const val UP_NEXT_MS = 5_000L

/**
 * The up-next countdown: an amber ring filling over [durationMs] with the seconds left inside, then [onDone].
 * Phone and TV next-episode cards both run their countdown through this.
 */
@Composable
fun CountdownRing(onDone: () -> Unit, modifier: Modifier = Modifier, durationMs: Long = UP_NEXT_MS, size: Dp = 48.dp, textStyle: TextStyle = Tabular) {
    val progress = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMs.toInt(), easing = LinearEasing))
        done()
    }
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            drawArc(Ink.ghost, 0f, 360f, useCenter = false, style = stroke)
            drawArc(Accent.main, -90f, 360f * progress.value, useCenter = false, style = stroke)
        }
        val left = ((1f - progress.value) * durationMs / 1000f).let { kotlin.math.ceil(it).toInt() }
        Text("$left", style = textStyle.copy(color = Ink.high))
    }
}

/** Per-title player settings. Speed, picture mode and subtitle size persist per [key]; the rest lasts the session. */
class PlayerTweaks(private val prefs: SharedPreferences, private val key: String, tv: Boolean = false) {
    /** caption position; global, TV defaults to Raised, phone to Bottom */
    var subPosition by mutableStateOf(SubPosition.entries.getOrElse(prefs.getInt("subPos", -1)) { if (tv) SubPosition.RAISED else SubPosition.BOTTOM }); private set

    fun updateSubPosition(p: SubPosition) {
        subPosition = p
        prefs.edit().putInt("subPos", p.ordinal).apply()
    }

    var speed by mutableFloatStateOf(prefs.getFloat("$key.speed", 1f)); private set
    var resize by mutableStateOf(Resize.entries.getOrElse(prefs.getInt("$key.resize", 0)) { Resize.FIT }); private set
    var subScale by mutableFloatStateOf(prefs.getFloat("$key.sub", 1f)); private set
    var subBackground by mutableStateOf(true)
    var subDelayMs by mutableLongStateOf(0L)
    /** minutes; 0 off, [SLEEP_END_OF_EPISODE] */
    var sleep by mutableIntStateOf(0); private set
    /** elapsedRealtime deadline for a timed sleep, 0 = none */
    var sleepAt by mutableLongStateOf(0L); private set
    /** jump over AniSkip intro/recap intervals; global, not per title */
    var autoSkip by mutableStateOf(prefs.getBoolean("autoSkip", false)); private set

    fun updateAutoSkip(on: Boolean) {
        autoSkip = on
        prefs.edit().putBoolean("autoSkip", on).apply()
    }

    fun updateSpeed(player: Player, v: Float) {
        speed = v
        player.setPlaybackSpeed(v)
        prefs.edit().putFloat("$key.speed", v).apply()
    }

    fun updateResize(r: Resize) {
        resize = r
        prefs.edit().putInt("$key.resize", r.ordinal).apply()
    }

    fun updateSubScale(v: Float) {
        subScale = v
        prefs.edit().putFloat("$key.sub", v).apply()
    }

    fun updateSleep(minutes: Int) {
        sleep = minutes
        sleepAt = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
    }
}

@Composable
fun rememberPlayerTweaks(key: String): PlayerTweaks {
    val ctx = LocalContext.current.applicationContext
    return remember(key) { PlayerTweaks(ctx.getSharedPreferences("player_prefs", Context.MODE_PRIVATE), key.ifBlank { "default" }, isTvDevice(ctx)) }
}

/** Current tracks, updated as the stream changes. */
@Composable
fun rememberTracks(player: Player): Tracks {
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { tracks = t }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    return tracks
}

class AudioOption(val label: String, val selected: Boolean, internal val group: Tracks.Group, internal val index: Int)

fun audioOptions(tracks: Tracks): List<AudioOption> {
    val out = mutableListOf<AudioOption>()
    for (g in tracks.groups) {
        if (g.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val lang = f.language?.takeIf { it != "und" }?.let { Locale(it).displayLanguage }
            // language first: releases often title every track with their site name ("Vegamovies.NL" twice)
            out += AudioOption(listOfNotNull(lang, f.label).distinct().joinToString(" · ").ifEmpty { "Track ${out.size + 1}" }, g.isTrackSelected(i), g, i)
        }
    }
    return out
}

/** Languages of the playing file's audio tracks, ISO 639-1. */
fun trackLanguages(tracks: Tracks): List<String> = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    .flatMap { g -> (0 until g.length).mapNotNull { AudioLanguage.normalize(g.getTrackFormat(it).language) } }.distinct()

/** "Audio language" choices: every language the request's [streams] advertise plus the playing file's tracks, by name. */
fun audioLanguages(streams: List<MediaStream>, tracks: Tracks): List<String> =
    (streams.flatMap { it.languages } + trackLanguages(tracks)).distinct().sortedBy(AudioLanguage::name)

fun selectAudio(player: Player, o: AudioOption) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
        .build()
}

fun hasSubtitles(tracks: Tracks) = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }

/**
 * PlayerView + picture mode + our own subtitle layer (size, background, delay) + speed and sleep timer.
 * Shared by the phone and TV players; [create] builds the PlayerView.
 */
@Composable
fun VideoSurface(
    player: Player,
    tweaks: PlayerTweaks,
    create: (Context) -> PlayerView,
    modifier: Modifier = Modifier,
    onView: (PlayerView) -> Unit = {},
    showCaptions: Boolean = true
) {
    LaunchedEffect(player, tweaks) { player.setPlaybackSpeed(tweaks.speed) }
    LaunchedEffect(tweaks.sleepAt) {
        val at = tweaks.sleepAt
        if (at > 0) {
            delay(at - SystemClock.elapsedRealtime())
            player.pause()
            tweaks.updateSleep(0)
        }
    }
    LaunchedEffect(tweaks.sleep) {
        // pausing just before the end also keeps the next-episode countdown from starting
        while (tweaks.sleep == SLEEP_END_OF_EPISODE) {
            val d = player.duration
            if (d > 0 && player.currentPosition >= d - 1500) {
                player.pause()
                tweaks.updateSleep(0)
            }
            delay(500)
        }
    }

    // Fill used to zoom from the centre, cropping top and bottom equally, which cut off subtitles burned into the
    // video (most anime "sub" streams). It now scales to full width and crops only the top.
    var videoAspect by remember { mutableStateOf(0f) }
    DisposableEffect(player) {
        fun read(v: VideoSize) { if (v.height > 0) videoAspect = v.width * v.pixelWidthHeightRatio / v.height }
        val l = object : Player.Listener { override fun onVideoSizeChanged(videoSize: VideoSize) = read(videoSize) }
        read(player.videoSize); player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val aspect = tweaks.resize.aspect
        // only when the video is narrower than the screen (16:9 on a 20:9 phone); wider films keep the centre zoom
        val fillFromBottom = tweaks.resize == Resize.FILL && videoAspect > 0f && maxHeight.value > 0f &&
            videoAspect < maxWidth.value / maxHeight.value
        val mode = if (fillFromBottom) AspectRatioFrameLayout.RESIZE_MODE_FIT else tweaks.resize.mode
        AndroidView(
            factory = { ctx -> create(ctx).also { it.subtitleView?.visibility = View.GONE; onView(it) } },
            update = { it.player = player; it.resizeMode = mode },
            onRelease = { it.player = null },
            modifier = when {
                fillFromBottom -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true).aspectRatio(videoAspect)
                aspect == null -> Modifier.fillMaxSize()
                else -> Modifier.align(Alignment.Center).aspectRatio(aspect)
            }
        )
        if (showCaptions) Subtitles(player, tweaks, Modifier.fillMaxSize())
    }
}

@Composable
private fun Subtitles(player: Player, tweaks: PlayerTweaks, modifier: Modifier) {
    val ctx = LocalContext.current
    val view = remember { SubtitleView(ctx) }
    val delayMs = tweaks.subDelayMs
    DisposableEffect(player, delayMs) {
        val handler = Handler(Looper.getMainLooper())
        val l = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                val cues = cueGroup.cues
                if (delayMs <= 0) view.setCues(cues) else handler.postDelayed({ view.setCues(cues) }, delayMs)
            }
        }
        view.setCues(player.currentCues.cues)
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            handler.removeCallbacksAndMessages(null)
        }
    }
    val bottom = tweaks.subPosition.bottomFraction
    val scale = tweaks.subScale
    val bg = tweaks.subBackground
    AndroidView(
        factory = { view },
        update = {
            it.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
            // TVs overscan the bottom edge, so TV defaults to Raised (12%); the Position setting overrides
            it.setBottomPaddingFraction(bottom)
            it.setStyle(
                CaptionStyleCompat(
                    AColor.WHITE,
                    if (bg) AColor.argb(170, 10, 12, 18) else AColor.TRANSPARENT,
                    AColor.TRANSPARENT,
                    if (bg) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    AColor.BLACK,
                    null
                )
            )
        },
        modifier = modifier
    )
}

/**
 * Enters picture-in-picture when the user leaves the app while [player] plays.
 * Returns whether the activity is in PiP now (callers show the video alone, no controls).
 */
@Composable
fun rememberPip(player: Player): Boolean {
    val activity = LocalContext.current.findActivity() as? ComponentActivity
    var inPip by remember { mutableStateOf(Build.VERSION.SDK_INT >= 26 && activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity, player) {
        if (Build.VERSION.SDK_INT < 26 || activity == null || !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return@DisposableEffect onDispose { }
        }
        val leave = Runnable {
            if (player.isPlaying) runCatching {
                activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
        }
        val changed = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity.addOnUserLeaveHintListener(leave)
        activity.addOnPictureInPictureModeChangedListener(changed)
        onDispose {
            activity.removeOnUserLeaveHintListener(leave)
            activity.removeOnPictureInPictureModeChangedListener(changed)
        }
    }
    return inPip
}

/** Saves the current TextureView frame to Pictures/Reelm (app pictures dir below Android 10) and toasts. */
internal suspend fun saveFrame(ctx: Context, view: PlayerView?) {
    val bmp = (view?.videoSurfaceView as? TextureView)?.bitmap
    if (bmp == null) {
        Toast.makeText(ctx, "Couldn't capture this frame", Toast.LENGTH_SHORT).show()
        return
    }
    val name = "Reelm_${System.currentTimeMillis()}.jpg"
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Reelm")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
                ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            } else {
                // ponytail: API 26-28 would need WRITE_EXTERNAL_STORAGE for public Pictures; app dir avoids the prompt
                val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Reelm").apply { mkdirs() }
                File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            }
        }.isSuccess
    }
    Toast.makeText(ctx, if (ok) "Screenshot saved to Pictures/Reelm" else "Couldn't save the screenshot", Toast.LENGTH_SHORT).show()
}

/** Playback position as a 0..1 fraction (0 until the duration is known). */
internal fun Player.progressFraction(): Float = if (duration > 0) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

/** Phone settings-sheet section: speed, audio track, subtitle options, sleep timer. */
@Composable
internal fun PlayerTweaksSection(
    player: Player, tweaks: PlayerTweaks, live: Boolean, autoSkipToggle: Boolean = false,
    streams: List<MediaStream> = emptyList(), onAudioLanguage: ((String?) -> Unit)? = null
) {
    val tracks = rememberTracks(player)
    val preferredLanguage by AudioLanguage.preferred.collectAsState()
    if (autoSkipToggle) {
        SheetHeading("Auto-skip intro and recap")
        ChoiceRow(listOf(true, false), tweaks.autoSkip, { if (it) "On" else "Off" }, tweaks::updateAutoSkip)
    }
    if (!live) {
        SheetHeading("Playback speed")
        ChoiceRow(PlaybackSpeeds, tweaks.speed, ::speedLabel) { tweaks.updateSpeed(player, it) }
    }
    SheetHeading("Picture")
    ChoiceRow(Resize.entries, tweaks.resize, { it.label }, tweaks::updateResize)
    val languages = if (onAudioLanguage == null) emptyList() else audioLanguages(streams, tracks)
    if (onAudioLanguage != null && languages.size > 1) {
        SheetHeading("Audio language")
        ChoiceRow(listOf<String?>(null) + languages, preferredLanguage, { it?.let(AudioLanguage::name) ?: "Any" }, onAudioLanguage)
    }
    val audio = audioOptions(tracks)
    if (audio.size > 1) {
        SheetHeading("Audio track")
        audio.forEach { o -> SheetOption(o.label, selected = o.selected) { selectAudio(player, o) } }
    }
    if (hasSubtitles(tracks)) {
        SheetHeading("Subtitle size")
        ChoiceRow(SubtitleScales, tweaks.subScale, ::subtitleScaleLabel, tweaks::updateSubScale)
        SheetHeading("Subtitle background")
        ChoiceRow(listOf(true, false), tweaks.subBackground, { if (it) "On" else "Off" }) { tweaks.subBackground = it }
        SheetHeading("Subtitle delay")
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { tweaks.subDelayMs = (tweaks.subDelayMs - 250).coerceAtLeast(0) }) {
                Icon(Icons.Rounded.Remove, contentDescription = "Less delay", tint = Ink.high)
            }
            Text(delayLabel(tweaks.subDelayMs), style = Tabular, modifier = Modifier.padding(horizontal = Space.sm))
            IconButton(onClick = { tweaks.subDelayMs = (tweaks.subDelayMs + 250).coerceAtMost(10_000) }) {
                Icon(Icons.Rounded.Add, contentDescription = "More delay", tint = Ink.high)
            }
        }
    }
    SheetHeading("Sleep timer")
    ChoiceRow(SleepOptions, tweaks.sleep, ::sleepLabel, tweaks::updateSleep)
}

@Composable
private fun SheetHeading(text: String) {
    Spacer(Modifier.height(Space.xl))
    Text(text, style = AppType.heading)
    Spacer(Modifier.height(Space.sm))
}

/** Horizontally scrolling pills; the selected one sits on haze in full mist. */
@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(40.dp)
                    .clip(Radius.pill)
                    .background(if (on) Bg.overlay else Color.Transparent)
                    .clickable { onPick(o) }
                    .padding(horizontal = Space.md)
            ) {
                Text(label(o), style = Tabular.copy(color = if (on) Ink.high else Ink.muted))
            }
        }
    }
}

/** Overlay pill for gestures: icon + level / target. */
@Composable
internal fun HintPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = modifier.clip(Radius.pill).background(Scrim).padding(horizontal = Space.lg, vertical = Space.sm)
    ) {
        Icon(icon, contentDescription = null, tint = Ink.high, modifier = Modifier.size(20.dp))
        Text(text, style = AppType.label.copy(color = Ink.high, fontFeatureSettings = "tnum"))
    }
}
