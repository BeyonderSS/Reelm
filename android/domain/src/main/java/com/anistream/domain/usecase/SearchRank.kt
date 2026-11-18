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

/** 1 - edit distance / length; swapping two neighbouring letters is one typo ("visoin" -> "vision"), not two. */
private fun sim(a: String, b: String): Double {
    var pp = IntArray(0)
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1).also { it[0] = i }
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) cur[j] = minOf(cur[j], pp[j - 2] + 1)
        }
        pp = prev
        prev = cur
    }
    return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length, 1)
}

private val MARKS = Regex("\\p{Mn}+")
/** Spacing, case, accents and punctuation don't count: "vision quest" = "VisionQuest", "Pokémon" = "pokemon", "&" = "and". */
fun compact(t: String) = java.text.Normalizer.normalize(stripTags(t), java.text.Normalizer.Form.NFD).replace(MARKS, "").lowercase()
    .replace("&", "and").replace(Regex("[^\\p{L}\\p{N}]+"), "")

/**
 * Mean over query tokens of the best similarity to a title token, plus small exact / prefix bonuses, less a little per extra title word
 * ("Interstellar" above "Interstellar Wars"). The space-free forms are compared too, so a split or joined word still matches.
 */
fun titleScore(query: String, title: String): Double {
    val q = tokens(query)
    val t = tokens(title)
    val qc = compact(query)
    val tc = compact(title)
    if (q.isEmpty() || t.isEmpty() || qc.isEmpty() || tc.isEmpty()) return 0.0
    if (qc == tc) return 1.15
    val base = q.sumOf { w -> t.maxOf { sim(w, it) } } / q.size
    val words = base + (if (q == t) 0.1 else 0.0) + (if (t.size >= q.size && t.subList(0, q.size) == q) 0.05 else 0.0) - minOf(0.3, 0.05 * maxOf(0, t.size - q.size))
    return maxOf(words, sim(qc, tc) + if (tc.startsWith(qc)) 0.05 else 0.0)
}

/** Scores within 0.05 count as the same match; there the rated, then the newer title goes first ("VisionQuest" 2026, 8.3 over the 1985 film). */
fun band(score: Double) = Math.round(score * 20).toInt()

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
        .sortedWith(compareBy({ -band(it.second) }, { -(it.first.rating ?: 0.0) }, { -(it.first.year ?: 0) }, { source(it.first.id) }))  // stable: keeps each source's own order
        .forEach { (item, _) ->
            val key = dedupeKey(item.title)
            val i = out.indexOfFirst { dedupeKey(it.title) == key && (it.year == null || item.year == null || abs(it.year!! - item.year!!) <= 1) }
            if (i < 0) out += item else {
                val (w, l) = if (prefer(item.id) < prefer(out[i].id)) item to out[i] else out[i] to item
                // the streamable MovieBox copy is "Inception [Hindi]"; show the other source's canonical title
                out[i] = if (TAGS.containsMatchIn(w.title) && !TAGS.containsMatchIn(l.title)) w.copy(title = l.title) else w
            }
        }
    return out
}
