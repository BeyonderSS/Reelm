package com.anistream.feature.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.domain.ContentPrefs
import com.anistream.feature.party.PartyDestination
import com.anistream.feature.party.PartyViewModel
import com.anistream.feature.party.UpdateViewModel
import kotlinx.coroutines.launch

/** Room codes are A-Z and 2-9 (see server/API.md). */
private val codeKeys = ('A'..'Z').map { it.toString() } + ('2'..'9').map { it.toString() }

/** "More": join a watch party with the on-screen code pad, and check for updates. */
@Composable
fun TvMoreScreen(onOpen: (PartyDestination) -> Unit, party: PartyViewModel = hiltViewModel(), updates: UpdateViewModel = hiltViewModel(), acct: TvAccountViewModel = hiltViewModel()) {
    val hidden by ContentPrefs.hidden.collectAsStateWithLifecycle()
    val ui by updates.updates.ui.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Row(Modifier.padding(start = Overscan, top = 40.dp, end = Overscan)) {
        if (party.client.enabled) Column(Modifier.width(520.dp)) {
            Text("Join party", style = TvType.headline)
            Spacer(Modifier.height(14.dp))
            Text(
                code.padEnd(6, '·').toList().joinToString("  "),
                style = TvType.title,
                maxLines = 1, overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth().background(Bg.raised, Radius.md).padding(horizontal = 18.dp, vertical = 12.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier.height(230.dp)
            ) {
                items(codeKeys) { k -> TvPill(k, { if (code.length < 6) { code += k; error = null } }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvPill("", { code = code.dropLast(1) }, icon = Icons.AutoMirrored.Outlined.Backspace)
                TvPill("Clear", { code = "" })
                TvPill(if (busy) "Joining…" else "Join", {
                    busy = true
                    scope.launch {
                        val dest = party.join(code)
                        busy = false
                        if (dest == null) error = "Couldn't join that party" else onOpen(dest)
                    }
                }, enabled = code.length == 6 && !busy, selected = code.length == 6)
            }
            error?.let { Text(it, style = TvType.body.copy(color = Accent.danger), modifier = Modifier.padding(top = 12.dp)) }
        }
        Spacer(Modifier.width(48.dp))
        Column {
            Text("Reelm", style = TvType.headline)
            Text("Version ${updates.updates.versionName}", style = TvType.body.copy(color = Ink.muted))
            Spacer(Modifier.height(14.dp))
            TvPill(if (ui.checking) "Checking…" else "Check for updates", updates.updates::checkNow, enabled = !ui.checking)
            ui.status?.let { Text(it, style = TvType.body, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(28.dp))
            Text("What you watch", style = TvType.headline)
            Text("Hide what you never watch. Turn a kind off and it leaves the menu, Home and search.", style = TvType.body.copy(color = Ink.muted))
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { ContentPrefs.ALL.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { k ->
                    val on = k !in hidden
                    // the last kind shown can't be turned off
                    TvPill("${ContentPrefs.LABELS[k]}  ${if (on) "On" else "Off"}", {
                        val next = if (on) hidden + k else hidden - k
                        if (next.size < ContentPrefs.ALL.size) scope.launch { runCatching { acct.repo.setHidden(next) } }
                    }, selected = on)
                }
            } } }
        }
    }
}

/** Update prompt for the TV: release notes, then Update / Later, both D-pad focusable. Also runs the start-up check. */
@Composable
fun TvUpdateHost(vm: UpdateViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.updates.autoCheck() }
    val ui by vm.updates.ui.collectAsStateWithLifecycle()
    val release = ui.release ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = { if (ui.progress == null) vm.updates.dismiss() }) {
        Column(Modifier.width(640.dp).background(Bg.raised, Radius.xl).padding(32.dp)) {
            Text(release.name, style = TvType.headline)
            // ponytail: notes are clipped at 10 lines; a D-pad scroller if release notes grow past that
            if (release.body.isNotBlank()) Text(release.body, style = TvType.body, maxLines = 10, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            ui.progress?.let { p ->
                Box(Modifier.padding(top = 20.dp).fillMaxWidth().height(8.dp).background(Ink.faint, Radius.pill)) {
                    Box(Modifier.fillMaxWidth(p).height(8.dp).background(Accent.main, Radius.pill))
                }
            }
            ui.status?.let { Text(it, style = TvType.body.copy(color = Accent.danger), modifier = Modifier.padding(top = 12.dp)) }
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvPill(
                    if (ui.progress == null) "Update" else "Downloading ${(ui.progress!! * 100).toInt()}%",
                    vm.updates::update, enabled = ui.progress == null, large = true, modifier = Modifier.focusRequester(focus)
                )
                TvPill("Later", vm.updates::dismiss, enabled = ui.progress == null, large = true)
            }
        }
    }
}
