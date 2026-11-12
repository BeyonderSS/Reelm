package com.anistream.core.media

import com.anistream.core.media.Ranking.Candidate
import com.anistream.core.media.Ranking.Doc
import com.anistream.core.media.Ranking.Seed
import com.anistream.domain.MaturePolicy
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.POPULAR_ON_REELM
import com.anistream.domain.model.Recommendation
import com.anistream.domain.model.forYouRows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecsRankingTest {

    private val drama = Doc(listOf("Drama"), "a family story")

    /** A heavy seed's long list used to fill the top in one block; support is now a seed count and the top is dealt per seed. */
    @Test fun heavySeedDoesNotFillTheTop() {
        val seeds = listOf(Seed(drama, 1.0), Seed(drama, 0.2))
        val heavy = (1..8).map { Candidate("h$it", drama, 8.0, listOf(1.0), group = 0) }
        val both = Candidate("both", drama, 6.0, listOf(1.0, 0.2), group = 0)
        val light = Candidate("light", drama, 7.0, listOf(0.2), group = 1)
        val out = Ranking.rank(seeds, heavy + both + light, perGenre = 10).map { it.item }
        assertEquals("both", out[0]) // suggested by two seeds beats any one-seed item
        assertEquals("light", out[1]) // the weak seed gets its turn in the top
    }

    @Test fun languageBoostLiftsAMatchingCandidate() {
        val seeds = listOf(Seed(drama, 1.0))
        val en = Candidate("en", drama, 7.0, listOf(1.0))
        val hi = Candidate("hi", drama, 7.0, listOf(1.0), boost = RecommendationsRepositoryImpl.LANGUAGE_BOOST)
        assertEquals(listOf("hi", "en"), Ranking.rank(seeds, listOf(en, hi)).map { it.item })
    }

    @Test fun roundRobinDealsOnePerSeed() {
        val sorted = listOf("a1", "a2", "a3", "b1", "c1", "b2")
        assertEquals(listOf("a1", "b1", "c1", "a2", "b2", "a3"), Ranking.roundRobin(sorted, 6) { it.take(1) })
        assertEquals(listOf("a1", "b1", "a2", "a3", "c1", "b2"), Ranking.roundRobin(sorted, 2) { it.take(1) })
    }

    /** Fixture: live TMDB discover/tv?with_origin_country=IN (Indian series row, before the 6+ rating floor), 4 of its results. */
    @Test fun indianSeriesRowParsesAndDropsEroticSeries() {
        val root = Json.parseToJsonElement(javaClass.classLoader!!.getResource("tmdb_discover_tv_in.json")!!.readText()).jsonObject
        val items = RecommendationsRepositoryImpl.parseTmdbList(root["results"], "tv")
        assertEquals(4, items.size)
        assertTrue(items.all { it.lang == "hi" && it.type == "tv" && !it.adult })
        val gandii = items.single { it.title == "Gandii Baat" }
        assertEquals(listOf("Crime", "Drama"), gandii.genres.sorted()) // nothing in its genres or adult flag gives it away
        assertTrue(MaturePolicy.hides(gandii.genres, gandii.title))
        assertEquals(listOf("Mirzapur", "Sacred Games", "The Family Man"),
            items.filterNot { MaturePolicy.hides(it.genres, it.title) }.map { it.title }.sorted())
        assertTrue(MaturePolicy.hides(listOf("Comedy"), "X.X.X: Uncensored"))
        assertFalse(MaturePolicy.hides(listOf("Drama"), "Made in Heaven"))
    }

    @Test fun becauseRowsSkipTopPicksAndFollowSeedWeight() {
        fun r(id: String, because: String, seed: Int) = Recommendation(id, "media", id, because = because, seed = seed)
        // top 10 all from the strongest seed; the rest: 3 from seed 2, 3 from seed 1, 2 from seed 0
        val picks = (1..10).map { r("t$it", "A", 0) } + listOf(r("c1", "C", 2), r("c2", "C", 2), r("c3", "C", 2)) +
            listOf(r("b1", "B", 1), r("b2", "B", 1), r("b3", "B", 1), r("a1", "A", 0), r("a2", "A", 0))
        val rows = picks.forYouRows()
        assertEquals(listOf("Top picks for you", "Because you watched B", "Because you watched C"), rows.map { it.first })
        assertTrue(rows.drop(1).flatMap { it.second }.none { it.id.startsWith("t") })
    }

    private fun pick(key: String, score: Double) = Ranking.ServerPick(key, score, null, null, key, key.substringAfterLast(':'), null, null, null)

    /** Reelm's co-watch picks: confidence 0 changes nothing, at 1 a server-only pick makes the top; a match across movie/series counts once. */
    @Test fun serverBlend() {
        val seeds = listOf(Seed(drama, 1.0), Seed(Doc(listOf("Crime"), "gangster town"), 0.2))
        val local = listOf("a", "b", "d", "e").map { Candidate(it, drama, 7.0, listOf(1.0), group = 0) } + Candidate("c", Doc(listOf("Crime"), "gangster"), 7.0, listOf(0.2), group = 1)
        val srv = listOf(pick("media:movie:zz", 4.0), pick("media:series:b", 2.0))
        val key = { c: Candidate<String> -> "media:${c.item}" }
        val make = { p: Ranking.ServerPick, b: Double -> Candidate(p.title, Doc(emptyList(), p.title), null, emptyList(), b) }
        fun ids(l: List<Candidate<String>>) = Ranking.rank(seeds, l, perGenre = 10).map { it.item }
        assertEquals(ids(local), ids(Ranking.blend(local, key, srv, 0.0, make)))
        val blended = Ranking.blend(local, key, srv, 1.0, make)
        assertTrue(ids(blended).take(3).toString(), "zz" in ids(blended).take(3))
        assertEquals(1, blended.count { it.item == "b" })
        assertEquals(0.15, blended.single { it.item == "b" }.boost, 1e-9)
    }

    @Test fun ratingFloorKeepsUnratedAndBarelyVoted() {
        assertTrue(Ranking.lowRated(4.9, 300))
        assertFalse(Ranking.lowRated(4.9, 12))
        assertFalse(Ranking.lowRated(null, 0))
        assertFalse(Ranking.lowRated(6.1, 900))
    }

    @Test fun parsesServerRecsAndShowsPopularAfterTopPicks() {
        val o = Json.parseToJsonElement("""{"items":[{"item":"media:series:panchayat","score":1.2,"because":"media:series:kota factory","becauseTitle":"Kota Factory",
            "payload":{"kind":"media","slug":"series:panchayat","mediaId":"tt12004706","title":"Panchayat","year":2020}},
            {"item":"anime:one-piece-100","score":0.4,"payload":{"kind":"anime","id":"one-piece-100","title":"One Piece"}}],
            "popular":[{"item":"media:movie:3idiots","score":3,"viewers":4,"payload":{"kind":"movie","id":"tt1187043","type":"movie","title":"3 Idiots"}}],"confidence":0.4}""").jsonObject
        val r = RecommendationsRepositoryImpl.parseServerRecs(o)
        assertEquals(listOf("tt12004706", "one-piece-100"), r.items.map { it.id })
        assertEquals(MediaType.SERIES, r.items[0].type)
        assertEquals("tt1187043", r.popular.single().id)
        assertEquals(0.4, r.confidence, 1e-9)
        val picks = (1..12).map { Recommendation("t$it", "media", "t$it", because = "A") } + Recommendation("p", "media", "p", because = POPULAR_ON_REELM)
        assertEquals(listOf("Top picks for you", POPULAR_ON_REELM), picks.forYouRows().map { it.first })
        assertEquals(listOf(POPULAR_ON_REELM), listOf(Recommendation("p", "media", "p", because = POPULAR_ON_REELM)).forYouRows().map { it.first })
    }
}
