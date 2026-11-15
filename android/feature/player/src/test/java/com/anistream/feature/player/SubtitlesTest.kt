package com.anistream.feature.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SubtitlesTest {
    @Test fun mimeFromTypeThenExtension() {
        assertEquals(MimeTypes.TEXT_VTT, subtitleMime("text/vtt"))
        assertEquals(MimeTypes.TEXT_VTT, subtitleMime("", "https://x/en.vtt?t=1"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitleMime("application/x-subrip", "https://x/sub"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitleMime("", "https://x/en.srt"))
        assertEquals(MimeTypes.TEXT_SSA, subtitleMime("", "https://x/en.ass"))
        assertEquals(MimeTypes.TEXT_SSA, subtitleMime("text/x-ssa"))
        assertEquals(MimeTypes.APPLICATION_TTML, subtitleMime("application/ttml+xml"))
    }

    @Test fun languageNamesBecomeCodes() {
        assertEquals("en", subtitleLanguage("English"))
        assertEquals("eng", subtitleLanguage("eng")) // codes pass through; Media3 normalises them
        assertEquals("es", subtitleLanguage("Spanish (Latin America)"))
    }

    private fun cue(line: Float, type: Int = Cue.LINE_TYPE_NUMBER) = Cue.Builder().setText("hi").setLine(line, type).build()

    @Test fun bottomCuesMirrorToTop() {
        // Media3 stacks WebVTT cues at lines -1, -2 … from the bottom; bottomPaddingFraction ignores them
        assertEquals(0f, cue(-1f).toTop().line)
        assertEquals(1f, cue(-2f).toTop().line)
        assertEquals(0f, cue(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).toTop().line) // SRT: no line
        val ssa = cue(0.95f, Cue.LINE_TYPE_FRACTION).toTop()
        assertEquals(0.05f, ssa.line, 1e-4f)
        assertEquals(Cue.ANCHOR_TYPE_START, ssa.lineAnchor)
        val sign = cue(3f)
        assertSame(sign, sign.toTop()) // placed on purpose: left where it is
    }
}
