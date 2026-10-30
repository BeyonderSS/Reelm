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
