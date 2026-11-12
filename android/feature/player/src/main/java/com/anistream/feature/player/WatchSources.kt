package com.anistream.feature.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anistream.core.ui.theme.AppType
import com.anistream.core.ui.theme.Ink
import com.anistream.core.ui.theme.Space
import com.anistream.domain.AudioLanguage

/** Phone Watch sheet: audio language → quality chips → the playing source → "Show all sources" → every source by height. */
@Composable
internal fun WatchSourcesSection(vm: MediaPlayerViewModel, onPicked: () -> Unit) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val streams = ui.request?.streams.orEmpty()
    val tracks = rememberTracks(vm.player)
    val preferred by AudioLanguage.preferred.collectAsStateWithLifecycle()
    val languages = audioLanguages(streams, tracks)
    if (languages.size > 1) {
        SheetHeading("Audio language")
        ChoiceRow(listOf<String?>(null) + languages, preferred, { it?.let(AudioLanguage::name) ?: "Any" }, vm::selectAudioLanguage)
    }
    val quals = vm.qualities()
    if (quals.isNotEmpty()) {
        SheetHeading("Quality")
        ChoiceRow(listOf(0) + quals.map { it.first }, ui.quality, { h -> qualityChip(h, quals, ui.stream?.height) }) { vm.selectQuality(it); onPicked() }
    }
    if (ui.note.isNotBlank()) Text(ui.note, style = AppType.numeric, modifier = Modifier.padding(top = Space.sm))
    val stream = ui.stream
    if (ui.showAllSources && streams.size > 1) {
        var last = -1
        vm.allSourcesOrder().forEach { i ->
            val s = streams[i]
            if (s.height != last) { last = s.height; SheetHeading(if (s.height > 0) "${s.height}p" else "Other") }
            SheetOption(s.label(), selected = i == ui.streamIndex, caption = ui.autoNote.takeIf { i == ui.autoIndex }) { vm.selectStream(i); onPicked() }
        }
    } else if (stream != null) {
        SheetHeading(if (streams.size > 1) "Playing from" else "Source (only one available)")
        SheetOption(stream.label(), selected = true) { onPicked() }
    }
    if (streams.size > 1) {
        SheetHeading("Show all sources")
        ChoiceRow(listOf(false, true), ui.showAllSources, { if (it) "On" else "Off" }, vm::setShowAllSources)
    }
    Spacer(Modifier.height(Space.sm))
}

/** "Auto", "1080p · 3", "720p · Playing" */
fun qualityChip(h: Int, quals: List<Pair<Int, Int>>, playingHeight: Int?): String =
    if (h == 0) "Auto" else listOfNotNull("${h}p", quals.firstOrNull { it.first == h }?.second?.takeIf { it > 1 }?.toString(), "Playing".takeIf { h == playingHeight })
        .joinToString(" · ")
