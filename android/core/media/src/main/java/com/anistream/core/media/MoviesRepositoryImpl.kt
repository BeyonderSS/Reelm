package com.anistream.core.media

import com.anistream.core.common.Result
import com.anistream.core.common.safeApiCall
import com.anistream.core.database.dao.AddonDao
import com.anistream.core.media.dramachi.DramachiSource
import com.anistream.core.media.fourkhdhub.FourKHdHubSource
import com.anistream.core.media.moviebox.MovieBoxSource
import com.anistream.core.media.vega.VegaSources
import com.anistream.domain.KidsBlockedException
import com.anistream.domain.KidsPolicy
import com.anistream.domain.MaturePolicy
import com.anistream.domain.model.MediaDetail
import com.anistream.domain.model.MediaItem
import com.anistream.domain.model.MediaRow
import com.anistream.domain.model.MediaSeason
import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.MediaType
import com.anistream.domain.repository.MoviesRepository
import com.anistream.domain.usecase.bestTitle
import com.anistream.domain.usecase.rankSearch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.net.URLEncoder
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
    vega: VegaSources
) : MoviesRepository {

    /** Title-searched sites, tried after MovieBox and before addons, in this order. */
    private val titleSources = listOf(fourKHdHub, dramachi, vega)

    // ponytail: in-memory caches, lost on process death; that only costs a re-fetch.
    private val typeOf = ConcurrentHashMap<String, MediaType>()        // tt → type, from anything we emitted
    private val ttToMb = ConcurrentHashMap<String, String>()           // tt → subjectId ("" = no MovieBox match)
    private val mbToTt = ConcurrentHashMap<String, String>()           // subjectId → tt ("" = not on Cinemeta)
    private val metaCache = ConcurrentHashMap<String, Stremio.Meta>()  // tt → Cinemeta meta

    override suspend fun home(): Result<List<MediaRow>> = safeApiCall {
        val rows = listOf(
            "Popular movies" to "movie/top",
            "Popular series" to "series/top",
            "Top rated movies" to "movie/imdbRating",
            "Top rated series" to "series/imdbRating"
        ) + (if (KidsPolicy.active) listOf("Animation", "Family", "Comedy", "Adventure") else listOf("Action", "Comedy", "Sci-Fi", "Horror", "Animation"))
            .map { "$it movies" to "movie/top/genre=$it" }
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
        val parts = key.split("/", limit = 3)
        require(parts.size >= 2) { "Bad catalog key: $key" }
        val extras = listOfNotNull(parts.getOrNull(2), if (skip > 0) "skip=$skip" else null)
        val extra = if (extras.isEmpty()) "" else "/" + extras.joinToString("&")
        return cinemetaList("${Stremio.CINEMETA_BASE}/catalog/${parts[0]}/${parts[1]}$extra.json")
    }

    private suspend fun cinemetaList(url: String): List<MediaItem> =
        Stremio.parseMetas(http.getJson(url)).onEach { typeOf[it.id] = it.type }

    /** MovieBox + Cinemeta + TMDB in parallel (each optional, 6 s), merged and ranked by [rankSearch]. Later pages: MovieBox only. */
    override suspend fun search(query: String, page: Int): Result<List<MediaItem>> = safeApiCall {
        if (page > 1) return@safeApiCall movieBox.search(query, page).filter { !MaturePolicy.active || !MaturePolicy.hides(it.genres, it.title) }
        suspend fun src(block: suspend () -> List<MediaItem>) = runCatching { withTimeout(SEARCH_TIMEOUT_MS) { block() } }
        val (mb, cine, tm) = coroutineScope {
            val a = async { src { movieBox.search(query, 1) } }
            val b = async { src { cinemetaSearch(query) } }
            val c = async { src { tmdbSearch(query) } }
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
        if (id.startsWith("mb:")) return@safeApiCall movieBox.detail(id.removePrefix("mb:"))
        if (id.startsWith("tmdb:")) return@safeApiCall tmdbDetail(id)
        val meta = cinemetaMeta(id)
        val cine = meta.detail
        val sid = matchMovieBox(id, meta)
        if (sid != null) {
            val mb = movieBox.detail(sid)
            return@safeApiCall mb.copy(
                description = mb.description ?: cine.description,
                backdropUrl = mb.backdropUrl ?: cine.backdropUrl,
                posterUrl = mb.posterUrl ?: cine.posterUrl,
                cast = mb.cast.ifEmpty { cine.cast },
                seasons = mb.seasons.ifEmpty { cine.seasons },
                genres = mb.genres.ifEmpty { cine.genres },
                runtime = mb.runtime ?: cine.runtime,
                rating = mb.rating ?: cine.rating
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
        mediaId.startsWith("tmdb:") -> runCatching { if (!tmdbTt.containsKey(mediaId)) tmdbDetail(mediaId); tmdbTt[mediaId]?.ifEmpty { null } }.getOrNull()
        mediaId.startsWith("mb:") -> runCatching {
            val sid = mediaId.removePrefix("mb:")
            mbToTt[sid]?.let { return@runCatching it.ifEmpty { null } }
            imdbIdFor(sid, movieBox.detail(sid))
        }.getOrNull()
        else -> null
    }

    override suspend fun streams(id: String, season: Int, episode: Int): Result<List<MediaStream>> = safeApiCall {
        val sid: String?
        val tt: String?
        val detail: MediaDetail?
        val years: IntRange?
        if (id.startsWith("mb:")) {
            sid = id.removePrefix("mb:")
            detail = runCatching { movieBox.detail(sid) }.getOrNull()
            tt = detail?.let { runCatching { imdbIdFor(sid, it) }.getOrNull() }
            years = detail?.year?.let { it..it }
        } else if (id.startsWith("tmdb:")) {
            sid = null
            detail = runCatching { tmdbDetail(id) }.getOrNull()
            tt = tmdbTt[id]?.ifEmpty { null }
            years = detail?.year?.let { it..it }
        } else {
            tt = id
            val meta = runCatching { cinemetaMeta(id) }.getOrNull()
            sid = meta?.let { matchMovieBox(id, it) }
            detail = meta?.detail
            years = meta?.years
        }
        val type = detail?.type ?: if (season > 0) MediaType.SERIES else MediaType.MOVIE

        val sources = mutableListOf<suspend () -> List<MediaStream>>()
        if (sid != null) sources += { movieBox.streams(sid, season, episode) }
        if (detail != null) titleSources.forEach { src ->
            sources += { src.streams(detail.title, years?.first, type, season, episode) }
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

        // Each source on its own timeout; order kept: MovieBox, 4KHDHub, Dramachi, Vega, then addons (each already best-first).
        val results = coroutineScope {
            sources.map { fetch -> async { runCatching { withTimeout(SOURCE_TIMEOUT_MS) { fetch() } } } }.awaitAll()
        }
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        results.flatMap { it.getOrNull().orEmpty() }
    }

    private suspend fun streamAddons() = addonDao.getEnabled().filter { "stream" in it.resourcesCsv.split(",") }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object {
        const val SOURCE_TIMEOUT_MS = 15_000L
        const val SEARCH_TIMEOUT_MS = 6_000L
    }
}
