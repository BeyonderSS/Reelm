package com.anistream.feature.home

import com.anistream.domain.model.heroItems
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anistream.core.ui.components.AnimeCarousel
import com.anistream.core.ui.components.ArtworkImage
import com.anistream.core.ui.components.EmptyState
import com.anistream.core.ui.components.ErrorContent
import com.anistream.core.ui.components.LoadingShimmer
import com.anistream.core.ui.components.MetaChips
import com.anistream.core.ui.components.PosterRowSkeleton
import com.anistream.core.ui.components.PrimaryButton
import com.anistream.core.ui.components.SecondaryButton
import com.anistream.core.ui.components.SectionHeader
import com.anistream.core.ui.components.TextAction
import com.anistream.core.ui.components.pressClickable
import com.anistream.core.ui.components.resumeEpisode
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Motion
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import com.anistream.domain.model.AnimeSearchResult
import com.anistream.domain.model.Channel
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.WatchHistory
import com.anistream.domain.model.forYouRows

private const val HERO_COUNT = 8
private const val HERO_INTERVAL_MS = 7_000
private val TOP_BAR_HEIGHT = 56.dp

private enum class HomeMode { Loading, Error, Empty, Content }

/** A featured title in the hero; [media] = Movies & TV (opens the movie detail), otherwise anime. */
@Immutable
private data class HeroItem(val card: AnimeSearchResult, val media: Boolean)

/** One poster row. [path] = hianime "See all" category, [catalogKey] = Movies & TV "See all". */
@Immutable
private data class HomeRow(
    val key: String,
    val title: String,
    val items: List<AnimeSearchResult>,
    val media: Boolean,
    val path: String? = null,
    val catalogKey: String? = null
)

