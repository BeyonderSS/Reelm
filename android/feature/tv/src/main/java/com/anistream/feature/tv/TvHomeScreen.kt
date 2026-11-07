package com.anistream.feature.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.DisplayHeavy
import com.anistream.core.ui.theme.Ink
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.tv.material3.Text
import com.anistream.domain.model.AnimeSearchResult
import com.anistream.domain.model.MediaItem
import com.anistream.domain.model.MediaType
import com.anistream.domain.model.WatchHistory
import com.anistream.feature.home.HomeViewModel
import com.anistream.feature.movies.MoviesHomeViewModel
import com.anistream.feature.movies.asMediaItem
import com.anistream.domain.model.forYouRows
import com.anistream.domain.ContentPrefs

enum class HomeFilter { ALL, MEDIA, ANIME }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvHomeScreen(filter: HomeFilter, homeVm: HomeViewModel, moviesVm: MoviesHomeViewModel, nav: NavController) {
    val anime by homeVm.uiState.collectAsStateWithLifecycle()
    val media by moviesVm.uiState.collectAsStateWithLifecycle()
    val hidden by ContentPrefs.hidden.collectAsStateWithLifecycle()

    // re-entering this destination (back from detail/player) picks up fresh progress
    LaunchedEffect(Unit) {
        homeVm.refreshContinueWatching()
        moviesVm.refreshContinueWatching()
    }

    val lowEnd = LocalLowEnd.current
    // ponytail: rows rebuild only when the data changes, not on every D-pad move
    var removing by remember { mutableStateOf<WatchHistory?>(null) }
    val rows = remember(anime, media, filter, lowEnd, hidden) { buildRows(filter, anime, media, lowEnd, nav) { removing = it } }
    removing?.let { h ->
        TvConfirm("Remove ${h.animeTitle} from history?", "Remove", {
            (if (h.kind == "media") moviesVm::removeHistory else homeVm::removeHistory)(h.animeSlug); removing = null
        }, { removing = null })
    }

    // Focus is written here but read only by the backdrop/hero composables below, so a D-pad move
    // recomposes those two and the two cards whose focus changed, never the whole screen.
    val focused = remember { mutableStateOf<TvItem?>(null) }
    val hero = remember { mutableStateOf<TvItem?>(null) }
    LaunchedEffect(lowEnd) {
        // low-end: change hero/backdrop only once focus has rested 300 ms; further moves cancel the load
        snapshotFlow { focused.value }.collectLatest { if (lowEnd && hero.value != null) delay(300); hero.value = it }
    }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val restore = remember { FocusRequester() }
    val heroReq = remember { FocusRequester() }
    // read without observing: only matters when the screen is (re)built, and writing it on each move must not recompose this
    val last = Snapshot.withoutReadObservation { lastFocused }
    val restoreKey = last?.takeIf { k -> rows.any { r -> r.items.any { it.key == k } } } ?: rows.firstOrNull()?.items?.firstOrNull()?.key
    val hasRows = rows.isNotEmpty()

    // The focused row snaps to the top of the rows area (never a half-cut row under the hero):
    // the column's own bring-into-view is turned off and we scroll by row index instead.
    val listState = rememberLazyListState()
    val focusedRow = remember { mutableIntStateOf(-1) }
    // everything but Continue watching
    val pool = remember(rows) { rows.filterNot { it.wide }.flatMap { it.items } }
    val lead = if (pool.isNotEmpty()) 1 else 0
    LaunchedEffect(lead) {
        snapshotFlow { focusedRow.intValue }.collectLatest { i ->
            if (i >= 0) listState.animateScrollToItem(if (i == 0) 0 else i + lead)
        }
    }
    val rowSpec = LocalBringIntoViewSpec.current
    // Continue watching arrives after the catalog rows; LazyColumn would keep the old first row in view (by key) and
    // leave the strip scrolled off above, so go back to the top while focus is still up there
    val firstRow = rows.firstOrNull()?.title
    // (and focus follows: the restore target is recomputed for the new first row)
    LaunchedEffect(hasRows, firstRow) {
        if (hasRows && focusedRow.intValue <= 0) {
            listState.scrollToItem(0)
            withFrameNanos { }
            runCatching { restore.requestFocus() }
        }
    }

