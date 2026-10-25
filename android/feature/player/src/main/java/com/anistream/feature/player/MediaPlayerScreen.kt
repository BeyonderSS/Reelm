package com.anistream.feature.player

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import com.anistream.feature.party.PartyOverlay
import com.anistream.feature.party.WatchTogetherSection
import com.anistream.feature.party.mediaPayload

/**
 * Movies & TV / addon streams / Live TV player (route "media_player").
 * The caller sets [com.anistream.domain.repository.NowPlaying.request] before navigating here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPlayerScreen(
    onBack: () -> Unit,
    /** (imdbId, season, episode) */
    onWebPlayer: (String, Int, Int) -> Unit = { _, _, _ -> },
    viewModel: MediaPlayerViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val (fullscreen, setFullscreen) = rememberFullscreen(viewModel::onStop)
    val request = ui.request
    val tweaks = rememberPlayerTweaks(request?.historyKey ?: request?.title ?: "media")
    val pip = rememberPip(viewModel.player)

    LaunchedEffect(ui.webImdbId) {
        val tt = ui.webImdbId ?: return@LaunchedEffect
        viewModel.webPlayerOpened()
        onWebPlayer(tt, request?.season ?: 0, request?.episode ?: 0)
    }

    LaunchedEffect(ui.message) {
        val msg = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.messageShown()
    }

    val title = listOfNotNull(request?.title, request?.subtitle).joinToString(", ")
    val video: @Composable (Modifier) -> Unit = { m ->
        VideoPlayer(
            player = viewModel.player,
            title = title,
            isLoading = ui.isLoading,
            error = ui.error,
            fullscreen = fullscreen,
            onBack = { if (fullscreen) setFullscreen(false) else onBack() },
            onToggleFullscreen = { setFullscreen(!fullscreen) },
            onSettings = { showSettings = true },
            // no request at all: Retry can't help, so it leaves
            onRetry = if (request == null) onBack else viewModel::retry,
            live = ui.isLive,
            tweaks = tweaks,
            episodic = (request?.episode ?: 0) > 0,
            pip = pip,
            onWebPlayer = if (ui.canWebPlay) viewModel::openWebPlayer else null,
            endOverlay = if (!ui.ended || request == null) null else {
                {
                    if (ui.hasNextEpisode) {
                        NextEpisodeCard(request.posterUrl, "Episode ${request.episode + 1}", "${request.title}, season ${request.season}", viewModel::nextEpisode, viewModel::dismissEnd)
                    } else {
                        FinishedCard(request.title, onBack)
                    }
                }
            },
            modifier = m
        )
    }

    Box(Modifier.fillMaxSize().background(if (fullscreen || pip) Color.Black else Bg.base)) {
        if (fullscreen || pip) {
            video(Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.background(Color.Black).statusBarsPadding()) {
                    video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(Space.lg)
                ) {
                    Text(request?.title ?: "", style = AppType.heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = request?.subtitle
                    if (sub != null) {
                        Spacer(Modifier.height(Space.xs))
                        Text(sub, style = AppType.numeric)
                    }
                    Spacer(Modifier.height(Space.lg))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        val stream = ui.stream
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .height(48.dp)
                                .clip(Radius.pill)
                                .background(Bg.raised)
                                .clickable(enabled = stream != null) { showSettings = true }
                                .padding(start = Space.md, end = Space.sm)
                        ) {
                            Text(
                                stream?.label() ?: "No source",
                                style = AppType.label.copy(color = Ink.high, fontFeatureSettings = "tnum"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Change source", tint = Ink.body, modifier = Modifier.size(20.dp))
                        }
                        if (!ui.isLive) {
                            IconButton(
                                onClick = viewModel::download,
                                enabled = request != null && !ui.downloadStarted,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    if (ui.downloadStarted) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                                    contentDescription = if (ui.downloadStarted) "Downloading" else "Download",
                                    tint = if (ui.downloadStarted) Accent.live else Ink.body
                                )
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        PartyOverlay(viewModel.player, Modifier.align(Alignment.TopEnd), payload = request?.takeIf { !ui.isLive }?.let(::mediaPayload))
    }

    if (showSettings && request != null) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = Bg.raised, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg)
                    .padding(bottom = Space.xl)
            ) {
                Text("Source", style = AppType.heading)
                request.streams.withIndex().groupBy { it.value.sourceName }.forEach { (source, streams) ->
                    Spacer(Modifier.height(Space.md))
                    Text(source, style = AppType.label.copy(color = Ink.muted), modifier = Modifier.padding(horizontal = Space.sm))
                    streams.forEach { (i, s) ->
                        SheetOption(s.detailLabel().ifBlank { source }, selected = i == ui.streamIndex, caption = ui.autoNote.takeIf { i == ui.autoIndex }) {
                            viewModel.selectStream(i)
                            showSettings = false
                        }
                    }
                }
                if (ui.canWebPlay) {
                    Spacer(Modifier.height(Space.md))
                    SheetOption("Web player", selected = false, caption = "Last resort · opens the title in a browser player") {
                        showSettings = false
                        viewModel.openWebPlayer()
                    }
                }
                val subs = ui.stream?.subtitles.orEmpty()
                if (subs.isNotEmpty()) {
                    Spacer(Modifier.height(Space.xl))
                    Text("Subtitles", style = AppType.heading)
                    Spacer(Modifier.height(Space.sm))
                    SheetOption("Off", selected = ui.subtitleIndex == -1) { viewModel.selectSubtitle(-1) }
                    subs.forEachIndexed { i, t ->
                        SheetOption(t.label.ifBlank { t.languageCode }, selected = i == ui.subtitleIndex) {
                            viewModel.selectSubtitle(i)
                        }
                    }
                }
                PlayerTweaksSection(viewModel.player, tweaks, live = ui.isLive)
                if (!ui.isLive) WatchTogetherSection(mediaPayload(request))
                ui.request?.takeIf { !it.isLive && it.historyKey != null }?.let { r ->
                    com.anistream.core.account.ui.ContinueOnTvSection(
                        history = { com.anistream.domain.model.WatchHistory(r.historyKey ?: "", r.title, posterUrl = r.posterUrl, totalEpisodes = r.seasonEpisodeCount, lastEpisodeWatched = r.episode, watchProgress = viewModel.player.progressFraction(), kind = "media", season = r.season, mediaId = r.mediaId, subtitle = r.subtitle, durationMs = viewModel.player.duration.coerceAtLeast(0)) },
                        positionMs = { viewModel.player.currentPosition },
                        onSent = { showSettings = false }
                    )
                }
            }
        }
    }
}