/**
 * The phone Home: a cinematic hero of anime and movies, then Continue Watching, For you,
 * and rows for anime, movies, series and live TV.
 *
 * @param onSeeAllClick receives a hianime category path ("top-airing", "recently-updated", ...)
 * @param onMediaClick opens a Movies & TV id ("tt…" / "mb:…")
 * @param onPlayLive called after the channel has been handed to the media player
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAnimeClick: (String) -> Unit,
    onSeeAllClick: (String) -> Unit,
    onPlayEpisode: (String, Int) -> Unit,
    onMediaClick: (String) -> Unit = {},
    onMediaSeeAll: (key: String, title: String) -> Unit = { _, _ -> },
    onLiveTvClick: () -> Unit = {},
    onPlayLive: () -> Unit = {},
    onGenresClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onScheduleClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refreshContinueWatching()
        onPauseOrDispose { }
    }

    val c = uiState.content
    val mediaRows = uiState.mediaRows
    // Hero: trending anime and the first Movies & TV row, interleaved.
    val hero = remember(c.trending, mediaRows) {
        val anime = c.trending.distinctBy { it.slug }.take(HERO_COUNT / 2).map { HeroItem(it, media = false) }
        val media = mediaRows.heroItems(HERO_COUNT / 2).map { HeroItem(it.asCard(), media = true) }
        (0 until maxOf(anime.size, media.size)).flatMap { i -> listOfNotNull(anime.getOrNull(i), media.getOrNull(i)) }
    }
    val rows = remember(c, mediaRows, hero) { buildRows(c, mediaRows.map { r -> HomeRow("m-${r.title}", r.title, r.items.map { it.asCard() }, media = true, catalogKey = r.catalogKey) }, hero) }
    val animePicks = remember(uiState.picks) {
        uiState.picks.forYouRows().map { (title, picks) ->
            title to picks.map { AnimeSearchResult(slug = it.id, title = it.title, posterUrl = it.posterUrl, score = it.rating, year = it.year) }
        }
    }
    val mediaPicks = remember(uiState.mediaPicks) {
        uiState.mediaPicks.forYouRows().map { (title, picks) ->
            title to picks.map {
                AnimeSearchResult(slug = it.id, title = it.title, posterUrl = it.posterUrl, score = it.rating, year = it.year,
                    type = if (it.mediaType == MediaType.SERIES) "Series" else "Movie")
            }
        }
    }

    val hasContent = hero.isNotEmpty() || rows.isNotEmpty() || uiState.continueWatching.isNotEmpty()
    val mode = when {
        hasContent -> HomeMode.Content
        uiState.isLoading -> HomeMode.Loading
        uiState.error != null || uiState.mediaError != null -> HomeMode.Error
        else -> HomeMode.Empty
    }

    val listState = rememberLazyListState()
    val refreshState = rememberPullToRefreshState()
    val refreshing = uiState.isLoading && hasContent
    val openHero: (HeroItem) -> Unit = { if (it.media) onMediaClick(it.card.slug) else onAnimeClick(it.card.slug) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg.base)
    ) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.loadContent() },
            state = refreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = refreshState,
                    isRefreshing = refreshing,
                    containerColor = Bg.overlay,
                    color = Accent.main,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = TOP_BAR_HEIGHT)
                )
            }
        ) {
            Crossfade(targetState = mode, animationSpec = Motion.standard(), label = "homeMode") { m ->
                when (m) {
                    HomeMode.Loading -> HomeSkeleton()
                    HomeMode.Error -> ErrorContent(
                        message = "Home didn't load. Check your connection, then try again.",
                        onRetry = { viewModel.loadContent() }
                    )
                    HomeMode.Empty -> EmptyState(
                        message = "Nothing to show yet",
                        subtitle = "Pull down or refresh to check again.",
                        icon = Icons.Rounded.LiveTv,
                        actionLabel = "Refresh",
                        onAction = { viewModel.loadContent() }
                    )
                    HomeMode.Content -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = Space.xxxl)
                    ) {
                        if (hero.isNotEmpty()) {
                            item(key = "hero", contentType = "hero") {
                                Hero(
                                    items = hero,
                                    myList = uiState.myList,
                                    listState = listState,
                                    onPlay = { h -> if (h.media) onMediaClick(h.card.slug) else onPlayEpisode(h.card.slug, 1) },
                                    onToggleList = { viewModel.toggleMyList(it.card) },
                                    onDetails = openHero
                                )
                            }
                        } else {
                            item(key = "topSpace") {
                                Column {
                                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                                    Spacer(Modifier.height(TOP_BAR_HEIGHT))
                                }
                            }
                        }

                        if (uiState.continueWatching.isNotEmpty()) {
                            item(key = "cw", contentType = "cw") {
                                Section("Continue watching") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = Space.lg),
                                        horizontalArrangement = Arrangement.spacedBy(Space.md)
                                    ) {
                                        items(uiState.continueWatching, key = { it.animeSlug }) { h ->
                                            ResumeCard(h, onRemove = { viewModel.removeHistory(h.animeSlug) }) {
                                                if (h.kind == "media") onMediaClick(h.detailId)
                                                else onPlayEpisode(h.animeSlug, h.resumeEpisode())
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val failed = listOfNotNull(
                            "Anime".takeIf { uiState.error != null && !uiState.animeLoading },
                            "Movies & TV".takeIf { uiState.mediaError != null && !uiState.mediaLoading }
                        )
                        if (failed.isNotEmpty()) {
                            item(key = "notice") {
                                PartialNotice("${failed.joinToString(" and ")} didn't load.", onRetry = { viewModel.loadContent() })
                            }
                        }

                        animePicks.forEach { (title, items) ->
                            item(key = "fy-a-$title", contentType = "row") {
                                Section(title) { AnimeCarousel(items = items, onAnimeClick = { onAnimeClick(it.slug) }) }
                            }
                        }
                        mediaPicks.forEachIndexed { i, (title, items) ->
                            item(key = "fy-m-$title", contentType = "row") {
                                Section(if (animePicks.any { it.first == title }) "$title in Movies & TV" else title) {
                                    AnimeCarousel(items = items, onAnimeClick = { onMediaClick(it.slug) })
                                    // TMDB's terms ask for attribution wherever their data shows
                                    if (i == mediaPicks.lastIndex) {
                                        Text("Data from TMDB", style = AppType.caption, modifier = Modifier.padding(start = Space.lg, top = Space.sm))
                                    }
                                }
                            }
                        }

                        rows.forEachIndexed { i, row ->
                            // live TV sits a few rows down, after the first anime and movie rows
                            if (i == LIVE_ROW_AT && uiState.channels.isNotEmpty()) liveRow(uiState.channels, onLiveTvClick) { ch ->
                                viewModel.playChannel(ch)
                                onPlayLive()
                            }
                            item(key = row.key, contentType = "row") {
                                val seeAll: (() -> Unit)? = when {
                                    row.path != null -> { { onSeeAllClick(row.path) } }
                                    row.catalogKey != null -> { { onMediaSeeAll(row.catalogKey, row.title) } }
                                    else -> null
                                }
                                Section(row.title, seeAll) {
                                    AnimeCarousel(items = row.items, onAnimeClick = { if (row.media) onMediaClick(it.slug) else onAnimeClick(it.slug) })
                                }
                            }
                        }
                        if (rows.size <= LIVE_ROW_AT && uiState.channels.isNotEmpty()) liveRow(uiState.channels, onLiveTvClick) { ch ->
                            viewModel.playChannel(ch)
                            onPlayLive()
                        }

                        val pool = rows.flatMap { r -> r.items.map { it to r.media } }
                        if (pool.isNotEmpty()) {
                            item(key = "end") {
                                EndOfFeed(onSurprise = {
                                    val (pick, media) = pool.random()
                                    if (media) onMediaClick(pick.slug) else onAnimeClick(pick.slug)
                                })
                            }
                        }
                    }
                }
            }
        }

        HomeTopBar(
            listState = listState,
            solid = mode != HomeMode.Content || hero.isEmpty(),
            onSearchClick = onSearchClick,
            onGenresClick = onGenresClick,
            onScheduleClick = onScheduleClick,
            onMenuClick = onMenuClick
        )
    }
}

private const val LIVE_ROW_AT = 4

/** Anime rows and Movies & TV rows alternate so the feed doesn't read as two stacked apps. */
private fun buildRows(c: com.anistream.domain.repository.HomeContent, media: List<HomeRow>, hero: List<HeroItem>): List<HomeRow> {
    val inHero = hero.filterNot { it.media }.map { it.card.slug }.toSet()
    val anime = listOf(
        HomeRow("a-trending", "Trending anime", c.trending.filterNot { it.slug in inHero }, media = false),
        HomeRow("a-latest", "New episodes", c.recentEpisodes, media = false, path = "recently-updated"),
        HomeRow("a-airing", "Top airing", c.topAiring, media = false, path = "top-airing"),
        HomeRow("a-popular", "Most popular anime", c.popular, media = false, path = "most-popular"),
        HomeRow("a-fav", "Most favourited", c.favorites, media = false, path = "most-favorite"),
        HomeRow("a-done", "Recently completed", c.completed, media = false, path = "latest-completed")
    ).filter { it.items.isNotEmpty() }
    // first two anime rows lead, then one movie row per anime row
    val head = anime.take(2)
    val tail = anime.drop(2)
    val mixed = (0 until maxOf(media.size, tail.size)).flatMap { i -> listOfNotNull(media.getOrNull(i), tail.getOrNull(i)) }
    return (head + mixed).distinctBy { it.key }
}

