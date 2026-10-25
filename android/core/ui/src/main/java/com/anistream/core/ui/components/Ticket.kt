package com.anistream.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anistream.core.ui.theme.DisplayHeavy
import com.anistream.core.ui.theme.Ink

/**
 * Marquee ticket stub: a rounded rect with two half-circle notches on the perforation line.
 * [vertical] = notches top and bottom at [at] of the width (poster | info stub);
 * otherwise notches left and right at [at] of the height (a stub button).
 */
class TicketShape(
    private val corner: Dp = 16.dp,
    private val notch: Dp = 9.dp,
    private val at: Float = 0.5f,
    private val vertical: Boolean = false,
    private val atDp: Dp? = null
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { notch.toPx() }
        val body = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(with(density) { corner.toPx() }))) }
        val holes = Path().apply {
            if (vertical) {
                val x = atDp?.let { with(density) { it.toPx() } } ?: (size.width * at)
                addOval(Rect(x - r, -r, x + r, r)); addOval(Rect(x - r, size.height - r, x + r, size.height + r))
            } else {
                val y = size.height * at
                addOval(Rect(-r, y - r, r, y + r)); addOval(Rect(size.width - r, y - r, size.width + r, y + r))
            }
        }
        return Outline.Generic(Path.combine(PathOperation.Difference, body, holes))
    }
}

/** Marquee title: a solid line over an outlined line ("The Dark Knight" / "Rises"), Bricolage ExtraBold. */
@Composable
fun MarqueeTitle(solid: String, outline: String?, modifier: Modifier = Modifier, size: Int = 34, maxLines: Int = 2) {
    val stroke = with(LocalDensity.current) { 1.5.dp.toPx() }
    val style = TextStyle(fontFamily = DisplayHeavy, fontSize = size.sp, lineHeight = (size + 2).sp, letterSpacing = (-0.5).sp, color = Ink.high)
    Column(modifier) {
        Text(solid, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        if (!outline.isNullOrBlank()) Text(outline, style = style.copy(drawStyle = Stroke(stroke)), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "The Dark Knight Rises" -> ("The Dark Knight", "Rises"); a one-word title has no outline line. */
fun marqueeSplit(title: String): Pair<String, String?> {
    val colon = title.indexOf(':')
    if (colon in 1 until title.length - 1) return title.substring(0, colon).trim() to title.substring(colon + 1).trim()
    val words = title.trim().split(' ')
    return if (words.size < 2) title to null else words.dropLast(1).joinToString(" ") to words.last()
}
