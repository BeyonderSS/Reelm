package com.anistream.core.media

import com.anistream.domain.AudioLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseLanguagesTest {
    private fun p(s: String, title: String? = null) = ReleaseLanguages.parse(s, title)

    @Test
    fun parsesReleaseNames() {
        assertEquals(listOf("hi", "en"), p("Spider-Man: No Way Home (2021) BluRay Dual Audio {Hindi-English} 1080p"))
        assertEquals(listOf("hi", "en"), p("Dual Audio [Hindi ORG 5.1ch – English]"))
        assertEquals(listOf("hi", "en"), p("Doctor Strange 2022 1080p WEB-DL Dual Audio"))
        assertEquals(listOf("hi", "en"), p("Movie.2021.2160p.BluRay.Hindi.DD5.1-English.DDP7.1.x265.mkv"))
        assertEquals(listOf("hi", "en"), p("Movie (2021) [Hin + Eng] Dual Audio 720p"))
        assertEquals(listOf("hi", "ta", "te", "en"), p("Pushpa Multi Audio [Hindi-Tamil-Telugu-English]"))
        assertEquals(listOf("ta"), p("Leo (2023) Tamil 1080p HQ"))
        assertEquals(listOf("hi", "en"), p("Kalki Hindi ORG Dual Audio"))
        assertEquals(emptyList<String>(), p("Inception (2010) BluRay 1080p x264"))
        assertEquals(emptyList<String>(), p("Ben-Hur (2016) 1080p")) // short form without an audio marker
        assertEquals(listOf("hi"), p("English Vinglish (2012) Hindi 1080p", "English Vinglish"))
    }

    @Test
    fun normalizesCodes() {
        assertEquals("hi", AudioLanguage.normalize("hin"))
        assertEquals("hi", AudioLanguage.normalize("hi-IN"))
        assertEquals("es", AudioLanguage.normalize("esla"))
        assertEquals("pt", AudioLanguage.normalize("ptbr"))
        assertEquals(null, AudioLanguage.normalize("und"))
        assertEquals("Hindi", AudioLanguage.name("hi"))
    }
}
