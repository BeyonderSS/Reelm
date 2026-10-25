package com.anistream.feature.anime

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.anistream.core.ui.components.AnimeCarousel
import com.anistream.core.ui.components.DownloadSheet
import com.anistream.core.ui.components.DownloadStubButton
import com.anistream.core.ui.components.DownloadedBadge
import com.anistream.core.ui.components.ErrorContent
import com.anistream.core.ui.components.LoadingShimmer
import com.anistream.core.ui.components.PrimaryButton
import com.anistream.core.ui.components.SecondaryButton
import com.anistream.core.ui.components.SectionHeader
import com.anistream.core.ui.components.resumeEpisode
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import com.anistream.core.ui.theme.Motion
import com.anistream.domain.model.AnimeDetail
import com.anistream.domain.model.Episode
import com.anistream.domain.model.Eps
import com.anistream.domain.model.WatchHistory
import kotlinx.coroutines.launch

private const val TILES_PER_ROW = 5
private const val RANGE_SIZE = 100
private val BarHeight = 56.dp
private val ActionBarExtra = 64.dp
private val Tabular = AppType.bodyStrong.copy(fontFeatureSettings = "tnum")

private enum class Phase { Loading, Error, Content }

@Composable
fun AnimeDetailScreen(
    onBack: () -> Unit,
    onAnimeClick: (String) -> Unit,
    onPlayEpisode: (String, Int) -> Unit,
    onGenreClick: (String) -> Unit = {},
    viewModel: AnimeDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refreshHistory()
        onPauseOrDispose { }
    }

    val phase = when {
        uiState.isLoading -> Phase.Loading
        uiState.anime == null -> Phase.Error
        else -> Phase.Content
    }

    val context = LocalContext.current
    LaunchedEffect(uiState.message) {
        uiState.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.messageShown() }
    }

    val listState = rememberLazyListState()
    // the hero bleeds under the status bar; once it scrolls away, a solid bar keeps text off the clock
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 240 } }
    val barBg by animateColorAsState(if (scrolled) Bg.base else Color.Transparent, tween(200), label = "detailBar")

    Box(Modifier.fillMaxSize().background(Bg.base)) {
        Crossfade(targetState = phase, animationSpec = tween(250), label = "detail") { p ->
            when (p) {
                Phase.Loading -> DetailSkeleton()
                Phase.Error -> ErrorContent(
                    message = uiState.error ?: "Failed to load",
                    onRetry = viewModel::loadAnimeDetail,
                    modifier = Modifier.statusBarsPadding()
                )
                Phase.Content -> {
                    val anime = uiState.anime
                    if (anime != null) {
                        DetailContent(
                            listState = listState,
                            anime = anime,
                            currentSlug = viewModel.slug,
                            episodes = uiState.episodes,
                            episodesError = uiState.episodesError,
                            history = uiState.history,
                            downloaded = uiState.downloaded,
                            activeEpisode = uiState.activeEpisode,
                            activeProgress = uiState.activeProgress,
                            onRetry = viewModel::loadAnimeDetail,
                            onAnimeClick = onAnimeClick,
                            onGenreClick = onGenreClick,
                            onPlay = { onPlayEpisode(viewModel.slug, it) }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().background(barBg).statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(Space.sm).background(if (scrolled) Color.Transparent else Bg.badge, Radius.round)
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Ink.high)
            }
            if (scrolled) {
                Text(
                    uiState.anime?.title.orEmpty(),
                    style = AppType.heading,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = Space.lg)
                )
            }
        }

        if (phase == Phase.Content) {
            val history = uiState.history
            val firstEpisode = uiState.episodes.firstOrNull()?.number ?: 1
            val resume = history?.takeIf { it.lastEpisodeWatched > 0 }?.resumeEpisode()
            val nextUp = resume ?: firstEpisode
            val epNumbers = uiState.episodes.map { it.number }
            val anime = uiState.anime
            if (uiState.sheetOpen && anime != null) {
                val rangeLabel = epNumbers.chunked(RANGE_SIZE).takeIf { it.size > 1 }
                    ?.firstOrNull { nextUp in it }?.let { "${it.first()}–${it.last()}" }
                val quality = uiState.options?.firstOrNull { it.id == uiState.selectedId }?.quality
                DownloadSheet(
                    title = "Download",
                    subtitle = if (rangeLabel != null) "${anime.title} · $rangeLabel" else anime.title,
                    options = uiState.options,
                    selectedId = uiState.selectedId,
                    onSelect = viewModel::select,
                    episodes = epNumbers,
                    downloaded = uiState.downloaded,
                    picked = uiState.picked,
                    onPickedChange = viewModel::setPicked,
                    nextEpisode = nextUp,
                    summary = quality?.let { "${uiState.picked.size} episode${if (uiState.picked.size == 1) "" else "s"} · $it" },
                    confirmLabel = "Download ${uiState.picked.size} episode${if (uiState.picked.size == 1) "" else "s"}",
                    onConfirm = viewModel::confirmDownload,
                    onDismiss = viewModel::closeSheet
                )
            }
            val active = uiState.activeEpisode
            ActionBar(
                downloadLabel = when {
                    active != null -> "Downloading Ep $active"
                    uiState.downloaded.isNotEmpty() -> "Download more"
                    else -> "Download"
                },
                downloadCaption = when {
                    active != null -> null
                    uiState.downloaded.isNotEmpty() -> "${uiState.downloaded.size} of ${epNumbers.size} on this phone"
                    else -> "Choose quality"
                },
                downloadTrailing = when {
                    active != null -> "${(uiState.activeProgress * 100).toInt()}%" + if (uiState.queued > 0) " · ${uiState.queued} queued" else ""
                    uiState.downloaded.isNotEmpty() -> null
                    else -> "Change"
                },
                downloadProgress = if (active != null) uiState.activeProgress else null,
                downloadEnabled = epNumbers.isNotEmpty(),
                onDownload = { viewModel.openSheet(nextUp) },
                label = when {
                    resume != null -> "Continue Ep $resume"
                    uiState.episodes.isEmpty() -> "Not released yet"
                    else -> "Play Ep $firstEpisode"
                },
                enabled = uiState.episodes.isNotEmpty() || resume != null,
                isFavorite = uiState.isFavorite,
                onPlay = { onPlayEpisode(viewModel.slug, resume ?: firstEpisode) },
                onFavorite = viewModel::toggleFavorite
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    listState: LazyListState,
    anime: AnimeDetail,
    currentSlug: String,
    episodes: List<Episode>,
    episodesError: String?,
    history: WatchHistory?,
    downloaded: Set<Int>,
    activeEpisode: Int?,
    activeProgress: Float,
    onRetry: () -> Unit,
    onAnimeClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    onPlay: (Int) -> Unit
) {
    val lastWatched = history?.lastEpisodeWatched ?: 0
    val nextUp = history?.takeIf { lastWatched > 0 }?.resumeEpisode()
    // per-episode progress when synced; older rows without it fall back to "everything before the latest episode"
    fun progress(n: Int) = history?.eps?.get("e$n")?.p ?: if (n == lastWatched) history?.watchProgress ?: 0f else 0f
    fun watched(n: Int) = n < lastWatched || progress(n) >= Eps.DONE

    val ranges = remember(episodes) { episodes.chunked(RANGE_SIZE) }
    var range by rememberSaveable(ranges.size, nextUp) {
        mutableIntStateOf(ranges.indexOfFirst { r -> r.any { it.number == nextUp } }.coerceAtLeast(0))
    }
    val rows = remember(ranges, range) { ranges.getOrElse(range) { emptyList() }.chunked(TILES_PER_ROW) }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + BarHeight + ActionBarExtra + Space.xxxl)
    ) {
        item(key = "hero") { Hero(anime) }

        val synopsis = anime.synopsis
        if (!synopsis.isNullOrBlank()) {
            item(key = "synopsis") { Synopsis(synopsis) }
        }

        if (anime.genres.isNotEmpty()) {
            item(key = "genres") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                    modifier = Modifier.padding(horizontal = Space.lg).padding(top = Space.lg)
                ) {
                    anime.genres.forEach { genre ->
                        Chip(genre.name, onClick = {
                            onGenreClick(genre.slug.ifBlank { genre.name.lowercase().replace(' ', '-') })
                        })
                    }
                }
            }
        }

        if (anime.seasons.size > 1) {
            item(key = "seasons") {
                Column(Modifier.padding(top = Space.xxl)) {
                    Heading("Seasons")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Space.lg),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm)
                    ) {
                        items(anime.seasons, key = { it.slug }) { season ->
                            val current = season.slug == currentSlug
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .width(144.dp)
                                    .height(64.dp)
                                    .clip(Radius.lg)
                                    .background(if (current) Ink.high else Bg.raised)
                                    .border(1.dp, if (current) Ink.high else Ink.hairline, Radius.lg)
                                    .clickable(enabled = !current) { onAnimeClick(season.slug) }
                                    .padding(horizontal = Space.md)
                            ) {
                                Text(
                                    season.title,
                                    style = AppType.captionStrong.copy(color = if (current) Bg.base else Ink.body),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "episodes-header") {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = Space.xxl)
            ) {
                Heading("Episodes")
                if (episodes.isNotEmpty()) {
                    Text(
                        "${episodes.size}",
                        style = AppType.numeric,
                        modifier = Modifier.padding(bottom = Space.md + 3.dp)
                    )
                }
            }
        }

        if (episodes.isEmpty()) {
            item(key = "episodes-empty") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(Space.xl)
                ) {
                    Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = Ink.faint, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(Space.sm))
                    Text(episodesError ?: "No episodes yet", style = AppType.body)
                    if (episodesError != null) {
                        Spacer(Modifier.height(Space.md))
                        SecondaryButton(text = "Retry", onClick = onRetry)
                    }
                }
            }
        } else {
            if (ranges.size > 1) {
                item(key = "ranges") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Space.lg),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        modifier = Modifier.padding(bottom = Space.md)
                    ) {
                        itemsIndexed(ranges) { i, r ->
                            Chip(
                                "${r.first().number}–${r.last().number}",
                                selected = i == range,
                                numeric = true,
                                onClick = { range = i }
                            )
                        }
                    }
                }
            }
            items(rows, key = { "row-${it.first().id}" }) { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    modifier = Modifier.padding(horizontal = Space.lg).padding(bottom = Space.sm)
                ) {
                    row.forEach { ep ->
                        EpisodeTile(
                            number = ep.number,
                            watched = watched(ep.number),
                            progress = progress(ep.number),
                            next = ep.number == nextUp,
                            downloaded = ep.number in downloaded,
                            downloadProgress = if (ep.number == activeEpisode) activeProgress else null,
                            onClick = { onPlay(ep.number) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(TILES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        if (episodes.isNotEmpty()) {
            item(key = "legend") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.lg),
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Box(Modifier.size(8.dp).background(Accent.live, CircleShape))
                        Text("On this phone", style = AppType.label)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Box(Modifier.size(8.dp).border(1.5.dp, Accent.main, CircleShape))
                        Text("Up next", style = AppType.label)
                    }
                }
            }
        }

        if (anime.recommendations.isNotEmpty()) {
            item(key = "recs") {
                Column(Modifier.padding(top = Space.xl)) {
                    SectionHeader(title = "You might also like")
                    AnimeCarousel(items = anime.recommendations, onAnimeClick = { onAnimeClick(it.slug) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(anime: AnimeDetail) {
    val context = LocalContext.current
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(Modifier.fillMaxWidth()) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(anime.bannerUrl ?: anime.posterUrl).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .then(if (canBlur) Modifier.blur(24.dp) else Modifier)
                // no RenderEffect blur before API 31: dim harder so the backdrop stays quiet
                .graphicsLayer { alpha = if (canBlur) 0.7f else 0.35f }
        )
        Box(Modifier.matchParentSize().background(Bg.scrim))

        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = BarHeight + Space.lg, start = Space.lg, end = Space.lg)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(anime.posterUrl).crossfade(true).build(),
                contentDescription = anime.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(2f / 3f)
                    .clip(Radius.md)
                    .background(Bg.raised)
            )
            Spacer(Modifier.width(Space.lg))
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(anime.title, style = AppType.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val score = anime.score
                if (score != null && score > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Star, contentDescription = null, tint = Accent.star, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Space.xs))
                        Text("%.2f".format(score), style = AppType.numeric.copy(color = Ink.high))
                        val by = anime.scoredBy
                        if (by != null && by > 0) Text("${compact(by)} ratings", style = AppType.numeric, modifier = Modifier.padding(start = Space.sm))
                    }
                }
            }
        }
    }

    val status = anime.status
    val meta = listOfNotNull(
        anime.type,
        anime.duration,
        anime.rating?.substringBefore(" ")?.takeIf { it.isNotBlank() },
        anime.subEpisodes?.let { "Sub $it" },
        anime.dubEpisodes?.let { "Dub $it" }
    ).filter { it.isNotBlank() }
    if (meta.isNotEmpty() || !status.isNullOrBlank()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
            modifier = Modifier.padding(horizontal = Space.lg).padding(top = Space.lg)
        ) {
            if (!status.isNullOrBlank()) {
                val live = status.contains("airing", true) || status.contains("finished", true) || status.contains("complete", true)
                MetaChip(status, if (live) Accent.live else Ink.body)
            }
            meta.forEach { MetaChip(it, Ink.body) }
        }
    }
}

