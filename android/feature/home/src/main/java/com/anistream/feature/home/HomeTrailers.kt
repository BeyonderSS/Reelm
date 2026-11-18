package com.anistream.feature.home

import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.anistream.core.trailer.LivingBackdrop
import com.anistream.core.trailer.PreviewMode
import com.anistream.core.trailer.TrailerInfo
import com.anistream.core.trailer.TrailerKey
import com.anistream.core.trailer.TrailerSurface
import com.anistream.core.trailer.rememberDwell
import com.anistream.core.ui.components.AnimeCard
import com.anistream.core.ui.components.ArtworkImage
import com.anistream.core.ui.components.MetaChips
import com.anistream.core.ui.components.TicketShape
import com.anistream.core.ui.components.pressClickable
import com.anistream.core.ui.components.resumeEpisode
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Display
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Motion
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import com.anistream.core.ui.theme.amberGlow
import com.anistream.core.ui.theme.duskShadow
import com.anistream.domain.model.AnimeSearchResult
import com.anistream.domain.model.WatchHistory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DWELL_MS = 1_500L
private const val HERO_INTERVAL_MS = 7_000L

/**
 * `/v1/trailer` key for a Home title: Movies & TV "tt…" ids go by IMDb id, other ids ("mb:…") by title/year/type;
 * anime by MAL id when known plus title/year, always as a series (so Cinemeta can add a logo).
 */
internal fun trailerKeyFor(id: String, title: String, year: Int?, type: String?, media: Boolean, mal: Long?): TrailerKey {
    if (!media) return TrailerKey(mal = mal, title = title, year = year, type = "series")
    // the type matters with an IMDb id too: the server reads Cinemeta's /meta/{type}/{tt}, and a series asked as a movie gets another title's trailer
    val kind = if (type.equals("Series", ignoreCase = true)) "series" else "movie"
    return if (id.startsWith("tt")) TrailerKey(imdb = id.substringBefore(':'), type = kind) else TrailerKey(title = title, year = year, type = kind)
}

/** System "remove animations" (animator scale 0): no spinning, tracing or scaling. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

@Composable
private fun rememberExtra(vm: HomeViewModel, item: HeroItem): State<TitleExtra?> =
    produceState<TitleExtra?>(null, item.card.slug) { value = vm.extra(item.card.slug, item.media) }

@Composable
private fun rememberLogo(vm: HomeViewModel, item: HeroItem): State<String?> =
    produceState<String?>(null, item.card.slug) { value = vm.logo(item.card, item.media) }

/** Title logo (Cinemeta), or the title in Bricolage when there is none or it fails to load. */
@Composable
private fun TitleLogo(logo: String?, title: String, height: Dp, maxWidth: Dp, center: Boolean, modifier: Modifier = Modifier) {
    var failed by remember(logo) { mutableStateOf(false) }
    if (logo != null && !failed) {
        AsyncImage(
            model = logo, contentDescription = title, contentScale = ContentScale.Fit,
            alignment = if (center) Alignment.BottomCenter else Alignment.BottomStart,
            onError = { failed = true },
            modifier = modifier.height(height).widthIn(max = maxWidth)
        )
    } else {
        Text(
            title, style = AppType.heading.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = if (center) TextAlign.Center else TextAlign.Start, modifier = modifier
        )
    }
}

/** "★ 8.4" then year and genre chips. */
@Composable
private fun MetaLine(item: HeroItem, extra: TitleExtra?, center: Boolean) {
    val card = item.card
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.xs, if (center) Alignment.CenterHorizontally else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        val score = card.score
        if (score != null && score > 0) Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Accent.main)) { append("★ ") }
                append(String.format(java.util.Locale.US, "%.1f", score))
            },
            style = AppType.label, modifier = Modifier.padding(end = Space.xs)
        )
        val year = (card.year ?: extra?.year)?.toString()
        val genres = extra?.genres.orEmpty().ifEmpty { listOfNotNull(if (item.media) card.type else "Anime") }
        MetaChips((listOf(year) + genres).filterNotNull().take(3))
    }
}

