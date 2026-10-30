package com.anistream.core.account.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.anistream.core.account.AuthRules
import com.anistream.core.account.FluentEmojis
import com.anistream.core.account.Profile
import com.anistream.core.ui.components.PrimaryButton
import com.anistream.core.ui.components.ReelmSplash
import com.anistream.core.ui.components.SecondaryButton
import com.anistream.core.ui.components.TextAction
import com.anistream.core.ui.components.pressClickable
import com.anistream.core.ui.components.rememberReducedMotion
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Motion
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import com.anistream.core.ui.theme.duskShadow

private enum class Page { SignIn, SignUp, Edit }

/** Picked for the welcome cluster: faces, an animal and the popcorn in the middle. */
private val WelcomeAvatars = listOf("star-struck", "cat_face", "popcorn", "fox", "smiling_face_with_sunglasses")
const val DEFAULT_AVATAR = "smiling_face_with_sunglasses"

/**
 * Phone root: the launch animation, then the gate ("Who's watching?", or Welcome when signed out) until a profile
 * or guest mode is picked. The pick is remembered, so later launches go from the animation straight to [content].
 * [onAccount] opens the account screen (TV approval, sign out) from the gate.
 */
@Composable
fun ProfilePickerGate(onAccount: () -> Unit = {}, content: @Composable () -> Unit) {
    val vm: AccountViewModel = hiltViewModel()
    val gate by vm.gate.collectAsStateWithLifecycle()
    var splash by rememberSaveable { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Bg.base)) {
        if (gate) Gate(vm, onAccount) else content()
        if (splash) ReelmSplash(lite = false) { splash = false }
    }
}

/** The projector lamp: one soft amber light from above, shared by every gate page (it continues the splash glow). */
fun Modifier.lamp(strength: Float = 0.14f) = drawBehind {
    drawCircle(
        Brush.radialGradient(listOf(Accent.main.copy(alpha = strength), Color.Transparent), Offset(size.width / 2, 0f), size.maxDimension * 0.6f),
        radius = size.maxDimension * 0.6f, center = Offset(size.width / 2, 0f)
    )
}

@Composable
private fun Gate(vm: AccountViewModel, onAccount: () -> Unit) {
    val session by vm.session.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var editId by rememberSaveable { mutableStateOf("") } // "" = new profile
    var manage by rememberSaveable { mutableStateOf(false) }
    val signedIn = session != null
    val go: (Page?) -> Unit = { vm.clearStatus(); page = it }

    LaunchedEffect(signedIn) {
        if (signedIn) { vm.refresh(); if (page == Page.SignIn) page = null } else { manage = false; if (page == Page.Edit) page = null }
    }
    BackHandler(page != null) { go(null) }
    BackHandler(page == null && manage) { manage = false }
    BackHandler(page == null && !manage && vm.repo.hasChoice) { vm.repo.closeGate() }

    val reduced = rememberReducedMotion()
    val key = page?.name ?: if (signedIn) "who" else "welcome"
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
            else (fadeIn(Motion.standard()) + slideInHorizontally(Motion.standard()) { it / 12 }) togetherWith fadeOut(Motion.quick())
        },
        label = "gate",
        modifier = Modifier.fillMaxSize().lamp().statusBarsPadding().navigationBarsPadding()
    ) { k ->
        when (k) {
            "welcome" -> Welcome(onSignIn = { go(Page.SignIn) }, onSignUp = { go(Page.SignUp) }, onGuest = vm::guest)
            "who" -> WhosWatching(
                profiles = profiles,
                activeId = session?.activeProfile?.id,
                username = session?.user?.username.orEmpty(),
                manage = manage,
                onManage = { manage = !manage },
                onPick = { p -> if (manage) { editId = p.id; go(Page.Edit) } else vm.select(p) },
                onAdd = { editId = ""; go(Page.Edit) },
                onAccount = if (vm.repo.hasChoice) ({ vm.repo.closeGate(); onAccount() }) else null,
                onSignOut = vm::signOut
            )
            Page.SignIn.name -> SignInPage(ui, onBack = { go(null) }, onSubmit = vm::signIn, onCreate = { go(Page.SignUp) })
            Page.SignUp.name -> SignUpPage(vm, ui, onExit = { go(null) })
            else -> ProfileEditorPage(
                initial = profiles.firstOrNull { it.id == editId },
                canDelete = profiles.size > 1,
                ui = ui,
                onBack = { go(null) },
                onSave = { name, avatar, kids, mature -> vm.save(editId.ifEmpty { null }, name, avatar, kids, mature) { go(null) } },
                onDelete = { vm.delete(editId) { go(null) } }
            )
        }
    }
}

