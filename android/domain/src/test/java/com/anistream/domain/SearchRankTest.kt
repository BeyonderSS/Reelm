package com.anistream.domain

import com.anistream.domain.model.MediaItem
import com.anistream.domain.model.MediaType
import com.anistream.domain.usecase.rankSearch
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRankTest {
    private fun i(id: String, title: String, year: Int? = 2012, vararg g: String) = MediaItem(id, MediaType.MOVIE, title, year = year, genres = g.toList())
    private val mb = listOf(
        i("mb:1", "Gangs of Wasseypur [Hindi]", 2012, "Action", "Crime"), i("mb:2", "Gang Bang", 2020, "Erotic", "Romance"),
        i("mb:3", "Gang Land", 2015, "Crime"), i("mb:4", "Gang Wrestling", 2021, "Wrestling"), i("mb:5", "Gang Pop", 2021, "Pop"), i("mb:6", "Gang Rape", 2019, "Crime")
    )
    private val tmdb = listOf(i("tmdb:movie:117691", "Gangs of Wasseypur - Part 1"), i("tmdb:movie:126400", "Gangs of Wasseypur - Part 2", 2012))
    private val cine = listOf(i("tt1954470", "Gangs of Wasseypur"))

    @Test fun typoRanksPartsAndDedupes() =
        assertEquals(listOf("mb:1", "tmdb:movie:126400"), rankSearch("gang of wasepur", listOf(mb, tmdb, cine), true).map { it.id })

    @Test fun matureGate() {
        assertEquals(false, rankSearch("gang bang", listOf(mb), true).any { it.id == "mb:2" })
        assertEquals(true, rankSearch("gang bang", listOf(mb), false).any { it.id == "mb:2" })
        assertEquals(false, rankSearch("gang rape", listOf(mb), true).any { it.id == "mb:6" })
        assertEquals(true, rankSearch("gang rape", listOf(mb), false).any { it.id == "mb:6" })
        assertEquals(false, rankSearch("gang", listOf(mb), true).any { it.id in setOf("mb:4", "mb:5") })
        assertEquals(false, rankSearch("gang of wasepur", listOf(mb), true).any { it.id == "mb:3" })
    }
}

class SearchSpacingTypoTest {
    private fun i(id: String, title: String, year: Int?, rating: Double? = null) = MediaItem(id, MediaType.MOVIE, title, year = year, rating = rating, genres = listOf("Drama"))
    private fun first(q: String, l: List<MediaItem>) = rankSearch(q, listOf(l), true).firstOrNull()?.title

    private val vq = listOf(
        i("mb:v1", "Vision Quest", 1985, 6.7), i("mb:v2", "Vision Quest", 2025), i("mb:v3", "Vernacular Visions 9 Feat. Guest Presenting Photographer Justin Smith", 2014),
        i("mb:v4", "VisionQuest [Hindi]", 2026, 8.3), i("mb:v5", "VisionQuest Sound", 2015)
    )

    @Test fun dubTagNotShownWhenAnotherSourceHasTheCanonicalTitle() {
        val r = rankSearch("inception", listOf(listOf(i("mb:i", "Inception [Hindi]", 2010)), listOf(i("tt1375666", "Inception", 2010))), true)
        assertEquals(listOf("mb:i" to "Inception"), r.map { it.id to it.title })
    }
    @Test fun spacingAndPopularity() = listOf("vision quest", "visionquest", "visoin quest", "Vision-Quest").forEach { assertEquals(it, "VisionQuest [Hindi]", first(it, vq)) }

    @Test fun typos() {
        assertEquals("Breaking Bad [Hindi] S1-S5", first("brekaing bad", listOf(i("mb:b1", "Ismo: Breaking Bad English", 2024), i("mb:b2", "The Bad News Bears in Breaking Training", 1977), i("mb:b3", "Breaking Bad [Hindi] S1-S5", 2013, 9.5))))
        assertEquals("Interstellar [Hindi]", first("intersteller", listOf(i("mb:i1", "The Science of Interstellar", 2014), i("mb:i2", "Interstellar Wars", 2016), i("mb:i3", "Interstellar [Hindi]", 2014, 8.7))))
        assertEquals("Naruto: Shippuden [Hindi]", first("naruto shipuden", listOf(i("mb:n1", "Naruto Shippuden the Movie: Blood Prison", 2011), i("mb:n2", "Naruto: Shippuden [Hindi]", 2017, 8.7))))
        val sv = listOf(i("mb:s1", "Hum Suryavasham Hain", 2022), i("mb:s2", "Sooryavansham [Hindi]", 1999, 6.4), i("mb:s3", "Suryavanshi", 1992))
        assertEquals(true, rankSearch("suryavansam", listOf(sv), true).any { it.title == "Sooryavansham [Hindi]" })
    }
}
