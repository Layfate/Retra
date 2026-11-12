package com.retra.emulator

import android.content.Context
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab as TabItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalConfiguration
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

/** Mihon-style shell: tabs with a filled icon on the selected one; Back closes a page, then returns to the first tab. */
@Composable
fun RetraApp(resumeTick: Int) {
    var tab by rememberSaveable { mutableStateOf(Tab.ROM) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    BackHandler(enabled = tab != Tab.ROM || page != null) { if (page != null) page = null else tab = Tab.ROM }
    // pages slide in from the right and back out to it, like a navigation push
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val opening = targetState != null
            (slideInHorizontally(tween(280)) { w -> if (opening) w / 3 else -w / 3 } + fadeIn(tween(280))) togetherWith
                (slideOutHorizontally(tween(280)) { w -> if (opening) -w / 3 else w / 3 } + fadeOut(tween(200)))
        },
        label = "page",
    ) { shown ->
        if (shown != null) PageScreen(shown) { page = null } else Tabs(tab, { tab = it }, resumeTick) { page = it }
    }
}

@Composable
private fun Tabs(tab: Tab, onTab: (Tab) -> Unit, resumeTick: Int, onOpen: (Page) -> Unit) {
    val content = @Composable {
        Crossfade(tab, animationSpec = tween(180), label = "tab") {
            when (it) {
                Tab.ROM -> RomScreen(resumeTick)
                Tab.HISTORY -> HistoryScreen(resumeTick)
                Tab.BROWSE -> BrowseScreen()
                Tab.MORE -> MoreScreen(onOpen)
            }
        }
    }
    val icon = @Composable { t: Tab -> Icon(painterResource(if (tab == t) t.selectedIcon else t.icon), contentDescription = null) }

    // tablets and phones in landscape get a side rail instead of a bottom bar, like Mihon
    if (LocalConfiguration.current.screenWidthDp >= 600) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    Spacer(Modifier.weight(1f))
                    Tab.entries.forEach { NavigationRailItem(selected = tab == it, onClick = { onTab(it) }, icon = { icon(it) }, label = { Text(it.label) }) }
                    Spacer(Modifier.weight(1f))
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { NavigationBarItem(selected = tab == it, onClick = { onTab(it) }, icon = { icon(it) }, label = { Text(it.label) }) }
                }
            },
        ) { padding ->
            // consume the insets the outer Scaffold already handled so each tab's own Scaffold doesn't pad twice
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) { content() }
        }
    }
}

/** Keeps lists at a readable width on tablets and in landscape; full width on phones. */
@Composable
fun ReadableWidth(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { content() } }

