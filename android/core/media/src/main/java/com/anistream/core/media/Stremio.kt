package com.anistream.core.media

import com.anistream.domain.model.MediaDetail
import com.anistream.domain.model.MediaItem
import com.anistream.domain.model.MediaSeason
import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.SubtitleTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Pure parsing of Stremio addon protocol JSON (Cinemeta catalogs/meta, addon manifests and streams) + title matching. */
internal object Stremio {
    const val CINEMETA_ID = "com.linvo.cinemeta"
    const val CINEMETA_MANIFEST = "https://v3-cinemeta.strem.io/manifest.json"
    val CINEMETA_BASE = baseOf(CINEMETA_MANIFEST)

    fun baseOf(manifestUrl: String) = manifestUrl.removeSuffix("/manifest.json")

    fun typePath(type: MediaType) = if (type == MediaType.SERIES) "series" else "movie"

    data class Meta(val detail: MediaDetail, val years: IntRange?)

    data class Manifest(
        val id: String,
        val name: String,
        val description: String?,
        val resources: List<String>,
        val types: List<String>
    )

    fun parseMetas(root: JsonObject): List<MediaItem> =
        root.arr("metas")?.mapNotNull { (it as? JsonObject)?.let(::metaItem) } ?: emptyList()

    /** Cinemeta /meta/{type}/{id}.json; null when the meta is empty (wrong type for the id). */
    fun parseMeta(root: JsonObject): Meta? {
        val meta = root.obj("meta") ?: return null
        val item = metaItem(meta) ?: return null
        val seasons = meta.arr("videos").orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { v ->
                val season = v.str("season")?.toIntOrNull() ?: return@mapNotNull null
                val episode = (v.str("episode") ?: v.str("number"))?.toIntOrNull() ?: return@mapNotNull null
                if (season > 0) season to episode else null // season 0 = specials
            }
            .groupBy({ it.first }, { it.second })
            .map { (season, episodes) -> MediaSeason(season, episodes.distinct().size) }
            .sortedBy { it.number }
        val detail = MediaDetail(
            id = item.id,
            type = item.type,
            title = item.title,
            description = meta.str("description"),
            posterUrl = item.posterUrl,
            backdropUrl = meta.str("background"),
            year = item.year,
            rating = item.rating,
            runtime = meta.str("runtime"),
            country = meta.str("country"),
            genres = item.genres,
            cast = meta.strings("cast"),
            seasons = seasons,
            playable = false
        )
        return Meta(detail, yearRange(meta.str("releaseInfo") ?: meta.str("year")))
    }

    private fun metaItem(o: JsonObject): MediaItem? {
        val id = (o.str("id") ?: o.str("imdb_id"))?.takeIf { it.startsWith("tt") } ?: return null
        val type = when (o.str("type")) {
            "movie" -> MediaType.MOVIE
            "series" -> MediaType.SERIES
            else -> return null
        }
        return MediaItem(
            id = id,
            type = type,
            title = o.str("name") ?: return null,
            posterUrl = o.str("poster"),
            year = yearRange(o.str("releaseInfo") ?: o.str("year"))?.first,
            rating = o.str("imdbRating")?.toDoubleOrNull(),
            genres = o.strings("genres").ifEmpty { o.strings("genre") }
        )
    }

    /** "2014" → 2014..2014, "2008–2013" → 2008..2013, "2026–" → 2026..9999 (still running). */
    fun yearRange(s: String?): IntRange? {
        if (s == null) return null
        val years = Regex("\\d{4}").findAll(s).map { it.value.toInt() }.toList()
        val first = years.firstOrNull() ?: return null
        val last = when {
            years.size > 1 -> years.last()
            s.trimEnd().endsWith("–") || s.trimEnd().endsWith("-") -> 9999
            else -> first
        }
        return first..last
    }

    fun parseManifest(root: JsonObject): Manifest {
        val resources = root.arr("resources").orEmpty().mapNotNull { r ->
            (r as? JsonPrimitive)?.contentOrNull ?: (r as? JsonObject)?.str("name")
        }
        val id = root.str("id")
        val name = root.str("name")
        require(id != null && name != null && resources.isNotEmpty()) { "Not a Stremio addon manifest (needs id, name, resources)" }
        return Manifest(id, name, root.str("description"), resources, root.strings("types"))
    }

    /** Stremio /stream response → playable http(s) streams, best first. Torrent (infoHash-only) entries are skipped. */
    fun parseStreams(root: JsonObject, sourceName: String): List<MediaStream> =
        root.arr("streams").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val url = o.str("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
            val height = parseHeight(listOfNotNull(o.str("name"), o.str("title"), o.str("description")).joinToString(" "))
            val hints = o.obj("behaviorHints")
            val headers = hints?.obj("proxyHeaders")?.obj("request")
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                ?.toMap().orEmpty()
            val path = url.substringBefore('?').lowercase()
            MediaStream(
                url = url,
                qualityLabel = if (height > 0) "${height}p" else "AUTO",
                height = height,
                sizeBytes = hints?.str("videoSize")?.toLongOrNull(),
                headers = headers,
                subtitles = o.arr("subtitles").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { s ->
                    val subUrl = s.str("url") ?: return@mapNotNull null
                    val lang = s.str("lang") ?: "und"
                    SubtitleTrack(subUrl, lang, lang, if (subUrl.substringBefore('?').endsWith(".vtt")) "text/vtt" else "application/x-subrip")
                },
                sourceName = sourceName,
                isDirectFile = !(path.endsWith(".m3u8") || path.endsWith(".mpd")),
                languages = ReleaseLanguages.parse(listOfNotNull(o.str("title"), o.str("description")).joinToString(" "))
            )
        }.sortedByDescending { it.height }

    fun parseHeight(text: String): Int {
        Regex("(?i)\\b(2160|1440|1080|720|576|480|360|240)p\\b").find(text)?.let { return it.groupValues[1].toInt() }
        return if (Regex("(?i)\\b(4k|uhd)\\b").containsMatchIn(text)) 2160 else 0
    }

    // ---- MovieBox ↔ Cinemeta title matching

    private val BRACKETS = Regex("\\[[^]]*]|\\([^)]*\\)")

    /** lowercase, drop "[Hindi]"/"(…)" tags, punctuation and a trailing "S5"/"Season 5". */
    fun normalizeTitle(title: String): String = title.lowercase()
        .replace(BRACKETS, " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+(s\\d{1,2}|season \\d{1,2})$"), "")
        .trim()

    /** Title as a site search query: "[Hindi]"/"(…)" tags and a trailing "S5" dropped, punctuation kept. */
    fun searchQuery(title: String): String = title.replace(BRACKETS, " ")
        .replace(Regex("(?i)\\s+(s\\d{1,2}|season \\d{1,2})\\s*$"), "")
        .replace(Regex("\\s+"), " ").trim()

    /** Best MovieBox candidate for a Cinemeta title: same type, same normalised title, year within ±1; untagged titles first. */
    fun pickMatch(title: String, type: MediaType, years: IntRange?, candidates: List<MediaItem>): MediaItem? {
        val norm = normalizeTitle(title)
        return candidates.filter { c ->
            val y = c.year
            c.type == type && normalizeTitle(c.title) == norm &&
                (years == null || y == null || y in (years.first - 1)..(years.last + 1))
        }.minByOrNull { if (Regex("\\[[^]]*]").containsMatchIn(it.title)) 1 else 0 }
    }

    // ---- json accessors (kotlinx 1.7 dropped the *OrNull helpers)
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.strings(key: String): List<String> =
        arr(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
}
