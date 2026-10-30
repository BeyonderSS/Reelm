package com.anistream.domain.usecase

import com.anistream.domain.MaturePolicy
import com.anistream.domain.model.MediaItem
import kotlin.math.abs

private val TAGS = Regex("\\[.*?]")

/** "Gangs of Wasseypur [Hindi]" -> "Gangs of Wasseypur". */
fun stripTags(t: String) = t.replace(TAGS, " ").trim()

private fun tokens(t: String): List<String> =
    stripTags(t).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotEmpty() && it != "part" }
        .let { if (it.size > 1 && it[0] == "the") it.drop(1) else it }

private fun sim(a: String, b: String): Double {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1).also { it[0] = i }
        for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        prev = cur
    }
    return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length, 1)
}

/** Mean over query tokens of the best similarity to a title token, plus small exact / prefix bonuses. */
fun titleScore(query: String, title: String): Double {
    val q = tokens(query)
    val t = tokens(title)
    if (q.isEmpty() || t.isEmpty()) return 0.0
    val base = q.sumOf { w -> t.maxOf { sim(w, it) } } / q.size
    return base + (if (q == t) 0.1 else 0.0) + (if (t.size >= q.size && t.subList(0, q.size) == q) 0.05 else 0.0)
}

/** Title of the best-matching item, tags stripped; null when nothing scores. */
fun bestTitle(query: String, items: List<MediaItem>): String? =
    items.maxByOrNull { titleScore(query, it.title) }?.takeIf { titleScore(query, it.title) > 0.0 }?.let { stripTags(it.title) }

private fun source(id: String) = when { id.startsWith("mb:") -> 0; id.startsWith("tmdb:") -> 1; else -> 2 }
// dedupe preference: MovieBox, then Cinemeta (tt id), then TMDB
private fun prefer(id: String) = when { id.startsWith("mb:") -> 0; id.startsWith("tt") -> 1; else -> 2 }
// "Part 1" == no part number; "Part 2" stays distinct
private fun dedupeKey(title: String) = tokens(title).joinToString(" ").removeSuffix(" 1")

/**
 * Merge MovieBox, TMDB and Cinemeta results (ids "mb:", "tmdb:", "tt…"): hide mature/non-film MovieBox items, drop
 * weak matches (< 0.6), sort by score then source, collapse same title (+-1 year) keeping the preferred source.
 */
fun rankSearch(query: String, lists: List<List<MediaItem>>, hideMature: Boolean = MaturePolicy.active): List<MediaItem> {
    val out = ArrayList<MediaItem>()
    lists.flatten()
        .filter { !(hideMature && it.id.startsWith("mb:") && MaturePolicy.hides(it.genres, it.title)) }
        .map { it to titleScore(query, it.title) }
        .filter { it.second >= 0.6 }
        .sortedWith(compareBy({ -it.second }, { source(it.first.id) }))  // stable: keeps each source's own order
        .forEach { (item, _) ->
            val key = dedupeKey(item.title)
            val i = out.indexOfFirst { dedupeKey(it.title) == key && (it.year == null || item.year == null || abs(it.year!! - item.year!!) <= 1) }
            if (i < 0) out += item else if (prefer(item.id) < prefer(out[i].id)) out[i] = item
        }
    return out
}
