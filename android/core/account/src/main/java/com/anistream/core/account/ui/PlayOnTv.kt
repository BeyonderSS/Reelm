package com.anistream.core.account.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anistream.core.account.AccountRepository
import com.anistream.core.account.Device
import com.anistream.core.account.HubClient
import com.anistream.core.account.Now
import com.anistream.core.account.deviceLabel
import com.anistream.core.ui.components.ArtworkImage
import com.anistream.core.ui.components.MarqueeTitle
import com.anistream.core.ui.components.SecondaryButton
import com.anistream.core.ui.components.marqueeSplit
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import com.anistream.domain.model.WatchHistory
import com.anistream.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The phone remote: which TV it shows, what this phone last sent there, and how the player that sent it resumes. */
object TvRemote {
    val open = MutableStateFlow<String?>(null)
    @Volatile var lastSent: WatchHistory? = null
    @Volatile var resumeLocal: ((Long) -> Unit)? = null
}

@HiltViewModel
class PlayOnTvViewModel @Inject constructor(
    private val account: AccountRepository,
    val hub: HubClient,
    private val history: WatchHistoryRepository
) : ViewModel() {
    val session = account.session
    val devices = hub.devices
    val states = hub.states
    val enabled get() = account.enabled

    /** Online TVs, the last one chosen first. */
    fun tvs(all: List<Device>) = hub.tvs(all).sortedByDescending { it.id == account.lastTv }

    /** Sends [item]; with no position (detail pages) the phone's own progress on that episode goes with it. */
    suspend fun send(tv: Device, item: WatchHistory, positionMs: Long, source: String?): Boolean {
        val profile = account.session.value?.activeProfile?.id ?: return false
        var h = item
        var ms = positionMs
        if (ms <= 0) history.getBySlug(item.animeSlug).getOrNull()
            ?.takeIf { it.lastEpisodeWatched == item.lastEpisodeWatched && it.season == item.season }
            ?.let { row -> h = item.copy(watchProgress = row.watchProgress, durationMs = row.durationMs, eps = row.eps); ms = (row.watchProgress * row.durationMs).toLong() }
        if (!hub.sendHandoff(tv, profile, h, ms, source)) return false
        account.lastTv = tv.id
        TvRemote.lastSent = h
        TvRemote.open.value = tv.id
        return true
    }

    /** "Play here" with no player open: store the TV's position so the phone's player resumes from it. */
    suspend fun keepPosition(h: WatchHistory, now: Now?, ms: Long) {
        val dur = now?.durationMs?.takeIf { it > 0 } ?: h.durationMs
        history.save(h.copy(watchProgress = if (dur > 0) (ms.toFloat() / dur).coerceIn(0f, 1f) else h.watchProgress, durationMs = dur, lastWatchedTimestamp = System.currentTimeMillis()))
    }
}

