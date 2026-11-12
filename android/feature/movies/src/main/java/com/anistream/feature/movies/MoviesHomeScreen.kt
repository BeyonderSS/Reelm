package com.anistream.feature.movies

import com.anistream.domain.model.heroItems
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anistream.core.ui.components.AnimeCarousel
import com.anistream.core.ui.components.AnimeGrid
import com.anistream.core.ui.components.ArtworkImage
import com.anistream.core.ui.components.EmptyState
import com.anistream.core.ui.components.ErrorContent
import com.anistream.core.ui.components.LoadingShimmer
import com.anistream.core.ui.components.PosterGridSkeleton
import com.anistream.core.ui.components.PosterRowSkeleton
import com.anistream.core.ui.components.PrimaryButton
import com.anistream.core.ui.components.SecondaryButton
import com.anistream.core.ui.components.SectionHeader
import com.anistream.core.ui.components.pressClickable
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import com.anistream.core.ui.theme.Motion
import com.anistream.core.ui.components.MetaChips
import com.anistream.domain.model.MediaItem
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.WatchHistory
import com.anistream.domain.model.forYouRows
import kotlinx.coroutines.delay

private const val HERO_COUNT = 8

private enum class Mode { Loading, Error, Empty, Content }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoviesHomeScreen(
    onItemClick: (String) -> Unit,
    onSeeAll: (key: String, title: String) -> Unit,
    onAddonsClick: () -> Unit,
    viewModel: MoviesHomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshContinueWatching()
        onPauseOrDispose { }
    }

    val hero = uiState.rows.heroItems(HERO_COUNT)
    val error = uiState.error
    val mode = when {
        uiState.rows.isNotEmpty() -> Mode.Content
        uiState.isLoading -> Mode.Loading
        error != null -> Mode.Error
        else -> Mode.Empty
    }
    val listState = rememberLazyListState()
    val refreshState = rememberPullToRefreshState()
    val refreshing = uiState.isLoading && uiState.rows.isNotEmpty()

    Box(Modifier.fillMaxSize().background(Bg.base)) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::load,
            state = refreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = refreshState,
                    isRefreshing = refreshing,
                    containerColor = Bg.overlay,
                    color = Accent.main,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp)
                )
            }
        ) {
            Crossfade(targetState = mode, animationSpec = tween(250), label = "moviesMode") { m ->
                when (m) {
                    Mode.Loading -> Column(Modifier.fillMaxSize()) {
                        LoadingShimmer(Modifier.fillMaxWidth().aspectRatio(3f / 4f))
                        Spacer(Modifier.height(Space.xl))
                        PosterRowSkeleton()
                    }
                    Mode.Error -> ErrorContent(message = error ?: "", onRetry = viewModel::load)
                    Mode.Empty -> EmptyState(
                        message = "Nothing to show yet",
                        icon = Icons.Rounded.Movie,
                        actionLabel = "Refresh",
                        onAction = viewModel::load
                    )
                    Mode.Content -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = Space.xl)
                    ) {
                        if (hero.isNotEmpty()) {
                            item(key = "hero") { HeroPager(hero, onOpen = { onItemClick(it.id) }) }
                        } else {
                            item(key = "top") {
                                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                                Spacer(Modifier.height(56.dp))
                            }
                        }
                        if (uiState.continueWatching.isNotEmpty()) {
                            item(key = "cw") {
                                Spacer(Modifier.height(Space.lg))
                                SectionHeader(title = "Continue watching")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = Space.lg),
                                    horizontalArrangement = Arrangement.spacedBy(Space.md)
                                ) {
                                    items(uiState.continueWatching, key = { it.animeSlug }) { h ->
                                        ResumeCard(h, onRemove = { viewModel.removeHistory(h.animeSlug) }, onClick = { onItemClick(h.detailId) })
                                    }
                                }
                            }
                        }
                        // "For you" (TMDB recommendations): only once there is history
                        val forYou = uiState.picks.forYouRows()
                        forYou.forEachIndexed { i, (title, picks) ->
                            item(key = "fy-$title") {
                                Spacer(Modifier.height(Space.xl))
                                SectionHeader(title = title)
                                AnimeCarousel(items = remember(picks) { picks.map { it.asMediaItem().asCard() } }, onAnimeClick = { onItemClick(it.slug) })
                                // TMDB's terms ask for attribution wherever their data shows
                                if (i == forYou.lastIndex) Text("Data from TMDB", style = AppType.caption, modifier = Modifier.padding(horizontal = Space.lg))
                            }
                        }
                        uiState.rows.forEachIndexed { i, row ->
                            item(key = "row-$i-${row.title}") {
                                Spacer(Modifier.height(Space.xl))
                                val key = row.catalogKey
                                SectionHeader(title = row.title, onSeeAll = key?.let { k -> { onSeeAll(k, row.title) } })
                                AnimeCarousel(items = remember(row) { row.items.map { it.asCard() } }, onAnimeClick = { onItemClick(it.slug) })
                            }
                        }
                    }
                }
            }
        }

        val solid by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120 } }
        val bg by animateColorAsState(
            if (solid || mode != Mode.Content) Bg.bar else Color.Transparent,
            Motion.standard(),
            label = "moviesTopBar"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Bg.scrimTop)
                .background(bg)
                .statusBarsPadding()
                .height(56.dp)
                .padding(start = Space.lg, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Movies & TV", style = AppType.heading.copy(fontSize = 24.sp), modifier = Modifier.weight(1f))
            IconButton(onClick = onAddonsClick) {
                Icon(Icons.Outlined.Extension, contentDescription = "Manage addons", tint = Ink.high)
            }
        }
    }
}

