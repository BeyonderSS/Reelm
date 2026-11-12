package com.anistream.feature.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.anistream.core.media.GuideEp
import com.anistream.core.ui.theme.Accent
import com.anistream.domain.model.Eps
import com.anistream.domain.model.MediaType
import com.anistream.feature.anime.AnimeDetailViewModel
import com.anistream.feature.movies.DetailEvent
import com.anistream.feature.movies.MovieDetailViewModel
import com.anistream.feature.movies.Resume
import kotlinx.coroutines.delay

/** Horizontal row of pills with per-row focus restoration; [initial] is composed on entry so it can take focus. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun <T> PillRow(
    title: String?,
    items: List<T>,
    label: (T) -> String,
    selected: (T) -> Boolean,
    onClick: (T) -> Unit,
    initial: Int = 0,
    firstFocus: FocusRequester? = null,
    inset: androidx.compose.ui.unit.Dp = Overscan
) {
    Column {
        if (title != null) Text(title, style = TvType.title, modifier = Modifier.padding(start = inset, bottom = 8.dp))
        val state = rememberLazyListState(initialFirstVisibleItemIndex = initial.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        LazyRow(
            state = state,
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = inset, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items.size) { i ->
                val it = items[i]
                TvPill(
                    label(it), onClick = { onClick(it) }, selected = selected(it),
                    modifier = if (i == initial && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
        }
    }
}

/** "Crime, drama, mystery": one chip for the genres. */
private fun genreLine(g: List<String>) = g.take(3).joinToString(", ").lowercase().replaceFirstChar { it.uppercase() }.ifEmpty { null }

@Composable
private fun RequestOnce(key: Any, requester: FocusRequester) {
    LaunchedEffect(key) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

@Composable
fun TvAnimeDetailScreen(
    onPlay: (slug: String, episode: Int) -> Unit, onOpen: (slug: String) -> Unit,
    vm: AnimeDetailViewModel = hiltViewModel(), guideVm: EpisodeGuideViewModel = hiltViewModel()
) {
    val s by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshHistory() }
    val a = s.anime
    if (a == null) {
        if (s.isLoading) TvMessage("Loading…") else TvMessage(s.error ?: "Couldn't load this title") { TvPill("Retry", vm::loadAnimeDetail) }
        return
    }
    val episodes = s.episodes
    val h = s.history
    val resumeEp = when {
        h == null || h.lastEpisodeWatched == 0 -> episodes.firstOrNull()?.number
        h.watchProgress >= 0.9f -> episodes.firstOrNull { it.number > h.lastEpisodeWatched }?.number ?: h.lastEpisodeWatched
        else -> h.lastEpisodeWatched
    }
    val playFocus = remember { FocusRequester() }
    RequestOnce(a.slug, playFocus)
    // Kitsu names and stills; empty (fallback to numbered stubs) until/unless they load
    val guide by produceState(emptyList<GuideEp>(), a.slug, episodes.size) {
        value = guideVm.guide.anime(a.title, a.titleJapanese, a.year, episodes.size.takeIf { it > 0 } ?: a.episodes ?: 20)
    }
    val byNumber = guide.associateBy { it.n }
    fun progress(n: Int) = h?.eps?.get("e$n")?.p ?: 0f
    val stubs = episodes.map { e ->
        val g = byNumber[e.number]
        val p = progress(e.number)
        Stub(
            e.number, (g?.title ?: e.title ?: "Episode ${e.number}") + if (e.filler) " (filler)" else "", g?.still, g?.runtime, g?.overview, p,
            done = p >= Eps.DONE || e.number < (h?.lastEpisodeWatched ?: 0)
        )
    }

    MarqueeDetail(
        wash = a.bannerUrl ?: a.posterUrl, poster = a.posterUrl, title = a.title,
        meta = listOfNotNull(a.type, a.year?.toString(), a.status, scoreLabel(a.score), genreLine(a.genres.map { it.name })),
        synopsis = a.synopsis, note = s.episodesError,
        actions = {
            MarqueeBtn(
                text = when {
                    resumeEp == null -> "No episodes"
                    h != null && h.lastEpisodeWatched > 0 -> "Resume E$resumeEp"
                    else -> "Play E$resumeEp"
                },
                sub = resumeEp?.let { leftLabel(byNumber[it]?.runtime, progress(it)) },
                onClick = { resumeEp?.let { onPlay(vm.slug, it) } }, enabled = resumeEp != null, primary = true,
                modifier = Modifier.focusRequester(playFocus)
            )
            FavouriteBtn(s.isFavorite, vm::toggleFavorite)
        },
        seasons = emptyList(), season = 0, onSeason = {},
        seasonNote = (a.episodes ?: episodes.size).takeIf { it > 0 }?.let { "$it episodes" },
        stubs = stubs, initial = episodes.indexOfFirst { it.number == resumeEp }.coerceAtLeast(0), onStub = { onPlay(vm.slug, it) },
        below = if (a.recommendations.isEmpty()) null else ({
            val row = TvRow("More like this", a.recommendations.distinctBy { it.slug }.map { r ->
                TvItem(key = r.slug, title = r.title, imageUrl = r.posterUrl, onClick = { onOpen(r.slug) })
            })
            TvCardRow(row, restoreKey = null, restoreRequester = remember { FocusRequester() }, onFocused = {}, modifier = Modifier.padding(bottom = 40.dp))
        })
    )
}

