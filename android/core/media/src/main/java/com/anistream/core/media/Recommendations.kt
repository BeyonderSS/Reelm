package com.anistream.core.media

import com.anistream.core.common.Result
import com.anistream.core.common.safeApiCall
import com.anistream.core.database.dao.RecommendationDao
import com.anistream.core.database.entity.RecommendationEntity
import com.anistream.domain.KidsPolicy
import com.anistream.domain.model.AnimeDetail
import com.anistream.domain.model.AnimeSearchResult
import com.anistream.domain.model.FavoriteAnime
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.Recommendation
import com.anistream.domain.model.WatchHistory
import com.anistream.domain.repository.AnidbRepository
import com.anistream.domain.repository.FavoriteRepository
import com.anistream.domain.repository.MoviesRepository
import com.anistream.domain.repository.RecommendationsRepository
import com.anistream.domain.repository.WatchHistoryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Plain-Kotlin content ranking: a taste vector from the seeds' genres and synopsis words (TF-IDF),
 * candidates scored by cosine similarity + how strongly the seeds recommended them + rating, then diversified.
 */
object Ranking {
    class Doc(val genres: List<String>, val text: String)
    class Seed(val doc: Doc, val weight: Double)
    /** [rating] 0–10; [seedWeights] = weights of the seeds that recommended this candidate. */
    class Candidate<T>(val item: T, val doc: Doc, val rating: Double?, val seedWeights: List<Double>)

    /** completion 0–1; halves every 30 days; favourites count 1.5x. */
    fun seedWeight(completion: Double, ageDays: Double, favourite: Boolean): Double =
        (0.3 + 0.7 * completion.coerceIn(0.0, 1.0)) * 0.5.pow(ageDays.coerceAtLeast(0.0) / 30.0) * (if (favourite) 1.5 else 1.0)

    fun <T> rank(seeds: List<Seed>, candidates: List<Candidate<T>>, firstPage: Int = 10, perGenre: Int = 3): List<Candidate<T>> {
        if (candidates.isEmpty()) return emptyList()
        val docs = seeds.map { tokens(it.doc) } + candidates.map { tokens(it.doc) }
        val df = HashMap<String, Int>()
        docs.forEach { d -> d.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = docs.size
        fun tfidf(tokens: List<String>): Map<String, Double> =
            tokens.groupingBy { it }.eachCount().mapValues { (t, c) -> c.toDouble() / tokens.size * (ln((n + 1.0) / (df.getValue(t) + 1)) + 1) }

        val taste = HashMap<String, Double>()
        seeds.forEachIndexed { i, s -> tfidf(docs[i]).forEach { (t, v) -> taste[t] = (taste[t] ?: 0.0) + v * s.weight } }
        val maxSupport = candidates.maxOf { it.seedWeights.sum() }.takeIf { it > 0 } ?: 1.0
        val sorted = candidates.mapIndexed { i, c ->
            val similarity = cosine(taste, tfidf(docs[seeds.size + i]))
            val support = c.seedWeights.sum() / maxSupport
            val rating = ((c.rating ?: 0.0) / 10).coerceIn(0.0, 1.0)
            c to 0.6 * similarity + 0.3 * support + 0.1 * rating
        }.sortedByDescending { it.second }.map { it.first }
        return diversify(sorted, firstPage, perGenre) { it.doc.genres.firstOrNull() }
    }

    /** At most [perGenre] items per primary genre in the first [firstPage]; the rest keep their order behind. */
    fun <T> diversify(sorted: List<T>, firstPage: Int, perGenre: Int, genre: (T) -> String?): List<T> {
        val head = mutableListOf<T>()
        val tail = mutableListOf<T>()
        val counts = HashMap<String, Int>()
        for (item in sorted) {
            val g = genre(item)
            if (head.size >= firstPage || (g != null && (counts[g] ?: 0) >= perGenre)) {
                tail += item
            } else {
                head += item
                if (g != null) counts[g] = (counts[g] ?: 0) + 1
            }
        }
        return head + tail
    }

    // genre tokens twice: a shared genre says more than a shared synopsis word
    fun tokens(doc: Doc): List<String> =
        doc.genres.flatMap { g -> "g:${g.lowercase()}".let { listOf(it, it) } } +
            WORD.findAll(doc.text.lowercase()).map { it.value }.filter { it.length > 3 && it !in STOP }.toList()

    fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dot = b.entries.sumOf { (k, v) -> v * (a[k] ?: 0.0) }
        val na = sqrt(a.values.sumOf { it * it })
        val nb = sqrt(b.values.sumOf { it * it })
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
    }