@Composable
private fun MetaChip(text: String, color: Color) {
    Text(
        text,
        style = AppType.label.copy(color = color),
        maxLines = 1,
        modifier = Modifier
            .background(Bg.raised, Radius.pill)
            .padding(horizontal = Space.md, vertical = Space.xs)
    )
}

@Composable
private fun Synopsis(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Column(
        Modifier
            .padding(horizontal = Space.lg)
            .padding(top = Space.xl)
            .animateContentSize(Motion.standard())
    ) {
        Text(
            text,
            style = AppType.body,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow }
        )
        if (overflows || expanded) {
            Text(
                if (expanded) "Show less" else "Read more",
                style = AppType.label.copy(color = Ink.body),
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = Space.sm)
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = AppType.heading,
        modifier = Modifier.padding(horizontal = Space.lg).padding(bottom = Space.md)
    )
}

/** Pill chip on slate; selected = mist fill + dusk label. */
@Composable
private fun Chip(text: String, selected: Boolean = false, numeric: Boolean = false, onClick: () -> Unit) {
    val base = if (numeric) AppType.numeric else AppType.label
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(36.dp)
            .clip(Radius.pill)
            .background(if (selected) Ink.high else Bg.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md)
    ) {
        Text(text, style = base.copy(color = if (selected) Bg.base else Ink.body))
    }
}

