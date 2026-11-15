package com.anistream.feature.tv

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.Player
import com.anistream.core.account.Control
import com.anistream.core.account.HandoffHint
import com.anistream.core.account.Now
import com.anistream.core.account.RemoteInfo
import com.anistream.core.account.SkipNow
import com.anistream.feature.player.PlaybackSpeeds
import com.anistream.feature.player.SubPosition
import com.anistream.feature.player.SubtitleScales
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/** players on screen; "left the player" is only sent when the last one goes (a handoff swaps one for another) */
internal var bridges = 0

/** What a player screen gives the remote beyond the chrome's own state: now-playing basics and its own pickers. */
internal class RemoteHooks(
    val title: String, val subtitle: String?, val poster: String?,
    val hasNext: Boolean, val hasPrev: Boolean, val onNext: () -> Unit, val onPrev: () -> Unit, val onStop: () -> Unit,
    val season: Int? = null, val episode: Int? = null, val payload: JsonObject? = null,
    val quality: RemoteInfo.Quality = RemoteInfo.Quality(), val onQuality: (Int) -> Unit = {},
    val sources: List<RemoteInfo.Source> = emptyList(), val source: String? = null, val onSource: (Int) -> Unit = {},
    val onEpisode: (season: Int, episode: Int) -> Unit = { _, _ -> },
    /** anime: sub/dub offered as audio languages ("ja"/"en"); null = the chrome's own audio-language list */
    val langs: List<String>? = null, val lang: String? = null, val onLang: ((String?) -> Unit)? = null,
)

/**
 * Phone remote for the TV player on screen: reports what's playing (on play/pause/seek/item change, whenever [info]'s rev
 * moves, and every 5 s while playing; null when the player leaves), answers `describe` with [info], obeys
 * play/pause/toggle/seekTo/seekBy/next/prev/stop and hands every other player action to [onAction].
 * Also lands a handoff on the phone's exact position once the player is ready.
 */
@Composable
internal fun TvRemoteBridge(player: Player, hooks: RemoteHooks, info: RemoteInfo, skip: SkipNow?, countdownEnd: Long?, onAction: (Control) -> Unit) {
    val hub = hiltViewModel<TvAccountViewModel>().hub
    val cur = rememberUpdatedState(Triple(hooks, info, skip to countdownEnd))
    val act = rememberUpdatedState(onAction)
    fun report() {
        val (h, i, sc) = cur.value
        if (h.title.isBlank()) return
        val t = System.currentTimeMillis()
        hub.sendState(Now(h.title, h.subtitle, h.poster, player.isPlaying, player.currentPosition.coerceAtLeast(0),
            player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0, h.hasNext, h.hasPrev, t,
            i.rev, sc.first, sc.second?.let { (it - t).coerceAtLeast(0) }))
    }
    DisposableEffect(player) {
        bridges++
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && p.playbackState == Player.STATE_READY && p.duration > 0)
                    HandoffHint.takeSeek()?.takeIf { it > 0 }?.let { p.seekTo(it.coerceAtMost(p.duration - 1000)) }
                if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED)) report()
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); if (--bridges == 0) hub.sendState(null) }
    }
    LaunchedEffect(hooks.title, hooks.subtitle, hooks.hasNext, hooks.hasPrev, info.rev, skip, countdownEnd) { report() }
    LaunchedEffect(player) { while (true) { delay(5_000); if (player.isPlaying) report() } }
    LaunchedEffect(player) {
        hub.controls.collect { c ->
            val h = cur.value.first
            val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            when (c.action) {
                "play" -> player.play()
                "pause" -> player.pause()
                "toggle" -> if (player.isPlaying) player.pause() else player.play()
                "seekTo" -> c.value?.let { player.seekTo(it.coerceIn(0, dur)) }
                "seekBy" -> c.value?.let { player.seekTo((player.currentPosition + it).coerceIn(0, dur)) }
                "next" -> if (h.hasNext) h.onNext()
                "prev" -> if (h.hasPrev) h.onPrev()
                "stop" -> { player.pause(); h.onStop() }
                "describe" -> if (c.from.isNotEmpty()) hub.sendInfo(c.from, cur.value.second.toJson())
                "key", "volume" -> Unit // TvActivity: they work on every screen
                else -> act.value(c)
            }
        }
    }
}

