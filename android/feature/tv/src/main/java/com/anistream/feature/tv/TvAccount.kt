package com.anistream.feature.tv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.anistream.core.account.AccountRepository
import com.anistream.core.account.ApiException
import com.anistream.core.account.FluentEmojis
import com.anistream.core.account.Handoff
import com.anistream.core.account.HistorySync
import com.anistream.core.account.HubClient
import com.anistream.core.account.Profile
import com.anistream.core.account.qrBitmap
import com.anistream.core.account.ui.AccountViewModel
import com.anistream.core.account.ui.AvatarCluster
import com.anistream.core.account.ui.lamp
import com.anistream.core.ui.R
import com.anistream.core.ui.components.ReelmSplash
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Display
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.domain.model.WatchHistory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PairUi(val code: String? = null, val qr: Bitmap? = null, val error: String? = null)

/** TV sign-in: shows a QR for the pairing code and polls until a signed-in phone claims it. The TV never asks for a password. */
@HiltViewModel
class TvAccountViewModel @Inject constructor(val repo: AccountRepository, val hub: HubClient, val sync: HistorySync) : ViewModel() {
    val session = repo.session

    private val _pair = MutableStateFlow(PairUi())
    val pair: StateFlow<PairUi> = _pair.asStateFlow()

    /** Runs until signed in or the screen leaves (the caller's coroutine is cancelled). */
    suspend fun pairLoop() {
        while (true) {
            val p = try { repo.startPair() } catch (e: Exception) {
                _pair.value = PairUi(error = "Can't reach Reelm. Trying again."); delay(5_000); continue
            }
            val qr = withContext(Dispatchers.Default) { qrBitmap("${repo.baseUrl}/pair/${p.code}", 360) }
            _pair.value = PairUi(p.code, qr)
            // poll every 3 s; a failed or expired code just gets replaced by a fresh one
            val deadline = if (p.expiresAt > 0) p.expiresAt else System.currentTimeMillis() + 9 * 60_000
            while (System.currentTimeMillis() < deadline) {
                delay(3_000)
                val done = try { repo.pollPair(p.code) } catch (e: Exception) { if ((e as? ApiException)?.code in 400..499) break else false }
                if (done) return
            }
        }
    }

    /** Continue on TV: store the phone's row (so the player resumes from its progress) and return it. */
    suspend fun applyHandoff(h: Handoff): WatchHistory? = sync.applyHandoff(h)

    fun signOut() { viewModelScope.launch { repo.logout() } }
}

private enum class TvPage { Qr, Edit }

/**
 * TV root: the launch animation (a cheap short cut in low-end mode), then the gate until a profile or guest mode
 * is picked; the pick is remembered so later launches go straight to [content]. The rail's profile button reopens it.
 */
@Composable
fun TvProfileGate(content: @Composable () -> Unit) {
    val vm: AccountViewModel = hiltViewModel()
    val gate by vm.gate.collectAsStateWithLifecycle()
    var splash by rememberSaveable { mutableStateOf(true) }
    val lowEnd = LocalLowEnd.current
    Box(Modifier.fillMaxSize()) {
        // the app is left out of composition while the gate shows, so nothing behind it can take D-pad focus
        if (gate) TvGate(vm) else content()
        if (splash) ReelmSplash(lite = lowEnd) { splash = false }
    }
}

@Composable
private fun TvGate(vm: AccountViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf<TvPage?>(null) }
    var editId by rememberSaveable { mutableStateOf("") }
    var manage by rememberSaveable { mutableStateOf(false) }
    val signedIn = session != null
    LaunchedEffect(signedIn) {
        if (signedIn) { vm.refresh(); if (page == TvPage.Qr) page = null } else { manage = false; page = if (page == TvPage.Qr) page else null }
    }
    val go: (TvPage?) -> Unit = { vm.clearStatus(); page = it }
    BackHandler(page != null) { go(null) }
    BackHandler(page == null && manage) { manage = false }
    BackHandler(page == null && !manage && vm.repo.hasChoice) { vm.repo.closeGate() }

    Box(Modifier.fillMaxSize().background(Bg.base).lamp(if (LocalLowEnd.current) 0.1f else 0.14f)) {
        when {
            page == TvPage.Qr -> TvQrSignIn(onBack = { go(null) })
            page == TvPage.Edit -> TvProfileEditor(vm, profiles.firstOrNull { it.id == editId }, canDelete = profiles.size > 1, onDone = { go(null) })
            !signedIn -> TvWelcome(onSignIn = { go(TvPage.Qr) }, onGuest = vm::guest)
            else -> TvWhosWatching(
                profiles = profiles,
                activeId = session?.activeProfile?.id,
                manage = manage,
                onPick = { p -> if (manage) { editId = p.id; go(TvPage.Edit) } else vm.select(p) },
                onAdd = { editId = ""; go(TvPage.Edit) },
                onManage = { manage = !manage },
                onSignOut = vm::signOut,
                onClearHistory = vm::clearHistory
            )
        }
    }
}

