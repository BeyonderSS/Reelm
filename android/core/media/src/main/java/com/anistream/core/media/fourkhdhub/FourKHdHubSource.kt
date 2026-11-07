package com.anistream.core.media.fourkhdhub

import com.anistream.core.media.MediaHttp
import com.anistream.core.media.ReleaseLanguages
import com.anistream.core.media.StreamSource
import com.anistream.core.media.Stremio
import com.anistream.domain.model.MediaItem
import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 4KHDHub (4khdhub.one): WordPress-style scraper. Release links go through GreenMotors → HubDrive → HubCloud →
 * a resolver page whose buttons point at the file CDNs. Ported from MovieBox-TUI (Apache-2.0).
 */
@Singleton
class FourKHdHubSource @Inject constructor(private val http: MediaHttp) : StreamSource {

    override suspend fun streams(title: String, year: Int?, type: MediaType, season: Int, episode: Int): List<MediaStream> {
        val query = URLEncoder.encode(Stremio.searchQuery(title), "UTF-8")
        val hits = FourKHdHubParser.search(http.get("$BASE/?s=$query"))
        // ponytail: series match ignores the year (a MovieBox per-season subject carries that season's year, not the first).
        val years = if (type == MediaType.MOVIE) year?.let { it..it } else null
        val match = Stremio.pickMatch(title, type, years, hits) ?: return emptyList()
        val releases = FourKHdHubParser.releases(http.get(BASE + match.id), season, episode)
            .groupBy { it.height }.values.flatMap { it.take(PER_QUALITY) }.take(MAX_RELEASES)
        // Within a quality, seekable (Range-capable) links first: a non-seekable 25 GB file can't be scrubbed.
        return coroutineScope {
            releases.map { r -> async { withTimeoutOrNull(RELEASE_TIMEOUT_MS) { resolve(r) } } }.awaitAll()
        }.filterNotNull().sortedWith(compareByDescending<Pair<MediaStream, Boolean>> { it.first.height }.thenBy { !it.second })
            .map { it.first }
    }

    /** Best verified link of one release (+ whether it's seekable), or null. */
    private suspend fun resolve(release: FourKHdHubParser.Release): Pair<MediaStream, Boolean>? = coroutineScope {
        val candidates = release.links.map { link -> async { runCatching { mirror(link, 0) }.getOrNull().orEmpty() } }
            .awaitAll().flatten().distinctBy { it.first }.sortedBy { FourKHdHubParser.score(it.first, it.second) }.take(MAX_PROBES)
        val playable = candidates.map { (url, label) ->
            async { runCatching { verify(url) }.getOrNull()?.let { Triple(it.first, it.second, FourKHdHubParser.score(url, label)) } }
        }.awaitAll().filterNotNull()
        val best = playable.minWithOrNull(compareBy({ !it.second }, { it.third })) ?: return@coroutineScope null
        val m3u8 = best.first.substringBefore('?').lowercase().endsWith(".m3u8")
        MediaStream(
            url = best.first,
            qualityLabel = if (release.height > 0) "${release.height}p" else "AUTO",
            height = release.height,
            codec = FourKHdHubParser.codec(release.title),
            sizeBytes = release.sizeBytes,
            headers = HEADERS,
            sourceName = "4KHDHub",
            isDirectFile = !m3u8,
            mimeType = if (m3u8) "application/x-mpegURL" else null,
            languages = ReleaseLanguages.parse(release.title)
        ) to best.second
    }

    /** A release/mirror link → (url, label) candidates on file CDNs. */
    private suspend fun mirror(url: String, depth: Int): List<Pair<String, String>> {
        require(depth < 4) { "mirror redirect loop" }
        val host = url.toHttpUrlOrNull()?.host ?: return emptyList()
        return when {
            "greenmotors." in host || "greenmountmotors." in host ->
                FourKHdHubParser.greenMotorsTarget(http.get(url))?.let { mirror(it, depth + 1) }.orEmpty()
            "hubdrive." in host -> FourKHdHubParser.hubDriveTarget(http.get(url))?.let { mirror(it, depth + 1) }.orEmpty()
            "hubcloud." in host && "/drive/" in url -> {
                val resolver = FourKHdHubParser.hubCloudResolver(http.get(url)) ?: return emptyList()
                FourKHdHubParser.candidates(http.get(resolver))
            }
            else -> listOfNotNull(FourKHdHubParser.playbackUrl(url)?.let { it to "Direct" })
        }
    }