// ---------------------------------------------------------------- welcome (signed out)

@Composable
private fun Welcome(onSignIn: () -> Unit, onSignUp: () -> Unit, onGuest: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        AvatarCluster()
        Spacer(Modifier.height(Space.xxxl))
        Text("Welcome to Reelm", style = AppType.title, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Space.md))
        Text(
            "Sign in to keep your profiles and watch history in sync on your phone and TV.",
            style = AppType.body, textAlign = TextAlign.Center, modifier = Modifier.width(320.dp)
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(Space.xxl))
        PrimaryButton("Sign in", onClick = onSignIn, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.md))
        SecondaryButton("Create account", onClick = onSignUp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.sm))
        TextAction("Continue as guest", onClick = onGuest, color = Ink.high)
        Text("As a guest, your history stays on this phone.", style = AppType.caption, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.xl))
    }
}

/** Five avatars on a shallow arc, the middle one lit: a preview of what profiles look like. */
@Composable
fun AvatarCluster(scale: Float = 1f) {
    val sizes = listOf(52, 68, 104, 68, 52)
    val drops = listOf(28, 10, 0, 10, 28)
    Row(horizontalArrangement = Arrangement.spacedBy((-10 * scale).dp), verticalAlignment = Alignment.Top) {
        WelcomeAvatars.forEachIndexed { i, id ->
            val s = (sizes[i] * scale).dp
            val center = i == 2
            Box(
                Modifier.offset(y = (drops[i] * scale).dp).size(s)
                    .duskShadow(if (center) 20.dp else 10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (center) Brush.verticalGradient(listOf(Color(0xFF3A3324), Bg.overlay)) else Brush.verticalGradient(listOf(Bg.overlay, Bg.raised)))
                    .then(if (center) Modifier.border(2.dp, Accent.main, CircleShape) else Modifier.border(1.dp, Ink.hairline, CircleShape)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(FluentEmojis.urlFor(id), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.72f))
            }
        }
    }
}

// ---------------------------------------------------------------- who's watching

@Composable
private fun WhosWatching(
    profiles: List<Profile>,
    activeId: String?,
    username: String,
    manage: Boolean,
    onManage: () -> Unit,
    onPick: (Profile) -> Unit,
    onAdd: () -> Unit,
    onAccount: (() -> Unit)?,
    onSignOut: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = Space.lg), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (profiles.isNotEmpty()) TextAction(if (manage) "Done" else "Manage", onClick = onManage, color = if (manage) Accent.text else Ink.high)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Text(if (manage) "Manage profiles" else "Who's watching?", style = AppType.title, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(Space.xxxl))
            if (profiles.isEmpty()) {
                CircularProgressIndicator(color = Accent.main, strokeWidth = 2.dp, modifier = Modifier.size(28.dp).semantics { contentDescription = "Loading profiles" })
            } else BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cols = if (maxWidth > 560.dp) 3 else 2
                val tiles: List<Profile?> = profiles + if (profiles.size < 5) listOf(null) else emptyList()
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xl)) {
                    tiles.chunked(cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                            row.forEach { p ->
                                if (p == null) ProfileTile("Add profile", null, 120.dp, active = false, editing = false, onClick = onAdd)
                                else ProfileTile(p.name, p.avatar, 120.dp, active = p.id == activeId && !manage, editing = manage, kids = p.kids, onClick = { onPick(p) })
                            }
                        }
                    }
                }
            }
        }
        Text("Signed in as @$username", style = AppType.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            if (onAccount != null) TextAction("Account", onClick = onAccount)
            TextAction("Sign out", onClick = onSignOut)
        }
        Spacer(Modifier.height(Space.sm))
    }
}

