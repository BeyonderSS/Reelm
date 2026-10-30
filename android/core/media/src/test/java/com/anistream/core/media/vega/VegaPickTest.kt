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

        val eps = listOf(o("""{"title":"EPISODE 1","link":"x"}"""), o("""{"title":"Episode 12","link":"y"}"""))
        assertEquals("y", VegaPick.episode(eps, 1, 12)!!["link"]!!.jsonPrimitive.content)
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
}
