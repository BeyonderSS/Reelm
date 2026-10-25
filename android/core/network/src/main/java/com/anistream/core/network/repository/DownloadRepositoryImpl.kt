package com.anistream.core.network.repository

import android.content.Context
import android.os.Environment
import com.anistream.core.common.Result
import com.anistream.core.database.dao.DownloadDao
import com.anistream.core.database.entity.DownloadEntity
import com.anistream.domain.model.DownloadItem
import com.anistream.domain.model.DownloadState
import com.anistream.domain.model.MediaStream
import com.anistream.domain.model.StreamSource
import com.anistream.domain.repository.DownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** Media URIs of an HLS media playlist, resolved against [baseUrl]. Master playlists yield their variant URIs. */
internal fun playlistUris(playlist: String, baseUrl: String): List<String> {
    val base = URI(baseUrl)
    return playlist.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { base.resolve(it).toString() }
        .toList()
}

internal fun isMasterPlaylist(playlist: String) = playlist.contains("#EXT-X-STREAM-INF")

/** Stable key for (anime, episode); hianime episode ids aren't known to the downloads screen. */
fun downloadId(animeSlug: String, episodeNumber: Int): Long =
    (animeSlug.hashCode().toLong() shl 32) or (episodeNumber.toLong() and 0xFFFFFFFFL)

/** "/x/a.ts" -> "/x/a": subtitle sidecars sit next to the video with a .vtt/.srt extension. */
internal fun sidecarBase(videoPath: String) = videoPath.substringBeforeLast('.')

/** Unique WorkManager name for a download; one work per download id. */
fun downloadWorkName(id: Long) = "download-$id"

