package com.anistream.feature.home

import com.anistream.core.trailer.TrailerKey
import org.junit.Assert.assertEquals
import org.junit.Test

class TrailerKeyTest {
    @Test fun imdbIdGoesByImdb() =
        assertEquals(TrailerKey(imdb = "tt0111161"), trailerKeyFor("tt0111161", "The Shawshank Redemption", 1994, "Movie", media = true, mal = null))

    @Test fun movieBoxIdGoesByTitleYearType() {
        assertEquals(TrailerKey(title = "Panchayat", year = 2020, type = "series"), trailerKeyFor("mb:123", "Panchayat", 2020, "Series", media = true, mal = null))
        assertEquals(TrailerKey(title = "Dune", year = 2021, type = "movie"), trailerKeyFor("mb:9", "Dune", 2021, "Movie", media = true, mal = null))
    }

    @Test fun animeIsAlwaysASeriesWithMal() =
        assertEquals(TrailerKey(mal = 16498, title = "Attack on Titan", year = 2013, type = "series"), trailerKeyFor("attack-on-titan-112", "Attack on Titan", 2013, "Movie", media = false, mal = 16498))
}
