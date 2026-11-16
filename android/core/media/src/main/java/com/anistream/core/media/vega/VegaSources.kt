package com.anistream.core.media.vega

import android.content.Context
import android.util.Log
import com.anistream.core.account.AccountApi
import com.anistream.core.common.LowEnd
import com.anistream.core.media.MediaHttp
import com.anistream.core.media.ReleaseLanguages
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    private val engine = VegaEngine(http.client, { runtimeJs }, { Log.d("Vega", it) }, File(context.noBackupFilesDir, "vega-state.json"))
    private val runtimeJs by lazy { context.assets.open("vega-runtime.js").bufferedReader().use { it.readText() } }
    @Volatile private var registry: Registry? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class Registry(val commit: String, val enabled: List<String>)

    /** No one to hand late results to: wait for all providers (40 s at most), never return early. */
    override suspend fun streams(title: String, year: Int?, type: MediaType, season: Int, episode: Int) = lookup(title, year, type, season, episode, null)

    override suspend fun streams(title: String, year: Int?, type: MediaType, season: Int, episode: Int, late: suspend (List<MediaStream>) -> Unit) =
        lookup(title, year, type, season, episode, late)

    /**
     * Every title provider, most reliable on this device first, 4 at a time (2 on a low-end TV). Returns once 3 sources at
     * 1080p (720p low-end) or 5 of any are in, or after 40 s; the rest keep going 2 at a time (1 low-end) into [late], 60 s at most.
     */
    private suspend fun lookup(title: String, year: Int?, type: MediaType, season: Int, episode: Int, late: (suspend (List<MediaStream>) -> Unit)?): List<MediaStream> {
        val reg = registry()
        val lowEnd = LowEnd.isOn(context)
        val kinds = runCatching { kinds(reg.commit) }.getOrDefault(emptyMap())
        val now = System.currentTimeMillis()
        val stats = context.getSharedPreferences("src_stats", Context.MODE_PRIVATE) // SourceRank's, keyed by source name
        val providers = FanOut.order(reg.enabled.filter { kindOf(it, kinds[it]) !in OFF_TITLE && !FanOut.tripped(breakers.getString(it, null), now) }) {
            FanOut.reliability(stats.getString(DISPLAY[it] ?: it, null), now)
        }
        val target = if (lowEnd) 720 else 1080
        Log.d("Vega", "asking ${providers.size}: ${providers.joinToString(" ")}")
        return FanOut.run(providers, background, if (lowEnd) 2 else 4, if (lowEnd) 1 else 2, PROVIDER_TIMEOUT_MS, DEADLINE_MS, { late != null && FanOut.enough(it, target, MediaStream::height) }, late ?: {}) { p ->
            one(reg.commit, p, title, year, type, season, episode, lowEnd)
        }.also { Log.d("Vega", "returned ${it.size} after ${System.currentTimeMillis() - now} ms") }
    }

    private val breakers = context.getSharedPreferences("vega_breaker", Context.MODE_PRIVATE)

    private fun breaker(p: String, failed: Boolean) {
        val old = breakers.getString(p, null)
        val n = FanOut.nextBreaker(old, failed, System.currentTimeMillis())
        if (n != old) breakers.edit().apply { if (n == null) remove(p) else putString(p, n) }.apply()
        if (n != null && FanOut.tripped(n, System.currentTimeMillis())) Log.w("Vega", "$p: search failed $TRIPS times in a row, skipped for 6 h")
    }

    /** One provider: its playable streams, never throws. */
    private suspend fun one(commit: String, p: String, title: String, year: Int?, type: MediaType, season: Int, episode: Int, lowEnd: Boolean): List<MediaStream> {
        var searched = false
        // the probe sits inside the provider's budget: probing after the slowest provider pushed the whole
        // Vega source past the repository's per-source timeout, and every provider's streams were lost
        return withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            runCatching {
                val all = if (p !in UNLOCKS) provider(commit, p, title, year, type, season, episode, lowEnd) { searched = true }
                // NetMirror's first lookup in 12 h spends ~35 s unlocking a session; past our budget it keeps
                // going here so the token it earns is saved and the next lookup takes ~2 s
                else background.async { withTimeout(UNLOCK_MS) { provider(commit, p, title, year, type, season, episode, lowEnd) { searched = true } } }.await()
                coroutineScope { all.zip(all.map { async { playable(it) } }.awaitAll()).filter { it.second }.map { it.first } }
            }.onFailure {
                Log.w("Vega", "$p: ${it.message}")
                if (it is VegaEngine.SearchFailed) breaker(p, true)
            }.getOrNull()
        }.orEmpty().also {
            if (searched) breaker(p, false)
            Log.d("Vega", "$p: ${it.size} playable")
        }
    }

    /**
     * NetMirror's session token (t_hash_t, 12 h) costs a ~35 s ad-verify poll, so the first mirror lookup after a cold
     * start used to come back empty. Earn it in the background at app start and again before it expires. Not on a
     * low-end TV: a second QuickJS runtime there competes with the UI for ~1 GB.
     */
    fun prewarm() {
        if (LowEnd.isOn(context)) return
        background.launch { while (true) delay(runCatching { warm() }.onFailure { Log.w("Vega", "pre-warm: ${it.message}") }.getOrDefault(RETRY_MS)) }
    }

    /** Unlocks when the token is missing or near expiry; returns how long until the next check. */
    private suspend fun warm(): Long {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION") if (cm.activeNetworkInfo?.isConnected != true) return RETRY_MS
        val reg = registry()
        val p = UNLOCKS.firstOrNull { it in reg.enabled } ?: return REFRESH_MS
        fun age() = System.currentTimeMillis() - (engine.kv(p, "t_hash_t_data")?.let {
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["ts"]?.jsonPrimitive?.content?.toLong() }.getOrNull()
        } ?: 0L)
        if (age() < REFRESH_MS) return REFRESH_MS - age()
        val t0 = System.currentTimeMillis()
        val files = listOf("posts").mapNotNull { f -> file(reg.commit, p, f)?.let { f to patch(it) } }.toMap()
        withTimeout(UNLOCK_MS) {
            engine.session(files, p) { call("posts.getSearchPosts({searchQuery:'Red Notice',page:1,providerValue:${JsonPrimitive(p)},signal:new AbortController().signal,providerContext:ctx})") }
        }
        val ok = age() < REFRESH_MS
        Log.d("Vega", "pre-warm: $p ${if (ok) "unlocked" else "still locked"} in ${System.currentTimeMillis() - t0} ms")
        return if (ok) REFRESH_MS else RETRY_MS
    }

    /** Ask the way ExoPlayer does (open range): some hosts only serve bounded ranges and answer players with a 403 page. */
    private suspend fun playable(s: MediaStream): Boolean = !s.isDirectFile || withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(s.url).apply { s.headers.forEach { (k, v) -> header(k, v) } }.header("Range", "bytes=0-").build()
            http.client.newCall(req).execute().use { r -> (r.code == 200 || r.code == 206) && r.header("Content-Type").orEmpty().let { !it.contains("html") && !it.contains("json") && !it.contains("xml") } }
        }.getOrDefault(false)
    }

    private suspend fun provider(
        commit: String, p: String, title: String, year: Int?, type: MediaType, season: Int, episode: Int, lowEnd: Boolean, searched: () -> Unit
    ): List<MediaStream> {
        // movies need episodes.js too: NetMirror's meta calls a film with seasons "series" and lists it via episodesLink
        val files = listOf("posts", "meta", "stream", "episodes")
            .mapNotNull { f -> file(commit, p, f)?.let { f to patch(it) } }.toMap()
        return engine.streams(files, p, DISPLAY[p] ?: p, title, year, type, season, episode, lowEnd, searched)
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

    /** provider → manifest type at [commit], disk-cached like the provider files. */
    private suspend fun kinds(commit: String): Map<String, String> = withContext(Dispatchers.IO) {
        val cached = File(context.cacheDir, "vega/$commit/manifest.json")
        val text = if (cached.exists()) cached.readText() else http.client.newCall(Request.Builder().url("$CDN@$commit/manifest.json").build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} for manifest")
            res.body!!.string().also { cached.parentFile?.mkdirs(); File(cached.path + ".tmp").apply { writeText(it) }.renameTo(cached) }
        }
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray.associate {
            it.jsonObject["value"]!!.jsonPrimitive.content to (it.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "global")
        }
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
        private const val PROVIDER_TIMEOUT_MS = 40_000L // under MoviesRepositoryImpl's 45 s per-source budget (slow networks)
        private const val REGISTRY_TIMEOUT_MS = 3_000L
        private const val UNLOCK_MS = 90_000L
        private const val DEADLINE_MS = 60_000L // nothing of one lookup runs longer (but a NetMirror unlock, which is saved)
        private const val REFRESH_MS = 11 * 3_600_000L // re-unlock an hour before the 12 h token expires
        private const val RETRY_MS = 15 * 60_000L
        private const val TRIPS = 3
        private val UNLOCKS = setOf("netflixMirror", "primeMirror", "disneyMirror")
        val DEFAULT = Registry("4ecbc98e12da870ffc06b41bd7affe4b1fe316f0", listOf("hdhub4u", "drive", "vega", "mod", "topmovies"))
        /** The manifest's type, corrected where it says "global" for a niche catalog (same table as web vega.ts). */
        private val KIND_FIX = mapOf("dramacool" to "kdrama", "kissKh" to "kdrama", "durbinLive" to "live", "liveTgTv" to "live", "youtube" to "video")
        fun kindOf(p: String, type: String?) = KIND_FIX[p] ?: type ?: "global"
        /** Never asked for a movie or series: anime (anime titles have their own sources), live TV, YouTube. */
        val OFF_TITLE = setOf("anime", "live", "video")
        private val DISPLAY = mapOf(
            "hdhub4u" to "HdHub4u", "drive" to "MoviesDrive", "vega" to "VMovies", "mod" to "MoviesMod", "topmovies" to "TopMovies"
        )

        /**
         * NetMirror's search always asks /mobile/search.php, which answers from the Netflix catalog whatever the ott
         * cookie says; Prime and Disney+ titles are only found at /mobile/pv/ and /mobile/hs/search.php. Without this
         * primeMirror matched Netflix ids and got playlists with broken audio URIs ("https:///files/…").
         * ponytail: string patch on the compiled provider; drop it once vega-providers searches the ott's own path.
         */
        fun patch(code: String) = code.replace("/mobile/search.php?s=", "/mobile/\${prefix?prefix+\"/\":\"\"}search.php?s=")

        /** Only a full commit sha on the official jsDelivr path is accepted; anything else falls back to [DEFAULT]. */
        fun parseRegistry(vega: JsonElement?): Registry? {
            val o = vega as? JsonObject ?: return null
            val commit = o["commit"]?.jsonPrimitive?.contentOrNull ?: return null
            if (!Regex("[0-9a-f]{40}").matches(commit)) return null
            if (o["base"]?.jsonPrimitive?.contentOrNull?.trimEnd('/') != "$CDN@$commit/dist") return null
            val enabled = (o["enabled"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { Regex("[A-Za-z0-9]+").matches(it) } ?: return null // camelCase too: autoEmbed, movieBoxWeb, kissKh… were silently dropped
            return Registry(commit, enabled)
        }
    }
}

/** One provider's pipeline: search → best post → meta → season/episode → up to two qualities → streams. */
internal suspend fun VegaEngine.streams(
    files: Map<String, String>, p: String, name: String, title: String, year: Int?, type: MediaType, season: Int, episode: Int,
    lowEnd: Boolean, searched: () -> Unit = {}
): List<MediaStream> {
    val series = type == MediaType.SERIES
    return session(files, p, if (lowEnd) LOW_END_HEAP else 0) {
        val q = JsonPrimitive(Stremio.searchQuery(title))
        val posts = try {
            call("posts.getSearchPosts({searchQuery:$q,page:1,providerValue:${JsonPrimitive(p)},signal:new AbortController().signal,providerContext:ctx})")
                .jsonArray.map { it.jsonObject }
        } catch (e: VegaEngine.VegaException) { throw VegaEngine.SearchFailed(e.message) }
        if (posts.isEmpty() && httpFails > 0) throw VegaEngine.SearchFailed("$httpFails search requests failed")
        searched()
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
        // the Vega app passes the meta's own type (its player route carries info.type), not ours
        val kind = (meta["type"] as? JsonPrimitive)?.takeIf { it.isString } ?: JsonPrimitive(if (series) "series" else "movie")
        val links = JsonArray(picks.map { it.second })
        call("Promise.all($links.map(link => stream.getStream({link,type:$kind,signal:new AbortController().signal,providerContext:ctx,isDownload:false}).catch(() => [])))")
            .jsonArray.zip(picks).flatMap { (streams, pick) ->
                val langs = ReleaseLanguages.parse("${(post["title"] as? JsonPrimitive)?.contentOrNull} ${(pick.first["title"] as? JsonPrimitive)?.contentOrNull}", title)
                (streams as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { s -> VegaPick.toStream(s, pick.first, name)?.copy(languages = langs) } }
            }
    }
}

/** QuickJS heap per provider runtime on low-end TVs (2 run at once): pages are capped at 8 MB, cheerio's DOM of one stays well under. */
private const val LOW_END_HEAP = 96L * 1024 * 1024

/** Pure choices over provider JSON: which post, season entries, qualities and episode. */
internal object VegaPick {
    private val YEAR = Regex("""\b(19|20)\d{2}\b""")
    private val CUT = Regex("""[(\[{|]|\b(?:19|20)\d{2}\b|\bseason\b|\bS\d{1,2}\b""", RegexOption.IGNORE_CASE)
    private val SEASON_RANGE = Regex("""(?i)\bseasons?\s*:?\s*(\d{1,2})(?:\s*(?:-|–|to)\s*(\d{1,2}))?""")
    private val SEASON_LIST = Regex("""(?i)\bseasons?\s*:?\s*(\d{1,2}(?:\s*\+\s*\d{1,2})+)""")
    private val S_TAG = Regex("""(?i)\bS(\d{1,2})(?:\s*(?:-|–)\s*S?(\d{1,2}))?(?:E\d+)?\b""")
    // "Episode 12", "Episode-01 2.9 GB" (4khdhub), "E05", or numbered titles "11. White Lines" (autoEmbed)
    private val EPISODE = Regex("""(?i)(?:\bepisode|\bep|\bE)[\s._-]*0*(\d{1,4})\b|^\s*0*(\d{1,4})\b""")
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
        .filter { (_, clean) -> clean.isNotEmpty() && minOf(titleScore(title, clean), titleScore(clean, title)) >= 0.6 && sequel(clean) == sequel(title) }
        .filter { (p, _) ->
            val t = p.str("title")
            val y = postYear(p)
            if (series) seasons(t).let { it.isEmpty() || season in it } else year == null || y == null || kotlin.math.abs(y - year) <= 1
        }
        .maxByOrNull { (p, clean) ->
            // NetMirror lists "The Boys" the 2024 film and "The Boys" the 2019 series under one name; its tag has the year
            val sameYear = year != null && postYear(p)?.let { kotlin.math.abs(it - year) <= 1 } == true
            titleScore(title, clean) + titleScore(clean, title) + (if (series && season in seasons(p.str("title"))) 0.5 else 0.0) +
                (if (sameYear) 0.25 else 0.0)
        }?.first

    private val SEQUEL_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
        .withIndex().associate { (i, w) -> w to i + 1 } +
        listOf("first", "second", "third", "fourth", "fifth").withIndex().associate { (i, w) -> w to i + 1 } +
        listOf("ii", "iii", "iv", "v", "vi", "vii", "viii", "ix").withIndex().associate { (i, w) -> w to i + 2 }

    /**
     * Which instalment a title names ("Part Two", "3", "Vol. 2", "III"); 1 when it names none. Title similarity alone
     * let "Dune: Part Two" stand in for "Dune: Part One" when the year was unknown. Lone "i"/"x" aren't counted (X-Men).
     */
    fun sequel(t: String): Int = t.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .firstNotNullOfOrNull { SEQUEL_WORDS[it] ?: it.toIntOrNull()?.takeIf { n -> n in 2..99 } } ?: 1

    /** Year in the post's title, else its tag when that is a bare year (NetMirror). */
    private fun postYear(p: JsonObject) = (YEAR.find(p.str("title")) ?: YEAR.matchEntire(p.str("tag").trim()))?.value?.toInt()

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
            ?: (EPISODE.find(t)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toInt() == episode)
    }

    /** Player configs or pages handed back as "streams" (autoEmbed's config-*.json), sometimes served as octet-stream. */
    private val NOT_MEDIA = Regex("\\.(json|txt|xml)([?#]|$)", RegexOption.IGNORE_CASE)

    /** Trial clips a provider labels itself (castle: "… - 1080p - preview"): a few minutes, not the film. Same as web's vega.ts. */
    private val TRIAL = Regex("\\b(preview|sample)\\b", RegexOption.IGNORE_CASE)

    fun toStream(s: JsonObject, entry: JsonObject, provider: String): MediaStream? {
        val url = s.str("link").takeIf { it.startsWith("http") && !NOT_MEDIA.containsMatchIn(it) } ?: return null
        if (TRIAL.containsMatchIn(s.str("server"))) return null
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
            qualityLabel = label.ifBlank { "AUTO" }, // the provider is sourceName; the UI prints it once
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
