package com.anistream.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.anistream.core.common.LowEnd
import com.anistream.domain.AudioLanguage
import com.anistream.domain.model.MediaStream

/** ExoPlayer preferring [AudioLanguage]; in low-end mode with 15 s / 30 s buffers (default 50 s) and adaptive streams capped at the hardware decoder's max size (1080p on the Fire TV Stick 2). */
@OptIn(UnstableApi::class)
internal fun buildPlayer(context: Context): ExoPlayer {
    val low = LowEnd.isOn(context)
    return ExoPlayer.Builder(context)
        .apply { if (low) setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 30_000, 2_500, 5_000).build()) }
        .build()
        .apply {
            if (low) trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(LowEnd.maxWidth, LowEnd.maxHeight).build()
            // multi-audio files start on the device's preferred language (Hindi in a "Dual Audio" release)
            trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredAudioLanguage(AudioLanguage.preferred.value).build()
        }
}

/** A dead link (HTTP 4xx) fails at once so the next source starts, instead of ~4 retries (~10 s); network errors and 5xx still retry. */
@OptIn(UnstableApi::class)
internal object FailFast4xx : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if ((info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode in 400..499) C.TIME_UNSET else super.getRetryDelayMsFor(info)
}

/**
 * Low-end stream order: what the hardware decodes first; above the decoder's max height (4K) next; HEVC without a
 * hardware decoder and 10-bit/HDR without HEVC Main10 last. Nothing is dropped, so the last buckets still play when
 * nothing else exists. Stable, so the source ranking is kept within each bucket.
 */
internal fun lowEndOrder(
    streams: List<MediaStream>,
    hardwareHevc: Boolean = LowEnd.hasHardwareHevc,
    main10: Boolean = LowEnd.hasHevcMain10,
    maxHeight: Int = LowEnd.maxHeight
): List<MediaStream> = streams.sortedBy {
    val text = listOfNotNull(it.codec, it.qualityLabel).joinToString(" ")
    val undecodable = (!hardwareHevc && LowEnd.isHevc(text)) || (!main10 && LowEnd.isHighBitDepth(text))
    (if (undecodable) 2 else 0) + (if (it.height > maxHeight) 1 else 0)
}