/** Header + content with the standard gap above; keeps every row's rhythm identical. */
@Composable
private fun Section(title: String, onSeeAll: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = Space.xl)) {
        SectionHeader(title = title, onSeeAll = onSeeAll)
        content()
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.liveRow(
    channels: List<Channel>,
    onSeeAll: () -> Unit,
    onPlay: (Channel) -> Unit
) {
    item(key = "live", contentType = "live") {
        Section("Live TV", onSeeAll) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.md)
            ) {
                items(channels, key = { it.url }) { ch -> ChannelTile(ch) { onPlay(ch) } }
            }
        }
    }
}

/** Transparent over the hero; the slate fill fades in with scroll. Read in the draw phase only, so scrolling never recomposes it. */
@Composable
private fun HomeTopBar(
    listState: LazyListState,
    solid: Boolean,
    onSearchClick: () -> Unit,
    onGenresClick: () -> Unit,
    onScheduleClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    val fadePx = with(LocalDensity.current) { 240.dp.toPx() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val a = when {
                    solid || listState.firstVisibleItemIndex > 0 -> 1f
                    else -> (listState.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
                }
                drawRect(Bg.scrimTop, alpha = 1f - a)
                drawRect(Bg.bar, alpha = a)
                drawLine(Ink.hairline, Offset(0f, size.height), Offset(size.width, size.height), alpha = a)
            }
            .statusBarsPadding()
            .height(TOP_BAR_HEIGHT)
            .padding(start = Space.lg, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Reelm",
            style = AppType.heading.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Outlined.Search, contentDescription = "Search", tint = Ink.high)
        }
        IconButton(onClick = onScheduleClick) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = "Schedule", tint = Ink.high)
        }
        IconButton(onClick = onGenresClick) {
            Icon(Icons.Outlined.Category, contentDescription = "Browse genres", tint = Ink.high)
        }
        IconButton(onClick = onMenuClick) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More", tint = Ink.high)
        }
    }
}