/** A profile as a lit rounded tile with its Fluent Emoji; [avatarId] null = the "Add profile" tile. */
@Composable
internal fun ProfileTile(
    name: String,
    avatarId: String?,
    size: Dp,
    active: Boolean,
    editing: Boolean,
    onClick: () -> Unit,
    kids: Boolean = false
) {
    val label = when {
        avatarId == null -> "Add profile"
        editing -> "Edit $name"
        else -> name + if (kids) ", kids profile" else ""
    }
    Column(
        Modifier.width(size + Space.sm).clip(Radius.lg).pressClickable(onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.padding(top = Space.xs).size(size)
                .duskShadow(14.dp, Radius.lg)
                .clip(Radius.lg)
                .background(if (avatarId == null) Brush.verticalGradient(listOf(Bg.raised, Bg.raised)) else Brush.verticalGradient(listOf(Color(0xFF30344A), Bg.raised)))
                .border(if (active) 2.dp else 1.dp, if (active) Accent.main else Ink.hairline, Radius.lg),
            contentAlignment = Alignment.Center
        ) {
            if (avatarId == null) {
                Box(Modifier.size(size * 0.42f).border(1.5.dp, Ink.ghost, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = Ink.body, modifier = Modifier.size(size * 0.24f))
                }
            } else {
                AsyncImage(FluentEmojis.urlFor(avatarId), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.74f))
                if (editing) {
                    Box(Modifier.fillMaxSize().background(Bg.base.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(44.dp).background(Bg.overlay, CircleShape).border(1.dp, Ink.ghost, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Edit, contentDescription = null, tint = Ink.high, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.md))
        Text(name, style = if (avatarId == null) AppType.body else AppType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (kids) Text("Kids", style = AppType.caption)
    }
}

// ---------------------------------------------------------------- forms

@Composable
private fun BackRow(onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Ink.high) }
        Box(Modifier.weight(1f)) { trailing() }
    }
}

/** Outlined slate field with a floating label, inline error / hint line and an optional show-password toggle. */
@Composable
internal fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    hint: String? = null,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    capitalize: Boolean = false,
    onIme: () -> Unit = {}
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        textStyle = AppType.body.copy(color = Ink.high),
        visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }, onGo = { onIme() }),
        trailingIcon = if (!password) null else ({
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (shown) "Hide password" else "Show password", tint = Ink.muted)
            }
        }),
        shape = Radius.md,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Bg.raised, unfocusedContainerColor = Bg.raised, errorContainerColor = Bg.raised,
            focusedBorderColor = Accent.main, unfocusedBorderColor = Ink.hairline, errorBorderColor = Accent.danger,
            focusedLabelColor = Accent.text, unfocusedLabelColor = Ink.muted, errorLabelColor = Accent.danger,
            focusedSupportingTextColor = Ink.muted, unfocusedSupportingTextColor = Ink.muted, errorSupportingTextColor = Accent.danger,
            cursorColor = Accent.main, errorCursorColor = Accent.danger,
            focusedTextColor = Ink.high, unfocusedTextColor = Ink.high, errorTextColor = Ink.high
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** Error line that screen readers announce as soon as it appears. */
@Composable
internal fun ErrorNote(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.md).background(Accent.danger.copy(alpha = 0.12f)).padding(Space.md)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Accent.danger, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.sm))
        Text(text, style = AppType.body.copy(color = Ink.high))
    }
}

