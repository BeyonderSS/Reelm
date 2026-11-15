package com.anistream.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRangeTest {
    @Test fun wholeFileAnswerMeansNoRange() {
        // video-downloads.googleusercontent.com answering `Range: bytes=0-` (HttpURLConnection keys the status line with null)
        assertTrue(ignoresRange(mapOf<String?, List<String>>(null to listOf("HTTP/1.1 200 OK"), "Content-Length" to listOf("3177157988")) as Map<String, List<String>>))
        assertFalse(ignoresRange(mapOf("content-range" to listOf("bytes 0-3177157987/3177157988"))))
        assertFalse(ignoresRange(emptyMap())) // file:// downloads: keep seeking
    }
}
