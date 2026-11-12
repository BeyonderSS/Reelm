package com.anistream.core.account

import android.os.Build
import com.anistream.domain.model.WatchHistory
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

/** A "Play on TV" request: the phone's history payload, where it was (ms), whether to start playing, and its source label. */
data class Handoff(val profileId: String, val payload: JsonObject, val positionMs: Long, val autoplay: Boolean = false, val source: String? = null)

/**
 * What the TV player should do with the handoff it is opening: seek to [positionMs] once ready, prefer the source
 * labelled [source], and (movies/series) start [autoplay] = season to episode straight from the detail page. Each is taken once.
 */
object HandoffHint {
    @Volatile var positionMs: Long? = null
    @Volatile var source: String? = null
    @Volatile var autoplay: Pair<Int, Int>? = null

    fun takeSeek() = positionMs.also { positionMs = null }
    fun takeAutoplay() = autoplay.also { autoplay = null }
    /** Index of the stream whose label matches the sender's, else one from the same source; null = auto-pick. */
    fun pickSource(labels: List<String>): Int? {
        val want = source ?: return null
        source = null
        return labels.indexOf(want).takeIf { it >= 0 }
            ?: labels.indexOfFirst { it.substringBefore(" · ") == want.substringBefore(" · ") }.takeIf { it >= 0 }
    }
}

/** Realtime hub (`/v1/hub`): the signed-in account's devices, handoffs to this one, and "history changed" pings. */
@Singleton
class HubClient @Inject constructor(private val account: AccountRepository, private val api: AccountApi) {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    private val _handoffs = MutableSharedFlow<Handoff>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val handoffs: SharedFlow<Handoff> = _handoffs.asSharedFlow()
    private val _historyPings = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** profileId whose history another device just pushed */
    val historyPings: SharedFlow<String> = _historyPings.asSharedFlow()

    private val _favoritePings = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** profileId whose favourites another device just pushed */
    val favoritePings: SharedFlow<String> = _favoritePings.asSharedFlow()

    private val _states = MutableStateFlow<Map<String, Now?>>(emptyMap())
    /** deviceId → what that TV is playing (null = not in a player). */
    val states: StateFlow<Map<String, Now?>> = _states.asStateFlow()
    private val _controls = MutableSharedFlow<Control>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** TV: remote commands from a phone, for the player on screen. */
    val controls: SharedFlow<Control> = _controls.asSharedFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var kind = ""

    private fun hello() = buildJsonObject {
        put("type", "hello"); put("deviceId", account.deviceId)
        put("deviceName", if (kind == "tv") account.deviceName else Build.MODEL ?: "Android"); put("kind", kind)
    }.toString()

    /** TV: rename this device and tell the others. */
    fun rename(name: String) { account.deviceName = name; socket?.send(hello()) }

    /** Other TVs on this account. */
    fun tvs(list: List<Device>) = list.filter { it.kind == "tv" && it.id != account.deviceId }

    fun sendHandoff(to: Device, profileId: String, h: WatchHistory, positionMs: Long, source: String? = null): Boolean =
        socket?.send(buildJsonObject {
            put("type", "handoff"); put("to", to.id); put("profileId", profileId)
            put("playback", buildJsonObject {
                put("payload", HistoryPayload.encode(h)); put("positionMs", positionMs); put("autoplay", true)
                if (source != null) put("source", source)
            })
        }.toString()) ?: false

    fun sendControl(to: String, action: String, value: Long? = null): Boolean =
        socket?.send(buildJsonObject { put("type", "control"); put("to", to); put("action", action); if (value != null) put("value", value) }.toString()) ?: false

    /** TV: what the player shows now (null = left the player). */
    fun sendState(now: Now?) {
        socket?.send(buildJsonObject {
            put("type", "state")
            put("now", now?.let { n ->
                buildJsonObject {
                    put("title", n.title.take(120)); n.subtitle?.let { put("subtitle", it.take(120)) }; n.poster?.let { put("poster", it) }
                    put("playing", n.playing); put("positionMs", n.positionMs); put("durationMs", n.durationMs)
                    put("hasNext", n.hasNext); put("hasPrev", n.hasPrev); put("at", n.at)
                }
            } ?: JsonNull)
        }.toString())
    }

    /**
     * Connects and stays connected (backoff 1 s doubling to 30 s) until the calling coroutine is cancelled.
     * The caller cancels it when the app leaves the foreground or the user signs out.
     */
    suspend fun run(token: String, kind: String) {
        this.kind = kind
        var attempt = 0
        while (true) {
            val closed = Channel<Boolean>(1)
            val ws = api.client.newWebSocket(
                Request.Builder().url("${api.baseUrl}/v1/hub?token=$token").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        attempt = 0
                        webSocket.send(hello())
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.trySend(true) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.trySend(true) }
                }
            )
            socket = ws
            try { closed.receive() } finally { ws.cancel(); socket = null; _devices.value = emptyList(); _states.value = emptyMap() }
            delay((1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L))
            attempt++
        }
    }

    private fun handle(text: String) {
        val m = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (str(m, "type")) {
            "devices" -> {
                _devices.value = m["devices"]?.jsonArray?.map { it.jsonObject }?.map { d ->
                    Device(str(d, "deviceId").ifEmpty { str(d, "id") }, str(d, "deviceName").ifEmpty { str(d, "name") }, str(d, "kind"), parseNow(d["now"], received = false))
                }.orEmpty()
                _states.value = _devices.value.associate { it.id to it.now }
            }
            "state" -> str(m, "from").takeIf { it.isNotEmpty() }?.let { from -> _states.update { it + (from to parseNow(m["now"], received = true)) } }
            "control" -> _controls.tryEmit(Control(str(m, "action"), m["value"]?.jsonPrimitive?.doubleOrNull?.toLong()))
            "history" -> _historyPings.tryEmit(str(m, "profileId"))
            "favorites" -> _favoritePings.tryEmit(str(m, "profileId"))
            "handoff" -> m["playback"]?.jsonObject?.let { p ->
                val payload = p["payload"]?.jsonObject ?: return
                // positionMs; older senders put ms (the web wrongly seconds) in "position"
                val ms = (p["positionMs"] ?: p["position"])?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
                _handoffs.tryEmit(Handoff(str(m, "profileId"), payload, ms, p["autoplay"]?.jsonPrimitive?.booleanOrNull == true, p["source"]?.jsonPrimitive?.contentOrNull))
            }
        }
    }

    /** A live "state" is stamped with our own clock (devices' clocks differ); a stored one keeps its own, capped at now. */
    private fun parseNow(e: JsonElement?, received: Boolean): Now? {
        val n = e as? JsonObject ?: return null
        fun l(k: String) = n[k]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
        fun b(k: String) = n[k]?.jsonPrimitive?.booleanOrNull == true
        val t = System.currentTimeMillis()
        return Now(str(n, "title"), n["subtitle"]?.jsonPrimitive?.contentOrNull, n["poster"]?.jsonPrimitive?.contentOrNull, b("playing"),
            l("positionMs"), l("durationMs"), b("hasNext"), b("hasPrev"), if (received) t else l("at").takeIf { it in 1..t } ?: t)
    }
}
