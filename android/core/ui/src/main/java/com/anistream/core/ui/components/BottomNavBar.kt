package com.anistream.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.anistream.domain.ContentPrefs
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink

enum class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Home("home", "Anime", Icons.Outlined.Home),
    Movies("movies", "Movies", Icons.Outlined.Movie),
    LiveTv("live_tv", "Live TV", Icons.Outlined.LiveTv),
    Search("search", "Search", Icons.Outlined.Search),
    Library("library", "Library", Icons.Outlined.VideoLibrary);

    /** "What you watch": a tab goes away when everything it holds is hidden. */
    fun visible(hidden: Set<String>) = when (this) {
        Home -> ContentPrefs.ANIME !in hidden
        Movies -> ContentPrefs.MOVIES !in hidden || ContentPrefs.SERIES !in hidden
        LiveTv -> ContentPrefs.LIVE !in hidden
        else -> true
    }
}

/** Slate 95% bar; selected = haze pill behind a mist icon + mist label, unselected at 52% ink. */
@Composable
fun BottomNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val hidden by ContentPrefs.hidden.collectAsState()
    Column(modifier.background(Bg.bar)) {
        NavigationBar(
            containerColor = Color.Transparent,
            contentColor = Ink.body,
            tonalElevation = 0.dp
        ) {
            BottomNavItem.entries.filter { it.visible(hidden) }.forEach { item ->
                val selected = currentRoute == item.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onNavigate(item.route) },
                    icon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = { Text(item.label, style = AppType.label.copy(color = Color.Unspecified, fontSize = 12.sp), maxLines = 1, softWrap = false) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Ink.high,
                        selectedTextColor = Ink.high,
                        unselectedIconColor = Ink.muted,
                        unselectedTextColor = Ink.muted,
                        indicatorColor = Bg.overlay
                    )
                )
            }
        }
    }
}