/** The mockup's compact pill (`.pbtn`): amber for the one primary action, quiet mist glass otherwise. */
@Composable
private fun CardButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, primary: Boolean = false) {
    val fg = if (primary) Accent.on else Ink.high
    Row(
        modifier
            .then(if (primary) Modifier.amberGlow() else Modifier)
            .clip(Radius.pill)
            .background(if (primary) Accent.main else Ink.glass)
            .then(if (primary) Modifier else Modifier.border(1.dp, Ink.hairline, Radius.pill))
            .pressClickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = Space.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.xs))
        }
        Text(text, style = AppType.label.copy(fontSize = 14.sp), color = fg, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SpeakerButton(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onToggle, modifier = modifier.size(40.dp).background(Bg.badge, Radius.round)) {
        Icon(
            if (on) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
            contentDescription = if (on) "Turn sound off" else "Turn sound on", tint = Ink.high, modifier = Modifier.size(20.dp)
        )
    }
}

/** true while more than half of the hero item is on screen. */
private fun heroVisible(s: LazyListState): Boolean {
    val info = s.layoutInfo
    val i = info.visibleItemsInfo.firstOrNull { it.key == "hero" } ?: return false
    val top = maxOf(i.offset, info.viewportStartOffset)
    val bottom = minOf(i.offset + i.size, info.viewportEndOffset)
    return (bottom - top) * 2 > i.size
}

/**
 * Living hero: a rounded card per featured title (art, logo, rating/year/genres, Watch + My list). After 1.5 s settled
 * on a page with previews allowed, the art crossfades into the muted trailer with an amber progress line and a speaker
 * button; it pauses when scrolled half away or the page changes, and the next title follows when it ends. No stream =
 * the living backdrop.
 */