/**
 * Cast-style "Play on TV" button, shown only while a TV on the account is online. One TV: sends straight to it;
 * several: a picker. After sending it calls [onSent] (pause here) and opens the remote; the remote's "Play here"
 * comes back through [onPlayHere] while this button is on screen. [circle] = the detail pages' round action style.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayOnTvButton(
    item: () -> WatchHistory?,
    positionMs: () -> Long = { 0L },
    source: () -> String? = { null },
    onSent: () -> Unit = {},
    onPlayHere: ((Long) -> Unit)? = null,
    circle: Boolean = false
) {
    val vm: PlayOnTvViewModel = hiltViewModel()
    val session by vm.session.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    val resume = remember { mutableStateOf<((Long) -> Unit)?>(null) }
    DisposableEffect(Unit) { onDispose { if (TvRemote.resumeLocal === resume.value) TvRemote.resumeLocal = null } }
    val tvs = vm.tvs(devices)
    if (!vm.enabled || session?.activeProfile == null || tvs.isEmpty()) return
    val send = { tv: Device ->
        picking = false
        val h = item()
        if (h != null) scope.launch {
            if (vm.send(tv, h, positionMs(), source())) {
                onSent()
                resume.value = onPlayHere
                TvRemote.resumeLocal = onPlayHere
            }
        }
    }
    val click = { if (tvs.size == 1) send(tvs[0]) else picking = true }
    if (circle) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(52.dp).clip(Radius.round).background(Bg.raised).clickable(onClick = click)
        ) { Icon(Icons.Rounded.Cast, contentDescription = "Play on TV", tint = Ink.body) }
    } else IconButton(onClick = click) { Icon(Icons.Rounded.Cast, contentDescription = "Play on TV", tint = Ink.high) }

    if (picking) ModalBottomSheet(onDismissRequest = { picking = false }, containerColor = Bg.raised) {
        Column(Modifier.padding(horizontal = Space.lg).padding(bottom = Space.xl)) {
            Text("Play on TV", style = AppType.heading)
            Spacer(Modifier.height(Space.sm))
            tvs.forEach { tv ->
                val now = states[tv.id]
                Row(
                    Modifier.fillMaxWidth().clip(Radius.md).clickable { send(tv) }.padding(horizontal = Space.md, vertical = Space.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Tv, contentDescription = null, tint = if (tv.id == tvs[0].id) Accent.main else Ink.body)
                    Column(Modifier.padding(start = Space.md)) {
                        Text(deviceLabel(tv, tvs), style = AppType.bodyStrong)
                        Text(now?.let { listOfNotNull(it.title, it.subtitle).joinToString(" · ") } ?: "Idle", style = AppType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** "Playing on <TV>" strip for above the bottom nav; tap opens the remote. Nothing while no TV is playing. */
