package com.anistream.feature.party

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.anistream.core.ui.components.PrimaryButton
import com.anistream.core.ui.components.SecondaryButton
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val GUEST = "Guest"

/**
 * Keeps this player in step with the room while [client] has one. Local play/pause/seek go out;
 * incoming events apply `position + (serverNow - at)` unless they are our own echo or within [DRIFT_MS].
 */
private class Sync(val player: Player, val client: PartyClient, host: Boolean) : Player.Listener {
    private var muteUntil = 0L
    private var pendingInitial = !host

    init {
        if (host) send(if (player.playWhenReady) "play" else "pause")
        else if (player.playbackState == Player.STATE_READY) initial()
    }

    private fun muted() = SystemClock.elapsedRealtime() < muteUntil
    private fun send(type: String) = client.send(type, player.currentPosition)

    fun apply(e: PartyEvent) {
        val playing = when (e.type) { "play" -> true; "pause" -> false; else -> player.playWhenReady }
        val target = targetPosition(e.position, e.at, client.serverNow(), playing)
        // the player echoes our own changes back through the listener; skip those briefly
        muteUntil = SystemClock.elapsedRealtime() + 400
        if (shouldSeek(player.currentPosition, target)) player.seekTo(target)
        player.playWhenReady = playing
    }

    private fun initial() {
        pendingInitial = false
        client.room.value?.last?.let(::apply)
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY && pendingInitial) initial()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && !muted()) send(if (playWhenReady) "play" else "pause")
    }

    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && !muted()) client.send("seek", new.positionMs)
    }
}

/**
 * Drop into the player's root Box: syncs [player] with the current party (nothing when there is none)
 * and draws the member avatar strip at the top end.
 */
@Composable
fun PartyOverlay(player: Player, modifier: Modifier = Modifier, payload: JsonObject? = null, vm: PartyViewModel = hiltViewModel()) {
    val room by vm.client.room.collectAsStateWithLifecycle()
    val r = room ?: return
    val sync = remember(player) { Sync(player, vm.client, r.host) }
    DisposableEffect(sync) {
        player.addListener(sync)
        onDispose {
            player.removeListener(sync)
            // leaving the player leaves the party, unless a `load` is swapping this screen for the next one
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
    Row(
        modifier.statusBarsPadding().padding(Space.md),
        horizontalArrangement = Arrangement.spacedBy((-6).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        r.members.take(6).forEach { Avatar(it) }
        if (r.members.size > 6) Text("+${r.members.size - 6}", style = AppType.caption, modifier = Modifier.padding(start = Space.sm + 6.dp))
    }
}

@Composable
private fun Avatar(m: Member) {
    Box(
        Modifier.size(28.dp).background(Bg.overlay, Radius.round),
        contentAlignment = Alignment.Center
    ) { Text(m.name.take(1).uppercase(), style = AppType.label) }
}

/** Phone player sheet: "Watch together" (create a room, show the code, share it) or the live room with Leave. */
@Composable
fun WatchTogetherSection(payload: JsonObject, vm: PartyViewModel = hiltViewModel()) {
    if (!vm.client.enabled) return
    val room by vm.client.room.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Spacer(Modifier.height(Space.xl))
    Text("Watch together", style = AppType.heading)
    Spacer(Modifier.height(Space.sm))
    val r = room
    if (r == null) {
        PrimaryButton(if (busy) "Creating room…" else "Start a party", enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true; failed = false
            scope.launch { failed = vm.create(payload) == null; busy = false }
        })
        if (failed) Text("Couldn't create a room", style = AppType.caption.copy(color = Accent.danger), modifier = Modifier.padding(top = Space.sm))
    } else {
        Text(r.code, style = AppType.heading.copy(letterSpacing = 6.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text("${r.members.size} watching. Friends enter this code under Join party.", style = AppType.caption, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            PrimaryButton("Share", modifier = Modifier.weight(1f), onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Watch ${(payload["title"] as? JsonPrimitive)?.content ?: "with me"} on Reelm. Join party code: ${r.code}")
                context.startActivity(Intent.createChooser(send, "Share party code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            SecondaryButton("Leave", modifier = Modifier.weight(1f), onClick = vm.client::leave)
        }
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
