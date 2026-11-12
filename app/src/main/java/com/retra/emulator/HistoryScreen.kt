package com.retra.emulator

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/** Mihon-style History tab: recently played games grouped by day, newest first. */
@Composable
fun HistoryScreen(resumeTick: Int) {
    val context = LocalContext.current
    var entries by remember(resumeTick) { mutableStateOf(History.entries(context)) }
    val playtime = remember(resumeTick) { Playtime.all(context).toMap() }
    var query by rememberSaveable { mutableStateOf<String?>(null) } // null = not searching
    val shown = entries.filter { gameTitle(it.first).contains(query.orEmpty(), ignoreCase = true) }
    val days = shown.groupBy { Instant.ofEpochMilli(it.second).atZone(ZoneId.systemDefault()).toLocalDate() }
    val dateFormat = remember { DateFormat.getDateFormat(context) }
    val timeFormat = remember { DateFormat.getTimeFormat(context) }

    Scaffold(
        topBar = {
            SearchTopBar("History", query, { query = it }) {
                if (entries.isNotEmpty()) IconButton(onClick = { History.clear(context); entries = emptyList() }) {
                    Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = "Clear history")
                }
            }
        },
    ) { padding ->
        when {
            entries.isEmpty() -> EmptyState("(｡•́︿•̀｡)", "Nothing played yet", Modifier.padding(padding))
            shown.isEmpty() -> EmptyState("(・_・)", "No history matches “${query.orEmpty()}”", Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding)) {
                days.forEach { (day, games) ->
                    item(key = "day:$day") {
                        Text(
                            dateFormat.format(Date(games.first().second)),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                    }
                    items(games, key = { it.first.absolutePath }) { (game, time) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { launchGame(context, game) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GameCover(game, Modifier.width(56.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(gameTitle(game), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    formatPlaytime(playtime[game] ?: 0) + " - " + timeFormat.format(Date(time)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { History.remove(context, game); entries = History.entries(context) }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = "Remove from history")
                            }
                        }
                    }
                }
            }
        }
    }
}
