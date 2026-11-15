package com.anistream.feature.search

import com.anistream.domain.model.AnimeSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeKindsTest {
    private fun r(slug: String, title: String, type: String) = AnimeSearchResult(slug, title, type = type)

    @Test fun bestMatchFirstWhateverTheKindAndAnimeWinsTies() {
        val anime = listOf(r("suryakanta-1", "Surya no Kanata", ANIME_TYPE), r("naruto-677", "Naruto", ANIME_TYPE))
        val media = listOf(r("mb:9", "Suryavansham", "Movie"), r("tt0409591", "Naruto", "Series"))
        assertEquals(listOf("mb:9", "suryakanta-1", "naruto-677", "tt0409591"), mergeKinds("suryavansham", anime, media).map { it.slug })
        assertEquals(listOf("naruto-677", "tt0409591"), mergeKinds("naruto", anime, media).take(2).map { it.slug })
        // an extra word costs a little, so the film beats the anime that only starts with its name
        val typo = mergeKinds("intersteller", listOf(r("iw-1", "Interstellar Wanderer", ANIME_TYPE)), listOf(r("mb:2", "Interstellar [Hindi]", "Movie")))
        assertEquals(listOf("mb:2", "iw-1"), typo.map { it.slug })
    }
}