@Composable
internal fun LivingHero(
    items: List<HeroItem>,
    myList: Set<String>,
    listState: LazyListState,
    paused: Boolean,
    vm: HomeViewModel,
    onPlay: (HeroItem) -> Unit,
    onToggleList: (HeroItem) -> Unit,
    onDetails: (HeroItem) -> Unit,
    /** Mirror mode: "Plays from the Netflix mirror" over the title */
    via: String? = null
) {
    val pagerState = rememberPagerState { items.size }
    val scope = rememberCoroutineScope()
    val mode by vm.trailers.mode.collectAsStateWithLifecycle()
    val allowed = remember(mode) { vm.trailers.allowedNow() }
    val page = pagerState.settledPage.coerceIn(0, items.lastIndex)
    val cur = items[page]
    val visible by remember { derivedStateOf { heroVisible(listState) } }
    val settled = !pagerState.isScrollInProgress

    val info by produceState<TrailerInfo?>(null, cur.card.slug, allowed) {
        value = null
        if (allowed) value = vm.trailer(cur.card, cur.media)
    }
    val dwell = key(cur.card.slug) { rememberDwell(allowed && settled && visible && !paused, DWELL_MS) }
    // settled on a movie/series long enough to start its trailer: Watch is the likely next tap
    LaunchedEffect(cur.card.slug, dwell) { if (dwell && cur.media) vm.prefetch(cur.card.slug) }
    var live by remember(cur.card.slug) { mutableStateOf(false) }
    var ended by remember(cur.card.slug) { mutableStateOf(false) }
    var sound by remember(cur.card.slug) { mutableStateOf(false) }
    var progress by remember(cur.card.slug) { mutableFloatStateOf(0f) }
    val stream = info?.streamUrl != null
    val playing = dwell && stream && !ended
    val showLive = live && playing

    // Auto-advance: the plain 7 s timer when there's no trailer; a trailer advances when it ends.
    LaunchedEffect(page, settled, items.size, stream, ended, allowed) {
        if (items.size < 2 || !settled) return@LaunchedEffect
        if (allowed && stream && !ended) return@LaunchedEffect
        delay(if (ended) 1_000 else HERO_INTERVAL_MS)
        // outside this effect: the scroll flips `settled`, which restarts the effect and would cancel it mid-way
        scope.launch { pagerState.animateScrollToPage((page + 1) % items.size, animationSpec = tween(600, easing = Motion.emphasized)) }
    }

    Column {
        Box(
            Modifier
                .padding(horizontal = Space.lg)
                .fillMaxWidth()
                .aspectRatio(4f / 5f)
                .duskShadow(24.dp, Radius.xl)
                .clip(Radius.xl)
                .background(Bg.raised)
        ) {
            Crossfade(
                targetState = items[pagerState.currentPage.coerceIn(0, items.lastIndex)],
                animationSpec = tween(Motion.PROJECTOR, easing = Motion.emphasized),
                label = "heroArt",
                modifier = Modifier.fillMaxSize()
            ) { item ->
                ArtworkImage(item.card.posterUrl, null, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            }
            val i = info
            if (i != null && i.streamUrl == null && dwell && i.backdrops.isNotEmpty()) {
                LivingBackdrop(listOfNotNull(cur.card.posterUrl) + i.backdrops, Modifier.fillMaxSize())
            }
            if (i != null && i.streamUrl != null) key(cur.card.slug) {
                TrailerSurface(
                    info = i, playing = playing, soundOn = sound, modifier = Modifier.fillMaxSize(),
                    onFirstFrame = { live = true }, onProgress = { progress = it }, onEnded = { ended = true; live = false }
                )
            }
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.7f).align(Alignment.BottomCenter).background(Bg.scrim))
            HorizontalPager(state = pagerState, key = { items[it].card.slug }, modifier = Modifier.fillMaxSize()) { p ->
                val item = items[p]
                HeroInfo(
                    item = item, vm = vm, inList = item.card.slug in myList, live = showLive && item == cur,
                    onPlay = { onPlay(item) }, onToggleList = { onToggleList(item) }, onDetails = { onDetails(item) }, via = via
                )
            }
            if (showLive) {
                SpeakerButton(sound, { sound = !sound }, Modifier.align(Alignment.TopEnd).padding(Space.md))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(2.5.dp).background(Accent.main))
            }
        }
        if (items.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = Space.md),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(items.size) { d ->
                    val selected = pagerState.currentPage == d
                    val w by animateDpAsState(if (selected) 18.dp else 6.dp, Motion.standard(), label = "dot")
                    Box(
                        Modifier
                            .height(6.dp)
                            .width(w)
                            .clip(Radius.pill)
                            .background(if (selected) Accent.main else Ink.faint)
                            .clickable(onClickLabel = "Show title ${d + 1}") { scope.launch { pagerState.animateScrollToPage(d) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroInfo(item: HeroItem, vm: HomeViewModel, inList: Boolean, live: Boolean, onPlay: () -> Unit, onToggleList: () -> Unit, onDetails: () -> Unit, via: String? = null) {
    val logo by rememberLogo(vm, item)
    val extra by rememberExtra(vm, item)
    val scale by animateFloatAsState(if (live) 0.85f else 1f, tween(500, easing = Motion.emphasized), label = "logoScale")
    Box(Modifier.fillMaxSize().clickable(onClickLabel = "Details", onClick = onDetails)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Space.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            if (via != null) Text(via, style = AppType.captionStrong.copy(color = Accent.text),
                modifier = Modifier.background(Bg.overlay, Radius.pill).padding(horizontal = Space.md, vertical = Space.xs))
            TitleLogo(
                logo, item.card.title, 64.dp, 210.dp, center = true,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; translationY = (1f - scale) * 40f }
            )
            MetaLine(item, extra, center = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                CardButton("Watch", onPlay, Modifier.weight(1f), Icons.Rounded.PlayArrow, primary = true)
                CardButton("My list", onToggleList, Modifier.weight(1f), if (inList) Icons.Rounded.Check else Icons.Rounded.Add)
            }
        }
    }
}

/**
 * A Home poster that, while held, traces an amber edge round its artwork (~380 ms, the long-press wait) and then
 * hands the title to "Hold to project".
 */
@Composable
internal fun HoldCarousel(items: List<AnimeSearchResult>, onClick: (AnimeSearchResult) -> Unit, onHold: (AnimeSearchResult) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        items(items.distinctBy { it.slug }, key = { it.slug }) { a -> HoldPoster(a, { onClick(a) }, { onHold(a) }) }
    }
}

@Composable
private fun HoldPoster(anime: AnimeSearchResult, onClick: () -> Unit, onHold: () -> Unit) {
    val reduced = rememberReducedMotion()
    val haptics = LocalHapticFeedback.current
    val slop = LocalViewConfiguration.current.touchSlop
    val trace = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(holding) {
        if (!holding) trace.snapTo(0f)
        else if (reduced) trace.snapTo(1f)
        else trace.animateTo(1f, tween(380, easing = LinearEasing))
    }
    Box(
        Modifier
            // watch the raw press without consuming it, so the card's own click / long-press still work
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    holding = true
                    while (true) {
                        val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed || (c.position - down.position).getDistance() > slop) break
                    }
                    holding = false
                }
            }
            .drawWithContent {
                drawContent()
                val t = trace.value
                if (t <= 0f) return@drawWithContent
                val w = size.width
                val r = 12.dp.toPx()
                val edge = Path().apply { addRoundRect(RoundRect(0f, 0f, w, w * 1.5f, CornerRadius(r))) }
                val m = PathMeasure().apply { setPath(edge, false) }
                val seg = Path()
                m.getSegment(0f, m.length * t, seg, true)
                drawPath(seg, Accent.main, style = Stroke(2.5.dp.toPx()))
            }
    ) {
        AnimeCard(anime, onClick = onClick, onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onHold()
        })
    }
}

