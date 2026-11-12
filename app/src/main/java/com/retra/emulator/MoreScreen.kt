package com.retra.emulator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val SOURCE_URL = "https://github.com/Layfate/Retra"

/** Full-screen pages opened from More (the bottom bar hides while one is open, like Mihon). */
enum class Page { CATEGORIES, STATISTICS, STORAGE, SETTINGS, ABOUT, HELP }

@Composable
fun PageScreen(page: Page, onBack: () -> Unit) = when (page) {
    Page.CATEGORIES -> CategoriesPage(onBack)
    Page.STATISTICS -> StatisticsPage(onBack)
    Page.STORAGE -> StoragePage(onBack)
    Page.SETTINGS -> SettingsPage(onBack)
    Page.ABOUT -> AboutPage(onBack)
    Page.HELP -> HelpPage(onBack)
}

private fun openUrl(context: Context, url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

/** Mihon-style More tab: logo header, then library tools, then app items. */
@Composable
fun MoreScreen(onOpen: (Page) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { Logo(96) }
        HorizontalDivider()
        MoreItem(R.drawable.ic_label, "Categories") { onOpen(Page.CATEGORIES) }
        MoreItem(R.drawable.ic_query_stats, "Statistics") { onOpen(Page.STATISTICS) }
        MoreItem(R.drawable.ic_storage, "Data and storage") { onOpen(Page.STORAGE) }
        HorizontalDivider()
        MoreItem(R.drawable.ic_settings, "Settings") { onOpen(Page.SETTINGS) }
        MoreItem(R.drawable.ic_volunteer_activism, "Support Us") { openUrl(context, SOURCE_URL) }
        MoreItem(R.drawable.ic_info, "About") { onOpen(Page.ABOUT) }
        MoreItem(R.drawable.ic_help, "Help") { onOpen(Page.HELP) }
    }
}

@Composable
private fun Logo(sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(colorResource(R.color.ic_launcher_background))) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Retra logo", modifier = Modifier.size(sizeDp.dp))
    }
}

@Composable
private fun MoreItem(@DrawableRes icon: Int, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    ListItem(
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPage(title: String, onBack: () -> Unit, fab: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") } },
            )
        },
        floatingActionButton = fab,
        content = { padding -> ReadableWidth { content(padding) } },
    )
}

@Composable
private fun SectionHeader(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))

