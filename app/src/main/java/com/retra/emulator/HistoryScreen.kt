package com.retra.emulator

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Mihon-style History tab: recently played games, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(resumeTick: Int) {
    val context = LocalContext.current
    var entries by remember(resumeTick) { mutableStateOf(History.entries(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                actions = {
                    if (entries.isNotEmpty()) IconButton(onClick = { History.clear(context); entries = emptyList() }) {
                        Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = "Clear history")
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState("(｡•́︿•̀｡)", "Nothing played yet", Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(entries, key = { it.first.absolutePath }) { (game, time) ->
                    ListItem(
                        leadingContent = { GameCover(game, Modifier.width(48.dp)) },
                        headlineContent = { Text(gameTitle(game)) },
                        supportingContent = { Text("Played " + DateUtils.getRelativeTimeSpanString(time)) },
                        trailingContent = {
                            IconButton(onClick = { History.remove(context, game); entries = History.entries(context) }) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove from history")
                            }
                        },
                        modifier = Modifier.clickable { launchGame(context, game) },
                    )
                }
            }
        }
    }
}
