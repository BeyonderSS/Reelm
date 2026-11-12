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

    @Test
    fun chips() {
        assertEquals(listOf(2160 to 1, 1080 to 3, 720 to 2, 480 to 1), SourceRank.qualities(l, null))
        assertEquals(listOf(720 to 1), SourceRank.qualities(l, "hi"))
        assertEquals(SourceRank.qualities(l, null), SourceRank.qualities(l, "ta"))
    }
}
