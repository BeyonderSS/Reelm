package com.anistream.feature.player

import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/** Connection estimate and per-show quality pins for the auto-pick; ExoPlayer's default builder shares the same meter singleton. */
@OptIn(UnstableApi::class)
internal object QualityPick {
    fun bandwidth(context: Context): Long {
        val est = DefaultBandwidthMeter.getSingletonInstance(context).bitrateEstimate
        if (est > 0) return est
        val metered = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered == true
        return if (metered) 3_000_000 else 10_000_000
    }

    private fun prefs(context: Context) = context.getSharedPreferences("quality_pins", Context.MODE_PRIVATE)
    fun pinned(context: Context, key: String): Int? = prefs(context).getInt(key, 0).takeIf { it > 0 }
    fun pin(context: Context, key: String, height: Int) { if (height > 0) prefs(context).edit().putInt(key, height).apply() }
}
