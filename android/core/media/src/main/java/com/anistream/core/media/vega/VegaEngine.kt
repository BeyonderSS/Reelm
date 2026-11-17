package com.anistream.core.media.vega

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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
    private val log: (String) -> Unit = {},
    /** kvStore + cookie jar survive restarts here (NetMirror's session token costs a ~35 s ad-verify poll, valid 12 h). */
    private val stateFile: File? = null
) {
    /** A provider's kvStore value (JSON text), e.g. NetMirror's "t_hash_t_data". */
    fun kv(provider: String, key: String): String? = state.kv[provider]?.get(key)

    @Volatile private var runtimeBytecode: ByteArray? = null
    /** url → (fetched at, response JSON) for [SHARED] GETs; one lock per url so parallel providers wait for one fetch. */
    private val shared = ConcurrentHashMap<String, Pair<Long, String>>()
    private val sharedLocks = ConcurrentHashMap<String, Mutex>()
    private val state by lazy { State.load(stateFile) }

    /**
     * Fresh runtime with [modules] (name → CommonJS source) loaded as globals; closed after [block].
     * [provider] scopes ctx.kvStore, which (as in the Vega app) outlives the runtime. [memoryLimit] (bytes, 0 = none) caps
     * the runtime's heap: on a 1 GB TV stick a provider parsing a huge page fails alone instead of the app being killed.
     */
    suspend fun <T> session(modules: Map<String, String>, provider: String = "", memoryLimit: Long = 0, block: suspend Session.() -> T): T {
        val js = Runtimes.create()
        val host = Host()
        try {
            if (memoryLimit > 0) js.memoryLimit = memoryLimit
            val session = Session(js) { host.fails.get() }
            bind(js, host, state.kv.getOrPut(provider) { ConcurrentHashMap() }, session)
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
            return session.block()
        } finally {
            // quickjs-kt's close() cancels in-flight host calls, whose completion then touches the freed context.
            // So first fail every host call fast, let the JS settle, and only close an idle runtime.
            withContext(NonCancellable) {
                host.shutdown()
                val idle = withTimeoutOrNull(DRAIN_MS) { runCatching { js.evaluate<Any?>("0", "drain.js") }; true } == true
                if (!idle) log("runtime still busy after ${DRAIN_MS} ms; leaking it rather than crashing")
                Runtimes.release(js, close = idle)
                state.save(stateFile)
            }
        }
    }

    /**
     * quickjs-kt alpha13's close() clears process-wide JNI caches (the JavaVM pointer and every cached class ref), so
     * closing one runtime while another is alive makes the other's host calls throw `null` until a new runtime
     * starts, and under CheckJNI aborts with "invalid global reference in releaseGlobals". So runtimes are closed
     * only once none is alive, all together, with creation held off meanwhile.
     * ponytail: a never-idle engine never frees; fine for search bursts, revisit if Vega runs continuously.
     */
    private object Runtimes {
        private val lock = Mutex()
        private var alive = 0
        private val idle = mutableListOf<QuickJs>()

        suspend fun create(): QuickJs = lock.withLock { QuickJs.create(Dispatchers.Default).also { alive++ } }

        suspend fun release(js: QuickJs, close: Boolean) = lock.withLock {
            if (close) idle += js
            if (--alive == 0) { idle.forEach { it.close() }; idle.clear() }
        }
    }

    /** Per-session host state: open calls and timers, so a session can be wound down before close. */
    private class Host {
        @Volatile var closed = false
        /** requests that failed or answered >= 400 */
        val fails = java.util.concurrent.atomic.AtomicInteger()
        val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
        val timers = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        fun shutdown() {
            closed = true
            calls.forEach { it.cancel() }
            timers.values.forEach { it.complete(Unit) }
        }
    }

    class Session(private val js: QuickJs, private val fails: () -> Int = { 0 }) {
        /** The provider's requests so far that failed (network, DNS) or answered 4xx/5xx. */
        val httpFails get() = fails()

        /** Receives `__host_emit(i, json)` from JS during [call]: a partial result handed out before the whole call settles. */
        @Volatile var onEmit: ((Int, String) -> Unit)? = null

        /** Awaits the JS expression [expr] and returns its value as JSON. */
        suspend fun call(expr: String): JsonElement {
            // evaluate() runs the event loop to idle but hands back the promise itself, so park the outcome in globals.
            js.evaluate<Any?>(
                "__out = __err = null; (async () => JSON.stringify(await ($expr)))()" +
                    ".then(v => { __out = v }, e => { __err = String(e) + (e && e.stack ? \"\\n\" + e.stack : \"\") })",
                "call.js"
            )
            js.evaluate<String?>("__err")?.let { throw VegaException(it) }
            return js.evaluate<String?>("__out")?.let(JSON::parseToJsonElement) ?: JsonNull
        }
    }

    private fun bind(js: QuickJs, host: Host, kv: MutableMap<String, String>, session: Session) {
        js.function("__host_log") { args -> log(args.getOrNull(1)?.toString().orEmpty()); null }
        js.function("__host_emit") { args -> session.onEmit?.invoke((args[0] as Number).toInt(), args[1] as String); null }
        // kvStore values go in and out as JSON text: get(k) -> json or null, set(k, json), delete(k), keys -> json array
        js.function("__host_kv") { args ->
            val k = args.getOrNull(1)?.toString().orEmpty()
            when (args[0]) {
                // providers' own TheIntroDB lookups (9 per series, 1.4–2.7 s) stay off unless set: the player fetches skip times itself
                "get" -> kv[k] ?: if (k.endsWith("_skipTimings")) "false" else null
                "set" -> { kv[k] = args[2] as String; state.dirty = true; null }
                "delete" -> { state.dirty = kv.remove(k) != null || state.dirty; null }
                "clear" -> { kv.clear(); state.dirty = true; null }
                else -> JsonArray(kv.keys.map(::JsonPrimitive)).toString()
            }
        }
        // setTimeout/clearTimeout: evaluate() waits for every pending host call, so an uncleared timer would stall each call.
        js.asyncFunction("__host_sleep") { args ->
            val id = (args[1] as Number).toInt()
            val cleared = host.timers.getOrPut(id) { CompletableDeferred() }
            if (host.closed) cleared.complete(Unit)
            withTimeoutOrNull((args[0] as Number).toLong()) { cleared.await() }
            host.timers.remove(id)
            null
        }
        js.function("__host_clear") { args -> host.timers.getOrPut((args[0] as Number).toInt()) { CompletableDeferred() }.complete(Unit); null }
        // Never throws: quickjs-kt hands a thrown Kotlin exception to JS as `null`, and providers then die on `e.message`.
        js.asyncFunction("__host_http") { args ->
            runCatching {
                if (host.closed) throw IOException("session closed")
                val get: suspend () -> String = {
                    http(
                        host,
                        method = args[0] as String,
                        url = args[1] as String,
                        headers = JSON.parseToJsonElement(args[2] as String).jsonObject,
                        body = args[3] as String?,
                        follow = args[4] as Boolean,
                        timeoutMs = (args[5] as Number).toLong()
                    )
                }
                val url = args[1] as String
                if (args[0] == "GET" && SHARED.any(url::startsWith)) sharedGet(url, get) else get()
            }.getOrElse { e -> host.fails.incrementAndGet(); buildJsonObject { put("error", "${e.message ?: e::class.simpleName} (${args[1]})") }.toString() }
        }
    }

    /**
     * GETs every provider repeats, answered once an hour for all runtimes (each provider runs in its own): the site
     * addresses in urls.json (ten parallel fetches got throttled by GitHub, 8–11 s) and Cinemeta metas (providers
     * cache those in globalThis, which dies with the runtime: 0.25–0.9 s each). Only 200s are kept.
     */
    private suspend fun sharedGet(url: String, get: suspend () -> String): String = sharedLocks.getOrPut(url) { Mutex() }.withLock {
        shared[url]?.takeIf { System.currentTimeMillis() - it.first < SHARED_TTL_MS }?.second
            ?: get().also { if (it.startsWith("{\"status\":200,")) shared[url] = System.currentTimeMillis() to it }
    }

    private suspend fun http(host: Host, method: String, url: String, headers: JsonObject, body: String?, follow: Boolean, timeoutMs: Long): String {
        val type = headers["content-type"]?.jsonPrimitive?.content
        val builder = Request.Builder().url(url)
            .method(method, if (method == "GET" || method == "HEAD") null else (body ?: "").toRequestBody(type?.toMediaTypeOrNull()))
        headers.forEach { (k, v) ->
            // OkHttp handles gzip itself only when the caller didn't ask for an encoding.
            if (k !in SKIP_HEADERS && k != "cookie") builder.header(k, v.jsonPrimitive.content)
        }
        if (headers["user-agent"] == null) builder.header("User-Agent", USER_AGENT)
        val c = client.newBuilder().followRedirects(follow).followSslRedirects(follow)
            .cookieJar(state.jar.forCall(url.toHttpUrl(), headers["cookie"]?.jsonPrimitive?.content))
            .apply { if (timeoutMs > 0) callTimeout(timeoutMs, TimeUnit.MILLISECONDS) }.build()
        val call = c.newCall(builder.build()).also { host.calls += it }
        val response = try { call.await() } finally { host.calls -= call }
        return response.use { res ->
            if (res.code >= 400) host.fails.incrementAndGet()
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

    /** The provider's search itself failed (threw, or its requests failed and it found nothing): what the circuit breaker counts. */
    class SearchFailed(message: String?) : Exception(message)

    /**
     * The Vega app's provider cookie jar (providerCookieJar.ts): one jar for all providers of a source author (we load
     * one author, Zenda-Cross), written only by server Set-Cookie, never by a provider's own Cookie header. A request
     * sends the jar's cookies plus the provider's, and the provider's win on a name clash. The NetMirror providers
     * rely on it: a sibling mirror's verified t_hash_t reaches the others through the jar.
     */
    class Jar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized fun save(url: HttpUrl, new: List<Cookie>) {
            val now = System.currentTimeMillis()
            new.forEach { n -> cookies.removeAll { it.name == n.name && it.domain == n.domain && it.path == n.path } }
            cookies += new.filter { it.expiresAt > now }
            cookies.removeAll { it.expiresAt <= now }
        }

        @Synchronized fun load(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }

        @Synchronized fun all(): List<Cookie> = cookies.toList()

        /**
         * OkHttp's bridge replaces a request's Cookie header with the jar's, so the provider's header is merged in
         * here. Like the Vega app, it rides along on same-host redirect hops (updated by their Set-Cookie) only.
         */
        fun forCall(url: HttpUrl, supplied: String?): CookieJar {
            val own = linkedMapOf<String, String>()
            supplied?.split(';')?.forEach { part ->
                val eq = part.indexOf('=')
                if (eq > 0) own[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
            return object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    save(url, cookies)
                    if (url.host == urlHost) cookies.forEach { if (it.expiresAt > System.currentTimeMillis()) own[it.name] = it.value else own.remove(it.name) }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val jar = load(url)
                    if (url.host != urlHost) return jar
                    val mine = own.mapNotNull { (k, v) -> runCatching { Cookie.Builder().name(k).value(v).hostOnlyDomain(url.host).build() }.getOrNull() }
                    return mine + jar.filter { c -> c.name !in own }
                }

                private val urlHost = url.host
            }
        }
    }

    /** What outlives a runtime: per-provider kvStore (JSON values) and the cookie jar, saved to [file] when changed. */
    private class State(val kv: ConcurrentHashMap<String, MutableMap<String, String>>, val jar: Jar) {
        @Volatile var dirty = false
        private var savedCookies = jar.all()

        @Synchronized fun save(file: File?) {
            val cookies = jar.all()
            if (file == null || (!dirty && cookies == savedCookies)) return
            dirty = false
            savedCookies = cookies
            runCatching {
                val text = buildJsonObject {
                    put("kv", JsonObject(kv.mapValues { (_, m) -> JsonObject(m.mapValues { JsonPrimitive(it.value) }) }))
                    // a persistent cookie's toString() is a Set-Cookie line that Cookie.parse reads back
                    put("cookies", JsonArray(cookies.filter { it.persistent }.map { JsonPrimitive(it.toString()) }))
                }.toString()
                file.parentFile?.mkdirs()
                File(file.path + ".tmp").apply { writeText(text) }.renameTo(file)
            }
        }

        companion object {
            fun load(file: File?): State {
                val jar = Jar()
                val kv = ConcurrentHashMap<String, MutableMap<String, String>>()
                runCatching {
                    val o = JSON.parseToJsonElement(file!!.readText()).jsonObject
                    o["kv"]?.jsonObject?.forEach { (p, m) -> kv[p] = ConcurrentHashMap(m.jsonObject.mapValues { it.value.jsonPrimitive.content }) }
                    (o["cookies"] as? JsonArray)?.forEach { line ->
                        val text = line.jsonPrimitive.content
                        val domain = Regex("domain=([^;]+)").find(text)?.groupValues?.get(1) ?: return@forEach
                        val url = "https://$domain/".toHttpUrl()
                        Cookie.parse(url, text)?.let { jar.save(url, listOf(it)) }
                    }
                }
                return State(kv, jar)
            }
        }
    }

    companion object {
        /** Desktop Chrome: the providers' sites serve desktop markup to it. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val DRAIN_MS = 5_000L
        private val SHARED = listOf("https://raw.githubusercontent.com/Zenda-Cross/vega-providers/refs/heads/main/urls.json", "https://v3-cinemeta.strem.io/meta/")
        private const val SHARED_TTL_MS = 3_600_000L // the providers' own cache TTL
        private const val MAX_BODY = 8L * 1024 * 1024
        private val SKIP_HEADERS = setOf("accept-encoding", "content-length", "host", "connection")
        private val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
