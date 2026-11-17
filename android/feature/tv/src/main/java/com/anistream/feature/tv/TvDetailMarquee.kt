package com.anistream.feature.tv

import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.lifecycle.ViewModel
import androidx.palette.graphics.Palette
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Size as CoilSize
import com.anistream.core.media.EpisodeGuide
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Display
import com.anistream.core.ui.theme.DisplayHeavy
import com.anistream.core.ui.theme.Ink
import com.anistream.domain.model.AudioVariant
import com.anistream.domain.model.Eps
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/** Hilt door to the singleton [EpisodeGuide] (TMDB / Kitsu stills, names, runtimes). */
@HiltViewModel
class EpisodeGuideViewModel @Inject constructor(val guide: EpisodeGuide) : ViewModel()

/** One ticket stub. [runtime] minutes, [p] 0..1 progress, [done] = counts as watched. */
internal class Stub(val n: Int, val name: String, val still: String?, val runtime: Int?, val overview: String?, val p: Float, val done: Boolean)

private val Brass = Color(0xFF8A6E4B)
private val SegText = Color(0xFFCFCBD9)

/** 1cqw of the design: 1% of the screen width. */
@Composable
private fun cqw(): Dp = (LocalConfiguration.current.screenWidthDp / 100f).dp

@Composable
private fun Dp.sp(): androidx.compose.ui.unit.TextUnit = with(LocalDensity.current) { toSp() }

// ponytail: duplicate of rememberPosterColor in TvFilmStrip.kt (Home); dedupe into one helper later.
private val tints = LruCache<String, Int>(16)

/** Show colour from the poster (Palette, decoded small); the fixed brass colour on low-end, or until known. */
@Composable
private fun rememberShowTint(url: String?): Color {
    val lowEnd = LocalLowEnd.current
    val ctx = LocalContext.current
    if (lowEnd || url == null) return Brass
    val c by produceState(tints[url]?.takeIf { it != 0 }?.let { Color(it) } ?: Brass, url) {
        if (tints[url] != null) return@produceState
        val bmp = (ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(96, 144).allowHardware(false).build()).drawable as? BitmapDrawable)?.bitmap
        val rgb = bmp?.let {
            withContext(Dispatchers.Default) {
                val p = Palette.from(it).maximumColorCount(8).generate()
                (p.vibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch)?.rgb
            }
        } ?: 0
        tints.put(url, rgb)
        if (rgb != 0) value = Color(rgb or (0xFF shl 24))
    }
    return c
}

/** "esla dub" -> Spanish (Latin America), "Original Audio" -> Original; unknown codes go through Locale. */
internal fun audioLabel(v: AudioVariant): String {
    val clean = v.label.trim().replace(Regex("(?i)\\s+(dub|sub|audio)$"), "").trim()
    val k = clean.lowercase()
    if (k.isEmpty() || k == "original") return "Original"
    AudioNames[k]?.let { return it }
    fun lang(c: String) = Locale(c).displayLanguage.takeIf { it.isNotBlank() && !it.equals(c, true) }
    return (if (k.length <= 3) lang(k) else null) ?: lang(v.languageCode.lowercase().take(2))?.takeIf { k.length <= 4 } ?: clean.replaceFirstChar { it.uppercase() }
}

private val AudioNames = mapOf(
    "esla" to "Spanish (Latin America)", "es-la" to "Spanish (Latin America)", "eses" to "Spanish (Spain)",
    "ptbr" to "Portuguese (Brazil)", "pt-br" to "Portuguese (Brazil)", "ptpt" to "Portuguese (Portugal)",
    "zhcn" to "Chinese (Simplified)", "zhtw" to "Chinese (Traditional)", "frca" to "French (Canada)", "enus" to "English (US)", "engb" to "English (UK)"
)

private fun minLabel(m: Int) = if (m >= 60) "${m / 60} h${if (m % 60 > 0) " ${m % 60} min" else ""}" else "$m min"

/** "30 min left" for a partly watched episode with a known runtime. */
internal fun leftLabel(runtime: Int?, p: Float): String? =
    if (runtime != null && runtime > 0 && p in 0.02f..<Eps.DONE) minLabel(((1 - p) * runtime).toInt().coerceAtLeast(1)) + " left" else null