fun launchGame(context: Context, game: File) =
    context.startActivity(Intent(context, GameActivity::class.java).putExtra(GameActivity.EXTRA_ROM, game.absolutePath))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RomScreen(resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf<List<File>?>(null) } // null until the first load finishes
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var assigned by remember { mutableStateOf<Map<File, Set<String>>>(emptyMap()) }
    var reload by remember { mutableIntStateOf(0) }
    // storage reads happen off the main thread so opening the tab and scrolling never wait on the disk
    LaunchedEffect(resumeTick, reload) {
        val g = withContext(Dispatchers.IO) { Library.games(context) }
        val c = withContext(Dispatchers.IO) { Categories.all(context) }
        assigned = withContext(Dispatchers.IO) { g.associateWith { Categories.of(context, it) } }
        categories = c
        games = g
    }
    var saveGamePath by rememberSaveable { mutableStateOf<String?>(null) }
    saveGamePath?.let { SaveFileDialog(File(it)) { saveGamePath = null } }
    var importing by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf<String?>(null) } // null = not searching
    var categoryTab by rememberSaveable { mutableIntStateOf(0) }
    val selectedTab = categoryTab.coerceAtMost(categories.size) // 0 = Default (games in no category)
    var categorizing by remember { mutableStateOf<File?>(null) }
    categorizing?.let { SetCategoriesDialog(it, { categorizing = null }) { categorizing = null; reload++ } }

    // copies can be hundreds of MB for RPG Maker games, so run them off the main thread
    fun import(failMessage: String, block: () -> File?) {
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block).getOrNull() }
            importing = false
            if (result == null) Toast.makeText(context, failMessage, Toast.LENGTH_LONG).show()
            reload++
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { import("Pick a .gba/.gb/.gbc ROM or a .zip/.mkxpz RPG Maker game") { Library.importFile(context, it) } }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { import("That folder has no Game.ini. Pick the RPG Maker game's main folder.") { Library.importGameFolder(context, it) } }
    }
    val exePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            import("Pick Game.exe inside the game's folder, browsing your phone's storage (not Recent or Drive)") { Library.linkGame(context, it) }
        }
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) exePicker.launch(arrayOf("*/*"))
    }
    var askAccess by remember { mutableStateOf(false) }
    val addFile = { if (!importing) filePicker.launch(arrayOf("*/*")) }
    val addFolder = { if (!importing) folderPicker.launch(null) }
    val addExe = { if (!importing) { if (hasStorageAccess(context)) exePicker.launch(arrayOf("*/*")) else askAccess = true } }
    if (askAccess) AlertDialog(
        onDismissRequest = { askAccess = false },
        title = { Text("Allow file access") },
        text = { Text("To play RPG Maker games straight from their folder (no copying, like JoiPlay), Retra needs “All files access”. Turn it on for Retra, come back, and tap + again.") },
        confirmButton = {
            TextButton(onClick = {
                askAccess = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)))
                } else legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }) { Text("Allow") }
        },
        dismissButton = { TextButton(onClick = { askAccess = false }) { Text("Cancel") } },
    )
    val validIds = categories.map { it.id }.toSet()
    val all = games.orEmpty()
    val inTab = when {
        categories.isEmpty() -> all
        selectedTab == 0 -> all.filter { (assigned[it].orEmpty() intersect validIds).isEmpty() }
        else -> all.filter { categories[selectedTab - 1].id in assigned[it].orEmpty() }
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
                                DropdownMenuItem(text = { Text("RPG Maker game (pick Game.exe)") }, onClick = { addMenu = false; addExe() })
                                DropdownMenuItem(text = { Text("Copy RPG Maker folder into Retra") }, onClick = { addMenu = false; addFolder() })
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
            games == null -> Box(Modifier.fillMaxSize()) // first load: a blank frame instead of flashing the empty state
            all.isEmpty() -> EmptyState("ಥ_ಥ", "Your library is empty", Modifier.padding(padding)) {
                TextButton(onClick = addFile) { Text("Add a GBA ROM or RPG Maker .zip") }
                TextButton(onClick = addExe) { Text("Add an RPG Maker game (pick Game.exe)") }
            }
            shown.isEmpty() && query.isNullOrEmpty() -> EmptyState("(・_・)", "This category is empty.\nLong-press a game to add it.", Modifier.padding(padding))
            shown.isEmpty() -> EmptyState("(・_・)", "No games match “${query.orEmpty()}”", Modifier.padding(padding))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(shown, key = { it.name }, contentType = { "game" }) { game ->
                    GameCard(game, Modifier.animateItem(), onSaves = { saveGamePath = game.absolutePath }, onLongClick = { categorizing = game }) { launchGame(context, game) }
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
        navigationIcon = {
            if (query != null) IconButton(onClick = { onQueryChange(null) }) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Close search")
            }
        },
        title = {
            if (query != null) TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search…") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            ) else Text(title)
        },
        actions = {
            if (query == null) IconButton(onClick = { onQueryChange("") }) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = "Search")
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCard(game: File, modifier: Modifier, onSaves: () -> Unit, onLongClick: () -> Unit, onClick: () -> Unit) {
    Column(modifier.padding(4.dp).clip(RoundedCornerShape(8.dp)).combinedClickable(onLongClick = onLongClick, onClick = onClick)) {
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
