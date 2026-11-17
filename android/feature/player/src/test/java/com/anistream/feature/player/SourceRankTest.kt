package com.anistream.feature.player

import com.anistream.domain.model.MediaStream
import com.anistream.domain.usecase.canPlayPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors web/src/lib/src-rank.check.ts. */
class SourceRankTest {
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L
    private fun s(h: Int, name: String, codec: String? = null, langs: List<String> = emptyList()) =
        MediaStream(url = "$name$h", qualityLabel = "${h}p", height = h, codec = codec, sourceName = name, languages = langs)
    private val l = listOf(
        s(1080, "VegaMovies · VCloud"), // 0
        s(1080, "MoD", codec = "HEVC DV"), // 1: phone can't decode Dolby Vision -> last of 1080p
        s(1080, "Drive · GDrive"), // 2: reliable -> first
        s(720, "MovieBox", langs = listOf("hi")), // 3
        s(480, "MovieBox"), // 4
        s(2160, "4KHDHub"), // 5
        s(720, "Drive · GDrive"), // 6
    )
    private val stats = mapOf("Drive" to SrcStat(5.0, 0.0, 0.0, 2000.0, now), "VegaMovies" to SrcStat(1.0, 4.0, 0.0, 0.0, now))

    @Test
    fun order() {
        assertEquals("VegaMovies", SourceRank.provider(l[0]))
        // within 1080p: reliable Drive, then Vega, undecodable last; then 720p, 480p (lower, nearest first); 2160p last
        assertEquals(listOf(2, 0, 1, 6, 3, 4, 5), SourceRank.fallbackOrder(l, null, 1080, stats, now, ::canPlayPhone))
        // unknown providers keep list order
        assertEquals(listOf(3, 6), SourceRank.fallbackOrder(l, null, 720, emptyMap(), now).take(2))
        // a language puts its sources first
        assertEquals(3, SourceRank.fallbackOrder(l, "hi", 1080, stats, now).first())
        // "show all": every height best first
        assertEquals(listOf(5, 2, 0, 1, 6, 3, 4), SourceRank.fallbackOrder(l, null, Int.MAX_VALUE, stats, now, ::canPlayPhone))
    }

    @Test
    fun decayAndRecord() {
        val old = SrcStat(fail = 10.0, t = now - 60 * day)
        assertTrue(SourceRank.score(old, now) > 0.45 && SourceRank.score(old.copy(t = now), now) < 0.1)
        val r = SourceRank.add(SourceRank.add(null, now, ok = true, ttff = 4000), now, fail = true, ttff = 2000)
        assertEquals(SrcStat(1.0, 1.0, 0.0, 3400.0, now), r)
    }

    /** Inception 2160p as measured 2026-11-17: Movy's adaptive 4K, Google "download only", Pixeldrain. */
    @Test
    fun fastStartFirstWithinHeight() {
        val k = listOf(
            MediaStream(url = "https://moon.goldenkite.top/m.m3u8", qualityLabel = "Movy - Miami (Multi/4K)", height = 2160, sourceName = "autoEmbed", isDirectFile = false), // 0
            MediaStream(url = "https://video-downloads.googleusercontent.com/x", qualityLabel = "GDrive (download only) 2160p", height = 2160, sourceName = "MoviesDrive"), // 1
            MediaStream(url = "https://pixeldrain.dev/api/file/abc", qualityLabel = "Pixeldrain 2160p", height = 2160, sourceName = "MoviesDrive"), // 2
            MediaStream(url = "https://bcdnxw.hakunaymatata.com/a.mp4", qualityLabel = "1080p", height = 1080, sourceName = "MovieBox"), // 3
        )
        // the fast one first, the slow starters after it; the height (the viewer's quality) never changes
        assertEquals(listOf(2, 0, 1, 3), SourceRank.fallbackOrder(k, null, 2160, emptyMap(), now))
        // this device's own first-frame times win over the defaults: Pixeldrain slow here, Movy fast
        val seen = mapOf("@pixeldrain.dev" to SrcStat(ttff = 7000.0, t = now), "@goldenkite.top" to SrcStat(ttff = 1500.0, t = now))
        assertEquals(listOf(0, 1, 2, 3), SourceRank.fallbackOrder(k, null, 2160, seen, now))
        assertEquals("@workers.dev", SourceRank.hostKey("https://small-dew-a3b5.becini95037599.workers.dev/f.mkv"))
    }

