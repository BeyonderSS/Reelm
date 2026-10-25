package com.anistream.feature.favorites

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.anistream.core.ui.components.MarqueeTitle
import com.anistream.core.ui.components.TicketShape
import com.anistream.core.ui.components.ErrorContent
import com.anistream.core.ui.components.LoadingShimmer
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.DisplayHeavy
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import com.anistream.domain.model.FavoriteAnime
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(
    onAnimeClick: (String) -> Unit,
    onDiscoverClick: () -> Unit = {},
    showHeader: Boolean = true,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    LifecycleResumeEffect(Unit) {
        viewModel.loadFavorites()
        onPauseOrDispose { }
    }

    fun remove(fav: FavoriteAnime) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.removeFavorite(fav)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("Removed “${fav.animeTitle}”", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) viewModel.restoreFavorite(fav)
        }
    }

    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear watch history?") },
        text = { Text("Continue Watching empties on all your devices. Favourites stay.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.clearHistory() }) { Text("Clear all") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )

    Scaffold(
        containerColor = Bg.base,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showHeader) MarqueeTitle("Favourites", null, Modifier.padding(start = Space.lg, end = Space.lg, top = Space.sm))
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.sm, top = Space.sm, bottom = Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                if (uiState.favorites.isNotEmpty()) SortMenu(uiState.sortOption, viewModel::onSortChange)
                TextButton(onClick = { confirmClear = true }) { Text("Clear history", style = AppType.label, color = Ink.body) }
            }

            val state = when {
                uiState.isLoading -> 0
                uiState.error != null -> 1
                uiState.sortedFavorites.isEmpty() -> 2
                else -> 3
            }
            Crossfade(targetState = state, animationSpec = tween(250), label = "favorites_state") { s ->
                when (s) {
                    0 -> Grid { items(9) { PosterSkeleton() } }
                    1 -> ErrorContent(message = uiState.error ?: "Failed to load favorites", onRetry = viewModel::loadFavorites)
                    2 -> EmptyFavorites(onDiscoverClick)
                    else -> Grid {
                        items(uiState.sortedFavorites, key = { it.animeSlug }) { fav ->
                            PosterTile(
                                fav = fav,
                                onClick = { onAnimeClick(fav.animeSlug) },
                                onLongClick = { remove(fav) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortMenu(current: SortOption, onSelect: (SortOption) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, tint = Ink.body, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.sm))
            Text(current.label, style = AppType.label, color = Ink.body)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Bg.overlay) {
            SortOption.entries.forEach { option ->
                val selected = option == current
                DropdownMenuItem(
                    text = { Text(option.label, style = AppType.body, color = if (selected) Ink.high else Ink.body) },
                    trailingIcon = { if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Accent.main) },
                    onClick = { onSelect(option); open = false }
                )
            }
        }
    }
}

@Composable
private fun Grid(content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.lg, end = Space.lg, top = Space.sm, bottom = Space.xxxl),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
        content = content
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterTile(fav: FavoriteAnime, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(200), label = "press")
    Column(
        modifier
            .scale(scale)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "Remove from favourites"
            )
    ) {
        // ticket stub: notches near the bottom edge of the poster
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(TicketShape(corner = 12.dp, notch = 6.dp, at = 0.9f)).background(Bg.raised)
        ) {
            Text(
                fav.animeTitle.trim().take(1).uppercase(),
                style = AppType.title.copy(fontFamily = DisplayHeavy, color = Accent.live.copy(alpha = 0.5f)),
                modifier = Modifier.align(Alignment.Center)
            )
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(fav.posterUrl).crossfade(true).build(),
                contentDescription = fav.animeTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Icon(
                Icons.Rounded.Favorite, contentDescription = "Remove from favourites", tint = Accent.main,
                modifier = Modifier.align(Alignment.TopEnd).padding(Space.xs).size(28.dp)
                    .background(Bg.badge, Radius.round).clickable(onClick = onLongClick).padding(5.dp)
            )
        }
        Spacer(Modifier.height(Space.sm))
        Text(fav.animeTitle, style = AppType.captionStrong.copy(fontWeight = FontWeight.SemiBold, color = Ink.high), maxLines = 2, overflow = TextOverflow.Ellipsis)
        val type = when { fav.kind == "anime" -> "Anime"; fav.mediaType == "movie" -> "Movie"; fav.mediaType == "series" -> "Series"; else -> null }
        val meta = listOfNotNull(type, fav.year?.toString()).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = AppType.caption, maxLines = 1)
    }
}

@Composable
private fun PosterSkeleton() {
    Column {
        LoadingShimmer(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(Radius.md))
        Spacer(Modifier.height(Space.sm))
        LoadingShimmer(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(Radius.sm))
    }
}

@Composable
private fun EmptyFavorites(onDiscoverClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Space.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MarqueeTitle("Nothing saved", "yet", size = 30)
        Text(
            "Tap the heart on any title to keep it here",
            style = AppType.body, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.md, bottom = Space.xl)
        )
        Button(
            onClick = onDiscoverClick,
            colors = ButtonDefaults.buttonColors(containerColor = Accent.main, contentColor = Accent.on)
        ) { Text("Discover", style = AppType.button, color = Accent.on) }
    }
}
