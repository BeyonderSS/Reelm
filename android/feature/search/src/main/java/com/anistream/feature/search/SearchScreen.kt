package com.anistream.feature.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anistream.core.ui.components.AnimeCard
import com.anistream.core.ui.components.EmptyState
import com.anistream.core.ui.components.ErrorContent
import com.anistream.core.ui.components.LoadingShimmer
import com.anistream.core.ui.components.SegmentedControl
import com.anistream.domain.ContentPrefs
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import com.anistream.domain.model.AnimeSearchResult

private val typeFilters = listOf("All", "TV", "Movie", "OVA", "ONA", "Special")
private val mediaTypeFilters = listOf("All", "Movie", "Series")

private enum class Mode { Idle, Loading, Error, Empty, Results }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onAnimeClick: (String) -> Unit,
    onMediaClick: (String) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val movies = uiState.scope == 1
    val open: (String) -> Unit = if (movies) onMediaClick else onAnimeClick
    val popular = if (movies) uiState.mediaPopular else uiState.popular
    val popularLoading = if (movies) uiState.isMediaPopularLoading else uiState.isPopularLoading

    Column(Modifier.fillMaxSize().background(Bg.base)) {
        Text(
            "Search",
            style = AppType.title,
            modifier = Modifier.padding(start = Space.lg, end = Space.lg, top = Space.sm, bottom = Space.md)
        )
        val hidden by ContentPrefs.hidden.collectAsStateWithLifecycle()
        if (ContentPrefs.ANIME !in hidden && ContentPrefs.showsMoviesTab()) SegmentedControl(
            options = listOf("Anime", "Movies & TV"),
            selectedIndex = uiState.scope,
            onSelect = viewModel::onScopeChange,
            modifier = Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.md)
        )
        SearchField(
            query = uiState.query,
            onQueryChange = viewModel::onQueryChange,
            onClear = viewModel::onClearQuery,
            onSearch = { keyboard?.hide() },
            modifier = Modifier.padding(horizontal = Space.lg)
        )
        if (uiState.query.isNotBlank()) {
            LazyRow(
                modifier = Modifier.padding(top = Space.sm),
                contentPadding = PaddingValues(horizontal = Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                items(if (movies) mediaTypeFilters.filter { it == "All" || ContentPrefs.shows(if (it == "Series") ContentPrefs.SERIES else ContentPrefs.MOVIES) } else typeFilters) { type ->
                    val value = if (type == "All") null else type
                    PillChip(label = type, selected = uiState.typeFilter == value, onClick = { viewModel.onTypeFilterChange(value) })
                }
            }
        }

        val mode = when {
            uiState.query.isBlank() -> Mode.Idle
            uiState.isLoading -> Mode.Loading
            uiState.error != null -> Mode.Error
            uiState.visibleResults.isEmpty() -> Mode.Empty
            else -> Mode.Results
        }
        AnimatedContent(
            targetState = mode,
            transitionSpec = { fadeIn(tween(250, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(200)) },
            label = "search_mode"
        ) { m ->
            when (m) {
                Mode.Idle -> PosterGrid(items = popular, onClick = { open(it.slug) }) {
                    if (uiState.recentSearches.isNotEmpty()) {
                        fullSpan {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Recent searches", style = AppType.heading, modifier = Modifier.weight(1f))
                                TextButton(onClick = viewModel::onClearRecents) {
                                    Text("Clear all", style = AppType.label, color = Ink.body)
                                }
                            }
                        }
                        fullSpan {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                                modifier = Modifier.padding(bottom = Space.xl)
                            ) {
                                uiState.recentSearches.forEach { q ->
                                    PillChip(
                                        label = q,
                                        selected = false,
                                        onClick = { viewModel.onRecentSearchClick(q) },
                                        onRemove = { viewModel.onRemoveRecent(q) }
                                    )
                                }
                            }
                        }
                    }
                    if (popularLoading || popular.isNotEmpty()) {
                        fullSpan {
                            Text("Popular right now", style = AppType.heading, modifier = Modifier.padding(bottom = Space.xs))
                        }
                    }
                    if (popularLoading) skeletonItems()
                    else if (popular.isEmpty() && uiState.recentSearches.isEmpty()) fullSpan {
                        EmptyState(
                            message = if (movies) "Find a movie or show" else "Find your next anime",
                            subtitle = "Search by title, then filter by type",
                            icon = Icons.Rounded.Search,
                            modifier = Modifier.height(360.dp)
                        )
                    }
                }

                Mode.Loading -> PosterGrid(items = emptyList(), onClick = {}) { skeletonItems() }

                Mode.Error -> ErrorContent(
                    message = uiState.error ?: "Search failed",
                    onRetry = { viewModel.onQueryChange(uiState.query) }
                )

                Mode.Empty -> EmptyState(
                    message = "No results for “${uiState.query.trim()}”",
                    subtitle = if (uiState.results.isEmpty()) "Check the spelling or try a shorter title"
                    else "Nothing matches this type. Try “All”",
                    icon = Icons.Rounded.SearchOff
                )

                Mode.Results -> PosterGrid(
                    items = uiState.visibleResults,
                    onClick = { open(it.slug) },
                    isLoadingMore = uiState.isLoadingMore,
                    onLoadMore = viewModel::loadNextPage
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().height(52.dp).clip(Radius.pill),
        placeholder = { Text("Titles, genres, studios", style = AppType.body, color = Ink.faint) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Ink.muted) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = Ink.muted)
                }
            }
        },
        singleLine = true,
        textStyle = AppType.body.copy(color = Ink.high),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Bg.raised,
            unfocusedContainerColor = Bg.raised,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Accent.main
        )
    )
}

@Composable
private fun PillChip(label: String, selected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .height(36.dp)
            .clip(Radius.pill)
            .background(if (selected) Ink.high else Bg.raised)
            .clickable(onClick = onClick)
            .padding(start = Space.md, end = if (onRemove != null) Space.xs else Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = AppType.label, color = if (selected) Bg.base else Ink.body, maxLines = 1)
        if (onRemove != null) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Remove $label",
                tint = Ink.muted,
                modifier = Modifier.padding(start = Space.xs).size(28.dp).clip(Radius.pill).clickable(onClick = onRemove).padding(Space.xs + 2.dp)
            )
        }
    }
}

private fun LazyGridScope.fullSpan(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.skeletonItems() = items(9) {
    Column {
        LoadingShimmer(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(Radius.md))
        Spacer(Modifier.height(Space.sm))
        LoadingShimmer(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(Radius.sm))
    }
}

/** 3-column poster grid; [header] items render above the posters. Calls [onLoadMore] near the end. */
@Composable
private fun PosterGrid(
    items: List<AnimeSearchResult>,
    onClick: (AnimeSearchResult) -> Unit,
    isLoadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null,
    header: LazyGridScope.() -> Unit = {}
) {
    val state = rememberLazyGridState()
    if (onLoadMore != null) {
        val nearEnd by remember {
            derivedStateOf {
                val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val total = state.layoutInfo.totalItemsCount
                total > 0 && last >= total - 6
            }
        }
        LaunchedEffect(nearEnd, items.size) { if (nearEnd) onLoadMore() }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.lg, end = Space.lg, top = Space.lg, bottom = Space.xxxl),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.lg)
    ) {
        header()
        items(items, key = { it.slug }) { anime ->
            AnimeCard(anime = anime, onClick = { onClick(anime) }, modifier = Modifier.fillMaxWidth())
        }
        if (isLoadingMore) {
            fullSpan {
                Box(Modifier.fillMaxWidth().padding(Space.lg), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent.main, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
