package com.anistream.feature.movies

import com.anistream.domain.model.MediaDetail
import com.anistream.domain.model.MediaSeason
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.WatchHistory
import org.junit.Assert.assertEquals
import org.junit.Test

/** The prefetch key must be the episode the detail page's Play / Continue starts. */
class PlayTargetTest {
    private val show = MediaDetail("mb:1", MediaType.SERIES, "Show", seasons = listOf(MediaSeason(1, 8), MediaSeason(2, 6)))
    private val film = MediaDetail("mb:2", MediaType.MOVIE, "Film")
    private fun target(d: MediaDetail, vararg h: WatchHistory) = playTarget(d, historyOf(d, h.toList())?.let { resumeOf(d, it) })
    private fun row(slug: String, ep: Int, p: Float, season: Int = 0) =
        WatchHistory(slug, "Show", kind = "media", mediaId = "mb:1", lastEpisodeWatched = ep, watchProgress = p, season = season)

    @Test fun firstWatch() {
        assertEquals(Resume(1, 1), target(show))
        assertEquals(Resume(0, 0), target(film))
    }

    @Test fun resumes() {
        assertEquals(Resume(1, 3), target(show, row("mb:1:s1e3", 3, 0.4f)))
        assertEquals(Resume(1, 4), target(show, row("mb:1:s1e3", 3, 0.95f)))
        // last episode of a season finished: the next season's first
        assertEquals(Resume(2, 1), target(show, row("series:show", 8, 0.97f, season = 1)))
    }
}
