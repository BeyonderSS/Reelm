package com.anistream.feature.tv

import com.anistream.core.common.LowEnd
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Display
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Motion
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.TextFamily

/** 48dp (5% of a 960dp screen) overscan-safe horizontal margin, as in the TV mockups. */
val Overscan = 48.dp

/** TV scale for 3 m viewing: 48 / 32 / 22 / 18. */
object TvType {
    val display = TextStyle(fontFamily = Display, fontSize = 48.sp, lineHeight = 54.sp, fontWeight = FontWeight.Bold, color = Ink.high)
    val headline = TextStyle(fontFamily = Display, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, color = Ink.high)
    val title = TextStyle(fontFamily = TextFamily, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = Ink.high)
    val body = TextStyle(fontFamily = TextFamily, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal, color = Ink.body)
}

/** Animator duration scale 0 = the user turned animations off: no scaling, no crossfades, amber light only. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** [LowEnd] mode, read once per activity start. */
val LocalLowEnd = staticCompositionLocalOf { false }

private val FocusGlow = Glow(elevationColor = Accent.main.copy(alpha = 0.3f), elevation = 18.dp)

/** Focus in every mode: a 3dp amber ring plus a slight brightness lift. Selected (current tab, watched) is soft amber instead. */
internal val FocusRing = BorderStroke(3.dp, Accent.main)
internal val FocusLift = Color.White.copy(alpha = 0.12f)

/** Low-end: no glow (a blurred shadow per focus change is too much for the Fire TV Stick 2 GPU). */
@Composable
internal fun focusGlow() = if (LocalLowEnd.current) Glow.None else FocusGlow

@Composable
fun ReelmTvTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lowEnd = remember { LowEnd.isOn(context) }
    // low-end mode reuses the reduced-motion path: no backdrop crossfade, no card scaling
    val reduce = remember {
        lowEnd || Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent.main,
            onPrimary = Accent.on,
            background = Bg.base,
            onBackground = Ink.high,
            surface = Bg.raised,
            onSurface = Ink.high,
            surfaceVariant = Bg.overlay,
            onSurfaceVariant = Ink.body,
            border = Ink.ghost,
            error = Accent.danger
        )
    ) {
        CompositionLocalProvider(LocalReduceMotion provides reduce, LocalLowEnd provides lowEnd) {
            Box(Modifier.fillMaxSize().background(Bg.base)) { content() }
        }
    }
}

@Immutable
/** One focusable thing on screen. [key] is unique per screen (row title + id) so focus can be restored to it. */
data class TvItem(
    val key: String,
    val title: String,
    val imageUrl: String?,
    val meta: List<String> = emptyList(),
    val progress: Float? = null,
    /** whole minutes left of [progress]; null = length unknown (the stub shows percent) */
    val minutesLeft: Int? = null,
    val subtitle: String? = null,
    /** Home hero: "Resume episode N" for history rows, null elsewhere; [onDetails] opens the detail page. */
    val resumeLabel: String? = null,
    val onDetails: (() -> Unit)? = null,
    val episodeLine: String? = null,
    val onLongClick: (() -> Unit)? = null,
    /** what to look the trailer up by (Home previews); null = no preview */
    val trailer: com.anistream.core.trailer.TrailerKey? = null,
    val onClick: () -> Unit
)

@Immutable
/** [caption]: small note under the row (e.g. a data-source attribution). */
data class TvRow(val title: String, val items: List<TvItem>, val wide: Boolean = false, val caption: String? = null)

/**
 * The projector: full-screen art of the focused title, crossfading 400 ms, under a dusk gradient
 * (left for the text column, bottom for the rows).
 */
@Composable
fun ProjectorBackdrop(url: String?, modifier: Modifier = Modifier, portrait: Boolean = false) {
    val lowEnd = LocalLowEnd.current
    // low-end: a quick 150 ms alpha fade (cheap; the old snap felt like a flicker)
    val spec = when {
        lowEnd -> tween(150)
        LocalReduceMotion.current -> snap<Float>()
        else -> tween(Motion.PROJECTOR, easing = Motion.emphasized)
    }
    Box(modifier.fillMaxSize()) {
        Crossfade(targetState = url, animationSpec = spec, label = "projector") { u ->
            if (u != null) {
                if (portrait) {
                    // a poster is not a backdrop: never crop it full-bleed. Whole poster, right-aligned, over a darkened
                    // scaled copy (skipped on low-end; no blur anywhere, it is too costly on API 22).
                    Box(Modifier.fillMaxSize()) {
                        if (!lowEnd) AsyncImage(model = u, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.22f, modifier = Modifier.fillMaxSize())
                        AsyncImage(
                            model = u, contentDescription = null, contentScale = ContentScale.Fit, alpha = 0.9f,
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = Overscan).height(230.dp).aspectRatio(2f / 3f).clip(Radius.md)
                        )
                    }
                } else AsyncImage(model = u, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.6f, modifier = Modifier.fillMaxSize())
            }
        }
        if (lowEnd) {
            // one full-screen layer instead of two stacked gradients
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Bg.base.copy(alpha = 0.45f), 0.45f to Bg.base.copy(alpha = 0.7f), 1f to Bg.base)))
        } else {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg.base, 0.55f to Bg.base.copy(alpha = 0.55f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Bg.base.copy(alpha = 0.4f), 1f to Bg.base)))
        }
    }
}

