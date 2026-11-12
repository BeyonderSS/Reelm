package com.anistream.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import com.anistream.core.database.ActiveProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anistream.app.navigation.Routes
import com.anistream.domain.model.POPULAR_ON_REELM
import com.anistream.domain.repository.AnidbRepository
import com.anistream.domain.repository.FavoriteRepository
import com.anistream.domain.repository.RecommendationsRepository
import com.anistream.domain.repository.WatchHistoryRepository
import com.anistream.feature.movies.isMediaKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.FlowPreview::class)
fun schedulePeriodicWork(context: Context) {
    val wm = WorkManager.getInstance(context)
    val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    wm.enqueueUniquePeriodicWork(
        "new-episodes", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<NewEpisodeWorker>(6, TimeUnit.HOURS).setConstraints(network).build()
    )
    wm.enqueueUniquePeriodicWork(
        "reminders", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS).build()
    )
    // the first run starts right away, so a fresh install gets picks without waiting a day
    wm.enqueueUniquePeriodicWork(
        "recommendations", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<RecommendationsWorker>(24, TimeUnit.HOURS).setConstraints(network).build()
    )
    // Between daily runs: at start and on a profile switch (each profile has its own picks), and after a watch reaches 70 %
    // (history writes ping ActiveProfile.changes; debounced so playback's progress saves don't each enqueue work).
    // The worker itself skips when the picks are fresh. A first install with no history gets picks after its first watch.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch { ActiveProfile.flow.collect { refreshPicksIfStale(context, afterWatch = false) } }
    scope.launch { ActiveProfile.changes.debounce(30_000).collect { refreshPicksIfStale(context, afterWatch = true) } }
}

private fun refreshPicksIfStale(context: Context, afterWatch: Boolean) {
    val request = OneTimeWorkRequestBuilder<RecommendationsWorker>()
        .setInputData(workDataOf(RecommendationsWorker.IF_STALE to true, RecommendationsWorker.AFTER_WATCH to afterWatch))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    // a profile switch replaces a refresh in flight for the previous profile
    WorkManager.getInstance(context).enqueueUniqueWork("recommendations-now", if (afterWatch) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
}

/** Daily "For you" refresh (and the one-off ones above); Home reads the Room cache, so it never waits on this. */
@HiltWorker
class RecommendationsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val recommendations: RecommendationsRepository,
    private val history: WatchHistoryRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("recommendations", Context.MODE_PRIVATE)
        val profile = ActiveProfile.id
        if (inputData.getBoolean(IF_STALE, false)) {
            val fresh = System.currentTimeMillis() - prefs.getLong("at:$profile", 0) < STALE_MS
            // "Popular on Reelm" rows alone don't count: a refresh that raced the history pull or lost TMDB leaves only those
            val empty = listOf("media", "anime").all { k -> recommendations.observe(k).first().all { it.because == POPULAR_ON_REELM } }
            if (fresh && !empty) return Result.success()
            val lastWatch = history.getAll().getOrNull()?.firstOrNull()?.watchProgress ?: 0f
            if (inputData.getBoolean(AFTER_WATCH, false) && lastWatch < 0.7f) return Result.success()
        }
        if (recommendations.refresh().isSuccess()) {
            prefs.edit().putLong("at:$profile", System.currentTimeMillis()).apply()
            return Result.success()
        }
        return if (runAttemptCount >= 2) Result.success() else Result.retry()
    }

    companion object {
        const val IF_STALE = "ifStale"
        const val AFTER_WATCH = "afterWatch"
        val STALE_MS = TimeUnit.HOURS.toMillis(6)
    }
}

/** Notifies when a favourite anime's episode count grew since the last check. */
@HiltWorker
class NewEpisodeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val favorites: FavoriteRepository,
    private val anidb: AnidbRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("episode_counts", Context.MODE_PRIVATE)
        favorites.getAll().getOrNull().orEmpty().filterNot { isMediaKey(it.animeSlug) }.forEach { fav ->
            val episodes = anidb.getEpisodes(fav.animeSlug).getOrNull()?.takeIf { it.isNotEmpty() } ?: return@forEach
            val known = prefs.getInt(fav.animeSlug, -1) // -1 = first check: just record, don't notify
            if (known in 0 until episodes.size) {
                val n = notificationBuilder(applicationContext, CH_EPISODES)
                    .setContentTitle("New episode of ${fav.animeTitle}")
                    .setContentText("Episode ${episodes.maxOf { it.number }} is out")
                    .setContentIntent(openAppIntent(applicationContext, Routes.animeDetail(fav.animeSlug), fav.animeSlug.hashCode()))
                    .setAutoCancel(true)
                    .build()
                postNotification(applicationContext, fav.animeSlug.hashCode(), n)
            }
            prefs.edit().putInt(fav.animeSlug, episodes.size).apply()
        }
        return Result.success()
    }
}

/** At most daily: nudge about the most recent unfinished item untouched for 2+ days, once per item. */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val history: WatchHistoryRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
        val item = history.getContinueWatching().getOrNull().orEmpty()
            .filter { it.lastWatchedTimestamp < cutoff }
            .maxByOrNull { it.lastWatchedTimestamp } ?: return Result.success()
        val prefs = applicationContext.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        val key = "${item.animeSlug}@${item.lastWatchedTimestamp}"
        if (prefs.getString("last", null) == key) return Result.success()
        prefs.edit().putString("last", key).apply()
        val episode = item.lastEpisodeWatched.takeIf { it > 0 }?.let { ", Episode $it" }.orEmpty()
        val n = notificationBuilder(applicationContext, CH_REMINDERS)
            .setContentTitle("Pick up where you left off")
            .setContentText("${item.animeTitle}$episode")
            .setContentIntent(openAppIntent(applicationContext, Routes.detailFor(item.animeSlug), REMINDER_ID))
            .setAutoCancel(true)
            .build()
        postNotification(applicationContext, REMINDER_ID, n)
        return Result.success()
    }

    private companion object {
        const val REMINDER_ID = 0x5EE1
    }
}
