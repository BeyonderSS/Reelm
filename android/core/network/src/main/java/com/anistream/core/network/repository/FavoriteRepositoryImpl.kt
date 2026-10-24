package com.anistream.core.network.repository

import com.anistream.core.common.Result
import com.anistream.core.database.ActiveProfile
import com.anistream.core.database.dao.FavoriteDao
import com.anistream.core.database.entity.FavoriteEntity
import com.anistream.domain.model.FavoriteAnime
import com.anistream.domain.repository.FavoriteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepositoryImpl @Inject constructor(
    private val dao: FavoriteDao
) : FavoriteRepository {

    override suspend fun getAll(): Result<List<FavoriteAnime>> {
        return try {
            val result = dao.getAll(ActiveProfile.id).first().map { it.toDomain() }
            Result.success(result)
        } catch (e: Exception) {
            Result.error(e)
        }
    }

    override suspend fun isFavorite(slug: String): Result<Boolean> {
        return try {
            Result.success(dao.isFavorite(slug, ActiveProfile.id))
        } catch (e: Exception) {
            Result.error(e)
        }
    }

    override suspend fun isSaved(favorite: FavoriteAnime): Result<Boolean> = try {
        Result.success(live(favorite) != null)
    } catch (e: Exception) {
        Result.error(e)
    }

    /** the row (any slug) holding the same canonical title, tombstones included */
    private suspend fun match(f: FavoriteAnime) = dao.rows(ActiveProfile.id).firstOrNull { it.toDomain().key == f.key }
    private suspend fun live(f: FavoriteAnime) = match(f)?.takeIf { !it.deleted }

    override suspend fun toggle(favorite: FavoriteAnime): Result<Boolean> {
        return try {
            val row = match(favorite)
            val on = row == null || row.deleted
            if (on) put(favorite, row) else dao.tombstone(row!!.animeSlug, ActiveProfile.id, System.currentTimeMillis())
            ActiveProfile.changes.tryEmit(Unit)
            Result.success(on)
        } catch (e: Exception) {
            Result.error(e)
        }
    }

    override suspend fun add(favorite: FavoriteAnime): Result<Unit> {
        return try {
            put(favorite, match(favorite))
            ActiveProfile.changes.tryEmit(Unit)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error(e)
        }
    }

    override suspend fun remove(slug: String): Result<Unit> {
        return try {
            dao.tombstone(slug, ActiveProfile.id, System.currentTimeMillis())
            ActiveProfile.changes.tryEmit(Unit)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error(e)
        }
    }

    /** a fresh updatedAt every time; an existing row keeps its slug so the key stays one row */
    private suspend fun put(f: FavoriteAnime, row: FavoriteEntity?) =
        dao.upsert(f.copy(animeSlug = row?.animeSlug ?: f.animeSlug, addedTimestamp = System.currentTimeMillis()).toEntity())

    private fun FavoriteEntity.toDomain() = FavoriteAnime(
        animeSlug = animeSlug,
        malId = malId,
        animeTitle = animeTitle,
        posterUrl = posterUrl,
        score = score,
        addedTimestamp = addedTimestamp,
        totalEpisodes = totalEpisodes,
        lastEpisodeWatched = lastEpisodeWatched,
        kind = kind,
        mediaType = mediaType,
        year = year
    )

    private fun FavoriteAnime.toEntity() = FavoriteEntity(
        animeSlug = animeSlug,
        malId = malId,
        animeTitle = animeTitle,
        posterUrl = posterUrl,
        score = score,
        addedTimestamp = addedTimestamp,
        totalEpisodes = totalEpisodes,
        lastEpisodeWatched = lastEpisodeWatched,
        profileId = ActiveProfile.id,
        kind = kind,
        mediaType = mediaType,
        year = year
    )
}