    /** Final URL + seekable when [url] answers with media (unwrapping "…?link=<file>" interstitials); throws otherwise. */
    private suspend fun verify(url: String): Pair<String, Boolean> {
        val p = http.probe(url, HEADERS)
        if (FourKHdHubParser.isMedia(p.contentType)) return p.url to (p.code == 206)
        val wrapped = p.url.toHttpUrlOrNull()?.queryParameter("link")?.let(FourKHdHubParser::playbackUrl)
            ?: error("not media: ${p.contentType}")
        val w = http.probe(wrapped, HEADERS)
        require(FourKHdHubParser.isMedia(w.contentType)) { "not media: ${w.contentType}" }
        return w.url to (w.code == 206)
    }

    private companion object {
        const val BASE = "https://4khdhub.one"
        val HEADERS = mapOf("Referer" to BASE, "User-Agent" to MediaHttp.USER_AGENT)
        const val PER_QUALITY = 2
        const val MAX_RELEASES = 4
        const val MAX_PROBES = 6
        const val RELEASE_TIMEOUT_MS = 30_000L // generous for slow networks; MoviesRepositoryImpl caps the source at 45 s
    }
}

/** Pure HTML/JS parsing for 4KHDHub and its HubCloud mirror chain (regex; the markup is simple and stable). */
internal object FourKHdHubParser {
    data class Release(val title: String, val height: Int, val sizeBytes: Long?, val links: List<String>)

