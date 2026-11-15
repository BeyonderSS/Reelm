package com.anistream.feature.party

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import com.anistream.core.media.MediaHttp
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateUi(
    /** set = show the update dialog */
    val release: Release? = null,
    /** 0..1 while downloading, null otherwise */
    val progress: Float? = null,
    val checking: Boolean = false,
    /** one-line result of the last manual check / failure, shown in the menu and dialog */
    val status: String? = null,
    /** the downloaded APK's installer intent, so a notification can reopen it */
    val installIntent: Intent? = null
)

private const val LATEST = "https://api.github.com/repos/BeyonderSS/Reelm/releases/latest"
private const val EVERY_MS = 6 * 60 * 60 * 1000L

/** Checks for a newer release (Reelm API, else GitHub), downloads its APK and hands it to the system installer. */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: MediaHttp
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(UpdateUi())
    val ui: StateFlow<UpdateUi> = _ui.asStateFlow()

    private val tv = context.packageName.endsWith(".tv")
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val versionName: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    private val versionCode = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()

    /** App start: at most once every 6 h, and silent unless there is something to install. */
    fun autoCheck() {
        // a fresh process can't be mid-download, so any APK left in updates/ was already installed (or abandoned)
        scope.launch(Dispatchers.IO) {
            listOfNotNull(context.cacheDir, context.getExternalFilesDir(null)).forEach { File(it, "updates").deleteRecursively() }
        }
        prefs.getInt("update_pending_to", 0).takeIf { it > 0 }?.let { to ->
            if (versionCode >= to) com.anistream.core.account.Telemetry.track("update_installed", "to" to versionName)
            prefs.edit().remove("update_pending_to").apply()
        }
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("update_checked_at", 0L) < EVERY_MS) return
        prefs.edit().putLong("update_checked_at", now).apply()
        scope.launch { check(manual = false) }
    }

    /** "Check for updates": offers any newer release, even on debug/local builds, so it can be tested. */
    fun checkNow() {
        if (_ui.value.checking) return
        scope.launch { check(manual = true) }
    }

    fun dismiss() = _ui.update { it.copy(release = null, status = null, installIntent = null) }

    private suspend fun check(manual: Boolean) {
        _ui.update { it.copy(checking = true, status = null) }
        val release = try {
            latest()
        } catch (_: Exception) {
            _ui.update { it.copy(checking = false, status = if (manual) "Couldn't check for updates" else null) }
            return
        }
        val offer = release != null && (if (manual) release.versionCode > versionCode else shouldOffer(versionCode, release.versionCode, debuggable))
        if (offer && release != null) com.anistream.core.account.Telemetry.track("update_offered", "to" to release.name, "from" to versionName)
        _ui.update {
            it.copy(
                checking = false,
                release = if (offer) release else it.release,
                status = if (!manual || offer) null else "You're on the latest version ($versionName)"
            )
        }
    }

    /** The Reelm API (APKs on Cloudflare, fast where GitHub's CDN crawls); GitHub releases if it's unset or down. */
    private suspend fun latest(): Release? {
        val api = BuildConfig.REELM_API_URL
        if (api.isNotBlank()) runCatching { parseAppLatest(http.getJson("$api/v1/app/latest"), tv) }.getOrNull()?.let { return it }
        return parseRelease(http.getJson(LATEST), tv)
    }

    /** Download the APK, then open the installer. Safe to call again after granting "install unknown apps". */
    fun update() {
        val release = _ui.value.release ?: return
        if (_ui.value.progress != null) return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            start(settings)
            _ui.update { it.copy(status = "Allow installs from Reelm, then tap Update again") }
            return
        }
        scope.launch {
            _ui.update { it.copy(progress = 0f, status = null) }
            try {
                install(download(release))
                prefs.edit().putInt("update_pending_to", release.versionCode).apply()
                _ui.update { it.copy(progress = null) }
            } catch (_: Exception) {
                _ui.update { it.copy(progress = null, status = "Download failed, check your connection") }
            }
        }
    }

    private suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        // API 22 installers can't read a content:// URI, so they get a file:// in external app storage (world-readable path)
        val root = if (Build.VERSION.SDK_INT >= 24) context.cacheDir else context.getExternalFilesDir(null) ?: context.cacheDir
        val dir = File(root, "updates").apply { mkdirs() }
        // named per version, so tapping Update again after a dropped connection resumes the same APK
        val file = File(dir, "Reelm-${release.versionCode}.apk")
        dir.listFiles()?.filter { it != file }?.forEach { it.deleteRecursively() }
        var attempt = 0
        while (true) {
            try {
                fetch(release, file)
                break
            } catch (e: IOException) {
                if (++attempt >= 4) throw e
                delay(2_000L * attempt)
            }
        }
        val bad = (release.sizeBytes > 0 && file.length() != release.sizeBytes) || (release.sha256 != null && sha256(file) != release.sha256)
        if (bad) {
            file.delete()
            throw IOException("Corrupt download")
        }
        file.setReadable(true, false)
        file
    }

    /** Appends to a partial [file] with a Range request; a server that ignores Range (plain 200) starts it over. */
    private fun fetch(release: Release, file: File) {
        val have = file.length()
        val request = Request.Builder().url(release.apkUrl).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.client.newCall(request).execute().use { response ->
            if (response.code == 416 && have > 0) return // already whole; the size/sha check decides
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val resume = have > 0 && response.code == 206
            if (resume && response.header("Content-Range")?.startsWith("bytes $have-") != true) {
                file.delete()
                throw IOException("Unexpected range")
            }
            val body = response.body ?: throw IOException("Empty body")
            var done = if (resume) have else 0L
            val total = body.contentLength().takeIf { it > 0 }?.plus(done) ?: release.sizeBytes
            body.byteStream().use { input ->
                FileOutputStream(file, resume).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _ui.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(file: File) {
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(context, "${context.packageName}.updates", file) else Uri.fromFile(file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        _ui.update { it.copy(installIntent = intent) }
        start(Intent(intent))
    }

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            _ui.update { it.copy(status = "This device can't install updates from the app") }
        }
    }
}

/** Gives composables the singleton [UpdateManager]. */
@HiltViewModel
class UpdateViewModel @Inject constructor(val updates: UpdateManager) : ViewModel()
