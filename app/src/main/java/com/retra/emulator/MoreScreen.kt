package com.retra.emulator

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SOURCE_URL = "https://github.com/Layfate/Retra"

/** Mihon-style More tab: logo header, then setup and about rows. */
@Composable
fun MoreScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasRtp by remember { mutableStateOf(Library.hasRtp(context)) }
    var importing by remember { mutableStateOf(false) }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }

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

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Column(
            Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(colorResource(R.color.ic_launcher_background))) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Retra logo", modifier = Modifier.size(96.dp))
            }
            Text("Retra!", style = MaterialTheme.typography.headlineSmall)
        }
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        HorizontalDivider()
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_sports_esports), contentDescription = null) },
            headlineContent = { Text("RPG Maker XP RTP") },
            supportingContent = { Text(if (hasRtp) "Installed. Tap to replace it" else "Not installed. Some XP games need it; tap to pick the RTP folder") },
            modifier = Modifier.clickable(enabled = !importing) { rtpPicker.launch(null) },
        )
        HorizontalDivider()
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_code), contentDescription = null) },
            headlineContent = { Text("Source code") },
            supportingContent = { Text(SOURCE_URL) },
            modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) },
        )
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
            headlineContent = { Text("About") },
            supportingContent = {
                Text("Version $version · GPL-3.0\nLibretroDroid (GPL-3.0), mGBA (MPL-2.0), mkxp-z (GPL)")
            },
        )
    }
}
