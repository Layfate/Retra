package com.retra.emulator

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mihon-style Browse tab: the user's repos, one search box across all of them, and a Get button per game. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urls by remember { mutableStateOf(Repos.urls(context)) }
    var repos by remember { mutableStateOf<List<Repo>>(emptyList()) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(urls) {
        loading = true
        val results = withContext(Dispatchers.IO) { urls.map { it to runCatching { Repos.fetch(it) } } }
        repos = results.mapNotNull { it.second.getOrNull() }
        failed = results.filter { it.second.isFailure }.map { it.first }
        loading = false
    }

    val results = repos.flatMap { it.games }.distinctBy { it.url }
        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }

    fun download(game: RepoGame) {
        downloading = true
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { Library.importStream(context, game.fileName, Repos.open(game.url), game.sha256) }.getOrNull()
            }
            downloading = false
            val message = if (file != null) "Added ${game.title} to your library" else "Download failed, or the file didn't match its checksum"
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Browse") }, actions = { TextButton(onClick = { adding = true }) { Text("Add repo") } })
                if (loading || downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search all repos") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (urls.isEmpty()) {
                item {
                    Text(
                        "Retra doesn't include any game sources. Tap “Add repo” and paste the https link to a repo index you trust, " +
                            "e.g. one for homebrew, ROM hacks you're allowed to share, or fan games.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(repos, key = { "repo:" + it.url }) { repo ->
                ListItem(
                    headlineContent = { Text(repo.name) },
                    supportingContent = { Text("${repo.games.size} games · ${repo.url}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { TextButton(onClick = { Repos.remove(context, repo.url); urls = Repos.urls(context) }) { Text("Remove") } },
                )
            }
            items(failed, key = { "failed:$it" }) { url ->
                ListItem(
                    headlineContent = { Text("Couldn't load repo", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { TextButton(onClick = { Repos.remove(context, url); urls = Repos.urls(context) }) { Text("Remove") } },
                )
            }
            items(results, key = { it.url }) { game ->
                ListItem(
                    headlineContent = { Text(game.title) },
                    supportingContent = {
                        Text(listOf(game.fileName, game.description).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = { TextButton(enabled = !downloading, onClick = { download(game) }) { Text("Get") } },
                )
            }
        }
    }

    if (adding) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add repo") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("https://…/index.json") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (Repos.add(context, text.trim())) { urls = Repos.urls(context); adding = false }
                    else Toast.makeText(context, "Repo links must start with https://", Toast.LENGTH_SHORT).show()
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