/**
 * "The projector": the backdrop crossfades to the current title and drifts slower than the page (parallax),
 * while title and actions slide with the pager. The active dash fills amber until the next title.
 */
@Composable
private fun Hero(
    items: List<HeroItem>,
    myList: Set<String>,
    listState: LazyListState,
    onPlay: (HeroItem) -> Unit,
    onToggleList: (HeroItem) -> Unit,
    onDetails: (HeroItem) -> Unit
) {
    val pagerState = rememberPagerState { items.size }
    val timer = remember { Animatable(0f) }

    // Auto-advance; restarts whenever the user swipes or a page settles.
    LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress, items.size) {
        timer.snapTo(0f)
        if (items.size < 2 || pagerState.isScrollInProgress) return@LaunchedEffect
        timer.animateTo(1f, tween(HERO_INTERVAL_MS, easing = LinearEasing))
        pagerState.animateScrollToPage(
            (pagerState.settledPage + 1) % items.size,
            animationSpec = tween(600, easing = Motion.emphasized)
        )
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 5f)
            .clipToBounds()
    ) {
        Crossfade(
            targetState = items[pagerState.currentPage.coerceIn(0, items.lastIndex)],
            animationSpec = tween(Motion.PROJECTOR, easing = Motion.emphasized),
            label = "heroBackdrop",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (listState.firstVisibleItemIndex == 0) translationY = listState.firstVisibleItemScrollOffset * 0.45f
                }
        ) { item ->
            ArtworkImage(
                url = item.card.posterUrl,
                contentDescription = null,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Scrims stay put while pages slide, so the text always sits on the same dusk.
        Box(Modifier.fillMaxWidth().height(140.dp).background(Bg.scrimTop))
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .align(Alignment.BottomCenter)
                .background(Bg.scrim)
        )
        HorizontalPager(
            state = pagerState,
            key = { items[it].card.slug },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            HeroPage(
                item = item,
                inList = item.card.slug in myList,
                onPlay = { onPlay(item) },
                onToggleList = { onToggleList(item) },
                onDetails = { onDetails(item) }
            )
        }
        if (items.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = Space.lg, bottom = Space.sm),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(items.size) { i ->
                    val selected = pagerState.currentPage == i
                    val w by animateDpAsState(if (selected) 24.dp else 6.dp, Motion.standard(), label = "dash")
                    Box(
                        Modifier
                            .height(4.dp)
                            .width(w)
                            .clip(Radius.pill)
                            .background(Ink.faint)
                            .drawBehind {
                                if (selected) drawRect(Accent.main, size = size.copy(width = size.width * timer.value))
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPage(item: HeroItem, inList: Boolean, onPlay: () -> Unit, onToggleList: () -> Unit, onDetails: () -> Unit) {
    val anime = item.card
    Box(
        Modifier
            .fillMaxSize()
            .clickable(onClick = onDetails)
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = Space.lg, end = Space.lg, bottom = Space.xl + Space.sm)
        ) {
            val meta = if (item.media) listOf(anime.type, anime.year?.toString())
            else listOf("Anime", anime.type, anime.year?.toString())
            MetaChips(meta)
            Spacer(Modifier.height(Space.sm))
            Text(anime.title, style = AppType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(text = "Play", onClick = onPlay, icon = Icons.Rounded.PlayArrow)
                SecondaryButton(
                    text = if (inList) "In My List" else "My List",
                    onClick = onToggleList,
                    icon = if (inList) Icons.Rounded.Check else Icons.Rounded.Add
                )
                IconButton(
                    onClick = onDetails,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Bg.badge, Radius.round)
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = "Details", tint = Ink.high)
                }
            }
        }
    }
}

