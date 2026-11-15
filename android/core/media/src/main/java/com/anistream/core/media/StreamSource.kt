package com.anistream.core.media

import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.MediaType

/** A site that is searched by title (not by id) and resolved to playable links. season/episode are 0 for movies. */
interface StreamSource {
    suspend fun streams(title: String, year: Int?, type: MediaType, season: Int, episode: Int): List<MediaStream>

    /** Same, but a source may return early and hand what it finds afterwards to [late]. */
    suspend fun streams(title: String, year: Int?, type: MediaType, season: Int, episode: Int, late: suspend (List<MediaStream>) -> Unit) =
        streams(title, year, type, season, episode)
}