/** Remote v2 argument ↔ player setting mappings (pure, unit-tested). */
internal object RemoteMap {
    private val sizes = listOf("s", "m", "l", "xl")
    private val positions = listOf("bottom" to SubPosition.BOTTOM, "raised" to SubPosition.RAISED, "higher" to SubPosition.HIGHER, "top" to SubPosition.TOP)

    fun sizeCode(scale: Float) = sizes.getOrElse(SubtitleScales.indexOf(scale)) { "m" }
    fun scale(arg: JsonElement?) = SubtitleScales.getOrNull(sizes.indexOf(string(arg)))
    fun posCode(p: SubPosition) = positions.first { it.second == p }.first
    fun position(arg: JsonElement?) = positions.firstOrNull { it.first == string(arg) }?.second
    fun percent(speed: Float) = Math.round(speed * 100)
    /** the offered speed nearest [percent] */
    fun speed(percent: Long) = PlaybackSpeeds.minBy { abs(it * 100 - percent) }
    fun string(arg: JsonElement?) = (arg as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    fun field(arg: JsonElement?, k: String) = (arg as? JsonObject)?.get(k)
    /** `{track: "3"}` → 3, `{track: null}` → -1 (off), anything else → null (ignored) */
    fun track(arg: JsonElement?): Int? = field(arg, "track").let { if (it == null) null else if (it is JsonNull) -1 else string(it)?.toIntOrNull() }
    fun episode(arg: JsonElement?): Pair<Int, Int>? {
        val s = (field(arg, "season") as? JsonPrimitive)?.intOrNull ?: 1 // anime has no seasons
        val e = (field(arg, "episode") as? JsonPrimitive)?.intOrNull ?: return null
        return (s to e).takeIf { e > 0 && s >= 0 }
    }
    /** anime quality the viewer picked (0 = Auto: still on the auto pick); autoNote is never empty, so it can't tell */
    fun pickedHeight(heights: Map<Int, List<String>>, selected: String, auto: String) =
        if (selected == auto) 0 else heights.entries.firstOrNull { selected in it.value }?.key ?: 0
    /** "1080p" / "720p HD" → 1080 / 720; 0 when the label has no height */
    fun heightOf(label: String) = Regex("""(\d{3,4})p""").find(label)?.groupValues?.get(1)?.toInt() ?: 0
}

/** TV speaker / HDMI volume (best effort: Fire TV over HDMI-CEC may ignore it). */
internal fun remoteVolume(ctx: Context): RemoteInfo.Volume? = runCatching {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val level = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    RemoteInfo.Volume(level, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        if (Build.VERSION.SDK_INT >= 23) am.isStreamMute(AudioManager.STREAM_MUSIC) else level == 0)
}.getOrNull()

/** `volume` arg "up"/"down"/"mute"/"unmute", or value = absolute level. */
@Suppress("DEPRECATION")
internal fun applyVolume(ctx: Context, c: Control) {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val s = AudioManager.STREAM_MUSIC
    val ui = AudioManager.FLAG_SHOW_UI
    runCatching {
        when (RemoteMap.string(c.arg)) {
            "up" -> am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, ui)
            "down" -> am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, ui)
            "mute", "unmute" -> (RemoteMap.string(c.arg) == "mute").let { m ->
                if (Build.VERSION.SDK_INT >= 23) am.adjustStreamVolume(s, if (m) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, ui) else am.setStreamMute(s, m)
            }
            else -> c.value?.let { am.setStreamVolume(s, it.toInt().coerceIn(0, am.getStreamMaxVolume(s)), ui) }
        }
    }
}
