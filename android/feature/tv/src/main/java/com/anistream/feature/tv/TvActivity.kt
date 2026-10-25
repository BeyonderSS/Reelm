package com.anistream.feature.tv

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Border
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.anistream.core.ui.theme.Radius
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.anistream.feature.home.HomeViewModel
import com.anistream.feature.movies.MoviesHomeViewModel
import com.anistream.feature.movies.isMediaKey
import com.anistream.feature.party.PartyDestination
import com.anistream.feature.party.PartyViewModel
import com.anistream.feature.player.WebEmbedPlayer
import dagger.hilt.android.AndroidEntryPoint

/** Launcher activity of the `tv` flavor (Android TV / Fire TV). D-pad only, no touch assumptions. */
@AndroidEntryPoint
class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { ReelmTvTheme { TvProfileGate { TvApp() } } }
    }
}

internal object TvRoutes {
    const val HOME = "home"
    const val MOVIES = "movies"
    const val ANIME = "anime"
    const val SEARCH = "search"
    const val LIVE = "live"
    const val LIBRARY = "library"
    const val MORE = "more"
    const val MEDIA_PLAYER = "media_player"
    const val ACCOUNT = "account"

    fun anime(slug: String) = "anime/${Uri.encode(slug)}"
    fun movie(id: String) = "movie/${Uri.encode(id)}"
    fun detail(id: String) = if (isMediaKey(id)) movie(id) else anime(id)
    fun player(slug: String, episode: Int) = "player/${Uri.encode(slug)}/$episode"
    fun web(imdb: String, season: Int, episode: Int) = "web/${Uri.encode(imdb)}/$season/$episode"
}

private data class RailEntry(val route: String, val label: String, val icon: ImageVector)

private val rail = listOf(
    RailEntry(TvRoutes.HOME, "Home", Icons.Outlined.Home),
    RailEntry(TvRoutes.SEARCH, "Search", Icons.Outlined.Search),
    RailEntry(TvRoutes.MOVIES, "Movies & TV", Icons.Outlined.Movie),
    RailEntry(TvRoutes.ANIME, "Anime", Icons.Outlined.AutoAwesome),
    RailEntry(TvRoutes.LIVE, "Live TV", Icons.Outlined.LiveTv),
    RailEntry(TvRoutes.LIBRARY, "Library", Icons.Outlined.VideoLibrary),
    RailEntry(TvRoutes.MORE, "More", Icons.Outlined.MoreHoriz)
)
/** Not a screen: shows the active profile's avatar and reopens "Who's watching?". */
private val accountEntry = RailEntry(TvRoutes.ACCOUNT, "Switch profile", Icons.Outlined.AccountCircle)

