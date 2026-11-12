package com.retra.emulator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.io.File

/** Mihon's "Edit categories": a card per category with rename/delete, and an Add button. */
@Composable
fun CategoriesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var list by remember { mutableStateOf(Categories.all(context)) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }

    SubPage(
        "Edit categories", onBack,
        fab = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text("Add") },
            )
        },
    ) { padding ->
        if (list.isEmpty()) {
            EmptyState("(・_・)", "You have no categories.\nTap Add to create one for organizing your games.", Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { category ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_label), contentDescription = null)
                            Text(category.name, Modifier.weight(1f).padding(horizontal = 16.dp))
                            IconButton(onClick = { editing = category }) { Icon(painterResource(R.drawable.ic_edit), contentDescription = "Rename") }
                            IconButton(onClick = { deleting = category }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete") }
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        CategoryNameDialog(
            title = if (adding) "Add category" else "Edit category",
            confirmLabel = if (adding) "Add" else "OK",
            initial = editing?.name.orEmpty(),
            taken = list.map { it.name }.toSet() - editing?.name.orEmpty(),
            onDismiss = { adding = false; editing = null },
        ) { name ->
            editing?.let { Categories.rename(context, it.id, name) } ?: Categories.add(context, name)
            list = Categories.all(context)
            adding = false
            editing = null
        }
    }

    deleting?.let { category ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete category") },
            text = { Text("Do you wish to delete the category “${category.name}”? Games in it stay in your library.") },
            confirmButton = {
                TextButton(onClick = { Categories.delete(context, category.id); list = Categories.all(context); deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CategoryNameDialog(title: String, confirmLabel: String, initial: String, taken: Set<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val duplicate = trimmed in taken
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                isError = duplicate,
                supportingText = { Text(if (duplicate) "A category with this name already exists" else "*required") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty() && !duplicate) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Long-press a game in the Rom tab: choose which categories it belongs to. */
@Composable
fun SetCategoriesDialog(game: File, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val all = remember { Categories.all(context) }
    var picked by remember { mutableStateOf(Categories.of(context, game)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set categories") },
        text = {
            if (all.isEmpty()) {
                Text("You have no categories yet. Create them in More → Categories.")
            } else {
                Column {
                    all.forEach { category ->
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = if (category.id in picked) picked - category.id else picked + category.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = category.id in picked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                            Text(category.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (all.isNotEmpty()) Categories.set(context, game, picked); onSaved() }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