/** 16:9 resume card for anime and Movies & TV: artwork, play glyph, amber progress edge, title + where you are. */
@Composable
private fun ResumeCard(history: WatchHistory, onRemove: () -> Unit, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(240.dp)
            .clip(Radius.md)
            .pressClickable(onLongClick = { menu = true }, onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(Radius.md)
                .border(1.dp, Ink.hairline, Radius.md)
        ) {
            ArtworkImage(history.posterUrl, history.animeTitle, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.6f).align(Alignment.BottomCenter).background(Bg.scrim))
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .background(Bg.badge, Radius.round),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume", tint = Ink.high, modifier = Modifier.size(26.dp))
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Ink.ghost)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(history.watchProgress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Accent.main)
                )
            }
        }
        Spacer(Modifier.height(Space.sm))
        Text(history.animeTitle, style = AppType.captionStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val where = if (history.kind == "media") history.subtitle else "Episode ${history.resumeEpisode()}"
        if (!where.isNullOrBlank()) Text(where, style = AppType.numeric, maxLines = 1)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Remove from history") }, onClick = { menu = false; onRemove() })
        }
    }
}

/** Channel logo on slate with the signal-green live dot; tap tunes in. */
@Composable
private fun ChannelTile(channel: Channel, onClick: () -> Unit) {
    Column(
        Modifier
            .width(136.dp)
            .clip(Radius.md)
            .pressClickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(Radius.md)
                .background(Bg.raised)
                .border(1.dp, Ink.hairline, Radius.md)
        ) {
            ArtworkImage(
                url = channel.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Space.lg)
            )
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(Space.sm)
                    .size(8.dp)
                    .background(Accent.live, Radius.round)
            )
        }
        Spacer(Modifier.height(Space.sm))
        Text(channel.name, style = AppType.captionStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One part of Home failed while the rest loaded: say which, offer a retry, keep the feed. */
@Composable
private fun PartialNotice(text: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .padding(start = Space.lg, end = Space.lg, top = Space.xl)
            .fillMaxWidth()
            .background(Bg.raised, Radius.lg)
            .padding(start = Space.lg, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = AppType.caption.copy(color = Ink.body), modifier = Modifier.weight(1f))
        TextAction("Retry", onClick = onRetry, color = Accent.text)
    }
}

/** The end of the feed: a way out for the undecided instead of a dead stop. */
@Composable
private fun EndOfFeed(onSurprise: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.lg, end = Space.lg, top = Space.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Can't decide?", style = AppType.heading)
        Spacer(Modifier.height(Space.xs))
        Text("Let Reelm pick something from Home.", style = AppType.caption)
        Spacer(Modifier.height(Space.lg))
        SecondaryButton(text = "Surprise me", onClick = onSurprise, icon = Icons.Rounded.Shuffle)
    }
}

@Composable
private fun HomeSkeleton() {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 5f)
        ) {
            LoadingShimmer(Modifier.fillMaxSize())
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = Space.lg, bottom = Space.xl + Space.sm)
            ) {
                Box(Modifier.width(220.dp).height(28.dp).background(Bg.overlay, Radius.sm))
                Spacer(Modifier.height(Space.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Box(Modifier.width(96.dp).height(44.dp).background(Bg.overlay, Radius.pill))
                    Box(Modifier.width(112.dp).height(44.dp).background(Bg.overlay, Radius.pill))
                }
            }
        }
        Spacer(Modifier.height(Space.xl))
        PosterRowSkeleton()
        Spacer(Modifier.height(Space.xl))
        PosterRowSkeleton()
    }
}