@Composable
private fun TvApp() {
    val nav = rememberNavController()
    // activity-scoped so Home, Movies & TV and Anime share one load
    val homeVm: HomeViewModel = hiltViewModel()
    val moviesVm: MoviesHomeViewModel = hiltViewModel()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val acct: TvAccountViewModel = hiltViewModel()
    // accounts are hidden entirely when REELM_API_URL is blank
    val entries = if (acct.repo.enabled) rail + accountEntry else rail
    val showRail = entries.any { it.route == current }
    val session by acct.session.collectAsStateWithLifecycle()
    val profileId = session?.activeProfile?.id

    // Continue on TV: same destination a Continue Watching tap opens, resuming at the phone's progress
    LaunchedEffect(Unit) {
        acct.hub.handoffs.collect { h ->
            val row = acct.applyHandoff(h) ?: return@collect
            if (row.kind == "media") nav.navigate(TvRoutes.movie(row.detailId))
            else nav.navigate(TvRoutes.player(row.animeSlug, row.lastEpisodeWatched.coerceAtLeast(1)))
        }
    }
    // a profile switch or a history pull changes Continue Watching
    LaunchedEffect(profileId) { homeVm.refreshContinueWatching(); moviesVm.refreshContinueWatching() }
    LaunchedEffect(Unit) { acct.sync.pulled.collect { homeVm.refreshContinueWatching(); moviesVm.refreshContinueWatching() } }

    // another member switched media: swap the player for the new one, staying in the party
    val party: PartyViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        party.loads.collect { dest ->
            val route = when (dest) {
                is PartyDestination.Anime -> TvRoutes.player(dest.slug, dest.episode)
                PartyDestination.Media -> TvRoutes.MEDIA_PLAYER
            }
            nav.navigate(route) { nav.currentDestination?.id?.let { popUpTo(it) { inclusive = true } } }
        }
    }
    TvUpdateHost()
    val host = @Composable {
        NavHost(nav, startDestination = TvRoutes.HOME) {
            composable(TvRoutes.HOME) { TvHomeScreen(HomeFilter.ALL, homeVm, moviesVm, nav) }
            composable(TvRoutes.MOVIES) { TvHomeScreen(HomeFilter.MEDIA, homeVm, moviesVm, nav) }
            composable(TvRoutes.ANIME) { TvHomeScreen(HomeFilter.ANIME, homeVm, moviesVm, nav) }
            composable(TvRoutes.SEARCH) { TvSearchScreen(onOpen = { nav.navigate(TvRoutes.detail(it)) }) }
            composable(TvRoutes.LIVE) { TvLiveScreen(onPlay = { nav.navigate(TvRoutes.MEDIA_PLAYER) }) }
            composable(TvRoutes.LIBRARY) { TvLibraryScreen(onOpen = { nav.navigate(TvRoutes.detail(it)) }, onPlayer = { nav.navigate(TvRoutes.MEDIA_PLAYER) }) }
            composable(TvRoutes.MORE) {
                TvMoreScreen(onOpen = { dest ->
                    nav.navigate(
                        when (dest) {
                            is PartyDestination.Anime -> TvRoutes.player(dest.slug, dest.episode)
                            PartyDestination.Media -> TvRoutes.MEDIA_PLAYER
                        }
                    )
                })
            }
            composable("anime/{slug}") {
                TvAnimeDetailScreen(
                    onPlay = { slug, ep -> nav.navigate(TvRoutes.player(slug, ep)) },
                    onOpen = { nav.navigate(TvRoutes.anime(it)) }
                )
            }
            composable("movie/{id}") {
                TvMovieDetailScreen(onPlayer = { nav.navigate(TvRoutes.MEDIA_PLAYER) }, onWebPlayer = { tt, s, e -> nav.navigate(TvRoutes.web(tt, s, e)) })
            }
            composable("player/{slug}/{episode}") { TvAnimePlayerScreen(onExit = { nav.popBackStack() }) }
            composable(TvRoutes.MEDIA_PLAYER) {
                TvMediaPlayerScreen(onExit = { nav.popBackStack() }, onWebPlayer = { tt, s, e -> nav.navigate(TvRoutes.web(tt, s, e)) })
            }
            composable("web/{imdb}/{season}/{episode}") { entry ->
                val a = entry.arguments
                WebEmbedPlayer(
                    imdbId = a?.getString("imdb").orEmpty(),
                    season = a?.getString("season")?.toIntOrNull() ?: 0,
                    episode = a?.getString("episode")?.toIntOrNull() ?: 0,
                    onBack = { nav.popBackStack() },
                    tv = true
                )
            }
        }
    }

    // Floating glass pill over the content, no background column. Hidden while focus is in the content
    // (full width); pressing Left from the leftmost item slides it in, icons only, with a label chip
    // beside the focused icon. Hidden (not removed) on detail/player screens so the NavHost keeps its place.
    var railOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var railFocused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val contentReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val selectedReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val ms = if (LocalReduceMotion.current) 0 else 180
    // focus hops between rail items drop hasFocus for an instant: close only if it is still gone a frame later
    LaunchedEffect(railFocused) {
        if (!railFocused && railOpen) { androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }; if (!railFocused && railOpen) railOpen = false }
    }
    LaunchedEffect(railOpen) {
        if (railOpen) { androidx.compose.runtime.withFrameNanos { }; runCatching { selectedReq.requestFocus() } }
    }
    androidx.compose.foundation.layout.Box(
        // Left with nothing further left in the content: reveal the rail (and cancel the move)
        // Left from the leftmost content item (no focus target further left) reveals the rail
        Modifier.fillMaxSize().onKeyEvent { e ->
            if (showRail && !railOpen && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && !fm.moveFocus(FocusDirection.Left)) { railOpen = true; true } else false
        }
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().focusRequester(contentReq).focusGroup()) { host() }
        // left-edge fade under the open rail, so content it overlaps (hero titles) recedes instead of clashing; a flat
        // gradient, cheap on the Fire Stick's GPU
        // hidden-state hint: a thin amber edge at mid-height says "press Left"
        if (showRail && !railOpen) Box(Modifier.align(androidx.compose.ui.Alignment.TopStart).padding(top = (540 * 0.28f).dp).width(4.8.dp).height((540 * 0.32f).dp)
            .background(Accent.main.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)))
        androidx.compose.animation.AnimatedVisibility(showRail && railOpen, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(ms)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(ms))) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxHeight().width(150.dp).background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color(0xE60B0C11), androidx.compose.ui.graphics.Color(0x000B0C11)))))
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showRail && railOpen,
            // centred at 42% of the height, as in the mockup
            modifier = Modifier.align(androidx.compose.ui.BiasAlignment(-1f, -0.16f)),
            enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(ms)) { -it } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(ms)),
            exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(ms)) { -it } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(ms))
        ) {
            Column(
                Modifier
                    .padding(start = 23.dp)
                    .focusGroup()
                    // nothing focusable to the right of an icon in the hero band: hand focus to the content explicitly
                    .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { runCatching { contentReq.requestFocus() }; true } else false }
                    .onFocusChanged { railFocused = it.hasFocus }
                    .background(IslandFill, IslandShape)
                    .border(1.dp, IslandEdge, IslandShape)
                    .padding(horizontal = 7.7.dp, vertical = 11.5.dp),
                verticalArrangement = Arrangement.spacedBy(10.6.dp)
            ) {
                entries.forEach { r ->
                    val selected = current == r.route
                    val profile = if (r.route == TvRoutes.ACCOUNT) session?.activeProfile else null
                    val label = (profile?.name ?: r.label) + if (profile?.kids == true) " · Kids" else ""
                    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box(Modifier.size(32.6.dp)) {
                        // focused = amber ring + lighter surface; selected (current screen) = soft amber fill + amber icon
                        androidx.tv.material3.Surface(
                            onClick = { if (r.route == TvRoutes.ACCOUNT) acct.repo.openGate() else nav.navigateTop(r.route) },
                            modifier = Modifier.size(32.6.dp)
                                .then(if (selected) Modifier.focusRequester(selectedReq) else Modifier)
                                .onFocusChanged { focused = it.isFocused },
                            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = if (selected) Color(0x4DF2B45A) else Color.Transparent,
                                contentColor = Ink.body,
                                focusedContainerColor = if (selected) Color(0x4DF2B45A) else Color.Transparent,
                                focusedContentColor = Ink.high
                            ),
                            border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                                focusedBorder = Border(FocusRing, shape = androidx.compose.foundation.shape.CircleShape)
                            ),
                            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(focusedGlow = Glow.None)
                        ) {
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                if (profile != null) coil.compose.AsyncImage(
                                    com.anistream.core.account.FluentEmojis.urlFor(profile.avatar), contentDescription = "Switch profile, watching as ${profile.name}",
                                    modifier = Modifier.size(20.dp)
                                )
                                else Icon(r.icon, contentDescription = r.label, modifier = Modifier.size(18.dp), tint = if (selected) Accent.main else LocalContentColor.current)
                            }
                        }
                        // label chip beside the focused icon; offset, so it never changes the pill's width
                        if (focused) Text(
                            label, maxLines = 1, style = TvType.body.copy(color = Ink.high, fontSize = 13.4.sp, lineHeight = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart).offset(x = 50.dp)
                                .wrapContentWidth(unbounded = true, align = androidx.compose.ui.Alignment.Start)
                                .background(Color(0xFF232634), Radius.pill).padding(horizontal = 12.5.dp, vertical = 4.8.dp)
                        )
                    }
                }
            }
        }
    }
}

private val IslandShape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
private val IslandFill = androidx.compose.ui.graphics.Brush.verticalGradient(
    listOf(Color(0xEB1A1B26), Color(0xEB1A1B26))
)
private val IslandEdge = Color(0x14FFFFFF)

private fun NavHostController.navigateTop(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
