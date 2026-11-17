package com.anistream.feature.movies

import android.util.Log
import com.anistream.domain.repository.MoviesRepository
import com.anistream.domain.repository.WatchHistoryRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the stream lookup Play would make, keyed exactly as the detail page's [MovieDetailViewModel.play] asks for it
 * (the detail's own id + the Play/Continue episode), so the player finds it running or done.
 */
@Singleton
class PlayPrefetch @Inject constructor(
    private val movies: MoviesRepository,
    private val history: WatchHistoryRepository
) {
    /** title id → when [title] last ran: Home rows re-focus the same titles over and over */
    private val recent = ConcurrentHashMap<String, Long>()

    fun start(id: String, season: Int, episode: Int) {
        Log.d(TAG, "$id:$season:$episode")
        movies.prefetch(id, season, episode)
    }

    /** A Home title ([id] = any MediaItem.id or a history row's mediaId): resolve it the way its detail page does, then [start]. */
    suspend fun title(id: String) {
        if (isCanonicalKey(id)) return
        val now = System.currentTimeMillis()
        if (now - (recent[id] ?: 0L) < REPEAT_MS) return
        recent[id] = now
        val d = movies.detail(id).getOrNull()?.takeIf { it.playable } ?: run { recent.remove(id); return }
        val t = playTarget(d, historyOf(d, history.getAll().getOrNull().orEmpty())?.let { resumeOf(d, it) })
        start(d.id, t.season, t.episode)
    }

    private companion object {
        const val TAG = "Prefetch"
        /** the stream cache's lifetime: sooner would only re-hit it */
        const val REPEAT_MS = 4 * 60_000L
    }
}
