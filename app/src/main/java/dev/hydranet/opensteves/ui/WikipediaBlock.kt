package dev.hydranet.opensteves.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hydranet.opensteves.data.PlaylistRepository
import dev.hydranet.opensteves.data.WikipediaArticle

@Composable
fun WikipediaBlock(guideId: String) {
    val context = LocalContext.current
    val repository = remember(context) { PlaylistRepository.get(context) }
    val articles by produceState<List<WikipediaArticle>>(emptyList(), guideId) {
        value = repository.loadWikipediaArticles(guideId)
    }
    if (articles.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.MenuBook, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Wikipedia (offline)", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Saved copies from Wikipedia — readable without a connection.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        articles.forEachIndexed { index, article ->
            WikipediaArticleSection(article, initiallyExpanded = index == 0)
        }
    }
}

@Composable
private fun WikipediaArticleSection(article: WikipediaArticle, initiallyExpanded: Boolean) {
    val context = LocalContext.current
    var expanded by remember(article.title) { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = { expanded = !expanded }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                article.title, Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                article.text.split("\n\n").filter { it.isNotBlank() }.forEach { paragraph ->
                    Text(paragraph.trim(), style = MaterialTheme.typography.bodyLarge)
                }
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
}
