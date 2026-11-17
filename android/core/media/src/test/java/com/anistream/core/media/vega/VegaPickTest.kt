package com.anistream.core.media.vega

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VegaPickTest {
    private fun o(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.t() = this["title"]!!.jsonPrimitive.content

    @Test
    fun seasons() {
        assertEquals(setOf(5), VegaPick.seasons("Breaking Bad (Season 5) BluRay"))
        assertEquals((1..5).toSet(), VegaPick.seasons("Breaking Bad (Season 1 – 5) {English}"))
        assertEquals(setOf(1, 2, 3), VegaPick.seasons("Season: 1 +2+3"))
        assertEquals(setOf(2), VegaPick.seasons("Show S02 720p"))
        assertEquals(emptySet<Int>(), VegaPick.seasons("Inception (2010) 1080p"))
        assertEquals("Breaking Bad", VegaPick.cleanTitle("[18+] Download Breaking Bad (Season 1 - 5) Complete"))
    }

    @Test
    fun picksPostSeasonAndEpisode() {
        val posts = listOf(
            o("""{"title":"El Camino: A Breaking Bad Movie (2019) BluRay","link":"a"}"""),
            o("""{"title":"Breaking Bad (Season 5) BluRay [Hindi] 1080p","link":"b"}"""),
            o("""{"title":"Breaking Bad (Season 2) BluRay [Hindi] 1080p","link":"c"}""")
        )
        assertEquals("c", VegaPick.post("Breaking Bad", 2008, true, 2, posts)!!["link"]!!.jsonPrimitive.content)
        assertNull(VegaPick.post("Breaking Bad", 2008, true, 7, posts))
        val movie = listOf(o("""{"title":"Inception (2010) BluRay 4K 1080p","link":"m"}"""))
        assertEquals("m", VegaPick.post("Inception", 2010, false, 0, movie)!!["link"]!!.jsonPrimitive.content)
        assertNull(VegaPick.post("Inception", 1995, false, 0, movie))
        val boys = listOf(o("""{"title":"The Boys","link":"film","tag":"2024"}"""), o("""{"title":"The Boys","link":"show","tag":"2019"}"""))
        assertEquals("show", VegaPick.post("The Boys", 2019, true, 1, boys)!!["link"]!!.jsonPrimitive.content)
        assertEquals("film", VegaPick.post("The Boys", 2024, false, 0, boys)!!["link"]!!.jsonPrimitive.content)

        val eps = listOf(o("""{"title":"EPISODE 1","link":"x"}"""), o("""{"title":"Episode 12","link":"y"}"""))
        assertEquals("y", VegaPick.episode(eps, 1, 12)!!["link"]!!.jsonPrimitive.content)
        // 4khdhub and autoEmbed list a season's episodes as directLinks titled like these
        val dl = listOf(o("""{"title":"Episode-01 2.88 GB","link":"a"}"""), o("""{"title":"Episode-11 2.9 GB","link":"b"}"""))
        assertEquals("b", VegaPick.episode(dl, 6, 11)!!["link"]!!.jsonPrimitive.content)
        val ae = listOf(o("""{"title":"1. The Desert Rose","link":"a"}"""), o("""{"title":"11. White Lines","link":"b"}"""))
        assertEquals("b", VegaPick.episode(ae, 6, 11)!!["link"]!!.jsonPrimitive.content)
        assertNull(VegaPick.episode(listOf(o("""{"title":"Season Pack","link":"p"}""")), 6, 1))
    }

    @Test
    fun qualities() {
        val e = listOf(
            o("""{"title":"480p [400MB]","quality":"480p","directLinks":[{"link":"1"}]}"""),
            o("""{"title":"720p x264 [1GB]","quality":"720p","directLinks":[{"link":"2"}]}"""),
            o("""{"title":"1080p 10bit HEVC [2.3GB]","quality":"1080p","directLinks":[{"link":"3"}]}"""),
            o("""{"title":"2160p 4K HDR","quality":"2160p","directLinks":[{"link":"4"}]}""")
        )
        assertEquals(listOf("2160p 4K HDR", "720p x264 [1GB]"), VegaPick.qualities(e, lowEnd = false).map { it.t() })
        assertEquals(listOf("720p x264 [1GB]", "480p [400MB]"), VegaPick.qualities(e, lowEnd = true).map { it.t() })
        val s = VegaPick.forSeason(listOf(o("""{"title":"Season 4 • 720p"}"""), o("""{"title":"Season 5 • 1080p"}""")), 5)
        assertEquals(listOf("Season 5 • 1080p"), s.map { it.t() })
    }

    /** camelCase provider ids (autoEmbed, movieBoxWeb…) used to be dropped from the registry; kinds keep anime/live out of title lookups. */
    @Test
    fun registryAndKinds() {
        val c = "0123456789abcdef0123456789abcdef01234567"
        val reg = VegaSources.parseRegistry(Json.parseToJsonElement(
            """{"commit":"$c","base":"https://cdn.jsdelivr.net/gh/Zenda-Cross/vega-providers@$c/dist","enabled":["vega","autoEmbed","bad/id"]}"""))
        assertEquals(listOf("vega", "autoEmbed"), reg!!.enabled)
        assertEquals("kdrama", VegaSources.kindOf("kissKh", "english"))
        assertEquals("anime", VegaSources.kindOf("anikoto", "anime"))
        assertEquals("global", VegaSources.kindOf("vega", null))
    }

    @Test
    fun sequelNumbersMustMatch() {
        fun pick(title: String, year: Int?, vararg posts: String) =
            VegaPick.post(title, year, false, 1, posts.map { o("""{"title":"$it","link":"x"}""") })?.t()
        // no year: "Part Two" used to win for "Part One" (TV played Dune: Part Two for Dune: Part One)
        assertNull(pick("Dune: Part One", null, "Dune: Part Two (2024) 4K HDR", "Dune Part 2 (2024) 1080p"))
        assertEquals("Dune (2021) 1080p", pick("Dune: Part One", null, "Dune: Part Two (2024) 4K HDR", "Dune (2021) 1080p"))
        assertNull(pick("Dune", null, "Dune: Part Two (2024)"))
        assertNull(pick("Toy Story 3", null, "Toy Story 4 (2019) BluRay"))
        assertEquals("X-Men 2 (2003) DD 5.1", pick("X-Men 2", null, "X-Men 2 (2003) DD 5.1"))
        assertEquals("Kill Bill: Vol. 2 (2004)", pick("Kill Bill: Vol. 2", 2004, "Kill Bill: Vol. 1 (2003)", "Kill Bill: Vol. 2 (2004)"))
        assertEquals("Ocean's Eleven (2001)", pick("Ocean's Eleven", null, "Ocean's Twelve (2004)", "Ocean's Eleven (2001)"))
    }

    @Test
    fun dropsTrialClips() {
        val entry = o("""{"title":"1080p"}""")
        // castle labels the few-minute trial file "… - preview"; Gangs of Wasseypur auto-played one on the web
        assertNull(VegaPick.toStream(o("""{"server":"Gangs of Wasseypur - Hindi - 1080p - preview","link":"https://x.y/v.mp4","quality":"1080"}"""), entry, "Castle"))
        assertEquals(1080, VegaPick.toStream(o("""{"server":"Gangs of Wasseypur - Hindi - 1080p","link":"https://x.y/v.mp4","quality":"1080"}"""), entry, "Castle")?.height)
    }

    /** drive's real "Squid Game" search: the 2023 reality spin-off must not stand in for the 2021 series. */
    @Test
    fun seriesPostHonoursYear() {
        val posts = listOf(
            o("""{"title":"Squid Game (Season 3) 2025 Dual Audio [Hindi (DD5.1) - English] Netflix Series 480p | 720p | 1080p | 4K 2160p WEB-DL","link":"s3"}"""),
            o("""{"title":"Download Squid Game – Season 2 (2024) Multi Audio {Hindi-English-Korean} NetFlix Original WEB Series – 480p | 720p | 1080p | 2160p 4K WEB-DL","link":"s2"}"""),
            o("""{"title":"Download Squid Game – Netflix Original (Season 1) Complete Dual-Audio {Hindi-English} Series 480p | 720p | 1080p WEB-DL","link":"s1"}"""),
            o("""{"title":"Download Squid Game: The Challenge (2023) Season 1 [S01] – Netflix Original Dual Audio {Hindi-English} Series 480p | 720p | 1080p WEB-DL","link":"challenge"}""")
        )
        fun pick(year: Int?, season: Int) = VegaPick.post("Squid Game", year, true, season, posts)?.get("link")?.jsonPrimitive?.content
        assertEquals("s1", pick(2021, 1))
        assertEquals("s2", pick(2021, 2))
        assertEquals("s3", pick(2021, 3))
        assertEquals("challenge", VegaPick.post("Squid Game: The Challenge", 2023, true, 1, posts)!!["link"]!!.jsonPrimitive.content)
    }
}
