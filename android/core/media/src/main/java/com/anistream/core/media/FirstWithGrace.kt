package com.anistream.core.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs every fetch in [scope] (each capped at [timeoutMs]) and returns, in fetch order, the results that arrived before
 * the cut: all of them, or those in by [graceMs] after the first non-empty one. Fetches still running keep going in
 * [scope]; each later non-empty result goes to [onLate].
 */
internal suspend fun <T> firstWithGrace(
    fetches: List<suspend () -> List<T>>,
    scope: CoroutineScope,
    timeoutMs: Long,
    graceMs: Long,
    onLate: suspend (List<T>) -> Unit,
): List<Result<List<T>>> {
    val done = arrayOfNulls<Result<List<T>>>(fetches.size)
    val arrivals = Channel<Int>(Channel.UNLIMITED)
    val jobs = fetches.mapIndexed { i, fetch ->
        scope.launch {
            done[i] = runCatching { withTimeout(timeoutMs) { fetch() } }
            arrivals.send(i)
        }
    }
    val returned = BooleanArray(fetches.size)
    var firstAt = 0L
    withTimeoutOrNull(timeoutMs + 1_000) {
        repeat(fetches.size) {
            val i = if (firstAt == 0L) arrivals.receive()
            else withTimeoutOrNull((graceMs - (System.currentTimeMillis() - firstAt)).coerceAtLeast(1)) { arrivals.receive() } ?: return@withTimeoutOrNull
            returned[i] = true
            if (firstAt == 0L && done[i]?.getOrNull().orEmpty().isNotEmpty()) firstAt = System.currentTimeMillis()
        }
    }
    scope.launch { for (i in arrivals) done[i]?.getOrNull()?.takeIf { it.isNotEmpty() }?.let { onLate(it) } }
    scope.launch { jobs.joinAll(); arrivals.close() }
    return done.indices.filter { returned[it] }.map { done[it]!! }
}
