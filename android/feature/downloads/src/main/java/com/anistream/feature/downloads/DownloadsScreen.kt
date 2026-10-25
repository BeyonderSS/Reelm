package com.anistream.feature.downloads

import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.anistream.core.ui.components.EmptyState
import com.anistream.core.ui.components.LoadingShimmer
import com.anistream.core.ui.components.MarqueeTitle
import com.anistream.core.ui.components.TicketShape
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.DisplayHeavy
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import com.anistream.domain.model.DownloadItem
import com.anistream.domain.model.DownloadState
import java.util.Locale

/** "157 MB" / "1.2 GB" (binary units, matching the old MB math). */
private fun formatSize(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else "${mb.toLong()} MB"
}

private fun isMovie(d: DownloadItem) = d.episodeNumber == 0 && d.subtitle == null
private val SE = Regex("S(\\d+)\\D+E(\\d+)")
private fun seasonOf(d: DownloadItem) = SE.find(d.subtitle ?: "")?.groupValues?.get(1)?.toIntOrNull()
private fun order(d: DownloadItem) = SE.find(d.subtitle ?: "")?.let { it.groupValues[1].toInt() * 1000 + it.groupValues[2].toInt() } ?: 0
private val DisplayName = AppType.heading.copy(fontSize = 18.sp, lineHeight = 23.sp)

