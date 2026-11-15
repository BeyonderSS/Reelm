package com.anistream.feature.tv

import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.derivedStateOf
import com.anistream.core.ui.theme.Motion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.DisplayHeavy
import com.anistream.core.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fallback tint when a poster has no usable colour (or low-end skips the glow). */
internal val DuskViolet = Color(0xFF5B4B8A)

private val posterColors = LruCache<String, Int>(64) // 0 = looked, no colour

/** Vibrant/muted/dominant colour of a poster, decoded small (96x144) off the main thread; null until known. */
@Composable
internal fun rememberPosterColor(url: String?): State<Color?> {
    val ctx = LocalContext.current
    return produceState(url?.let { posterColors[it] }?.takeIf { it != 0 }?.let(::Color), url) {
        if (url == null || posterColors[url] != null) return@produceState
        val bmp = (ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(96, 144).allowHardware(false).build()).drawable as? BitmapDrawable)?.bitmap
        val rgb = bmp?.let {
            withContext(Dispatchers.Default) {
                val p = Palette.from(it).maximumColorCount(8).generate()
                (p.vibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch)?.rgb
            }
        } ?: 0
        posterColors.put(url, rgb)
        value = rgb.takeIf { it != 0 }?.let { Color(it or (0xFF shl 24)) }
    }
}

/** 1.0 on a 960dp-wide (1080p at 2x) screen; the strip and hero are drawn for that and scale to the real width. */
@Composable
internal fun uiScale() = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp / 960f

/** Rounded ticket with a half-circle notch top and bottom on the perforation line ([notch] of the width; 1/3, more for the reel). */
private class TicketShape(val s: Float, val notch: Float = 1f / 3) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = 6.7f * s * density.density
        val body = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(8.6f * s * density.density))) }
        val x = size.width * notch
        val holes = Path().apply {
            addOval(Rect(x - r, -r, x + r, r)); addOval(Rect(x - r, size.height - r, x + r, size.height + r))
        }
        return Outline.Generic(Path.combine(PathOperation.Difference, body, holes))
    }
}

private fun tint(c: Color, a: Float, b: Float) = Brush.linearGradient(listOf(lerp(Color(0xFF14151C), c, a), lerp(Color(0xFF101118), c, b)))

@Composable
private fun Ticket(item: TvItem, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    val s = uiScale()
    fun d(n: Float) = (n * s).dp
    var focused by remember { mutableStateOf(false) }
    val color by rememberPosterColor(item.imageUrl)
    // Running reel: the focused ticket widens and its art slot becomes a 16:9 frame playing the trailer
    val p = LocalTvPreview.current
    val reel by remember(p, item.key) { derivedStateOf { p != null && p.live && p.inStrip && p.key == item.key } }
    val r by animateFloatAsState(if (reel) 1f else 0f, if (LocalReduceMotion.current) snap() else tween(600, easing = Motion.emphasized), label = "reel")
    val base = if (focused) d(249.6f) else d(201.6f)
    val h = base / 2
    val art = base / 3 + (h * 16f / 9f - base / 3) * r
    val w = base + (art - base / 3)
    val notch = art / w
    val ticket = remember(s, notch) { TicketShape(s, notch) }
    Surface(
        onClick = item.onClick,
        onLongClick = item.onLongClick,
        modifier = modifier.width(w).height(h)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(ticket),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None)
    ) {
        Row(
            Modifier.fillMaxSize().background(tint(color ?: DuskViolet, 0.42f, 0.14f)).dwellRing(item.key, p, d(22f), d(8.6f)).drawWithContent {
                drawContent()
                // dashed perforation; amber inset edge when focused (outer half is clipped by the shape)
                drawLine(Color.White.copy(alpha = 0.28f), Offset(size.width * notch, 0f), Offset(size.width * notch, size.height), d(1.4f).toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(d(4f).toPx(), d(4f).toPx())))
                if (focused) drawPath((ticket.createOutline(size, layoutDirection, this) as Outline.Generic).path, Accent.main, style = Stroke(d(4.8f).toPx()))
            }
        ) {
            Box(Modifier.width(art).fillMaxSize()) {
                if (item.imageUrl != null) AsyncImage(item.imageUrl, item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                val info = p?.info
                if (reel && info != null) PreviewFrame(p, info, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).fillMaxSize().padding(start = d(13.4f), end = d(11.5f), top = d(10.6f), bottom = d(9.6f)), verticalArrangement = Arrangement.spacedBy(d(3.4f))) {
                val t = if (focused) 19.2f else 14.9f
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TvType.title.copy(fontFamily = DisplayHeavy, fontWeight = FontWeight.ExtraBold, fontSize = (t * s).sp, lineHeight = (t * 1.02f * s).sp))
                item.episodeLine?.let { Text(it, maxLines = 1, style = TvType.body.copy(fontSize = (9.8f * s).sp, lineHeight = (13f * s).sp, color = Color(0xFFDAD6E4))) }
                Spacer(Modifier.weight(1f))
                val small = TvType.body.copy(fontSize = (9.1f * s).sp, lineHeight = (13f * s).sp, color = Color(0xFFBDB9C9))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(if (focused) "OK to resume" else "", maxLines = 1, style = small.copy(color = Accent.main, fontWeight = FontWeight.SemiBold))
                    val left = item.minutesLeft
                    if (left != null) Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = DisplayHeavy, fontWeight = FontWeight.ExtraBold, fontSize = (13f * s).sp, color = Ink.high)) {
                            append(if (left >= 60) "${left / 60} h" + (if (left % 60 > 0) " ${left % 60}" else "") else "$left")
                        }
                        append(if (left >= 60 && left % 60 == 0) " left" else " min left")
                    }, maxLines = 1, style = small)
                    else item.progress?.let { p -> Text("${(p * 100).toInt()}% watched", maxLines = 1, style = small) }
                }
                item.progress?.let { p ->
                    Box(Modifier.fillMaxWidth().height(d(2.9f)).background(Color.White.copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape)) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(d(2.9f)).background(Accent.main, androidx.compose.foundation.shape.CircleShape))
                    }
                }
            }
        }
    }
}