/** Full-window dialog (covers the bottom bar like the mockup), no system dim: each overlay draws its own. */
@Composable
private fun FullDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        content()
    }
}

/**
 * Hold to project: blurred scrim and the lifted card (16:9 trailer, logo, meta, 3-line synopsis, Play / + My list /
 * Details). Not gated by Wi-Fi only (the user asked for it), but Off still means no video. Tap outside closes.
 */
@Composable
internal fun ProjectCard(
    item: HeroItem,
    vm: HomeViewModel,
    inList: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleList: () -> Unit,
    onDetails: () -> Unit
) = FullDialog(onDismiss) {
    val reduced = rememberReducedMotion()
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(340, easing = Motion.emphasized)) }
    val mode by vm.trailers.mode.collectAsStateWithLifecycle()
    val info by produceState<TrailerInfo?>(null, item.card.slug, mode) {
        if (mode != PreviewMode.OFF) value = vm.trailer(item.card, item.media)
    }
    val logo by rememberLogo(vm, item)
    val extra by rememberExtra(vm, item)
    var sound by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = (appear.value * 2f).coerceAtMost(1f) }
            .background(Bg.badge)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onDismiss)
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(Space.lg)
                .fillMaxWidth()
                .graphicsLayer {
                    val s = 0.6f + 0.4f * appear.value
                    scaleX = s; scaleY = s; alpha = 0.4f + 0.6f * appear.value
                }
                .amberGlow(Radius.xl, 24.dp)
                .clip(Radius.xl)
                .background(Bg.raised)
                .border(1.dp, Ink.hairline, Radius.xl)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Bg.shadow)) {
                ArtworkImage(info?.backdrops?.firstOrNull() ?: item.card.posterUrl, null, Modifier.fillMaxSize())
                info?.takeIf { it.streamUrl != null }?.let {
                    TrailerSurface(it, playing = mode != PreviewMode.OFF && !ended, soundOn = sound, modifier = Modifier.fillMaxSize(),
                        onFirstFrame = { live = true }, onEnded = { ended = true; live = false })
                }
                if (live) SpeakerButton(sound, { sound = !sound }, Modifier.align(Alignment.TopEnd).padding(Space.md))
            }
            Column(Modifier.padding(start = Space.lg, end = Space.lg, top = 14.dp, bottom = Space.lg), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TitleLogo(info?.logo ?: logo, item.card.title, 40.dp, 190.dp, center = false)
                MetaLine(item, extra, center = false)
                extra?.synopsis?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = AppType.caption.copy(color = Ink.body, lineHeight = 20.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    CardButton("Play", onPlay, Modifier.weight(1f), Icons.Rounded.PlayArrow, primary = true)
                    CardButton("My list", onToggleList, Modifier.weight(1f), if (inList) Icons.Rounded.Check else Icons.Rounded.Add)
                    CardButton("Details", onDetails, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Tonight's reel: spinning reel discs (amber sprocket ring; grey and still once seen). */
@Composable
internal fun ReelRow(items: List<HeroItem>, seen: Set<String>, onOpen: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(start = Space.lg, end = Space.lg, bottom = Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        itemsIndexed(items, key = { _, it -> it.card.slug }) { i, it -> ReelDisc(it, it.card.slug in seen) { onOpen(i) } }
    }
}

@Composable
private fun ReelDisc(item: HeroItem, seen: Boolean, onClick: () -> Unit) {
    val still = seen || rememberReducedMotion()
    val spin = if (still) null else rememberInfiniteTransition(label = "reel")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(10_000, easing = LinearEasing)), label = "spin")
    val ring = if (seen) Ink.faint else Accent.main
    Column(
        Modifier.width(64.dp).clip(Radius.md).clickable(onClickLabel = "Play trailers", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(62.dp)) {
                rotate(spin?.value ?: 0f) { repeat(16) { drawArc(ring, it * 22.5f, 13f, useCenter = true) } }
            }
            ArtworkImage(item.card.posterUrl, null, Modifier.size(56.dp).clip(Radius.round).border(2.5.dp, Bg.base, Radius.round))
        }
        Text(
            item.card.title, style = AppType.caption.copy(fontSize = 11.sp, lineHeight = 13.sp, color = Ink.body, fontWeight = FontWeight.Medium),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
    }
}

/** Tonight's reel, full screen: one trailer per page, swipe up for the next, auto-advance at the end. */
@Composable
internal fun ReelFeed(
    items: List<HeroItem>,
    start: Int,
    vm: HomeViewModel,
    myList: Set<String>,
    onSeen: (String) -> Unit,
    onDismiss: () -> Unit,
    onPlay: (HeroItem) -> Unit,
    onToggleList: (HeroItem) -> Unit
) = FullDialog(onDismiss) {
    val pager = rememberPagerState(start.coerceIn(0, items.lastIndex)) { items.size }
    val scope = rememberCoroutineScope()
    var sound by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val page = pager.settledPage
    LaunchedEffect(page) { progress = 0f; onSeen(items[page].card.slug) }
    fun next() {
        if (page < items.lastIndex) scope.launch { pager.animateScrollToPage(page + 1) } else onDismiss()
    }

    Box(Modifier.fillMaxSize().background(Bg.base)) {
        VerticalPager(state = pager, key = { items[it].card.slug }, modifier = Modifier.fillMaxSize()) { p ->
            ReelPage(
                item = items[p], next = items.getOrNull(p + 1), vm = vm, inList = items[p].card.slug in myList,
                playing = p == page && !pager.isScrollInProgress, sound = sound,
                onProgress = { if (p == page) progress = it }, onEnded = { if (p == page) next() },
                onPlay = { onPlay(items[p]) }, onToggleList = { onToggleList(items[p]) }
            )
        }
        Row(
            Modifier.statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = Space.sm).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.xs)
        ) {
            items.indices.forEach { i ->
                Box(Modifier.weight(1f).height(3.dp).clip(Radius.pill).background(Ink.ghost)) {
                    val f = when { i < page -> 1f; i == page -> progress; else -> 0f }
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Ink.high))
                }
            }
        }
        Row(
            Modifier.statusBarsPadding().padding(start = Space.md, end = Space.md, top = Space.lg + Space.xs).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp).background(Bg.badge, Radius.round)) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Ink.high, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            SpeakerButton(sound, { sound = !sound })
        }
    }
}

