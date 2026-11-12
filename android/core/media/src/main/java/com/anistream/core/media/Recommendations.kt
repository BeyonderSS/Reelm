package com.anistream.core.media

import com.anistream.core.account.AccountApi
import com.anistream.core.account.AccountRepository
import com.anistream.core.common.Result
import com.anistream.core.common.safeApiCall
import com.anistream.core.database.ActiveProfile
import com.anistream.core.database.dao.RecommendationDao
import com.anistream.core.database.entity.RecommendationEntity
import com.anistream.domain.AudioLanguage
import com.anistream.domain.KidsPolicy
import com.anistream.domain.MaturePolicy
import com.anistream.domain.model.AnimeDetail
import com.anistream.domain.model.AnimeSearchResult
import com.anistream.domain.model.FavoriteAnime
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.POPULAR_ON_REELM
import com.anistream.domain.model.Recommendation
import com.anistream.domain.model.WatchHistory
import com.anistream.domain.model.mediaSlug
import com.anistream.domain.repository.AnidbRepository
import com.anistream.domain.repository.FavoriteRepository
import com.anistream.domain.repository.MoviesRepository
import com.anistream.domain.repository.RecommendationsRepository
import com.anistream.domain.repository.WatchHistoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Plain-Kotlin content ranking: a taste vector from the seeds' genres and synopsis words (TF-IDF),
 * candidates scored by cosine similarity + how many seeds recommended them + rating (+ a language boost), then the first
 * page is dealt round-robin across seeds and diversified by genre.
 */
object Ranking {
    class Doc(val genres: List<String>, val text: String)
    class Seed(val doc: Doc, val weight: Double)
    /**
     * [rating] 0–10; [seedWeights] = weights of the seeds that recommended this candidate (their count is the support);
     * [boost] added to the score as is; [group] = the seed it is shown "because" of, for the round-robin.
     */
    class Candidate<T>(val item: T, val doc: Doc, val rating: Double?, val seedWeights: List<Double>, val boost: Double = 0.0, val group: Any? = null)

    /** One /v1/recs pick (server/API.md): [key] is the history key (`anime:<slug>` / `media:<movie|series>:<normtitle>`), [id] what opens it. */
    class ServerPick(val key: String, val score: Double, val because: String?, val becauseTitle: String?, val id: String, val title: String,
                     val poster: String?, val type: MediaType?, val year: Int?)
    class ServerRecs(val items: List<ServerPick>, val popular: List<ServerPick>, val confidence: Double)

    /** Type-agnostic match key: TMDB and the apps don't always agree on movie vs series. */
    fun serverKey(key: String) = key.replace(Regex("^media:(movie|series):"), "media:")
    fun mediaKey(title: String) = "media:" + mediaSlug(false, title).substringAfter(':')

    /** TMDB titles most voters disliked (< 5.5 from 50+ votes); unrated and barely-voted new titles stay. */
    fun lowRated(rating: Double?, votes: Int) = rating != null && rating < 5.5 && votes >= 50

