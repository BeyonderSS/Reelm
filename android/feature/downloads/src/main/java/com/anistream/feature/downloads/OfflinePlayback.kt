package com.anistream.feature.downloads

import android.net.Uri
import com.anistream.domain.model.DownloadItem
import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.PlaybackRequest
import com.anistream.domain.model.SubtitleTrack
import com.anistream.domain.repository.NowPlaying
import java.io.File

/** Hands a downloaded file (+ its .vtt/.srt sidecar) to the regular media player: same controls, fullscreen, subtitles. */
fun playOffline(path: String, d: DownloadItem?) {
    val base = path.substringBeforeLast('.')
    val subs = listOf("vtt" to "text/vtt", "srt" to "application/x-subrip")
        .map { (ext, mime) -> File("$base.$ext") to mime }
        .filter { it.first.exists() }
        .map { (f, mime) -> SubtitleTrack(Uri.fromFile(f).toString(), "en", "English", mime) }
    NowPlaying.request = PlaybackRequest(
        historyKey = null,
        mediaId = null,
        title = d?.animeTitle ?: File(path).nameWithoutExtension,
        subtitle = d?.let { it.subtitle ?: if (it.episodeNumber > 0) "Episode ${it.episodeNumber}" else null },
        posterUrl = d?.posterUrl,
        streams = listOf(
            MediaStream(
                url = Uri.fromFile(File(path)).toString(),
                qualityLabel = d?.quality ?: "Downloaded",
                sourceName = "On this phone",
                subtitles = subs,
                mimeType = if (path.endsWith(".ts")) "video/mp2t" else null
            )
        )
    )
}
