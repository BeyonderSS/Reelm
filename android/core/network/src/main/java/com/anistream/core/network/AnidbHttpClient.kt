package com.anistream.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class AnidbHttpClient @Inject constructor(
    private val cloudflareBypass: CloudflareBypassEngine,
    private val cookieJarHolder: CookieJarHolder
) {
    companion object {
        const val CHROME_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val BASE_URL = "https://hianime.at"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(CloudflareInterceptor())
            .addInterceptor(UserAgentInterceptor())
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** headers: extra request headers, e.g. Referer for the zokoanime stream host. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Response = withContext(Dispatchers.IO) {
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
        headers.forEach { (k, v) -> builder.header(k, v) }

        var response = client.newCall(builder.build()).execute()

        // bypass only helps for hianime itself; a 403 from the stream host means a missing Referer
        if (isHianime(url) && isCloudflareBlocked(response)) {
            response.close()
            response = fetchWithBypass(builder)
        }

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("hianime.at returned HTTP $code for $url")
        }

        response
    }

    /** Main-safe: the body is read on IO too (reading it on the caller's thread caused an ANR from Home). */
    suspend fun getHtml(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        get(url, headers).use { response ->
            response.body?.string() ?: throw IOException("Empty response from $url")
        }
    }

    private suspend fun fetchWithBypass(builder: okhttp3.Request.Builder): Response {
        val clearance = cloudflareBypass.resolveClearance(BASE_URL)
            ?: throw IOException("Cloudflare bypass failed")

        cookieJarHolder.setCookies(clearance.cookies)
        cookieJarHolder.setUserAgent(clearance.userAgent)

        return withContext(Dispatchers.IO) { client.newCall(builder.build()).execute() }
    }

    private fun isHianime(url: String): Boolean = url.toHttpUrlOrNull()?.host?.endsWith("hianime.at") == true

    private fun isCloudflareBlocked(response: Response): Boolean {
        if (response.code == 403) return true
        if (response.code == 503) {
            val body = response.peekBody(2048).string()
            return body.contains("Just a moment") ||
                body.contains("challenge-platform") ||
                body.contains("Checking your browser")
        }
        return false
    }

    inner class CloudflareInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            val cookies = cookieJarHolder.getCookies()
            // don't leak hianime cookies to the embed / stream hosts
            if (cookies != null && request.url.host.endsWith("hianime.at")) {
                request = request.newBuilder()
                    .header("Cookie", cookies)
                    .build()
            }
            return chain.proceed(request)
        }
    }

    inner class UserAgentInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val ua = cookieJarHolder.getUserAgent() ?: CHROME_USER_AGENT
            val request = chain.request().newBuilder()
                .header("User-Agent", ua)
                .build()
            return chain.proceed(request)
        }
    }
}
