package com.anistream.app.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anistream.core.account.ui.AccountScreen
import com.anistream.feature.admin.AdminScreen
import com.anistream.core.account.ui.AccountViewModel
import com.anistream.core.account.ui.AvatarImage
import com.anistream.core.account.ui.ProfilePickerGate
import com.anistream.core.ui.theme.Ink
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.hilt.navigation.compose.hiltViewModel
import com.anistream.feature.party.PartyDestination
import com.anistream.feature.party.PartyViewModel
import com.anistream.feature.party.ReelmMenu
import com.anistream.feature.party.UpdateHost
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.anistream.core.ui.components.SegmentedControl
import com.anistream.core.ui.theme.Space
import androidx.compose.ui.Modifier
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Accent
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.anistream.core.ui.components.BottomNavBar
import com.anistream.core.ui.components.BottomNavItem
import com.anistream.core.ui.theme.Bg
import com.anistream.feature.anime.AnimeDetailScreen
import com.anistream.feature.downloads.DownloadsScreen
import com.anistream.feature.favorites.FavoritesScreen
import com.anistream.feature.genre.GenreBrowseScreen
import com.anistream.feature.home.HomeScreen
import com.anistream.feature.livetv.LiveTvScreen
import com.anistream.feature.movies.AddonManagerScreen
import com.anistream.feature.movies.MovieDetailScreen
import com.anistream.feature.movies.MoviesCatalogScreen
import com.anistream.feature.movies.MoviesHomeScreen
import com.anistream.feature.movies.isMediaKey
import com.anistream.feature.player.MediaPlayerScreen
import com.anistream.feature.player.WebEmbedPlayer
import com.anistream.feature.player.PlayerScreen
import com.anistream.feature.schedule.ScheduleScreen
import com.anistream.feature.search.SearchScreen

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val DOWNLOADS = "downloads?play={play}"
    const val ANIME_DETAIL = "anime_detail/{slug}"
    const val PLAYER = "player/{slug}/{episode}"
    /** path is optional: absent = genre picker, else a category path like "top-airing" or "genres/action". */
    const val GENRE_BROWSE = "genre_browse?path={path}"
    const val SCHEDULE = "schedule"
    const val MOVIES = "movies"
    const val MOVIE_DETAIL = "movie_detail/{id}"
    const val MOVIES_CATALOG = "movies_catalog/{key}?title={title}"
    const val MEDIA_PLAYER = "media_player"
    const val WEB_PLAYER = "web_player/{imdb}/{season}/{episode}"
    const val LIVE_TV = "live_tv"
    const val ADDONS = "addons"
    const val LIBRARY = "library"
    const val ACCOUNT = "account?code={code}"
    const val ADMIN = "admin"

    fun animeDetail(slug: String) = "anime_detail/$slug"
    fun movieDetail(id: String) = "movie_detail/${Uri.encode(id)}"
    fun moviesCatalog(key: String, title: String) = "movies_catalog/${Uri.encode(key)}?title=${Uri.encode(title)}"
    /** Favourites hold both kinds; media ids ("mb:…", "tt…") open the movie detail. */
    fun detailFor(id: String) = if (isMediaKey(id)) movieDetail(id) else animeDetail(id)
    /** [play] = a downloaded file to open in the local player right away (from the "Download finished" notification) */
    fun downloads(play: String? = null) = if (play == null) "downloads" else "downloads?play=${Uri.encode(play)}"
    fun player(slug: String, episode: Int) = "player/$slug/$episode"
    fun webPlayer(imdb: String, season: Int, episode: Int) = "web_player/${Uri.encode(imdb)}/$season/$episode"
    fun browse(path: String? = null) = if (path == null) "genre_browse" else "genre_browse?path=${Uri.encode(path)}"
}

private val bottomBarRoutes = setOf(
    Routes.HOME,
    Routes.MOVIES,
    Routes.LIVE_TV,
    Routes.SEARCH,
    Routes.LIBRARY
)

