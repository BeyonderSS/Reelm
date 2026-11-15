package com.anistream.core.account.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anistream.core.account.HistoryPayload
import com.anistream.core.account.Now
import com.anistream.core.account.RemoteInfo
import com.anistream.core.ui.components.LoadingShimmer
import com.anistream.core.ui.components.PillChip
import com.anistream.core.ui.components.PrimaryButton
import com.anistream.core.ui.components.SecondaryButton
import com.anistream.core.ui.components.SegmentedControl
import com.anistream.core.ui.components.TextAction
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** Sends one control to the TV: action, value, arg. */
internal typealias Ctl = (String, Long?, JsonElement?) -> Unit

internal enum class RemoteTab(val label: String) { Subtitles("Subs"), Audio("Audio"), Quality("Quality"), Episodes("Episodes"), More("More") }

/** Which sections the TV reported something for (the others show disabled). */
internal fun remoteTabs(i: RemoteInfo): Map<RemoteTab, Boolean> = mapOf(
    RemoteTab.Subtitles to i.subtitles.tracks.isNotEmpty(),
    RemoteTab.Audio to (i.audio.tracks.isNotEmpty() || i.audio.langs.isNotEmpty()),
    RemoteTab.Quality to (i.quality.chips.isNotEmpty() || i.sources.isNotEmpty()),
    RemoteTab.Episodes to (i.payload != null && i.episode != null),
    RemoteTab.More to true
)

internal fun heightLabel(h: Int) = when { h <= 0 -> "Auto"; h >= 2160 -> "4K"; else -> "${h}p" }
internal fun chipLabel(c: RemoteInfo.Chip) = heightLabel(c.height) + if (!c.playable) " · not supported" else if (c.count > 0) " · ${c.count}" else ""
internal fun speedLabel(p: Int) = (p / 100.0).toString().removeSuffix(".0") + "×"
internal fun langName(code: String) = Locale(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
/** Seconds left on the TV's credits countdown at [t]; null when none runs. */
internal fun nextSeconds(n: Now?, t: Long): Long? = n?.nextInMs?.let { ((it - (t - n.at)).coerceAtLeast(0) + 999) / 1000 }
/** Sleep "End of episode" as whole minutes (at least 1). */
internal fun minutesToEnd(posMs: Long, durMs: Long) = ((durMs - posMs).coerceAtLeast(0) + 59_999) / 60_000

/** Skip / next-episode banner from the TV's now-playing state. */
@Composable
internal fun RemoteBanner(now: Now?, t: Long, ctl: Ctl) {
    val skip = now?.skip
    val secs = nextSeconds(now, t)
    if (skip != null) PrimaryButton(skip.label, { ctl("skip", null, null) }, Modifier.fillMaxWidth().padding(top = Space.lg))
    else if (secs != null && secs > 0) Column(
        Modifier.fillMaxWidth().padding(top = Space.lg).clip(Radius.lg).background(Bg.raised).padding(Space.lg)
    ) {
        Text("Next episode in $secs s", style = AppType.bodyStrong)
        Row(Modifier.padding(top = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            PrimaryButton("Play now", { ctl("next", null, null) }, Modifier.weight(1f))
            SecondaryButton("Watch credits", { ctl("cancelNext", null, null) }, Modifier.weight(1f))
        }
    }
}

/** Tabs + the chosen section; skeletons until the TV's first `info`. */
@Composable
internal fun RemoteSections(info: RemoteInfo?, now: Now?, pos: Long, vm: PlayOnTvViewModel, ctl: Ctl) {
    val tabs = info?.let(::remoteTabs)
    var tab by remember { mutableStateOf(RemoteTab.Subtitles) }
    val shown = tab.takeIf { tabs?.get(it) == true } ?: tabs?.entries?.firstOrNull { it.value }?.key ?: RemoteTab.More
    // only the sections the TV reported, as one full-width bar: nothing runs off a narrow phone screen
    val visible = RemoteTab.entries.filter { tabs?.get(it) == true }
    if (visible.size > 1) SegmentedControl(visible.map { it.label }, visible.indexOf(shown).coerceAtLeast(0), { tab = visible[it] }, Modifier.fillMaxWidth().padding(top = Space.xl))
    Column(Modifier.fillMaxWidth().padding(top = Space.lg)) {
        if (info == null) {
            repeat(3) { LoadingShimmer(Modifier.fillMaxWidth().height(44.dp).padding(bottom = Space.sm).clip(Radius.md)) }
            return@Column
        }
        when (shown) {
            RemoteTab.Subtitles -> Subtitles(info.subtitles, ctl)
            RemoteTab.Audio -> Audio(info.audio, ctl)
            RemoteTab.Quality -> Quality(info, ctl)
            RemoteTab.Episodes -> Episodes(info, vm, ctl)
            RemoteTab.More -> More(info, pos, now?.durationMs ?: 0L, ctl)
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = AppType.captionStrong, modifier = Modifier.padding(top = Space.md, bottom = Space.sm))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) { content() }

/** Full-width pick row: check when [selected], faint when not [enabled]. */
@Composable
private fun PickRow(title: String, selected: Boolean, enabled: Boolean = true, caption: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.md).background(if (selected) Bg.overlay else Bg.base)
            .clickable(enabled = enabled, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = Space.md).alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = if (selected) AppType.bodyStrong else AppType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) Text(caption, style = AppType.caption, maxLines = 1)
        }
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Accent.main, modifier = Modifier.size(20.dp))
    }
}