/** Small rounded meta chips instead of middle-dot strings; wraps (up to [maxLines]) instead of overflowing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetaChips(meta: List<String>, modifier: Modifier = Modifier, maxLines: Int = 2) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxLines = maxLines) {
        meta.filter { it.isNotBlank() }.forEach {
            Text(
                it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TvType.body.copy(color = Ink.high),
                modifier = Modifier.background(Bg.overlay.copy(alpha = 0.85f), Radius.pill).padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
    }
}

/** Poster (2:3) or continue-watching (16:9) card: amber ring + brightness lift on focus; scale 1.08 and glow unless reduced/low-end. */
@Composable
fun TvCard(item: TvItem, wide: Boolean, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = item.onClick,
        onLongClick = item.onLongClick,
        modifier = modifier
            .width(if (wide) 248.dp else 132.dp)
            .aspectRatio(if (wide) 16f / 9f else 2f / 3f)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(Radius.md),
        colors = CardDefaults.colors(containerColor = Bg.raised),
        scale = CardDefaults.scale(focusedScale = if (reduce) 1f else 1.08f),
        glow = CardDefaults.glow(focusedGlow = focusGlow()),
        border = CardDefaults.border(focusedBorder = Border(FocusRing, shape = Radius.md))
    ) {
        Box(Modifier.fillMaxSize().dwellRing(item.key, LocalTvPreview.current, 26.dp, 8.dp)) {
            if (item.imageUrl != null) {
                AsyncImage(model = item.imageUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (wide || item.imageUrl == null) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Bg.base.copy(alpha = 0.92f))))
                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Text(item.title, style = TvType.body.copy(color = Ink.high, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.subtitle?.let { Text(it, style = TvType.body.copy(color = Ink.muted), maxLines = 1) }
                }
            }
            if (focused) Box(Modifier.fillMaxSize().background(FocusLift))
            item.progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Ink.faint)) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(4.dp).background(Accent.main))
                }
            }
        }
    }
}

/**
 * Titled horizontal row. focusRestorer() brings focus back to the last focused card when the row is re-entered;
 * [restoreKey]/[restoreRequester] put focus back on the right card after returning from another screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvCardRow(
    row: TvRow,
    restoreKey: String?,
    restoreRequester: FocusRequester,
    onFocused: (TvItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(row.title, style = TvType.title, modifier = Modifier.padding(start = Overscan, bottom = 10.dp))
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = Overscan, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            itemsIndexed(row.items, key = { _, it -> it.key }) { _, item ->
                TvCard(
                    item = item,
                    wide = row.wide,
                    onFocused = { onFocused(item) },
                    modifier = if (item.key == restoreKey) Modifier.focusRequester(restoreRequester) else Modifier
                )
            }
        }
        val caption = row.caption
        if (caption != null) Text(caption, style = TvType.body.copy(color = Ink.muted), modifier = Modifier.padding(start = Overscan))
    }
}

/** Every TV button/chip: pill, amber fill on focus with the projector glow; [selected] = soft amber at rest. */
@Composable
fun TvPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    large: Boolean = false
) {
    val reduce = LocalReduceMotion.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(percent = 50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Accent.soft else Bg.overlay.copy(alpha = 0.9f),
            contentColor = if (selected) Accent.main else Ink.high,
            focusedContainerColor = Accent.main,
            focusedContentColor = Accent.on,
            pressedContainerColor = Accent.pressed,
            pressedContentColor = Accent.on,
            disabledContainerColor = Bg.overlay.copy(alpha = 0.5f),
            disabledContentColor = Ink.faint
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (reduce) 1f else 1.08f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow())
    ) {
        Row(
            Modifier.padding(horizontal = if (large) 28.dp else 20.dp, vertical = if (large) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                if (text.isNotEmpty()) Spacer(Modifier.width(10.dp))
            }
            if (text.isNotEmpty()) {
                Text(text, style = (if (large) TvType.title else TvType.body).copy(color = Color.Unspecified, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

/** Centered status text (loading / empty / error) with an optional action. */
@Composable
fun TvMessage(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, style = TvType.title.copy(color = Ink.body))
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

fun scoreLabel(score: Double?): String? = score?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }

/** Modal yes/no for destructive actions (remove one item, clear all); focus starts on Cancel. */
@Composable
fun TvConfirm(message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Column(Modifier.background(Bg.overlay, Radius.md).padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(message, style = TvType.body.copy(color = Ink.high))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvPill("Cancel", onDismiss, Modifier.focusRequester(first))
                TvPill(confirmLabel, onConfirm)
            }
        }
    }
}