/** The app module binds this to its @HiltWorker class (core:network can't see it). */
const val DOWNLOAD_WORKER = "downloadWorker"

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    private val dao: DownloadDao,
    @ApplicationContext private val context: Context,
    @Named(DOWNLOAD_WORKER) private val workerClass: Class<out ListenableWorker>
) : DownloadRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val workManager get() = WorkManager.getInstance(context)

    private val dir: File
        get() = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "downloads")
            .apply { mkdirs() }

    override suspend fun getAll(): Result<List<DownloadItem>> {
        return try {
            Result.success(dao.getAll().first().map { it.toDomain() })
        } catch (e: Exception) {
            Result.error(e)
        }
    }

    private suspend fun isRunning(id: Long) =
        workManager.getWorkInfosForUniqueWorkFlow(downloadWorkName(id)).first().any { !it.state.isFinished }

    override suspend fun startDownload(
        stream: StreamSource,
        animeSlug: String,
        title: String,
        episodeNumber: Int,
        posterUrl: String?,
        stillUrl: String?,
        episodeTitle: String?
    ): Result<Unit> {
        return try {
            val id = downloadId(animeSlug, episodeNumber)
            if (isRunning(id)) return Result.success(Unit)
            val video = File(dir, "${animeSlug.replace('/', '_')}-ep$episodeNumber.ts")
            dao.upsert(
                DownloadEntity(
                    episodeId = id,
                    animeTitle = title,
                    episodeNumber = episodeNumber,
                    quality = stream.qualityLabel,
                    filePath = video.path,
                    state = DownloadState.PENDING.name,
                    posterUrl = posterUrl,
                    stillUrl = stillUrl,
                    episodeTitle = episodeTitle
                )
            )
            enqueue(
                mapOf(K_ID to id.toString(), K_KIND to "hls", K_URL to stream.m3u8Url, K_REFERER to stream.referer, K_SUB to stream.subtitleUrl)
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error(e)
        }
    }

    override suspend fun startMediaDownload(stream: MediaStream, key: String, title: String, subtitle: String?, posterUrl: String?, stillUrl: String?, episodeTitle: String?): Result<Unit> {
        return try {
            if (!stream.isDirectFile) return Result.error(IOException("Only direct files can be downloaded"))
            val id = downloadId(key, 0)
            if (isRunning(id)) return Result.success(Unit)
            val name = listOfNotNull(title, subtitle).joinToString(" ").replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(120)
            val video = File(dir, "${name.ifEmpty { "video" }}.mp4")
            dao.upsert(
                DownloadEntity(
                    episodeId = id,
                    animeTitle = title,
                    episodeNumber = 0,
                    quality = stream.qualityLabel,
                    filePath = video.path,
                    state = DownloadState.PENDING.name,
                    subtitle = subtitle,
                    posterUrl = posterUrl,
                    stillUrl = stillUrl,
                    episodeTitle = episodeTitle
                )
            )
            val track = stream.subtitles.firstOrNull()
            enqueue(
                mapOf(
                    K_ID to id.toString(), K_KIND to "file", K_URL to stream.url,
                    K_SIZE to stream.sizeBytes?.toString(), K_SUB to track?.url, K_SUB_MIME to track?.mimeType
                ) + stream.headers.mapKeys { HEADER_PREFIX + it.key }
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error(e)
        }
    }

    private fun enqueue(input: Map<String, String?>) {
        val id = input.getValue(K_ID)!!.toLong()
        val request = OneTimeWorkRequest.Builder(workerClass)
            .setInputData(Data.Builder().apply { input.forEach { (k, v) -> if (v != null) putString(k, v) } }.build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(downloadWorkName(id), ExistingWorkPolicy.KEEP, request)
    }

    /** Re-run a failed download with its original worker input; partial files resume. */
    suspend fun retry(input: Map<String, String?>) {
        val id = input[K_ID]?.toLongOrNull() ?: return
        val row = dao.getById(id) ?: return
        dao.updateProgress(id, row.progress, DownloadState.PENDING.name)
        enqueue(input)
    }

    suspend fun markFailed(id: Long) = dao.updateProgress(id, 0f, DownloadState.FAILED.name)

    /**
     * The actual transfer, run by the app's download worker. Throws on failure (IOException = retryable);
     * partial files are kept so the next attempt resumes. [onProgress] fires at most once a second.
     */
    suspend fun transfer(input: Data, onProgress: suspend (Float) -> Unit) {
        val id = input.getString(K_ID)?.toLongOrNull() ?: return
        val entity = dao.getById(id) ?: return // deleted while queued
        val video = File(entity.filePath ?: return)
        val url = input.getString(K_URL) ?: throw IllegalArgumentException("No url")
        val report: suspend (Float) -> Unit = { p ->
            dao.updateProgress(id, p, DownloadState.DOWNLOADING.name)
            onProgress(p)
        }
        if (input.getString(K_KIND) == "file") {
            val headers = input.keyValueMap.filterKeys { it.startsWith(HEADER_PREFIX) }
                .map { (k, v) -> k.removePrefix(HEADER_PREFIX) to v.toString() }.toMap()
            downloadFile(url, headers, input.getString(K_SIZE)?.toLongOrNull(), video, report)
            input.getString(K_SUB)?.let { sub ->
                val ext = if (input.getString(K_SUB_MIME).orEmpty().contains("vtt")) "vtt" else "srt"
                // subtitles are optional; a failure here shouldn't sink the video
                runCatching { File(sidecarBase(video.path) + ".$ext").writeText(fetchText(sub, null)) }
            }
        } else {
            val referer = input.getString(K_REFERER)
            downloadHls(url, referer, video, report)
            input.getString(K_SUB)?.let { sub ->
                runCatching { File(sidecarBase(video.path) + ".vtt").writeText(fetchText(sub, referer)) }
            }
        }
        dao.upsert(entity.copy(progress = 1f, state = DownloadState.COMPLETED.name, fileSizeBytes = video.length()))
    }

    private suspend fun downloadHls(m3u8Url: String, referer: String?, video: File, report: suspend (Float) -> Unit) {
        var playlistUrl = m3u8Url
        var playlist = fetchText(playlistUrl, referer)
        if (isMasterPlaylist(playlist)) {
            playlistUrl = playlistUris(playlist, playlistUrl).first()
            playlist = fetchText(playlistUrl, referer)
        }
        val segments = playlistUris(playlist, playlistUrl)
        if (segments.isEmpty()) throw IOException("Empty playlist")

        // "<segments done> <bytes>" checkpoint: a retried worker truncates any half-written segment and skips the rest.
        // ponytail: assumes the playlist is identical across attempts; a re-resolved stream with different segments would corrupt the file
        val mark = File(video.path + ".part")
        val (doneSegs, doneBytes) = mark.takeIf { it.exists() }?.readText()?.split(' ')
            ?.let { (it[0].toIntOrNull() ?: 0) to (it.getOrNull(1)?.toLongOrNull() ?: 0L) } ?: (0 to 0L)
        RandomAccessFile(video, "rw").use { it.setLength(doneBytes) }
        report(doneSegs.toFloat() / segments.size)
        var lastWrite = 0L
        FileOutputStream(video, true).use { out ->
            for (i in doneSegs until segments.size) {
                coroutineContext.ensureActive()
                client.newCall(request(segments[i], referer)).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Segment ${i + 1}/${segments.size}: HTTP ${resp.code}")
                    resp.body?.byteStream()?.copyTo(out) ?: throw IOException("Empty segment")
                }
                mark.writeText("${i + 1} ${video.length()}")
                val now = System.currentTimeMillis()
                if (now - lastWrite > 1000) {
                    lastWrite = now
                    report((i + 1f) / segments.size)
                }
            }
        }
        mark.delete()
    }

    private suspend fun downloadFile(url: String, headers: Map<String, String>, sizeBytes: Long?, video: File, report: suspend (Float) -> Unit) {
        val existing = if (video.exists()) video.length() else 0L
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
                if (existing > 0) header("Range", "bytes=$existing-")
            }
            .build()
        report(0f)
        client.newCall(req).execute().use { resp ->
            if (resp.code == 416 && existing > 0) return@use // partial file is already whole
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty body")
            val resumed = resp.code == 206
            val start = if (resumed) existing else 0L
            val total = body.contentLength().takeIf { it > 0 }?.plus(start) ?: sizeBytes ?: -1L
            var done = start
            var lastWrite = 0L
            FileOutputStream(video, resumed).buffered().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastWrite > 1000) {
                            lastWrite = now
                            report(done.toFloat() / total)
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("Connection closed at $done/$total bytes")
        }
    }

    private fun request(url: String, referer: String?): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .apply { if (referer != null) header("Referer", referer) }
        .build()

    private suspend fun fetchText(url: String, referer: String?): String = withContext(Dispatchers.IO) {
        client.newCall(request(url, referer)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            resp.body?.string() ?: throw IOException("Empty body for $url")
        }
    }

    override suspend fun cancelDownload(episodeId: Long): Result<Unit> {
        return try {
            workManager.cancelUniqueWork(downloadWorkName(episodeId))
            deleteFiles(episodeId)
            dao.updateProgress(episodeId, 0f, DownloadState.FAILED.name)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error(e)
        }
    }

    override suspend fun setPoster(episodeId: Long, url: String) = dao.setPoster(episodeId, url)

    override suspend fun deleteDownload(episodeId: Long): Result<Unit> {
        return try {
            workManager.cancelUniqueWork(downloadWorkName(episodeId))
            deleteFiles(episodeId)
            dao.delete(episodeId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error(e)
        }
    }

    private suspend fun deleteFiles(episodeId: Long) {
        val path = dao.getById(episodeId)?.filePath ?: return
        File(path).delete()
        File("$path.part").delete()
        listOf("vtt", "srt").forEach { File(sidecarBase(path) + ".$it").delete() }
    }

    companion object {
        const val K_ID = "id"
        const val K_KIND = "kind"
        const val K_URL = "url"
        const val K_REFERER = "referer"
        const val K_SUB = "sub"
        const val K_SUB_MIME = "subMime"
        const val K_SIZE = "size"
        private const val HEADER_PREFIX = "h:"
    }

    private fun DownloadEntity.toDomain() = DownloadItem(
        episodeId = episodeId,
        animeTitle = animeTitle,
        episodeNumber = episodeNumber,
        quality = quality,
        filePath = filePath,
        fileSizeBytes = fileSizeBytes,
        progress = progress,
        state = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.PENDING),
        createdAt = createdAt,
        subtitle = subtitle,
        posterUrl = posterUrl,
        stillUrl = stillUrl,
        episodeTitle = episodeTitle
    )
}
