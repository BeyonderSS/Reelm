package com.anistream.feature.party

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.DisplayHeavy
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

const val GUEST = "Guest"
const val DIFFERENT_COPY = "Playing a different copy — scenes may be off by a few minutes"

/**
 * Keeps this player in step with the room while [client] has one. Local play/pause/seek go out unless [EchoGuard]
 * holds them; incoming events apply `position + (serverNow - at)` once the player is ready, unless within [DRIFT_MS].
 */
private class Sync(val player: Player, val client: PartyClient, host: Boolean) : Player.Listener {
    private val guard = EchoGuard(SystemClock::elapsedRealtime, host)
    private var pendingInitial = !host
    private val main = Handler(Looper.getMainLooper())

    init {
        if (host) send(if (player.playWhenReady) "play" else "pause")
        else if (player.playbackState == Player.STATE_READY) initial()
    }

    private fun send(type: String) { if (guard.mayBroadcast()) client.send(type, player.currentPosition) }

    fun apply(e: PartyEvent) {
        if (pendingInitial) return // the room's latest state waits in room.last until the player is ready
        main.removeCallbacksAndMessages(null)
        val playing = when (e.type) { "play" -> true; "pause" -> false; else -> player.playWhenReady }
        val now = client.serverNow()
        val target = targetPosition(e.position, e.at, now, playing)
        guard.applying()
        if (shouldSeek(player.currentPosition, target)) player.seekTo(target)
        if (playing && e.at > now) {
            // "Lights down": hold at the frame, start when the 3-2-1 ends
            player.playWhenReady = false
            main.postDelayed({ guard.applying(); player.playWhenReady = true }, e.at - now)
        } else {
            player.playWhenReady = playing
        }
    }

    private fun initial() {
        pendingInitial = false
        client.room.value?.last?.let(::apply)
        guard.joined()
    }

    fun release() = main.removeCallbacksAndMessages(null)

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY && pendingInitial) initial()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) send(if (playWhenReady) "play" else "pause")
    }

    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && guard.mayBroadcast()) client.send("seek", new.positionMs)
    }
}

/**
 * Drop into the player's root Box: syncs [player] with the current party (nothing when there is none).
 * Phone: hosts the party sheet, ducks the film while people talk, and shows the notes, the 3-2-1 and the shared
 * heart; reports [source] (the copy playing) or warns when it differs from the room's. TV: the member avatar strip.
 */
@Composable
fun PartyOverlay(player: Player, modifier: Modifier = Modifier, payload: JsonObject? = null, source: JsonObject? = null, vm: PartyViewModel = hiltViewModel()) {
    val room by vm.client.room.collectAsStateWithLifecycle()
    val rtc = vm.rtc
    if (vm.sheet && payload != null && rtc != null) PartySheet(payload, vm, rtc)
    val r = room ?: return
    val sync = remember(player) { Sync(player, vm.client, r.host) }
    DisposableEffect(sync) {
        player.addListener(sync)
        onDispose {
            player.removeListener(sync)
            sync.release()
            player.volume = 1f
            // leaving the player leaves the party (and stops camera and mic), unless a `load` is swapping this screen for the next one
            if (vm.client.switching) vm.client.switching = false else vm.client.leave()
        }
    }
    // we switched episode/movie: tell the room (the server pauses everyone at 0), then resume it if we are playing
    LaunchedEffect(payload) {
        if (payload != null && !samePayload(payload, vm.client.room.value?.payload)) {
            vm.client.load(payload)
            if (player.playWhenReady) vm.client.send("play", player.currentPosition)
        }
    }
    LaunchedEffect(sync) {
        vm.client.events.collect { if (!isEcho(it.by, vm.client.selfId)) sync.apply(it) }
    }
    // the first player's copy becomes the room's; anyone on another copy is told scenes may be off
    LaunchedEffect(source, r.source) {
        val rs = r.source
        if (source == null) return@LaunchedEffect
        if (rs == null) vm.client.reportSource(source) else if (!sameSource(source, rs)) vm.say(DIFFERENT_COPY, 6000)
    }
    if (rtc == null) {
        Row(
            modifier.statusBarsPadding().padding(Space.md),
            horizontalArrangement = Arrangement.spacedBy((-6).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            r.members.take(6).forEach { Avatar(it) }
            if (r.members.size > 6) Text("+${r.members.size - 6}", style = AppType.caption, modifier = Modifier.padding(start = Space.sm + 6.dp))
        }
        return
    }
    LaunchedEffect(rtc) { rtc.duck.collect { player.volume = it } }
    Box(Modifier.fillMaxSize()) {
        vm.note?.let {
            Text(
                it, style = AppType.label, maxLines = 2,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp, start = Space.lg, end = Space.lg)
                    .background(Bg.badge, Radius.pill).padding(horizontal = Space.md, vertical = 6.dp)
            )
        }
        r.countdownAt?.let { Countdown(it, vm.client, Modifier.align(Alignment.Center)) }
        vm.syncHeart?.let { at ->
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("❤️❤️", fontSize = 44.sp)
                Text("You both tapped at ${clock(at)}", style = AppType.label, modifier = Modifier.background(Bg.badge, Radius.pill).padding(horizontal = Space.md, vertical = 4.dp))
            }
        }
    }
}

