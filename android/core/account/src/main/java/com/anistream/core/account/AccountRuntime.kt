package com.anistream.core.account

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.anistream.core.database.ActiveProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires the account pieces to the app lifecycle: the hub runs only while an activity is started,
 * history is pulled on launch / profile switch / hub ping and pushed (debounced) after local writes.
 * Does nothing when REELM_API_URL is blank.
 */
@Singleton
class AccountRuntime @Inject constructor(
    private val account: AccountRepository,
    private val hub: HubClient,
    private val sync: HistorySync,
    private val favSync: FavoriteSync,
    private val api: AccountApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val foreground = MutableStateFlow(false)
    private var started = false

    @OptIn(FlowPreview::class)
    fun start(app: Application, kind: String) {
        if (!account.enabled || started) return
        started = true
        Telemetry.installCrashHandler(app)
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        val flush: suspend () -> Unit = {
            account.session.value?.let { s -> Telemetry.flush(api, s.token, s.activeProfile?.id, kind, version) }
        }
        scope.launch { while (true) { kotlinx.coroutines.delay(60_000); runCatching { flush() } } }
        scope.launch { foreground.collect { fg -> if (!fg) runCatching { flush() } } }

        var activities = 0
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { activities++; foreground.value = true }
            override fun onActivityStopped(a: Activity) { if (--activities <= 0) foreground.value = false }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        // hub: connected only in the foreground and while signed in; cancelling closes the socket
        scope.launch {
            combine(foreground, account.session.map { it?.token }.distinctUntilChanged()) { fg, t -> if (fg) t else null }
                .distinctUntilChanged()
                .collectLatest { t -> if (t != null) runCatching { hub.run(t, kind) } }
        }
        // pull on app start / return to foreground / profile switch
        scope.launch {
            combine(foreground, account.session.map { it?.activeProfile?.id }.distinctUntilChanged()) { fg, p -> fg && p != null }
                .distinctUntilChanged()
                .collectLatest { ok -> if (ok) runCatching { sync.pull(); sync.push() }; runCatching { favSync.pull(); favSync.push() } } // push too: rows saved just before the process died
        }
        // push shortly after local writes, and when leaving the app
        scope.launch { ActiveProfile.changes.debounce(5_000).collect { runCatching { sync.push() }; runCatching { favSync.push() } } }
        scope.launch { foreground.collect { fg -> if (!fg) { runCatching { sync.push() }; runCatching { favSync.push() } } } }
        scope.launch { hub.favoritePings.collect { p -> if (p == account.session.value?.activeProfile?.id) runCatching { favSync.pull() } } }
        // another device pushed history for our profile
        scope.launch { hub.historyPings.collect { p -> if (p == account.session.value?.activeProfile?.id) runCatching { sync.pull() } } }
    }
}
