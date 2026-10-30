package com.anistream.core.media.vega

import android.content.Context
import android.util.Log
import com.anistream.core.account.AccountApi
import com.anistream.core.common.LowEnd
import com.anistream.core.media.MediaHttp
import com.anistream.core.media.StreamSource
import com.anistream.core.media.Stremio
import com.anistream.core.media.fourkhdhub.FourKHdHubParser
import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.SubtitleTrack
import com.anistream.domain.usecase.canPlayLowEnd
import com.anistream.domain.usecase.stripTags
import com.anistream.domain.usecase.titleScore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vega providers (github.com/Zenda-Cross/vega-providers), run unchanged in QuickJS. Which commit and providers come
 * from the Reelm API's /v1/sources (compiled-in default when unreachable); files are only ever fetched from that
 * commit's jsDelivr URL and cached on disk per commit.
 */
@Singleton
class VegaSources @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: MediaHttp,
    private val api: AccountApi
) : StreamSource {

    private val engine = VegaEngine(http.client, { runtimeJs }, { Log.d("Vega", it) })
    private val runtimeJs by lazy { context.assets.open("vega-runtime.js").bufferedReader().use { it.readText() } }
    @Volatile private var registry: Registry? = null

    data class Registry(val commit: String, val enabled: List<String>)

    override suspend fun streams(title: String, year: Int?, type: MediaType, season: Int, episode: Int): List<MediaStream> {
        val reg = registry()
        val lowEnd = LowEnd.isOn(context)
        val providers = reg.enabled.take(if (lowEnd) 2 else 10) // ponytail: first 10 in parallel, same cap as web
        return coroutineScope {
            providers.map { p ->
                async {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        runCatching { provider(reg.commit, p, title, year, type, season, episode, lowEnd) }
                            .onFailure { Log.w("Vega", "$p: ${it.message}") }.getOrNull()
                    }.orEmpty()
                }
            }.awaitAll().flatten().let { all -> all.zip(all.map { async { playable(it) } }.awaitAll()).filter { it.second }.map { it.first } }
        }
    }

    /** Ask the way ExoPlayer does (open range): some hosts only serve bounded ranges and answer players with a 403 page. */
    private suspend fun playable(s: MediaStream): Boolean = !s.isDirectFile || withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(s.url).apply { s.headers.forEach { (k, v) -> header(k, v) } }.header("Range", "bytes=0-").build()
            http.client.newCall(req).execute().use { r -> (r.code == 200 || r.code == 206) && r.header("Content-Type").orEmpty().let { !it.contains("html") && !it.contains("json") && !it.contains("xml") } }
        }.getOrDefault(false)
    }

    private suspend fun provider(
        commit: String, p: String, title: String, year: Int?, type: MediaType, season: Int, episode: Int, lowEnd: Boolean
    ): List<MediaStream> {
        val files = listOfNotNull("posts", "meta", "stream", "episodes".takeIf { type == MediaType.SERIES })
            .mapNotNull { f -> file(commit, p, f)?.let { f to it } }.toMap()
        return engine.streams(files, p, DISPLAY[p] ?: p, title, year, type, season, episode, lowEnd)
    }

    /** Provider file from the pinned commit, disk-cached; null when that provider doesn't ship it (404). */
    private suspend fun file(commit: String, p: String, f: String): String? = withContext(Dispatchers.IO) {
        val cached = File(context.cacheDir, "vega/$commit/$p/$f.js")
        if (cached.exists()) return@withContext cached.readText().ifEmpty { null }
        val url = "$CDN@$commit/dist/$p/$f.js"
        val text = http.client.newCall(Request.Builder().url(url).build()).execute().use { res ->
            when {
                res.code == 404 -> ""
                !res.isSuccessful -> throw IOException("HTTP ${res.code} for $url")
                else -> res.body!!.string()
            }
        }
        cached.parentFile?.mkdirs()
        File(cached.path + ".tmp").apply { writeText(text) }.renameTo(cached)
        text.ifEmpty { null }
    }

    private suspend fun registry(): Registry {
        registry?.let { return it }
        val remote = if (!api.enabled) null else withTimeoutOrNull(REGISTRY_TIMEOUT_MS) {
            runCatching { parseRegistry(api.call("GET", "/v1/sources")?.get("vega")) }.getOrNull()
        }
        // ponytail: cached for the process; a commit bump reaches running apps on next launch.
        return remote?.also { registry = it } ?: DEFAULT
    }

    companion object {
        private const val CDN = "https://cdn.jsdelivr.net/gh/Zenda-Cross/vega-providers"
        private const val PROVIDER_TIMEOUT_MS = 14_000L // under MoviesRepositoryImpl's 15 s per-source budget
        private const val REGISTRY_TIMEOUT_MS = 3_000L
        val DEFAULT = Registry("4ecbc98e12da870ffc06b41bd7affe4b1fe316f0", listOf("hdhub4u", "drive", "vega", "mod", "topmovies"))
        private val DISPLAY = mapOf(
            "hdhub4u" to "HdHub4u", "drive" to "MoviesDrive", "vega" to "VMovies", "mod" to "MoviesMod", "topmovies" to "TopMovies"
        )

        /** Only a full commit sha on the official jsDelivr path is accepted; anything else falls back to [DEFAULT]. */
        fun parseRegistry(vega: JsonElement?): Registry? {
            val o = vega as? JsonObject ?: return null
            val commit = o["commit"]?.jsonPrimitive?.contentOrNull ?: return null
            if (!Regex("[0-9a-f]{40}").matches(commit)) return null
            if (o["base"]?.jsonPrimitive?.contentOrNull?.trimEnd('/') != "$CDN@$commit/dist") return null
            val enabled = (o["enabled"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { Regex("[a-z0-9]+").matches(it) } ?: return null
            return Registry(commit, enabled)
        }
    }
}

/** One provider's pipeline: search → best post → meta → season/episode → up to two qualities → streams. */
internal suspend fun VegaEngine.streams(
    files: Map<String, String>, p: String, name: String, title: String, year: Int?, type: MediaType, season: Int, episode: Int,
    lowEnd: Boolean
): List<MediaStream> {
    val series = type == MediaType.SERIES
    return session(files) {
        val q = JsonPrimitive(Stremio.searchQuery(title))
        val posts = call("posts.getSearchPosts({searchQuery:$q,page:1,providerValue:${JsonPrimitive(p)},signal:new AbortController().signal,providerContext:ctx})")
            .jsonArray.map { it.jsonObject }
        val post = VegaPick.post(title, year, series, season, posts) ?: return@session emptyList()
        val meta = call("meta.getMeta({link:${post["link"]},provider:${JsonPrimitive(p)},providerContext:ctx})").jsonObject
        val entries = (meta["linkList"] as? JsonArray).orEmpty().map { it.jsonObject }
            .let { if (series) VegaPick.forSeason(it, season) else it }
        val picks = VegaPick.qualities(entries, lowEnd).mapNotNull { entry ->
            runCatching {
                if (series) {
                    val eps = (entry["directLinks"] as? JsonArray)?.map { it.jsonObject }
                        ?: entry["episodesLink"]?.let { call("episodes.getEpisodes({url:$it,providerContext:ctx})").jsonArray.map { e -> e.jsonObject } }
                    eps?.let { VegaPick.episode(it, season, episode) }?.get("link")
                } else {
                    (entry["directLinks"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("link")
                        ?: entry["episodesLink"]?.let { call("episodes.getEpisodes({url:$it,providerContext:ctx})").jsonArray.firstOrNull()?.jsonObject?.get("link") }
                }
            }.getOrNull()?.let { entry to it }
        }
        if (picks.isEmpty()) return@session emptyList()
        // Both qualities resolve concurrently inside the one runtime; a failed one comes back as [].
        val kind = JsonPrimitive(if (series) "series" else "movie")
        val links = JsonArray(picks.map { it.second })
        call("Promise.all($links.map(link => stream.getStream({link,type:$kind,signal:new AbortController().signal,providerContext:ctx,isDownload:false}).catch(() => [])))")
            .jsonArray.zip(picks).flatMap { (streams, pick) ->
                (streams as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { s -> VegaPick.toStream(s, pick.first, name) } }
            }
    }
}

/** Pure choices over provider JSON: which post, season entries, qualities and episode. */
internal object VegaPick {
    private val YEAR = Regex("""\b(19|20)\d{2}\b""")
    private val CUT = Regex("""[(\[{|]|\b(?:19|20)\d{2}\b|\bseason\b|\bS\d{1,2}\b""", RegexOption.IGNORE_CASE)
    private val SEASON_RANGE = Regex("""(?i)\bseasons?\s*:?\s*(\d{1,2})(?:\s*(?:-|–|to)\s*(\d{1,2}))?""")
    private val SEASON_LIST = Regex("""(?i)\bseasons?\s*:?\s*(\d{1,2}(?:\s*\+\s*\d{1,2})+)""")
    private val S_TAG = Regex("""(?i)\bS(\d{1,2})(?:\s*(?:-|–)\s*S?(\d{1,2}))?(?:E\d+)?\b""")
    private val EPISODE = Regex("""(?i)(?:\bepisode|\bep|\bE)\s*\.?\s*0*(\d{1,4})\b""")
    private val SXE = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,4})\b""")

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** "Breaking Bad (Season 1 - 5) Dual Audio…" → "Breaking Bad". */
    fun cleanTitle(t: String): String {
        val s = stripTags(t).replace(Regex("(?i)^\\s*download\\s+"), "")
        return s.substring(0, CUT.find(s)?.range?.first ?: s.length).trim()
    }

    /** Seasons a title covers ("Season 1-7", "S01", "Season: 1 +2+3"); empty when it doesn't say. */
    fun seasons(t: String): Set<Int> {
        SEASON_LIST.find(t)?.let { m -> return m.groupValues[1].split('+').map { it.trim().toInt() }.toSet() }
        val m = SEASON_RANGE.find(t) ?: S_TAG.find(t) ?: return emptySet()
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toIntOrNull() ?: a
        return (minOf(a, b)..maxOf(a, b)).toSet()
    }

    fun post(title: String, year: Int?, series: Boolean, season: Int, posts: List<JsonObject>): JsonObject? = posts
        .map { it to cleanTitle(it.str("title")) }
        .filter { (_, clean) -> clean.isNotEmpty() && minOf(titleScore(title, clean), titleScore(clean, title)) >= 0.6 }
        .filter { (p, _) ->
            val t = p.str("title")
            val y = YEAR.find(t)?.value?.toInt()
            if (series) seasons(t).let { it.isEmpty() || season in it } else year == null || y == null || kotlin.math.abs(y - year) <= 1
        }
        .maxByOrNull { (p, clean) ->
            titleScore(title, clean) + titleScore(clean, title) + (if (series && season in seasons(p.str("title"))) 0.5 else 0.0)
        }?.first

    /** linkList entries for [season]; all of them when none says which season it is. */
    fun forSeason(entries: List<JsonObject>, season: Int): List<JsonObject> {
        val tagged = entries.filter { seasons(it.str("title")).isNotEmpty() }
        return if (tagged.isEmpty()) entries else tagged.filter { season in seasons(it.str("title")) }
    }

    fun height(e: JsonObject) = Stremio.parseHeight("${e.str("quality")} ${e.str("title")}")

    /** Up to two entries: the best the device plays, plus one near 720p. */
    fun qualities(entries: List<JsonObject>, lowEnd: Boolean): List<JsonObject> {
        val ok = entries.filter { e ->
            (e["directLinks"] as? JsonArray)?.isNotEmpty() == true || e["episodesLink"] != null
        }.filter { e -> !lowEnd || canPlayLowEnd(MediaStream(url = "", qualityLabel = e.str("title"), height = height(e), sourceName = "")) }
        val best = ok.maxByOrNull { height(it) } ?: return emptyList()
        val mid = ok.filter { it !== best && height(it) in 1 until height(best) }.minByOrNull { kotlin.math.abs(height(it) - 720) }
        return listOfNotNull(best, mid)
    }

    fun episode(eps: List<JsonObject>, season: Int, episode: Int): JsonObject? = eps.firstOrNull { e ->
        val t = e.str("title")
        SXE.find(t)?.let { it.groupValues[1].toInt() == season && it.groupValues[2].toInt() == episode }
            ?: (EPISODE.find(t)?.groupValues?.get(1)?.toInt() == episode)
    }

    fun toStream(s: JsonObject, entry: JsonObject, provider: String): MediaStream? {
        val url = s.str("link").takeIf { it.startsWith("http") } ?: return null
        val type = s.str("type").lowercase()
        val m3u8 = type == "m3u8" || url.substringBefore('?').endsWith(".m3u8")
        val h = s.str("quality").let { q -> q.toIntOrNull()?.takeIf { it in 144..4320 } ?: Stremio.parseHeight(q) }
            .takeIf { it > 0 } ?: height(entry)
        val headers = mapOf("User-Agent" to VegaEngine.USER_AGENT) +
            (s["headers"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        val title = entry.str("title")
        val codec = FourKHdHubParser.codec(title)
        // Providers' server names carry filler ("Unknown · CF Worker", "1080p · Pixeldrain"); the height is appended anyway.
        val server = s.str("server").split(" · ").filter { it.isNotBlank() && it != "Unknown" && !Regex("\\d{3,4}p").matches(it) }
        val label = (server + listOfNotNull(if (h > 0) "${h}p" else null)).joinToString(" ")
        return MediaStream(
            url = url,
            qualityLabel = "$provider · $label".trimEnd(' ', '·'),
            height = h,
            codec = if (codec != null && LowEnd.isHighBitDepth(title)) "$codec 10bit" else codec,
            headers = headers,
            subtitles = (s["subtitles"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { t ->
                val uri = t.str("uri").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val lang = t.str("language")
                SubtitleTrack(uri, lang, t.str("title").ifEmpty { lang }, t.str("type").ifEmpty {
                    if (uri.substringBefore('?').endsWith(".vtt")) "text/vtt" else "application/x-subrip"
                })
            },
            sourceName = provider,
            isDirectFile = !m3u8,
            mimeType = if (m3u8) "application/x-mpegURL" else null
        )
    }
}