@Composable
private fun SignInPage(ui: AccountUi, onBack: () -> Unit, onSubmit: (String, String) -> Unit, onCreate: () -> Unit) {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    val passFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { userFocus.requestFocus() } }
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = AuthRules.canSubmit(user, pass)
    val submit = { if (ready && !ui.busy) { keyboard?.hide(); onSubmit(user, pass) } }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.lg)) {
        BackRow(onBack)
        Column(Modifier.padding(horizontal = Space.sm)) {
            Spacer(Modifier.height(Space.lg))
            Text("Sign in", style = AppType.title, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(Space.sm))
            Text("Welcome back. Your profiles and watch history are waiting.", style = AppType.body)
            Spacer(Modifier.height(Space.xxl))
            AuthField(user, { user = it.trim().lowercase() }, "Username", Modifier.focusRequester(userFocus), error = AuthRules.usernameError(user), onIme = { passFocus.requestFocus() })
            Spacer(Modifier.height(Space.sm))
            AuthField(pass, { pass = it }, "Password", Modifier.focusRequester(passFocus), password = true, imeAction = ImeAction.Done, onIme = submit)
            ui.error?.let { Spacer(Modifier.height(Space.lg)); ErrorNote(it) }
            Spacer(Modifier.height(Space.xl))
            PrimaryButton(if (ui.busy) "Signing in" else "Sign in", onClick = submit, enabled = ready, loading = ui.busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.md))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("New to Reelm?", style = AppType.caption)
                TextAction("Create account", onClick = onCreate, color = Accent.text)
            }
            Spacer(Modifier.height(Space.xl))
        }
    }
}

// ---------------------------------------------------------------- sign up (3 steps)

@Composable
private fun SignUpPage(vm: AccountViewModel, ui: AccountUi, onExit: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var avatar by rememberSaveable { mutableStateOf(DEFAULT_AVATAR) }
    var name by rememberSaveable { mutableStateOf("") }
    // the account exists after step 1, so going back from step 2 leaves for "Who's watching?" instead of re-registering
    val back = { vm.clearStatus(); if (step == 2) step = 1 else onExit() }
    BackHandler(step > 0) { back() }
    val reduced = rememberReducedMotion()

    Column(Modifier.fillMaxSize().imePadding()) {
        BackRow(back) {
            Row(Modifier.padding(end = Space.lg), verticalAlignment = Alignment.CenterVertically) {
                StepBar(step, Modifier.weight(1f))
                Spacer(Modifier.width(Space.md))
                Text("Step ${step + 1} of 3", style = AppType.caption)
            }
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
                else (fadeIn(Motion.standard()) + slideInHorizontally(Motion.standard()) { if (targetState > initialState) it / 8 else -it / 8 }) togetherWith fadeOut(Motion.quick())
            },
            label = "signUpStep",
            modifier = Modifier.weight(1f)
        ) { s ->
            when (s) {
                0 -> AccountStep(user, { user = it }, pass, { pass = it }, ui) {
                    vm.register(user, pass) { if (name.isEmpty()) name = user.replaceFirstChar { it.uppercase() }; step = 1 }
                }
                1 -> AvatarStep(avatar, { avatar = it }) { step = 2 }
                else -> NameStep(avatar, name, { name = it.take(20) }, ui) { vm.finishSignUp(name.trim(), avatar) }
            }
        }
    }
}

/** Three segments filling with amber as the steps complete. */
@Composable
private fun StepBar(step: Int, modifier: Modifier = Modifier) {
    Row(modifier.semantics { contentDescription = "Step ${step + 1} of 3" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            Box(Modifier.weight(1f).height(4.dp).clip(Radius.pill).background(if (i <= step) Accent.main else Ink.hairline))
        }
    }
}

@Composable
private fun StepHeader(title: String, body: String) {
    Text(title, style = AppType.title, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(Space.sm))
    Text(body, style = AppType.body)
}

@Composable
private fun AccountStep(user: String, onUser: (String) -> Unit, pass: String, onPass: (String) -> Unit, ui: AccountUi, onNext: () -> Unit) {
    val passFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { userFocus.requestFocus() } }
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = AuthRules.canSubmit(user, pass)
    val submit = { if (ready && !ui.busy) { keyboard?.hide(); onNext() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl)) {
        Spacer(Modifier.height(Space.lg))
        StepHeader("Create your account", "You'll use these to sign in on your phone and to approve your TV.")
        Spacer(Modifier.height(Space.xxl))
        AuthField(
            user, { onUser(it.trim().lowercase()) }, "Username", Modifier.focusRequester(userFocus),
            error = AuthRules.usernameError(user), hint = "3 to 32 characters: letters, numbers and _",
            onIme = { passFocus.requestFocus() }
        )
        Spacer(Modifier.height(Space.sm))
        AuthField(
            pass, onPass, "Password", Modifier.focusRequester(passFocus),
            error = AuthRules.passwordError(pass), hint = "At least 8 characters",
            password = true, imeAction = ImeAction.Done, onIme = submit
        )
        ui.error?.let { Spacer(Modifier.height(Space.lg)); ErrorNote(it) }
        Spacer(Modifier.height(Space.xl))
        PrimaryButton(if (ui.busy) "Creating account" else "Continue", onClick = submit, enabled = ready, loading = ui.busy, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.xl))
    }
}