@Composable
private fun EpisodeTile(number: Int, watched: Boolean, progress: Float, next: Boolean, downloaded: Boolean, downloadProgress: Float?, onClick: () -> Unit, modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(48.dp)
            .clip(Radius.md)
            .background(if (next) Accent.soft else Bg.raised)
            .then(if (next) Modifier.border(1.5.dp, Accent.main, Radius.md) else Modifier)
            .clickable(onClick = onClick)
    ) {
        Text(
            "$number",
            style = Tabular.copy(color = when {
                next -> Accent.text
                watched -> Ink.faint
                else -> Ink.body
            })
        )
        if (downloaded) DownloadedBadge(Modifier.align(Alignment.TopEnd).padding(Space.xs))
        if (downloadProgress != null) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(downloadProgress.coerceIn(0.02f, 1f)).height(2.dp).background(Accent.live))
        }
        if (watched && !downloaded) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Watched",
                tint = Ink.faint,
                modifier = Modifier.align(Alignment.TopEnd).padding(Space.xs).size(12.dp)
            )
        }
        if (progress > 0.02f && progress < Eps.DONE && !watched) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(Accent.main))
        }
    }
}

@Composable
private fun BoxScope.ActionBar(
    downloadLabel: String,
    downloadCaption: String?,
    downloadTrailing: String?,
    downloadProgress: Float?,
    downloadEnabled: Boolean,
    onDownload: () -> Unit,
    label: String,
    enabled: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val heartScale = remember { Animatable(1f) }
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Bg.base, Bg.base)))
            .navigationBarsPadding()
            .padding(start = Space.lg, end = Space.lg, top = Space.xl, bottom = Space.md)
    ) {
        PrimaryButton(
            text = label,
            onClick = onPlay,
            enabled = enabled,
            icon = Icons.Rounded.PlayArrow,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        if (downloadEnabled) DownloadStubButton(
            label = downloadLabel,
            caption = downloadCaption,
            onClick = onDownload,
            progress = downloadProgress,
            trailing = downloadTrailing,
            modifier = Modifier.weight(1f)
        ) else Spacer(Modifier.weight(1f))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(Radius.round)
                .background(if (isFavorite) Bg.overlay else Bg.raised)
                .clickable {
                    onFavorite()
                    scope.launch {
                        heartScale.animateTo(1.3f, tween(120))
                        heartScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                }
        ) {
            Icon(
                if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favourites" else "Add to favourites",
                tint = if (isFavorite) Ink.high else Ink.body,
                modifier = Modifier.scale(heartScale.value)
            )
        }
        }
    }
}