@Composable
fun TvMovieDetailScreen(
    onPlayer: () -> Unit,
    /** (imdbId, season, episode) */
    onWebPlayer: (String, Int, Int) -> Unit,
    /** another title's id ("More like this") */
    onOpen: (String) -> Unit = {},
    vm: MovieDetailViewModel = hiltViewModel(),
    guideVm: EpisodeGuideViewModel = hiltViewModel()
) {
    val s by vm.uiState.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.refreshHistory()
        vm.events.collect { e ->
            when (e) {
                DetailEvent.OpenPlayer -> onPlayer()
                is DetailEvent.OpenWebPlayer -> onWebPlayer(e.imdbId, e.season, e.episode)
                is DetailEvent.Message -> message = e.text
            }
        }
    }
    LaunchedEffect(message) { if (message != null) { delay(4000); message = null } }
    val d = s.detail
    if (d == null) {
        if (s.isLoading) TvMessage("Loading…") else TvMessage(s.error ?: "Couldn't load this title") { TvPill("Retry", vm::load) }
        return
    }
    // Play on TV from a phone: start the sent episode right away
    LaunchedEffect(d.id) { com.anistream.core.account.HandoffHint.takeAutoplay()?.let { (sn, ep) -> if (d.playable) vm.play(sn, ep) } }
    val series = d.type == MediaType.SERIES
    val r = s.resume
    val firstSeason = d.seasons.firstOrNull()?.number ?: 1
    val playFocus = remember { FocusRequester() }
    RequestOnce(d.id, playFocus)
    val season = s.selectedSeason
    // TMDB stills, names and runtimes for the selected season; empty falls back to numbered stubs
    val guide by produceState(emptyList<GuideEp>(), d.id, season) {
        value = if (series) guideVm.guide.season(d.id, d.title, d.year, season) else emptyList()
    }
    val byNumber = guide.associateBy { it.n }
    fun progress(sn: Int, n: Int) = s.eps["s${sn}e$n"]?.p ?: 0f
    val count = (d.seasons.firstOrNull { it.number == season }?.episodeCount ?: 0).takeIf { it > 0 } ?: guide.size
    val resumeHere = r?.takeIf { it.season == season }?.episode ?: 0
    val stubs = if (series) (1..count).map { n ->
        val g = byNumber[n]
        val p = progress(season, n)
        Stub(n, g?.title ?: "Episode $n", g?.still, g?.runtime, g?.overview, p, done = p >= Eps.DONE || n < resumeHere)
    } else emptyList()
    val play = { sn: Int, n: Int -> if (d.playable) vm.play(sn, n) else vm.openWebPlayer(Resume(sn, n)) }

    MarqueeDetail(
        wash = d.backdropUrl ?: d.posterUrl, poster = d.posterUrl, title = d.title,
        meta = listOfNotNull(if (series) "Series" else "Movie", d.year?.toString(), d.runtime, scoreLabel(d.rating), genreLine(d.genres)),
        synopsis = d.description,
        note = message ?: if (!d.playable) "Not available to stream in the app" else null,
        actions = {
            val web = s.webFallback
            if (!d.playable && web != null) {
                MarqueeBtn("Watch in web player", { vm.openWebPlayer(web) }, primary = true, modifier = Modifier.focusRequester(playFocus))
            } else MarqueeBtn(
                text = when {
                    s.resolving -> "Finding sources…"
                    r != null && r.episode > 0 -> "Resume S${r.season} E${r.episode}"
                    r != null -> "Resume"
                    series -> "Play S$firstSeason E1"
                    else -> "Play"
                },
                sub = if (r != null && r.season == season) leftLabel(byNumber[r.episode]?.runtime, progress(r.season, r.episode)) else null,
                onClick = { if (r != null) vm.play(r.season, r.episode) else if (series) vm.play(firstSeason, 1) else vm.play(0, 0) },
                primary = true, modifier = Modifier.focusRequester(playFocus)
            )
            // streams came back empty for a playable title: offer the web player next to Play
            if (d.playable && web != null) MarqueeBtn("Web player", { vm.openWebPlayer(web) })
            FavouriteBtn(s.isFavorite, vm::toggleFavorite)
            if (d.audioVariants.size > 1) AudioSegments(d.audioVariants) { vm.selectVariant(it.id) }
        },
        seasons = if (series) d.seasons.map { it.number } else emptyList(), season = season, onSeason = vm::selectSeason,
        seasonNote = if (series && count > 0) "Season $season, $count episodes" else null,
        stubs = stubs, initial = (resumeHere - 1).coerceAtLeast(0), onStub = { play(season, it) },
        below = if (s.similar.isEmpty()) null else ({
            val row = TvRow("More like this", s.similar.map { m ->
                TvItem(key = m.id, title = m.title, imageUrl = m.posterUrl, onClick = { onOpen(m.id) })
            })
            TvCardRow(row, restoreKey = null, restoreRequester = remember { FocusRequester() }, onFocused = {}, modifier = Modifier.padding(bottom = 40.dp))
        })
    )
}