@Composable
private fun ReelPage(
    item: HeroItem,
    next: HeroItem?,
    vm: HomeViewModel,
    inList: Boolean,
    playing: Boolean,
    sound: Boolean,
    onProgress: (Float) -> Unit,
    onEnded: () -> Unit,
    onPlay: () -> Unit,
    onToggleList: () -> Unit
) {
    val info by produceState<TrailerInfo?>(null, item.card.slug) { value = vm.trailer(item.card, item.media) }
    val logo by rememberLogo(vm, item)
    val extra by rememberExtra(vm, item)
    val art = info?.backdrops?.firstOrNull() ?: item.card.posterUrl
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    Box(Modifier.fillMaxSize()) {
        ArtworkImage(art, null, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.1f; scaleY = 1.1f }.blur(28.dp))
        Box(Modifier.fillMaxSize().background(Bg.badge))
        Box(
            Modifier.align(Alignment.Center).offset(y = -screenH * 0.08f).fillMaxWidth().aspectRatio(16f / 9f).background(Bg.shadow)
        ) {
            ArtworkImage(art, null, Modifier.fillMaxSize())
            val i = info
            if (i != null && i.streamUrl == null) LivingBackdrop(listOfNotNull(art) + i.backdrops, Modifier.fillMaxSize())
            if (i?.streamUrl != null) TrailerSurface(i, playing, sound, Modifier.fillMaxSize(), onProgress = onProgress, onEnded = onEnded)
        }
        Text(
            "Swipe up for next", style = AppType.caption.copy(fontSize = 11.sp),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = Space.sm).verticalText()
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Bg.scrim)
                .navigationBarsPadding()
                .padding(start = Space.lg, end = Space.lg, top = Space.xxxl, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (next != null) Text("Next: ${next.card.title}", style = AppType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TitleLogo(info?.logo ?: logo, item.card.title, 52.dp, 220.dp, center = false)
            MetaLine(item, extra, center = false)
            extra?.synopsis?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = AppType.caption.copy(color = Ink.body, lineHeight = 20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                CardButton("Play", onPlay, Modifier.weight(1f), Icons.Rounded.PlayArrow, primary = true)
                CardButton("My list", onToggleList, Modifier.weight(1f), if (inList) Icons.Rounded.Check else Icons.Rounded.Add)
            }
        }
    }
}

