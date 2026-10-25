package com.anistream.core.ui.components

import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anistream.core.ui.theme.Accent
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Bg
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Radius
import com.anistream.core.ui.theme.Space

data class DownloadOption(val id: String, val quality: String, val source: String, val sizeLabel: String?, val best: Boolean = false)

fun formatBytes(bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}

@Composable
fun DownloadedBadge(modifier: Modifier = Modifier) {
    Box(modifier.size(16.dp).background(Accent.live, Radius.round), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Download, contentDescription = "On this phone", tint = Accent.onLive, modifier = Modifier.size(11.dp))
    }
}

@Composable
fun DownloadStubButton(
    label: String, caption: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
    progress: Float? = null, trailing: String? = null
) {
    val tint = if (progress != null) Accent.live else Accent.main
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(56.dp)
            .clip(TicketShape(corner = 28.dp, notch = 6.dp))
            .background(Bg.overlay)
            .clickable(onClick = onClick)
            .drawBehind { if (progress != null) drawRect(Accent.live, Offset(0f, size.height - 3.dp.toPx()), Size(size.width * progress.coerceIn(0f, 1f), 3.dp.toPx())) }
            .padding(horizontal = Space.lg)
    ) {
        Icon(Icons.Rounded.Download, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(label, style = AppType.bodyStrong.copy(fontSize = 15.sp, lineHeight = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) Text(caption, style = AppType.caption.copy(fontSize = 12.sp, lineHeight = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.sm))
            Text(trailing, style = AppType.label.copy(color = if (progress == null) Accent.text else Ink.body), maxLines = 1)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadSheet(
    title: String, subtitle: String,
    options: List<DownloadOption>?,
    selectedId: String?, onSelect: (String) -> Unit,
    episodes: List<Int>?,
    downloaded: Set<Int>, picked: Set<Int>, onPickedChange: (Set<Int>) -> Unit,
    nextEpisode: Int,
    summary: String?,
    confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val free = remember { runCatching { formatBytes(StatFs(context.filesDir.path).availableBytes) + " free" }.getOrNull() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bg.raised,
        shape = Radius.xl.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp))
    ) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.lg).padding(bottom = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            MarqueeTitle("Download", subtitle.ifBlank { title }, size = 30)
            Text("Quality", style = AppType.label.copy(color = Ink.muted))
            when {
                options == null -> repeat(2) { LoadingShimmer(Modifier.fillMaxWidth().height(56.dp).clip(Radius.md)) }
                options.isEmpty() -> Text("No downloadable source for this title", style = AppType.caption)
                else -> options.forEach { o ->
                    val sel = o.id == selectedId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Radius.md)
                            .background(if (sel) Accent.faint else Color0)
                            .border(if (sel) 1.5.dp else 1.dp, if (sel) Accent.main else Ink.hairline, Radius.md)
                            .clickable { onSelect(o.id) }.padding(end = Space.md)
                    ) {
                        RadioButton(sel, { onSelect(o.id) }, colors = RadioButtonDefaults.colors(selectedColor = Accent.main, unselectedColor = Ink.faint))
                        Column(Modifier.weight(1f)) {
                            Text(o.quality, style = AppType.bodyStrong)
                            Text((if (o.best) "Best quality · " else "") + o.source, style = AppType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        o.sizeLabel?.let { Text(it, style = AppType.numeric.copy(color = Ink.body)) }
                    }
                }
            }
            if (episodes != null) {
                val todo = episodes.filter { it !in downloaded && it >= nextEpisode }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text("Episodes", style = AppType.label.copy(color = Ink.muted), modifier = Modifier.weight(1f))
                    Shortcut("Next 3") { onPickedChange(todo.take(3).toSet()) }
                    Shortcut("Rest of season") { onPickedChange(todo.toSet()) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm), maxItemsInEachRow = 6) {
                    episodes.forEach { ep ->
                        val done = ep in downloaded
                        val on = ep in picked
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(40.dp).clip(Radius.sm)
                                .background(if (done) Accent.live.copy(alpha = 0.18f) else if (on) Accent.main else Bg.overlay)
                                .clickable(enabled = !done) { onPickedChange(if (on) picked - ep else picked + ep) }
                        ) {
                            Text("$ep", style = AppType.numeric.copy(color = if (done) Accent.live else if (on) Accent.on else Ink.body, fontSize = 14.sp))
                        }
                    }
                }
                val have = downloaded.filter { it in episodes }.sorted()
                if (have.isNotEmpty()) {
                    val text = if (have.size == 1) "E${have[0]} is already on this phone" else
                        if (have.last() - have.first() + 1 == have.size) "E${have.first()} to E${have.last()} are already on this phone" else "${have.size} episodes are already on this phone"
                    Text(text, style = AppType.caption)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(summary.orEmpty(), style = AppType.caption.copy(color = Ink.body), modifier = Modifier.weight(1f))
                if (free != null) Text(free, style = AppType.caption)
            }
            PrimaryButton(
                text = confirmLabel, onClick = onConfirm, modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = selectedId != null && (episodes == null || picked.isNotEmpty())
            )
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color.Transparent

@Composable
private fun Shortcut(text: String, onClick: () -> Unit) {
    Text(
        text, style = AppType.label.copy(color = Accent.text),
        modifier = Modifier.clip(Radius.pill).background(Accent.faint).clickable(onClick = onClick).padding(horizontal = Space.md, vertical = 6.dp)
    )
}