@Composable
private fun AvatarStep(avatar: String, onAvatar: (String) -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(56.dp),
            modifier = Modifier.weight(1f).padding(horizontal = Space.xl),
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.fillMaxWidth().padding(top = Space.lg)) {
                        StepHeader("Choose your avatar", "It shows on your profile tile. You can change it any time.")
                    }
                    Spacer(Modifier.height(Space.xl))
                    AvatarPreview(avatar, 148.dp)
                    Spacer(Modifier.height(Space.sm))
                    Text(FluentEmojis.byId(avatar).folder, style = AppType.captionStrong)
                }
            }
            avatarItems(avatar, onAvatar)
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(Space.lg)) }
        }
        Box(Modifier.fillMaxWidth().background(Bg.base).padding(horizontal = Space.xl, vertical = Space.lg)) {
            PrimaryButton("Continue", onClick = onNext, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NameStep(avatar: String, name: String, onName: (String) -> Unit, ui: AccountUi, onDone: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = name.isNotBlank()
    val submit = { if (ready && !ui.busy) { keyboard?.hide(); onDone() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl)) {
        Spacer(Modifier.height(Space.lg))
        StepHeader("Name your profile", "This is you. You can add up to four more profiles for the rest of your home later.")
        Spacer(Modifier.height(Space.xxl))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ProfileTile(name.ifBlank { "Your name" }, avatar, 120.dp, active = true, editing = false, onClick = {})
        }
        Spacer(Modifier.height(Space.xl))
        AuthField(name, onName, "Profile name", imeAction = ImeAction.Done, capitalize = true, onIme = submit)
        ui.error?.let { Spacer(Modifier.height(Space.lg)); ErrorNote(it) }
        Spacer(Modifier.height(Space.xl))
        PrimaryButton("Start watching", onClick = submit, enabled = ready, loading = ui.busy, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.xl))
    }
}

// ---------------------------------------------------------------- avatar picker + editor

/** Large avatar on the lamp: the live preview while picking. */
@Composable
internal fun AvatarPreview(avatarId: String, size: Dp) {
    Box(
        Modifier.size(size).drawBehind {
            drawCircle(Brush.radialGradient(listOf(Accent.main.copy(alpha = 0.32f), Color.Transparent)), radius = this.size.minDimension * 0.75f)
        }.semantics { contentDescription = "Selected avatar: ${FluentEmojis.byId(avatarId).folder}" },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(size).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFF3A3324), Bg.overlay))).border(2.dp, Accent.main, CircleShape), contentAlignment = Alignment.Center) {
            AsyncImage(FluentEmojis.urlFor(avatarId), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.76f))
        }
    }
}

/** The 24 avatars in their sections, for a LazyVerticalGrid; the selected one is lit amber and slightly larger. */
internal fun LazyGridScope.avatarItems(selected: String, onSelect: (String) -> Unit) {
    FluentEmojis.groups.forEach { (label, list) ->
        item(span = { GridItemSpan(maxLineSpan) }, key = "h-$label") {
            Text(label, style = AppType.captionStrong, modifier = Modifier.padding(top = Space.md).semantics { heading() })
        }
        items(list, key = { it.id }) { e ->
            val sel = e.id == selected
            val scale by animateFloatAsState(if (sel) 1.06f else 1f, Motion.quick(), label = "avatarScale")
            Box(
                Modifier.aspectRatio(1f).graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(if (sel) Accent.soft else Bg.raised)
                    .border(if (sel) 2.dp else 1.dp, if (sel) Accent.main else Ink.hairline, CircleShape)
                    .selectable(selected = sel, role = Role.RadioButton) { onSelect(e.id) }
                    .semantics { contentDescription = e.folder },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(e.url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.72f))
            }
        }
    }
}

