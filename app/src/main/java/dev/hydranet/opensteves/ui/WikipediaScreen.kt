package dev.hydranet.opensteves.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hydranet.opensteves.data.PlaylistRepository
import dev.hydranet.opensteves.data.WikipediaArticle

/**
 * Compact entry point shown in a custom guide: a button row in the guide's
 * block style that opens the guide's saved Wikipedia articles on their own
 * page instead of rendering them inline. Renders nothing when the guide has
 * no saved articles.
 */
@Composable
fun WikipediaEntryButton(guideId: String, onOpen: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { PlaylistRepository.get(context) }
    val articles by produceState<List<WikipediaArticle>>(emptyList(), guideId) {
        value = repository.loadWikipediaArticles(guideId)
    }
    if (articles.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.MenuBook, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Wikipedia (offline)", style = MaterialTheme.typography.titleMedium)
                Text(
                    articles.joinToString(" · ") { it.title },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Full-page reader for a guide's saved Wikipedia articles, fully offline. */
@Composable
fun WikipediaScreen(repository: PlaylistRepository, guideId: String) {
    val articles by produceState<List<WikipediaArticle>?>(null, guideId) {
        value = repository.loadWikipediaArticles(guideId)
    }
    val loaded = articles
    when {
        loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        loaded.isEmpty() -> EmptyState(
            Icons.Default.MenuBook, "No saved Wikipedia articles",
            "This guide does not have a saved Wikipedia copy yet.",
        )
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(loaded, key = { _, article -> article.title }) { index, article ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    WikipediaArticle(article)
                }
            }
        }
    }
}

@Composable
private fun WikipediaArticle(article: WikipediaArticle) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(article.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        article.text.split("\n\n").filter { it.isNotBlank() }.forEach { paragraph ->
            Text(paragraph.trim(), style = MaterialTheme.typography.bodyLarge)
        }
        if (article.retrieved.isNotBlank()) Text(
            "From Wikipedia — retrieved ${article.retrieved}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (article.sourceUrl.isNotBlank()) OutlinedButton(
            shape = MaterialTheme.shapes.small,
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.sourceUrl)))
                }
            },
        ) {
            Text("Open on Wikipedia")
        }
    }
}
