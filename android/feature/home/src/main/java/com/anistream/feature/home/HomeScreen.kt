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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.blur
import com.anistream.core.trailer.PreviewMode
import com.anistream.core.ui.components.PillChip
import com.anistream.domain.ContentPrefs
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
private const val REEL_MAX = 10
private const val TOP_PICKS = "Top picks for you"
private val TOP_BAR_HEIGHT = 56.dp

private enum class HomeMode { Loading, Error, Empty, Content }

/** A featured title in the hero; [media] = Movies & TV (opens the movie detail), otherwise anime. */
@Immutable
internal data class HeroItem(val card: AnimeSearchResult, val media: Boolean)

/** Home's filter chips; Live TV is a chip too but opens the Live TV tab. */
private enum class PhoneFilter(val label: String) {
    All("All"), Movies("Movies"), Series("Series"), Anime("Anime");

    fun keeps(media: Boolean, type: String?): Boolean = when (this) {
        All -> true
        Movies -> media && !type.equals("Series", true)
        Series -> media && type.equals("Series", true)
        Anime -> !media
    }

    /** media history rows: episode 0 = a movie */
    fun keeps(h: WatchHistory): Boolean = if (h.kind == "media") keeps(true, if (h.lastEpisodeWatched > 0) "Series" else "Movie") else keeps(false, null)
}

private fun <T> interleave(a: List<T>, b: List<T>): List<T> = (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }

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

    var filter by rememberSaveable { mutableStateOf(PhoneFilter.All) }
    val hidden by ContentPrefs.hidden.collectAsStateWithLifecycle()
    val fHero = remember(hero, filter) { hero.filter { filter.keeps(it.media, it.card.type) } }
    val fRows = remember(rows, filter) {
        rows.map { r -> r.copy(items = r.items.filter { filter.keeps(r.media, it.type) }) }.filter { it.items.isNotEmpty() }
    }
    val fAnimePicks = if (filter.keeps(false, null)) animePicks else emptyList()
    val fMediaPicks = remember(mediaPicks, filter) {
        mediaPicks.map { (t, l) -> t to l.filter { filter.keeps(true, it.type) } }.filter { it.second.isNotEmpty() }
    }
    val continueWatching = remember(uiState.continueWatching, filter) { uiState.continueWatching.filter(filter::keeps) }

    // Tonight's reel: Top picks (anime and Movies & TV interleaved); the hero's titles stand in until there are 3 picks
    val previewMode by viewModel.trailers.mode.collectAsStateWithLifecycle()
    val reelCandidates = remember(animePicks, mediaPicks, hero) {
        val a = animePicks.firstOrNull { it.first == TOP_PICKS }?.second.orEmpty().map { HeroItem(it, media = false) }
        val m = mediaPicks.firstOrNull { it.first == TOP_PICKS }?.second.orEmpty().map { HeroItem(it, media = true) }
        val picks = interleave(a, m)
        (if (picks.size >= 3) picks else picks + hero).distinctBy { it.card.slug }.take(REEL_MAX)
    }
    LaunchedEffect(reelCandidates, previewMode) {
        if (previewMode != PreviewMode.OFF && reelCandidates.isNotEmpty()) viewModel.probeReel(reelCandidates.map { it.card to it.media })
    }
    val reelReady by viewModel.reelReady.collectAsStateWithLifecycle()
    val reel = remember(reelCandidates, reelReady, filter) {
        reelCandidates.filter { it.card.slug in reelReady && filter.keeps(it.media, it.card.type) }
    }
    val showReel = previewMode != PreviewMode.OFF && reel.size >= 3
    var projecting by remember { mutableStateOf<HeroItem?>(null) }
    var reelAt by remember { mutableStateOf<Int?>(null) }
    var seen by remember { mutableStateOf(emptySet<String>()) }

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
    val playHero: (HeroItem) -> Unit = { h -> if (h.media) onMediaClick(h.card.slug) else onPlayEpisode(h.card.slug, 1) }
    val hold: (AnimeSearchResult, Boolean) -> Unit = { a, media -> projecting = HeroItem(a, media) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg.base)
    ) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.loadContent() },
            state = refreshState,
            // the Hold to project scrim blurs Home behind the lifted card (API 31+; plain scrim below)
            modifier = Modifier.fillMaxSize().blur(if (projecting != null) 14.dp else 0.dp),
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
                        item(key = "topSpace") {
                            Column {
                                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                                Spacer(Modifier.height(TOP_BAR_HEIGHT))
                            }
                        }
                        item(key = "filters", contentType = "filters") {
                            FilterChips(
                                selected = filter,
                                hidden = hidden,
                                showLive = ContentPrefs.LIVE !in hidden,
                                onSelect = { filter = it },
                                onLive = onLiveTvClick
                            )
                        }
                        if (showReel) {
                            item(key = "reel", contentType = "reel") {
                                ReelRow(reel, seen) { reelAt = it }
                            }
                        }
                        if (fHero.isNotEmpty()) {
                            item(key = "hero", contentType = "hero") {
                                // re-key on the filter so the pager starts over on the filtered titles
                                androidx.compose.runtime.key(filter) {
                                    LivingHero(
                                        items = fHero,
                                        myList = uiState.myList,
                                        listState = listState,
                                        paused = projecting != null || reelAt != null,
                                        vm = viewModel,
                                        onPlay = playHero,
                                        onToggleList = { viewModel.toggleMyList(it.card) },
                                        onDetails = openHero
                                    )
                                }
                            }
                        }

                        if (continueWatching.isNotEmpty()) {
                            item(key = "cw", contentType = "cw") {
                                Section("Continue watching") {
                                    TicketStrip(continueWatching, onRemove = { viewModel.removeHistory(it.animeSlug) }) { h ->
                                        if (h.kind == "media") onMediaClick(h.detailId)
                                        else onPlayEpisode(h.animeSlug, h.resumeEpisode())
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

                        fAnimePicks.forEach { (title, items) ->
                            item(key = "fy-a-$title", contentType = "row") {
                                Section(title) { HoldCarousel(items, onClick = { onAnimeClick(it.slug) }, onHold = { hold(it, false) }) }
                            }
                        }
                        fMediaPicks.forEachIndexed { i, (title, items) ->
                            item(key = "fy-m-$title", contentType = "row") {
                                Section(if (fAnimePicks.any { it.first == title }) "$title in Movies & TV" else title) {
                                    HoldCarousel(items, onClick = { onMediaClick(it.slug) }, onHold = { hold(it, true) })
                                    // TMDB's terms ask for attribution wherever their data shows
                                    if (i == fMediaPicks.lastIndex) {
                                        Text("Data from TMDB", style = AppType.caption, modifier = Modifier.padding(start = Space.lg, top = Space.sm))
                                    }
                                }
                            }
                        }

                        val showLiveRow = filter == PhoneFilter.All && uiState.channels.isNotEmpty()
                        fRows.forEachIndexed { i, row ->
                            // live TV sits a few rows down, after the first anime and movie rows
                            if (i == LIVE_ROW_AT && showLiveRow) liveRow(uiState.channels, onLiveTvClick) { ch ->
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
                                    HoldCarousel(
                                        row.items,
                                        onClick = { if (row.media) onMediaClick(it.slug) else onAnimeClick(it.slug) },
                                        onHold = { hold(it, row.media) }
                                    )
                                }
                            }
                        }
                        if (fRows.size <= LIVE_ROW_AT && showLiveRow) liveRow(uiState.channels, onLiveTvClick) { ch ->
                            viewModel.playChannel(ch)
                            onPlayLive()
                        }

                        val pool = fRows.flatMap { r -> r.items.map { it to r.media } }
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
            solid = mode != HomeMode.Content,
            onSearchClick = onSearchClick,
            onGenresClick = onGenresClick,
            onScheduleClick = onScheduleClick,
            onMenuClick = onMenuClick
        )
    }

    projecting?.let { p ->
        ProjectCard(
            item = p, vm = viewModel, inList = p.card.slug in uiState.myList,
            onDismiss = { projecting = null },
            onPlay = { projecting = null; playHero(p) },
            onToggleList = { viewModel.toggleMyList(p.card) },
            onDetails = { projecting = null; openHero(p) }
        )
    }
    reelAt?.let { start ->
        ReelFeed(
            items = reel, start = start, vm = viewModel, myList = uiState.myList,
            onSeen = { seen = seen + it },
            onDismiss = { reelAt = null },
            onPlay = { reelAt = null; playHero(it) },
            onToggleList = { viewModel.toggleMyList(it.card) }
        )
    }
}

