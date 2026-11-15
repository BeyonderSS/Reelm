package com.anistream.core.trailer

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TrailerTest {
    @Test fun keyToQuery() {
        assertEquals("imdb=tt15239678", TrailerKey(imdb = "tt15239678").query())
        assertEquals("mal=16498&title=Attack+on+Titan&year=2013&type=series",
            TrailerKey(mal = 16498, title = " Attack on Titan ", year = 2013, type = "series").query())
        assertEquals("title=Am%C3%A9lie+%26+Co", TrailerKey(title = "Amélie & Co", imdb = "").query())
        assertEquals("", TrailerKey().query())
    }

    @Test fun walkSkipsDeadIds() = runBlocking {
        val tried = mutableListOf<String>()
        val got = firstPlayable(listOf("HJaUvV9Hwgs", "private", "U2Qp5pL3ovA", "never")) { id ->
            tried += id
            when (id) {
                "HJaUvV9Hwgs" -> throw IllegalStateException("Video unavailable")
                "private" -> null
                else -> "url:$id"
            }
        }
        assertEquals("url:U2Qp5pL3ovA", got)
        assertEquals(listOf("HJaUvV9Hwgs", "private", "U2Qp5pL3ovA"), tried)
        assertNull(firstPlayable(listOf("a", "b")) { null })
        assertNull(firstPlayable(emptyList<String>()) { "x" })
    }

    /** Live: real NewPipe resolve of the Dune Part Two trailer, then a ranged GET on the URL. Run with -Ptrailer.live. */
    @Test fun liveResolve() {
        assumeTrue(System.getProperty("trailer.live") == "true")
        val client = OkHttpClient()
        val yt = YouTube(client)
        val dead = runCatching { yt.resolve("HJaUvV9Hwgs", 720, false) }
        println("dead id HJaUvV9Hwgs -> ${dead.exceptionOrNull()?.javaClass?.simpleName ?: dead.getOrNull()?.url}")
        for ((cap, muxed) in listOf(720 to false, 480 to true)) {
            val t = System.currentTimeMillis()
            val s = yt.resolve("U2Qp5pL3ovA", cap, muxed)
            val ms = System.currentTimeMillis() - t
            assertNotNull(s)
            println("cap=$cap preferMuxed=$muxed -> ${s!!.height}p audio=${s.audioUrl != null} in $ms ms\n  ${s.url.take(120)}")
            assertTrue(s.height in 1..cap)
            for (u in listOfNotNull(s.url, s.audioUrl)) {
                client.newCall(Request.Builder().url(u).header("Range", "bytes=0-1023").build()).execute().use {
                    println("  GET range -> ${it.code} ${it.header("Content-Type")}")
                    assertTrue(it.code == 206 || it.code == 200)
                }
            }
        }
    }
}
