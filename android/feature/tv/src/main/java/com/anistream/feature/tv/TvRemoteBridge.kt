package com.anistream.feature.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.Player
import com.anistream.core.account.HandoffHint
import com.anistream.core.account.Now
import kotlinx.coroutines.delay

/** players on screen; "left the player" is only sent when the last one goes (a handoff swaps one for another) */
private var bridges = 0

/**
 * Phone remote for the TV player on screen: reports what's playing (on play/pause/seek/item change and every 5 s while
 * playing, null when the player leaves) and obeys play/pause/toggle/seekTo/seekBy/next/prev/stop from the hub.
 * Also lands a handoff on the phone's exact position once the player is ready.
 */
@Composable
internal fun TvRemoteBridge(
    player: Player, title: String, subtitle: String?, poster: String?,
    hasNext: Boolean, hasPrev: Boolean, onNext: () -> Unit, onPrev: () -> Unit, onStop: () -> Unit
) {
    val hub = hiltViewModel<TvAccountViewModel>().hub
    val info = rememberUpdatedState(listOf(title, subtitle, poster))
    val nav = rememberUpdatedState(Triple(hasNext to hasPrev, onNext to onPrev, onStop))
    fun report() {
        val (t, sub, p) = info.value
        if (t.isNullOrBlank()) return
        val (hn, hp) = nav.value.first
        hub.sendState(Now(t.orEmpty(), sub, p, player.isPlaying, player.currentPosition.coerceAtLeast(0),
            player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0, hn, hp, System.currentTimeMillis()))
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
    LaunchedEffect(title, subtitle, hasNext, hasPrev) { report() }
    LaunchedEffect(player) { while (true) { delay(5_000); if (player.isPlaying) report() } }
    LaunchedEffect(player) {
        hub.controls.collect { c ->
            val (hn, hp) = nav.value.first
            val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            when (c.action) {
                "play" -> player.play()
                "pause" -> player.pause()
                "toggle" -> if (player.isPlaying) player.pause() else player.play()
                "seekTo" -> c.value?.let { player.seekTo(it.coerceIn(0, dur)) }
                "seekBy" -> c.value?.let { player.seekTo((player.currentPosition + it).coerceIn(0, dur)) }
                "next" -> if (hn) nav.value.second.first()
                "prev" -> if (hp) nav.value.second.second()
                "stop" -> { player.pause(); nav.value.third() }
            }
        }
    }
}