/** All / Movies / Series / Anime filter Home in place (kinds hidden in "What you watch" drop out); Live TV opens its tab. */
@Composable
private fun FilterChips(selected: PhoneFilter, hidden: Set<String>, showLive: Boolean, onSelect: (PhoneFilter) -> Unit, onLive: () -> Unit) {
    val shown = PhoneFilter.entries.filter {
        when (it) {
            PhoneFilter.All -> true
            PhoneFilter.Movies -> ContentPrefs.MOVIES !in hidden
            PhoneFilter.Series -> ContentPrefs.SERIES !in hidden
            PhoneFilter.Anime -> ContentPrefs.ANIME !in hidden
        }
    }
    LazyRow(
        contentPadding = PaddingValues(start = Space.lg, end = Space.lg, bottom = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        items(shown, key = { it.name }) { f -> PillChip(f.label, selected = f == selected, onClick = { onSelect(f) }) }
        if (showLive) item(key = "live") { PillChip("Live TV", selected = false, onClick = onLive) }
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
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Spacer(Modifier.height(TOP_BAR_HEIGHT + 48.dp))
        Box(
            Modifier
                .padding(horizontal = Space.lg)
                .fillMaxWidth()
                .aspectRatio(4f / 5f)
                .clip(Radius.xl)
        ) {
            LoadingShimmer(Modifier.fillMaxSize())
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Space.lg),
                horizontalAlignment = Alignment.CenterHorizontally
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