    private val WORD = Regex("[a-z]+")
    private val STOP = setOf(
        "with", "that", "this", "from", "they", "their", "them", "have", "will", "into", "when", "where", "which",
        "while", "after", "before", "about", "what", "your", "there", "been", "were", "who", "whose", "only", "also",
        "more", "most", "than", "then", "some", "such", "over", "just", "life", "story", "find", "finds", "must",
        "each", "other", "himself", "herself", "becomes", "being", "world", "years", "year", "these", "those"
    )
}

/** A watched / favourited title the picks are based on. */
internal class SeedRef(val id: String, val kind: String, val title: String, val weight: Double)

/** TMDB movie/tv result. [type] "movie" | "tv". */
internal class TmdbItem(
    val type: String, val id: Int, val title: String, val posterPath: String?, val year: Int?,
    val genres: List<String>, val overview: String, val rating: Double?
)

@Singleton
class RecommendationsRepositoryImpl @Inject constructor(
    private val http: MediaHttp,
    private val dao: RecommendationDao,
    private val history: WatchHistoryRepository,
    private val favorites: FavoriteRepository,
    private val anidb: AnidbRepository,
    private val movies: MoviesRepository
) : RecommendationsRepository {

    private val tmdbKey = BuildConfig.TMDB_API_KEY

    // The cache stores no genres or ratings, so a kids profile gets no personalised picks rather than unchecked ones.
    override fun observe(kind: String): Flow<List<Recommendation>> = dao.observe(kind).map { rows ->
        if (KidsPolicy.active) return@map emptyList()
        rows.map {
            Recommendation(it.id, it.kind, it.title, it.posterUrl, it.year, it.rating, it.mediaType?.let(MediaType::valueOf), it.because)
        }
    }

    override suspend fun refresh(): Result<Unit> = safeApiCall {
        val watched = history.getAll().getOrNull().orEmpty()
        val seeds = seedsFrom(watched, favorites.getAll().getOrNull().orEmpty(), System.currentTimeMillis())
        val seen = watched.flatMap { listOfNotNull(it.animeSlug, it.mediaId) }.toSet()
        val seenTitles = watched.map { it.animeTitle.lowercase() }.toSet()
        val (anime, media) = coroutineScope {
            val a = async { runCatching { animePicks(seeds.filter { it.kind == "anime" }.take(SEEDS), seen) } }
            val m = async { runCatching { mediaPicks(seeds.filter { it.kind == "media" }.take(SEEDS), seen, seenTitles) } }
            a.await() to m.await()
        }
        // a failed kind keeps yesterday's cache
        anime.getOrNull()?.let { dao.replace("anime", it) }
        media.getOrNull()?.let { dao.replace("media", it) }
        if (anime.isFailure && media.isFailure) throw anime.exceptionOrNull()!!
    }

    private suspend fun animePicks(seeds: List<SeedRef>, seen: Set<String>): List<RecommendationEntity> = coroutineScope {
        if (seeds.isEmpty()) return@coroutineScope emptyList()
        val gate = Semaphore(4)
        val details = seeds.map { s -> async { gate.withPermit { anidb.getAnimeDetail(s.id).getOrNull() } } }.awaitAll()
        if (details.all { it == null }) throw IOException("Couldn't load anime details")

        class Agg(val card: AnimeSearchResult, val by: MutableList<Int> = mutableListOf(), var detail: AnimeDetail? = null)
        val agg = LinkedHashMap<String, Agg>()
        details.forEachIndexed { i, d ->
            d?.recommendations?.forEach { r -> if (r.slug !in seen) agg.getOrPut(r.slug) { Agg(r) }.by += i }
        }
        // only the most-recommended get a detail fetch (genres + synopsis); the rest rank on support alone
        agg.values.sortedByDescending { a -> a.by.sumOf { seeds[it].weight } }.take(ENRICH)
            .map { a -> async { a.detail = gate.withPermit { anidb.getAnimeDetail(a.card.slug).getOrNull() } } }.awaitAll()

        val rankSeeds = seeds.mapIndexed { i, s -> Ranking.Seed(animeDoc(details[i], s.title), s.weight) }
        val candidates = agg.values.map { a -> Ranking.Candidate(a, animeDoc(a.detail, a.card.title), a.detail?.score, a.by.map { seeds[it].weight }) }
        Ranking.rank(rankSeeds, candidates).take(KEEP).mapIndexed { pos, c ->
            val a = c.item
            RecommendationEntity(
                kind = "anime", id = a.card.slug, title = a.detail?.title?.ifBlank { null } ?: a.card.title,
                posterUrl = a.card.posterUrl ?: a.detail?.posterUrl, year = a.detail?.year, rating = a.detail?.score,
                mediaType = null, because = seeds[a.by.maxBy { seeds[it].weight }].title, position = pos
            )
        }
    }

    private fun animeDoc(d: AnimeDetail?, fallback: String) =
        Ranking.Doc(d?.genres?.map { it.name }.orEmpty(), d?.synopsis ?: fallback)

    private suspend fun mediaPicks(seeds: List<SeedRef>, seen: Set<String>, seenTitles: Set<String>): List<RecommendationEntity> = coroutineScope {
        if (seeds.isEmpty() || tmdbKey.isBlank()) return@coroutineScope emptyList()
        val gate = Semaphore(4)
        val found = seeds.map { s ->
            async { gate.withPermit { runCatching { tmdbFind(s.id) }.getOrNull() } }
        }.awaitAll()
        if (found.all { it == null }) throw IOException("TMDB unreachable")
        val recs = found.map { f -> async { f?.let { gate.withPermit { runCatching { tmdbRecommendations(it.second) }.getOrNull() } }.orEmpty() } }.awaitAll()

        val seedKeys = found.mapNotNull { it?.second?.let { t -> "${t.type}:${t.id}" } }.toSet()
        class Agg(val item: TmdbItem, val by: MutableList<Int> = mutableListOf())
        val agg = LinkedHashMap<String, Agg>()
        recs.forEachIndexed { i, list ->
            list.forEach { t ->
                val key = "${t.type}:${t.id}"
                if (key !in seedKeys && t.title.lowercase() !in seenTitles) agg.getOrPut(key) { Agg(t) }.by += i
            }
        }
        val rankSeeds = seeds.mapIndexed { i, s ->
            val t = found[i]?.second
            Ranking.Seed(Ranking.Doc(t?.genres.orEmpty(), t?.overview ?: s.title), s.weight)
        }
        val candidates = agg.values.map { a -> Ranking.Candidate(a, Ranking.Doc(a.item.genres, a.item.overview), a.item.rating, a.by.map { seeds[it].weight }) }
        val ranked = Ranking.rank(rankSeeds, candidates).take(KEEP)

        // IMDb ids only for what gets shown, so a tap opens the regular "tt…" detail
        val seenTt = seen + found.mapNotNull { it?.first }
        val ids = ranked.map { c -> async { gate.withPermit { runCatching { tmdbImdbId(c.item.item) }.getOrNull() } } }.awaitAll()
        ranked.zip(ids).filter { (_, tt) -> tt != null && tt !in seenTt }.distinctBy { it.second }.mapIndexed { pos, (c, tt) ->
            val t = c.item.item
            RecommendationEntity(
                kind = "media", id = tt!!, title = t.title, posterUrl = t.posterPath?.let { "$TMDB_IMAGES$it" },
                year = t.year, rating = t.rating, mediaType = if (t.type == "tv") MediaType.SERIES.name else MediaType.MOVIE.name,
                because = seeds[c.item.by.maxBy { seeds[it].weight }].title, position = pos
            )
        }
    }

    /** (imdb id, TMDB match) for a history media id ("tt…" or "mb:…"). */
    private suspend fun tmdbFind(mediaId: String): Pair<String, TmdbItem>? {
        val tt = movies.imdbId(mediaId) ?: return null
        val root = tmdb("/find/$tt?external_source=imdb_id")
        val item = parseTmdbList(root["movie_results"], "movie").firstOrNull() ?: parseTmdbList(root["tv_results"], "tv").firstOrNull()
        return item?.let { tt to it }
    }

    private suspend fun tmdbRecommendations(t: TmdbItem): List<TmdbItem> =
        parseTmdbList(tmdb("/${t.type}/${t.id}/recommendations?").get("results"), t.type)

    private suspend fun tmdbImdbId(t: TmdbItem): String? =
        (tmdb("/${t.type}/${t.id}/external_ids?")["imdb_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("tt") }

    // TMDB drops the odd connection; one retry
    private suspend fun tmdb(pathAndQuery: String): JsonObject {
        val sep = if (pathAndQuery.endsWith("?")) "" else "&"
        val url = "https://api.themoviedb.org/3$pathAndQuery${sep}api_key=$tmdbKey"
        return try {
            http.getJson(url)
        } catch (e: IOException) {
            delay(1500)
            http.getJson(url)
        }
    }

    internal companion object {
        const val SEEDS = 8
        const val ENRICH = 15
        const val KEEP = 30
        const val TMDB_IMAGES = "https://image.tmdb.org/t/p/w342"

        /** History weighted by progress and recency; favourites boosted (and seeded even if never played). */
        fun seedsFrom(history: List<WatchHistory>, favs: List<FavoriteAnime>, now: Long): List<SeedRef> {
            val favSlugs = favs.map { it.animeSlug }.toSet()
            fun ageDays(ts: Long) = (now - ts) / 86_400_000.0
            val fromHistory = history.map { h ->
                val completion = if (h.lastEpisodeWatched > 0 && h.totalEpisodes > 0) {
                    ((h.lastEpisodeWatched - 1) + h.watchProgress.toDouble()) / h.totalEpisodes
                } else h.watchProgress.toDouble()
                val media = h.kind == "media"
                SeedRef(
                    id = h.detailId,
                    kind = if (media) "media" else "anime",
                    title = h.animeTitle,
                    weight = Ranking.seedWeight(completion, ageDays(h.lastWatchedTimestamp), h.animeSlug in favSlugs || h.mediaId in favSlugs)
                )
            }
            val inHistory = history.map { it.animeSlug }.toSet()
            val fromFavs = favs.filter { it.animeSlug !in inHistory }.map { f ->
                SeedRef(f.animeSlug, if (isMediaId(f.animeSlug)) "media" else "anime", f.animeTitle, Ranking.seedWeight(0.5, ageDays(f.addedTimestamp), true))
            }
            return (fromHistory + fromFavs).sortedByDescending { it.weight }.distinctBy { it.kind to it.id }
        }

        private fun isMediaId(id: String) = id.startsWith("mb:") || id.startsWith("tmdb:") || (id.length > 2 && id.startsWith("tt") && id[2].isDigit())

        fun parseTmdbList(el: kotlinx.serialization.json.JsonElement?, type: String): List<TmdbItem> =
            (el as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                TmdbItem(
                    type = when (str("media_type")) { "tv" -> "tv"; "movie" -> "movie"; else -> type },
                    id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
                    title = str("title") ?: str("name") ?: return@mapNotNull null,
                    posterPath = str("poster_path"),
                    year = (str("release_date") ?: str("first_air_date"))?.take(4)?.toIntOrNull(),
                    genres = (o["genre_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull?.let(TMDB_GENRES::get) },
                    overview = str("overview").orEmpty(),
                    rating = (o["vote_average"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }
                )
            }

        // movie + tv genre ids (stable for years), so no /genre/list round trip
        private val TMDB_GENRES = mapOf(
            28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
            18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
            9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi", 10770 to "TV Movie", 53 to "Thriller", 10752 to "War",
            37 to "Western", 10759 to "Action", 10762 to "Kids", 10763 to "News", 10764 to "Reality",
            10765 to "Sci-Fi", 10766 to "Soap", 10767 to "Talk", 10768 to "War"
        )
    }
}
