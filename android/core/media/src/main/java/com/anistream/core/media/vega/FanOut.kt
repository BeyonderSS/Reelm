package com.anistream.core.media.vega

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.pow

/** How one lookup asks every provider: most reliable first, a few at a time, at full speed only until there's enough (web vega.ts fanOut). */
internal object FanOut {
    private const val TRIP = 3
    private const val COOL_MS = 6 * 3_600_000L
    private const val HALF = 7 * 86_400_000.0

    /** Most reliable on this device first; the admin order breaks ties (the sort is stable). */
    fun order(ps: List<String>, score: (String) -> Double) = ps.sortedByDescending(score)

    /** 3 sources at [target] height or better, or 5 of any. */
    fun <T> enough(got: List<T>, target: Int, height: (T) -> Int) = got.count { height(it) >= target } >= 3 || got.size >= 5

    /** Breaker entry "fails,until": 3 failed searches in a row (network, DNS, 4xx) skip the provider for 6 h; a working search clears it. */
    fun tripped(entry: String?, now: Long) = (entry?.substringAfter(',')?.toLongOrNull() ?: 0) > now
    fun nextBreaker(entry: String?, failed: Boolean, now: Long): String? {
        if (!failed) return null
        val n = (entry?.substringBefore(',')?.toIntOrNull() ?: 0) + 1
        return "$n,${if (n >= TRIP) now + COOL_MS else 0}"
    }

    /** SourceRank.score (feature/player) over its stored "ok,fail,stall,ttff,t" line; 0.5 when unknown. */
    fun reliability(stat: String?, now: Long): Double {
        val p = stat?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 5 } ?: return 0.5
        val k = 0.5.pow(maxOf(0.0, now - p[4]) / HALF)
        return (p[0] * k + 1) / (p[0] * k + p[1] * k + p[2] * k / 2 + 2) - minOf(p[3] / 1e5, 0.2)
    }

    /**
     * Runs [one] over [ps] in [scope], at most [limit] at a time. Returns what's in once [enough] holds, all are done, or
     * [returnMs] passes; after that only [after] workers keep going and each later result goes to [late]. Nothing runs
     * past [deadlineMs]. [one] must not throw.
     */
    suspend fun <T> run(
        ps: List<String>, scope: CoroutineScope, limit: Int, after: Int, returnMs: Long, deadlineMs: Long,
        enough: (List<T>) -> Boolean, late: suspend (List<T>) -> Unit, one: suspend (String) -> List<T>
    ): List<T> {
        val queue = ConcurrentLinkedQueue(ps)
        val lock = Mutex()
        val got = mutableListOf<T>()
        var returned = false
        val result = CompletableDeferred<List<T>>()
        suspend fun hand(): List<T>? = lock.withLock { if (returned) null else { returned = true; got.toList() } }
        scope.launch {
            withTimeoutOrNull(deadlineMs) {
                coroutineScope {
                    repeat(limit) { w ->
                        launch {
                            while (true) {
                                val x = one(lock.withLock { if (w >= after && returned) null else queue.poll() } ?: break)
                                var isLate = false
                                val ready = lock.withLock {
                                    isLate = returned
                                    if (!returned) got += x
                                    if (!returned && enough(got)) got.toList().also { returned = true } else null
                                }
                                ready?.let(result::complete)
                                if (isLate && x.isNotEmpty()) late(x)
                            }
                        }
                    }
                }
            }
            hand()?.let(result::complete)
        }
        return withTimeoutOrNull(returnMs) { result.await() } ?: hand() ?: result.await()
    }
}
