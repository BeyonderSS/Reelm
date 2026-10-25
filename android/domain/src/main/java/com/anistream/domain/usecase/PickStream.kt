package com.anistream.domain.usecase

import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.StreamSource

private val DOLBY_VISION = Regex("""dolby vision|\bDV\b""", RegexOption.IGNORE_CASE)
private val HIGH_BIT = Regex("""10-?bit|HDR|\bDV\b|Dolby Vision|x265.*10""", RegexOption.IGNORE_CASE)

/** Typical bitrate by frame height, bits/s. */
fun nominalBitrate(height: Int): Double = when {
    height >= 2160 -> 16e6
    height >= 1440 -> 9e6
    height >= 1080 -> 5e6
    height >= 720 -> 2.5e6
    height >= 480 -> 1.2e6
    else -> 0.7e6
}

private fun MediaStream.text() = listOfNotNull(codec, qualityLabel).joinToString(" ")

/** Phone: only Dolby Vision is unplayable. */
fun canPlayPhone(s: MediaStream) = !DOLBY_VISION.containsMatchIn(s.text())

/** Low-end TV (Fire TV Stick): no 10-bit/HDR/DV, nothing above 1080p. */
fun canPlayLowEnd(s: MediaStream) = !HIGH_BIT.containsMatchIn(s.text()) && s.height <= 1080

/** Adaptive manifests fit any connection (0 bps); files use the height table. */
fun MediaStream.estimatedBitrate(): Double = if (isDirectFile) nominalBitrate(height) else 0.0

/**
 * Index to start with. A pinned height wins (exact, else closest lower) regardless of bandwidth; otherwise the
 * highest height at <= 70% of [bandwidthBps], else the lowest bitrate. Ties keep source order.
 */
fun <T> pickIndex(items: List<T>, height: (T) -> Int, bitrate: (T) -> Double, bandwidthBps: Long, pinnedHeight: Int?, canPlay: (T) -> Boolean): Int {
    if (items.isEmpty()) return 0
    val ok = items.indices.filter { canPlay(items[it]) }.ifEmpty { items.indices.toList() }
    if (pinnedHeight != null && pinnedHeight > 0) {
        ok.firstOrNull { height(items[it]) == pinnedHeight }?.let { return it }
        ok.filter { height(items[it]) < pinnedHeight }.maxByOrNull { height(items[it]) }?.let { return it }
    }
    val fits = ok.filter { bitrate(items[it]) <= 0.7 * bandwidthBps }
    return fits.maxByOrNull { height(items[it]) } ?: ok.minBy { bitrate(items[it]) }
}

fun pickStream(streams: List<MediaStream>, bandwidthBps: Long, pinnedHeight: Int?, canPlay: (MediaStream) -> Boolean): Int =
    pickIndex(streams, { it.height }, { it.estimatedBitrate() }, bandwidthBps, pinnedHeight, canPlay)

/** HLS quality variants of one provider. */
fun pickSource(variants: List<StreamSource>, bandwidthBps: Long, pinnedHeight: Int?, canPlay: (StreamSource) -> Boolean = { true }): Int =
    pickIndex(variants, { it.height }, { if (it.bandwidth > 0) it.bandwidth.toDouble() else nominalBitrate(it.height) }, bandwidthBps, pinnedHeight, canPlay)
