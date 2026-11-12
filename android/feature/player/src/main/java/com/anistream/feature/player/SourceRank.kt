package com.anistream.feature.player

import android.content.Context
import com.anistream.domain.model.MediaStream
import kotlin.math.pow

/** A provider's decayed record on this device: plays that started, failed, stalled after starting, and time to first frame (ms). */
data class SrcStat(val ok: Double = 0.0, val fail: Double = 0.0, val stall: Double = 0.0, val ttff: Double = 0.0, val t: Long = 0)

/** Quality-first picking (port of web src-rank.ts): which source of a height to try first, and where to fall over to. */
internal object SourceRank {
    private const val HALF = 7 * 86_400_000.0 // a week's half-life: a provider that recovers climbs back

    /** "VegaMovies · VCloud" -> "VegaMovies" */
    fun provider(s: MediaStream) = s.sourceName.substringBefore(" · ").trim()

    private fun decay(s: SrcStat, now: Long): SrcStat {
        val k = 0.5.pow(maxOf(0L, now - s.t) / HALF)
        return SrcStat(s.ok * k, s.fail * k, s.stall * k, s.ttff, now)
    }

    /** 0..1, higher is better; an unknown provider scores 0.5 */
    fun score(s: SrcStat?, now: Long): Double {
        if (s == null) return 0.5
        val d = decay(s, now)
        return (d.ok + 1) / (d.ok + d.fail + d.stall / 2 + 2) - minOf(d.ttff / 1e5, 0.2) // 10 s to first frame costs 0.1
    }

    fun add(s: SrcStat?, now: Long, ok: Boolean = false, fail: Boolean = false, stall: Boolean = false, ttff: Long = 0): SrcStat {
        val d = decay(s ?: SrcStat(t = now), now)
        return d.copy(
            ok = d.ok + if (ok) 1 else 0, fail = d.fail + if (fail) 1 else 0, stall = d.stall + if (stall) 1 else 0,
            ttff = if (ttff <= 0) d.ttff else if (d.ttff > 0) 0.7 * d.ttff + 0.3 * ttff else ttff.toDouble()
        )
    }

    /**
     * Indexes of [list] to try, in order: [lang]'s sources first; [height] itself, then each lower height (nearest first), then higher;
     * within a height: what this device can decode, then provider reliability, then list order.
     */
    fun fallbackOrder(list: List<MediaStream>, lang: String?, height: Int, stats: Map<String, SrcStat>, now: Long, canPlay: (MediaStream) -> Boolean = { true }): List<Int> {
        val anyLang = lang != null && list.any { lang in it.languages }
        return list.indices.sortedWith(compareBy(
            { if (anyLang && lang !in list[it].languages) 1 else 0 },
            { list[it].height.let { h -> if (h == height) 0 else if (h < height) 1 else 2 } },
            { list[it].height.let { h -> if (h < height) -h else h } },
            { if (canPlay(list[it])) 0 else 1 },
            { -score(stats[provider(list[it])], now) }
        ))
    }

    /** Quality chips: (height, count) best first, among the sources carrying [lang] (all of them when none do). */
    fun qualities(list: List<MediaStream>, lang: String?): List<Pair<Int, Int>> =
        list.filter { lang == null || lang in it.languages }.ifEmpty { list }
            .filter { it.height > 0 }.groupingBy { it.height }.eachCount().toList().sortedByDescending { it.first }

    private fun prefs(c: Context) = c.getSharedPreferences("src_stats", Context.MODE_PRIVATE)

    fun load(c: Context): Map<String, SrcStat> = prefs(c).all.mapNotNull { (k, v) ->
        (v as? String)?.split(',')?.takeIf { it.size == 5 }?.let { p ->
            runCatching { k to SrcStat(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toLong()) }.getOrNull()
        }
    }.toMap()

    fun record(c: Context, s: MediaStream?, ok: Boolean = false, fail: Boolean = false, stall: Boolean = false, ttff: Long = 0) {
        val p = s?.let(::provider)?.takeIf { it.isNotBlank() } ?: return
        val n = add(load(c)[p], System.currentTimeMillis(), ok, fail, stall, ttff)
        prefs(c).edit().putString(p, "${n.ok},${n.fail},${n.stall},${n.ttff},${n.t}").apply()
    }

    /** "Show all sources" (advanced, per device) */
    fun showAll(c: Context) = c.getSharedPreferences("player_sources", Context.MODE_PRIVATE).getBoolean("all", false)
    fun setShowAll(c: Context, on: Boolean) = c.getSharedPreferences("player_sources", Context.MODE_PRIVATE).edit().putBoolean("all", on).apply()
}
