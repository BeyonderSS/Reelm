package com.anistream.core.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteInfoTest {
    @Test fun roundTripsAndCapsLists() {
        val i = RemoteInfo(rev = 3, title = "The Mentalist", season = 1, episode = 2,
            subtitles = RemoteInfo.Subtitles(true, "s1", List(20) { RemoteInfo.Track("s$it", "Sub $it") }, "l", "top"),
            quality = RemoteInfo.Quality(1080, listOf(RemoteInfo.Chip(1080, 3), RemoteInfo.Chip(720, 1, false))),
            sources = List(50) { RemoteInfo.Source("$it", "Src $it", 720) }, source = "0", speed = 125, speeds = listOf(100, 125),
            volume = RemoteInfo.Volume(7, 15, false))
        val back = RemoteInfo.parse(i.toJson())!!
        assertEquals(12, back.subtitles.tracks.size)
        assertEquals(40, back.sources.size)
        assertEquals(i.copy(subtitles = back.subtitles, sources = back.sources), back)
        assertNull(back.sleepMin)
    }

    @Test fun parsesSparseAndUnknownFields() {
        val o = Json.parseToJsonElement("""{"rev":1,"title":"X","new":42,"subtitles":{"on":false,"selected":null,"tracks":[]},"volume":null}""").jsonObject
        val i = RemoteInfo.parse(o)!!
        assertEquals("X", i.title); assertEquals("m", i.subtitles.size); assertNull(i.volume)
    }
}
