package com.anistream.core.account.ui

import com.anistream.core.account.Now
import com.anistream.core.account.RemoteInfo
import com.anistream.domain.model.WatchHistory
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemotePanelTest {
    @Test fun tabsFollowWhatTheTvReported() {
        val bare = remoteTabs(RemoteInfo())
        assertEquals(listOf(RemoteTab.More), bare.filterValues { it }.keys.toList())
        val full = remoteTabs(RemoteInfo(episode = 2, payload = JsonObject(emptyMap()),
            subtitles = RemoteInfo.Subtitles(tracks = listOf(RemoteInfo.Track("1", "English"))),
            audio = RemoteInfo.Audio(langs = listOf("hi")), quality = RemoteInfo.Quality(chips = listOf(RemoteInfo.Chip(1080)))))
        assertEquals(RemoteTab.entries.toSet(), full.filterValues { it }.keys)
    }

    @Test fun labels() {
        assertEquals("Auto", heightLabel(0)); assertEquals("4K", heightLabel(2160))
        assertEquals("1080p · 3", chipLabel(RemoteInfo.Chip(1080, 3)))
        assertEquals("720p · not supported", chipLabel(RemoteInfo.Chip(720, 2, playable = false)))
        assertEquals("1×", speedLabel(100)); assertEquals("1.25×", speedLabel(125)); assertEquals("0.5×", speedLabel(50))
        assertEquals("Hindi", langName("hi"))
    }

    @Test fun countdownAndSleep() {
        val n = Now("X", null, null, true, 0, 0, true, false, at = 1_000, nextInMs = 9_500)
        assertEquals(10L, nextSeconds(n, 1_000)); assertEquals(5L, nextSeconds(n, 6_000)); assertEquals(0L, nextSeconds(n, 99_000))
        assertNull(nextSeconds(n.copy(nextInMs = null), 1_000))
        assertEquals(3L, minutesToEnd(0, 150_000)); assertEquals(0L, minutesToEnd(10, 5))
    }

    @Test fun searchCardsBecomeHandoffs() {
        val s = searchItem("tt1196946", "The Mentalist", "p", media = true, series = true)
        assertEquals("series:mentalist", s.animeSlug); assertEquals("media", s.kind); assertEquals("tt1196946", s.mediaId)
        assertEquals(1 to 1, s.season to s.lastEpisodeWatched)
        val m = searchItem("mb:1", "Interstellar", null, media = true, series = false)
        assertEquals(0 to 0, m.season to m.lastEpisodeWatched)
        assertEquals("anime", searchItem("frieren", "Frieren", null, media = false, series = false).kind)
        val row = WatchHistory("series:mentalist", "The Mentalist", season = 2, lastEpisodeWatched = 5, watchProgress = 0.4f, durationMs = 100, subtitle = "S2 · E5", kind = "media")
        val r = resumeFrom(s, row)
        assertEquals(listOf(2, 5), listOf(r.season, r.lastEpisodeWatched)); assertEquals("tt1196946", r.mediaId); assertEquals("S2 · E5", r.subtitle)
        assertEquals(s, resumeFrom(s, null))
    }
}