/** 3-2-1 until server time [at], then nothing. */
@Composable
private fun Countdown(at: Long, client: PartyClient, modifier: Modifier) {
    var left by remember(at) { mutableLongStateOf(at - client.serverNow()) }
    LaunchedEffect(at) { while (left > 0) { delay(100); left = at - client.serverNow() } }
    if (left > 0) Text(
        "${((left + 999) / 1000).coerceAtMost(3)}",
        style = AppType.title.copy(fontFamily = DisplayHeavy, fontSize = 120.sp, lineHeight = 120.sp, color = Accent.main),
        modifier = modifier
    )
}

@Composable
private fun Avatar(m: Member) {
    Box(
        Modifier.size(28.dp).background(Bg.overlay, Radius.round),
        contentAlignment = Alignment.Center
    ) { Text(m.name.take(1).uppercase(), style = AppType.label) }
}

/** Player top bar: opens the party sheet (start a party, or the live one). Amber while in a room. */
@Composable
fun PartyButton(vm: PartyViewModel = hiltViewModel()) {
    if (!vm.client.enabled || vm.rtc == null) return
    val room by vm.client.room.collectAsStateWithLifecycle()
    IconButton(onClick = { vm.sheet = true }) {
        Icon(Icons.Rounded.Groups, contentDescription = "Watch together", tint = if (room != null) Accent.main else Ink.high)
    }
}

/** Code entry; on success hands the destination to [onJoined]. */
@Composable
fun JoinPartyDialog(onDismiss: () -> Unit, onJoined: (PartyDestination) -> Unit, vm: PartyViewModel = hiltViewModel()) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bg.raised,
        shape = Radius.xl,
        title = { Text("Join party", style = AppType.heading) },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isLetterOrDigit).uppercase().take(6); error = null },
                    singleLine = true,
                    placeholder = { Text("6-character code") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    isError = error != null
                )
                error?.let { Text(it, style = AppType.caption.copy(color = Accent.danger), modifier = Modifier.padding(top = Space.sm)) }
            }
        },
        confirmButton = {
            TextButton(enabled = code.length == 6 && !busy, onClick = {
                busy = true
                scope.launch {
                    val dest = vm.join(code)
                    busy = false
                    if (dest == null) error = "Couldn't join that party" else onJoined(dest)
                }
            }) { Text(if (busy) "Joining…" else "Join", color = Accent.main) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Ink.body) } }
    )
}

/** Phone "Reelm" menu: Join party + Check for updates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReelmMenu(onDismiss: () -> Unit, onOpen: (PartyDestination) -> Unit, vm: UpdateViewModel = hiltViewModel(), party: PartyViewModel = hiltViewModel()) {
    val ui by vm.updates.ui.collectAsStateWithLifecycle()
    var joining by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Bg.raised) {
        Column(Modifier.padding(horizontal = Space.lg).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text("Reelm", style = AppType.heading)
            Text("Version ${vm.updates.versionName}", style = AppType.caption)
            if (party.client.enabled) MenuRow("Join party", "Watch along with a friend using their code") { joining = true }
            MenuRow(if (ui.checking) "Checking…" else "Check for updates", ui.status) { vm.updates.checkNow() }
            // the setting pads itself by the 16dp gutter, which here lines up with the rows' inner padding
            Box(Modifier.fillMaxWidth().background(Bg.overlay, Radius.md)) { com.anistream.core.trailer.PreviewTrailersSetting(vm.trailers) }
        }
    }
    if (joining) JoinPartyDialog(onDismiss = { joining = false }, onJoined = { joining = false; onDismiss(); onOpen(it) })
}

@Composable
private fun MenuRow(label: String, caption: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Bg.overlay, Radius.md).clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, style = AppType.bodyStrong)
        if (caption != null) Text(caption, style = AppType.caption)
    }
}

/** Phone update prompt. [UpdateHost] runs the start-up check; both flavors share [UpdateManager]. */
@Composable
fun UpdateHost(vm: UpdateViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.updates.autoCheck() }
    val ui by vm.updates.ui.collectAsStateWithLifecycle()
    val release = ui.release ?: return
    AlertDialog(
        onDismissRequest = { if (ui.progress == null) vm.updates.dismiss() },
        containerColor = Bg.raised,
        shape = Radius.xl,
        title = { Text(release.name, style = AppType.heading) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (release.body.isNotBlank()) Text(release.body, style = AppType.body)
                ui.progress?.let {
                    Spacer(Modifier.height(Space.md))
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = Accent.main, trackColor = Ink.faint)
                }
                ui.status?.let { Text(it, style = AppType.caption.copy(color = Accent.danger), modifier = Modifier.padding(top = Space.sm)) }
            }
        },
        confirmButton = {
            TextButton(enabled = ui.progress == null, onClick = vm.updates::update) {
                Text(if (ui.progress == null) "Update" else "Downloading ${(ui.progress!! * 100).toInt()}%", color = Accent.main)
            }
        },
        dismissButton = { TextButton(enabled = ui.progress == null, onClick = vm.updates::dismiss) { Text("Later", color = Ink.body) } }
    )
}
