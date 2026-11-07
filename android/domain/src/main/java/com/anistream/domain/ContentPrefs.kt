package com.anistream.domain

import com.anistream.domain.model.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the active profile chose not to see ("What you watch"). Set by the account layer from the profile's `hide`
 * list (or the device's guest choice); screens collect [hidden] so tabs and rows update the moment it changes.
 * Same values on server, web and TV: anime, movies, series, live. At least one kind always stays visible.
 */
object ContentPrefs {
    const val ANIME = "anime"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val LIVE = "live"
    val ALL = listOf(ANIME, MOVIES, SERIES, LIVE)
    val LABELS = mapOf(ANIME to "Anime", MOVIES to "Movies", SERIES to "Series", LIVE to "Live TV")

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    fun set(hide: Set<String>) { _hidden.value = clean(hide) }
    fun shows(kind: String) = kind !in _hidden.value
    fun shows(type: MediaType) = shows(if (type == MediaType.SERIES) SERIES else MOVIES)
    /** The Movies & TV tab: there while either movies or series are shown. */
    fun showsMoviesTab() = shows(MOVIES) || shows(SERIES)

    /** Known kinds only, and never all four hidden. */
    fun clean(hide: Set<String>): Set<String> = hide.filter { it in ALL }.toSet().let { if (it.size >= ALL.size) emptySet() else it }
}
