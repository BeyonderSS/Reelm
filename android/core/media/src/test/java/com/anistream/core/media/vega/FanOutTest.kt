package com.anistream.core.media.vega

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class FanOutTest {
    private val hour = 3_600_000L

    @Test
    fun orderByReliabilityThenAdmin() {
        val now = 10 * 86_400_000L
        val stats = mapOf("c" to "5.0,0.0,0.0,0.0,$now", "d" to "0.0,4.0,0.0,0.0,$now")
        assertEquals(listOf("c", "a", "b", "d"), FanOut.order(listOf("a", "b", "c", "d")) { FanOut.reliability(stats[it], now) })
        assertEquals(0.5, FanOut.reliability(null, now), 1e-9)
    }

    @Test
    fun enoughAtTargetOrFiveOverall() {
        assertTrue(FanOut.enough(listOf(1080, 2160, 1080), 1080) { it })
        assertFalse(FanOut.enough(listOf(1080, 1080, 720, 720), 1080) { it })
        assertTrue(FanOut.enough(listOf(480, 480, 480, 480, 480), 1080) { it })
        assertTrue(FanOut.enough(listOf(720, 720, 720), 720) { it })
    }

    @Test
    fun breakerTripsAfterThreeAndResets() {
        var e: String? = null
        repeat(2) { e = FanOut.nextBreaker(e, true, 0) }
        assertFalse(FanOut.tripped(e, 0))
        e = FanOut.nextBreaker(e, true, 0)
        assertTrue(FanOut.tripped(e, 5 * hour))
        assertFalse(FanOut.tripped(e, 6 * hour + 1))
        assertTrue(FanOut.tripped(FanOut.nextBreaker(e, true, 7 * hour), 8 * hour)) // one more failure after the cool-off trips it again
        assertNull(FanOut.nextBreaker(e, false, 0))
    }

    @Test
    fun returnsAtEnoughThenOneAtATimeIntoLate() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val live = AtomicInteger(); val liveAfter = AtomicInteger(); val peak = AtomicInteger(); val peakAfter = AtomicInteger()
        val done = AtomicInteger()
        val reached = java.util.concurrent.atomic.AtomicBoolean()
        val late = Collections.synchronizedList(mutableListOf<Int>())
        val got = FanOut.run(
            ('a'..'h').map { "$it" }, scope, limit = 3, after = 1, returnMs = 5_000, deadlineMs = 5_000,
            enough = { FanOut.enough(it, 1080) { h -> h }.also { e -> if (e) reached.set(true) } }, late = { late += it }
        ) {
            val after = reached.get()
            peak.accumulateAndGet(live.incrementAndGet(), ::maxOf)
            if (after) peakAfter.accumulateAndGet(liveAfter.incrementAndGet(), ::maxOf)
            delay(30)
            live.decrementAndGet(); if (after) liveAfter.decrementAndGet()
            done.incrementAndGet()
            listOf(1080)
        }
        assertEquals(3, got.size) // returned at the third 1080p source
        while (got.size + late.size < 8) delay(10)
        assertEquals(3, peak.get())
        assertEquals(1, peakAfter.get()) // in-flight ones finish; new ones start one at a time
    }

    @Test
    fun returnsByReturnMsAndStopsAtDeadline() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val t0 = System.currentTimeMillis()
        val late = Collections.synchronizedList(mutableListOf<Int>())
        val got = FanOut.run(listOf("slow", "x", "y"), scope, limit = 2, after = 1, returnMs = 100, deadlineMs = 300,
            enough = { false }, late = { late += it }) { p -> if (p == "slow") awaitCancellation() else { delay(150); listOf(720) } }
        assertTrue(System.currentTimeMillis() - t0 < 140)
        assertEquals(0, got.size)
        delay(500)
        assertEquals(listOf(720), late.toList()) // x lands late; its worker then retires (after = 1), and "slow" is cut at 300 ms
    }
}
