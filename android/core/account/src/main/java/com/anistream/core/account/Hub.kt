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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/** A "Continue on TV" request: the phone's history payload and where it was (ms). */
data class Handoff(val profileId: String, val payload: JsonObject, val positionMs: Long)

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

    @Volatile private var socket: WebSocket? = null

    /** Other TVs on this account. */
    fun tvs(list: List<Device>) = list.filter { it.kind == "tv" && it.id != account.deviceId }

    fun sendHandoff(to: Device, profileId: String, h: WatchHistory, positionMs: Long): Boolean =
        socket?.send(buildJsonObject {
            put("type", "handoff"); put("to", to.id); put("profileId", profileId)
            put("playback", buildJsonObject { put("payload", HistoryPayload.encode(h)); put("position", positionMs) })
        }.toString()) ?: false

    /**
     * Connects and stays connected (backoff 1 s doubling to 30 s) until the calling coroutine is cancelled.
     * The caller cancels it when the app leaves the foreground or the user signs out.
     */
    suspend fun run(token: String, kind: String) {
        var attempt = 0
        while (true) {
            val closed = Channel<Boolean>(1)
            val ws = api.client.newWebSocket(
                Request.Builder().url("${api.baseUrl}/v1/hub?token=$token").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        attempt = 0
                        webSocket.send(buildJsonObject {
                            put("type", "hello"); put("deviceId", account.deviceId)
                            put("deviceName", Build.MODEL ?: "Android"); put("kind", kind)
                        }.toString())
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.trySend(true) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.trySend(true) }
                }
            )
            socket = ws
            try { closed.receive() } finally { ws.cancel(); socket = null; _devices.value = emptyList() }
            delay((1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L))
            attempt++
        }
    }

    private fun handle(text: String) {
        val m = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (str(m, "type")) {
            "devices" -> _devices.value = m["devices"]?.jsonArray?.map { it.jsonObject }?.map { d ->
                Device(str(d, "deviceId").ifEmpty { str(d, "id") }, str(d, "deviceName").ifEmpty { str(d, "name") }, str(d, "kind"))
            }.orEmpty()
            "history" -> _historyPings.tryEmit(str(m, "profileId"))
            "favorites" -> _favoritePings.tryEmit(str(m, "profileId"))
            "handoff" -> m["playback"]?.jsonObject?.let { p ->
                val payload = p["payload"]?.jsonObject ?: return
                _handoffs.tryEmit(Handoff(str(m, "profileId"), payload, p["position"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L))
            }
        }
    }
}
