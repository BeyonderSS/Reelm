package com.anistream.core.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class ApiException(message: String, val code: Int = 0) : IOException(message)

/** Thin JSON client for the Reelm API (server/API.md). Blank [baseUrl] = accounts are off. */
@Singleton
class AccountApi @Inject constructor() {
    val baseUrl: String = BuildConfig.REELM_API_URL.trim().trimEnd('/')
    val enabled: Boolean get() = baseUrl.isNotBlank()

    /** Set by [AccountRepository]: a 401 on a call made with `token` means the server revoked that session. */
    @Volatile var onUnauthorized: ((String) -> Unit)? = null

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // keeps the hub socket alive
            .build()
    }

    /** Returns the JSON object, or null for an empty (204) body. Throws [ApiException] with the server's `error` text. */
    suspend fun call(method: String, path: String, body: JsonObject? = null, token: String? = null): JsonObject? =
        withContext(Dispatchers.IO) {
            check(enabled) { "accounts are off" }
            val req = Request.Builder().url(baseUrl + path)
                .method(method, if (method == "GET") null else (body?.toString() ?: "{}").toRequestBody(JSON))
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .build()
            client.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    if (res.code == 401 && token != null) onUnauthorized?.invoke(token) // third-party hosts never come through here
                    val msg = runCatching { Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    throw ApiException(msg ?: "Request failed (${res.code})", res.code)
                }
                if (text.isBlank()) null else Json.parseToJsonElement(text).jsonObject
            }
        }

    private companion object { val JSON = "application/json".toMediaType() }
}