/** Marquee button: [primary] amber, others glass; focus = amber fill (primary gets a white ring). [sub] is the small trailing note. */
@Composable
internal fun MarqueeBtn(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = false, selected: Boolean = false, enabled: Boolean = true, sub: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val u = cqw()
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(percent = 50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Accent.main else if (selected) Accent.soft else Color.White.copy(alpha = 0.1f),
            contentColor = if (primary) Accent.on else if (selected) Accent.main else Ink.high,
            focusedContainerColor = Accent.main, focusedContentColor = Accent.on,
            pressedContainerColor = Accent.pressed, pressedContentColor = Accent.on,
            disabledContainerColor = Color.White.copy(alpha = 0.05f), disabledContentColor = Ink.faint
        ),
        // focused primary keeps its amber and gains a ring a hair outside it (the mockup's double edge)
        border = ClickableSurfaceDefaults.border(focusedBorder = if (primary) androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(u * 0.2f, Accent.main), inset = u * 0.2f, shape = RoundedCornerShape(percent = 50)) else androidx.tv.material3.Border.None),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (LocalReduceMotion.current) 1f else 1.06f)
    ) {
        Row(Modifier.padding(horizontal = u * 1.9f, vertical = u * 0.8f), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(u * 1.3f)); Spacer(Modifier.width(u * 0.4f)) }
            Text(text, maxLines = 1, style = TextStyle(fontFamily = com.anistream.core.ui.theme.TextFamily, fontSize = (u * 1.15f).sp(), fontWeight = FontWeight.SemiBold))
            if (sub != null) Text(sub, maxLines = 1, modifier = Modifier.padding(start = u * 0.6f).alpha(0.75f), style = TextStyle(fontFamily = com.anistream.core.ui.theme.TextFamily, fontSize = (u * 0.95f).sp(), fontWeight = FontWeight.Medium, color = Color.Unspecified))
        }
    }
}

@Composable
internal fun FavouriteBtn(on: Boolean, onClick: () -> Unit) =
    MarqueeBtn(if (on) "In favourites" else "Favourite", onClick, selected = on, icon = if (on) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder)