@Composable
fun AniStreamNavGraph() {
    val navController = rememberNavController()
    com.anistream.app.work.PendingDeepLink.route?.let { r -> androidx.compose.runtime.LaunchedEffect(r) { com.anistream.app.work.PendingDeepLink.route = null; navController.navigate(r) } }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomBarRoutes
    var showMenu by rememberSaveable { mutableStateOf(false) }
    UpdateHost()
    // another member switched media: swap the player for the new one, staying in the party
    val party: PartyViewModel = hiltViewModel()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        party.loads.collect { dest ->
            val route = when (dest) {
                is PartyDestination.Anime -> Routes.player(dest.slug, dest.episode)
                PartyDestination.Media -> Routes.MEDIA_PLAYER
            }
            navController.navigate(route) { navController.currentDestination?.id?.let { popUpTo(it) { inclusive = true } } }
        }
    }
    if (showMenu) ReelmMenu(
        onDismiss = { showMenu = false },
        onOpen = { dest ->
            navController.navigate(
                when (dest) {
                    is PartyDestination.Anime -> Routes.player(dest.slug, dest.episode)
                    PartyDestination.Media -> Routes.MEDIA_PLAYER
                }
            )
        }
    )

    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // No system-bar insets here: the only padding the Scaffold contributes is the bottom bar
    // (which itself sits above the gesture bar). Screens decide about the status bar; see [Insetted].
    ProfilePickerGate(onAccount = { navController.navigate("account") }) {
    Scaffold(
        containerColor = Bg.base,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    onNavigate = navigateToTab
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            enterTransition = {
                fadeIn(tween(300)) + slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    tween(300)
                )
            },
            exitTransition = {
                fadeOut(tween(300))
            },
            popEnterTransition = {
                fadeIn(tween(300))
            },
            popExitTransition = {
                fadeOut(tween(300)) + slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    tween(300)
                )
            }
        ) {
            composable(Routes.HOME) {
                // Home draws its hero behind the status bar and pads its own top bar.
                HomeScreen(
                    onAnimeClick = { slug ->
                        navController.navigate(Routes.animeDetail(slug))
                    },
                    onSeeAllClick = { path ->
                        navController.navigate(Routes.browse(path))
                    },
                    onGenresClick = {
                        navController.navigate(Routes.browse())
                    },
                    onSearchClick = { navigateToTab(Routes.SEARCH) },
                    onScheduleClick = { navController.navigate(Routes.SCHEDULE) },
                    onMenuClick = { showMenu = true },
                    onPlayEpisode = { slug, episode ->
                        navController.navigate(Routes.player(slug, episode))
                    },
                    onMediaClick = { id -> navController.navigate(Routes.movieDetail(id)) },
                    onMediaSeeAll = { key, title -> navController.navigate(Routes.moviesCatalog(key, title)) },
                    onLiveTvClick = { navigateToTab(Routes.LIVE_TV) },
                    onPlayLive = { navController.navigate(Routes.MEDIA_PLAYER) }
                )
            }

            composable(Routes.SEARCH) {
                Insetted {
                    SearchScreen(
                        onAnimeClick = { slug ->
                            navController.navigate(Routes.animeDetail(slug))
                        },
                        onMediaClick = { id -> navController.navigate(Routes.movieDetail(id)) }
                    )
                }
            }

            composable(
                Routes.ACCOUNT,
                arguments = listOf(navArgument("code") { type = NavType.StringType; nullable = true; defaultValue = null })
            ) { entry ->
                Insetted { AccountScreen(onBack = { navController.popBackStack() }, pairCode = entry.arguments?.getString("code"), onAdmin = { navController.navigate(Routes.ADMIN) }) }
            }

            composable(Routes.ADMIN) { Insetted { AdminScreen(onBack = { navController.popBackStack() }) } }

            composable(Routes.LIBRARY) {
                Insetted {
                    LibraryScreen(
                        onOpen = { id -> navController.navigate(Routes.detailFor(id)) },
                        onDiscoverClick = { navigateToTab(Routes.HOME) }
                    )
                }
            }

            // Old tab routes stay reachable for any deep link / saved state.
            composable(Routes.FAVORITES) {
                Insetted {
                    FavoritesScreen(
                        onAnimeClick = { id -> navController.navigate(Routes.detailFor(id)) },
                        onDiscoverClick = { navigateToTab(Routes.HOME) }
                    )
                }
            }

            composable(
                Routes.DOWNLOADS,
                arguments = listOf(navArgument("play") { type = NavType.StringType; nullable = true; defaultValue = null })
            ) { entry ->
                Insetted { DownloadsScreen(initialPlay = entry.arguments?.getString("play")) }
            }

            composable(Routes.MOVIES) {
                // Like anime Home: hero under the status bar, top bar pads itself.
                MoviesHomeScreen(
                    onItemClick = { id -> navController.navigate(Routes.movieDetail(id)) },
                    onSeeAll = { key, title -> navController.navigate(Routes.moviesCatalog(key, title)) },
                    onAddonsClick = { navController.navigate(Routes.ADDONS) }
                )
            }

            composable(
                route = Routes.MOVIES_CATALOG,
                arguments = listOf(
                    navArgument("key") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" }
                )
            ) {
                Insetted {
                    MoviesCatalogScreen(
                        onBack = { navController.popBackStack() },
                        onItemClick = { id -> navController.navigate(Routes.movieDetail(id)) }
                    )
                }
            }

            composable(
                route = Routes.MOVIE_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) {
                // Edge to edge, like anime detail.
                MovieDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = { navController.navigate(Routes.MEDIA_PLAYER) },
                    onWebPlayer = { tt, s, e -> navController.navigate(Routes.webPlayer(tt, s, e)) }
                )
            }

            composable(Routes.MEDIA_PLAYER) {
                MediaPlayerScreen(
                    onBack = { navController.popBackStack() },
                    onWebPlayer = { tt, s, e -> navController.navigate(Routes.webPlayer(tt, s, e)) }
                )
            }

            composable(
                route = Routes.WEB_PLAYER,
                arguments = listOf(
                    navArgument("imdb") { type = NavType.StringType },
                    navArgument("season") { type = NavType.IntType },
                    navArgument("episode") { type = NavType.IntType }
                )
            ) { entry ->
                val args = entry.arguments
                WebEmbedPlayer(
                    imdbId = args?.getString("imdb").orEmpty(),
                    season = args?.getInt("season") ?: 0,
                    episode = args?.getInt("episode") ?: 0,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.LIVE_TV) {
                Insetted {
                    LiveTvScreen(onPlay = { navController.navigate(Routes.MEDIA_PLAYER) })
                }
            }

            composable(Routes.ADDONS) {
                Insetted { AddonManagerScreen(onBack = { navController.popBackStack() }) }
            }

            composable(
                route = Routes.ANIME_DETAIL,
                arguments = listOf(navArgument("slug") { type = NavType.StringType })
            ) {
                // Edge to edge: detail handles its own insets.
                AnimeDetailScreen(
                    onBack = { navController.popBackStack() },
                    onAnimeClick = { slug ->
                        navController.navigate(Routes.animeDetail(slug))
                    },
                    onPlayEpisode = { slug, episode ->
                        navController.navigate(Routes.player(slug, episode))
                    },
                    onGenreClick = { genreSlug ->
                        navController.navigate(Routes.browse("genres/$genreSlug"))
                    }
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument("slug") { type = NavType.StringType },
                    navArgument("episode") { type = NavType.IntType }
                )
            ) {
                // Edge to edge: player handles its own insets.
                PlayerScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.GENRE_BROWSE,
                arguments = listOf(navArgument("path") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) {
                Insetted {
                    GenreBrowseScreen(
                        onAnimeClick = { slug ->
                            navController.navigate(Routes.animeDetail(slug))
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            composable(Routes.SCHEDULE) {
                Insetted {
                    ScheduleScreen(
                        onAnimeClick = { slug ->
                            navController.navigate(Routes.animeDetail(slug))
                        }
                    )
                }
            }
        }
    }
    }
}

/** Favourites | Downloads under one tab; one marquee header, the child screens skip their own. */
@Composable
private fun LibraryScreen(onOpen: (String) -> Unit, onDiscoverClick: () -> Unit) {
    val account = androidx.hilt.navigation.compose.hiltViewModel<AccountViewModel>()
    val session by account.session.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = Space.lg, end = Space.xs, top = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            com.anistream.core.ui.components.MarqueeTitle("Library", null, Modifier.weight(1f))
            // accounts are optional and hidden when REELM_API_URL is blank; the avatar reopens "Who's watching?"
            if (account.repo.enabled) IconButton(onClick = account.repo::openGate) {
                val p = session?.activeProfile
                if (p != null) AvatarImage(p.avatar, 32.dp, ring = true, contentDescription = "Switch profile, watching as ${p.name}${if (p.kids) ", kids profile" else ""}", kids = p.kids)
                else Icon(Icons.Outlined.AccountCircle, contentDescription = "Sign in or switch profile", tint = Ink.high)
            }
        }
        // ticket-stub switcher: two stubs, the selected one amber
        Row(Modifier.padding(horizontal = Space.lg, vertical = Space.md), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Space.sm)) {
            listOf("Favourites", "Downloads").forEachIndexed { i, label ->
                val on = tab == i
                androidx.compose.material3.Text(
                    label,
                    style = AppType.label,
                    color = if (on) Accent.on else Ink.body,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f)
                        .clip(com.anistream.core.ui.components.TicketShape(corner = 14.dp, notch = 6.dp))
                        .background(if (on) Accent.main else Bg.raised)
                        .clickable { tab = i }
                        .padding(vertical = Space.md)
                )
            }
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) FavoritesScreen(onAnimeClick = onOpen, onDiscoverClick = onDiscoverClick, showHeader = false)
            else DownloadsScreen(showHeader = false)
        }
    }
}

/**
 * Pads a destination below the status bar (and above the gesture bar when there is no bottom nav).
 * The insets are consumed, so inner Scaffolds/TopAppBars don't add them a second time.
 */
@Composable
private fun Insetted(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) { content() }
}
