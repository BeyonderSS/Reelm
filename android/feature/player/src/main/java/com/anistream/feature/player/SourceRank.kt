package com.anistream.feature.player

import android.content.Context
import com.anistream.core.common.LowEnd
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

    /** Providers whose HEVC is x265 10-bit even when the release name doesn't say so. */
    private val TEN_BIT_HEVC = setOf("4khdhub") // native "4KHDHub" and Vega's "4khdhub · GDrive"

    /** Before loading: without HEVC Main10 ([main10]), 10-bit/HDR/DV by label, or HEVC from a 10-bit provider, most likely won't decode. */
    fun likelyUndecodable(s: MediaStream, main10: Boolean): Boolean {
        if (main10) return false
        val t = listOfNotNull(s.codec, s.qualityLabel).joinToString(" ")
        return LowEnd.isHighBitDepth(t) || (LowEnd.isHevc(t) && provider(s).lowercase() in TEN_BIT_HEVC)
    }

    private val MAIN10 = Regex("""^(hvc1|hev1)\.[ABC]?2\.|^(dvh1|dvhe)\b""", RegexOption.IGNORE_CASE)

    /** After loading: the selected video format is 10-bit (HEVC Main10 "hvc1.2.4.L120", Dolby Vision, or an HDR transfer). */
    fun isMain10(codecs: String?, mime: String?, hdr: Boolean): Boolean =
        hdr || mime == "video/dolby-vision" || (codecs != null && MAIN10.containsMatchIn(codecs))

    /** Stats key for a host's start time: "@pixeldrain.dev" (random worker / bucket subdomains share their site's). */
    fun hostKey(url: String): String? = Regex("""^\w+://([^/:?#]+)""").find(url)?.groupValues?.get(1)?.lowercase()?.split('.')?.takeLast(2)?.joinToString(".", "@")

    private const val SLOW_START_MS = 3_000.0

    /**
     * Starts slowly (over 3 s to the first frame): this device's own first-frame average for the host when it has one,
     * else what measured slow from here (2026-11-17, first 2 MB / first HLS segment): Google's "download only" files
     * 1.8–6 s, Pixeldrain's ?download, adaptive 4K HLS 4.4–6.1 s; CF Storage, MovieBox and castle took 0.3–2 s.
     */
    fun slowStart(s: MediaStream, stats: Map<String, SrcStat>): Boolean {
        hostKey(s.url)?.let { stats[it] }?.ttff?.takeIf { it > 0 }?.let { return it > SLOW_START_MS }
        val u = s.url.lowercase()
        return "download only" in s.qualityLabel.lowercase() || "video-downloads.googleusercontent.com" in u ||
            ("pixeldrain" in u && "download" in u) || (!s.isDirectFile && s.height >= 2160)
    }

    /** The title's audio as far as auto-pick needs it: [lang] its original language when known, [indian] an Indian original. */
    data class Origin(val lang: String?, val indian: Boolean)

    private val REGIONAL = setOf("hi", "ta", "te", "ml", "kn", "bn", "mr")
    private val COUNTRY_LANG = listOf(
        "india" to "", "united states" to "en", "usa" to "en", "united kingdom" to "en", "uk" to "en", "canada" to "en", "australia" to "en",
        "ireland" to "en", "new zealand" to "en", "korea" to "ko", "japan" to "ja", "china" to "zh", "hong kong" to "zh", "taiwan" to "zh",
        "france" to "fr", "spain" to "es", "mexico" to "es", "argentina" to "es", "germany" to "de", "italy" to "it", "turkey" to "tr",
        "thailand" to "th", "russia" to "ru", "brazil" to "pt", "portugal" to "pt"
    )

    /**
     * From the title's first country ("United States, United Kingdom" → en; India → Indian, its language the one most of
     * [list] carry, Hindi on a tie); without a country the title counts as Indian only when every source names an Indian language.
     */
    fun origin(country: String?, list: List<MediaStream>): Origin {
        val first = country?.split(',', '/')?.firstOrNull()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val code = first?.let { c -> COUNTRY_LANG.firstOrNull { (k, _) -> c == k || c.contains(k) && k.length > 3 }?.second }
        val indian = if (first != null) code == "" else list.isNotEmpty() && list.all { s -> s.languages.isNotEmpty() && s.languages.any { it in REGIONAL } }
        if (!indian) return Origin(code?.takeIf { it.isNotEmpty() }, false)
        val counts = list.flatMap { it.languages.filter { l -> l in REGIONAL } }.groupingBy { it }.eachCount()
        return Origin(counts.maxByOrNull { (l, n) -> n * 2 + if (l == "hi") 1 else 0 }?.key ?: "hi", true)
    }

    /**
     * Audio rank within a height, lower first: the original language → unlabelled (usually the original audio) → English →
     * other multi-audio → a single-language regional dub (Tamil, Hindi … only) of a non-Indian title, which never beats
     * the rest: castle's "Inception - Tamil - 1080p" was auto-picked over the English files.
     */
    fun dubRank(s: MediaStream, o: Origin): Int {
        val l = s.languages
        return when {
            o.lang != null && o.lang in l -> 0
            l.isEmpty() -> 1
            "en" in l -> 2
            !o.indian && l.size == 1 && l[0] in REGIONAL -> 4
            else -> 3
        }
    }

    /**
     * Indexes of [list] to try, in order: [lang]'s sources first; within them what this device can decode at all ([undecodable] last, whatever its height); [height] itself, then each lower height (nearest first), then higher;
     * within a height: the title's own audio ([dubRank] by [origin]), what this device can decode, then what starts fast ([slowStart] last), then provider reliability, then list order.
     */
    fun fallbackOrder(list: List<MediaStream>, lang: String?, height: Int, stats: Map<String, SrcStat>, now: Long, canPlay: (MediaStream) -> Boolean = { true }, undecodable: (MediaStream) -> Boolean = { false }, origin: Origin? = null): List<Int> {
        val anyLang = lang != null && list.any { lang in it.languages }
        return list.indices.sortedWith(compareBy(
            { if (anyLang && lang !in list[it].languages) 1 else 0 },
            { if (undecodable(list[it])) 1 else 0 },
            { list[it].height.let { h -> if (h == height) 0 else if (h < height) 1 else 2 } },
            { list[it].height.let { h -> if (h < height) -h else h } },
            { if (origin == null) 0 else dubRank(list[it], origin) },
            { if (canPlay(list[it])) 0 else 1 },
            { if (slowStart(list[it], stats)) 1 else 0 },
            { -score(stats[provider(list[it])], now) }
        ))
    }

    /** "45 min", "2h 10m", "1h", "130", or seconds ("5400") → minutes; null when unknown. */
    fun runtimeMinutes(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val h = Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toInt()
        val m = Regex("""(\d+)\s*m""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toInt()
        if (h != null || m != null) return (h ?: 0) * 60 + (m ?: 0)
        return s.trim().toIntOrNull()?.let { if (it > 600) it / 60 else it }?.takeIf { it > 0 }
    }

    /**
     * NetMirror's "Too Many Requests – STOP Abuse" clip (~10 min) plays fine instead of the film: a file under 11 min when the
     * title is known to run 20+ min ([expectedMin]: TMDB/Cinemeta runtime or an earlier play's duration), or is a movie.
     */
    fun isPlaceholder(durationMs: Long, expectedMin: Int?, movie: Boolean): Boolean =
        durationMs in 1 until 11 * 60_000L && (if (expectedMin != null) expectedMin >= 20 else movie)

    /** First of [order] whose url hasn't failed yet; null when every source has. */
    fun nextUntried(list: List<MediaStream>, order: List<Int>, tried: Set<String>): Int? = order.firstOrNull { list[it].url !in tried }

    /** Quality chips: (height, count) best first, among the sources carrying [lang] (all of them when none do). */
    fun qualities(list: List<MediaStream>, lang: String?): List<Pair<Int, Int>> =
        inLang(list, lang).groupingBy { it.height }.eachCount().toList().sortedByDescending { it.first }

    /** Heights (among the chips) whose every source is [undecodable]. */
    fun undecodableHeights(list: List<MediaStream>, lang: String?, undecodable: (MediaStream) -> Boolean): Set<Int> =
        inLang(list, lang).groupBy { it.height }.filterValues { it.all(undecodable) }.keys

    private fun inLang(list: List<MediaStream>, lang: String?) =
        list.filter { lang == null || lang in it.languages }.ifEmpty { list }.filter { it.height > 0 }

    private fun prefs(c: Context) = c.getSharedPreferences("src_stats", Context.MODE_PRIVATE)

    fun load(c: Context): Map<String, SrcStat> = prefs(c).all.mapNotNull { (k, v) ->
        (v as? String)?.split(',')?.takeIf { it.size == 5 }?.let { p ->
            runCatching { k to SrcStat(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toLong()) }.getOrNull()
        }
    }.toMap()

    /** Updates the provider's record; a first-frame time also goes to the host's ([hostKey]) for [slowStart]. */
    fun record(c: Context, s: MediaStream?, ok: Boolean = false, fail: Boolean = false, stall: Boolean = false, ttff: Long = 0) {
        val p = s?.let(::provider)?.takeIf { it.isNotBlank() } ?: return
        val all = load(c)
        val now = System.currentTimeMillis()
        val e = prefs(c).edit()
        fun put(k: String, n: SrcStat) = e.putString(k, "${n.ok},${n.fail},${n.stall},${n.ttff},${n.t}")
        put(p, add(all[p], now, ok, fail, stall, ttff))
        if (ttff > 0) hostKey(s.url)?.let { put(it, add(all[it], now, ttff = ttff)) }
        e.apply()
    }

    /** "Show all sources" (advanced, per device) */
    fun showAll(c: Context) = c.getSharedPreferences("player_sources", Context.MODE_PRIVATE).getBoolean("all", false)
    fun setShowAll(c: Context, on: Boolean) = c.getSharedPreferences("player_sources", Context.MODE_PRIVATE).edit().putBoolean("all", on).apply()
}
