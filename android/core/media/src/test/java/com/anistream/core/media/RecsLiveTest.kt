package com.anistream.core.media

import com.anistream.core.common.Result
import com.anistream.core.database.dao.RecommendationDao
import com.anistream.core.database.entity.RecommendationEntity
import com.anistream.domain.model.WatchHistory
import com.anistream.domain.repository.AnidbRepository
import com.anistream.domain.repository.FavoriteRepository
import com.anistream.domain.repository.MoviesRepository
import com.anistream.domain.repository.WatchHistoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.math.ln

/**
 * Real network, real ranking code: prints the top 15 media picks for an Indian and an English history.
 * RECS_LIVE=1 ./gradlew :core:media:testDebugUnitTest --tests '*RecsLiveTest*' --rerun -i
 */
class RecsLiveTest {

    private inline fun <reified T> stub(crossinline h: (String, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, a -> h(m.name, a) } as T

    private fun run(name: String, seeds: List<Pair<String, Double>>) = runBlocking {
        val now = System.currentTimeMillis()
        // weight = 0.5^(age/30) for a finished title, so the age that gives each wanted weight
        val history = seeds.map { (tt, w) -> WatchHistory(animeSlug = tt, animeTitle = tt, kind = "media", mediaId = tt, watchProgress = 1f, lastWatchedTimestamp = now - (-30 * ln(w) / ln(2.0) * 86_400_000).toLong()) }
        var picks: List<RecommendationEntity> = emptyList()
        val repo = RecommendationsRepositoryImpl(
            MediaHttp(),
            stub<RecommendationDao> { n, a -> if (n == "replace" && a!![1] == "media") @Suppress("UNCHECKED_CAST") run { picks = a[2] as List<RecommendationEntity> }; Unit },
            stub<WatchHistoryRepository> { n, _ -> if (n == "getAll") Result.success(history) else error(n) },
            stub<FavoriteRepository> { n, _ -> if (n == "getAll") Result.success(emptyList<Any>()) else error(n) },
            stub<AnidbRepository> { n, _ -> error(n) },
            stub<MoviesRepository> { n, a -> if (n == "imdbId") a!![0] else error(n) },
            com.anistream.core.account.AccountApi(), { error("signed out") }
        )
        println("== $name: ${repo.refresh()}")
        picks.take(15).forEachIndexed { i, p -> println("${(i + 1).toString().padStart(2)} ${p.mediaType?.take(6)?.padEnd(6)} ${p.title.take(40).padEnd(40)} ${p.year} ${"%.1f".format(p.rating ?: 0.0)} because ${p.because}") }
    }

    @Test fun live() {
        assumeTrue(System.getenv("RECS_LIVE") != null)
        run("Indian history", listOf("tt1187043" to 1.0, "tt5074352" to 0.9, "tt6473300" to 0.9, "tt6077448" to 0.8, "tt9389998" to 0.7, "tt13751694" to 0.7))
        run("English history", listOf("tt1196946" to 1.0, "tt0903747" to 0.8, "tt0816692" to 0.7))
    }
}