    // the hero stands tall over Continue watching (as in the mockup) and gives the lower rows room once focus goes down
    val tall = remember(rows) { derivedStateOf { focusedRow.intValue <= 0 && rows.firstOrNull()?.wide == true } }

    Box(Modifier.fillMaxSize()) {
        HomeBackdrop(hero)
        Column(Modifier.fillMaxSize()) {
            HomeHero(hero, filter, tall, heroReq)
            when {
                rows.isEmpty() && (anime.isLoading || media.isLoading) -> TvMessage("Loading…")
                rows.isEmpty() -> TvMessage(anime.error ?: media.error ?: "Nothing here yet") {
                    TvPill("Retry", onClick = { homeVm.loadContent(); moviesVm.load() })
                }
                else -> CompositionLocalProvider(LocalBringIntoViewSpec provides NoVerticalBringIntoView) {
                    val rowItem: @Composable (Int, TvRow) -> Unit = { i, row ->
                        // rows keep the default horizontal scrolling
                        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                            val onF: (TvItem) -> Unit = {
                                focused.value = it; lastFocused = it.key; focusedRow.intValue = i
                            }
                            if (row.wide) TvStripRow(row, restoreKey, restore, onF, up = heroReq) else TvCardRow(row, restoreKey, restore, onF)
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.clipToBounds(),
                        contentPadding = PaddingValues(bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // first row right under the hero, "Surprise me" below it (row i >= 1 sits at list index i + lead)
                        item(key = rows[0].title) { rowItem(0, rows[0]) }
                        if (pool.isNotEmpty()) item(key = "surprise") {
                            TvPill("Surprise me", onClick = { pool.random().onClick() }, modifier = Modifier.padding(start = Overscan, top = (12 * uiScale()).dp))
                        }
                        itemsIndexed(rows.drop(1), key = { _, it -> it.title }) { i, row -> rowItem(i + 1, row) }
                    }
                }
            }
        }
    }
}

/** The mockups' night: the TV frame behind every marquee screen. */
internal val Night = Color(0xFF0B0C11)

/** Elliptical glow (70 x 45 % of the width) up and right, tinted with the focused poster's colour (static dusk violet on low-end). */
@Composable
private fun HomeBackdrop(hero: State<TvItem?>) {
    val lowEnd = LocalLowEnd.current
    val c = if (lowEnd) null else rememberPosterColor(hero.value?.imageUrl).value
    val glow = lerp(Night, c ?: DuskViolet, 0.5f)
    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(Night)
        val center = Offset(size.width * 0.75f, size.height * 0.25f)
        scale(1f, 45f / 70f, pivot = center) {
            drawRect(Brush.radialGradient(0f to glow, 0.7f to Night, center = center, radius = size.width * 0.7f), Offset(0f, -size.height), Size(size.width, size.height * 3))
        }
    })
}