@Composable
private fun ProfileEditorPage(
    initial: Profile?,
    canDelete: Boolean,
    ui: AccountUi,
    onBack: () -> Unit,
    onSave: (String, String, Boolean, Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var avatar by rememberSaveable { mutableStateOf(initial?.avatar ?: FluentEmojis.all.random().id) }
    var kids by rememberSaveable { mutableStateOf(initial?.kids ?: false) }
    var mature by rememberSaveable { mutableStateOf(initial?.mature ?: false) }
    var confirmMature by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize().imePadding()) {
        BackRow(onBack) { Text(if (initial == null) "Add profile" else "Edit profile", style = AppType.heading, modifier = Modifier.semantics { heading() }) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(56.dp),
            modifier = Modifier.weight(1f).padding(horizontal = Space.xl),
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(Space.md))
                    AvatarPreview(avatar, 120.dp)
                    Spacer(Modifier.height(Space.xl))
                    AuthField(name, { name = it.take(20) }, "Name", imeAction = ImeAction.Done, capitalize = true, onIme = { keyboard?.hide() })
                    Row(Modifier.fillMaxWidth().padding(top = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text("Kids profile", style = AppType.bodyStrong, modifier = Modifier.weight(1f))
                        Switch(
                            checked = kids, onCheckedChange = { kids = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Accent.on, checkedTrackColor = Accent.main, uncheckedThumbColor = Ink.muted, uncheckedTrackColor = Bg.raised, uncheckedBorderColor = Ink.ghost),
                            modifier = Modifier.semantics { contentDescription = "Kids profile" }
                        )
                    }
                    if (!kids) Row(Modifier.fillMaxWidth().padding(top = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text("Show mature & non-film content", style = AppType.bodyStrong, modifier = Modifier.weight(1f))
                        Switch(
                            checked = mature, onCheckedChange = { if (it) confirmMature = true else mature = false },
                            colors = SwitchDefaults.colors(checkedThumbColor = Accent.on, checkedTrackColor = Accent.main, uncheckedThumbColor = Ink.muted, uncheckedTrackColor = Bg.raised, uncheckedBorderColor = Ink.ghost),
                            modifier = Modifier.semantics { contentDescription = "Show mature and non-film content" }
                        )
                    }
                }
            }
            avatarItems(avatar, { avatar = it })
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(Space.lg)) }
        }
        Column(Modifier.fillMaxWidth().background(Bg.base).padding(horizontal = Space.xl, vertical = Space.md), horizontalAlignment = Alignment.CenterHorizontally) {
            ui.error?.let { ErrorNote(it); Spacer(Modifier.height(Space.md)) }
            PrimaryButton(
                if (initial == null) "Add profile" else "Save", onClick = { onSave(name.trim(), avatar, kids, mature && !kids) },
                enabled = name.isNotBlank(), loading = ui.busy, modifier = Modifier.fillMaxWidth()
            )
            if (initial != null && canDelete) TextAction("Delete profile", onClick = { confirm = true }, color = Accent.danger)
        }
    }
    if (confirmMature) AlertDialog(
        onDismissRequest = { confirmMature = false },
        containerColor = Bg.overlay,
        shape = Radius.xl,
        title = { Text("Show mature content?", style = AppType.heading) },
        text = { Text("This shows adult titles, music videos and sports clips in search for this profile. Turn it on?", style = AppType.body) },
        confirmButton = { TextAction("Turn on", onClick = { confirmMature = false; mature = true }) },
        dismissButton = { TextAction("Cancel", onClick = { confirmMature = false }) }
    )
    if (confirm && initial != null) AlertDialog(
        onDismissRequest = { confirm = false },
        containerColor = Bg.overlay,
        shape = Radius.xl,
        title = { Text("Delete ${initial.name}?", style = AppType.heading) },
        text = { Text("This removes the profile and its watch history from your account.", style = AppType.body) },
        confirmButton = { TextAction("Delete", onClick = { confirm = false; onDelete() }, color = Accent.danger) },
        dismissButton = { TextAction("Cancel", onClick = { confirm = false }) }
    )
}