private fun obj(k: String, v: String?): JsonElement = buildJsonObject { put(k, v) }

@Composable
private fun Subtitles(s: RemoteInfo.Subtitles, ctl: Ctl) {
    Chips {
        PillChip("Off", !s.on, { ctl("subs", null, obj("track", null)) })
        PillChip("On", s.on, { if (!s.on) ctl("subs", null, obj("track", s.selected ?: s.tracks.firstOrNull()?.id)) })
    }
    Label("Track")
    s.tracks.forEach { tr -> PickRow(tr.label, s.on && tr.id == s.selected) { ctl("subs", null, obj("track", tr.id)) } }
    Label("Size")
    val sizes = listOf("s" to "S", "m" to "M", "l" to "L", "xl" to "XL")
    SegmentedControl(sizes.map { it.second }, sizes.indexOfFirst { it.first == s.size }, { ctl("subSize", null, JsonPrimitive(sizes[it].first)) })
    Label("Position")
    val posns = listOf("bottom", "raised", "higher", "top")
    SegmentedControl(posns.map { it.replaceFirstChar(Char::uppercase) }, posns.indexOf(s.position), { ctl("subPos", null, JsonPrimitive(posns[it])) })
}

@Composable
private fun Audio(a: RemoteInfo.Audio, ctl: Ctl) {
    if (a.langs.isNotEmpty()) {
        Label("Language")
        Chips {
            PillChip("Any", a.lang == null, { ctl("audioLang", null, JsonNull) })
            a.langs.forEach { l -> PillChip(langName(l), a.lang == l, { ctl("audioLang", null, JsonPrimitive(l)) }) }
        }
    }
    if (a.tracks.isNotEmpty()) {
        Label("Track")
        a.tracks.forEach { tr -> PickRow(tr.label, tr.id == a.selected) { ctl("audio", null, obj("track", tr.id)) } }
    }
}

@Composable
private fun Quality(i: RemoteInfo, ctl: Ctl) {
    var all by remember { mutableStateOf(false) }
    Chips {
        PillChip("Auto", i.quality.current == 0, { ctl("quality", 0L, null) })
        i.quality.chips.forEach { c ->
            PillChip(chipLabel(c), i.quality.current == c.height, if (c.playable) ({ ctl("quality", c.height.toLong(), null) }) else null,
                Modifier.alpha(if (c.playable) 1f else 0.38f))
        }
    }
    if (i.sources.isNotEmpty()) {
        TextAction(if (all) "Hide sources" else "Show all sources (${i.sources.size})", { all = !all }, Modifier.padding(top = Space.sm))
        if (all) i.sources.forEach { s ->
            PickRow(s.label, s.id == i.source, s.playable, listOfNotNull(heightLabel(s.height).takeIf { s.height > 0 }, "not supported".takeIf { !s.playable }).joinToString(" · ").ifEmpty { null }) {
                ctl("source", null, obj("id", s.id))
            }
        }
    }
}

@Composable
private fun Episodes(i: RemoteInfo, vm: PlayOnTvViewModel, ctl: Ctl) {
    val h = remember(i.payload) { i.payload?.let { HistoryPayload.decode(it, 0) } }
    val list by produceState<List<Pair<Int, List<Pair<Int, String?>>>>?>(null, h?.animeSlug, h?.mediaId) { value = h?.let { vm.episodes(it) }.orEmpty() }
    val seasons = list
    if (seasons == null) { repeat(3) { LoadingShimmer(Modifier.fillMaxWidth().height(44.dp).padding(bottom = Space.sm).clip(Radius.md)) }; return }
    if (seasons.isEmpty()) { Text("Couldn't load the episode list", style = AppType.caption); return }
    var pick by remember(seasons) { mutableStateOf(seasons.indexOfFirst { it.first == i.season }.coerceAtLeast(0)) }
    if (seasons.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        seasons.forEachIndexed { n, s -> PillChip("Season ${s.first}", n == pick, { pick = n }) }
    }
    val (season, eps) = seasons[pick]
    eps.forEach { (e, title) ->
        PickRow("Episode $e" + (title?.let { " · $it" } ?: ""), season == (i.season ?: season) && e == i.episode) {
            ctl("episode", null, buildJsonObject { put("season", season); put("episode", e) })
        }
    }
}