/** First focus once the screen is laid out (and again when [key] changes). */
@Composable
private fun rememberFirstFocus(key: Any? = Unit): FocusRequester {
    val r = remember { FocusRequester() }
    LaunchedEffect(key) { runCatching { r.requestFocus() } }
    return r
}

@Composable
private fun Wordmark(size: Dp = 36.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Reelm" }) {
        Image(painterResource(R.drawable.reelm_reel), contentDescription = null, modifier = Modifier.size(size))
        Spacer(Modifier.width(size / 3))
        Text("Reelm", style = TvType.headline.copy(fontSize = (size.value * 0.8f).sp, fontFamily = Display, fontWeight = FontWeight.Bold))
    }
}

// ---------------------------------------------------------------- welcome (signed out)

@Composable
private fun TvWelcome(onSignIn: () -> Unit, onGuest: () -> Unit) {
    val first = rememberFirstFocus()
    Row(Modifier.fillMaxSize().padding(horizontal = Overscan, vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Wordmark()
            Spacer(Modifier.height(36.dp))
            Text("Welcome to Reelm", style = TvType.display)
            Spacer(Modifier.height(12.dp))
            Text(
                "Sign in with your phone to bring your profiles and watch history to this TV.",
                style = TvType.body, modifier = Modifier.width(460.dp)
            )
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvPill("Sign in with phone", onSignIn, Modifier.focusRequester(first), icon = Icons.Outlined.QrCode2, large = true)
                TvPill("Continue as guest", onGuest, large = true)
            }
            Spacer(Modifier.height(16.dp))
            Text("As a guest, your history stays on this TV.", style = TvType.body.copy(color = Ink.muted))
        }
        Box(Modifier.width(280.dp), contentAlignment = Alignment.Center) { AvatarCluster(scale = 0.9f) }
    }
}

// ---------------------------------------------------------------- who's watching

private val TileSize = 116.dp

@Composable
private fun TvWhosWatching(
    profiles: List<Profile>,
    activeId: String?,
    manage: Boolean,
    onPick: (Profile) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    onSignOut: () -> Unit,
    onClearHistory: () -> Unit
) {
    // the gate replaces the app (and its update host) while it shows, so it hosts the update dialog itself
    val updates: com.anistream.feature.party.UpdateViewModel = hiltViewModel()
    val updateUi by updates.updates.ui.collectAsStateWithLifecycle()
    TvUpdateHost(updates)
    var confirm by remember { mutableStateOf(false) }
    if (confirm) TvConfirm("Clear watch history for this profile? It empties on all your devices.", "Clear all", { confirm = false; onClearHistory() }, { confirm = false })
    if (profiles.isEmpty()) { TvMessage("Loading profiles"); return }
    // land on the profile in use (or the first), and again after leaving manage mode
    val focusIndex = profiles.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    val first = rememberFirstFocus(manage to profiles.size)
    Column(Modifier.fillMaxSize().padding(horizontal = Overscan), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (manage) "Manage profiles" else "Who's watching?", style = TvType.display)
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            profiles.forEachIndexed { i, p ->
                TvProfileTile(
                    p.name, p.avatar, active = p.id == activeId && !manage, editing = manage, onClick = { onPick(p) },
                    modifier = if (i == focusIndex) Modifier.focusRequester(first) else Modifier
                )
            }
            if (profiles.size < 5) TvProfileTile("Add profile", null, active = false, editing = false, onClick = onAdd)
        }
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (manage) TvPill("Done", onManage)
            else {
                TvPill("Manage profiles", onManage, icon = Icons.Rounded.Edit)
                TvPill("Clear history", { confirm = true })
                TvPill("Sign out", onSignOut)
                TvPill(if (updateUi.checking) "Checking…" else "Check for updates", updates.updates::checkNow, enabled = !updateUi.checking)
            }
        }
    }
}