/** Page-shaped skeleton: backdrop + poster + title lines + episode tiles. */
@Composable
private fun DetailSkeleton() {
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .background(Bg.raised.copy(alpha = 0.5f))
                .statusBarsPadding()
                .padding(top = BarHeight + Space.lg, start = Space.lg, end = Space.lg, bottom = Space.lg)
        ) {
            LoadingShimmer(Modifier.width(120.dp).aspectRatio(2f / 3f).clip(Radius.md))
            Spacer(Modifier.width(Space.lg))
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                LoadingShimmer(Modifier.fillMaxWidth().height(28.dp).clip(Radius.sm))
                LoadingShimmer(Modifier.fillMaxWidth(0.6f).height(28.dp).clip(Radius.sm))
                LoadingShimmer(Modifier.width(80.dp).height(16.dp).clip(Radius.sm))
            }
        }
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            repeat(3) { LoadingShimmer(Modifier.fillMaxWidth(if (it == 2) 0.7f else 1f).height(16.dp).clip(Radius.sm)) }
            Spacer(Modifier.height(Space.xl))
            LoadingShimmer(Modifier.width(120.dp).height(20.dp).clip(Radius.sm))
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    repeat(TILES_PER_ROW) { LoadingShimmer(Modifier.weight(1f).height(48.dp).clip(Radius.md)) }
                }
            }
        }
    }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000 -> "${n / 1_000}K"
    else -> "$n"
}
