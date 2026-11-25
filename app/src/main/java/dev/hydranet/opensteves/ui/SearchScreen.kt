package dev.hydranet.opensteves.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hydranet.opensteves.data.CustomGuide
import dev.hydranet.opensteves.data.Playlist
import dev.hydranet.opensteves.data.PlaylistRepository
import dev.hydranet.opensteves.data.SearchIndex
import dev.hydranet.opensteves.data.SearchResult
import dev.hydranet.opensteves.data.SearchResultKind
import dev.hydranet.opensteves.data.Tour
import dev.hydranet.opensteves.data.WikipediaArticle

private val RESULT_GROUPS: List<Pair<String, List<SearchResultKind>>> = listOf(
    "Tours" to listOf(SearchResultKind.TOUR),
    "Playlist stops" to listOf(SearchResultKind.PLAYLIST, SearchResultKind.STOP),
    "Guide chapters" to listOf(SearchResultKind.GUIDE, SearchResultKind.CHAPTER),
    "Wikipedia" to listOf(SearchResultKind.WIKIPEDIA),
)

/**
 * App-wide search over everything already on the device — tour catalog,
 * playlists and stops, bundled guide chapters, and saved Wikipedia articles.
 * The index is built once from the loaded repositories (guides and articles
 * load lazily on first open); searching itself is fully offline.
 */
@Composable
fun SearchScreen(
    tours: List<Tour>,
    playlists: List<Playlist>,
    playlistRepository: PlaylistRepository,
    onOpenTour: (Tour) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenGuide: (String, Int?) -> Unit,
    onOpenWikipedia: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val guides by produceState<List<CustomGuide>?>(null) {
        value = playlistRepository.loadAllGuides()
    }
    val loadedGuides = guides
    val articles by produceState<List<WikipediaArticle>>(emptyList(), loadedGuides) {
        value = loadedGuides.orEmpty().flatMap { playlistRepository.loadWikipediaArticles(it.id) }
    }
    val index = remember(tours, playlists, loadedGuides, articles) {
        loadedGuides?.let { SearchIndex.build(tours, playlists, it, articles) }
    }
    val results = remember(index, query) { index?.search(query).orEmpty() }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun open(result: SearchResult) {
        when (result.kind) {
            SearchResultKind.TOUR -> tours.find { it.id == result.tourId }?.let(onOpenTour)
            SearchResultKind.PLAYLIST -> playlists.find { it.id == result.playlistId }?.let(onOpenPlaylist)
            SearchResultKind.STOP -> {
                val playlist = playlists.find { it.id == result.playlistId }
                val stop = playlist?.stops?.find { it.id == result.stopId }
                when {
                    stop == null -> playlist?.let(onOpenPlaylist)
                    stop.isCustom && stop.guideId != null -> onOpenGuide(stop.guideId, null)
                    else -> {
                        val tour = stop?.let { playlistRepository.findOfficialTour(it, tours) }
                        if (tour != null) onOpenTour(tour) else playlist?.let(onOpenPlaylist)
                    }
                }
            }
            SearchResultKind.GUIDE, SearchResultKind.CHAPTER ->
                result.guideId?.let { onOpenGuide(it, result.chapterIndex) }
            SearchResultKind.WIKIPEDIA -> result.guideId?.let(onOpenWikipedia)
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                .focusRequester(focusRequester),
            singleLine = true,
            placeholder = { Text("Search tours, guides, and Wikipedia") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }, colors = TourButtonDefaults.iconColors()) {
                    Icon(Icons.Default.Close, "Clear search")
                }
            },
            shape = RoundedCornerShape(6.dp),
        )
        when {
            index == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            query.trim().length < 2 -> EmptyState(
                Icons.Default.Search, "Search everything on this device",
                "Tours, playlist stops, guide chapters, and saved Wikipedia articles — no connection needed.",
            )
            results.isEmpty() -> EmptyState(
                Icons.Default.Search, "No results for “${query.trim()}”",
                "Check the spelling, or try a shorter or more general word.",
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                RESULT_GROUPS.forEach { (label, kinds) ->
                    val group = results.filter { it.kind in kinds }
                    if (group.isNotEmpty()) {
                        item(key = "search-heading-$label") { SearchSection(label) }
                        items(group, key = { "${it.kind}-${it.title}-${it.context}-${it.chapterIndex}" }) { result ->
                            SearchResultRow(result) { open(result) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSection(title: String) {
    Text(
        title,
        Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    val icon: ImageVector = when (result.kind) {
        SearchResultKind.TOUR -> Icons.Default.Headphones
        SearchResultKind.PLAYLIST, SearchResultKind.STOP -> Icons.Default.PlaylistPlay
        SearchResultKind.GUIDE, SearchResultKind.CHAPTER, SearchResultKind.WIKIPEDIA -> Icons.Default.MenuBook
    }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            Modifier.weight(1f).padding(start = 14.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(result.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (result.context.isNotBlank()) Text(
                result.context, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (result.snippet.isNotBlank()) Text(
                result.snippet, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