@Composable
fun PlayingOnTvBar() {
    val vm: PlayOnTvViewModel = hiltViewModel()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val tvs = vm.tvs(devices)
    val tv = tvs.firstOrNull { states[it.id] != null } ?: return
    val now = states[tv.id] ?: return
    Row(
        Modifier.fillMaxWidth().background(Bg.bar).clickable { TvRemote.open.value = tv.id }.padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkImage(now.poster, null, Modifier.width(32.dp).aspectRatio(2f / 3f).clip(Radius.sm))
        Column(Modifier.weight(1f).padding(horizontal = Space.md)) {
            Text("Playing on ${deviceLabel(tv, tvs)}", style = AppType.caption.copy(color = Accent.text), maxLines = 1)
            Text(listOfNotNull(now.title, now.subtitle).joinToString(" · "), style = AppType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { vm.hub.sendControl(tv.id, "toggle") }) {
            Icon(if (now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (now.playing) "Pause" else "Play", tint = Ink.high)
        }
    }
}

/**
 * The remote screen, shown over everything while [TvRemote.open] names a TV. Mount once at the app root.
 * [onPlayHere] opens the phone's player for the item this phone sent, when no player of it is open any more.
 */
@Composable
fun TvRemoteHost(onPlayHere: (WatchHistory) -> Unit) {
    val id by TvRemote.open.collectAsStateWithLifecycle()
    val tvId = id ?: return
    val vm: PlayOnTvViewModel = hiltViewModel()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val tvs = vm.tvs(devices)
    val tv = tvs.firstOrNull { it.id == tvId }
    val now = states[tvId]
    val t by produceState(System.currentTimeMillis()) { while (true) { delay(500); value = System.currentTimeMillis() } }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val close = { TvRemote.open.value = null }
    val ctl = { action: String, value: Long? -> vm.hub.sendControl(tvId, action, value); Unit }
    val sent = TvRemote.lastSent
    val pos = now?.positionAt(t) ?: 0L
    val dur = now?.durationMs ?: 0L

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Bg.base).statusBarsPadding().navigationBarsPadding().padding(horizontal = Space.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", tint = Ink.high) }
                Icon(Icons.Rounded.CastConnected, contentDescription = null, tint = Accent.main, modifier = Modifier.padding(start = Space.sm).size(18.dp))
                Text(tv?.let { deviceLabel(it, tvs) } ?: "TV", style = AppType.captionStrong, modifier = Modifier.padding(start = Space.sm))
            }
            Spacer(Modifier.weight(1f))
            ArtworkImage(now?.poster ?: sent?.posterUrl, null, Modifier.width(180.dp).aspectRatio(2f / 3f).clip(Radius.md))
            Spacer(Modifier.height(Space.xl))
            val (solid, outline) = marqueeSplit(now?.title ?: sent?.animeTitle.orEmpty())
            MarqueeTitle(solid, outline, size = 28)
            Text(
                when {
                    tv == null -> "The TV went offline"
                    now == null -> "Starting on the TV…"
                    else -> now.subtitle.orEmpty()
                },
                style = AppType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs)
            )
            Spacer(Modifier.height(Space.xl))
            Slider(
                value = scrub ?: if (dur > 0) pos.toFloat() / dur else 0f,
                onValueChange = { scrub = it },
                onValueChangeFinished = { scrub?.let { ctl("seekTo", (it * dur).toLong()) }; scrub = null },
                enabled = now != null && dur > 0,
                colors = SliderDefaults.colors(thumbColor = Accent.main, activeTrackColor = Accent.main, inactiveTrackColor = Ink.hairline)
            )
            Row(Modifier.fillMaxWidth()) {
                Text(clock(scrub?.let { (it * dur).toLong() } ?: pos), style = AppType.numeric)
                Spacer(Modifier.weight(1f))
                Text(if (dur > 0) clock(dur) else "", style = AppType.numeric)
            }
            Spacer(Modifier.height(Space.lg))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                RemoteIcon(Icons.Rounded.SkipPrevious, "Previous episode", now?.hasPrev == true) { ctl("prev", null) }
                RemoteIcon(Icons.Rounded.Replay10, "Back 10 seconds", now != null) { ctl("seekBy", -10_000L) }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(72.dp).clip(Radius.round).background(if (now != null) Accent.main else Ink.hairline)
                        .clickable(enabled = now != null) { ctl("toggle", null) }
                ) {
                    Icon(if (now?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (now?.playing == true) "Pause" else "Play", tint = Accent.on, modifier = Modifier.size(36.dp))
                }
                RemoteIcon(Icons.Rounded.Forward10, "Forward 10 seconds", now != null) { ctl("seekBy", 10_000L) }
                RemoteIcon(Icons.Rounded.SkipNext, "Next episode", now?.hasNext == true) { ctl("next", null) }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                val local = TvRemote.resumeLocal
                val here = sent?.let { hereFor(it, now) }
                val moved = here != null && (here.lastEpisodeWatched != sent?.lastEpisodeWatched || here.season != sent?.season)
                if (here != null || (local != null && sent == null)) SecondaryButton("Play here", {
                    ctl("stop", null)
                    close()
                    if (local != null && !moved) local(pos) else if (here != null) scope.launch { vm.keepPosition(here, now, pos); onPlayHere(here) }
                }, Modifier.weight(1f), icon = Icons.Rounded.PhoneAndroid)
                SecondaryButton("Stop on TV", { ctl("stop", null); close() }, Modifier.weight(1f), icon = Icons.Rounded.Stop, enabled = now != null)
            }
        }
    }
}

/** [h] moved to the episode the TV shows now (subtitle "S2 · E5 · …" or "Episode 5"); null = the TV plays another title. */
internal fun hereFor(h: WatchHistory, n: Now?): WatchHistory? {
    if (n == null) return h
    if (!n.title.lowercase().contains(h.animeTitle.lowercase().take(12))) return null
    val m = Regex("""(?:S(\d+)\D+)?E(?:pisode )?(\d+)""").find(n.subtitle.orEmpty()) ?: return h
    return h.copy(season = m.groupValues[1].toIntOrNull() ?: h.season, lastEpisodeWatched = m.groupValues[2].toInt())
}

@Composable
private fun RemoteIcon(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp)) {
        Icon(icon, contentDescription = label, tint = if (enabled) Ink.high else Ink.faint, modifier = Modifier.size(30.dp))
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
