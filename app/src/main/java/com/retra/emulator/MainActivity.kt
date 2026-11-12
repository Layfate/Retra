package com.retra.emulator

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retra.emulator.ui.theme.RetraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RetraTheme { LibraryScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf(Library.games(context)) }
    var saveGamePath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    saveGamePath?.let { SaveFileDialog(File(it)) { saveGamePath = null } }
    var importing by remember { mutableStateOf(false) }

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
    val rtpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { import("That folder has no Graphics folder. Pick the RPG Maker XP RTP folder.") { Library.importRtp(context, it) } }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Library") },
                    actions = {
                        TextButton(onClick = { folderPicker.launch(null) }, enabled = !importing) { Text("Add RPG folder") }
                        TextButton(onClick = { rtpPicker.launch(null) }, enabled = !importing) {
                            Text(if (Library.hasRtp(context)) "RTP ✓" else "RTP")
                        }
                    },
                )
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { if (!importing) filePicker.launch(arrayOf("*/*")) }) { Text("Add game") }
        },
    ) { padding ->
        if (games.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Your library is empty.\nTap “Add game” for a GBA ROM or RPG Maker .zip,\nor “Add RPG folder” for an RPG Maker game folder.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(games, key = { it.name }) { game ->
                    GameCard(game, onSaves = { saveGamePath = game.absolutePath }) {
                        context.startActivity(Intent(context, GameActivity::class.java).putExtra(GameActivity.EXTRA_ROM, game.absolutePath))
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(game: File, onSaves: () -> Unit, onClick: () -> Unit) {
    // Mihon-style cover tile; ROMs have no cover art yet, so show the system tag
    Column(Modifier.padding(4.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (coreFor(game) == Core.MKXPZ) "RPG" else game.extension.uppercase(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(
            if (game.isDirectory) game.name else game.nameWithoutExtension,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(4.dp),
        )
    }
}