    @Test
    fun chips() {
        assertEquals(listOf(2160 to 1, 1080 to 3, 720 to 2, 480 to 1), SourceRank.qualities(l, null))
        assertEquals(listOf(720 to 1), SourceRank.qualities(l, "hi"))
        assertEquals(SourceRank.qualities(l, null), SourceRank.qualities(l, "ta"))
    }

    /** The Mentalist S6E22 on a Fire TV Stick 2: 1080p and 720p only as 4KHDHub HEVC, H.264 at 540p/480p. */
    private val m = listOf(
        s(1080, "4KHDHub", codec = "HEVC"), // 0: 4KHDHub HEVC is 10-bit
        s(720, "4KHDHub", codec = "HEVC"), // 1
        s(1080, "VegaMovies · VCloud", codec = "HEVC 10bit"), // 2: labelled 10-bit
        s(540, "Drive · GDrive", codec = "H.264"), // 3
        s(480, "MovieBox"), // 4
        s(720, "VegaMovies · VCloud", codec = "HEVC"), // 5: plain HEVC from a provider that also ships 8-bit
    )

    @Test
    fun noMain10() {
        val bad = { x: com.anistream.domain.model.MediaStream -> SourceRank.likelyUndecodable(x, main10 = false) }
        assertEquals(listOf(true, true, true, false, false, false), m.map(bad))
        assertTrue(m.none { SourceRank.likelyUndecodable(it, main10 = true) })
        // asking for 1080p: every decodable source of any height before the 10-bit ones
        assertEquals(listOf(5, 3, 4, 0, 2, 1), SourceRank.fallbackOrder(m, null, 1080, emptyMap(), now, undecodable = bad))
        // "show all" still lists them, last
        assertEquals(listOf(5, 3, 4, 0, 2, 1), SourceRank.fallbackOrder(m, null, Int.MAX_VALUE, emptyMap(), now, undecodable = bad))
        // chips: 1080p has only 10-bit sources; 720p has the plain-HEVC Vega one
        assertEquals(setOf(1080), SourceRank.undecodableHeights(m, null, bad))
        assertEquals(emptySet<Int>(), SourceRank.undecodableHeights(m, null) { false })
    }

    @Test
    fun codecsString() {
        assertTrue(SourceRank.isMain10("hvc1.2.4.L120.B0", "video/hevc", false))
        assertTrue(SourceRank.isMain10("hev1.2.6.L153.90", "video/hevc", false))
        assertTrue(SourceRank.isMain10("dvh1.05.06", "video/dolby-vision", false))
        assertTrue(SourceRank.isMain10("hvc1.1.6.L120.90", "video/hevc", hdr = true))
        assertTrue(!SourceRank.isMain10("hvc1.1.6.L120.90", "video/hevc", false)) // Main (8-bit)
        assertTrue(!SourceRank.isMain10("avc1.64001F", "video/avc", false))
        assertTrue(!SourceRank.isMain10(null, "video/hevc", false))
    }

    @Test
    fun placeholderClip() {
        assertEquals(listOf(45, 130, 60, 130, 90, null), listOf("45 min", "2h 10m", "1h", "130", "5400", "").map(SourceRank::runtimeMinutes))
        val tenMin = 10 * 60_000L
        assertTrue(SourceRank.isPlaceholder(tenMin, 45, movie = false)) // episode known to run 45 min
        assertTrue(SourceRank.isPlaceholder(tenMin, null, movie = true)) // a movie
        assertTrue(!SourceRank.isPlaceholder(tenMin, 11, movie = false)) // a short-form episode
        assertTrue(!SourceRank.isPlaceholder(tenMin, null, movie = false)) // episode, runtime unknown
        assertTrue(!SourceRank.isPlaceholder(42 * 60_000L, 45, movie = false))
        assertTrue(!SourceRank.isPlaceholder(0, 45, movie = true)) // duration not known yet
    }

