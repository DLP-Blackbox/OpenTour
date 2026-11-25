@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.hydranet.opensteves.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hydranet.opensteves.data.CompletionStore
import dev.hydranet.opensteves.data.CustomGuide
import dev.hydranet.opensteves.data.GuideChapter
import dev.hydranet.opensteves.data.GuideSubchapter
import dev.hydranet.opensteves.data.InstalledTour
import dev.hydranet.opensteves.data.Playlist
import dev.hydranet.opensteves.data.PlaylistRepository
import dev.hydranet.opensteves.data.PlaylistStop
import dev.hydranet.opensteves.data.Tour
import dev.hydranet.opensteves.playback.TtsPlayer
import dev.hydranet.opensteves.playback.TtsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun PlaylistsScreen(playlists: List<Playlist>, onSelect: (Playlist) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "playlists-heading") { PlaylistSection("Playlists", "${playlists.size}") }
        if (playlists.isEmpty()) item(key = "empty") {
            EmptyState(Icons.Default.PlaylistPlay, "No playlists yet", "Playlists bundled with the app will appear here.")
        }
        items(playlists, key = { it.id }) { playlist ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = { onSelect(playlist) })
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TourThumbnail(null, Modifier.size(44.dp), fallbackIcon = Icons.Default.PlaylistPlay)
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(playlist.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (playlist.subtitle.isNotBlank()) Text(
                        playlist.subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${playlist.stops.size} stops", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun PlaylistScreen(
    playlist: Playlist,
    tours: List<Tour>,
    installed: Map<Int, InstalledTour>,
    repository: PlaylistRepository,
    onOpenTour: (Tour) -> Unit,
    onOpenGuide: (String) -> Unit,
) {
    val days = remember(playlist) { playlist.stops.groupBy { it.dayLabel } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "playlist-title") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(playlist.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
                if (playlist.subtitle.isNotBlank()) Text(
                    playlist.subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        days.forEach { (day, stops) ->
            item(key = "day-$day") { PlaylistSection(day) }
            items(stops, key = { it.id }) { stop ->
                PlaylistStopRow(stop, repository.findOfficialTour(stop, tours), installed, repository, onOpenTour, onOpenGuide)
            }
        }
    }
}

@Composable
private fun PlaylistStopRow(
    stop: PlaylistStop,
    matched: Tour?,
    installed: Map<Int, InstalledTour>,
    repository: PlaylistRepository,
    onOpenTour: (Tour) -> Unit,
    onOpenGuide: (String) -> Unit,
) {
    val downloaded = matched != null && installed.containsKey(matched.id)
    val context = LocalContext.current
    val completion = remember { CompletionStore.get(context) }
    val completed by completion.completed.collectAsState()
    val guideId = stop.guideId
    val totalSubchapters by produceState(0, stop.isCustom, guideId) {
        value = if (stop.isCustom && guideId != null) {
            repository.loadGuide(guideId)?.chapters?.sumOf { it.subchapters.size } ?: 0
        } else {
            0
        }
    }
    val completedSubchapters = if (guideId != null) {
        completed.count { it.startsWith("guide:$guideId:") }
    } else {
        0
    }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = {
                if (stop.isCustom) stop.guideId?.let(onOpenGuide) else if (matched != null) onOpenTour(matched)
            }).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TourThumbnail(null, Modifier.size(44.dp), fallbackIcon = if (stop.isCustom) Icons.Default.MenuBook else Icons.Default.Headphones)
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stop.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (stop.isCustom) CustomBadge()
                }
                if (stop.subtitle.isNotBlank()) Text(
                    stop.subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (!stop.isCustom) Text(
                    when {
                        matched == null -> "Not in the catalog yet — search Explore for “${stop.officialMatch}”"
                        downloaded -> "Rick Steves tour: ${displayTitle(matched)} / Downloaded"
                        else -> "Rick Steves tour: ${displayTitle(matched)} / Not downloaded"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                stop.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (stop.isCustom && totalSubchapters > 0) {
                    if (completedSubchapters == totalSubchapters) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "$completedSubchapters of $totalSubchapters completed",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        Text(
                            "$completedSubchapters of $totalSubchapters completed",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (stop.isCustom || matched != null) Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CustomBadge() {
    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(Icons.Default.EditNote, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text("CUSTOM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
fun CustomGuideScreen(repository: PlaylistRepository, guideId: String, tts: TtsPlayer, onOpenWikipedia: (String) -> Unit) {
    var guide by remember(guideId) { mutableStateOf<CustomGuide?>(null) }
    var loaded by remember(guideId) { mutableStateOf(false) }
    val context = LocalContext.current
    val completion = remember { CompletionStore.get(context) }
    val completed by completion.completed.collectAsState()
    LaunchedEffect(guideId) {
        guide = repository.loadGuide(guideId)
        loaded = true
    }
    DisposableEffect(guideId) { onDispose { tts.stop() } }
    DisposableEffect(guideId) {
        tts.onItemFinished = { chapterIndex, subchapterIndex ->
            if (subchapterIndex >= 0) {
                completion.setCompleted(CompletionStore.guideSubchapterKey(guideId, chapterIndex, subchapterIndex), true)
            }
        }
        onDispose { tts.onItemFinished = null }
    }
    val ttsState by tts.state.collectAsState()
    val current = guide
    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        current == null -> EmptyState(
            Icons.Default.MenuBook, "Guide not available yet",
            "This guide has not been added to the app yet. The rest of the playlist still works.",
        )
        else -> {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val expandedChapters = remember(current.id) { mutableStateListOf(0) }
            var contentsExpanded by remember { mutableStateOf(false) }
            LaunchedEffect(ttsState) {
                val activeChapter = when (val state = ttsState) {
                    is TtsState.Speaking -> state.chapterIndex
                    is TtsState.Paused -> state.chapterIndex
                    else -> null
                }
                if (activeChapter != null && activeChapter !in expandedChapters) expandedChapters.add(activeChapter)
            }
            // LazyColumn indices of the chapter items, counted from the items emitted below:
            // header, practical?, map?, wikipedia, controls, contents, chapters-heading, then chapters.
            val chapterItemIndices = remember(current) {
                var next = 1
                if (current.practical.isNotEmpty()) next++
                if (current.location != null || current.mapNote.isNotBlank()) next++
                next++
                next++
                next++
                if (current.chapters.isNotEmpty()) next++
                List(current.chapters.size) { next + it }
            }
            LazyColumn(
                Modifier.fillMaxSize(), state = listState,
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "header") { GuideHeaderBlock(current) }
                if (current.practical.isNotEmpty()) item(key = "practical") { GuidePracticalBlock(current) }
                if (current.location != null || current.mapNote.isNotBlank()) item(key = "map") { GuideMapBlock(current) }
                item(key = "wikipedia") { WikipediaEntryButton(current.id) { onOpenWikipedia(current.id) } }
                item(key = "controls") { GuidePlaybackControls(current, ttsState, tts) }
                item(key = "contents") {
                    Box {
                        OutlinedButton(
                            onClick = { contentsExpanded = true },
                            shape = MaterialTheme.shapes.small, modifier = Modifier.height(48.dp),
                        ) {
                            Icon(Icons.Default.List, null, Modifier.size(18.dp))
                            Text("Contents", Modifier.padding(start = 6.dp))
                        }
                        DropdownMenu(expanded = contentsExpanded, onDismissRequest = { contentsExpanded = false }) {
                            current.chapters.forEachIndexed { chapterIndex, chapter ->
                                DropdownMenuItem(
                                    text = { Text("${chapterIndex + 1}. ${chapter.title}") },
                                    onClick = {
                                        contentsExpanded = false
                                        if (chapterIndex !in expandedChapters) expandedChapters.add(chapterIndex)
                                        scope.launch { listState.animateScrollToItem(chapterItemIndices[chapterIndex]) }
                                    },
                                )
                            }
                        }
                    }
                }
                if (current.chapters.isNotEmpty()) item(key = "chapters-heading") {
                    val totalSubchapters = current.chapters.sumOf { it.subchapters.size }
                    val completedSubchapters = current.chapters.indices.sumOf { chapterIndex ->
                        current.chapters[chapterIndex].subchapters.indices.count { subchapterIndex ->
                            CompletionStore.guideSubchapterKey(current.id, chapterIndex, subchapterIndex) in completed
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Chapters", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$completedSubchapters of $totalSubchapters completed",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(current.chapters, key = { index, _ -> "chapter-$index" }) { index, chapter ->
                    GuideChapterBlock(
                        current, chapter, index, ttsState, tts,
                        expanded = index in expandedChapters,
                        onToggleExpanded = {
                            if (index in expandedChapters) expandedChapters.removeAll { it == index } else expandedChapters.add(index)
                        },
                        completed = completed,
                        completion = completion,
                    )
                }
                if (current.sources.isNotEmpty()) item(key = "sources") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Sources", style = MaterialTheme.typography.titleMedium)
                        current.sources.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideHeaderBlock(guide: CustomGuide) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (guide.heroImage.isNotBlank()) AssetImage(
            "guides/${guide.heroImage}", guide.title,
            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(6.dp)),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (guide.area.isNotBlank()) Text(guide.area, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CustomBadge()
        }
        Text(guide.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
        if (guide.subtitle.isNotBlank()) Text(guide.subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val meta = listOf(
            guide.dayLabel,
            if (guide.estimatedMinutes > 0) "About ${guide.estimatedMinutes} min" else "",
        ).filter(String::isNotBlank).joinToString(" / ")
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (guide.intro.isNotBlank()) Text(guide.intro, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GuidePracticalBlock(guide: CustomGuide) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Good to know", style = MaterialTheme.typography.titleMedium)
        guide.practical.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GuideMapBlock(guide: CustomGuide) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Map, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Finding your way", style = MaterialTheme.typography.titleMedium)
        }
        guide.location?.let { location ->
            if (location.name.isNotBlank()) Text(location.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (guide.mapNote.isNotBlank()) Text(guide.mapNote, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GuidePlaybackControls(guide: CustomGuide, state: TtsState, tts: TtsPlayer) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is TtsState.Speaking -> {
                    Button(shape = MaterialTheme.shapes.small, onClick = { tts.pause() }, modifier = Modifier.height(48.dp)) {
                        Icon(Icons.Default.Pause, null, Modifier.size(18.dp))
                        Text("Pause", Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { tts.stop() }, modifier = Modifier.height(48.dp)) {
                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                        Text("Stop", Modifier.padding(start = 6.dp))
                    }
                }
                is TtsState.Paused -> {
                    Button(shape = MaterialTheme.shapes.small, onClick = { tts.resume() }, modifier = Modifier.height(48.dp)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Text("Resume", Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { tts.stop() }, modifier = Modifier.height(48.dp)) {
                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                        Text("Stop", Modifier.padding(start = 6.dp))
                    }
                }
                else -> Button(
                    shape = MaterialTheme.shapes.small,
                    onClick = { tts.speakAll(guide) },
                    enabled = guide.chapters.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Text("Listen to the whole guide", Modifier.padding(start = 6.dp))
                }
            }
        }
        Text(
            "Read aloud by text-to-speech. Pausing restarts the current item.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state is TtsState.Error) Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun GuideChapterBlock(
    guide: CustomGuide,
    chapter: GuideChapter,
    index: Int,
    state: TtsState,
    tts: TtsPlayer,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    completed: Set<String>,
    completion: CompletionStore,
) {
    val speaking = state is TtsState.Speaking && state.chapterIndex == index
    val paused = state is TtsState.Paused && state.chapterIndex == index
    val introActive = (speaking || paused) && when (state) {
        is TtsState.Speaking -> state.subchapterIndex == -1
        is TtsState.Paused -> state.subchapterIndex == -1
        else -> false
    }
    Column(
        Modifier.fillMaxWidth()
            .background(if (introActive) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = chapter.subchapters.isNotEmpty() && chapter.subchapters.indices.all { subIndex ->
                    CompletionStore.guideSubchapterKey(guide.id, index, subIndex) in completed
                },
                onCheckedChange = { checked ->
                    chapter.subchapters.indices.forEach { subIndex ->
                        completion.setCompleted(CompletionStore.guideSubchapterKey(guide.id, index, subIndex), checked)
                    }
                },
            )
            Text(
                "${index + 1}. ${chapter.title}", Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = { if (speaking) tts.pause() else if (paused) tts.resume() else tts.speakChapter(guide, index) },
                colors = TourButtonDefaults.iconColors(),
            ) {
                Icon(if (speaking) Icons.Default.Pause else Icons.Default.PlayArrow, if (speaking) "Pause chapter" else "Play chapter")
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                if (expanded) "Collapse chapter" else "Expand chapter",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            if (chapter.intro.isNotBlank()) Text(chapter.intro, style = MaterialTheme.typography.bodyLarge)
            if (chapter.image.isNotBlank()) AssetImage(
                "guides/${chapter.image}", chapter.caption,
                Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(5.dp)),
            )
            if (chapter.caption.isNotBlank()) Text(chapter.caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            chapter.subchapters.forEachIndexed { subIndex, subchapter ->
                GuideSubchapterBlock(guide, subchapter, index, subIndex, state, tts, completed, completion)
            }
        }
    }
}

@Composable
private fun GuideSubchapterBlock(
    guide: CustomGuide,
    subchapter: GuideSubchapter,
    chapterIndex: Int,
    subchapterIndex: Int,
    state: TtsState,
    tts: TtsPlayer,
    completed: Set<String>,
    completion: CompletionStore,
) {
    val speaking = state is TtsState.Speaking && state.chapterIndex == chapterIndex && state.subchapterIndex == subchapterIndex
    val paused = state is TtsState.Paused && state.chapterIndex == chapterIndex && state.subchapterIndex == subchapterIndex
    Column(
        Modifier.fillMaxWidth()
            .background(if (speaking || paused) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = CompletionStore.guideSubchapterKey(guide.id, chapterIndex, subchapterIndex) in completed,
                onCheckedChange = { checked ->
                    completion.setCompleted(CompletionStore.guideSubchapterKey(guide.id, chapterIndex, subchapterIndex), checked)
                },
            )
            if (subchapter.title.isNotBlank()) Text(
                subchapter.title, Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            ) else Box(Modifier.weight(1f))
            IconButton(
                onClick = { if (speaking) tts.pause() else if (paused) tts.resume() else tts.speakSubchapter(guide, chapterIndex, subchapterIndex) },
                colors = TourButtonDefaults.iconColors(),
            ) {
                Icon(if (speaking) Icons.Default.Pause else Icons.Default.PlayArrow, if (speaking) "Pause" else "Play")
            }
        }
        if (subchapter.image.isNotBlank()) AssetImage(
            "guides/${subchapter.image}", subchapter.caption,
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(5.dp)),
        )
        if (subchapter.caption.isNotBlank()) Text(subchapter.caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (subchapter.text.isNotBlank()) Text(subchapter.text, style = MaterialTheme.typography.bodyLarge)
        if (subchapter.directions.isNotBlank()) Text(
            subchapter.directions, style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaylistSection(title: String, meta: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        meta?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun AssetImage(path: String, description: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                context.assets.open(path).use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) Image(image, description, modifier, contentScale = ContentScale.Crop)
}