    /**
     * Reelm's co-watch picks as extra support: a candidate the server also picked gets 0.3 × confidence × score / best score
     * on its boost; picks no candidate covers join via [make] (null skips) with that boost. Confidence 0 changes nothing.
     */
    fun <T> blend(cands: List<Candidate<T>>, keyOf: (Candidate<T>) -> String, picks: List<ServerPick>, confidence: Double,
                  make: (ServerPick, Double) -> Candidate<T>?): List<Candidate<T>> {
        val top = picks.maxOfOrNull { it.score } ?: 0.0
        if (confidence <= 0 || top <= 0) return cands
        val bonus = picks.associate { serverKey(it.key) to 0.3 * confidence.coerceAtMost(1.0) * it.score / top }
        val have = cands.map(keyOf).toMutableSet()
        return cands.map { c -> bonus[keyOf(c)]?.let { Candidate(c.item, c.doc, c.rating, c.seedWeights, c.boost + it, c.group) } ?: c } +
            picks.mapNotNull { p -> serverKey(p.key).takeIf(have::add)?.let { make(p, bonus.getValue(it)) } }
    }

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
        // support = how many seeds suggest it, not their weight: a weight share let the strongest seed's list fill the top
        val maxSupport = candidates.maxOf { it.seedWeights.size }.coerceAtLeast(1).toDouble()
        val sorted = candidates.mapIndexed { i, c ->
            val similarity = cosine(taste, tfidf(docs[seeds.size + i]))
            val support = c.seedWeights.size / maxSupport
            val rating = ((c.rating ?: 0.0) / 10).coerceIn(0.0, 1.0)
            c to 0.6 * similarity + 0.3 * support + 0.1 * rating + c.boost
        }.sortedByDescending { it.second }.map { it.first }
        return diversify(roundRobin(sorted, firstPage) { it.group }, firstPage, perGenre) { it.doc.genres.firstOrNull() }
    }

    /** The first [firstPage] dealt one per [group] in turn (groups in order of their best item); the rest keep their order. */
    fun <T> roundRobin(sorted: List<T>, firstPage: Int, group: (T) -> Any?): List<T> {
        val queues = LinkedHashMap<Any?, ArrayDeque<T>>()
        sorted.forEach { queues.getOrPut(group(it)) { ArrayDeque() }.add(it) }
        val head = mutableListOf<T>()
        while (head.size < firstPage && queues.values.any { it.isNotEmpty() }) {
            queues.values.forEach { q -> if (head.size < firstPage) q.removeFirstOrNull()?.let(head::add) }
        }
        val inHead = head.toSet()
        return head + sorted.filter { it !in inHead }
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
internal class SeedRef(val id: String, val kind: String, val title: String, val weight: Double, val malId: Long? = null)

/** TMDB movie/tv result. [type] "movie" | "tv"; [lang] original language ("hi", "en" …). */
internal class TmdbItem(
    val type: String, val id: Int, val title: String, val posterPath: String?, val year: Int?,
    val genres: List<String>, val overview: String, val rating: Double?,
    val lang: String? = null, val genreIds: List<Int> = emptyList(), val adult: Boolean = false, val votes: Int = 0
)

@Singleton
class RecommendationsRepositoryImpl @Inject constructor(
    private val http: MediaHttp,
    private val dao: RecommendationDao,
    private val history: WatchHistoryRepository,
    private val favorites: FavoriteRepository,
    private val anidb: AnidbRepository,
    private val movies: MoviesRepository,
    private val api: AccountApi,
    private val account: Provider<AccountRepository>
) : RecommendationsRepository {

    private val tmdbKey = BuildConfig.TMDB_API_KEY

    // Per profile; a kids profile's picks were filtered by KidsPolicy when they were computed.
    // ponytail: turning kids on for an existing profile shows its old picks until the next refresh; key on kids too if that matters.
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(kind: String): Flow<List<Recommendation>> = ActiveProfile.flow.flatMapLatest {
        combine(dao.observe(it, kind), dao.observe(it, "$kind:popular")) { picks, popular -> picks + popular } // popular rows say so in `because`
    }.map { rows ->
        rows.map {
            Recommendation(it.id, it.kind, it.title, it.posterUrl, it.year, it.rating, it.mediaType?.let(MediaType::valueOf), it.because, it.seed)
        }
    }

    override suspend fun refresh(): Result<Unit> = safeApiCall {
        val profile = ActiveProfile.id // a profile switch mid-refresh must not file these picks under the new one
        val watched = history.getAll().getOrNull().orEmpty()
        val seeds = seedsFrom(watched, favorites.getAll().getOrNull().orEmpty(), System.currentTimeMillis())
        val seen = watched.flatMap { listOfNotNull(it.animeSlug, it.mediaId) }.toSet()
        val seenTitles = watched.map { it.animeTitle.lowercase() }.toSet()
        // signed in: Reelm's own co-watch picks (server/API.md /v1/recs); guest, offline or any failure → local picks only
        val server = if (profile.isEmpty() || !api.enabled) null else runCatching {
            parseServerRecs(api.call("GET", "/v1/recs?profile=$profile&limit=30", token = account.get().token!!)!!)
        }.getOrNull()
        val (anime, media) = coroutineScope {
            val a = async { runCatching { animePicks(seeds.filter { it.kind == "anime" }.take(SEEDS), seen, server) } }
            val m = async { runCatching { mediaPicks(seeds.filter { it.kind == "media" }.take(SEEDS), seen, seenTitles, server) } }
            a.await() to m.await()
        }
        // history payloads can lack a poster: look it up like the picks do, and drop what still has none (no blank cards)
        server?.popular?.let { pop -> coroutineScope { pop.map { p -> async { p to (p.poster ?: posterFor(p)) } }.awaitAll() } }
            ?.filter { it.second != null }?.partition { it.first.key.startsWith("anime:") }?.let { (a, m) ->
                dao.replace(profile, "anime:popular", a.mapIndexed { i, (p, img) -> p.entity(profile, "anime:popular", i).copy(posterUrl = img) })
                dao.replace(profile, "media:popular", m.mapIndexed { i, (p, img) -> p.entity(profile, "media:popular", i).copy(posterUrl = img) })
            }
        // a failed kind keeps yesterday's cache
        anime.getOrNull()?.let { dao.replace(profile, "anime", it.filter { r -> r.posterUrl != null }.map { r -> r.copy(profileId = profile) }) }
        media.getOrNull()?.let { dao.replace(profile, "media", it.filter { r -> r.posterUrl != null }.map { r -> r.copy(profileId = profile) }) }
        if (anime.isFailure && media.isFailure) throw anime.exceptionOrNull()!!
    }

    private suspend fun posterFor(p: Ranking.ServerPick): String? = runCatching {
        if (p.key.startsWith("anime:")) anidb.getAnimeDetail(p.id).getOrNull()?.posterUrl
        else tmdbFind(p.id)?.second?.posterPath?.let { "$TMDB_IMAGES$it" }
    }.getOrNull()

    private fun Ranking.ServerPick.entity(profile: String, kind: String, pos: Int) = RecommendationEntity(
        profileId = profile, kind = kind, id = id, title = title, posterUrl = poster, year = year, rating = null,
        mediaType = type?.name, because = POPULAR_ON_REELM, seed = 0, position = pos
    )

    private suspend fun animePicks(seeds: List<SeedRef>, seen: Set<String>, server: Ranking.ServerRecs?): List<RecommendationEntity> = coroutineScope {
        if (seeds.isEmpty()) return@coroutineScope emptyList()
        val gate = Semaphore(4)
        val details = seeds.map { s -> async { gate.withPermit { anidb.getAnimeDetail(s.id).getOrNull() } } }.awaitAll()
        if (details.all { it == null }) throw IOException("Couldn't load anime details")

        class Agg(val card: AnimeSearchResult, val by: MutableList<Int> = mutableListOf(), var detail: AnimeDetail? = null, val why: String? = null)
        val agg = LinkedHashMap<String, Agg>()
        // MAL's per-title recommendations (via Jikan), mapped to hianime by title; hianime's own "Recommended For You"
        // block is the same pool on every page, so it is only the fallback when Jikan is down.
        // any failure (blocked, 429, down) drops Jikan for this run instead of waiting out every seed
        val jikanRecs = runCatching {
            withTimeoutOrNull(JIKAN_BUDGET_MS) { seeds.mapIndexed { i, s -> jikanRecommendations(s.malId ?: jikanMalId(details[i]?.title ?: s.title)) } }
        }.getOrNull()?.takeIf { l -> l.any { it.isNotEmpty() } }
        if (jikanRecs != null) {
            val byTitle = LinkedHashMap<String, MutableList<Int>>()
            jikanRecs.forEachIndexed { i, l -> l.forEach { t -> byTitle.getOrPut(t) { mutableListOf() }.let { if (i !in it) it += i } } }
            byTitle.entries.sortedByDescending { it.value.size }.take(JIKAN_MAP).map { (title, by) ->
                // ponytail: first hianime search hit for MAL's title; a wrong season/spin-off can slip in, compare titles if it shows
                async { gate.withPermit { anidb.searchAnime(title).getOrNull()?.firstOrNull() }?.let { it to by } }
            }.awaitAll().filterNotNull().forEach { (card, by) ->
                if (card.slug !in seen) agg.getOrPut(card.slug) { Agg(card) }.let { a -> by.forEach { if (it !in a.by) a.by += it } }
            }
        }
        if (agg.isEmpty()) details.forEachIndexed { i, d ->
            d?.recommendations?.forEach { r -> if (r.slug !in seen) agg.getOrPut(r.slug) { Agg(r) }.by += i }
        }
        // only the most-recommended get a detail fetch (genres + synopsis); the rest rank on support alone
        agg.values.sortedByDescending { a -> a.by.size }.take(ENRICH)
            .map { a -> async { a.detail = gate.withPermit { anidb.getAnimeDetail(a.card.slug).getOrNull() } } }.awaitAll()
        // server-only picks get the same detail fetch (genres for the kids/mature checks and the taste match); top 10 only
        val conf = server?.confidence ?: 0.0
        val srv = if (conf > 0) server!!.items.filter { it.key.startsWith("anime:") }.take(10) else emptyList()
        val extra = srv.filter { it.id !in seen && it.id !in agg }.map { p ->
            val i = seeds.indexOfFirst { p.because == "anime:${it.id}" }
            Agg(AnimeSearchResult(slug = p.id, title = p.title, posterUrl = p.poster), if (i < 0) mutableListOf() else mutableListOf(i), why = p.becauseTitle)
        }
        extra.map { a -> async { a.detail = gate.withPermit { anidb.getAnimeDetail(a.card.slug).getOrNull() } } }.awaitAll()

        val kids = KidsPolicy.active
        val safe = MaturePolicy.active
        // kids: only what passed KidsPolicy on its detail page; no mature content: no ecchi/hentai (unknown genres = dropped)
        val kept = (agg.values + extra).filter { a ->
            val g = a.detail?.genres?.map { it.name.lowercase() }
            (!kids || a.detail != null) && (!safe || (g != null && g.none { it in ADULT_ANIME }))
        }
        val rankSeeds = seeds.mapIndexed { i, s -> Ranking.Seed(animeDoc(details[i], s.title), s.weight) }
        val cands = kept.map { a ->
            Ranking.Candidate(a, animeDoc(a.detail, a.card.title), a.detail?.score, a.by.map { seeds[it].weight }, group = a.by.maxByOrNull { seeds[it].weight })
        }
        val (local, fromServer) = cands.partition { it.item !in extra }
        val candidates = Ranking.blend(local, { "anime:${it.item.card.slug}" }, srv, conf) { p, b ->
            fromServer.firstOrNull { it.item.card.slug == p.id }?.let { Ranking.Candidate(it.item, it.doc, it.rating, it.seedWeights, b, it.group) }
        }
        Ranking.rank(rankSeeds, candidates).take(KEEP).mapIndexed { pos, c ->
            val a = c.item
            val primary = c.group as Int?
            RecommendationEntity(
                profileId = "", kind = "anime", id = a.card.slug, title = a.detail?.title?.ifBlank { null } ?: a.card.title,
                posterUrl = a.card.posterUrl ?: a.detail?.posterUrl, year = a.detail?.year, rating = a.detail?.score,
                mediaType = null, because = primary?.let { seeds[it].title } ?: a.why, seed = primary ?: seeds.size, position = pos
            )
        }
    }

    /** MAL titles recommended for [malId], most-voted first. */
    private suspend fun jikanRecommendations(malId: Long?): List<String> =
        if (malId == null) emptyList()
        else (jikan("/anime/$malId/recommendations")["data"] as? JsonArray).orEmpty().take(JIKAN_PER_SEED).mapNotNull { e ->
            ((e as? JsonObject)?.get("entry") as? JsonObject)?.get("title")?.let { (it as? JsonPrimitive)?.contentOrNull }
        }

    private suspend fun jikanMalId(title: String): Long? =
        ((jikan("/anime?limit=1&q=${java.net.URLEncoder.encode(title, "UTF-8")}")["data"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("mal_id")?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }

    // Jikan allows 3 requests/s: calls go one at a time, spaced; answers are kept for the process
    private val jikanLock = Mutex()
    private var jikanAt = 0L
    private val jikanCache = ConcurrentHashMap<String, JsonObject>()

    private suspend fun jikan(path: String): JsonObject = jikanCache[path] ?: jikanLock.withLock {
        jikanCache[path] ?: try {
            delay(jikanAt + JIKAN_SPACING_MS - System.currentTimeMillis())
            http.getJson("https://api.jikan.moe/v4$path").also { jikanCache[path] = it }
        } finally {
            jikanAt = System.currentTimeMillis()
        }
    }

    private fun animeDoc(d: AnimeDetail?, fallback: String) =
        Ranking.Doc(d?.genres?.map { it.name }.orEmpty(), d?.synopsis ?: fallback)

    private suspend fun mediaPicks(seeds: List<SeedRef>, seen: Set<String>, seenTitles: Set<String>, server: Ranking.ServerRecs?): List<RecommendationEntity> = coroutineScope {
        if (seeds.isEmpty() || tmdbKey.isBlank()) return@coroutineScope emptyList()
        val gate = Semaphore(4)
        val found = seeds.map { s ->
            async { gate.withPermit { runCatching { tmdbFind(s.id) }.getOrNull() } }
        }.awaitAll()
        if (found.all { it == null }) throw IOException("TMDB unreachable")
        // TMDB's /recommendations lean English; an Indian-language seed also pulls popular titles in its language and genres
        val recs = found.map { f ->
            async {
                val t = f?.second ?: return@async emptyList()
                val similar = gate.withPermit { runCatching { tmdbRecommendations(t) }.getOrNull().orEmpty() }
                val local = if (t.lang in INDIAN) gate.withPermit { runCatching { tmdbSameLanguage(t) }.getOrNull().orEmpty() } else emptyList()
                similar + local
            }
        }.awaitAll()

        val seedKeys = found.mapNotNull { it?.second?.let { t -> "${t.type}:${t.id}" } }.toSet()
        val kids = KidsPolicy.active
        val safe = MaturePolicy.active
        fun allowed(t: TmdbItem) = !t.adult && !Ranking.lowRated(t.rating, t.votes) && (!safe || !MaturePolicy.hides(t.genres, t.title)) && (!kids || KidsPolicy.media(t.genres)) &&
            ((t.year ?: Int.MAX_VALUE) >= OLDEST || (t.rating ?: 0.0) >= OLD_BUT_GREAT) // a 1970s show ranked high only on genre overlap
        class Agg(val item: TmdbItem, val by: MutableList<Int> = mutableListOf(), val id: String? = null, val poster: String? = null, val why: String? = null)
        val agg = LinkedHashMap<String, Agg>() // by normalised title: a movie and its namesake series or a remake count once
        recs.forEachIndexed { i, list ->
            list.forEach { t ->
                if ("${t.type}:${t.id}" !in seedKeys && t.title.lowercase() !in seenTitles && allowed(t)) {
                    val a = agg.getOrPut(normTitle(t.title)) { Agg(t) }
                    if (i !in a.by) a.by += i
                }
            }
        }
        val pref = AudioLanguage.preferred.value
        val rankSeeds = seeds.mapIndexed { i, s ->
            val t = found[i]?.second
            Ranking.Seed(Ranking.Doc(t?.genres.orEmpty(), t?.overview ?: s.title), s.weight)
        }
        val local = agg.values.map { a ->
            val lang = a.item.lang
            val boost = if (lang != null && (lang == pref || a.by.any { found[it]?.second?.lang == lang })) LANGUAGE_BOOST else 0.0
            Ranking.Candidate(a, Ranking.Doc(a.item.genres, a.item.overview), a.item.rating, a.by.map { seeds[it].weight }, boost, a.by.maxBy { seeds[it].weight })
        }
        // server-only picks get their TMDB entry (genres, synopsis, language, rating) so they rank like any candidate; top 10 only
        val conf = server?.confidence ?: 0.0
        val srv = if (conf > 0) server!!.items.filter { it.key.startsWith("media:") }.take(10) else emptyList()
        val srvFound = srv.map { p -> async { gate.withPermit { runCatching { tmdbFind(p.id)?.second }.getOrNull() } } }.awaitAll()
        val candidates = Ranking.blend(local, { Ranking.mediaKey(it.item.item.title) }, srv, conf) { p, b ->
            val f = srvFound[srv.indexOf(p)]
            val t = TmdbItem(if (p.type == MediaType.SERIES) "tv" else "movie", 0, p.title, f?.posterPath, p.year ?: f?.year, f?.genres.orEmpty(),
                f?.overview.orEmpty(), f?.rating, f?.lang, adult = f?.adult == true, votes = f?.votes ?: 0)
            val i = seeds.indexOfFirst { Ranking.serverKey(p.because.orEmpty()) == Ranking.mediaKey(it.title) }
            val lang = t.lang
            val boost = if (lang != null && (lang == pref || found.getOrNull(i)?.second?.lang == lang)) LANGUAGE_BOOST else 0.0
            if (p.id in seen || p.title.lowercase() in seenTitles || !allowed(t)) null
            else Ranking.Candidate(Agg(t, if (i < 0) mutableListOf() else mutableListOf(i), p.id, if (f?.posterPath != null) null else p.poster, p.becauseTitle),
                Ranking.Doc(t.genres, t.overview.ifBlank { p.title }), t.rating, if (i < 0) emptyList() else listOf(seeds[i].weight), b + boost, i.takeIf { it >= 0 })
        }
        val ranked = Ranking.rank(rankSeeds, candidates).take(KEEP)

        // IMDb ids only for what gets shown, so a tap opens the regular "tt…" detail
        val seenTt = seen + found.mapNotNull { it?.first }
        val ids = ranked.map { c -> async { c.item.id ?: gate.withPermit { runCatching { tmdbImdbId(c.item.item) }.getOrNull() } } }.awaitAll()
        ranked.zip(ids).filter { (_, tt) -> tt != null && tt !in seenTt }.distinctBy { it.second }.mapIndexed { pos, (c, tt) ->
            val t = c.item.item
            val primary = c.group as Int?
            RecommendationEntity(
                profileId = "", kind = "media", id = tt!!, title = t.title, posterUrl = c.item.poster ?: t.posterPath?.let { "$TMDB_IMAGES$it" },
                year = t.year, rating = t.rating, mediaType = if (t.type == "tv") MediaType.SERIES.name else MediaType.MOVIE.name,
                because = primary?.let { seeds[it].title } ?: c.item.why, seed = primary ?: seeds.size, position = pos
            )
        }
    }

    private suspend fun tmdbSameLanguage(t: TmdbItem): List<TmdbItem> =
        parseTmdbList(tmdb("/discover/${t.type}?with_original_language=${t.lang}&with_genres=${t.genreIds.joinToString("|")}&sort_by=popularity.desc&vote_count.gte=50").get("results"), t.type)

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
        const val LANGUAGE_BOOST = 0.15
        const val OLDEST = 1990
        const val OLD_BUT_GREAT = 8.0
        const val JIKAN_PER_SEED = 10
        const val JIKAN_MAP = 24
        const val JIKAN_SPACING_MS = 400L
        const val JIKAN_BUDGET_MS = 30_000L

        /** Indian original languages: Indian Home rows, language-aware picks. */
        val INDIAN = setOf("hi", "te", "ta", "ml", "kn", "bn", "mr")
        private val ADULT_ANIME = setOf("ecchi", "hentai")

        fun normTitle(t: String) = t.lowercase().filter(Char::isLetterOrDigit)

        /** /v1/recs → picks; a payload is a web ({kind,id,type}) or app ({slug,mediaId}) history payload. */
        fun parseServerRecs(o: JsonObject): Ranking.ServerRecs {
            fun s(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun picks(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { e ->
                val r = e as? JsonObject ?: return@mapNotNull null
                val p = r["payload"] as? JsonObject ?: return@mapNotNull null
                val key = s(r["item"]) ?: return@mapNotNull null
                val anime = key.startsWith("anime:")
                val id = (if (anime) s(p["id"]) ?: s(p["slug"]) else s(p["mediaId"]) ?: s(p["id"])) ?: return@mapNotNull null
                Ranking.ServerPick(
                    key, (r["score"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, s(r["because"]), s(r["becauseTitle"]), id, s(p["title"]) ?: return@mapNotNull null,
                    s(p["poster"]), if (anime) null else if (key.startsWith("media:series:") || s(p["type"]) == "series") MediaType.SERIES else MediaType.MOVIE,
                    s(p["year"])?.toIntOrNull()
                )
            }
            return Ranking.ServerRecs(picks("items"), picks("popular"), ((o["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.0).coerceIn(0.0, 1.0))
        }

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
                    weight = Ranking.seedWeight(completion, ageDays(h.lastWatchedTimestamp), h.animeSlug in favSlugs || h.mediaId in favSlugs),
                    malId = h.malId
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
                    rating = (o["vote_average"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 },
                    lang = str("original_language"),
                    genreIds = (o["genre_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull },
                    adult = (o["adult"] as? JsonPrimitive)?.booleanOrNull == true,
                    votes = (o["vote_count"] as? JsonPrimitive)?.intOrNull ?: 0
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
