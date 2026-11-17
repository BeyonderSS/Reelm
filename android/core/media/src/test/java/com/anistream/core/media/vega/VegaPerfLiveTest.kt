package com.anistream.core.media.vega

import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Live: per provider, how long `one()` takes (pipeline + playability probe) and what it finds, for the perf sample.
 * `-Pvega.live -Dvega.perf.out=<file>` writes the table. Run with -Pvega.live.
 */
class VegaPerfLiveTest {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val engine = VegaEngine(client, { File("src/main/assets/vega-runtime.js").readText() })
    private val commit = "70f69e58af852bb39b7a24a57a000622dfc35d0b"
    private val providers = listOf(
        "4khdhub", "hdhub4u", "vega", "drive", "autoEmbed", "castle", "movieBoxWeb", "1flex", "luxMovies", "movielinkdb", "mod",
        "showbox", "internetArchive", "moviezwap", "mxplayer", "eonMovies", "kmMovies", "zeefliz", "gokuHD", "world4u", "katmovies",
        "topmovies", "torrentio", "everything"
    )
    private data class T(val title: String, val year: Int, val s: Int, val e: Int)
    private val titles = listOf(T("Inception", 2010, 0, 0), T("Squid Game", 2021, 1, 1), T("Panchayat", 2020, 1, 1))

    private fun fetch(url: String) = client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body!!.string() else null }

    @Test
    fun perProvider(): Unit = runBlocking {
        assumeTrue(System.getProperty("vega.live") == "true")
        val files = providers.associateWith { p ->
            listOf("posts", "meta", "stream", "episodes").mapNotNull { f -> fetch("https://cdn.jsdelivr.net/gh/Zenda-Cross/vega-providers@$commit/dist/$p/$f.js")?.let { f to VegaSources.patch(it) } }.toMap()
        }
        val slots = Semaphore(4) // as the app: 4 providers at a time
        val rows = titles.flatMap { t ->
            providers.map { p ->
                async(Dispatchers.IO) {
                    slots.withPermit {
                        val t0 = System.currentTimeMillis()
                        val all = java.util.Collections.synchronizedList(mutableListOf<MediaStream>())
                        var first = 0L
                        // as VegaSources.one: each quality verified the moment it resolves, parts handed out as they pass
                        val r = withTimeoutOrNull(40_000) {
                            runCatching {
                                kotlinx.coroutines.coroutineScope {
                                    engine.streams(files[p]!!, p, p, t.title, t.year, if (t.s > 0) MediaType.SERIES else MediaType.MOVIE, t.s, t.e, lowEnd = false) { q ->
                                        launch {
                                            FanOut.verify(q, 1_200, { "download only" in it.qualityLabel.lowercase() || "video-downloads.googleusercontent.com" in it.url }, ::playable) {
                                                if (first == 0L) first = System.currentTimeMillis() - t0
                                                all += it
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val tRet = System.currentTimeMillis() - t0
                        "%-16s %-11s first=%6s done=%6d n=%2d %s".format(
                            p, t.title.take(11), if (first > 0) first else "-", tRet, all.size,
                            if (r == null) "TIMEOUT" else r.exceptionOrNull()?.let { "FAIL ${it.message?.take(60)}" }
                                ?: all.map { "${it.height}:${it.qualityLabel}" }.sorted().joinToString(",")
                        )
                    }
                }
            }
        }.awaitAll()
        val out = rows.joinToString("\n")
        println("=== perf\n$out")
        System.getProperty("vega.perf.out")?.let { File(it).writeText(out) }
    }

    private suspend fun playable(s: MediaStream): Boolean = !s.isDirectFile || withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(s.url).apply { s.headers.forEach { (k, v) -> header(k, v) } }.header("Range", "bytes=0-").build()
            client.newCall(req).execute().use { r -> (r.code == 200 || r.code == 206) && r.header("Content-Type").orEmpty().let { !it.contains("html") && !it.contains("json") && !it.contains("xml") } }
        }.getOrDefault(false)
    }
}
