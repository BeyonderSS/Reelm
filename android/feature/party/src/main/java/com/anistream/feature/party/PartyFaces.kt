package com.anistream.feature.party

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.anistream.core.ui.components.PrimaryButton
import com.anistream.core.ui.components.SecondaryButton
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.DisplayHeavy
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack
import kotlin.math.abs
import kotlin.math.roundToInt

// Phone-only watch-party faces (libwebrtc): reached only when PartyViewModel.rtc is non-null.

private const val WEB_URL = "https://reelm-tv.vercel.app"
private val TileShape = RoundedCornerShape(18.dp)

/** Camera on/off and mic on/off, asking for the permission only when the user turns one on. */
private class Toggles(val cam: Boolean, val mic: Boolean, val toggleCam: () -> Unit, val toggleMic: () -> Unit, val askMic: (() -> Unit) -> Unit)

@Composable
private fun rememberToggles(rtc: PartyRtc): Toggles {
    val ctx = LocalContext.current
    val cam by rtc.cam.collectAsStateWithLifecycle()
    val mic by rtc.mic.collectAsStateWithLifecycle()
    val camAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) rtc.setCam(true) }
    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) rtc.setMic(true) }
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return Toggles(
        cam, mic,
        toggleCam = { if (cam) rtc.setCam(false) else if (granted(Manifest.permission.CAMERA)) rtc.setCam(true) else camAsk.launch(Manifest.permission.CAMERA) },
        toggleMic = { if (mic) rtc.setMic(false) else if (granted(Manifest.permission.RECORD_AUDIO)) rtc.setMic(true) else micAsk.launch(Manifest.permission.RECORD_AUDIO) },
        // whisper: only when the mic is already allowed (asking mid-hold would steal the gesture)
        askMic = { go -> if (granted(Manifest.permission.RECORD_AUDIO)) go() else micAsk.launch(Manifest.permission.RECORD_AUDIO) }
    )
}

@Composable
private fun VideoView(track: VideoTrack, egl: EglBase.Context, mirror: Boolean, modifier: Modifier) {
    AndroidView(
        factory = { c ->
            VideoTextureViewRenderer(c).apply {
                init(egl, object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {}
                    override fun onFrameResolutionChanged(w: Int, h: Int, r: Int) {}
                })
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            }
        },
        update = { v ->
            val old = v.tag as? VideoTrack
            if (old !== track) {
                runCatching { old?.removeSink(v) }
                track.addSink(v)
                v.tag = track
            }
            v.setMirror(mirror)
        },
        onRelease = { v -> runCatching { (v.tag as? VideoTrack)?.removeSink(v) } },
        modifier = modifier
    )
}

