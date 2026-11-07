package com.anistream.domain

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The device's preferred audio language ("I want to watch in Hindi"), shared by phone and TV.
 *
 * - [preferred]: ISO 639-1 code ("hi", "en", "ta" …) or null for "Any". Collect it in UI to show the current choice.
 * - [set]: change (and persist) it; null = Any. Players apply it themselves: the source auto-pick prefers streams whose
 *   [com.anistream.domain.model.MediaStream.languages] contain it, and ExoPlayer's preferred audio language is set to it
 *   so multi-audio files start on that track. MediaPlayerViewModel.selectAudioLanguage does the in-player switch.
 * - [name]: English display name for a code ("hi" → "Hindi"); [normalize]: any code ("hin", "hi-IN", "ptbr") → "hi"/"pt".
 *
 * Persisted in SharedPreferences "audio_language"; [init] is called once from Application.onCreate.
 */
object AudioLanguage {
    private val _preferred = MutableStateFlow<String?>(null)
    val preferred: StateFlow<String?> = _preferred.asStateFlow()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("audio_language", Context.MODE_PRIVATE).also { _preferred.value = normalize(it.getString("code", null)) }
    }

    fun set(code: String?) {
        val c = normalize(code)
        _preferred.value = c
        prefs?.edit()?.putString("code", c)?.apply()
    }

    /** "hin" / "HI-in" / "ptbr" → "hi" / "hi" / "pt"; null for blank or "und". */
    fun normalize(code: String?): String? {
        val c = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        return when (c.length) {
            3 -> ISO3[c] ?: c
            4 -> c.take(2) // MovieBox "esla" (Latin American Spanish), "ptbr"
            else -> c
        }
    }

    fun name(code: String): String = Locale(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    private val ISO3 by lazy { Locale.getISOLanguages().associateBy { Locale(it).isO3Language } + mapOf("fre" to "fr", "ger" to "de", "chi" to "zh") }
}
