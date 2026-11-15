package com.anistream.core.trailer

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import java.util.concurrent.TimeUnit

/** A playable trailer: [url] alone, or a video-only [url] plus [audioUrl] to merge. */
internal class TrailerStream(val url: String, val audioUrl: String? = null, val height: Int = 0)

/**
 * YouTube id → playable stream through NewPipe Extractor. Pure JVM (the live unit test runs it on the host).
 *
 * Choice: H.264 MP4 over plain progressive HTTP only (no DASH/SABR), so ExoPlayer on API 22 decodes it in hardware.
 * - [preferMuxed] (Fire TV / LowEnd): muxed audio+video first (YouTube only keeps 360p itag 18), so one connection.
 * - otherwise: the best video-only MP4 ≤ [maxHeight] plus the best M4A track (merged by TrailerSurface), so previews
 *   look sharp and sound-on still has audio. Muxed is the fallback, then HLS (only some ids/clients give one).
 */
internal class YouTube(client: OkHttpClient) {
    init {
        initOnce(client)
    }

    fun resolve(id: String, maxHeight: Int, preferMuxed: Boolean): TrailerStream? {
        val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$id")
        ex.fetchPage()
        fun ok(s: Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && s.format == MediaFormat.MPEG_4
        val muxed = ex.videoStreams.filter { ok(it) && it.height in 1..maxHeight }.maxByOrNull { it.height }
            ?.let { TrailerStream(it.content, height = it.height) }
        val audio = ex.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.M4A }
            .sortedWith(compareBy({ it.audioTrackType != null && it.audioTrackType != AudioTrackType.ORIGINAL }, { -it.averageBitrate }))
            .firstOrNull()
        val split = ex.videoOnlyStreams
            .filter { ok(it) && it.height in 1..maxHeight && it.codec.orEmpty().startsWith("avc1") }
            .maxWithOrNull(compareBy({ it.height }, { it.fps <= 30 }))
            ?.let { TrailerStream(it.content, audio?.content, it.height) }
        val hls = ex.hlsUrl.takeIf { it.isNotBlank() }?.let { TrailerStream(it) }
        return if (preferMuxed) muxed ?: split ?: hls else split ?: muxed ?: hls
    }

    private companion object {
        @Volatile var ready = false

        @Synchronized fun initOnce(client: OkHttpClient) {
            if (ready) return
            NewPipe.init(OkDownloader(client.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()))
            ready = true
        }
    }
}

/** NewPipe's HTTP on OkHttp (same shape as NewPipe's own DownloaderImpl). */
private class OkDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody() ?: if (request.httpMethod() == "POST") ByteArray(0).toRequestBody() else null
        val b = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body).header("User-Agent", UA)
        request.headers().forEach { (name, values) ->
            b.removeHeader(name)
            values.forEach { b.addHeader(name, it) }
        }
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
