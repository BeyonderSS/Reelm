package com.anistream.domain

import com.anistream.domain.model.MediaStream
import com.anistream.domain.usecase.canPlayPhone
import com.anistream.domain.usecase.pickStream
import org.junit.Assert.assertEquals
import org.junit.Test

class PickStreamTest {
    private fun s(src: String, h: Int, label: String = "${h}p") = MediaStream(url = "u$src$h", qualityLabel = label, height = h, sourceName = src)
    private val list = listOf(s("MovieBox", 480), s("4KHDHub", 1080), s("4KHDHub", 720))

    @Test fun fastPicksHighest() = assertEquals(1, pickStream(list, 20_000_000, null, ::canPlayPhone))
    @Test fun slowPicksFitting() = assertEquals(0, pickStream(list, 2_000_000, null, ::canPlayPhone))
    @Test fun pinWinsOnSlow() = assertEquals(1, pickStream(list, 2_000_000, 1080, ::canPlayPhone))
    @Test fun dolbyVisionExcluded() =
        assertEquals(1, pickStream(listOf(s("A", 1080, "1080p Dolby Vision"), s("B", 720)), 20_000_000, null, ::canPlayPhone))
}
