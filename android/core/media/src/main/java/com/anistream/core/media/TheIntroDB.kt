package com.anistream.core.media

import com.anistream.domain.model.SkipInterval
import com.anistream.domain.repository.MediaSkipTimesRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** TheIntroDB (no key; needs a User-Agent, which [MediaHttp] sends): intro / recap / credits / preview for movies and series. */
@Singleton
class TheIntroDB @Inject constructor(private val http: MediaHttp) : MediaSkipTimesRepository {

    private val cache = ConcurrentHashMap<String, List<SkipInterval>>()

    override suspend fun skipTimes(imdbId: String, season: Int, episode: Int): List<SkipInterval> {
        val q = if (season > 0 && episode > 0) "imdb_id=$imdbId&season=$season&episode=$episode" else "imdb_id=$imdbId"
        cache[q]?.let { return it }
        return try {
            parse(http.get("https://api.theintrodb.org/v4/media?$q")).also { cache[q] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList() // 404 = no submissions; offline = same as no data
        }
    }

    companion object {
        private val TYPES = listOf("intro" to "op", "recap" to "recap", "credits" to "ed", "preview" to "preview")

        /** First segment of each kind, in ms. start_ms null = 0, end_ms null = end of video ([Long.MAX_VALUE]). */
        fun parse(json: String): List<SkipInterval> {
            val o = MediaHttp.json.parseToJsonElement(json).jsonObject
            return TYPES.mapNotNull { (key, type) ->
                val seg = (o[key] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
                val start = (seg["start_ms"] as? JsonPrimitive)?.longOrNull ?: 0L
                val end = (seg["end_ms"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE
                if (end <= start) null else SkipInterval(start, end, type)
            }.sortedBy { it.startMs }
        }
    }
}
