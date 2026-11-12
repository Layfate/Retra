package com.retra.emulator

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SaveFileDialog(game: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportExtension by rememberSaveable { mutableStateOf("sav") }
    var importUri by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun transfer(block: () -> String) {
        busy = true
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching(block).getOrElse { it.message ?: "Save transfer failed" }
            }
            busy = false
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importUri = it?.toString() }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) transfer {
            val directory = Library.savesDir(context)
            SaveFiles.migrate(directory, game)
            val file = SaveFiles.file(directory, game, exportExtension)
            require(file.exists()) { "No .$exportExtension save yet. Save in-game or use the matching state slot first." }
            val bytes = file.readBytes()
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open destination")
            output.use { it.write(bytes) }
            "Save exported"
        }
    }
    if (importUri != null) {
        AlertDialog(
            onDismissRequest = { importUri = null },
            title = { Text("Import save for ${game.nameWithoutExtension}?") },
            text = { Text("Replaces the matching battery save or state slot; keeps the previous file as a backup. States must be compatible with mGBA and this game.") },
            confirmButton = {
                TextButton(onClick = {
                    val uri = android.net.Uri.parse(importUri)
                    importUri = null
                    transfer {
                        val resolver = context.contentResolver
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: error("Cannot read filename")
                        val extension = SaveFiles.extension(name)
                        val bytes = resolver.openInputStream(uri)?.use(SaveFiles::read) ?: error("Cannot open save")
                        SaveFiles.validate(extension, bytes)
                        val directory = Library.savesDir(context)
                        SaveFiles.migrate(directory, game)
                        val target = SaveFiles.file(directory, game, extension)
                        if (target.exists()) File(target.path + ".bak").writeAtomic(target.readBytes())
                        target.writeAtomic(bytes)
                        if (extension == "sav" || extension == "srm") "Battery save imported. Open the game to use it."
                        else "State imported. Open the game, select .$extension, then Load."
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importUri = null }) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text("Saves: ${game.nameWithoutExtension}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Battery: .sav / .srm\nStates: mGBA .state / .ss0–.ss9")
                    TextButton(enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import save…") }
                    for (extension in SaveFiles.extensions) {
                        TextButton(enabled = !busy, onClick = {
                            exportExtension = extension
                            exporter.launch(game.nameWithoutExtension + "." + extension)
                        }) { Text("Export .$extension") }
                    }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Done") } },
        )
    }
}
