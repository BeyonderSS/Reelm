package com.anistream.core.media

import com.anistream.domain.model.MediaItem
import com.anistream.domain.model.MediaType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaParsingTest {

    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun json(s: String): JsonObject = MediaHttp.json.parseToJsonElement(s).jsonObject

    @Test fun mirrorShelfRoundTrip() {
        val rows = listOf(com.anistream.domain.model.MediaRow("New on Netflix", listOf(
            MediaItem("nm:netflixMirror:81|nf|Squid%20Game", MediaType.SERIES, "Squid Game", "https://p/1.jpg", 2021, 8.0, listOf("Drama")),
            MediaItem("nm:netflixMirror:82||Bare", MediaType.MOVIE, "Bare \"quoted\"")
        )))
        assertEquals(rows, MirrorShelf.decode(MirrorShelf.encode(rows)))
    }

    @Test fun cinemetaCatalogs() {
        val movies = Stremio.parseMetas(json(text("cinemeta_top_movie.json")))
        assertEquals(48, movies.size)
        val first = movies.first()
        assertEquals("tt22084616", first.id)
        assertEquals(MediaType.MOVIE, first.type)
        assertEquals("Spider-Man: Brand New Day", first.title)
        assertEquals(2026, first.year)
        assertEquals(8.0, first.rating!!, 0.001)
        assertEquals(listOf("Action", "Adventure"), first.genres)
        assertTrue(first.posterUrl!!.startsWith("https://"))

        val series = Stremio.parseMetas(json(text("cinemeta_top_series.json")))
        assertEquals(50, series.size)
        assertTrue(series.all { it.type == MediaType.SERIES && it.id.startsWith("tt") })

        val search = Stremio.parseMetas(json(text("cinemeta_search.json")))
        assertEquals("tt0816692", search.first().id)
        assertEquals(2014, search.first().year)
    }

    @Test fun cinemetaMetaSeasons() {
        val meta = Stremio.parseMeta(json(text("cinemeta_meta_series.json")))!!
        val d = meta.detail
        assertEquals("tt0903747", d.id)
        assertEquals(MediaType.SERIES, d.type)
        assertEquals("Breaking Bad", d.title)
        assertEquals(listOf(1 to 7, 2 to 13, 3 to 13, 4 to 13, 5 to 16), d.seasons.map { it.number to it.episodeCount })
        assertEquals(listOf("Bryan Cranston", "Aaron Paul", "Anna Gunn"), d.cast)
        assertTrue(d.description!!.isNotBlank())
        assertTrue(d.backdropUrl!!.contains("background"))
        assertEquals("49 min", d.runtime)
        assertEquals(2008..2013, meta.years)
        assertNull(Stremio.parseMeta(json("{}")))
    }

    @Test fun yearRanges() {
        assertEquals(2014..2014, Stremio.yearRange("2014"))
        assertEquals(2026..9999, Stremio.yearRange("2026–"))
        assertNull(Stremio.yearRange(null))
    }

    @Test fun titleMatching() {
        fun mb(id: String, title: String, year: Int?, type: MediaType = MediaType.MOVIE) = MediaItem("mb:$id", type, title, year = year)
        val years = 2014..2014

        // tagged dub still matches; untagged preferred when both exist
        assertEquals("mb:1", Stremio.pickMatch("Interstellar", MediaType.MOVIE, years, listOf(mb("1", "Interstellar [Hindi]", 2014)))?.id)
        assertEquals("mb:2", Stremio.pickMatch("Interstellar", MediaType.MOVIE, years,
            listOf(mb("1", "Interstellar [Hindi]", 2014), mb("2", "Interstellar", 2014)))?.id)
        assertEquals("mb:3", Stremio.pickMatch("Interstellar", MediaType.MOVIE, years, listOf(mb("3", "Interstellar", 2015)))?.id)

        // wrong year / type / title rejected
        assertNull(Stremio.pickMatch("Interstellar", MediaType.MOVIE, years, listOf(mb("1", "Interstellar", 2019))))
        assertNull(Stremio.pickMatch("Interstellar", MediaType.MOVIE, years, listOf(mb("1", "Interstellar", 2014, MediaType.SERIES))))
        assertNull(Stremio.pickMatch("Interstellar", MediaType.MOVIE, years, listOf(mb("1", "Interstellar Wars", 2014))))

        // series: season suffix stripped, year anywhere in the run
        assertEquals("mb:5", Stremio.pickMatch("Breaking Bad", MediaType.SERIES, 2008..2013,
            listOf(mb("5", "Breaking Bad S5", 2012, MediaType.SERIES)))?.id)
        assertEquals("spider man brand new day", Stremio.normalizeTitle("Spider-Man: Brand New Day [Tamil]"))
        assertEquals("the office", Stremio.normalizeTitle("The Office Season 2"))
    }

    @Test fun addonStreams() {
        val sample = """
            {"streams":[
              {"name":"Addon\n720p","title":"Movie.2014.720p.WEB","url":"https://cdn.example/a.mp4"},
              {"name":"Addon 4K","title":"Movie HDR","url":"https://cdn.example/hls/master.m3u8?tok=1",
               "behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://site.example/","User-Agent":"UA/1"}}}},
              {"name":"Torrent 1080p","infoHash":"abcdef0123456789","fileIdx":0},
              {"name":"Plain","url":"https://cdn.example/b.mkv","subtitles":[{"url":"https://s.example/en.vtt","lang":"eng"}]},
              {"name":"Magnet","url":"magnet:?xt=urn:btih:abc"}
            ]}
        """.trimIndent()
        val streams = Stremio.parseStreams(json(sample), "MyAddon")
        assertEquals(3, streams.size) // torrent + magnet skipped
        assertEquals(listOf("2160p", "720p", "AUTO"), streams.map { it.qualityLabel })
        val hls = streams[0]
        assertEquals(2160, hls.height)
        assertEquals(false, hls.isDirectFile)
        assertEquals(mapOf("Referer" to "https://site.example/", "User-Agent" to "UA/1"), hls.headers)
        assertEquals("MyAddon", hls.sourceName)
        assertTrue(streams[1].isDirectFile)
        assertEquals("text/vtt", streams[2].subtitles.single().mimeType)
        assertEquals(1080, Stremio.parseHeight("Some.Movie.1080p.BluRay"))
    }

    @Test fun manifestAndUrls() {
        val m = Stremio.parseManifest(json("""{"id":"x.y","name":"X","resources":["catalog",{"name":"stream","types":["movie"]}],"types":["movie"]}"""))
        assertEquals(listOf("catalog", "stream"), m.resources)
        assertEquals("https://a.example/cfg/manifest.json", AddonRepositoryImpl.normalizeManifestUrl(" stremio://a.example/cfg/manifest.json "))
        assertTrue(runCatching { Stremio.parseManifest(json("""{"id":"x"}""")) }.isFailure)
        assertTrue(runCatching { AddonRepositoryImpl.normalizeManifestUrl("https://a.example/") }.isFailure)
    }

    @Test fun m3uParsing() {
        val raw = text("iptv_news.m3u")
        val channels = M3u.parse(raw)
        assertEquals(1017, channels.size)
        val first = channels.first()
        assertEquals("2GB (1080p)", first.name)
        assertEquals("https://i.ibb.co/jwM8DFG/2GB-1.png", first.logoUrl)
        assertEquals("News", first.group)
        assertEquals("https://2gblive.akamaized.net/hls/live/2033805/2GB/index.m3u8", first.url)
        assertEquals(56, channels.count { it.headers.containsKey("Referer") || it.headers.containsKey("User-Agent") })
        assertTrue(channels.count { it.logoUrl != null } > 900)

        // dedupe by URL + EXTVLCOPT headers don't leak into the next channel
        val dup = M3u.parse(
            raw + "\n#EXTINF:-1 group-title=\"X\",Dup\n#EXTVLCOPT:http-user-agent=Foo/1\n" + first.url +
                "\n#EXTINF:-1,Other, With Comma\n#EXTVLCOPT:http-user-agent=Bar/2\n#EXTVLCOPT:http-referrer=https://r.example/\nhttps://x.example/live.m3u8\n" +
                "#EXTINF:-1,Next\nhttps://y.example/live.m3u8\n"
        )
        assertEquals(1019, dup.size)
        val other = dup[dup.size - 2]
        assertEquals("With Comma", other.name) // name = text after the last comma
        assertEquals(mapOf("User-Agent" to "Bar/2", "Referer" to "https://r.example/"), other.headers)
        assertTrue(dup.last().headers.isEmpty())
    }
}