@Composable
private fun More(i: RemoteInfo, pos: Long, dur: Long, ctl: Ctl) {
    if (i.speeds.isNotEmpty()) {
        Label("Speed")
        Chips { i.speeds.forEach { p -> PillChip(speedLabel(p), p == i.speed, { ctl("speed", p.toLong(), null) }) } }
    }
    Label("Sleep timer")
    Chips {
        PillChip("Off", i.sleepMin == null || i.sleepMin == 0, { ctl("sleep", 0L, null) })
        listOf(15, 30, 60).forEach { m -> PillChip("$m min", i.sleepMin == m, { ctl("sleep", m.toLong(), null) }) }
        if (dur > 0) PillChip("End of episode", i.sleepMin != null && i.sleepMin !in listOf(0, 15, 30, 60), { ctl("sleep", minutesToEnd(pos, dur), null) })
    }
    val v = i.volume ?: return
    Label("TV volume")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        RoundKey(Icons.Rounded.Remove, "Volume down", 48) { ctl("volume", null, JsonPrimitive("down")) }
        Box(Modifier.weight(1f).height(6.dp).clip(Radius.pill).background(Ink.hairline)) {
            val f = if (v.muted || v.max <= 0) 0f else v.level.toFloat() / v.max
            Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(6.dp).background(Accent.main))
        }
        RoundKey(Icons.Rounded.Add, "Volume up", 48) { ctl("volume", null, JsonPrimitive("up")) }
        RoundKey(if (v.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (v.muted) "Unmute" else "Mute", 48, active = v.muted) {
            ctl("volume", null, JsonPrimitive(if (v.muted) "unmute" else "mute"))
        }
    }
}

/** Circular key with a light tap haptic. */
@Composable
private fun RoundKey(icon: ImageVector, label: String, size: Int, active: Boolean = false, onClick: () -> Unit) {
    val view = LocalView.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size.dp).clip(Radius.round).background(if (active) Accent.soft else Bg.raised)
            .border(1.dp, Ink.hairline, Radius.round)
            .clickable { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
    ) { Icon(icon, contentDescription = label, tint = if (active) Accent.main else Ink.high, modifier = Modifier.size((size * 0.45f).dp)) }
}

/** Big circular D-pad plus back / home / menu / play-pause, each a `key` control. */
@Composable
internal fun DPad(ctl: Ctl) {
    val view = LocalView.current
    val key = { k: String -> view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); ctl("key", null, JsonPrimitive(k)) }
    Column(Modifier.fillMaxWidth().padding(top = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(260.dp).clip(Radius.round).background(Bg.raised).border(1.dp, Ink.hairline, Radius.round)) {
            @Composable fun Arrow(icon: ImageVector, k: String, a: Alignment) = Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.align(a).size(84.dp).clip(Radius.round).clickable { key(k) }
            ) { Icon(icon, contentDescription = k.replaceFirstChar(Char::uppercase), tint = Ink.high, modifier = Modifier.size(40.dp)) }
            Arrow(Icons.Rounded.KeyboardArrowUp, "up", Alignment.TopCenter)
            Arrow(Icons.Rounded.KeyboardArrowDown, "down", Alignment.BottomCenter)
            Arrow(Icons.Rounded.KeyboardArrowLeft, "left", Alignment.CenterStart)
            Arrow(Icons.Rounded.KeyboardArrowRight, "right", Alignment.CenterEnd)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.align(Alignment.Center).size(96.dp).clip(Radius.round).background(Accent.main).clickable { key("ok") }
            ) { Text("OK", style = AppType.button, color = Accent.on) }
        }
        Spacer(Modifier.height(Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            RoundKey(Icons.AutoMirrored.Rounded.ArrowBack, "Back", 60) { ctl("key", null, JsonPrimitive("back")) }
            RoundKey(Icons.Rounded.Home, "Home", 60) { ctl("key", null, JsonPrimitive("home")) }
            RoundKey(Icons.Rounded.Menu, "Menu", 60) { ctl("key", null, JsonPrimitive("menu")) }
            RoundKey(Icons.Rounded.PlayArrow, "Play/Pause", 60) { ctl("key", null, JsonPrimitive("playpause")) }
        }
    }
}
