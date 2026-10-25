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
