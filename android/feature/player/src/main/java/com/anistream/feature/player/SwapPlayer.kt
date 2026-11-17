package com.anistream.feature.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * One [Player] for the screens, the remote bridge and party sync while the ExoPlayer behind it can be replaced (the 4K
 * step-up): every call goes to the current [exo], and listeners plus the video output move over on [swap], so code that
 * captured the player once (effects, gesture handlers) keeps driving whichever instance is playing.
 */
@OptIn(UnstableApi::class)
internal class SwapPlayer(first: ExoPlayer) {
    var exo: ExoPlayer = first
        private set
    private val listeners = LinkedHashSet<Player.Listener>()
    /** the last setVideoSurface… / setVideoTextureView call, replayed on the next instance */
    private var output: Pair<Method, Array<Any?>>? = null

    val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, m, a ->
        val args = a ?: emptyArray()
        when (m.name) {
            "equals" -> return@newProxyInstance proxy === args[0]
            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
            "toString" -> return@newProxyInstance "SwapPlayer($exo)"
            "addListener" -> listeners += args[0] as Player.Listener
            "removeListener" -> listeners -= args[0] as Player.Listener
            "setVideoSurface", "setVideoSurfaceView", "setVideoSurfaceHolder", "setVideoTextureView" -> output = m to args
            "clearVideoSurface", "clearVideoSurfaceView", "clearVideoSurfaceHolder", "clearVideoTextureView" -> output = null
        }
        try { m.invoke(exo, *args) } catch (e: InvocationTargetException) { throw e.cause ?: e }
    } as Player

    /** Makes [next] the playing instance and returns the previous one (still to be released by the caller). */
    fun swap(next: ExoPlayer): ExoPlayer {
        val old = exo
        listeners.forEach { old.removeListener(it); next.addListener(it) }
        output?.let { (m, args) -> old.clearVideoSurface(); m.invoke(next, *args) }
        exo = next
        // listeners only hear changes from here on: give them the new instance's state
        listeners.toList().forEach { l ->
            l.onTracksChanged(next.currentTracks)
            l.onVideoSizeChanged(next.videoSize)
            l.onPlaybackStateChanged(next.playbackState)
            l.onIsPlayingChanged(next.isPlaying)
        }
        return old
    }
}
