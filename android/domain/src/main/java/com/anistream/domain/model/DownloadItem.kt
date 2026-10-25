package com.anistream.domain.model

data class DownloadItem(
    val episodeId: Long,
    val animeTitle: String,
    val episodeNumber: Int,
    val quality: String,
    val filePath: String? = null,
    val fileSizeBytes: Long = 0,
    val progress: Float = 0f,
    val state: DownloadState = DownloadState.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    /** movies & TV: "S1 · E2"; null for anime episodes and movies */
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val stillUrl: String? = null,
    val episodeTitle: String? = null
)

enum class DownloadState {
    PENDING, DOWNLOADING, COMPLETED, FAILED, PAUSED
}

/** The finished file (+ its .vtt/.srt sidecar) as a playable stream; null while it isn't fully on the device. */
fun DownloadItem.localStream(): MediaStream? {
    val path = filePath?.takeIf { state == DownloadState.COMPLETED && java.io.File(it).exists() } ?: return null
    val base = path.substringBeforeLast('.')
    val subs = listOf("vtt" to "text/vtt", "srt" to "application/x-subrip")
        .map { (ext, mime) -> java.io.File("$base.$ext") to mime }
        .filter { it.first.exists() }
        .map { (f, mime) -> SubtitleTrack(f.toURI().toString(), "en", "English", mime) }
    return MediaStream(
        url = java.io.File(path).toURI().toString(), qualityLabel = quality, sourceName = "On this phone",
        subtitles = subs, mimeType = if (path.endsWith(".ts")) "video/mp2t" else null
    )
}
