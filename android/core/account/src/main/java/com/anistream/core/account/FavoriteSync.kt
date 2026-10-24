package com.anistream.core.account

import android.content.Context
import com.anistream.core.database.dao.FavoriteDao
import com.anistream.core.database.entity.FavoriteEntity
import com.anistream.domain.model.favKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private fun FavoriteEntity.key() = favKey(kind, animeSlug, mediaType, animeTitle)

/** Favourites sync, same design as [HistorySync]: last-write-wins on updatedAt (= addedTimestamp), tombstones, `since` = server clock. */
@Singleton
class FavoriteSync @Inject constructor(
    @ApplicationContext context: Context,
    private val account: AccountRepository,
    private val api: AccountApi,
    private val dao: FavoriteDao
) {
    private val prefs = context.getSharedPreferences("favorite_sync", Context.MODE_PRIVATE)
    private val lock = Mutex()

    suspend fun pull(): Unit = lock.withLock {
        val s = account.session.value ?: return@withLock
        val profile = s.activeProfile?.id ?: return@withLock
        val adoptKey = "adopted:${s.user.id}"
        if (!prefs.getBoolean(adoptKey, false)) { // first sign-in on this device: offline favourites join this profile and get uploaded by push
            dao.adoptLocal(profile)
            prefs.edit().putBoolean(adoptKey, true).apply()
        }
        val res = api.call("GET", "/v1/profiles/$profile/favorites?since=${prefs.getLong("since:$profile", 0L)}", token = s.token) ?: return@withLock
        val local = dao.rows(profile).associateBy { it.key() }
        res["entries"]?.jsonArray?.map { it.jsonObject }?.forEach { e ->
            val key = e["key"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val at = e["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L
            val cur = local[key]
            if (cur != null && at <= cur.addedTimestamp) return@forEach
            val p = e["payload"] as? JsonObject ?: JsonObject(emptyMap())
            fun str(k: String) = p[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val kind = if (key.startsWith("media:")) "media" else "anime"
            dao.upsert(FavoriteEntity(
                animeSlug = cur?.animeSlug ?: str("id") ?: str("mediaId") ?: str("slug") ?: key.substringAfter(':'),
                malId = p["malId"]?.jsonPrimitive?.longOrNull, animeTitle = str("title") ?: cur?.animeTitle.orEmpty(),
                posterUrl = str("poster"), score = p["score"]?.jsonPrimitive?.doubleOrNull,
                addedTimestamp = at, totalEpisodes = p["total"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                lastEpisodeWatched = p["episode"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                profileId = profile, deleted = e["deleted"]?.jsonPrimitive?.booleanOrNull ?: false, kind = kind,
                mediaType = if (kind == "media") key.removePrefix("media:").substringBefore(':') else null,
                year = p["year"]?.jsonPrimitive?.longOrNull?.toInt()
            ))
        }
        res["now"]?.jsonPrimitive?.longOrNull?.let { prefs.edit().putLong("since:$profile", it).apply() }
    }

    suspend fun push(): Unit = lock.withLock {
        val s = account.session.value ?: return@withLock
        val profile = s.activeProfile?.id ?: return@withLock
        val rows = dao.changedSince(profile, prefs.getLong("pushed:$profile", 0L))
        if (rows.isEmpty()) return@withLock
        rows.chunked(100).forEach { chunk ->
            val body = buildJsonObject { put("entries", JsonArray(chunk.map { r -> entryJson(r) })) }
            api.call("PUT", "/v1/profiles/$profile/favorites", body, s.token)
        }
        prefs.edit().putLong("pushed:$profile", rows.maxOf { it.addedTimestamp }).apply()
    }

    private fun entryJson(r: FavoriteEntity) = buildJsonObject {
        put("key", r.key())
        put("payload", buildJsonObject {
            put("id", r.animeSlug); put("kind", r.kind); put("title", r.animeTitle); put("poster", r.posterUrl)
            if (r.kind == "media") put("mediaId", r.animeSlug) else put("slug", r.animeSlug)
            put("type", r.mediaType); put("year", r.year); put("malId", r.malId); put("score", r.score)
            put("total", r.totalEpisodes); put("episode", r.lastEpisodeWatched)
        })
        put("updatedAt", r.addedTimestamp); put("deleted", r.deleted)
    }
}