/** Continue watching as a black 35mm strip (static -1.2 degree tilt, sprockets drawn once) of ticket stubs. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvStripRow(row: TvRow, restoreKey: String?, restoreRequester: FocusRequester, onFocused: (TvItem) -> Unit, modifier: Modifier = Modifier, up: FocusRequester? = null) {
    val s = uiScale()
    fun d(n: Float) = (n * s).dp
    // sprockets run while a ticket plays its trailer (Running reel)
    val p = LocalTvPreview.current
    val running by remember(p) { derivedStateOf { p != null && p.live && p.inStrip } }
    val shift = if (running && !LocalReduceMotion.current)
        rememberInfiniteTransition(label = "sprockets").animateFloat(0f, 1f, infiniteRepeatable(tween(450, easing = LinearEasing)), label = "run")
    else null
    // no row label: the strip itself says "continue watching" (as in the mockup)
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(d(186f)), contentAlignment = Alignment.Center) {
            // wider than the screen so the tilt never shows the strip's ends
            Box(
                Modifier.requiredWidth(d(1040f)).height(d(177.6f)).graphicsLayer { rotationZ = -1.2f }
                    .background(Color(0xFF08080B))
                    .drawBehind {
                        val hole = Color(0xFF2A2A30)
                        val hw = d(11.5f).toPx(); val hh = d(10.6f).toPx(); val y = d(5.8f).toPx(); val cr = CornerRadius(d(1f).toPx())
                        var x = d(4f).toPx() - (shift?.value ?: 0f) * d(28.8f).toPx()
                        while (x < size.width) {
                            drawRoundRect(hole, Offset(x, y), Size(hw, hh), cr)
                            drawRoundRect(hole, Offset(x, size.height - y - hh), Size(hw, hh), cr)
                            x += d(28.8f).toPx()
                        }
                    }
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxSize().focusRestorer(),
                    contentPadding = PaddingValues(horizontal = Overscan + d(40f)),
                    horizontalArrangement = Arrangement.spacedBy(d(11.5f)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(row.items, key = { it.key }) { item ->
                        // the strip is tilted, so plain focus search would read the next (higher) stub as "up"
                        val m = if (up != null) Modifier.focusProperties { this.up = up } else Modifier
                        Ticket(item, { onFocused(item) }, if (item.key == restoreKey) m.focusRequester(restoreRequester) else m)
                    }
                }
            }
        }
    }
}
