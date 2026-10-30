package com.anistream.core.media.vega

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs Vega provider modules (CommonJS, unchanged) in QuickJS. [runtimeJs] is the bundled vega-runtime.js
 * (cheerio, axios/fetch shims, URL, atob…); all network goes through [client] via one host function.
 */
class VegaEngine(
    private val client: OkHttpClient,
    private val runtimeJs: () -> String,
    private val log: (String) -> Unit = {}
) {
    @Volatile private var runtimeBytecode: ByteArray? = null

    /** Fresh runtime with [modules] (name → CommonJS source) loaded as globals; closed after [block]. */
    suspend fun <T> session(modules: Map<String, String>, block: suspend Session.() -> T): T {
        val js = QuickJs.create(Dispatchers.Default)
        try {
            bind(js)
            // Parsing the 400 KB bundle is the slow part on a Fire TV stick; compile once, reuse the bytecode.
            val bytecode = runtimeBytecode ?: js.compile(runtimeJs(), "vega-runtime.js").also { runtimeBytecode = it }
            js.evaluate<Any?>(bytecode)
            js.evaluate<Any?>("globalThis.ctx = __vegaContext(${JsonPrimitive(USER_AGENT)});", "ctx.js")
            modules.forEach { (name, code) ->
                js.evaluate<Any?>(
                    "globalThis.$name = (function(){var module={exports:{}},exports=module.exports;\n$code\n;return module.exports})();",
                    "$name.js"
                )
            }
            return Session(js).block()
        } finally {
            js.close()
        }
    }

    class Session(private val js: QuickJs) {
        /** Awaits the JS expression [expr] and returns its value as JSON. */
        suspend fun call(expr: String): JsonElement {
            // evaluate() runs the event loop to idle but hands back the promise itself, so park the outcome in globals.
            js.evaluate<Any?>(
                "__out = __err = null; (async () => JSON.stringify(await ($expr)))()" +
                    ".then(v => { __out = v }, e => { __err = String(e && (e.stack || e.message) || e) })",
                "call.js"
            )
            js.evaluate<String?>("__err")?.let { throw VegaException(it) }
            return js.evaluate<String?>("__out")?.let(JSON::parseToJsonElement) ?: JsonNull
        }
    }

    private fun bind(js: QuickJs) {
        js.function("__host_log") { args -> log(args.getOrNull(1)?.toString().orEmpty()); null }
        js.asyncFunction("__host_sleep") { args -> delay((args[0] as Number).toLong()); null }
        js.asyncFunction("__host_http") { args ->
            http(
                method = args[0] as String,
                url = args[1] as String,
                headers = JSON.parseToJsonElement(args[2] as String).jsonObject,
                body = args[3] as String?,
                follow = args[4] as Boolean,
                timeoutMs = (args[5] as Number).toLong()
            )
        }
    }

    private suspend fun http(method: String, url: String, headers: JsonObject, body: String?, follow: Boolean, timeoutMs: Long): String {
        val type = headers["content-type"]?.jsonPrimitive?.content
        val builder = Request.Builder().url(url)
            .method(method, if (method == "GET" || method == "HEAD") null else (body ?: "").toRequestBody(type?.toMediaTypeOrNull()))
        headers.forEach { (k, v) ->
            // OkHttp handles gzip itself only when the caller didn't ask for an encoding.
            if (k !in SKIP_HEADERS) builder.header(k, v.jsonPrimitive.content)
        }
        if (headers["user-agent"] == null) builder.header("User-Agent", USER_AGENT)
        val c = client.newBuilder().followRedirects(follow).followSslRedirects(follow)
            .apply { if (timeoutMs > 0) callTimeout(timeoutMs, TimeUnit.MILLISECONDS) }.build()
        return c.newCall(builder.build()).await().use { res ->
            buildJsonObject {
                put("status", res.code)
                put("url", res.request.url.toString())
                put("headers", JsonArray(res.headers.map { (k, v) -> JsonArray(listOf(JsonPrimitive(k), JsonPrimitive(v))) }))
                put("body", if (method == "HEAD") "" else res.peekBody(MAX_BODY).string())
            }.toString()
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { response.close() }
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
    }

    class VegaException(message: String) : Exception(message)

    companion object {
        /** Desktop Chrome: the providers' sites serve desktop markup to it. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val MAX_BODY = 8L * 1024 * 1024
        private val SKIP_HEADERS = setOf("accept-encoding", "content-length", "host", "connection")
        private val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