/** Marquee hero: giant title (solid name, outlined subtitle), tilted whole poster, chips and the amber Resume pill. */
@Composable
private fun HomeHero(hero: State<TvItem?>, filter: HomeFilter, tall: State<Boolean>, focus: FocusRequester) {
    val s = uiScale()
    fun d(n: Float) = (n * s).dp
    val h = hero.value
    val stroke = with(LocalDensity.current) { d(1.15f).toPx() }
    Box(Modifier.fillMaxWidth().height(d(if (tall.value) 330f else 250f))) {
        if (h?.imageUrl != null) AsyncImage(
            h.imageUrl, null, contentScale = ContentScale.Crop,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = d(48f), end = d(57.6f)).width(d(134.4f)).aspectRatio(2f / 3f)
                .graphicsLayer { rotationZ = -3f; shadowElevation = d(18f).toPx(); shape = RoundedCornerShape(d(7.7f)); clip = true }
                .background(Bg.raised)
        )
        val title = h?.title ?: when (filter) { HomeFilter.ALL -> "Reelm"; HomeFilter.MEDIA -> "Movies & TV"; HomeFilter.ANIME -> "Anime" }
        val cut = listOf(":", " - ").map { title.indexOf(it) }.filter { it > 0 }.minOrNull() ?: title.length
        val rest = title.substring(cut).trimStart(':', '-', ' ')
        val big = title.length <= 40
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Ink.high)) { append(title.substring(0, cut).trim()) }
                if (rest.isNotEmpty()) withStyle(SpanStyle(color = Color(0x80ECEAF2), drawStyle = Stroke(stroke))) { append(" $rest") }
            },
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Overscan, top = d(17f)).width(d(662f)),
            style = TvType.display.copy(
                fontFamily = DisplayHeavy, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.015).em, lineBreak = LineBreak.Heading,
                fontSize = ((if (big) 73f else 56f) * s).sp, lineHeight = ((if (big) 62.7f else 48.2f) * s).sp
            ),
        )
        if (h != null) Column(Modifier.align(Alignment.BottomStart).padding(start = Overscan, bottom = d(30f)), verticalArrangement = Arrangement.spacedBy(d(9.6f))) {
            Row(horizontalArrangement = Arrangement.spacedBy(d(5.8f))) {
                h.meta.distinct().filter { it.isNotBlank() }.take(5).forEach {
                    Text(it, maxLines = 1, style = TvType.body.copy(fontSize = (10.1f * s).sp, lineHeight = (14f * s).sp, color = Color(0xFFD9D6E2)),
                        modifier = Modifier.background(Color.White.copy(alpha = 0.09f), RoundedCornerShape(50)).padding(horizontal = d(8.6f), vertical = d(2.9f)))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(d(7.7f)), verticalAlignment = Alignment.CenterVertically) {
                MarqueeBtn(h.resumeLabel ?: "Play", h.onClick, primary = true, modifier = Modifier.focusRequester(focus))
                h.onDetails?.let { MarqueeBtn("Details", it) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal val NoVerticalBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

private fun episodeLine(h: WatchHistory) = when {
    h.kind == "media" && h.lastEpisodeWatched == 0 -> "Movie"
    h.kind == "media" -> "Season ${h.season.coerceAtLeast(1)}, episode ${h.lastEpisodeWatched}"
    h.totalEpisodes > 0 -> "Episode ${h.lastEpisodeWatched} of ${h.totalEpisodes}"
    else -> "Episode ${h.lastEpisodeWatched}"
}

private fun buildRows(
    filter: HomeFilter,
    anime: com.anistream.feature.home.HomeUiState,
    media: com.anistream.feature.movies.MoviesHomeUiState,
    lowEnd: Boolean,
    nav: NavController,
    onRemove: (WatchHistory) -> Unit
): List<TvRow> {
    fun animeItem(row: String, a: AnimeSearchResult) = TvItem(
        key = "$row|${a.slug}", title = a.title, imageUrl = a.posterUrl,
        meta = listOfNotNull(a.type, a.year?.toString(), scoreLabel(a.score)),
        onClick = { nav.navigate(TvRoutes.anime(a.slug)) }
    )

    fun mediaItem(row: String, m: MediaItem) = TvItem(
        key = "$row|${m.id}", title = m.title, imageUrl = m.posterUrl,
        meta = listOfNotNull(if (m.type == MediaType.SERIES) "Series" else "Movie", m.year?.toString(), scoreLabel(m.rating)) + m.genres.take(2),
        onClick = { nav.navigate(TvRoutes.movie(m.id)) }
    )

    fun historyItem(h: WatchHistory) = TvItem(
        key = "cw|${h.animeSlug}", title = h.animeTitle, imageUrl = h.posterUrl,
        subtitle = h.subtitle ?: h.lastEpisodeWatched.takeIf { it > 0 }?.let { "Episode $it" },
        resumeLabel = h.lastEpisodeWatched.takeIf { it > 0 }?.let { "Resume episode ${if (h.watchProgress >= 0.9f && !h.isComplete) h.nextEpisode else it}" } ?: "Resume",
        onDetails = { nav.navigate(if (h.kind == "media") TvRoutes.movie(h.detailId) else TvRoutes.anime(h.animeSlug)) },
        episodeLine = episodeLine(h),
        meta = listOfNotNull(if (h.kind == "media") (if (h.season > 0 || h.lastEpisodeWatched > 0) "Series" else "Movie") else "Anime", episodeLine(h)),
        progress = h.watchProgress,
        minutesLeft = h.minutesLeft,
        onLongClick = { onRemove(h) },
        // anime resumes straight into the player; media goes through detail, where Resume is the first focus
        onClick = {
            if (h.kind == "media") nav.navigate(TvRoutes.movie(h.detailId))
            else nav.navigate(TvRoutes.player(h.animeSlug, if (h.watchProgress >= 0.9f && !h.isComplete) h.nextEpisode else h.lastEpisodeWatched.coerceAtLeast(1)))
        }
    )

    // "What you watch": a hidden kind drops out of every row
    fun keep(m: MediaItem) = ContentPrefs.shows(if (m.type == MediaType.SERIES) ContentPrefs.SERIES else ContentPrefs.MOVIES)
    fun keep(h: WatchHistory) = ContentPrefs.shows(
        if (h.kind != "media") ContentPrefs.ANIME else if (h.season > 0 || h.lastEpisodeWatched > 0) ContentPrefs.SERIES else ContentPrefs.MOVIES
    )
    val showAnime = ContentPrefs.shows(ContentPrefs.ANIME)
    val c = anime.content
    val continueRow = when (filter) {
        HomeFilter.ALL -> anime.continueWatching + media.continueWatching
        HomeFilter.MEDIA -> media.continueWatching
        HomeFilter.ANIME -> anime.continueWatching
    }.filter(::keep).sortedByDescending { it.lastWatchedTimestamp }.distinctBy { it.animeSlug }.map(::historyItem) // anime list already holds media rows too

    fun animeRow(title: String, list: List<AnimeSearchResult>) = TvRow(title, list.distinctBy { it.slug }.map { animeItem(title, it) })
    val mediaRows = media.rows.map { r -> TvRow(r.title, r.items.filter(::keep).distinctBy { it.id }.map { mediaItem(r.title, it) }) }
    val animeRows = listOf(
        animeRow("Top airing anime", c.topAiring),
        animeRow("Most popular anime", c.popular),
        animeRow("New episodes", c.recentEpisodes),
        animeRow("Fan favourites", c.favorites),
        animeRow("Completed anime", c.completed)
    )
    // "For you" rows exist only once there is history; TMDB rows carry the attribution TMDB's terms ask for
    val mediaPicks = media.picks.forYouRows().mapIndexed { i, (t, l) ->
        TvRow(t, l.map { it.asMediaItem() }.filter(::keep).map { mediaItem(t, it) }, caption = "Data from TMDB".takeIf { i == 0 })
    }
    val animePicks = anime.picks.forYouRows().map { (t, l) ->
        val title = if (filter == HomeFilter.ALL && t == "Top picks for you") "Top anime picks for you" else t
        animeRow(title, l.map { AnimeSearchResult(slug = it.id, title = it.title, posterUrl = it.posterUrl, score = it.rating, year = it.year) })
    }
    val animeRowsAll = if (showAnime) animeRows else emptyList()
    return buildList {
        add(TvRow("Continue watching", continueRow, wide = true))
        when (filter) {
            HomeFilter.ALL -> { addAll(mediaPicks); addAll(if (showAnime) animePicks else emptyList()); if (showAnime) add(animeRow("Trending anime", c.trending)); addAll(mediaRows); addAll(animeRowsAll) }
            HomeFilter.MEDIA -> { addAll(mediaPicks); addAll(mediaRows) }
            HomeFilter.ANIME -> if (showAnime) { addAll(animePicks); add(animeRow("Trending anime", c.trending)); addAll(animeRows) }
        }
    }.filter { it.items.isNotEmpty() }.distinctBy { it.title }
        // low-end: fewer rows keeps composition and image memory down
        .let { if (lowEnd) it.take(LOW_END_ROWS) else it }
}

private const val LOW_END_ROWS = 8