@Composable
private fun HeroPager(items: List<MediaItem>, onOpen: (MediaItem) -> Unit) {
    val pagerState = rememberPagerState { items.size }
    LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress, items.size) {
        if (items.size < 2 || pagerState.isScrollInProgress) return@LaunchedEffect
        delay(6_000)
        pagerState.animateScrollToPage((pagerState.settledPage + 1) % items.size, animationSpec = tween(600, easing = Motion.emphasized))
    }
    Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
        // "The projector": backdrop crossfades to the current item; text slides with the pager.
        Crossfade(
            targetState = items[pagerState.currentPage.coerceIn(0, items.lastIndex)],
            animationSpec = tween(Motion.PROJECTOR, easing = Motion.emphasized),
            label = "heroBackdrop"
        ) { item -> ArtworkImage(item.posterUrl, null, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()) }
        HorizontalPager(state = pagerState, key = { items[it].id }, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            Box(Modifier.fillMaxSize().clickable { onOpen(item) }) {
                Box(Modifier.fillMaxWidth().height(140.dp).background(Bg.scrimTop))
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.6f).align(Alignment.BottomCenter).background(Bg.scrim))
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = Space.lg, end = Space.lg, bottom = Space.xl + Space.sm)
                ) {
                    MetaChips(listOf(if (item.type == MediaType.SERIES) "Series" else "Movie", item.year?.toString(), item.genres.firstOrNull()))
                    Spacer(Modifier.height(Space.sm))
                    Text(item.title, style = AppType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(Space.lg))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        PrimaryButton(text = "Watch", onClick = { onOpen(item) }, icon = Icons.Rounded.PlayArrow)
                        SecondaryButton(text = "Details", onClick = { onOpen(item) }, icon = Icons.Outlined.Info)
                    }
                }
            }
        }
        if (items.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = Space.sm),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(items.size) { i ->
                    val selected = pagerState.currentPage == i
                    val w by animateDpAsState(if (selected) 20.dp else 6.dp, Motion.standard(), label = "dash")
                    Box(Modifier.height(6.dp).width(w).background(if (selected) Ink.high else Ink.faint, Radius.pill))
                }
            }
        }
    }
}

/** 16:9 resume card with an amber progress edge; caption is the title plus the episode label. */
@Composable
private fun ResumeCard(history: WatchHistory, onRemove: () -> Unit, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.width(240.dp).clip(Radius.md).pressClickable(onLongClick = { menu = true }, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Radius.md)) {
            ArtworkImage(history.posterUrl, history.animeTitle, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.6f).align(Alignment.BottomCenter).background(Bg.scrim))
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume", tint = Ink.high, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Ink.ghost)) {
                Box(Modifier.fillMaxWidth(history.watchProgress.coerceIn(0f, 1f)).fillMaxHeight().background(Accent.main))
            }
        }
        Spacer(Modifier.height(Space.sm))
        Text(history.animeTitle, style = AppType.captionStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = history.subtitle
        if (!sub.isNullOrBlank()) Text(sub, style = AppType.numeric, maxLines = 1)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Remove from history") }, onClick = { menu = false; onRemove() })
        }
    }
}

/** "See all" for one home row: infinite-scroll poster grid. */
@Composable
fun MoviesCatalogScreen(
    onBack: () -> Unit,
    onItemClick: (String) -> Unit,
    viewModel: MoviesCatalogViewModel = hiltViewModel()
) {
    val s by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Bg.base)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(56.dp).padding(end = Space.lg)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Ink.high)
            }
            Text(viewModel.title.ifBlank { "Browse" }, style = AppType.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val err = s.error
        when {
            s.items.isEmpty() && s.isLoading -> PosterGridSkeleton()
            err != null -> ErrorContent(message = err, onRetry = viewModel::reload)
            s.items.isEmpty() -> EmptyState(message = "Nothing here yet", icon = Icons.Rounded.Movie)
            else -> AnimeGrid(
                items = remember(s.items) { s.items.map { it.asCard() } },
                onAnimeClick = { onItemClick(it.slug) },
                isLoadingMore = s.isLoadingMore,
                onLoadMore = viewModel::loadMore
            )
        }
    }
}