/** Rounded profile tile: amber ring, lift and glow on focus; the name brightens with it. [avatarId] null = Add profile. */
@Composable
private fun TvProfileTile(name: String, avatarId: String?, active: Boolean, editing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(TileSize + 8.dp)) {
        Card(
            onClick = onClick,
            modifier = modifier.size(TileSize).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = if (avatarId == null) name else if (editing) "Edit $name" else name },
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(containerColor = if (avatarId == null) Bg.raised else Bg.overlay, focusedContainerColor = Bg.overlay),
            scale = CardDefaults.scale(focusedScale = if (reduce) 1f else 1.08f),
            glow = CardDefaults.glow(focusedGlow = focusGlow()),
            border = CardDefaults.border(
                border = if (active) Border(BorderStroke(2.dp, Accent.main.copy(alpha = 0.6f)), shape = shape) else Border(BorderStroke(1.dp, Ink.hairline), shape = shape),
                focusedBorder = Border(FocusRing, shape = shape)
            )
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (avatarId == null) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = if (focused) Ink.high else Ink.body, modifier = Modifier.size(44.dp))
                } else {
                    AsyncImage(FluentEmojis.urlFor(avatarId), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.74f))
                    if (editing) Box(Modifier.fillMaxSize().background(Bg.base.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, tint = Ink.high, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(name, style = TvType.title.copy(color = if (focused) Ink.high else Ink.body), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- QR sign-in

private val Steps = listOf(
    "Open Reelm on your phone and sign in, or create an account.",
    "Point the phone's camera at this code and approve the TV.",
    "No camera? On the phone, go to Account and type the code."
)

@Composable
private fun TvQrSignIn(onBack: () -> Unit, vm: TvAccountViewModel = hiltViewModel()) {
    val pair by vm.pair.collectAsStateWithLifecycle()
    val lowEnd = LocalLowEnd.current
    val back = rememberFirstFocus()
    LaunchedEffect(Unit) { vm.pairLoop() }
    Row(Modifier.fillMaxSize().padding(horizontal = Overscan, vertical = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        // the QR on a white card, lit from behind by the projector lamp
        Box(
            Modifier.size(380.dp).then(
                if (lowEnd) Modifier else Modifier.drawBehind {
                    drawCircle(Brush.radialGradient(listOf(Accent.main.copy(alpha = 0.28f), Color.Transparent)), radius = size.minDimension * 0.62f)
                }
            ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(300.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                val qr = pair.qr
                if (qr != null) Image(qr.asImageBitmap(), contentDescription = "QR code to sign in this TV with your phone", filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
                else Image(painterResource(R.drawable.reelm_reel), contentDescription = null, modifier = Modifier.size(72.dp))
            }
        }
        Spacer(Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text("Sign in with your phone", style = TvType.headline)
            Spacer(Modifier.height(24.dp))
            Steps.forEachIndexed { i, s ->
                Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).background(Accent.soft, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = TvType.body.copy(color = Accent.main, fontWeight = FontWeight.SemiBold))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(s, style = TvType.body)
                }
            }
            Spacer(Modifier.height(8.dp))
            CodeSlots(pair.code)
            Spacer(Modifier.height(18.dp))
            WaitingLine(pair.error, animate = !lowEnd && !LocalReduceMotion.current)
            Spacer(Modifier.height(24.dp))
            TvPill("Back", onBack, Modifier.focusRequester(back))
        }
    }
}

/** The pairing code as 8 slots in two groups of 4, so it reads at 3 m and is easy to type on the phone. */
@Composable
private fun CodeSlots(code: String?) {
    val chars = code.orEmpty().padEnd(8, ' ').take(8)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = code?.let { "Code ${it.toList().joinToString(" ")}" } ?: "Getting a code" }
    ) {
        chars.forEachIndexed { i, c ->
            if (i == 4) Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(width = 42.dp, height = 54.dp).background(Bg.raised, Radius.sm).border(1.dp, Ink.hairline, Radius.sm),
                contentAlignment = Alignment.Center
            ) {
                Text(c.toString(), style = TvType.headline.copy(color = Accent.main, fontSize = 30.sp))
            }
        }
    }
}

@Composable
private fun WaitingLine(error: String?, animate: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val pulse = if (animate) {
            val t = rememberInfiniteTransition(label = "waiting")
            t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse").value
        } else 1f
        Box(Modifier.size(10.dp).background((if (error != null) Accent.danger else Accent.live).copy(alpha = pulse), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(error ?: "Waiting for your phone", style = TvType.body.copy(color = if (error != null) Accent.danger else Ink.muted))
    }
}

// ---------------------------------------------------------------- profile editor (D-pad)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvProfileEditor(vm: AccountViewModel, initial: Profile?, canDelete: Boolean, onDone: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var avatar by rememberSaveable { mutableStateOf(initial?.avatar ?: FluentEmojis.all.random().id) }
    var typing by rememberSaveable { mutableStateOf(initial == null) }
    val first = rememberFirstFocus(typing)
    // Title Case as it is typed: a capital after the start or a space
    fun type(k: String) { if (name.length < 20) name += if (name.isEmpty() || name.endsWith(" ")) k.uppercase() else k }

    Row(Modifier.fillMaxSize().padding(horizontal = Overscan, vertical = 36.dp)) {
        Column(Modifier.width(300.dp).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (initial == null) "Add profile" else "Edit profile", style = TvType.headline)
            Spacer(Modifier.height(28.dp))
            Box(
                Modifier.size(150.dp).clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(Color(0xFF3A3324), Bg.overlay)))
                    .border(2.dp, Accent.main, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(FluentEmojis.urlFor(avatar), contentDescription = "Avatar: ${FluentEmojis.byId(avatar).folder}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.76f))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                name.ifEmpty { "Name" }, style = TvType.title.copy(color = if (name.isEmpty()) Ink.faint else Ink.high),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(Bg.raised, Radius.md).padding(horizontal = 18.dp, vertical = 10.dp)
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvPill(if (ui.busy) "Saving" else "Save", { vm.save(initial?.id, name.trim(), avatar, initial?.kids ?: false, initial?.mature ?: false, onDone) }, enabled = name.isNotBlank() && !ui.busy)
                if (initial != null && canDelete) TvPill("Delete", { vm.delete(initial.id, onDone) }, enabled = !ui.busy)
            }
            ui.error?.let { Spacer(Modifier.height(12.dp)); Text(it, style = TvType.body.copy(color = Accent.danger)) }
        }
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvPill("Name", { typing = true }, selected = typing)
                TvPill("Avatar", { typing = false }, selected = !typing)
            }
            Spacer(Modifier.height(20.dp))
            if (typing) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(9),
                    modifier = Modifier.focusRestorer(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(keys.size) { i ->
                        TvPill(keys[i], { type(keys[i]) }, if (i == 0) Modifier.focusRequester(first) else Modifier)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvPill("Space", { if (name.isNotEmpty() && !name.endsWith(" ")) type(" ") })
                    TvPill("", { name = name.dropLast(1) }, icon = Icons.AutoMirrored.Outlined.Backspace)
                    TvPill("Clear", { name = "" })
                    TvPill("Pick avatar", { typing = false })
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier.focusRestorer(),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(FluentEmojis.all.size) { i ->
                        val e = FluentEmojis.all[i]
                        TvAvatarChoice(e.id, e.folder, e.id == avatar, { avatar = e.id }, if (e.id == avatar) Modifier.focusRequester(first) else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvAvatarChoice(id: String, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    Surface(
        onClick = onClick,
        modifier = modifier.size(60.dp).semantics { contentDescription = if (selected) "$label, selected" else label },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Accent.soft else Bg.raised,
            focusedContainerColor = Bg.overlay
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, Accent.main.copy(alpha = 0.5f)), shape = CircleShape) else Border.None,
            focusedBorder = Border(FocusRing, shape = CircleShape)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (reduce) 1f else 1.12f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow())
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(FluentEmojis.urlFor(id), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.74f))
        }
    }
}