    private val CARD = Regex("""<a href="(/[^"]+)" class="movie-card"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val CARD_TITLE = Regex("""class="movie-card-title">([^<]*)<""")
    private val CARD_META = Regex("""class="movie-card-meta">([^<]*)<""")
    private val FILE_TITLE = Regex("""class="(?:episode-)?file-title">\s*([^<]*?)\s*<""")
    private val SIZE = Regex("""(\d+(?:\.\d+)?)\s*(GB|MB)\b""")
    private val HREF = Regex("""href="(https://[^"]+)"""")
    private val SXE = Regex("""(?i)S(\d{1,2})E(\d{1,3})""")
    private val ANCHOR = Regex("""<a\s([^>]*)>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val PIXELDRAIN = Regex("""https://(pixeldrain\.(?:dev|com))/(?:u|api/file)/([A-Za-z0-9_-]+)""")
    private val MIRROR_HOSTS = listOf("hubcloud.", "hubdrive.", "greenmotors.", "greenmountmotors.", "pixeldrain.")

    fun search(html: String): List<MediaItem> = CARD.findAll(html).mapNotNull { m ->
        val href = m.groupValues[1]
        val title = CARD_TITLE.find(m.groupValues[2])?.groupValues?.get(1)?.let(::unescape)?.trim()
        if (title.isNullOrEmpty()) return@mapNotNull null
        MediaItem(
            id = href,
            type = if ("-series-" in href) MediaType.SERIES else MediaType.MOVIE,
            title = title,
            year = CARD_META.find(m.groupValues[2])?.groupValues?.get(1)?.let { Regex("\\d{4}").find(it)?.value?.toInt() }
        )
    }.toList()

    /** Movie (season 0) download items or the matching episode's items; best quality first, smaller file first within it. */
    fun releases(html: String, season: Int, episode: Int): List<Release> {
        val items = if (season > 0) {
            html.substringAfter("id=\"episodes\"", "").split("class=\"episode-download-item\"").drop(1)
        } else {
            html.split("class=\"download-item ").drop(1)
        }
        return items.mapNotNull { chunk ->
            val title = FILE_TITLE.find(chunk)?.groupValues?.get(1)?.let(::unescape)
            if (title.isNullOrEmpty() || isArchive(title)) return@mapNotNull null
            if (season > 0 && seasonEpisode(title) != season to episode) return@mapNotNull null
            val links = HREF.findAll(chunk).map { unescape(it.groupValues[1]) }
                .filter { link -> "logout" !in link && MIRROR_HOSTS.any { it in link } }.distinct().toList()
            if (links.isEmpty()) null else Release(title, Stremio.parseHeight(title), SIZE.find(chunk)?.let(::sizeBytes), links)
        }.distinctBy { it.title.lowercase().filter(Char::isLetterOrDigit) }
            .sortedWith(compareByDescending<Release> { it.height }.thenBy { it.sizeBytes ?: Long.MAX_VALUE })
    }

    fun seasonEpisode(title: String): Pair<Int, Int>? =
        SXE.find(title)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    private fun isArchive(title: String) = title.lowercase().let { it.endsWith(".zip") || "complete season" in it || "season pack" in it }

    private fun sizeBytes(m: MatchResult): Long {
        val n = m.groupValues[1].toDouble()
        return (n * if (m.groupValues[2] == "GB") 1024.0 * 1024 * 1024 else 1024.0 * 1024).toLong()
    }

    fun codec(title: String): String? {
        val t = title.lowercase()
        return when {
            "av1" in t -> "AV1"
            listOf("hevc", "x265", "h.265", "h265").any { it in t } -> "HEVC"
            listOf("avc", "x264", "h.264", "h264").any { it in t } -> "H.264"
            else -> null
        }
    }

    /** GreenMotors interstitial: s('o','<payload>',…) where payload = b64(b64(rot13(b64(json{"o": b64(target)})))). */
    fun greenMotorsTarget(html: String): String? = runCatching {
        val payload = Regex("""s\(\s*['"]o['"]\s*,\s*['"]([^'"]+)['"]""").find(html)!!.groupValues[1]
        val json = b64(rot13(b64(b64(payload))))
        val o = (Json.parseToJsonElement(json) as JsonObject)["o"] as JsonPrimitive
        b64(o.contentOrNull!!).takeIf { it.startsWith("https://") }
    }.getOrNull()

    fun hubDriveTarget(html: String): String? =
        HREF.findAll(html).map { unescape(it.groupValues[1]) }.firstOrNull { Regex("""^https://[^/]*hubcloud\.[^/]+/drive/""").containsMatchIn(it) }

    /** HubCloud /drive/ page → its "Generate Direct Download Link" resolver URL. */
    fun hubCloudResolver(html: String): String? =
        ANCHOR.findAll(html).firstOrNull { """id="download"""" in it.groupValues[1] }
            ?.let { HREF.find(it.groupValues[1])?.groupValues?.get(1) }?.let(::unescape)
            ?: Regex("""https://[^"'\s]*hubcloud\.php[^"'\s]*""").find(html)?.value?.let(::unescape)

    /** Resolver page → (url, label) download buttons plus any PixelDrain file, best-scored first. */
    fun candidates(html: String): List<Pair<String, String>> {
        val buttons = ANCHOR.findAll(html).mapNotNull { m ->
            val attrs = m.groupValues[1]
            if (!Regex("""class="[^"]*\bbtn""").containsMatchIn(attrs)) return@mapNotNull null
            val href = Regex("""href="([^"]+)"""").find(attrs)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
            val label = unescape(m.groupValues[2].replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
            val url = watchOnline(href) ?: pixeldrainApi(href) ?: href
            playbackUrl(url)?.let { it to label.ifEmpty { "Direct" } }
        }
        val pixel = PIXELDRAIN.findAll(html).map { "https://${it.groupValues[1]}/api/file/${it.groupValues[2]}?download" to "PixelDrain" }
        return (pixel + buttons).distinctBy { it.first }.sortedBy { score(it.first, it.second) }.toList()
    }

    private fun pixeldrainApi(url: String) = PIXELDRAIN.find(url)?.let { "https://${it.groupValues[1]}/api/file/${it.groupValues[2]}?download" }

    /** "Watch Online" buttons wrap the file URL as base64 in ?u= on a pages.dev player. */
    private fun watchOnline(url: String): String? {
        val u = url.toHttpUrlOrNull()?.takeIf { "pages.dev" in it.host }?.queryParameter("u") ?: return null
        return runCatching { b64(u) }.getOrNull()?.takeIf { it.startsWith("https://") }
    }

    /** https file URL that isn't a login/zip/interstitial page or a local address; null otherwise. */
    fun playbackUrl(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase()
        val path = url.encodedPath.lowercase()
        val bad = url.scheme != "https" || host == "localhost" || host.endsWith(".local") ||
            Regex("""^[\d.]+$""").matches(host) || ':' in host || path.endsWith(".zip") ||
            "login.php" in path || "logout" in path || "greenmotors." in host || "greenmountmotors." in host ||
            "/drive/admin" in path
        return if (bad) null else url.toString()
    }

    /** Lower is better (TUI's playback ranking): fast CDNs, then Google storage, PixelDrain, generic. */
    fun score(url: String, label: String): Int {
        val v = "$url $label".lowercase()
        return when {
            listOf("pixel.hubcloud.", "googleusercontent.com", "googlevideo.com", "cloudflarestorage.com", "fsl server",
                "10gbps", "r2.dev", "watch online").any { it in v } -> 0
            listOf("storage.googleapis.com", "hubcloud.cx/re/", "hubcloud.fans/re/").any { it in v } -> 1
            "pixeldrain" in v -> 2
            listOf("testzip.php", "vcloud.php", "drive.php", "gpdl.").any { it in v } -> 3
            else -> 4
        }
    }

    fun isMedia(contentType: String) =
        listOf("video/", "mpegurl", "octet-stream", "matroska", "binary/").any { it in contentType }

    private fun b64(s: String) = String(Base64.getDecoder().decode(s.trim()), Charsets.UTF_8)

    private fun rot13(s: String) = s.map { c ->
        when (c) {
            in 'a'..'m', in 'A'..'M' -> c + 13
            in 'n'..'z', in 'N'..'Z' -> c - 13
            else -> c
        }
    }.joinToString("")

    fun unescape(s: String): String = s
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&quot;", "\"").replace("&#039;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")
}
