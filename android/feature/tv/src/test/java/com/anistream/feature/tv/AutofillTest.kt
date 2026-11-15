package com.anistream.feature.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutofillTest {
    @Test fun firstMatchingTitleInOrderWins() {
        assertEquals("Death Note", autofillTitle("de", listOf("Kimetsu no Yaiba: Demon Slayer", "Death Note", "Demon Slayer")))
    }

    @Test fun articlesAndCaseAreIgnored() {
        assertEquals("Matrix", autofillTitle("MAT", listOf("The Matrix")))
    }

    @Test fun trademarkSignsNeverComplete() {
        assertNull(autofillTitle("vision quest", listOf("The Vision Quest™")))
        assertEquals("Vision Quest 2", autofillTitle("vision", listOf("Vision® Quest 2")))
    }

    @Test fun nothingWhenNoTitleStartsWithQuery() {
        assertNull(autofillTitle("zz", listOf("Death Note")))
        assertNull(autofillTitle("", listOf("Death Note")))
        assertNull(autofillTitle("death note", listOf("Death Note")))
    }
}
