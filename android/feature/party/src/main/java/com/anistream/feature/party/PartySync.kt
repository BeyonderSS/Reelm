package com.anistream.feature.party

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToLong

/** Local and room position may differ by less than this before we bother seeking. */
const val DRIFT_MS = 1500L

/** Where playback should be now for an event stamped at server time [at]; paused rooms stay put, and so does a
 *  "Lights down" whose [at] (the countdown's end) is still ahead. */
fun targetPosition(position: Long, at: Long, serverNow: Long, playing: Boolean): Long =
    if (playing) position + (serverNow - at).coerceAtLeast(0L) else position

fun shouldSeek(local: Long, target: Long) = abs(local - target) >= DRIFT_MS

/** Own broadcast coming back; [self] is null when the server never told us our id. */
fun isEcho(by: String, self: String?) = self != null && by == self

/** Same title, season and episode: the identity of what is playing, ignoring art and other display fields.
 *  Only the id that names the title counts (mediaId, or slug for anime): the web's payload also carries a movie slug. */
fun samePayload(a: JsonObject?, b: JsonObject?): Boolean {
    if (a == null || b == null) return false
    fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    fun n(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
    val id = if (s(a, "kind") == "anime") "slug" else "mediaId"
    return s(a, "kind") == s(b, "kind") && s(a, id) != null && s(a, id) == s(b, id) && n(a, "season") == n(b, "season") && n(a, "episode") == n(b, "episode")
}

/** Room positions are integer ms; old web clients sent float seconds, so decimals are rounded, never dropped. */
fun ms(el: JsonElement?): Long? = (el as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.roundToLong()

/** True when this build carries libwebrtc (phone); the TV build leaves it out and keeps sync only. */
val rtcAvailable: Boolean by lazy { runCatching { Class.forName("org.webrtc.PeerConnectionFactory") }.isSuccess }

const val JOIN_QUIET_MS = 2000L
const val APPLY_QUIET_MS = 800L

/**
 * When this player may broadcast play/pause/seek. A joiner stays quiet until the room's state is applied and 2 s
 * after (its own autoplay and resume-seek must not rewind the room), and everyone stays quiet briefly while a
 * remote event is being applied (the player reports our own seek back to us).
 */
class EchoGuard(private val now: () -> Long, host: Boolean) {
    private var quietUntil = if (host) 0L else Long.MAX_VALUE
    fun joined() { quietUntil = now() + JOIN_QUIET_MS }
    fun applying() { if (quietUntil != Long.MAX_VALUE) quietUntil = maxOf(quietUntil, now() + APPLY_QUIET_MS) }
    fun mayBroadcast() = now() >= quietUntil
}

const val DUCK_LEVEL = 0.35f
const val TALK_THRESHOLD = 0.04f
private const val DUCK_AFTER_MS = 150L
private const val DUCK_RELEASE_MS = 400L

/** Film volume while people talk: down to [DUCK_LEVEL] once someone has been loud for 150 ms, back up over 400 ms. */
class Ducker {
    private var loudSince = -1L
    private var quietSince = -1L
    private var ducked = false

    fun volume(now: Long, loud: Boolean): Float {
        if (loud) {
            if (loudSince < 0) loudSince = now
            quietSince = -1
            if (now - loudSince >= DUCK_AFTER_MS) ducked = true
            return if (ducked) DUCK_LEVEL else 1f
        }
        loudSince = -1
        if (!ducked) return 1f
        if (quietSince < 0) quietSince = now
        val t = (now - quietSince).toFloat() / DUCK_RELEASE_MS
        if (t >= 1f) { ducked = false; return 1f }
        return DUCK_LEVEL + (1f - DUCK_LEVEL) * t
    }
}

/** The room's `source` descriptor (≤1 KB): `{provider, quality, host?, label?}`. */
fun sourceOf(provider: String, quality: String, host: String?, label: String?): JsonObject = buildJsonObject {
    put("provider", provider); put("quality", quality); host?.let { put("host", it) }; label?.let { put("label", it.take(200)) }
}

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** Same copy of the film: same provider and the same quality label or host. */
fun sameSource(a: JsonObject?, b: JsonObject?): Boolean {
    if (a == null || b == null) return false
    if (!a.s("provider").equals(b.s("provider"), ignoreCase = true)) return false
    return (a.s("quality") != null && a.s("quality") == b.s("quality")) || (a.s("host") != null && a.s("host") == b.s("host"))
}

/** Index in [ours] of the room's [want] source: the exact label first, then any [sameSource]; null = none here. */
fun matchSource(want: JsonObject, ours: List<JsonObject>): Int? =
    want.s("label")?.let { l -> ours.indexOfFirst { it.s("label") == l }.takeIf { it >= 0 } }
        ?: ours.indexOfFirst { sameSource(want, it) }.takeIf { it >= 0 }

/** "1:02:14" / "4:05" */
fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
