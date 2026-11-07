package com.anistream.feature.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import com.anistream.core.ui.theme.Display
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.DisplayHeavy
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.domain.model.AnimeSearchResult
import com.anistream.domain.ContentPrefs
import com.anistream.feature.search.SearchViewModel

private val Violet = Color(0xFF5B3FA0)
private val KeyBg = Color(0xFF1A1B24)

private val animeTypes = listOf("TV", "Movie", "OVA", "ONA", "Special")
private val mediaTypes = listOf("Movie", "Series")
private val articles = listOf("the ", "a ", "an ")

/**
 * Completion for [query]: the first title, in result order, that starts with it (case-insensitive, leading punctuation and
 * article ignored). Not just the top result: "de" still completes to "Death Note" when a title like "Kimetsu: Demon Slayer"
 * ranks above it.
 */
internal fun autofillTitle(query: String, titles: List<String>): String? {
    val q = query.trimStart().lowercase()
    if (q.isEmpty()) return null
    return titles.firstNotNullOfOrNull { title ->
        val t = title.trimStart { !it.isLetterOrDigit() }
        val noArticle = articles.firstOrNull { t.startsWith(it, ignoreCase = true) }?.let { t.substring(it.length) }
        listOfNotNull(t, noArticle).firstOrNull { it.length > q.length && it.lowercase().startsWith(q) }
    }
}

/** One focusable thing: [base] at rest, amber when focused (no scale or glow, the Fire Stick 2 GPU can't afford it). */
@Composable
private fun STouch(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Radius.pill,
    base: Color = Color.White.copy(alpha = 0.07f),
    selected: Boolean = false,
    selectedSolid: Boolean = false,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) (if (selectedSolid) Accent.main else Color(0x38F2B45A)) else base,
            contentColor = if (selected) (if (selectedSolid) Accent.on else Accent.main) else Color(0xFFCFCBD9),
            focusedContainerColor = Accent.main,
            focusedContentColor = Accent.on,
            pressedContainerColor = Accent.pressed,
            pressedContentColor = Accent.on
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = if (selected && selectedSolid) Border(BorderStroke(2.dp, Ink.high)) else Border.None),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) { content() }
}