/** One member's face: their camera, else their initial; amber ring + level bars while talking, reactions float up. */
@Composable
private fun Face(m: Member, vm: PartyViewModel, rtc: PartyRtc, modifier: Modifier, shape: Shape = TileShape, ring: Boolean = true, label: Boolean = false) {
    val videos by rtc.videos.collectAsStateWithLifecycle()
    val levels by rtc.levels.collectAsStateWithLifecycle()
    val front by rtc.frontCamera.collectAsStateWithLifecycle()
    val me = m.id == vm.client.selfId
    val track = videos[m.id]?.takeIf { me || m.cam }
    val level = levels[m.id] ?: 0f
    val talking = level > TALK_THRESHOLD
    Box(modifier) {
        Box(
            Modifier.fillMaxSize().clip(shape).background(Bg.overlay)
                .then(if (talking && ring) Modifier.border(2.5.dp, Accent.main, shape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (track != null) VideoView(track, rtc.egl.eglBaseContext, mirror = me && front, modifier = Modifier.fillMaxSize())
            else Text(m.name.take(1).uppercase(), style = AppType.heading.copy(color = Ink.body))
            if (talking) LevelBars(level, Modifier.align(Alignment.BottomStart).padding(6.dp))
            if (!m.mic && !talking) Icon(
                Icons.Rounded.MicOff, contentDescription = "Muted", tint = Accent.danger,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(20.dp).background(Bg.badge, Radius.round).padding(3.dp)
            )
            if (label) Text(
                if (me) "You" else m.name, style = AppType.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(Space.sm).background(Bg.badge, Radius.pill).padding(horizontal = 9.dp, vertical = 3.dp)
            )
        }
        vm.reactions.filter { it.by == m.id }.forEach { Floater(it, Modifier.align(Alignment.TopCenter)) }
    }
}

@Composable
private fun LevelBars(level: Float, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(0.6f, 1f, 0.75f).forEach { k ->
            Box(Modifier.width(3.dp).height((4 + 10 * k * (level * 3).coerceAtMost(1f)).dp).background(Accent.main, Radius.sm))
        }
    }
}

@Composable
private fun Floater(r: Reaction, modifier: Modifier) {
    val t = remember(r.id) { Animatable(0f) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(r.id) {
        // "Remove animations" (scale 0) would end the float on its first frame: hold it still for 1.5 s instead
        if (android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) {
            kotlinx.coroutines.delay(1500); t.snapTo(1f)
        } else t.animateTo(1f, tween(2000))
    }
    Text(
        REACTIONS.firstOrNull { it.first == r.kind }?.second ?: return,
        fontSize = 28.sp,
        modifier = modifier.offset { IntOffset(((r.id % 3) - 1).toInt() * 18, (-110 * t.value * density).roundToInt()) }.alpha(1f - t.value)
    )
}

/** Width/height of the picture, or null before the first frame. */
@Composable
private fun rememberAspect(player: Player): Float? {
    var aspect by remember(player) { mutableStateOf<Float?>(null) }
    DisposableEffect(player) {
        fun read(v: VideoSize) { aspect = if (v.width > 0 && v.height > 0) v.width * v.pixelWidthHeightRatio / v.height else null }
        val l = object : Player.Listener { override fun onVideoSizeChanged(videoSize: VideoSize) = read(videoSize) }
        read(player.videoSize); player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    return aspect
}

/** Where the user left each face (member slot → [FaceSpot]); "Reset faces" in the party sheet clears it. */
internal object FaceStore {
    private var rev by mutableIntStateOf(0) // bumped on every write so faces recompose
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("party_faces", Context.MODE_PRIVATE)
    fun get(ctx: Context, slot: Int): FaceSpot? = prefs(ctx).getString("$slot", null).takeIf { rev >= 0 }
        ?.split(',')?.mapNotNull(String::toFloatOrNull)?.takeIf { it.size == 3 }?.let { FaceSpot(it[0], it[1], it[2]) }
    fun put(ctx: Context, slot: Int, s: FaceSpot) { prefs(ctx).edit().putString("$slot", "${s.x},${s.y},${s.w}").apply(); rev++ }
    fun reset(ctx: Context) { prefs(ctx).edit().clear().apply(); rev++ }
}

private fun whose(m: Member, me: String?) = if (m.id == me) "Your" else "${m.name}'s"

/**
 * A face the user can drag anywhere over the player and pinch to resize (or, with the controls showing, step through
 * small/medium/big with the corner button). Sits at ([x0], [y0]) and [w0] wide until moved; then at its saved spot.
 * Dropped within 16 dp of an edge or a black bar's edge, it snaps flush. Touches on it never reach the player.
 */
@Composable
private fun BoxWithConstraintsScope.Movable(
    slot: Int, who: String, x0: Dp, y0: Dp, w0: Dp, ratio: Float, bar: Dp, controls: Boolean, onTap: (() -> Unit)?,
    modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit
) {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val sw = constraints.maxWidth.toFloat()
    val sh = constraints.maxHeight.toFloat()
    val minW = with(d) { min(72.dp, w0).toPx() }
    val barPx = with(d) { bar.toPx() }
    val snap = with(d) { 16.dp.toPx() }
    val spot = FaceStore.get(ctx, slot)?.let { fitFace(it.x * sw, it.y * sh, it.w * sw, ratio, sw, sh, minW) }
        ?: with(d) { fitFace(x0.toPx(), y0.toPx(), w0.toPx(), ratio, sw, sh, minW) }
    val base by rememberUpdatedState(spot)
    val tap by rememberUpdatedState(onTap)
    var live by remember { mutableStateOf<FaceSpot?>(null) }
    fun put(x: Float, y: Float, w: Float) = FaceStore.put(ctx, slot, fitFace(x, y, w, ratio, sw, sh, minW, barPx, snap))
    val s = live ?: spot
    val w = with(d) { (s.w * sw).toDp() }
    Box(
        Modifier
            .offset { IntOffset((s.x * sw).roundToInt(), (s.y * sh).roundToInt()) }
            .size(w, w * ratio)
            .then(modifier)
            .semantics { contentDescription = "$who video, drag to move" }
            .then(if (onTap != null) Modifier.pointerInput(Unit) { detectTapGestures { tap?.invoke() } } else Modifier)
            .pointerInput(slot, sw, sh, ratio, minW, barPx) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var moving = false
                    var slop = Offset.Zero
                    var zoom = 1f
                    do {
                        val e = awaitPointerEvent()
                        val pan = e.calculatePan()
                        val z = e.calculateZoom()
                        if (!moving) {
                            slop += pan; zoom *= z
                            moving = slop.getDistance() > viewConfiguration.touchSlop || abs(zoom - 1f) > 0.05f
                        }
                        if (moving) {
                            val c = live ?: base
                            val cw = c.w * sw
                            // pinch grows around the face's centre; held to the limits first, or a pinch past them walks the face off-centre
                            val nw = faceWidth(cw * z, ratio, sw, sh, minW)
                            live = fitFace(c.x * sw + (cw - nw) / 2 + pan.x, c.y * sh + (cw - nw) * ratio / 2 + pan.y, nw, ratio, sw, sh, minW)
                            e.changes.forEach { it.consume() }
                        }
                    } while (e.changes.any { it.pressed })
                    live?.let { put(it.x * sw, it.y * sh, it.w * sw); live = null }
                }
            }
    ) {
        content()
        if (controls) Box(
            Modifier.align(Alignment.TopEnd).offset(14.dp, (-14).dp).size(48.dp).clip(Radius.round).clickable(role = Role.Button) {
                val cw = s.w * sw
                val nw = nextFaceWidth(cw, ratio, sw, sh, minW)
                put(s.x * sw + (cw - nw) / 2, s.y * sh + (cw - nw) * ratio / 2, nw)
            },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.OpenInFull, contentDescription = "Change size of ${if (who == "Your") "your" else who} video", tint = Ink.high,
                modifier = Modifier.size(26.dp).background(Bg.badge, Radius.round).padding(5.dp))
        }
    }
}

