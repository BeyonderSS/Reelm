package com.anistream.core.media

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import kotlinx.coroutines.delay
import com.anistream.core.common.Result
import com.anistream.core.common.safeApiCall
import com.anistream.core.database.dao.AddonDao
import com.anistream.core.media.dramachi.DramachiSource
import com.anistream.core.media.fourkhdhub.FourKHdHubSource
import com.anistream.core.media.moviebox.MovieBoxSource
import com.anistream.core.media.vega.VegaSources
import com.anistream.domain.AudioLanguage
import com.anistream.domain.KidsBlockedException
import com.anistream.domain.KidsPolicy
import com.anistream.domain.MaturePolicy
import com.anistream.domain.model.AudioVariant
import com.anistream.domain.model.MediaDetail
import com.anistream.domain.model.MediaItem
import com.anistream.domain.model.MediaRow
import com.anistream.domain.model.MediaSeason
import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.Mirrors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import com.anistream.domain.repository.MoviesRepository
import com.anistream.domain.usecase.bestTitle
import com.anistream.domain.usecase.rankSearch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.anistream.domain.repository.LateStreams
import com.anistream.domain.repository.NowPlaying
import com.anistream.core.common.LowEnd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ids: "mb:<subjectId>" → MovieBox; "tt…" → Cinemeta (IMDb); "tmdb:<movie|tv>:<id>" → TMDB-only titles.
 * catalogKey: "<type>/<catalogId>" or "<type>/<catalogId>/genre=<Genre>", e.g. "movie/top/genre=Sci-Fi".
 */
