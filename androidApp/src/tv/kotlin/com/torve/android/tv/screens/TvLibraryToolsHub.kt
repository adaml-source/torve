package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.torve.android.tv.components.tvSettingsLabel

/** TV-sized collections hub follows the same grouping and stable focus treatment as tvOS. */
@Composable
internal fun TvLibraryToolsHub(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val entry = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; entry.requestFocus() }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(tvSettingsLabel("Library tools"), Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
            TextButton(onClick = onBack) { Text(tvSettingsLabel("Done")) }
        }
        Text(tvSettingsLabel("Your collections and connected services."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        val tools = listOf(
            Triple("favorites", "Favorites", "The titles you love"), Triple("history", "Viewing history", "Your recently watched titles"),
            Triple("cloud", "Cloud files", "Downloads in connected accounts"), Triple("requests", "Requests", "Requests and library availability"),
            Triple("trakt", "Trakt lists", "Your personal lists"), Triple("mdblist", "MDBList", "Search lists and add them to Home"),
            Triple("collections", "Saved collections", "Keep franchises together"), Triple("groups", "Catalog groups", "Organize your catalogue rows"),
            Triple("jellyfin", "Jellyfin", "Your media server"),
            Triple("profiles", "Profiles", "Manage your household"), Triple("receive", "Receive connections", "Set up from your phone"))
        LazyVerticalGrid(GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(tools, key = { _, tool -> tool.first }) { index, tool ->
                var focused by remember { mutableStateOf(false) }
                Surface(onClick = { onOpen(tool.first) }, color = MaterialTheme.colorScheme.surface.copy(alpha = .88f), shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(entry) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .35f), RoundedCornerShape(16.dp))) {
                    Column(Modifier.heightIn(min = 112.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                        Text(tvSettingsLabel(tool.second), style = MaterialTheme.typography.titleLarge)
                        Text(tvSettingsLabel(tool.third), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