@Composable
private fun StatisticsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val played = remember { Playtime.all(context) }
    val gameCount = remember { Library.games(context).size }
    SubPage("Statistics", onBack) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(formatPlaytime(played.sumOf { it.second }), "Play time")
                    StatCard("$gameCount", "Games")
                    StatCard("${played.size}", "Played")
                }
            }
            if (played.isEmpty()) {
                item { Text("Play a game and your time shows up here.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item { SectionHeader("Most played") }
                items(played, key = { it.first.absolutePath }) { (game, ms) ->
                    ListItem(
                        leadingContent = { GameCover(game, Modifier.width(40.dp)) },
                        headlineContent = { Text(gameTitle(game)) },
                        trailingContent = { Text(formatPlaytime(ms), style = MaterialTheme.typography.titleSmall) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatCard(value: String, label: String) {
    Card(Modifier.weight(1f)) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

@Composable
private fun StoragePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var hasRtp by remember { mutableStateOf(Library.hasRtp(context)) }
    var importing by remember { mutableStateOf(false) }
    LaunchedEffect(importing) {
        sizes = withContext(Dispatchers.IO) { dirSize(Library.romsDir(context)) to dirSize(Library.savesDir(context)) }
    }
    val rtpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Library.importRtp(context, uri) }.getOrNull() } != null
            importing = false
            hasRtp = Library.hasRtp(context)
            Toast.makeText(context, if (ok) "RTP installed" else "That folder has no Graphics folder. Pick the RPG Maker XP RTP folder.", Toast.LENGTH_LONG).show()
        }
    }
    fun size(bytes: Long?) = bytes?.let { Formatter.formatShortFileSize(context, it) } ?: "Calculating…"

    SubPage("Data and storage", onBack) { padding ->
        Column(Modifier.padding(padding)) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            SectionHeader("Storage")
            MoreItem(R.drawable.ic_sports_esports, "Games", size(sizes?.first))
            MoreItem(R.drawable.ic_storage, "Saves and save states", size(sizes?.second))
            SectionHeader("RPG Maker")
            MoreItem(
                R.drawable.ic_sports_esports, "RPG Maker XP RTP",
                if (hasRtp) "Installed. Tap to replace it" else "Not installed. Some XP games need it; tap to pick the RTP folder",
            ) { if (!importing) rtpPicker.launch(null) }
            SectionHeader("History")
            MoreItem(R.drawable.ic_delete_sweep, "Clear history", "Play time statistics are kept") {
                History.clear(context)
                Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun SettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val all = remember { listOf(Settings.SCREEN_FILTER) + Settings.MGBA + Settings.MKXPZ }
    val values = remember { mutableStateMapOf(*all.map { it.key to Settings.get(context, it) }.toTypedArray()) }
    var picking by remember { mutableStateOf<Setting?>(null) }

    @Composable
    fun SettingRow(setting: Setting) = ListItem(
        headlineContent = { Text(setting.title) },
        supportingContent = {
            Column {
                Text(setting.choices.first { it.value == values[setting.key] }.label, color = MaterialTheme.colorScheme.primary)
                Text(setting.summary)
            }
        },
        modifier = Modifier.clickable { picking = setting },
    )

    SubPage("Settings", onBack) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SectionHeader("Display") }
            item { SettingRow(Settings.SCREEN_FILTER) }
            item { SectionHeader("mGBA · Game Boy Advance") }
            items(Settings.MGBA, key = { it.key }) { SettingRow(it) }
            item { SectionHeader("RPG Maker XP · mkxp-z") }
            items(Settings.MKXPZ, key = { it.key }) { SettingRow(it) }
            item {
                Text(
                    "Changes apply the next time you start a game.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    picking?.let { setting ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(setting.title) },
            text = {
                Column {
                    setting.choices.forEach { choice ->
                        Row(
                            Modifier.fillMaxWidth().selectable(values[setting.key] == choice.value) {
                                Settings.set(context, setting, choice.value)
                                values[setting.key] = choice.value
                                picking = null
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = values[setting.key] == choice.value, onClick = null)
                            Text(choice.label, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
    SubPage("About", onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Logo(96)
                Text("Retra!", style = MaterialTheme.typography.headlineSmall)
                Text("Version $version", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            MoreItem(R.drawable.ic_code, "Source code", SOURCE_URL) { openUrl(context, SOURCE_URL) }
            MoreItem(R.drawable.ic_info, "License", "GNU General Public License v3.0") { openUrl(context, "https://www.gnu.org/licenses/gpl-3.0.html") }
            MoreItem(
                R.drawable.ic_label, "Open-source libraries",
                "LibretroDroid (GPL-3.0)\nmGBA (MPL-2.0)\nmkxp-z (GPL)\nJetpack Compose & AndroidX (Apache-2.0)\nMaterial Symbols (Apache-2.0)\nInter font (SIL OFL 1.1)",
            )
        }
    }
}

private val HELP = listOf(
    "How do I add a GBA game?" to "In the Rom tab tap +, then “ROM or game file”, and pick a .gba, .gb or .gbc file.",
    "How do I add an RPG Maker XP game?" to "Tap +, then “RPG Maker game (pick Game.exe)”, and tap Game.exe in the game's folder. It plays from where it is, nothing is copied, so it's instant. The first time, allow “All files access”. Prefer not to? “Copy RPG Maker folder into Retra” copies the game instead, and a .zip or .mkxpz works with “ROM or game file”.",
    "An RPG Maker game has missing graphics or won't start" to "Many XP games need the RTP. Download it free from rpgmakerweb.com, then go to More → Data and storage → RPG Maker XP RTP. If text or the map is invisible, try More → Settings → Subimage fix.",
    "What do the buttons do?" to "GBA: the buttons match the console. RPG Maker: A confirms, B cancels and opens the menu, X is RPG Maker's A key (dash in many games), Y is RPG Maker's X key.",
    "Save states, fast forward and filters" to "In a game, Slot picks a save-state slot, Save and Load use it. ⏩ cycles 1×, 2× and 4× speed. The filter button switches between Sharp, Smooth, LCD and CRT.",
    "Where do games in Browse come from?" to "From repos you add yourself. Retra includes no game sources; only add repos and play games you have the right to.",
    "Organize games with categories" to "Create categories in More → Categories, then long-press a game in the Rom tab to put it in one or more of them.",
)

@Composable
private fun HelpPage(onBack: () -> Unit) {
    val context = LocalContext.current
    SubPage("Help", onBack) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(HELP) { (question, answer) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(question, style = MaterialTheme.typography.titleSmall)
                        Text(answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                TextButton(onClick = { openUrl(context, "$SOURCE_URL/issues") }) { Text("Still stuck? Report a problem on GitHub") }
            }
        }
    }
}