@Composable
fun DownloadsScreen(
    initialPlay: String? = null,
    showHeader: Boolean = true,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var playing by rememberSaveable { mutableStateOf(initialPlay) }
    playing?.let { LocalPlayerDialog(filePath = it, onDismiss = { playing = null }) }

    val downloads = uiState.downloads
    Column(Modifier.fillMaxSize().background(Bg.base)) {
        if (showHeader) MarqueeTitle("Downloads", "on this phone", Modifier.padding(start = Space.lg, end = Space.lg, top = Space.sm))
        if (downloads.isNotEmpty()) StorageBar(downloads, Modifier.padding(horizontal = Space.lg, vertical = Space.md))
        else Spacer(Modifier.height(Space.md))

        val state = when {
            uiState.isLoading -> 0
            uiState.error != null -> 1
            downloads.isEmpty() -> 2
            else -> 3
        }
        Crossfade(targetState = state, animationSpec = tween(250), label = "downloads_state") { s ->
            when (s) {
                0 -> Column(Modifier.padding(horizontal = Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    LoadingShimmer(Modifier.fillMaxWidth().height(6.dp).clip(Radius.pill))
                    repeat(3) { LoadingShimmer(Modifier.fillMaxWidth().height(150.dp).clip(Radius.lg)) }
                }

                1 -> EmptyState(
                    message = "Couldn't load downloads",
                    subtitle = uiState.error ?: "",
                    icon = Icons.Rounded.ErrorOutline
                )

                2 -> Column(
                    Modifier.fillMaxSize().padding(Space.xxl),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MarqueeTitle("Nothing on", "this phone yet", size = 30)
                    Text(
                        "Tap Download on any movie, series or anime to watch offline",
                        style = AppType.body, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.md)
                    )
                }

                else -> {
                    // movies stay single cards; everything else groups by title; both keep first-seen (newest first) order
                    val entries = remember(downloads) {
                        val groups = downloads.filterNot(::isMovie).groupBy { it.animeTitle }
                            .mapValues { (_, v) -> v.sortedWith(compareBy({ it.episodeNumber }, ::order)) }
                        val seen = HashSet<String>()
                        downloads.mapNotNull { d ->
                            if (isMovie(d)) listOf(d) else if (seen.add(d.animeTitle)) groups[d.animeTitle] else null
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = Space.lg, end = Space.lg, bottom = Space.xxxl),
                        verticalArrangement = Arrangement.spacedBy(Space.md)
                    ) {
                        items(entries.size, key = { i -> entries[i].first().let { if (isMovie(it)) "m:${it.episodeId}" else "g:${it.animeTitle}" } }) { i ->
                            val eps = entries[i]
                            if (isMovie(eps.first())) MovieCard(
                                eps.first(),
                                onCancel = { viewModel.cancelDownload(it) },
                                onDelete = { viewModel.deleteDownload(it) },
                                onPlay = { playing = it },
                                modifier = Modifier.animateItem()
                            ) else SeriesCard(
                                eps,
                                onCancel = { viewModel.cancelDownload(it) },
                                onDelete = { viewModel.deleteDownload(it) },
                                onPlay = { playing = it },
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
private fun StorageBar(downloads: List<DownloadItem>, modifier: Modifier = Modifier) {
    val reelm = downloads.sumOf { it.fileSizeBytes }
    val (total, free) = remember(reelm) {
        runCatching { StatFs(Environment.getDataDirectory().path).let { it.totalBytes to it.availableBytes } }.getOrDefault(0L to 0L)
    }
    val titles = downloads.map { if (isMovie(it)) "m${it.episodeId}" else it.animeTitle }.toSet().size
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(Radius.pill).background(Ink.hairline)) {
            if (total > 0) {
                Box(Modifier.fillMaxWidth(((total - free) / total.toFloat()).coerceIn(0f, 1f)).fillMaxHeight().background(Ink.ghost))
                Box(Modifier.fillMaxWidth((reelm / total.toFloat()).coerceIn(0.01f, 1f)).fillMaxHeight().background(Accent.main))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.sm), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Reelm ${formatSize(reelm)} · $titles title${if (titles == 1) "" else "s"}", style = AppType.caption)
            if (total > 0) Text("${formatSize(free)} free", style = AppType.caption)
        }
    }
}

/** Poster, or a tinted tile with the title's initial (never blank). */
@Composable
private fun Poster(url: String?, title: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Bg.overlay), contentAlignment = Alignment.Center) {
        Text(
            title.trim().take(1).uppercase(),
            style = AppType.title.copy(fontFamily = DisplayHeavy, color = Accent.live.copy(alpha = 0.5f))
        )
        if (url != null) AsyncImage(model = url, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun Progress(d: DownloadItem, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { d.progress },
            modifier = Modifier.weight(1f).height(4.dp).clip(Radius.pill),
            color = Accent.main,
            trackColor = Ink.ghost,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {}
        )
        Text(
            if (d.state == DownloadState.PENDING) "Queued" else "${(d.progress * 100).toInt()}%",
            style = AppType.numeric,
            color = Ink.body,
            modifier = Modifier.padding(start = Space.sm).width(44.dp)
        )
    }
}

private fun DownloadItem.active() = state == DownloadState.DOWNLOADING || state == DownloadState.PENDING
private fun DownloadItem.playable() = state == DownloadState.COMPLETED && filePath != null

@Composable
private fun MovieCard(d: DownloadItem, onCancel: (Long) -> Unit, onDelete: (Long) -> Unit, onPlay: (String) -> Unit, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete download?") },
        text = { Text("${d.subtitle ?: d.animeTitle} will be removed from this device.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete(d.episodeId) }) { Text("Delete", color = Accent.danger) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
    Row(
        modifier.fillMaxWidth().heightIn(min = 150.dp).height(IntrinsicSize.Min)
            .clip(TicketShape(vertical = true, atDp = 100.dp)).background(Bg.raised)
    ) {
        Poster(d.posterUrl, d.animeTitle, Modifier.width(100.dp).fillMaxHeight())
        Canvas(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 14.dp)) {
            drawLine(Ink.ghost, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
        }
        Column(Modifier.weight(1f).padding(start = Space.md, end = Space.sm, top = Space.md, bottom = Space.md)) {
            Text(d.animeTitle, style = DisplayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull("Movie", d.quality, d.fileSizeBytes.takeIf { it > 0 }?.let(::formatSize)).joinToString(" · "),
                style = AppType.caption, modifier = Modifier.padding(top = Space.xs)
            )
            Spacer(Modifier.weight(1f).heightIn(min = Space.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    d.active() -> {
                        Progress(d, Modifier.weight(1f))
                        IconButton(onClick = { onCancel(d.episodeId) }) { Icon(Icons.Rounded.Close, "Cancel download", tint = Ink.muted) }
                    }
                    else -> {
                        if (d.state == DownloadState.FAILED) Text(
                            "Failed, tap to remove", style = AppType.label, color = Accent.danger,
                            modifier = Modifier.weight(1f).clickable { onDelete(d.episodeId) }
                        ) else Row(
                            Modifier.height(40.dp).clip(Radius.pill).background(if (d.playable()) Accent.main else Ink.ghost)
                                .clickable(enabled = d.playable()) { onPlay(d.filePath!!) }.padding(horizontal = Space.lg),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = Accent.on, modifier = Modifier.size(20.dp))
                            Text("Play", style = AppType.label, color = Accent.on, modifier = Modifier.padding(start = Space.xs))
                        }
                        if (d.state != DownloadState.FAILED) Spacer(Modifier.weight(1f))
                        IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.Delete, "Delete download", tint = Ink.muted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesCard(eps: List<DownloadItem>, onCancel: (Long) -> Unit, onDelete: (Long) -> Unit, onPlay: (String) -> Unit, modifier: Modifier = Modifier) {
    val title = eps.first().animeTitle
    val poster = eps.firstNotNullOfOrNull { it.posterUrl }
    val seasons = eps.mapNotNull(::seasonOf).distinct()
    val n = eps.size
    val sub = listOfNotNull(
        when { seasons.size == 1 -> "Season ${seasons[0]}"; seasons.size > 1 -> "${seasons.size} seasons"; else -> null },
        "$n episode${if (n == 1) "" else "s"}",
        formatSize(eps.sumOf { it.fileSizeBytes })
    ).joinToString(" · ")
    Column(modifier.fillMaxWidth().clip(Radius.lg).background(Bg.raised)) {
        Row(Modifier.fillMaxWidth().background(Accent.live.copy(alpha = 0.06f)).padding(Space.md), verticalAlignment = Alignment.CenterVertically) {
            Poster(poster, title, Modifier.size(width = 52.dp, height = 78.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f).padding(start = Space.md)) {
                Text(title, style = DisplayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(sub, style = AppType.caption, modifier = Modifier.padding(top = Space.xs))
            }
        }
        eps.forEach { d ->
            HorizontalDivider(color = Ink.hairline)
            SwipeToDelete(onDelete = { onDelete(d.episodeId) }) {
                EpisodeRow(d, poster, onCancel = { onCancel(d.episodeId) }, onDelete = { onDelete(d.episodeId) }, onPlay = { onPlay(d.filePath!!) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            val delete = it == SwipeToDismissBoxValue.EndToStart
            if (delete) onDelete()
            delete
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = modifier,
        backgroundContent = {
            val color by animateColorAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) Accent.danger else Bg.raised,
                tween(250),
                label = "swipe_color"
            )
            Box(
                Modifier.fillMaxSize().background(color).padding(horizontal = Space.xl),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = Ink.high)
            }
        }
    ) { content() }
}

@Composable
private fun EpisodeRow(d: DownloadItem, poster: String?, onCancel: () -> Unit, onDelete: () -> Unit, onPlay: () -> Unit) {
    val failed = d.state == DownloadState.FAILED
    Row(
        Modifier.fillMaxWidth().background(Bg.raised)
            .clickable(enabled = d.playable() || failed) { if (failed) onDelete() else onPlay() }
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 88.dp, height = 50.dp).clip(Radius.sm), contentAlignment = Alignment.Center) {
            Poster(d.stillUrl ?: poster, d.animeTitle, Modifier.fillMaxSize())
            if (d.playable()) Box(Modifier.size(28.dp).background(Color(0x990A0C12), Radius.round), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Ink.high, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = Space.md)) {
            Text(listOfNotNull(if (d.episodeNumber > 0) "E${d.episodeNumber}" else d.subtitle ?: d.animeTitle, d.episodeTitle).joinToString(" · "), style = AppType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                failed -> Text("Failed, tap to remove", style = AppType.caption, color = Accent.danger)
                d.active() -> Progress(d, Modifier.padding(top = Space.xs))
                else -> Text(
                    listOfNotNull(d.quality, d.fileSizeBytes.takeIf { it > 0 }?.let(::formatSize)).joinToString(" · "),
                    style = AppType.numeric
                )
            }
        }
        if (d.active()) IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel download", tint = Ink.muted) }
    }
}
