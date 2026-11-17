package com.anistream.core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.anistream.core.common.LowEnd
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Plain OkHttp GETs for Cinemeta, Stremio addons and M3U playlists. Cancellable, so withTimeout really aborts the call. */
@Singleton
class MediaHttp @Inject constructor() {
    /** shared with update downloads and the party socket */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        // one lookup talks to ~15 hosts; OkHttp's default 5 idle connections left most of them cold (DNS + TLS again)
        .connectionPool(ConnectionPool(if (LowEnd.eligible) 8 else 16, 5, TimeUnit.MINUTES))
        .build()

    suspend fun get(url: String, maxBytes: Long = 8L * 1024 * 1024): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty body for $url")
            if (body.contentLength() > maxBytes) throw IOException("Response too large (${body.contentLength()} bytes)")
            val source = body.source()
            if (source.request(maxBytes + 1)) throw IOException("Response larger than $maxBytes bytes")
            source.buffer.readUtf8()
        }
    }

    suspend fun getJson(url: String): JsonObject = json.parseToJsonElement(get(url)).jsonObject

    /** First KB of [url] (redirects followed): final URL, status and content type, without reading the body. */
    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): Probe = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", "bytes=0-1023")
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            Probe(response.request.url.toString(), response.code, response.header("Content-Type").orEmpty().lowercase())
        }
    }

    data class Probe(val url: String, val code: Int, val contentType: String)

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { response.close() }
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