/** One segmented pill of audio choices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AudioSegments(variants: List<AudioVariant>, onPick: (AudioVariant) -> Unit) {
    val u = cqw()
    // three choices fit beside the buttons; more fold into "+N", which opens the rest (wrapping, never cut)
    var all by remember { mutableStateOf(false) }
    val shown = if (all || variants.size <= 3) variants else (variants.filter { it.isCurrent } + variants.filterNot { it.isCurrent }).take(2).let { p -> variants.filter { it in p } }
    @Composable fun seg(text: String, current: Boolean, onClick: () -> Unit) = Surface(
        onClick = onClick, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(percent = 50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (current) Accent.soft else Color.Transparent, contentColor = if (current) Accent.main else SegText,
            focusedContainerColor = Accent.main, focusedContentColor = Accent.on
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(text, maxLines = 1, modifier = Modifier.padding(horizontal = u * 1.1f, vertical = u * 0.45f),
            style = TextStyle(fontFamily = com.anistream.core.ui.theme.TextFamily, fontSize = (u * 1f).sp(), fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal))
    }
    FlowRow(
        Modifier.background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(u * 1.6f)).padding(u * 0.3f),
        horizontalArrangement = Arrangement.spacedBy(u * 0.3f), verticalArrangement = Arrangement.spacedBy(u * 0.3f)
    ) {
        shown.forEach { v -> seg(audioLabel(v), v.isCurrent) { onPick(v) } }
        if (shown.size < variants.size) seg("+${variants.size - shown.size}", false) { all = true }
    }
}

/**
 * "D1 Marquee": giant title over a tiny-image wash (a free blur), tilted poster, buttons, season numerals and a film strip of
 * episode stubs. No blur and no per-frame animation anywhere, so it is safe on the Fire TV Stick 2.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MarqueeDetail(
    wash: String?, poster: String?, title: String, meta: List<String>, synopsis: String?, note: String?,
    actions: @Composable RowScope.() -> Unit,
    seasons: List<Int>, season: Int, onSeason: (Int) -> Unit, seasonNote: String?,
    stubs: List<Stub>, initial: Int, onStub: (Int) -> Unit, onStubFocus: (Int) -> Unit = {},
    below: (@Composable () -> Unit)? = null
) {
    val u = cqw()
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        // wash: ~24 px wide, stretched; bilinear scaling does the blur
        if (wash != null) AsyncImage(
            model = ImageRequest.Builder(ctx).data(wash).size(CoilSize(24, 14)).scale(Scale.FILL).crossfade(false).build(),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
        )
        val bg = Night
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg.copy(alpha = 0.95f), 0.5f to bg.copy(alpha = 0.72f), 1f to bg.copy(alpha = 0.35f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color.Transparent, 0.96f to bg)))
        // the marquee fills the screen; [below] (if any) scrolls up into view when focus moves down past the strip
        // the page scrolls only to show [below] while it has focus (no automatic bring-into-view: a stub coming into
        // focus must never nudge the marquee); rows inside keep their normal horizontal scrolling
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val rowSpec = LocalBringIntoViewSpec.current
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoVerticalBringIntoView) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
        Box(Modifier.fillMaxWidth().height(LocalConfiguration.current.screenHeightDp.dp)) {
        if (poster != null) AsyncImage(
            model = poster, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = u * 6, top = u * 5).width(u * 14).aspectRatio(2f / 3f)
                .graphicsLayer { rotationZ = -3f; shadowElevation = u.toPx() * 2; shape = RoundedCornerShape(u * 0.8f); clip = true }
        )
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = u * 5, top = u * 2.4f).width(u * 58), verticalArrangement = Arrangement.spacedBy(u * 1.2f)) {
                Text(
                    title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = DisplayHeavy, fontWeight = FontWeight.ExtraBold, fontSize = (u * 8).sp(), lineHeight = (u * 8 * 0.86f).sp(), letterSpacing = (-0.015f).em, color = Ink.high)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(u * 0.6f)) {
                    meta.filter { it.isNotBlank() }.forEach {
                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TvType.body.copy(color = Color(0xFFD9D6E2), fontSize = (u * 1.05f).sp(), lineHeight = (u * 1.5f).sp()),
                            modifier = Modifier.background(Color.White.copy(alpha = 0.09f), RoundedCornerShape(50)).padding(horizontal = u * 0.9f, vertical = u * 0.3f))
                    }
                }
                if (!synopsis.isNullOrBlank()) Text(synopsis, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TvType.body.copy(fontSize = (u * 1.15f).sp(), lineHeight = (u * 1.725f).sp(), color = Color(0xFFCFCCD9)))
                if (note != null) Text(note, style = TvType.body.copy(color = Accent.main, fontSize = (u * 1.15f).sp()))
                // wraps (audio choices drop to a second line) rather than running under the poster
                FlowRow(horizontalArrangement = Arrangement.spacedBy(u * 0.8f), verticalArrangement = Arrangement.spacedBy(u * 0.8f)) { actions() }
            }
            // season numerals sit right on top of the strip
            Spacer(Modifier.weight(1f))
            // the strip's row is taller than the black film (stubs pop out); the numerals sit on the film's top edge
            Box(Modifier.offset(y = u * 2.5f).zIndex(1f)) { Seasons(seasons, season, onSeason, seasonNote) }
            if (stubs.isNotEmpty()) androidx.compose.runtime.key(season) { Strip(stubs, initial, rememberShowTint(poster), onStub, onStubFocus) }
        }
        }
        if (below != null) Box(Modifier.onFocusChanged { f -> scope.launch { scroll.animateScrollTo(if (f.hasFocus) scroll.maxValue else 0) } }) { below() }
        }
        }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Seasons(seasons: List<Int>, selected: Int, onSeason: (Int) -> Unit, note: String?) {
    val u = cqw()
    LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = u * 4.6f), horizontalArrangement = Arrangement.spacedBy(u * 0.8f), verticalAlignment = Alignment.Bottom) {
        items(seasons) { s ->
            val on = s == selected
            Surface(
                onClick = { onSeason(s) }, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(u * 0.8f)),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Accent.soft),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Text(
                    "$s", modifier = Modifier.padding(horizontal = u * 0.4f),
                    style = TextStyle(
                        fontFamily = DisplayHeavy, fontWeight = FontWeight.ExtraBold, fontSize = (u * (if (on) 3.6f else 2.6f)).sp(),
                        color = if (on) Accent.main else Ink.high.copy(alpha = 0.45f), drawStyle = if (on) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = with(LocalDensity.current) { u.toPx() } * 0.12f)
                    )
                )
            }
        }
        if (note != null) item { Text(note, modifier = Modifier.padding(start = u * 0.6f, bottom = u * 0.6f), style = TvType.body.copy(fontSize = (u * 1.05f).sp(), fontWeight = FontWeight.Medium, color = Color(0xFFA3A1AE))) }
    }
}

/** Black 35mm strip, tilted -1°, sprocket holes drawn once top and bottom; the row is taller than the strip so the focused stub pops out. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Strip(stubs: List<Stub>, initial: Int, tint: Color, onStub: (Int) -> Unit, onStubFocus: (Int) -> Unit) {
    val u = cqw()
    // the episode before the current one stays in view, as in the mockup
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (initial - 1).coerceIn(0, stubs.size - 1))
    var inStrip by remember { mutableStateOf(false) }
    val current = stubs.getOrNull(initial)?.n
    val currentReq = remember { FocusRequester() }
    Box(Modifier.fillMaxWidth().padding(bottom = u * 0.5f), contentAlignment = Alignment.Center) {
        Box(Modifier.requiredWidth(u * 104).height(u * 25).graphicsLayer { rotationZ = -1f }) {
            Box(
                Modifier.align(Alignment.Center).fillMaxWidth().height(u * 19.5f).background(Color(0xFF08080B)).drawBehind {
                    val step = u.toPx() * 3
                    var x = 0f
                    while (x < size.width) {
                        for (y in listOf(0.6f, 19.5f - 1.6f)) drawRoundRect(
                            Color(0xFF2A2A30), Offset(x + step * 0.3f, y * (size.height / 19.5f)), Size(step * 0.4f, size.height / 19.5f), CornerRadius(2f)
                        )
                        x += step
                    }
                }
            )
            LazyRow(
                state = state, modifier = Modifier.fillMaxSize().focusRestorer { currentReq }.onFocusChanged { inStrip = it.hasFocus }, contentPadding = PaddingValues(horizontal = u * 7), horizontalArrangement = Arrangement.spacedBy(u * 1.3f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(stubs, key = { _, s -> s.n }) { _, s -> StubCard(s, tint, lit = !inStrip && s.n == current, (if (s.n == current) Modifier.focusRequester(currentReq) else Modifier).onFocusChanged { if (it.hasFocus) onStubFocus(s.n) }) { onStub(s.n) } }
            }
        }
    }
}

@Composable
private fun StubCard(s: Stub, tint: Color, lit: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val u = cqw()
    var hasFocus by remember { mutableStateOf(false) }
    // lit = the episode Resume plays, shown as focused while focus is elsewhere (the synopsis waits for real focus)
    val focused = hasFocus || lit
    val shape = RoundedCornerShape(u * 0.9f)
    Surface(
        onClick = onClick, shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier.width(if (focused) u * 23 else u * 19).alpha(if (focused) 1f else 0.8f).onFocusChanged { hasFocus = it.hasFocus }
    ) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(lerp(Color(0xFF15141B), tint, 0.38f), Color(0xFF101016)), start = Offset.Zero, end = Offset(300f, 900f)))
                .drawWithContent {
                    drawContent()
                    if (focused) { // inset amber edge
                        val w = u.toPx() * 0.24f
                        drawRoundRect(Accent.main, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(u.toPx() * 0.9f), style = Stroke(w))
                    }
                }
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black.copy(alpha = 0.3f))) {
                if (s.still != null) AsyncImage(
                    ImageRequest.Builder(LocalContext.current).data(s.still).size(CoilSize(480, 270)).crossfade(false).build(), null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                Text(
                    "E${s.n}", modifier = Modifier.align(Alignment.TopStart).padding(u * 0.8f).background(Bg.base.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = u * 0.8f, vertical = u * 0.15f),
                    style = TextStyle(fontFamily = DisplayHeavy, fontWeight = FontWeight.ExtraBold, fontSize = (u * 1.2f).sp(), color = Ink.high)
                )
                if (s.done) Box(Modifier.align(Alignment.TopEnd).padding(u * 0.8f).size(u * 2.2f).background(Color(0xFF6FD39A), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, Modifier.size(u * 1.5f), tint = Bg.base)
                }
            }
            Column(
                Modifier.fillMaxWidth().drawBehind { drawLine(Ink.high.copy(alpha = 0.3f), Offset.Zero, Offset(size.width, 0f), u.toPx() * 0.14f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))) }
                    .padding(start = u * 1.1f, end = u * 1.1f, top = u * 1f, bottom = u * 1.1f),
                verticalArrangement = Arrangement.spacedBy(u * 0.3f)
            ) {
                Text(s.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = (u * 1.3f).sp(), lineHeight = (u * 1.37f).sp(), color = Ink.high))
                val left = leftLabel(s.runtime, s.p)
                val line = when {
                    s.runtime != null -> minLabel(s.runtime) + (left?.let { ", $it" } ?: "")
                    s.p in 0.02f..<Eps.DONE -> "${(s.p * 100).toInt()}% watched"
                    else -> null
                }
                if (line != null) Text(line, maxLines = 1, style = TextStyle(fontFamily = com.anistream.core.ui.theme.TextFamily, fontSize = (u * 0.95f).sp(), color = Color(0xFFBDB9C9)))
                if (hasFocus && !s.overview.isNullOrBlank()) Text(s.overview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = com.anistream.core.ui.theme.TextFamily, fontSize = (u * 0.95f).sp(), lineHeight = (u * 1.33f).sp(), color = SegText))
                if (s.p in 0.02f..<Eps.DONE) Box(Modifier.padding(top = u * 0.3f).fillMaxWidth().height(u * 0.28f).background(Ink.high.copy(alpha = 0.16f), RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(s.p).background(Accent.main, RoundedCornerShape(50)))
                }
            }
        }
    }
}