/** Text running top to bottom (writing-mode: vertical-rl), laid out at its rotated size. */
private fun Modifier.verticalText() = layout { m, _ ->
    val p = m.measure(Constraints())
    layout(p.height, p.width) { p.placeWithLayer((p.height - p.width) / 2, (p.width - p.height) / 2) { rotationZ = 90f } }
}

/**
 * Continue watching on a tilted 35mm strip (like the TV's): ticket stubs with the poster stub cut off by the notched
 * perforation, title, where you are and an amber progress line. Long-press keeps "Remove from history".
 */
@Composable
internal fun TicketStrip(items: List<WatchHistory>, onRemove: (WatchHistory) -> Unit, onOpen: (WatchHistory) -> Unit) {
    val width = LocalConfiguration.current.screenWidthDp.dp + Space.lg
    Box(Modifier.fillMaxWidth().padding(top = Space.xs), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredWidth(width)
                .graphicsLayer { rotationZ = -1.4f }
                .background(Bg.shadow)
                .drawBehind {
                    val hole = Ink.ghost
                    val hw = 6.dp.toPx(); val hh = 5.dp.toPx(); val y = 4.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        drawRect(hole, Offset(x, y), Size(hw, hh))
                        drawRect(hole, Offset(x, size.height - y - hh), Size(hw, hh))
                        x += 14.dp.toPx()
                    }
                }
                .padding(vertical = 15.dp)
        ) {
            LazyRow(contentPadding = PaddingValues(horizontal = Space.xl), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.animeSlug }) { h -> Ticket(h, { onRemove(h) }) { onOpen(h) } }
            }
        }
    }
}

private val ticketShape = TicketShape(corner = 12.dp, notch = 5.dp, vertical = true, atDp = 58.dp)

@Composable
private fun Ticket(history: WatchHistory, onRemove: () -> Unit, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .width(210.dp)
                .height(80.dp)
                .clip(ticketShape)
                .background(Bg.raised)
                .pressClickable(onLongClick = { menu = true }, onClick = onClick)
        ) {
            ArtworkImage(history.posterUrl, null, Modifier.width(58.dp).fillMaxHeight())
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 11.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    history.animeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = AppType.bodyStrong.copy(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 16.sp)
                )
                val where = if (history.kind == "media") history.subtitle else "Episode ${history.resumeEpisode()}"
                if (!where.isNullOrBlank()) Text(where, style = AppType.numeric.copy(fontSize = 12.sp), maxLines = 1)
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(Radius.pill).background(Ink.ghost)) {
                    Box(Modifier.fillMaxWidth(history.watchProgress.coerceIn(0f, 1f)).fillMaxHeight().background(Accent.main))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Remove from history") }, onClick = { menu = false; onRemove() })
        }
    }
}
