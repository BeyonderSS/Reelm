package com.anistream.feature.party

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where a face sits, as fractions of the player: top-left corner and width (height follows the face's ratio). */
data class FaceSpot(val x: Float, val y: Float, val w: Float)

/**
 * A face of width [w] px and height [w]·[ratio] px with its top-left at ([x], [y]), fitted into a [sw]×[sh] px player:
 * width clamped to [minW]..60 % of the player (and never taller than it), the face kept fully on screen, then, when
 * [snap] > 0, pulled flush to the nearest screen edge or black-bar edge ([bar] px wide on each side) within [snap] px.
 */
fun fitFace(x: Float, y: Float, w: Float, ratio: Float, sw: Float, sh: Float, minW: Float, bar: Float = 0f, snap: Float = 0f): FaceSpot {
    val fw = faceWidth(w, ratio, sw, sh, minW)
    val fh = fw * ratio
    var fx = x.coerceIn(0f, max(0f, sw - fw))
    var fy = y.coerceIn(0f, max(0f, sh - fh))
    if (snap > 0f) {
        fun pull(v: Float, lines: List<Float>) = lines.minBy { abs(it - v) }.takeIf { abs(it - v) <= snap } ?: v
        fx = pull(fx, listOf(0f, sw - fw) + (if (bar > 0f) listOf(bar - fw, sw - bar) else emptyList()).filter { it in 0f..sw - fw })
        fy = pull(fy, listOf(0f, sh - fh))
    }
    return FaceSpot(fx / sw, fy / sh, fw / sw)
}

/** [w] held to a face's limits: [minW] up to 60 % of the player's width, never taller than it. */
fun faceWidth(w: Float, ratio: Float, sw: Float, sh: Float, minW: Float) = w.coerceIn(minW, maxFaceWidth(ratio, sw, sh, minW))

private fun maxFaceWidth(ratio: Float, sw: Float, sh: Float, minW: Float) = max(minW, min(sw * 0.6f, sh / ratio))

/** The size button's next width: small ([minW]) → medium (25 % of the player) → big (45 %, or as big as fits) → small. */
fun nextFaceWidth(w: Float, ratio: Float, sw: Float, sh: Float, minW: Float): Float =
    listOf(minW, sw * 0.25f, sw * 0.45f).map { it.coerceAtMost(maxFaceWidth(ratio, sw, sh, minW)) }.firstOrNull { it > w + 1f } ?: minW

/** Saved-spot slot for member [id]: self is 0, then everyone else in room order from 1. */
fun faceSlot(ids: List<String>, id: String, me: String?): Int = if (id == me) 0 else ids.filter { it != me }.indexOf(id) + 1
