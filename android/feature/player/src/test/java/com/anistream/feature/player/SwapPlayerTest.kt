package com.anistream.feature.player

import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class SwapPlayerTest {
    /** An ExoPlayer that logs the calls it gets as "name" or "name:arg". */
    private fun fake(log: MutableList<String>) = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, m, a ->
        log += m.name + (a?.firstOrNull()?.let { ":${if (it is Player.Listener) "l" else it}" } ?: "")
        when (m.returnType) {
            Tracks::class.java -> Tracks.EMPTY
            VideoSize::class.java -> VideoSize.UNKNOWN
            Int::class.javaPrimitiveType -> Player.STATE_READY
            Boolean::class.javaPrimitiveType -> true
            Long::class.javaPrimitiveType -> 42L
            else -> null
        }
    } as ExoPlayer

    @Test
    fun listenersAndSurfaceFollowTheSwap() {
        val a = mutableListOf<String>(); val b = mutableListOf<String>()
        val swap = SwapPlayer(fake(a))
        val heard = mutableListOf<String>()
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { heard += "playing:$isPlaying" }
            override fun onPlaybackStateChanged(state: Int) { heard += "state:$state" }
        }
        swap.player.addListener(l)
        swap.player.setVideoSurface(null)
        assertEquals(42L, swap.player.currentPosition)
        assertEquals(listOf("addListener:l", "setVideoSurface", "getCurrentPosition"), a)
        swap.swap(fake(b))
        assertEquals(listOf("removeListener:l", "clearVideoSurface"), a.drop(3))
        assertEquals(listOf("addListener:l", "setVideoSurface"), b.take(2))
        assertEquals(listOf("state:${Player.STATE_READY}", "playing:true"), heard) // the new instance's state
        swap.player.pause()
        assertEquals("pause", b.last())
        assertEquals(swap.player, swap.player)
    }
}
