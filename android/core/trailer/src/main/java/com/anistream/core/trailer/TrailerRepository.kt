package com.anistream.core.trailer

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.util.LruCache
import com.anistream.core.account.AccountApi
import com.anistream.core.common.LowEnd
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class TrailerKey(val imdb: String? = null, val mal: Long? = null, val title: String? = null, val year: Int? = null, val type: String? = null /* movie|series */)

data class TrailerInfo(val logo: String?, val backdrops: List<String>, val streamUrl: String?, val startAtMs: Long) {
    /** Separate audio track when [streamUrl] is video-only; TrailerSurface merges it so sound-on works. Not part of equals. */
    internal var audioUrl: String? = null
}

enum class PreviewMode { ON, WIFI_ONLY, OFF }

/** `/v1/trailer` query for [this]; blank fields are left out. Also the cache key. */
internal fun TrailerKey.query(): String = listOfNotNull(
    imdb?.takeIf { it.isNotBlank() }?.let { "imdb" to it },
    mal?.let { "mal" to it.toString() },
    title?.takeIf { it.isNotBlank() }?.let { "title" to it.trim() },
    year?.let { "year" to it.toString() },
    type?.takeIf { it.isNotBlank() }?.let { "type" to it },
).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

/** First id in order that [resolve]s to something; failures and nulls (dead / private / no usable stream) are skipped. */
internal suspend fun <T : Any> firstPlayable(ids: List<String>, resolve: suspend (String) -> T?): T? {
    for (id in ids) runCatching { resolve(id) }.getOrNull()?.let { return it }
    return null
}

@Singleton
class TrailerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AccountApi,
) {
    private class Meta(val ytIds: List<String>, val logo: String?, val backdrops: List<String>, val startAtMs: Long)
    private class Resolved(val info: TrailerInfo, val at: Long)

    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val metas = LruCache<String, Meta>(64)
    private val infos = LruCache<String, Resolved>(64)
    private val youTube by lazy { YouTube(api.client) }

    private val _mode = MutableStateFlow(
        prefs.getString(KEY_MODE, null)?.let { runCatching { PreviewMode.valueOf(it) }.getOrNull() } ?: PreviewMode.WIFI_ONLY
    )
    val mode: StateFlow<PreviewMode> = _mode.asStateFlow()

    /** Set by the TV while a phone remote session drives playback; no previews then. */
    @Volatile var remoteDriving = false

    fun setMode(m: PreviewMode) {
        _mode.value = m
        prefs.edit().putString(KEY_MODE, m.name).apply()
    }

    /** Cached in memory (LRU 64). Calls /v1/trailer, then walks ytIds resolving each with NewPipe Extractor until one gives a playable stream at ≤720p (≤480p when LowEnd.isOn). Dead ids are skipped. streamUrl null = no playable trailer (UI shows the living backdrop). Never throws. */
    suspend fun get(key: TrailerKey): TrailerInfo? = withContext(Dispatchers.IO) {
        val q = key.query()
        infos.get(q)?.takeIf { System.currentTimeMillis() - it.at < URL_TTL_MS }?.let { return@withContext it.info }
        val meta = meta(q) ?: return@withContext null
        val maxHeight = if (LowEnd.isOn(context)) 480 else 720
        val stream = firstPlayable(meta.ytIds) { youTube.resolve(it, maxHeight, preferMuxed = LowEnd.isOn(context)) }
        val info = TrailerInfo(meta.logo, meta.backdrops, stream?.url, meta.startAtMs).apply { audioUrl = stream?.audioUrl }
        infos.put(q, Resolved(info, System.currentTimeMillis()))
        info
    }

    /** Logo only, cheap (no NewPipe). Used by heroes before a trailer is needed. */
    suspend fun logo(key: TrailerKey): String? = withContext(Dispatchers.IO) { meta(key.query())?.logo }

    /** true if previews may autoplay right now: mode ON, or WIFI_ONLY and on an unmetered network and not Data Saver; never while the phone remote drives the TV or the system reduced-motion (animator scale 0) is set. */
    fun allowedNow(): Boolean {
        if (remoteDriving) return false
        if (Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return false
        return when (_mode.value) {
            PreviewMode.ON -> true
            PreviewMode.OFF -> false
            PreviewMode.WIFI_ONLY -> {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                @Suppress("DEPRECATION") val connected = cm.activeNetworkInfo?.isConnected == true
                connected && !cm.isActiveNetworkMetered && (Build.VERSION.SDK_INT < 24 ||
                    cm.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
            }
        }
    }

    /** Server response, cached; null when the API is off or unreachable (not cached, so a later call retries). */
    private suspend fun meta(q: String): Meta? {
        metas.get(q)?.let { return it }
        if (!api.enabled || q.isEmpty()) return null
        val o = runCatching { api.call("GET", "/v1/trailer?$q") }.getOrNull() ?: return null
        return Meta(
            ytIds = o.strings("ytIds"),
            logo = o["logo"]?.jsonPrimitive?.contentOrNull,
            backdrops = o.strings("backdrops"),
            startAtMs = (o["startAt"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000,
        ).also { metas.put(q, it) }
    }

    private fun JsonObject.strings(name: String): List<String> =
        runCatching { this[name]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull().orEmpty()

    private companion object {
        const val KEY_MODE = "preview_trailers"
        /** googlevideo URLs expire after ~6 h; re-resolve before that */
        const val URL_TTL_MS = 4 * 60 * 60 * 1000L
    }
}
