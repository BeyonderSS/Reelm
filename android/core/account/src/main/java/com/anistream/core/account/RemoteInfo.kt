package com.anistream.core.account

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Hub `info` (TV → one remote, CONTRACT §3): everything the full remote's panel shows. Build on the TV, [parse] on the remote. */
@Serializable
data class RemoteInfo(
    val rev: Int = 0,
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val payload: JsonObject? = null,
    val subtitles: Subtitles = Subtitles(),
    val audio: Audio = Audio(),
    val quality: Quality = Quality(),
    val sources: List<Source> = emptyList(),
    val source: String? = null,
    val speed: Int = 100,
    val speeds: List<Int> = emptyList(),
    val sleepMin: Int? = null,
    val volume: Volume? = null,
) {
    @Serializable data class Track(val id: String, val label: String)
    @Serializable data class Subtitles(val on: Boolean = false, val selected: String? = null, val tracks: List<Track> = emptyList(), val size: String = "m", val position: String = "bottom")
    @Serializable data class Audio(val selected: String? = null, val tracks: List<Track> = emptyList(), val lang: String? = null, val langs: List<String> = emptyList())
    @Serializable data class Chip(val height: Int, val count: Int = 0, val playable: Boolean = true)
    @Serializable data class Quality(val current: Int = 0, val chips: List<Chip> = emptyList())
    @Serializable data class Source(val id: String, val label: String, val height: Int = 0, val playable: Boolean = true)
    @Serializable data class Volume(val level: Int, val max: Int, val muted: Boolean = false)

    /** Capped to the contract's list limits so the message stays under 8 KB. */
    fun toJson(): JsonObject = json.encodeToJsonElement(serializer(), copy(
        subtitles = subtitles.copy(tracks = subtitles.tracks.take(12)), audio = audio.copy(tracks = audio.tracks.take(8)), sources = sources.take(40)
    )).jsonObject

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; isLenient = true }
        fun parse(o: JsonObject): RemoteInfo? = runCatching { json.decodeFromJsonElement(serializer(), o) }.getOrNull()
    }
}
