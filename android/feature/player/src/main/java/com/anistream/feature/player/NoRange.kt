package com.anistream.feature.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Some hosts ignore Range and send the whole file to every request (Google's video-downloads.googleusercontent.com, where
 * 4KHDHub's and Vega's "GDrive" files live). Media3 then "seeks" by reading and throwing away every byte before the position,
 * and an MKV with its Cues at the end (every 4KHDHub release) is read to the end before the first frame: 3 GB for a 1080p
 * episode. Progressive files are opened the way a browser opens them (`Range: bytes=0-`); a 200 instead of a 206 means the
 * host can't seek, and then
 *  - MKV skips its jump to the Cues and plays from the start (not seekable; a resume point starts from 0),
 *  - any other read more than [NO_RANGE_MAX_SKIP] bytes in (an MP4 whose moov is at the end) fails at once with
 *    [NoRangeException], so the next source starts instead of a silent download.
 */
@OptIn(UnstableApi::class)
internal fun progressiveSources(context: Context, http: HttpDataSource.Factory): DefaultMediaSourceFactory {
    val noRange = ConcurrentHashMap<String, Boolean>()
    return DefaultMediaSourceFactory(DefaultDataSource.Factory(context) { RangeGuard(http.createDataSource(), noRange) }, NoRangeExtractors())
}

/** ~6 s of reading on a 40 Mbps line: a short forward skip is fine, a trip to the end of a 3 GB file is not. */
internal const val NO_RANGE_MAX_SKIP = 32L shl 20

internal class NoRangeException(at: Long) : IOException("The host ignores Range: reaching byte ${at shr 20} MB means downloading everything before it")

/** True when a response to `Range: bytes=0-` was the whole file (no Content-Range): the host can't seek. Empty = not HTTP. */
internal fun ignoresRange(headers: Map<String, List<String>>): Boolean = headers.isNotEmpty() && headers.keys.none { "Content-Range".equals(it, true) }

@OptIn(UnstableApi::class)
private class RangeGuard(private val up: HttpDataSource, private val noRange: MutableMap<String, Boolean>) : DataSource by up {
    init { up.setRequestProperty("Range", "bytes=0-") } // Media3 replaces it with its own Range whenever it reads from a position

    // a Java default method: `by up` doesn't forward it, and the extractors would see no headers at all
    override fun getResponseHeaders(): Map<String, List<String>> = up.responseHeaders

    override fun open(dataSpec: DataSpec): Long {
        val host = dataSpec.uri.host.orEmpty()
        if (dataSpec.position > NO_RANGE_MAX_SKIP && noRange[host] == true) throw NoRangeException(dataSpec.position)
        val n = try { up.open(dataSpec) } catch (e: HttpDataSource.InvalidResponseCodeException) {
            // a host that refuses an open-ended Range (403/416) but serves the plain request still plays, as before
            if (dataSpec.position != 0L || (e.responseCode != 403 && e.responseCode != 416)) throw e
            up.clearRequestProperty("Range"); up.open(dataSpec)
        }
        if (dataSpec.position == 0L) noRange[host] = up.responseCode == 200
        return n
    }
}

@OptIn(UnstableApi::class)
private class NoRangeExtractors : ExtractorsFactory {
    private val normal = DefaultExtractorsFactory()
    private val linear = DefaultExtractorsFactory().setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
    override fun createExtractors(): Array<Extractor> = normal.createExtractors()
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        (if (ignoresRange(responseHeaders)) linear else normal).createExtractors(uri, responseHeaders)
    // DefaultMediaSourceFactory hands these to its extractors so MKV text tracks reach the renderer already parsed
    override fun setSubtitleParserFactory(f: SubtitleParser.Factory) = apply { normal.setSubtitleParserFactory(f); linear.setSubtitleParserFactory(f) }
    override fun experimentalSetTextTrackTranscodingEnabled(on: Boolean) = apply { normal.experimentalSetTextTrackTranscodingEnabled(on); linear.experimentalSetTextTrackTranscodingEnabled(on) }
}
