package com.anistream.core.media

import com.anistream.core.media.Ranking.Candidate
import com.anistream.core.media.Ranking.Doc
import com.anistream.core.media.Ranking.Seed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationsTest {

    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun rankingPrefersTasteSupportAndRating() {
        val seeds = listOf(
            Seed(Doc(listOf("Crime", "Drama"), "A chemistry teacher turns to cooking meth with a former student"), 1.0),
            Seed(Doc(listOf("Crime"), "A lawyer hustles through cartel cases in Albuquerque"), 0.5)
        )
        val candidates = listOf(
            Candidate("romcom", Doc(listOf("Comedy", "Romance"), "Two strangers fall in love in Paris"), 9.0, listOf(1.0)),
            Candidate("narcos", Doc(listOf("Crime", "Drama"), "A cartel kingpin and the agents chasing his meth empire"), 8.0, listOf(1.0, 0.5)),
            Candidate("heist", Doc(listOf("Crime", "Thriller"), "A crew plans a cartel heist"), 7.0, listOf(0.5))
        )
        assertEquals(listOf("narcos", "heist", "romcom"), Ranking.rank(seeds, candidates).map { it.item })
    }

    @Test fun diversifyCapsAGenreInTheFirstPage() {
        val items = listOf("a1", "a2", "a3", "a4", "b1", "a5", "c1")
        val out = Ranking.diversify(items, firstPage = 5, perGenre = 3) { it.take(1) }
        assertEquals(listOf("a1", "a2", "a3", "b1", "c1", "a4", "a5"), out)
    }

    @Test fun seedWeightFavoursProgressRecencyAndFavourites() {
        val fresh = Ranking.seedWeight(1.0, 0.0, false)
        assertEquals(1.0, fresh, 1e-9)
        assertEquals(0.5, Ranking.seedWeight(1.0, 30.0, false), 1e-9)
        assertTrue(Ranking.seedWeight(0.1, 0.0, false) < fresh)
        assertEquals(1.5, Ranking.seedWeight(1.0, 0.0, true), 1e-9)
    }

    /** Fixture: live `GET api.aniskip.com/v2/skip-times/52991/1?types[]=op&types[]=recap&types[]=ed&types[]=mixed-op&episodeLength=0`. */
    @Test fun aniSkipParsesAndDropsMixedOpWhenOpExists() {
        val skips = AniSkip.parse(text("aniskip.json"))
        assertEquals(listOf("op", "ed"), skips.map { it.type })
        assertEquals(3_221L, skips[0].startMs)
        assertEquals(93_221L, skips[0].endMs)
        assertEquals(1_417_135L, skips[1].startMs)
        assertTrue(skips[0].isIntroOrRecap)
        assertTrue(!skips[1].isIntroOrRecap)
        assertEquals(emptyList<Any>(), AniSkip.parse("""{"found":false,"results":[],"statusCode":404}"""))
    }

    /** Fixture: live `GET api.theintrodb.org/v4/media?imdb_id=tt1196946&season=1&episode=2`, plus an empty recap and two previews. */
    @Test fun theIntroDbMapsNullsAndTypes() {
        val skips = TheIntroDB.parse(text("theintrodb.json"))
        assertEquals(listOf("op", "ed", "preview"), skips.map { it.type })
        assertEquals(0L, skips[0].startMs) // start_ms null = 0
        assertEquals(14_000L, skips[0].endMs)
        assertEquals(2_571_992L, skips[1].startMs)
        assertEquals(Long.MAX_VALUE, skips[1].endMs) // end_ms null = end of video
        assertEquals(2_600_000L, skips[2].startMs)
        assertEquals(emptyList<Any>(), TheIntroDB.parse("""{"error":"no submissions for this media","code":"no_submissions"}"""))
    }
}