@Singleton
class MoviesRepositoryImpl @Inject constructor(
    private val http: MediaHttp,
    private val movieBox: MovieBoxSource,
    private val addonDao: AddonDao,
    fourKHdHub: FourKHdHubSource,
    dramachi: DramachiSource,
    private val vega: VegaSources,
    @ApplicationContext private val context: Context
) : MoviesRepository {

    /** Title-searched sites, tried after MovieBox and before addons, in this order. */
    private val titleSources = listOf(fourKHdHub, dramachi, vega)

    // ponytail: in-memory caches, lost on process death; that only costs a re-fetch.
    private val typeOf = ConcurrentHashMap<String, MediaType>()        // tt → type, from anything we emitted
    private val ttToMb = ConcurrentHashMap<String, String>()           // tt → subjectId ("" = no MovieBox match)
    private val mbToTt = ConcurrentHashMap<String, String>()           // subjectId → tt ("" = not on Cinemeta)
    private val metaCache = ConcurrentHashMap<String, Stremio.Meta>()  // tt → Cinemeta meta
    private val dubsOf = ConcurrentHashMap<String, List<AudioVariant>>()  // subjectId → its MovieBox audio variants

    override suspend fun home(): Result<List<MediaRow>> = safeApiCall {
        val global = listOf(
            "Popular movies" to "movie/top",
            "Popular series" to "series/top",
            "Top rated movies" to "movie/imdbRating",
            "Top rated series" to "series/imdbRating"
        ) + (if (KidsPolicy.active) listOf("Animation", "Family", "Comedy", "Adventure") else listOf("Action", "Comedy", "Sci-Fi", "Horror", "Animation"))
            .map { "$it movies" to "movie/top/genre=$it" }
        // Cinemeta's charts are US IMDb charts with no Indian titles; Indian rows come from TMDB, first for users in India
        val indian = if (BuildConfig.TMDB_API_KEY.isBlank()) emptyList() else TMDB_ROWS.map { (key, row) -> row.first to key }
        val rows = if (inIndia()) indian + global else global + indian
        val loaded = coroutineScope {
            rows.map { (title, key) ->
                async { runCatching { MediaRow(title, fetchCatalog(key, 0).filter { !KidsPolicy.active || KidsPolicy.media(it.genres) }, key) }.getOrNull() }
            }.awaitAll()
        }.filterNotNull().filter { it.items.isNotEmpty() }
        if (loaded.isEmpty()) error("Couldn't load Movies & TV (Cinemeta unreachable)")
        loaded
    }

    override suspend fun catalog(catalogKey: String, skip: Int): Result<List<MediaItem>> =
        safeApiCall { fetchCatalog(catalogKey, skip) }

    private suspend fun fetchCatalog(key: String, skip: Int): List<MediaItem> {
        TMDB_ROWS[key]?.let { return tmdbRow(it.second, skip) }
        val parts = key.split("/", limit = 3)
        require(parts.size >= 2) { "Bad catalog key: $key" }
        val extras = listOfNotNull(parts.getOrNull(2), if (skip > 0) "skip=$skip" else null)
        val extra = if (extras.isEmpty()) "" else "/" + extras.joinToString("&")
        return cinemetaList("${Stremio.CINEMETA_BASE}/catalog/${parts[0]}/${parts[1]}$extra.json")
    }

    /** India by network (SIM) country, else locale; or an Indian preferred audio language. */
    private fun inIndia(): Boolean {
        val country = runCatching { (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkCountryIso }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country
        return country.equals("IN", true) || AudioLanguage.preferred.value in RecommendationsRepositoryImpl.INDIAN
    }

    /**
     * A TMDB list page as items ("tmdb:<kind>:<id>" ids, open via [tmdbDetail]): TMDB pages are 20, so [skip] maps to a page.
     * Adult, mature (when the profile hides it) and, on a kids profile, non-family titles are dropped.
     * ponytail: skip counts kept items, so a page with drops can repeat a few on "See all"; the grid dedupes by id.
     */
    private suspend fun tmdbRow(path: String, skip: Int): List<MediaItem> {
        val url = "$path${if ("?" in path) "&" else "?"}page=${skip / 20 + 1}"
        val root = runCatching { tmdb(url) }.getOrElse { delay(1500); tmdb(url) } // TMDB drops the odd connection
        val kind = if (path.startsWith("/discover/tv") || path.startsWith("/tv/")) "tv" else "movie"
        return RecommendationsRepositoryImpl.parseTmdbList(root["results"], kind)
            .filter { t ->
                t.posterPath != null && !t.adult && (!MaturePolicy.active || !MaturePolicy.hides(t.genres, t.title)) &&
                    (!KidsPolicy.active || KidsPolicy.media(t.genres))
            }
            .map { t ->
                MediaItem(
                    id = "tmdb:${t.type}:${t.id}", type = if (t.type == "tv") MediaType.SERIES else MediaType.MOVIE, title = t.title,
                    posterUrl = "https://image.tmdb.org/t/p/w342${t.posterPath}", year = t.year, rating = t.rating, genres = t.genres
                )
            }
    }

    private suspend fun cinemetaList(url: String): List<MediaItem> =
        Stremio.parseMetas(http.getJson(url)).onEach { typeOf[it.id] = it.type }

    /** MovieBox + Cinemeta + TMDB in parallel (each optional, 6 s), merged and ranked by [rankSearch]. Later pages: MovieBox only. */
    override suspend fun search(query: String, page: Int): Result<List<MediaItem>> = safeApiCall {
        if (page > 1) return@safeApiCall movieBox.search(query, page).filter { !MaturePolicy.active || !MaturePolicy.hides(it.genres, it.title) }
        suspend fun src(block: suspend () -> List<MediaItem>) = runCatching { withTimeout(SEARCH_TIMEOUT_MS) { block() } }
        // "vision quest" also asks for "visionquest" (titleScore treats both alike); each source's lists are joined, a source fails only if every ask did
        val asks = listOf(query) + (if (Regex("\\S\\s+\\S").containsMatchIn(query.trim())) listOf(query.replace(Regex("\\s+"), "")) else emptyList())
        suspend fun each(block: suspend (String) -> List<MediaItem>) = coroutineScope { asks.map { q -> async { src { block(q) } } }.awaitAll() }
            .let { r -> if (r.all { it.isFailure }) r.first() else kotlin.Result.success(r.flatMap { it.getOrNull().orEmpty() }) }
        val (mb, cine, tm) = coroutineScope {
            val a = async { each { movieBox.search(it, 1) } }
            val b = async { each { cinemetaSearch(it) } }
            val c = async { each { tmdbSearch(it) } }
            Triple(a.await(), b.await(), c.await())
        }
        if (mb.isFailure && cine.isFailure && tm.isFailure) throw mb.exceptionOrNull()!!
        var tmdb = tm.getOrNull().orEmpty()
        val others = mb.getOrNull().orEmpty() + cine.getOrNull().orEmpty()
        if (tmdb.isEmpty()) bestTitle(query, others)?.takeIf { !it.equals(query, true) }?.let { t -> tmdb = src { tmdbSearch(t) }.getOrNull().orEmpty() }
        rankSearch(query, listOf(mb.getOrNull().orEmpty(), tmdb, cine.getOrNull().orEmpty()))
    }

    private suspend fun tmdb(pathAndQuery: String): JsonObject =
        http.getJson("https://api.themoviedb.org/3$pathAndQuery${if ("?" in pathAndQuery) "&" else "?"}api_key=${BuildConfig.TMDB_API_KEY}")

    private suspend fun tmdbSearch(q: String): List<MediaItem> {
        if (BuildConfig.TMDB_API_KEY.isBlank()) return emptyList()
        return (tmdb("/search/multi?include_adult=false&query=${enc(q)}")["results"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val kind = o.str("media_type")?.takeIf { it == "movie" || it == "tv" } ?: return@mapNotNull null
            MediaItem(
                id = "tmdb:$kind:${o.int("id") ?: return@mapNotNull null}", type = if (kind == "tv") MediaType.SERIES else MediaType.MOVIE,
                title = o.str("title") ?: o.str("name") ?: return@mapNotNull null,
                posterUrl = o.str("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
                year = (o.str("release_date") ?: o.str("first_air_date"))?.take(4)?.toIntOrNull(),
                rating = (o["vote_average"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }
            )
        }
    }

    private val tmdbTt = ConcurrentHashMap<String, String>()  // "tmdb:…" → tt ("" = TMDB has none)

    private suspend fun tmdbDetail(id: String): MediaDetail {
        val (_, kind, n) = id.split(":").also { require(it.size == 3) { "Bad TMDB id: $id" } }
        val o = tmdb("/$kind/$n?append_to_response=external_ids")
        val tt = (o.str("imdb_id") ?: (o["external_ids"] as? JsonObject)?.str("imdb_id"))?.takeIf { it.startsWith("tt") }
        tmdbTt[id] = tt.orEmpty()
        return MediaDetail(
            id = id, type = if (kind == "tv") MediaType.SERIES else MediaType.MOVIE, title = o.str("title") ?: o.str("name") ?: error("Title $id not found"),
            description = o.str("overview"), posterUrl = o.str("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
            backdropUrl = o.str("backdrop_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
            year = (o.str("release_date") ?: o.str("first_air_date"))?.take(4)?.toIntOrNull(),
            rating = (o["vote_average"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 },
            runtime = (o.int("runtime") ?: ((o["episode_run_time"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.intOrNull)?.let { "$it min" },
            genres = (o["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") },
            seasons = (o["seasons"] as? JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonObject)?.let { s -> s.int("season_number")?.takeIf { it > 0 }?.let { MediaSeason(it, s.int("episode_count") ?: 0) } }
            }
        )
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull

    private suspend fun cinemetaSearch(query: String): List<MediaItem> = coroutineScope {
        val q = enc(query)
        listOf("movie", "series").map { type ->
            async { runCatching { cinemetaList("${Stremio.CINEMETA_BASE}/catalog/$type/top/search=$q.json") }.getOrNull() }
        }.awaitAll().let { results ->
            if (results.all { it == null }) error("Search failed")
            results.filterNotNull().flatten()
        }
    }

    override suspend fun detail(id: String): Result<MediaDetail> = rawDetail(id).let { r ->
        if (KidsPolicy.active && r is Result.Success && !KidsPolicy.media(r.data.genres)) Result.error(KidsBlockedException()) else r
    }

    private suspend fun rawDetail(id: String): Result<MediaDetail> = safeApiCall {
        if (id.startsWith("mb:")) return@safeApiCall movieBox.detail(id.removePrefix("mb:")).also { dubsOf[it.id.removePrefix("mb:")] = it.audioVariants }.copy(playable = true) // as below
        if (id.startsWith("tmdb:")) return@safeApiCall tmdbDetail(id)
        Mirrors.of(id)?.let { return@safeApiCall mirrorDetail(it, id) }
        val meta = cinemetaMeta(id)
        val cine = meta.detail
        val sid = matchMovieBox(id, meta)
        if (sid != null) {
            val mb = movieBox.detail(sid).also { dubsOf[sid] = it.audioVariants }
            return@safeApiCall mb.copy(
                description = mb.description ?: cine.description,
                backdropUrl = mb.backdropUrl ?: cine.backdropUrl,
                posterUrl = mb.posterUrl ?: cine.posterUrl,
                cast = mb.cast.ifEmpty { cine.cast },
                seasons = mb.seasons.ifEmpty { cine.seasons },
                genres = mb.genres.ifEmpty { cine.genres },
                runtime = mb.runtime ?: cine.runtime,
                rating = mb.rating ?: cine.rating,
                playable = true // MovieBox having no copy (hasResource=false) says nothing about Vega/4KHDHub/addons; streams() decides
            )
        }
        // No MovieBox match: 4KHDHub/Dramachi/addons may still carry it; streams() comes back empty if none do.
        cine.copy(playable = true)
    }

    /** Cinemeta meta; series is tried first when the type is unknown (a movie lookup of a series id returns a wrong title). */
    private suspend fun cinemetaMeta(tt: String): Stremio.Meta {
        metaCache[tt]?.let { return it }
        val types = typeOf[tt]?.let { listOf(it) } ?: listOf(MediaType.SERIES, MediaType.MOVIE)
        for (type in types) {
            val meta = Stremio.parseMeta(http.getJson("${Stremio.CINEMETA_BASE}/meta/${Stremio.typePath(type)}/$tt.json"))
            if (meta != null) {
                typeOf[tt] = meta.detail.type
                metaCache[tt] = meta
                return meta
            }
        }
        throw NoSuchElementException("Title $tt not found")
    }

    /** MovieBox subjectId for a Cinemeta title, or null. Never throws (MovieBox down = no match, not cached). */
    private suspend fun matchMovieBox(tt: String, meta: Stremio.Meta): String? {
        ttToMb[tt]?.let { return it.ifEmpty { null } }
        val candidates = runCatching { movieBox.search(meta.detail.title) }.getOrNull() ?: return null
        val sid = Stremio.pickMatch(meta.detail.title, meta.detail.type, meta.years, candidates)?.id?.removePrefix("mb:")
        ttToMb[tt] = sid.orEmpty()
        if (sid != null) mbToTt[sid] = tt
        return sid
    }

    /** IMDb id for a MovieBox subject (needed by Stremio addons), via Cinemeta search by title + year. */
    private suspend fun imdbIdFor(sid: String, mb: MediaDetail): String? {
        mbToTt[sid]?.let { return it.ifEmpty { null } }
        // Cinemeta search hits only carry a series' first year; a MovieBox per-season subject can be years later.
        val years = mb.year?.takeIf { mb.type == MediaType.MOVIE }?.let { it..it }
        val q = enc(Stremio.normalizeTitle(mb.title))
        val hits = cinemetaList("${Stremio.CINEMETA_BASE}/catalog/${Stremio.typePath(mb.type)}/top/search=$q.json")
        val tt = Stremio.pickMatch(mb.title, mb.type, years, hits)?.id
        mbToTt[sid] = tt.orEmpty()
        return tt
    }

    override suspend fun imdbId(mediaId: String): String? = when {
        mediaId.startsWith("tt") -> mediaId
        Mirrors.of(mediaId) != null -> mirrorTt(mediaId)
        mediaId.startsWith("tmdb:") -> runCatching { if (!tmdbTt.containsKey(mediaId)) tmdbDetail(mediaId); tmdbTt[mediaId]?.ifEmpty { null } }.getOrNull()
        mediaId.startsWith("mb:") -> runCatching {
            val sid = mediaId.removePrefix("mb:")
            mbToTt[sid]?.let { return@runCatching it.ifEmpty { null } }
            imdbIdFor(sid, movieBox.detail(sid))
        }.getOrNull()
        else -> null
    }

    override suspend fun similar(id: String): List<MediaItem> = runCatching {
        if (BuildConfig.TMDB_API_KEY.isBlank()) return emptyList()
        val tmdbId = if (id.startsWith("tmdb:")) id.substringAfter(':').replace(':', '/') else {
            val tt = imdbId(id) ?: return emptyList()
            val found = tmdb("/find/$tt?external_source=imdb_id")
            (found["movie_results"] as? JsonArray)?.firstOrNull()?.let { "movie/${(it as JsonObject).int("id")}" }
                ?: (found["tv_results"] as? JsonArray)?.firstOrNull()?.let { "tv/${(it as JsonObject).int("id")}" } ?: return emptyList()
        }
        tmdbRow("/$tmdbId/recommendations", 0).filter { it.id != id }
    }.getOrDefault(emptyList())

    /**
     * One lookup per title/episode, shared by [prefetch] and [streams] and kept [CACHE_MS] (stream links carry expiring
     * tokens). Sources that land after it returned are kept in [late] too, so a player opened long after a prefetch
     * still starts with everything found so far.
     */
    private inner class Lookup(key: String, id: String, season: Int, episode: Int) {
        val at = System.currentTimeMillis()
        val late = java.util.concurrent.CopyOnWriteArrayList<List<MediaStream>>()
        @Volatile var failed = false
        val result = this@MoviesRepositoryImpl.late.async(start = CoroutineStart.LAZY) {
            resolve(id, season, episode) { batch -> late += batch; NowPlaying.late.emit(LateStreams(key, batch)) }.also { failed = it !is Result.Success }
        }
        fun fresh() = !failed && System.currentTimeMillis() - at < CACHE_MS

        /** The result plus what came late, merged the way the player merges [NowPlaying.late] (by url). */
        fun merged(r: List<MediaStream>) = late.fold(r) { acc, batch -> acc + batch.filter { m -> acc.none { it.url == m.url } } }
    }

    private val lookups = ConcurrentHashMap<String, Lookup>()

    private fun lookup(id: String, season: Int, episode: Int): Lookup {
        val key = NowPlaying.lateKey(id, season, episode)
        lookups.keys.toList().forEach { k -> lookups[k]?.takeIf { !it.fresh() }?.let { lookups.remove(k, it) } }
        return lookups.compute(key) { _, old -> old?.takeIf { it.fresh() } ?: Lookup(key, id, season, episode) }!!.also { it.result.start() }
    }

    override suspend fun streams(id: String, season: Int, episode: Int): Result<List<MediaStream>> {
        Log.d("Sources", "streams $id $season:$episode")
        val l = lookup(id, season, episode)
        if (l.result.isCompleted) Log.d("Sources", "cached lookup for $id $season:$episode (${(System.currentTimeMillis() - l.at) / 1000} s old)")
        return when (val r = l.result.await()) {
            is Result.Success -> Result.success(l.merged(r.data))
            else -> r
        }
    }

    private val prefetchCap = if (LowEnd.isOn(context)) 1 else 2
    private val prefetchSlots = Semaphore(prefetchCap)
    private val prefetchGen = AtomicLong()

    /**
     * At most [prefetchCap] prefetched lookups at a time (on top of a lookup Play started); a queued one that has been
     * overtaken by [prefetchCap] newer requests is dropped (the TV focus moved on).
     */
    override fun prefetch(id: String, season: Int, episode: Int) {
        if (lookups[NowPlaying.lateKey(id, season, episode)]?.fresh() == true) return
        val gen = prefetchGen.incrementAndGet()
        late.launch {
            prefetchSlots.withPermit {
                if (prefetchGen.get() - gen >= prefetchCap) return@withPermit
                Log.d("Sources", "prefetch $id $season:$episode")
                lookup(id, season, episode).result.await()
            }
        }
    }

    private suspend fun resolve(id: String, season: Int, episode: Int, onLate: suspend (List<MediaStream>) -> Unit): Result<List<MediaStream>> =
        Mirrors.of(id)?.let { p -> safeApiCall { mirrorFirst(p, id, season, episode, onLate) } } ?: ladder(id, season, episode, onLate)

    /** Every source for a title, quality-first (a mirror title: matched by name, and year when Cinemeta knows it). */
    private suspend fun ladder(id: String, season: Int, episode: Int, onLate: suspend (List<MediaStream>) -> Unit): Result<List<MediaStream>> = safeApiCall {
        val sid: String?
        val tt: String?
        val detail: MediaDetail?
        val years: IntRange?
        if (id.startsWith("mb:")) {
            sid = id.removePrefix("mb:")
            detail = runCatching { movieBox.detail(sid) }.getOrNull()?.also { dubsOf[sid] = it.audioVariants }
            tt = detail?.let { runCatching { imdbIdFor(sid, it) }.getOrNull() }
            years = detail?.year?.let { it..it }
        } else if (id.startsWith("tmdb:")) {
            sid = null
            detail = runCatching { tmdbDetail(id) }.getOrNull()
            tt = tmdbTt[id]?.ifEmpty { null }
            years = detail?.year?.let { it..it }
        } else if (Mirrors.of(id) != null) {
            tt = mirrorTt(id)
            val meta = tt?.let { runCatching { cinemetaMeta(it) }.getOrNull() }
            sid = meta?.let { matchMovieBox(tt, it) }
            detail = meta?.detail ?: MediaDetail(id, if (season > 0) MediaType.SERIES else MediaType.MOVIE, mirrorTitle(id))
            years = meta?.years
        } else {
            tt = id
            val meta = runCatching { cinemetaMeta(id) }.getOrNull()
            sid = meta?.let { matchMovieBox(id, it) }
            detail = meta?.detail
            years = meta?.years
        }
        val type = detail?.type ?: if (season > 0) MediaType.SERIES else MediaType.MOVIE

        val sources = mutableListOf<suspend () -> List<MediaStream>>()
        if (sid != null) {
            sources += { movieBoxStreams(sid, season, episode, dub = false) }
            sources += { movieBoxStreams(sid, season, episode, dub = true) } // its own source, so dubs never delay the original
        }
        if (detail != null) titleSources.forEach { src ->
            sources += { src.streams(detail.title, years?.first, type, season, episode, onLate) }
        }
        if (tt != null) {
            val videoId = if (type == MediaType.SERIES) "$tt:$season:$episode" else tt
            streamAddons().forEach { addon ->
                sources += {
                    val url = "${Stremio.baseOf(addon.manifestUrl)}/stream/${Stremio.typePath(type)}/$videoId.json"
                    Stremio.parseStreams(http.getJson(url), addon.name)
                }
            }
        }
        if (sources.isEmpty()) error("No streaming source carries this title")

        // Each source on its own (generous) timeout. Returns once the first sources are in plus a short grace, so a fast
        // network still auto-picks across MovieBox/HDHub4u/etc.; on a slow one the rest keep resolving in [late] and
        // reach the player through NowPlaying.late (a hard 15 s cut used to leave only Dramachi on a slow connection).
        // Order kept within what's returned: MovieBox, 4KHDHub, Dramachi, Vega, then addons (each already best-first).
        val t0 = System.currentTimeMillis()
        val got = firstWithGrace(sources, late, SOURCE_TIMEOUT_MS, GRACE_MS) {
            Log.d("Sources", "late: ${it.size} from ${it.first().sourceName}"); onLate(it)
        }
        Log.d("Sources", "returned after ${System.currentTimeMillis() - t0} ms")
        Log.d("Sources", "${got.size}/${sources.size} in: " + got.joinToString { r -> r.fold({ l -> "${l.size}" }, { e -> "fail(${e.message?.take(60)})" }) })
        if (got.isNotEmpty() && got.all { it.isFailure } && got.size == sources.size) throw got.first().exceptionOrNull()!!
        got.flatMap { it.getOrNull().orEmpty() }
    }

    // ---- Mirror mode (ids "nm:<provider>:<link>", see Mirrors) ----

    private val mirrorTts = ConcurrentHashMap<String, String>()     // mirror id → Cinemeta tt ("" = no exact-title match)
    private val mirrorImages = ConcurrentHashMap<String, String>()  // mirror id → the mirror's own image
    private val byTitle = ConcurrentHashMap<String, MediaItem>()    // normalised title → Cinemeta hit (absent = none or not asked)
    private val skipMirror = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Last rows per mirror, shown at once on the next open (even after a restart) while fresh ones load. */
    private fun shelf(p: String) = File(context.cacheDir, "mirror-rows/$p.json")

    override fun mirrorRows(provider: String): Flow<List<MediaRow>> = flow {
        val t0 = System.currentTimeMillis()
        val saved = shelf(provider).takeIf { System.currentTimeMillis() - it.lastModified() < MIRROR_SHELF_MS }
            ?.let { f -> runCatching { MirrorShelf.decode(f.readText()) }.getOrNull() }?.takeIf { it.isNotEmpty() }
        if (saved != null) { emit(saved); Log.d("Mirror", "$provider: ${saved.size} saved rows in ${System.currentTimeMillis() - t0} ms") }
        val raw = try { vega.mirrorRows(provider) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || saved == null) throw e
            Log.w("Mirror", "$provider: ${e.message}; keeping the saved rows"); return@flow
        }
        // the mirror's own titles and images first: Cinemeta's art follows in one swap, not one recomposition per title
        val posts = raw.map { (title, ps) -> title to ps.mapNotNull { p -> p.str("title")?.let { t -> p.str("link")?.let { Triple(Mirrors.id(provider, it), t, p.str("image")) } } } }
            .filter { it.second.isNotEmpty() }
        if (posts.isEmpty()) { if (saved == null) error("The ${Mirrors.all[provider]} mirror didn't answer") else return@flow }
        posts.forEach { (_, ps) -> ps.forEach { (id, _, img) -> img?.let { mirrorImages[id] = it } } }
        Log.d("Mirror", "$provider: mirror rows in ${System.currentTimeMillis() - t0} ms")
        if (saved == null) emit(posts.map { (title, ps) -> MediaRow(title, ps.map { (id, t, img) -> MediaItem(id, MediaType.MOVIE, t, img) }) })
        val t1 = System.currentTimeMillis()
        val gate = Semaphore(if (LowEnd.isOn(context)) 4 else 8)
        val rows = coroutineScope {
            posts.map { (title, ps) -> MediaRow(title, ps.map { (id, t, img) -> async { gate.withPermit { mirrorItem(id, t, img) } } }.awaitAll()) }
        }
        Log.d("Mirror", "$provider: Cinemeta art in ${System.currentTimeMillis() - t1} ms")
        emit(rows)
        runCatching { shelf(provider).apply { parentFile?.mkdirs() }.writeText(MirrorShelf.encode(rows)) }
    }.flowOn(Dispatchers.IO)

    /** A mirror row item with Cinemeta's art and facts when a Cinemeta title has exactly its name, else the mirror's image. */
    private suspend fun mirrorItem(id: String, title: String, image: String?): MediaItem {
        val hit = cinemetaByTitle(title)
        mirrorTts[id] = hit?.id.orEmpty()
        return MediaItem(id, hit?.type ?: MediaType.MOVIE, title, hit?.posterUrl ?: image, hit?.year, hit?.rating, hit?.genres.orEmpty())
    }

    /**
     * Cinemeta's title with exactly this (normalised) name, newest first when a film and a series share it.
     * ponytail: name only, so a same-named older title can lend its art; play still goes to the mirror's own id.
     */
    private suspend fun cinemetaByTitle(title: String): MediaItem? {
        val norm = Stremio.normalizeTitle(title)
        byTitle[norm]?.let { return it }
        val hit = runCatching { cinemetaSearch(Stremio.searchQuery(title)) }.getOrNull().orEmpty()
            .filter { Stremio.normalizeTitle(it.title) == norm }.maxByOrNull { it.year ?: 0 } ?: return null
        byTitle[norm] = hit
        return hit
    }

    /** "id|prefix|Title%20encoded" → "Title encoded" */
    private fun mirrorTitle(id: String) = Mirrors.link(id).split('|').getOrNull(2)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.orEmpty()

    private suspend fun mirrorTt(id: String): String? =
        (mirrorTts[id] ?: cinemetaByTitle(mirrorTitle(id))?.id.orEmpty().also { mirrorTts[id] = it }).ifEmpty { null }

    /** Cinemeta's detail when the name matched (fast, has seasons); else the mirror's own meta, which may wait for its unlock. */
    private suspend fun mirrorDetail(p: String, id: String): MediaDetail {
        mirrorTt(id)?.let { tt -> runCatching { cinemetaMeta(tt) }.getOrNull()?.let { return it.detail.copy(id = id, playable = true) } }
        val title = mirrorTitle(id)
        // ponytail: a cold mirror still unlocking shows the bare title as a film; a series then needs a revisit once it's open
        val m = withTimeoutOrNull(MIRROR_META_MS) { runCatching { vega.mirrorMeta(p, Mirrors.link(id)) }.getOrNull() }
            ?: return MediaDetail(id, MediaType.MOVIE, title, posterUrl = mirrorImages[id])
        val tags = (m["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val counts = (m["counts"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.intOrNull ?: 0 }
        val series = m.str("type") == "series"
        return MediaDetail(
            id = id, type = if (series) MediaType.SERIES else MediaType.MOVIE, title = m.str("title") ?: title, description = m.str("synopsis"),
            posterUrl = mirrorImages[id] ?: m.str("image"), backdropUrl = m.str("image"),
            year = tags.firstNotNullOfOrNull { t -> t.toIntOrNull()?.takeIf { it in 1900..2100 } },
            genres = tags.filter { it.toIntOrNull() == null && "IMDb" !in it },
            cast = (m["cast"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            seasons = if (!series) emptyList() else (m["linkList"] as? JsonArray).orEmpty().mapIndexedNotNull { i, l ->
                val n = (l as? JsonObject)?.str("title")?.filter(Char::isDigit)?.toIntOrNull() ?: (i + 1)
                MediaSeason(n, counts.getOrElse(i) { 0 })
            }
        )
    }

    override fun playElsewhere(id: String, season: Int, episode: Int) {
        skipMirror[NowPlaying.lateKey(id, season, episode)]?.complete(Unit)
    }

    /**
     * The mirror's own streams for its own id (no title matching) first; the usual ladder runs alongside and its streams
     * follow as late ones, so the player falls back to them when the mirror's file fails its checks (placeholder clip,
     * 10-bit). When the mirror has nothing, or the viewer won't wait for its unlock ([playElsewhere]), the ladder's streams
     * answer instead and the mirror's, if any, arrive late.
     */
    private suspend fun mirrorFirst(p: String, id: String, season: Int, episode: Int, onLate: suspend (List<MediaStream>) -> Unit): List<MediaStream> {
        val key = NowPlaying.lateKey(id, season, episode)
        val skip = CompletableDeferred<Unit>().also { skipMirror[key] = it }
        val t0 = System.currentTimeMillis()
        val type = if (season > 0) MediaType.SERIES else MediaType.MOVIE
        val mirror = late.async { vega.mirrorStreams(p, Mirrors.link(id), mirrorTitle(id), type, season, episode) }
        val others = late.async { (ladder(id, season, episode, onLate) as? Result.Success)?.data.orEmpty() }
        val first = select<List<MediaStream>?> { mirror.onAwait { it }; skip.onAwait { null } }
        skipMirror.remove(key, skip)
        Log.d("Sources", "mirror $p: ${first?.size ?: "skipped"} after ${System.currentTimeMillis() - t0} ms")
        if (!first.isNullOrEmpty()) {
            late.launch { others.await().takeIf { it.isNotEmpty() }?.let { onLate(it) } }
            return first
        }
        late.launch { mirror.await().takeIf { it.isNotEmpty() }?.let { onLate(it) } }
        return others.await().ifEmpty { error("Neither the ${Mirrors.all[p]} mirror nor any other source has this title") }
    }

    /**
     * MovieBox streams tagged with their audio language: the subject itself ([dub] false), or every other audio variant
     * of it ("Hindi dub" is a separate MovieBox subject). A dub that only serves the notice clip resolves to nothing.
     */
    private suspend fun movieBoxStreams(sid: String, season: Int, episode: Int, dub: Boolean): List<MediaStream> = coroutineScope {
        val variants = async {
            dubsOf[sid] ?: runCatching { movieBox.detail(sid).audioVariants }.getOrDefault(emptyList()).also { dubsOf[sid] = it }
        }
        if (!dub) {
            val streams = movieBox.streams(sid, season, episode)
            val lang = AudioLanguage.normalize(variants.await().firstOrNull { it.id == "mb:$sid" }?.languageCode)
            return@coroutineScope streams.map { it.copy(languages = listOfNotNull(lang)) }
        }
        // ponytail: capped at MAX_DUBS, preferred language first; raise it if titles with more dubs miss the one wanted
        val pref = AudioLanguage.preferred.value
        variants.await().filter { it.id != "mb:$sid" }
            .sortedBy { if (AudioLanguage.normalize(it.languageCode) == pref) 0 else 1 }.take(MAX_DUBS)
            .map { v ->
                async {
                    val lang = AudioLanguage.normalize(v.languageCode)
                    runCatching { movieBox.streams(v.id.removePrefix("mb:"), season, episode) }.getOrDefault(emptyList())
                        .map { it.copy(languages = listOfNotNull(lang)) }
                }
            }.awaitAll().flatten()
    }

    private suspend fun streamAddons() = addonDao.getEnabled().filter { "stream" in it.resourcesCsv.split(",") }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** outlives the caller: late sources keep resolving after streams() returned */
    private val late = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val SOURCE_TIMEOUT_MS = 45_000L
        const val CACHE_MS = 4 * 60_000L // a lookup's stream links are reused this long (they carry expiring tokens)
        const val GRACE_MS = 4_000L // after the first source with streams, how long the others get before playback starts
        const val SEARCH_TIMEOUT_MS = 6_000L
        const val MAX_DUBS = 6
        const val MIRROR_SHELF_MS = 6 * 3_600_000L // saved mirror rows older than this aren't shown
        const val MIRROR_META_MS = 12_000L // a mirror title's own meta (only when Cinemeta has no match); a cold unlock takes ~40 s

        /** Indian Home rows, catalogKey → (title, TMDB path), in Home order. trending?region=IN ignores the region, so "New" = now playing. */
        val TMDB_ROWS = linkedMapOf(
            "tmdb/in-hindi" to ("Bollywood" to "/discover/movie?with_original_language=hi&region=IN&sort_by=popularity.desc&vote_count.gte=50"),
            "tmdb/in-south" to ("South Indian hits" to "/discover/movie?with_original_language=te|ta|ml|kn&sort_by=popularity.desc&vote_count.gte=50"),
            // drama/crime/action/mystery, no soaps/talk/reality, 2016+, rated 6+ (the low-rated tail is where erotic web series sit)
            "tmdb/in-series" to ("Indian series" to "/discover/tv?with_origin_country=IN&with_genres=18|80|10759|9648&without_genres=10766|10767|10764" +
                "&first_air_date.gte=2016-01-01&sort_by=popularity.desc&vote_count.gte=15&vote_average.gte=6"),
            "tmdb/in-new" to ("New in India" to "/movie/now_playing?region=IN")
        )
    }
}

/** Mirror rows ⇄ the JSON saved per mirror on disk. */
internal object MirrorShelf {
    fun encode(rows: List<MediaRow>): String = JsonArray(rows.map { r ->
        JsonObject(mapOf("title" to JsonPrimitive(r.title), "items" to JsonArray(r.items.map { i ->
            JsonObject(mapOf(
                "id" to JsonPrimitive(i.id), "type" to JsonPrimitive(i.type.name), "title" to JsonPrimitive(i.title),
                "poster" to JsonPrimitive(i.posterUrl), "year" to JsonPrimitive(i.year), "rating" to JsonPrimitive(i.rating),
                "genres" to JsonArray(i.genres.map(::JsonPrimitive))
            ))
        })))
    }).toString()

    fun decode(text: String): List<MediaRow> = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray.map { r ->
        val o = r.jsonObject
        MediaRow(o["title"]!!.jsonPrimitive.content, o["items"]!!.jsonArray.map { e ->
            val i = e.jsonObject
            fun s(k: String) = i[k]?.jsonPrimitive?.contentOrNull
            MediaItem(s("id")!!, MediaType.valueOf(s("type")!!), s("title")!!, s("poster"), i["year"]?.jsonPrimitive?.intOrNull,
                i["rating"]?.jsonPrimitive?.doubleOrNull, (i["genres"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content })
        })
    }
}