/**
 * Faces drawn over the video by the phone player. Fullscreen: tiles in the black side bars when the picture is
 * pillarboxed, round bubbles when it isn't; Date night = one big partner bubble. Every face can be dragged anywhere
 * and resized (see [Movable]). With the controls showing, the party pill (mic, camera, react, "Party · N") sits at the
 * top under the bar, clear of Settings. Not fullscreen: only the faces popped out of the couch, as bubbles.
 * [onTap] is the player's own tap (show/hide controls), so a tap on a face still does what a tap on the video does.
 */
@Composable
fun BoxScope.PartyFaces(player: Player, controls: Boolean, fit: Boolean, fullscreen: Boolean = true, onTap: () -> Unit = {}, vm: PartyViewModel = hiltViewModel()) {
    val rtc = vm.rtc ?: return
    val room by vm.client.room.collectAsStateWithLifecycle()
    val r = room ?: return
    val aspect = rememberAspect(player)
    val dim = if (controls) 0.55f else 1f
    val me = vm.client.selfId
    val ids = r.members.map { it.id }
    BoxWithConstraints(Modifier.matchParentSize()) {
        if (!fullscreen) {
            r.members.filter { it.id in vm.popped }.forEachIndexed { i, m ->
                Movable(faceSlot(ids, m.id, me), whose(m, me), maxWidth - 72.dp - Space.sm, Space.sm + 80.dp * i, 72.dp, 1f, 0.dp, controls, onTap) {
                    Face(m, vm, rtc, Modifier.fillMaxSize(), Radius.round)
                }
            }
            return@BoxWithConstraints
        }
        val bar = if (fit && aspect != null && aspect < maxWidth / maxHeight) (maxWidth - maxHeight * aspect) / 2 else 0.dp
        val partner = r.members.firstOrNull { it.id != me }
        if (r.mode == "date" && partner != null) {
            DateNight(partner, r.members.firstOrNull { it.id == me }, player, vm, rtc, controls, bar, ids, onTap)
        } else {
            val faces = r.members.take(6)
            if (bar >= 88.dp) {
                // two columns, centred in the bars: even seats left, odd seats right; each card = 80×104 tile + name
                val card = 126.dp
                faces.forEachIndexed { i, m ->
                    val column = faces.indices.filter { it % 2 == i % 2 }
                    val top = (maxHeight - card * column.size - Space.sm * (column.size - 1)) / 2
                    val x = (bar - 80.dp) / 2 + if (i % 2 == 0) 0.dp else maxWidth - bar
                    Movable(faceSlot(ids, m.id, me), whose(m, me), x, top + (card + Space.sm) * column.indexOf(i), 80.dp, card / 80.dp, bar, controls, onTap, Modifier.alpha(dim)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Face(m, vm, rtc, Modifier.fillMaxWidth().weight(1f))
                            val level = (rtc.levels.collectAsStateWithLifecycle().value[m.id] ?: 0f) > TALK_THRESHOLD
                            Text(
                                if (m.id == me) if (m.mic) "You" else "You · muted" else m.name,
                                style = AppType.label.copy(fontSize = 12.sp, color = if (level) Accent.main else Ink.body),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else {
                // a stack of bubbles in the bottom-right corner
                faces.forEachIndexed { i, m ->
                    val below = faces.size - 1 - i
                    Movable(
                        faceSlot(ids, m.id, me), whose(m, me), maxWidth - Space.lg - 56.dp, maxHeight - 96.dp - 56.dp - (56.dp + Space.sm) * below,
                        56.dp, 1f, 0.dp, controls, onTap, Modifier.alpha(dim)
                    ) { Face(m, vm, rtc, Modifier.fillMaxSize(), Radius.round) }
                }
            }
        }
        if (controls) PartyPill(r, player, vm, rtc, Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
    }
}

@Composable
private fun BoxWithConstraintsScope.DateNight(partner: Member, self: Member?, player: Player, vm: PartyViewModel, rtc: PartyRtc, controls: Boolean, bar: Dp, ids: List<String>, onTap: () -> Unit) {
    val t = rememberToggles(rtc)
    // pillarboxed: the bubble lives in the right bar, sized to it (≥ 80 dp), off the picture; no bars: bottom-right corner
    val size = if (bar > 0.dp) (bar - 16.dp).coerceIn(80.dp, 140.dp) else 140.dp
    val end = if (bar > 0.dp) ((bar - size) / 2).coerceAtLeast(4.dp) else 14.dp
    val selfEnd = if (bar > 0.dp) ((bar - 48.dp) / 2).coerceAtLeast(4.dp) else 138.dp
    val selfBottom = if (bar > 0.dp) 18.dp + size + 22.dp else 120.dp
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(6000); hint = false }
    val me = self?.id
    // double-tap = heart and hold = whisper here, so no tap-through: resizing is pinch, or the size button with the controls up
    Movable(
        faceSlot(ids, partner.id, me), whose(partner, me), maxWidth - end - size, maxHeight - 18.dp - size, size, 1f, bar, controls, null,
        Modifier.pointerInput(partner.id) {
            var whispering = false
            detectTapGestures(
                onDoubleTap = { vm.react("heart", player.currentPosition) },
                onLongPress = { t.askMic { whispering = true; rtc.whisper(true) } },
                onPress = { tryAwaitRelease(); if (whispering) { whispering = false; rtc.whisper(false) } }
            )
        }
    ) {
        BoxWithConstraints(Modifier.matchParentSize()) { Box(Modifier.requiredSize(maxWidth + 12.dp).border(2.dp, Accent.main.copy(alpha = 0.7f), Radius.round)) }
        Face(partner, vm, rtc, Modifier.matchParentSize(), Radius.round, ring = false)
        Text(
            partner.name, style = AppType.label.copy(fontSize = 12.sp, color = Accent.on), maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = 8.dp).background(Accent.main, Radius.pill).padding(horizontal = 10.dp, vertical = 2.dp)
        )
    }
    if (self != null) Movable(0, "Your", maxWidth - selfEnd - 48.dp, maxHeight - selfBottom - 48.dp, 48.dp, 1f, bar, controls, onTap) {
        Face(self, vm, rtc, Modifier.fillMaxSize().border(2.dp, Ink.body, Radius.round), Radius.round)
    }
    if (!controls) Row(
        Modifier.align(Alignment.TopStart).padding(14.dp).background(Bg.badge, Radius.pill).padding(horizontal = Space.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        Icon(Icons.Rounded.NightsStay, contentDescription = null, tint = Accent.main, modifier = Modifier.size(14.dp))
        Text("Date night · just you two", style = AppType.label.copy(fontSize = 12.sp))
    }
    if (hint && !controls) Text(
        "Double-tap ${partner.name}'s bubble to send a heart · hold it to whisper", style = AppType.caption.copy(fontSize = 12.sp, color = Ink.body),
        modifier = Modifier.align(Alignment.BottomStart).padding(14.dp).background(Bg.badge, Radius.pill).padding(horizontal = Space.md, vertical = 5.dp)
    )
}

@Composable
private fun PartyPill(r: Room, player: Player, vm: PartyViewModel, rtc: PartyRtc, modifier: Modifier) {
    val t = rememberToggles(rtc)
    val configured by rtc.configured.collectAsStateWithLifecycle()
    var reacting by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(
            Modifier.background(Bg.raised.copy(alpha = 0.92f), Radius.pill).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (configured != false) {
                RoundButton(if (t.mic) Icons.Rounded.Mic else Icons.Rounded.MicOff, if (t.mic) "Mute mic" else "Mic is muted, tap to talk", 40.dp,
                    tint = if (t.mic) Ink.high else Accent.danger, bg = if (t.mic) Color.Transparent else Accent.danger.copy(alpha = 0.18f), onClick = t.toggleMic)
                RoundButton(if (t.cam) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, if (t.cam) "Camera off" else "Camera on", 40.dp, onClick = t.toggleCam)
            }
            RoundButton(Icons.Rounded.Favorite, "React", 40.dp, tint = Accent.danger) { reacting = !reacting }
            Row(
                Modifier.height(40.dp).clip(Radius.pill).background(Accent.main).clickable(role = Role.Button) { vm.sheet = true }.padding(start = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy((-7).dp)) {
                    r.members.take(3).forEach { m ->
                        Box(Modifier.size(22.dp).border(2.dp, Accent.main, Radius.round).background(Bg.overlay, Radius.round), contentAlignment = Alignment.Center) {
                            Text(m.name.take(1).uppercase(), style = AppType.label.copy(fontSize = 10.sp))
                        }
                    }
                }
                Text("Party · ${r.members.size}", style = AppType.label.copy(color = Accent.on))
            }
        }
        if (reacting) ReactionRow(Modifier.background(Bg.raised.copy(alpha = 0.92f), Radius.pill).padding(horizontal = Space.sm)) {
            vm.react(it, player.currentPosition); reacting = false
        }
    }
}

@Composable
private fun ReactionRow(modifier: Modifier, onReact: (String) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        REACTIONS.forEach { (kind, glyph) ->
            Box(Modifier.size(44.dp).clip(Radius.round).clickable(role = Role.Button) { onReact(kind) }, contentAlignment = Alignment.Center) {
                Text(glyph, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: Dp, tint: Color = Ink.high, bg: Color = Color.Transparent, shape: Shape = Radius.round, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(shape).background(bg).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp)) }
}

/**
 * Portrait "couch": under the video, up to four faces in a 2×2 grid, then mic, camera and reactions.
 * Nothing when there is no room (or on TV).
 */
@Composable
fun PartyCouch(player: Player, vm: PartyViewModel = hiltViewModel()) {
    val rtc = vm.rtc ?: return
    val room by vm.client.room.collectAsStateWithLifecycle()
    val r = room ?: return
    val t = rememberToggles(rtc)
    val configured by rtc.configured.collectAsStateWithLifecycle()
    val me = vm.client.selfId
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (r.mode == "date") "Date night" else "On the couch", style = AppType.heading.copy(fontFamily = DisplayHeavy, fontSize = 20.sp), modifier = Modifier.weight(1f))
            Text(
                "${r.code} · Invite", style = AppType.label.copy(color = Accent.main),
                modifier = Modifier.clip(Radius.pill).clickable(role = Role.Button) { vm.sheet = true }.padding(vertical = 6.dp, horizontal = Space.xs)
            )
        }
        val faces = r.members.take(4)
        val tall = if (faces.size <= 2) 150.dp else 120.dp
        faces.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                row.forEach { m ->
                    val gestures = if (r.mode == "date" && m.id != me) Modifier.pointerInput(m.id) {
                        var whispering = false
                        detectTapGestures(
                            onDoubleTap = { vm.react("heart", player.currentPosition) },
                            onLongPress = { t.askMic { whispering = true; rtc.whisper(true) } },
                            onPress = { tryAwaitRelease(); if (whispering) { whispering = false; rtc.whisper(false) } }
                        )
                    } else Modifier
                    Box(Modifier.weight(1f).height(tall)) {
                        Face(m, vm, rtc, Modifier.fillMaxSize().then(gestures), label = true)
                        val out = m.id in vm.popped
                        val who = if (m.id == me) "your" else whose(m, me)
                        Box(Modifier.align(Alignment.TopEnd)) {
                            RoundButton(Icons.Rounded.PictureInPictureAlt, if (out) "Put $who video back" else "Pop out $who video", 48.dp,
                                tint = if (out) Accent.main else Ink.high) { if (out) vm.popped.remove(m.id) else vm.popped.add(m.id) }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (r.members.size > 4) Text("+${r.members.size - 4} more watching", style = AppType.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val square = RoundedCornerShape(16.dp)
            if (configured != false) {
                RoundButton(if (t.mic) Icons.Rounded.Mic else Icons.Rounded.MicOff, if (t.mic) "Mute mic" else "Unmute mic", 48.dp,
                    tint = if (t.mic) Ink.high else Accent.danger, bg = Bg.raised, shape = square, onClick = t.toggleMic)
                RoundButton(if (t.cam) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, if (t.cam) "Camera off" else "Camera on", 48.dp, bg = Bg.raised, shape = square, onClick = t.toggleCam)
            }
            ReactionRow(Modifier.weight(1f).height(48.dp).background(Bg.raised, square)) { vm.react(it, player.currentPosition) }
        }
        if (r.members.any { it.cam }) Text("Turn sideways and they move into the black bars", style = AppType.caption.copy(fontSize = 12.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The party sheet: start a party (Friends or Date night), or the live one with your own camera preview, mic/camera/flip,
 * the code and "Send invite link", the seats, Lights down and Leave.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PartySheet(payload: JsonObject, vm: PartyViewModel, rtc: PartyRtc) {
    val room by vm.client.room.collectAsStateWithLifecycle()
    val configured by rtc.configured.collectAsStateWithLifecycle()
    val videos by rtc.videos.collectAsStateWithLifecycle()
    val front by rtc.frontCamera.collectAsStateWithLifecycle()
    val t = rememberToggles(rtc)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf("friends") }
    val r = room
    val me = vm.client.selfId
    ModalBottomSheet(onDismissRequest = { vm.sheet = false }, containerColor = Bg.raised, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Space.lg).padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Watch together", style = AppType.heading.copy(fontFamily = DisplayHeavy, fontSize = 24.sp), modifier = Modifier.weight(1f))
                val mode = r?.mode ?: pendingMode
                Row(Modifier.background(Bg.base, Radius.pill).padding(3.dp)) {
                    listOf("friends" to "Friends", "date" to "Date night").forEach { (k, label) ->
                        Text(
                            label, style = AppType.label.copy(color = if (mode == k) Ink.high else Ink.muted),
                            modifier = Modifier.clip(Radius.pill).background(if (mode == k) Bg.overlay else Color.Transparent)
                                .clickable(role = Role.Button) { if (r == null) pendingMode = k else vm.setMode(k) }
                                .padding(horizontal = Space.md, vertical = 7.dp)
                        )
                    }
                }
            }
            if (r == null) {
                Text("Start a party and share the code. Everyone plays, pauses and seeks together, and can turn on their camera and mic.", style = AppType.caption)
                PrimaryButton(if (busy) "Creating room…" else "Start a party", enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true; failed = false
                    scope.launch { failed = vm.create(payload, pendingMode) == null; busy = false }
                })
                if (failed) Text("Couldn't create a room", style = AppType.caption.copy(color = Accent.danger))
                return@Column
            }
            val others = r.members.filter { it.id != me }
            if (configured == false) {
                Text("Video and voice aren't set up on this server. Play, pause and seeking still stay in sync.", style = AppType.caption)
            } else {
                Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(22.dp)).background(Bg.overlay)) {
                    val own = videos[me.orEmpty()]
                    if (own != null) VideoView(own, rtc.egl.eglBaseContext, mirror = front, modifier = Modifier.fillMaxSize())
                    else Text("Camera is off", style = AppType.captionStrong, modifier = Modifier.align(Alignment.Center))
                    Text(
                        "This is how ${others.singleOrNull()?.name ?: "everyone"} sees you", style = AppType.label.copy(fontSize = 12.sp),
                        modifier = Modifier.align(Alignment.TopStart).padding(Space.md).background(Bg.badge, Radius.pill).padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = Space.md), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val glass = Bg.base.copy(alpha = 0.78f)
                        RoundButton(if (t.mic) Icons.Rounded.Mic else Icons.Rounded.MicOff, if (t.mic) "Mute mic" else "Mic on", 48.dp, tint = if (t.mic) Ink.high else Accent.danger, bg = glass, onClick = t.toggleMic)
                        RoundButton(if (t.cam) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, if (t.cam) "Camera off" else "Camera on", 48.dp, bg = glass, onClick = t.toggleCam)
                        if (t.cam) RoundButton(Icons.Rounded.Cameraswitch, "Flip camera", 48.dp, bg = glass, onClick = rtc::flip)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(Bg.base, Radius.lg).padding(horizontal = 14.dp, vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Party code", style = AppType.caption.copy(fontSize = 12.sp))
                    Text(r.code, style = AppType.heading.copy(fontFamily = DisplayHeavy, letterSpacing = 4.sp))
                }
                SecondaryButton("Send invite link", onClick = {
                    val title = (payload["title"] as? JsonPrimitive)?.content ?: "a film"
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Watch $title with me on Reelm: $WEB_URL/watch?room=${r.code} (in the app: Join party, code ${r.code})")
                    context.startActivity(Intent.createChooser(send, "Send invite link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
            if (others.isEmpty()) Text("Nobody else yet. Send the link and their seat shows up here.", style = AppType.caption)
            others.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(40.dp).then(if (m.cam || m.mic) Modifier.border(2.dp, Accent.live, Radius.round) else Modifier).background(Bg.overlay, Radius.round),
                        contentAlignment = Alignment.Center
                    ) { Text(m.name.take(1).uppercase(), style = AppType.label) }
                    Column {
                        Text("${m.name} is in their seat", style = AppType.bodyStrong.copy(fontSize = 15.sp))
                        val status = when {
                            m.cam && m.mic -> "Camera and mic on"
                            m.cam -> "Camera on"
                            m.mic -> "Mic on"
                            m.device == "tv" -> "Watching on TV"
                            else -> "Just watching"
                        }
                        Text(status, style = AppType.caption.copy(color = if (m.cam || m.mic) Accent.live else Ink.muted))
                    }
                }
            }
            if (configured != false) Text(
                "Reset faces", style = AppType.label.copy(color = Accent.main),
                modifier = Modifier.fillMaxWidth().clip(Radius.md).clickable(role = Role.Button) { FaceStore.reset(context); vm.popped.clear() }.padding(vertical = 14.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = Space.sm)) {
                PrimaryButton("Lights down", modifier = Modifier.weight(1.4f), onClick = { vm.lights(); vm.sheet = false })
                SecondaryButton("Leave", modifier = Modifier.weight(1f), onClick = { vm.client.leave(); vm.sheet = false })
            }
            Text("A 3, 2, 1 on every screen, then the film starts at the same frame.", style = AppType.caption.copy(fontSize = 12.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}
