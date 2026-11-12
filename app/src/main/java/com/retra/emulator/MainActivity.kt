package com.retra.emulator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab as TabItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retra.emulator.ui.theme.RetraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    // bumped on every resume so tabs re-read games/history after returning from a game
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RetraTheme { RetraApp(resumeTick) } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

private enum class Tab(val label: String, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    ROM("Rom", R.drawable.ic_sports_esports, R.drawable.ic_sports_esports_filled),
    HISTORY("History", R.drawable.ic_history, R.drawable.ic_history_filled),
    BROWSE("Browse", R.drawable.ic_explore, R.drawable.ic_explore_filled),
    MORE("More", R.drawable.ic_more_horiz, R.drawable.ic_more_horiz),
}

/** Mihon-style shell: bottom tabs, filled icon on the selected one, Back returns to the first tab. */
@Composable
fun RetraApp(resumeTick: Int) {
    var tab by rememberSaveable { mutableStateOf(Tab.ROM) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    BackHandler(enabled = tab != Tab.ROM || page != null) { if (page != null) page = null else tab = Tab.ROM }
    page?.let { return PageScreen(it) { page = null } }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it,
                        onClick = { tab = it },
                        icon = { Icon(painterResource(if (tab == it) it.selectedIcon else it.icon), contentDescription = null) },
                        label = { Text(it.label) },
                    )
                }
            }
        },
    ) { padding ->
        // consume the insets the outer Scaffold already handled so each tab's own Scaffold doesn't pad twice
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (tab) {
                Tab.ROM -> RomScreen(resumeTick)
                Tab.HISTORY -> HistoryScreen(resumeTick)
                Tab.BROWSE -> BrowseScreen()
                Tab.MORE -> MoreScreen { page = it }
            }
        }
    }
}

fun launchGame(context: Context, game: File) =
    context.startActivity(Intent(context, GameActivity::class.java).putExtra(GameActivity.EXTRA_ROM, game.absolutePath))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RomScreen(resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var games by remember(resumeTick) { mutableStateOf(Library.games(context)) }
    var saveGamePath by rememberSaveable { mutableStateOf<String?>(null) }
    saveGamePath?.let { SaveFileDialog(File(it)) { saveGamePath = null } }
    var importing by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf<String?>(null) } // null = not searching
    var categoryTick by remember { mutableIntStateOf(0) }
    val categories = remember(resumeTick, categoryTick) { Categories.all(context) }
    val assigned = remember(games, categories) { games.associateWith { Categories.of(context, it) } }
    var categoryTab by rememberSaveable { mutableIntStateOf(0) }
    val selectedTab = categoryTab.coerceAtMost(categories.size) // 0 = Default (games in no category)
    var categorizing by remember { mutableStateOf<File?>(null) }
    categorizing?.let { SetCategoriesDialog(it, { categorizing = null }) { categorizing = null; categoryTick++ } }

    // copies can be hundreds of MB for RPG Maker games, so run them off the main thread
    fun import(failMessage: String, block: () -> File?) {
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block).getOrNull() }
            importing = false
            if (result == null) Toast.makeText(context, failMessage, Toast.LENGTH_LONG).show()
            games = Library.games(context)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { import("Pick a .gba/.gb/.gbc ROM or a .zip/.mkxpz RPG Maker game") { Library.importFile(context, it) } }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { import("That folder has no Game.ini. Pick the RPG Maker game's main folder.") { Library.importGameFolder(context, it) } }
    }
    val addFile = { if (!importing) filePicker.launch(arrayOf("*/*")) }
    val addFolder = { if (!importing) folderPicker.launch(null) }
    val validIds = categories.map { it.id }.toSet()
    val inTab = when {
        categories.isEmpty() -> games
        selectedTab == 0 -> games.filter { (assigned[it].orEmpty() intersect validIds).isEmpty() }
        else -> games.filter { categories[selectedTab - 1].id in assigned[it].orEmpty() }
    }
    val shown = inTab.filter { it.name.contains(query.orEmpty(), ignoreCase = true) }

    Scaffold(
        topBar = {
            Column {
                SearchTopBar("Rom", query, { query = it }) {
                        Box {
                            IconButton(onClick = { addMenu = true }, enabled = !importing) {
                                Icon(painterResource(R.drawable.ic_add), contentDescription = "Add game")
                            }
                            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                DropdownMenuItem(text = { Text("ROM or game file") }, onClick = { addMenu = false; addFile() })
                                DropdownMenuItem(text = { Text("RPG Maker game folder") }, onClick = { addMenu = false; addFolder() })
                            }
                        }
                }
                if (categories.isNotEmpty()) PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
                    (listOf("Default") + categories.map { it.name }).forEachIndexed { i, name ->
                        TabItem(selected = selectedTab == i, onClick = { categoryTab = i }, text = { Text(name) })
                    }
                }
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        when {
            games.isEmpty() -> EmptyState("ಥ_ಥ", "Your library is empty", Modifier.padding(padding)) {
                TextButton(onClick = addFile) { Text("Add a GBA ROM or RPG Maker .zip") }
                TextButton(onClick = addFolder) { Text("Add an RPG Maker game folder") }
            }
            shown.isEmpty() && query.isNullOrEmpty() -> EmptyState("(・_・)", "This category is empty.\nLong-press a game to add it.", Modifier.padding(padding))
            shown.isEmpty() -> EmptyState("(・_・)", "No games match “${query.orEmpty()}”", Modifier.padding(padding))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(shown, key = { it.name }) { game ->
                    GameCard(game, onSaves = { saveGamePath = game.absolutePath }, onLongClick = { categorizing = game }) { launchGame(context, game) }
                }
            }
        }
    }
}

/** Mihon-style top bar whose title turns into a search field. [query] is null while not searching. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(title: String, query: String?, onQueryChange: (String?) -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(query != null) { if (query != null) focus.requestFocus() }
    TopAppBar(
        title = {
            if (query != null) TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search…") },
                singleLine = true,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            ) else Text(title)
        },
        actions = {
            IconButton(onClick = { onQueryChange(if (query == null) "" else null) }) {
                Icon(
                    painterResource(if (query != null) R.drawable.ic_close else R.drawable.ic_search),
                    contentDescription = if (query != null) "Close search" else "Search",
                )
            }
            actions()
        },
    )
}

/** Mihon's empty screen: a big kaomoji, a line of text, then optional actions. */
@Composable
fun EmptyState(face: String, message: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(face, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        actions()
    }
}

/** Cover tile; ROMs have no cover art yet, so it shows the system tag. */
@Composable
fun GameCover(game: File, modifier: Modifier = Modifier) {
    Box(
        modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (coreFor(game) == Core.MKXPZ) "RPG" else game.extension.uppercase(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

fun gameTitle(game: File): String = if (game.isDirectory) game.name else game.nameWithoutExtension

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCard(game: File, onSaves: () -> Unit, onLongClick: () -> Unit, onClick: () -> Unit) {
    Column(Modifier.padding(4.dp).clip(RoundedCornerShape(8.dp)).combinedClickable(onLongClick = onLongClick, onClick = onClick)) {
        GameCover(game, Modifier.fillMaxWidth())
        Text(
            gameTitle(game),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(4.dp),
        )
        if (coreFor(game) == Core.MGBA) TextButton(onClick = onSaves) { Text("Saves") }
    }
}
