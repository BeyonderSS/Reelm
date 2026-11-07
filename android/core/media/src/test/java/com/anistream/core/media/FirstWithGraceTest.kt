package com.anistream.core.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstWithGraceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun src(ms: Long, vararg v: String): suspend () -> List<String> = { delay(ms); v.toList() }

    @Test fun slowSourceArrivesLate() = runBlocking {
        val late = CompletableDeferred<List<String>>()
        val got = firstWithGrace(listOf(src(100, "a"), src(300, "b"), src(2_000, "c")), scope, 10_000, 500) { late.complete(it) }
        assertEquals(listOf("a", "b"), got.flatMap { it.getOrThrow() })
        assertEquals(listOf("c"), withTimeout(5_000) { late.await() })
    }

    @Test fun allInTimeAreReturnedInFetchOrder() = runBlocking {
        val got = firstWithGrace(listOf(src(200, "a"), src(50, "b")), scope, 10_000, 1_000) {}
        assertEquals(listOf("a", "b"), got.flatMap { it.getOrThrow() })
    }

    @Test fun emptyFirstSourceDoesNotStartTheGrace() = runBlocking {
        // the only real streams come after the grace would have run out if an empty answer had started it
        val got = firstWithGrace(listOf(src(10), src(800, "x")), scope, 10_000, 300) {}
        assertEquals(listOf("x"), got.flatMap { it.getOrThrow() })
    }

    @Test fun failuresAreReturnedNotThrown() = runBlocking {
        val boom: suspend () -> List<String> = { error("down") }
        val got = firstWithGrace(listOf(boom, src(50, "a")), scope, 10_000, 500) {}
        assertEquals(2, got.size)
        assertEquals(true, got[0].isFailure)
    }
}