/** Text sized in design units (1 = 1% of the screen width). */
@Composable
private fun SText(text: String, size: Float, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified) {
    val u = LocalConfiguration.current.screenWidthDp / 100f
    Text(text, style = TvType.body.copy(fontSize = (size * u).sp, lineHeight = (size * u * 1.35f).sp, color = color, fontWeight = weight), modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Chip(text: String, selected: Boolean, u: Dp, onClick: () -> Unit) =
    STouch(onClick, selected = selected, selectedSolid = true) { SText(text, 1.05f, Modifier.padding(horizontal = u * 1.1f, vertical = u * 0.4f), if (selected) FontWeight.SemiBold else FontWeight.Normal) }

/** Keyboard key: dark cap with a bottom lip, amber under focus. */
@Composable
private fun Key(label: String?, onClick: () -> Unit, u: Dp, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) =
    STouch(onClick, modifier.drawBehind {
        drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, size.height - u.toPx() * 0.25f), androidx.compose.ui.geometry.Size(size.width, u.toPx() * 0.25f))
    }, RoundedCornerShape(u * 0.6f), KeyBg) {
        Row(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterVertically) {
            if (label != null) SText(label, if (label.length == 1) 1.35f else 1.1f, color = Color.Unspecified)
            if (icon != null) {
                if (label != null) Spacer(Modifier.width(u * 0.4f))
                Icon(icon, null, Modifier.size(u * 1.3f))
            }
        }
    }

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun TvSearchScreen(onOpen: (id: String) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val s by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    }
    // Fire TV and most Android TVs ship a recognizer; hide the mic where none resolves
    val canVoice = remember { voiceIntent.resolveActivity(context.packageManager) != null }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::onQueryChange)
    }
    val hidden by ContentPrefs.hidden.collectAsStateWithLifecycle()
    val showAnime = ContentPrefs.ANIME !in hidden
    val showMedia = ContentPrefs.MOVIES !in hidden || ContentPrefs.SERIES !in hidden
    // the scope on screen is never a hidden one
    LaunchedEffect(showAnime, showMedia, s.scope) { if (!showAnime && s.scope == 0) vm.onScopeChange(1) else if (!showMedia && s.scope == 1) vm.onScopeChange(0) }
    val list = (if (s.query.isBlank()) (if (s.scope == 1) s.mediaPopular else s.popular) else s.visibleResults).let { l ->
        // movie vs series is told apart by the search result's type
        if (s.scope != 1) l else l.filter { r -> if (r.type.equals("Series", true)) ContentPrefs.SERIES !in hidden else ContentPrefs.MOVIES !in hidden }
    }
    val suggestion = autofillTitle(s.query, s.visibleResults.map { it.title })
    val u = (LocalConfiguration.current.screenWidthDp / 100f).dp
    val strokePx = with(LocalDensity.current) { (u * 0.12f).toPx() }

    Box(
        Modifier.fillMaxSize().drawBehind {
            // one soft violet glow top-right (60 x 40 % of the width); a single static gradient, cheap on weak GPUs
            drawRect(Night)
            val c = Offset(size.width * 0.7f, size.height * 0.2f)
            scale(1f, 40f / 60f, pivot = c) {
                drawRect(Brush.radialGradient(0f to lerp(Night, Violet, 0.4f), 0.7f to Night, center = c, radius = size.width * 0.6f), Offset(0f, -size.height), androidx.compose.ui.geometry.Size(size.width, size.height * 3))
            }
        }
    ) {
        Row(Modifier.fillMaxSize().padding(start = u * 5, top = u * 3)) {
            Column(Modifier.width(u * 34), verticalArrangement = Arrangement.spacedBy(u * 1.3f)) {
                // the marquee query: typed letters solid, an amber caret, the predicted rest in outline
                val outline = SpanStyle(color = Color(0x73ECEAF2), drawStyle = Stroke(strokePx))
                val caret = "\u2009"
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Ink.high)) { append(s.query) }
                        appendInlineContent("caret", caret)
                        when {
                            suggestion != null -> withStyle(outline) { append(suggestion.substring(s.query.trimStart().length)) }
                            s.query.isEmpty() -> withStyle(outline) { append("Search") }
                        }
                    },
                    inlineContent = mapOf("caret" to InlineTextContent(Placeholder(0.09.em, 0.8.em, PlaceholderVerticalAlign.TextCenter)) {
                        Box(Modifier.fillMaxSize().padding(start = u * 0.1f).background(Accent.main))
                    }),
                    style = TextStyle(fontFamily = DisplayHeavy, fontWeight = FontWeight.ExtraBold, fontSize = (u.value * 6.8f).sp, lineHeight = (u.value * 6.8f * 0.86f).sp, letterSpacing = (-0.015f).em),
                    maxLines = 2, overflow = TextOverflow.Clip
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(u * 1f), verticalArrangement = Arrangement.spacedBy(u * 0.6f)) {
                    Row(Modifier.background(Color.White.copy(alpha = 0.07f), Radius.pill).padding(u * 0.3f), horizontalArrangement = Arrangement.spacedBy(u * 0.3f)) {
                        listOf("Anime", "Movies & TV").forEachIndexed { i, t ->
                            if ((i == 0 && !showAnime) || (i == 1 && !showMedia)) return@forEachIndexed
                            STouch({ vm.onScopeChange(i) }, selected = s.scope == i, base = Color.Transparent) {
                                SText(t, 1.15f, Modifier.padding(horizontal = u * 1.3f, vertical = u * 0.5f), if (s.scope == i) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                    if (canVoice) STouch({ try { voice.launch(voiceIntent) } catch (_: ActivityNotFoundException) { } }, base = Color.White.copy(alpha = 0.08f)) {
                        Row(Modifier.padding(horizontal = u * 1.3f, vertical = u * 0.6f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Mic, null, Modifier.size(u * 1.4f))
                            Spacer(Modifier.width(u * 0.6f))
                            SText("Voice", 1.15f, weight = FontWeight.SemiBold, color = Ink.high)
                        }
                    }
                }
                Column(Modifier.focusRestorer(), verticalArrangement = Arrangement.spacedBy(u * 0.5f)) {
                    keys.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(u * 0.5f)) {
                            row.forEach { k -> Key(k, { vm.onQueryChange(s.query + k) }, u, Modifier.weight(1f).aspectRatio(1.3f)) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(u * 0.5f)) {
                        Key("Space", { vm.onQueryChange(s.query + " ") }, u, Modifier.weight(2f).height(u * 2.6f))
                        Key(null, { vm.onQueryChange(s.query.dropLast(1)) }, u, Modifier.weight(1f).height(u * 2.6f), Icons.AutoMirrored.Outlined.Backspace)
                        Key("Clear", vm::onClearQuery, u, Modifier.weight(1f).height(u * 2.6f))
                        // Right on the last key column goes to the results, so accepting the outline lives on this key
                        Key("Complete", { suggestion?.let(vm::onQueryChange) }, u, Modifier.weight(2f).height(u * 2.6f), Icons.AutoMirrored.Outlined.ArrowForward)
                    }
                }
            }
            Spacer(Modifier.width(u * 5))
            // the giant query's line box sits high, so the left column starts 1 unit above the results
            Column(Modifier.weight(1f).padding(end = u * 4, top = u)) {
                val types = if (s.scope == 1) mediaTypes.filter { !(it == "Movie" && ContentPrefs.MOVIES in hidden) && !(it == "Series" && ContentPrefs.SERIES in hidden) } else animeTypes
                val label = TvType.title.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = (u.value * 1.4f).sp, lineHeight = (u.value * 1.8f).sp)
                Row(Modifier.fillMaxWidth().padding(bottom = u * 1.2f), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u)) {
                        Text(
                            when {
                                s.query.isBlank() -> if (s.recentSearches.isEmpty()) "Popular" else "Recent"
                                s.isLoading -> "Searching…"
                                s.error != null -> s.error!!
                                list.isEmpty() -> "Nothing matches \"${s.query.trim()}\""
                                else -> if (list.size == 1) "1 result" else "${list.size} results"
                            },
                            style = label, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (s.error != null) Chip("Retry", false, u) { vm.onQueryChange(s.query) }
                    }
                    // the chips keep their full width; the label gives way instead
                    if (s.query.isNotBlank()) Row(Modifier.focusRestorer().padding(start = u), horizontalArrangement = Arrangement.spacedBy(u * 0.5f)) {
                        Chip("All", s.typeFilter == null, u) { vm.onTypeFilterChange(null) }
                        types.forEach { t -> Chip(t, s.typeFilter.equals(t, true), u) { vm.onTypeFilterChange(t) } }
                    }
                }
                @Composable fun recents(withLabel: Boolean) = Column(verticalArrangement = Arrangement.spacedBy(u * 0.8f)) {
                    if (withLabel) Text("Recent", style = label)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(u * 0.6f), verticalArrangement = Arrangement.spacedBy(u * 0.6f)) {
                        s.recentSearches.forEach { r ->
                            STouch({ vm.onRecentSearchClick(r) }, Modifier.drawBehind {
                                drawLine(Color(0x4DECEAF2), Offset(0f, 0f), Offset(0f, size.height), u.toPx() * 0.32f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(u.toPx() * 0.4f, u.toPx() * 0.3f)))
                            }, RoundedCornerShape(u * 0.5f), Color(0xFF1D1B26)) {
                                Row(Modifier.padding(start = u * 0.9f, end = u * 1.1f, top = u * 0.45f, bottom = u * 0.45f)) {
                                    SText("↺ ", 1.05f, color = Color(0xFFA3A1AE)); SText(r, 1.05f, color = Color.Unspecified)
                                }
                            }
                        }
                        Chip("Clear recents", false, u, vm::onClearRecents)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.focusRestorer(),
                    contentPadding = PaddingValues(top = u * 0.5f, start = u * 0.5f, end = u * 0.5f, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(u * 1.2f),
                    horizontalArrangement = Arrangement.spacedBy(u * 1.2f)
                ) {
                    val hasRecents = s.recentSearches.isNotEmpty()
                    if (s.query.isBlank() && hasRecents) item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(u * 1.2f)) { recents(false); Text("Popular", style = label, modifier = Modifier.padding(top = u * 0.6f)) }
                    }
                    // while typing, recents sit under the first row of results (as in the mockup)
                    val head = if (s.query.isNotBlank() && hasRecents) list.take(5) else list
                    items(head, key = { it.slug }) { r ->
                        Poster(r, u, onClick = { onOpen(r.slug) }, onFocused = { if (r == list.lastOrNull() && s.query.isNotBlank()) vm.loadNextPage() })
                    }
                    if (head.size < list.size || (s.query.isNotBlank() && hasRecents && list.isNotEmpty())) {
                        if (s.query.isNotBlank() && hasRecents) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(top = u * 0.6f)) { recents(true) } }
                        items(list.drop(head.size), key = { it.slug }) { r ->
                            Poster(r, u, onClick = { onOpen(r.slug) }, onFocused = { if (r == list.lastOrNull()) vm.loadNextPage() })
                        }
                    }
                    if (s.query.isNotBlank() && list.isEmpty() && !s.isLoading && s.error == null) item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(u * 1.2f)) {
                            SText(if (s.typeFilter != null) "Try All types, or fewer letters." else if (showAnime && showMedia) "Try fewer letters, or switch to ${if (s.scope == 1) "Anime" else "Movies & TV"}." else "Try fewer letters.", 1.15f, color = Color(0xFFA3A1AE))
                            if (hasRecents) recents(true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Poster(r: AnimeSearchResult, u: Dp, onClick: () -> Unit, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(u * 0.5f)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(u * 0.7f)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Bg.raised, focusedContainerColor = Bg.raised),
            // amber ring a hair outside the poster
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(u * 0.22f, Accent.main), inset = u * 0.3f, shape = RoundedCornerShape(u * 0.9f))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            if (r.posterUrl != null) AsyncImage(r.posterUrl, r.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column {
            SText(r.title, 1.05f, weight = FontWeight.SemiBold, color = Ink.high)
            listOfNotNull(r.type, r.year?.toString()).joinToString(", ").takeIf { it.isNotEmpty() }?.let { SText(it, 0.9f, color = Color(0xFFA3A1AE)) }
        }
    }
}
