package com.anistream.feature.party

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anistream.core.account.AccountRepository
import com.anistream.core.common.Result
import com.anistream.domain.model.PlaybackRequest
import com.anistream.domain.repository.MoviesRepository
import com.anistream.domain.repository.NowPlaying
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/** Where joining a party sends the user. */
sealed interface PartyDestination {
    /** [NowPlaying] is already filled; open the media player */
    data object Media : PartyDestination
    data class Anime(val slug: String, val episode: Int) : PartyDestination
}

fun animePayload(slug: String, title: String, episode: Int, posterUrl: String?): JsonObject = buildJsonObject {
    put("kind", "anime"); put("slug", slug); put("title", title); put("episode", episode); put("posterUrl", posterUrl)
}

fun mediaPayload(r: PlaybackRequest): JsonObject = buildJsonObject {
    put("kind", "media"); put("historyKey", r.historyKey); put("mediaId", r.mediaId); put("title", r.title)
    put("subtitle", r.subtitle); put("posterUrl", r.posterUrl); put("season", r.season); put("episode", r.episode)
    put("seasonEpisodeCount", r.seasonEpisodeCount)
}

/** The room payload's title without catalog tags: the web sends the MovieBox title ("Inception [Hindi]"). */
fun partyTitle(t: String?) = com.anistream.domain.usecase.stripTags(t.orEmpty())

/** Built from season/episode like the detail screen: the web's own `subtitle` is "Movie" / "Season 1, episode 2". */
fun partySubtitle(season: Int, episode: Int) = if (episode > 0) "S$season · E$episode" else null

/** A reaction floating up from [by]'s face; [id] keys its animation. */
data class Reaction(val by: String, val kind: String, val id: Long)

val REACTIONS = listOf("heart" to "❤️", "laugh" to "😂", "gasp" to "😮", "cry" to "😢")

@HiltViewModel
class PartyViewModel @Inject constructor(
    val client: PartyClient,
    private val movies: MoviesRepository,
    private val accounts: AccountRepository,
    rtcLazy: dagger.Lazy<PartyRtc>
) : ViewModel() {
    /** video and voice; null in the TV build, which has no libwebrtc and keeps sync only */
    val rtc: PartyRtc? = if (rtcAvailable) rtcLazy.get() else null

    /** the party sheet (first-class entry from the player's top bar, the party pill and the couch) */
    var sheet by mutableStateOf(false)
    /** a short line over the player: "Kabir paused", the different-copy warning, rtc notes */
    var note by mutableStateOf<String?>(null)
        private set
    val reactions = mutableStateListOf<Reaction>()
    /** member ids popped out of the portrait couch into bubbles over the video */
    val popped = mutableStateListOf<String>()
    /** media position (ms) two of us hearted together; shows the burst */
    var syncHeart by mutableStateOf<Long?>(null)
        private set
    private var noteJob: Job? = null
    private var reactionId = 0L

    init {
        viewModelScope.launch {
            client.events.collect { e ->
                if (e.by.isEmpty() || isEcho(e.by, client.selfId)) return@collect
                val who = client.room.value?.members?.firstOrNull { it.id == e.by }?.name ?: return@collect
                say(when (e.type) { "pause" -> "$who paused"; "play" -> "$who pressed play"; else -> "$who skipped to ${clock(e.position)}" }, 1500)
            }
        }
        viewModelScope.launch {
            client.messages.collect { m ->
                fun s(k: String) = (m[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                when (s("type")) {
                    "react" -> {
                        val r = Reaction(s("by").orEmpty(), s("kind") ?: return@collect, reactionId++)
                        reactions.add(r)
                        launch { delay(2200); reactions.remove(r) }
                    }
                    "sync_heart" -> {
                        syncHeart = ms(m["at"]) ?: return@collect
                        launch { delay(3500); syncHeart = null }
                    }
                }
            }
        }
        rtc?.let { r -> viewModelScope.launch { r.notes.collect { say(it) } } }
    }

    fun say(text: String, forMs: Long = 3000) {
        note = text
        noteJob?.cancel()
        noteJob = viewModelScope.launch { delay(forMs); note = null }
    }

    /** [kind] from [REACTIONS] at media position [atMs]; it comes back to us as a broadcast and floats from our face. */
    fun react(kind: String, atMs: Long) = client.send("react") { put("kind", kind); put("at", atMs) }
    fun lights() = client.send("lights") {}
    fun setMode(mode: String) = client.send("mode") { put("mode", mode) }
    private val profile get() = accounts.session.value?.activeProfile
    private val name get() = profile?.name?.ifBlank { null } ?: GUEST
    private val avatar get() = profile?.avatar.orEmpty()

    /** Other members switching media: the player destination to replace the current one with. Collect from the nav host. */
    val loads: Flow<PartyDestination> = client.loads.map { resolve(it) }.filterNotNull().onEach { client.switching = true }

    /** Creates a room for [payload] and joins it as host (as the active profile, "Guest" if none); returns the code or null. */
    suspend fun create(payload: JsonObject, mode: String = "friends"): String? =
        client.create(payload)?.also { code ->
            client.join(code, name, avatar, host = true)
            if (mode != "friends") viewModelScope.launch {
                withTimeoutOrNull(8_000) { client.room.filterNotNull().first() }
                setMode(mode)
            }
        }

    /** Joins [code], waits for the room `state`, and prepares the player for its payload. Null = couldn't join. */
    suspend fun join(code: String): PartyDestination? {
        client.join(code, name, avatar)
        val room = withTimeoutOrNull(8_000) { client.room.filterNotNull().first() }
        val payload = room?.payload
        val dest = payload?.let { resolve(it) }
        if (dest == null) client.leave()
        return dest
    }

    private suspend fun resolve(p: JsonObject): PartyDestination? {
        fun s(k: String) = p[k]?.jsonPrimitive?.contentOrNull
        fun i(k: String) = p[k]?.jsonPrimitive?.intOrNull ?: 0
        return when (s("kind")) {
            "anime" -> PartyDestination.Anime(s("slug") ?: return null, i("episode"))
            "media" -> {
                val id = s("mediaId") ?: return null
                val streams = (movies.streams(id, i("season"), i("episode")) as? Result.Success)?.data?.takeIf { it.isNotEmpty() } ?: return null
                NowPlaying.request = PlaybackRequest(
                    historyKey = s("historyKey"), mediaId = id, title = partyTitle(s("title")), subtitle = partySubtitle(i("season"), i("episode")),
                    posterUrl = s("posterUrl"), streams = streams, season = i("season"), episode = i("episode"),
                    seasonEpisodeCount = i("seasonEpisodeCount")
                )
                PartyDestination.Media
            }
            else -> null
        }
    }
}
