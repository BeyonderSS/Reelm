package com.anistream.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageResult
import coil.size.Dimension
import coil.size.Size
import com.anistream.app.work.createNotificationChannels
import com.anistream.app.work.schedulePeriodicWork
import com.anistream.core.common.LowEnd
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AniStreamApp : Application(), Configuration.Provider, ImageLoaderFactory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var updates: com.anistream.feature.party.UpdateManager
    @Inject lateinit var accountRuntime: com.anistream.core.account.AccountRuntime

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        LowEnd.eligible = BuildConfig.FLAVOR == "tv"
        com.anistream.domain.AudioLanguage.init(this)
        super.onCreate()
        createNotificationChannels(this)
        schedulePeriodicWork(this)
        com.anistream.app.work.notifyUpdates(this, updates, kotlinx.coroutines.MainScope())
        accountRuntime.start(this, if (BuildConfig.FLAVOR == "tv") "tv" else "phone")
    }

    /** Phone: Coil defaults. Low-end TV: memory cache ≤ 15 % of heap, RGB_565, every decode ≤ 300 px wide. */
    override fun newImageLoader(): ImageLoader {
        val builder = ImageLoader.Builder(this)
        if (!LowEnd.isOn(this)) return builder.build()
        return builder
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
            .allowRgb565(true)
            .crossfade(false)
            .allowHardware(true)
            // ponytail: one width cap for posters and the backdrop alike (the dimmed backdrop just goes soft); per-call sizes if that looks bad
            .components { add(CapWidth(300)) }
            .build()
    }

    private class CapWidth(private val max: Int) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val w = chain.size.width
            if (w !is Dimension.Pixels || w.px <= max) return chain.proceed(chain.request)
            val h = chain.size.height
            val capped = Size(Dimension(max), if (h is Dimension.Pixels) Dimension((h.px.toLong() * max / w.px).toInt().coerceAtLeast(1)) else h)
            return chain.withSize(capped).proceed(chain.request)
        }
    }
}
