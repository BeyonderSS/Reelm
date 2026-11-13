package com.anistream.core.media.vega

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.anistream.domain.model.MediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class VegaEngineTest {
    private val client = OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build() // as MediaHttp
    private val engine = VegaEngine(client, { File("src/main/assets/vega-runtime.js").readText() }, ::println)

    @Test
    fun runtimeGlobals() = runBlocking {
        val out = engine.session(emptyMap()) {
            call(
                """[ctx.cheerio.load('<a href="/x">Hi</a>')('a').attr('href'), atob(btoa('ok')),
                   new URL('/p?q=1', 'https://h.io/a/').href, new URLSearchParams({a: 'b c'}).toString(),
                   new TextDecoder().decode(new TextEncoder().encode('é'))]"""
            )
        }.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("/x", "ok", "https://h.io/p?q=1", "a=b+c", "é"), out)
    }

    /** Providers run in parallel runtimes; one finishing must not break the host calls of those still running. */
    @Test
    fun closingOneRuntimeLeavesOthersWorking() = runBlocking {
        val out = engine.session(emptyMap()) {
            engine.session(emptyMap()) { call("1") }
            call("new Promise(r => setTimeout(() => r('ok'), 1))")
        }.jsonPrimitive.content
        assertEquals("ok", out)
    }

    /** A promise nobody handles must not take down the runtime's other pending host calls (browsers just log it). */
    @Test
    fun unhandledRejectionIsHarmless() = runBlocking {
        val out = engine.session(emptyMap()) {
            call(
                """(async () => {
                     Promise.reject(new Error('a')); new Promise((_, no) => no(new Error('b'))); fetch('http://invalid.invalid/').finally(() => {});
                     const late = Promise.reject(new Error('c')); await new Promise(r => setTimeout(r, 50));
                     let caught = await late.catch(e => e.message);
                     try { await fetch('http://invalid.invalid/') } catch (e) { caught += ' ' + (e && e.message ? 'msg' : 'null') }
                     return caught + ' ' + (Promise.resolve(1) instanceof Promise) })()"""
            )
        }.jsonPrimitive.content
        assertEquals("c msg true", out)
    }

    /** kvStore is per provider and outlives the runtime (and the process, via the state file), as in the Vega app. */
    @Test
    fun kvStorePersists() = runBlocking {
        val file = File.createTempFile("vega-state", ".json").apply { delete(); deleteOnExit() }
        val a = VegaEngine(client, { File("src/main/assets/vega-runtime.js").readText() }, ::println, file)
        a.session(emptyMap(), "netflixMirror") { call("ctx.kvStore.set('t', {token: 'x', ts: 1})") }
        assertEquals("x", a.session(emptyMap(), "netflixMirror") { call("ctx.kvStore.get('t').then(v => v.token)") }.jsonPrimitive.content)
        assertEquals("none", a.session(emptyMap(), "primeMirror") { call("ctx.kvStore.get('t').then(v => v === undefined ? 'none' : 'leak')") }.jsonPrimitive.content)
        val b = VegaEngine(client, { File("src/main/assets/vega-runtime.js").readText() }, ::println, file)
        assertEquals("x", b.session(emptyMap(), "netflixMirror") { call("ctx.kvStore.get('t').then(v => v.token)") }.jsonPrimitive.content)
    }

    /** Server cookies fill in what the provider's own Cookie header lacks; the provider's win on a clash; other hosts get the jar only. */
    @Test
    fun cookieJarMergesWithProviderCookies() {
        val jar = VegaEngine.Jar()
        val site = "https://net52.cc/mobile/home".toHttpUrl()
        jar.save(site, listOfNotNull(
            okhttp3.Cookie.parse(site, "t_hash_t=server; Max-Age=600; path=/; domain=net52.cc"),
            okhttp3.Cookie.parse(site, "addhash=old; Max-Age=600; path=/")
        ))
        val call = jar.forCall(site, "addhash=mine; hd=on")
        fun names(u: String) = call.loadForRequest(u.toHttpUrl()).joinToString("; ") { "${it.name}=${it.value}" }
        assertEquals("addhash=mine; hd=on; t_hash_t=server", names("https://net52.cc/mobile/verify2.php"))
        assertEquals("t_hash_t=server", names("https://userver.net52.cc/"))
        call.saveFromResponse(site, listOfNotNull(okhttp3.Cookie.parse(site, "hd=off; Max-Age=60; path=/")))
        assertEquals("addhash=mine; hd=off; t_hash_t=server", names("https://net52.cc/x"))
    }

    /** Live: hdhub4u search → meta → stream for Inception. Run with -Pvega.live. */
    @Test
    fun hdhub4uInception() = runBlocking {
        assumeTrue(System.getProperty("vega.live") == "true")
        val base = "https://cdn.jsdelivr.net/gh/Zenda-Cross/vega-providers@4ecbc98e12da870ffc06b41bd7affe4b1fe316f0/dist/hdhub4u"
        val files = listOf("posts", "meta", "stream").associateWith { f ->
            client.newCall(Request.Builder().url("$base/$f.js").build()).execute().use { it.body!!.string() }
        }
        val start = System.currentTimeMillis()
        val streams = engine.session(files) {
            val posts = call("posts.getSearchPosts({searchQuery:'Inception',page:1,providerValue:'hdhub4u',signal:new AbortController().signal,providerContext:ctx})").jsonArray
            println("posts: ${posts.size} ${posts.firstOrNull()}")
            val link = posts.first { "inception" in it.jsonObject["title"].toString().lowercase() }.jsonObject["link"]!!
            val meta = call("meta.getMeta({link:$link,provider:'hdhub4u',providerContext:ctx})").jsonObject
            val entry = (meta["linkList"] as JsonArray).map { it.jsonObject }.first { "1080" in it["title"].toString() }
            println("meta: ${meta["title"]} ${meta["type"]} -> $entry")
            val target = (entry["directLinks"] as? JsonArray)?.first()?.jsonObject?.get("link") ?: entry["episodesLink"]!!
            call("stream.getStream({link:$target,type:'movie',signal:new AbortController().signal,providerContext:ctx,isDownload:false})").jsonArray
        }
        println("streams in ${System.currentTimeMillis() - start} ms: $streams")
        assertTrue(streams.any { (it as JsonObject)["link"]!!.jsonPrimitive.content.startsWith("http") })
    }

    /** Live: the app's whole per-provider pipeline on the server's pinned commit + first 10 enabled. Run with -Pvega.live. */
    @Test
    fun pipeline() = runBlocking {
        assumeTrue(System.getProperty("vega.live") == "true")
        val reg = runCatching {
            client.newCall(Request.Builder().url("https://reelm-api.ssbeyonder.workers.dev/v1/sources").build()).execute()
                .use { VegaSources.parseRegistry(kotlinx.serialization.json.Json.parseToJsonElement(it.body!!.string()).jsonObject["vega"]) }
        }.getOrNull() ?: VegaSources.DEFAULT
        val base = "https://cdn.jsdelivr.net/gh/Zenda-Cross/vega-providers@${reg.commit}/dist"
        val titles = listOf(Quad("Spider-Man: No Way Home", 2021, 0, 0), Quad("Breaking Bad", 2008, 2, 3))
        val results = coroutineScope {
            reg.enabled.take(10).map { p ->
                async(Dispatchers.IO) {
                    val files = listOf("posts", "meta", "stream", "episodes").mapNotNull { f ->
                        client.newCall(Request.Builder().url("$base/$p/$f.js").build()).execute().use { r -> if (r.isSuccessful) f to r.body!!.string() else null }
                    }.toMap()
                    titles.map { (title, year, s, e) ->
                        val start = System.currentTimeMillis()
                        val out = runCatching {
                            withTimeout(60_000) {
                                engine.streams(files, p, p, title, year, if (s > 0) MediaType.SERIES else MediaType.MOVIE, s, e, lowEnd = false)
                            }
                        }
                        "$p $title: ${System.currentTimeMillis() - start} ms " + out.fold(
                            { l -> "${l.size} streams ${l.map { "${it.qualityLabel} ${it.url.take(60)}" }}" }, { "FAIL $it".take(500) })
                    }
                }
            }.awaitAll().flatten()
        }
        println("=== vega ${reg.commit}\n" + results.joinToString("\n"))
    }

    /** Live: the NetMirror providers unlock a session (~35 s ad-verify poll, then cached) and return HLS. Run with -Pvega.live. */
    @Test
    fun netMirrors() = runBlocking {
        assumeTrue(System.getProperty("vega.live") == "true")
        val base = "https://cdn.jsdelivr.net/gh/Zenda-Cross/vega-providers@988c7b6bfb3a6c8524f03c9dbb4c865a6c0e7168/dist"
        val cases = listOf(
            "netflixMirror" to Quad("Red Notice", 2021, 0, 0), "netflixMirror" to Quad("Stranger Things", 2016, 1, 1),
            "primeMirror" to Quad("The Tomorrow War", 2021, 0, 0), "primeMirror" to Quad("The Boys", 2019, 1, 1),
            "disneyMirror" to Quad("Loki", 2021, 1, 1)
        )
        val results = cases.map { (p, q) ->
            val files = listOf("posts", "meta", "stream", "episodes").mapNotNull { f ->
                client.newCall(Request.Builder().url("$base/$p/$f.js").build()).execute().use { r -> if (r.isSuccessful) f to VegaSources.patch(r.body!!.string()) else null }
            }.toMap()
            val start = System.currentTimeMillis()
            val out = runCatching {
                withTimeout(90_000) { engine.streams(files, p, p, q.a, q.b, if (q.c > 0) MediaType.SERIES else MediaType.MOVIE, q.c, q.d, lowEnd = false) }
            }
            val ms = System.currentTimeMillis() - start
            out.getOrNull()?.firstOrNull()?.let { s ->
                val code = client.newCall(Request.Builder().url(s.url).apply { s.headers.forEach { (k, v) -> header(k, v) } }.build()).execute()
                    .use { r -> "${r.code} ${r.body!!.string().lines().take(4)}" }
                println("$p ${q.a}: playlist -> $code")
            }
            "$p ${q.a}: $ms ms " + out.fold({ l -> "${l.size} streams ${l.map { "${it.qualityLabel} ${it.url.take(80)} ${it.headers.keys}" }}" }, { "FAIL $it".take(500) })
        }
        println("=== netmirror\n" + results.joinToString("\n"))
        assertTrue(results.joinToString(), results.all { "FAIL" !in it && !it.contains(" 0 streams") })
    }

    private data class Quad(val a: String, val b: Int, val c: Int, val d: Int)
}