    /** Languages are what ReleaseLanguages parses from the real labels (see ReleaseLanguagesTest). */
    @Test
    fun dubSafe() {
        val stats = mapOf("castle" to SrcStat(30.0, 0.0, 0.0, 800.0, now), "4khdhub" to SrcStat(1.0, 3.0, 0.0, 5000.0, now))
        fun first(list: List<MediaStream>, country: String?, lang: String? = null) =
            list[SourceRank.fallbackOrder(list, lang, 1080, stats, now, origin = SourceRank.origin(country, list)).first()].sourceName
        // castle · Inception - Tamil - 1080p: the most reliable provider, still never over the English files
        val inception = listOf(s(1080, "castle", langs = listOf("ta")), s(1080, "4khdhub"), s(1080, "hdhub4u", langs = listOf("hi", "en")), s(2160, "4khdhub"))
        assertEquals("hdhub4u", first(inception, "United States, United Kingdom")) // carries the original English
        assertEquals("4khdhub", first(inception, null)) // country unknown: unlabelled (the original audio) first
        assertEquals("castle", first(inception, "United States", lang = "ta")) // the viewer asked for Tamil
        assertEquals(SourceRank.Origin("en", false), SourceRank.origin("United States, United Kingdom", inception))
        assertEquals(4, SourceRank.dubRank(inception[0], SourceRank.origin(null, inception)))
        // a single-language dub loses to a multi-audio file too
        assertEquals("multi", first(listOf(s(1080, "castle", langs = listOf("ta")), s(1080, "multi", langs = listOf("hi", "ta", "te"))), "South Korea"))
        // Gangs of Wasseypur - Hindi - 1080p: a Hindi original, so Hindi is right
        val gangs = listOf(s(1080, "castle", langs = listOf("ta")), s(1080, "vega", langs = listOf("hi")))
        assertEquals("vega", first(gangs, "India"))
        assertEquals(SourceRank.Origin("hi", true), SourceRank.origin(null, gangs)) // every source Indian: an Indian original
        // Dhurandhar - Hindi, Tamil, Telugu - 720p (an Indian original's multi-audio) is fine
        val dhurandhar = listOf(s(720, "castle", langs = listOf("ta")), s(720, "uhd", langs = listOf("hi", "ta", "te")), s(720, "mod", langs = listOf("hi")))
        assertEquals(0, SourceRank.dubRank(dhurandhar[1], SourceRank.origin("India", dhurandhar)))
        assertEquals("uhd", dhurandhar[SourceRank.fallbackOrder(dhurandhar, null, 720, emptyMap(), now, origin = SourceRank.origin("India", dhurandhar)).first()].sourceName)
        // Squid Game: Korean original, a Hindi-only dub last; Dual Audio with Korean first
        val squid = listOf(s(1080, "castle", langs = listOf("hi")), s(1080, "drive"), s(1080, "hdhub4u", langs = listOf("hi", "ko")))
        assertEquals(listOf("hdhub4u", "drive", "castle"), SourceRank.fallbackOrder(squid, null, 1080, stats, now, origin = SourceRank.origin("South Korea", squid)).map { squid[it].sourceName })
    }

    @Test
    fun fallbackNeverLoops() {
        var list = listOf(s(1080, "4KHDHub", codec = "HEVC"), s(1080, "4KHDHub", codec = "H.264").copy(url = "4KHDHub1080b"), s(540, "Dramachi"))
        val good = "Dramachi540"
        val tried = mutableSetOf<String>()
        val played = mutableListOf<String>()
        while (true) {
            val i = SourceRank.nextUntried(list, SourceRank.fallbackOrder(list, null, 1080, emptyMap(), now), tried) ?: break
            played += list[i].url
            if (list[i].url == good) break
            tried += list[i].url
            // a late merge after the first failure adds a source and the list comes back in a new order: indexes shift
            if (played.size == 1) list = lowEndOrder(list + s(480, "Late"), hardwareHevc = false, main10 = false, maxHeight = 1080).reversed()
        }
        assertEquals(listOf("4KHDHub1080", "4KHDHub1080b", good), played)
    }
}
