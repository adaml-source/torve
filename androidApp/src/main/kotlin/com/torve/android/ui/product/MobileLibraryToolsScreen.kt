package com.torve.android.ui.product

import com.torve.android.ui.community.parityLabel
import com.torve.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MobileLibraryToolsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    ProductPage(stringResource(R.string.flow_library_tools), stringResource(R.string.flow_library_tools_sub), onBack) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Favorites" to "library_collection/favorites", "Viewing history" to "library_collection/history", "Requests" to "library_collection/requests",
                "Cloud files" to "library_collection/cloud", "Saved collections" to "collections", "Your shows" to "your_shows", "Episode calendar" to "calendar",
                "Offline library" to "downloads", "Jellyfin" to "jellyfin_library").forEach { (title, route) ->
                item { ProductToolRow(parityLabel(title), "", Icons.Rounded.Folder, onClick = { onOpen(route) }) }
            }
            item { ProductToolRow(stringResource(R.string.flow_groups), stringResource(R.string.flow_groups_tool_sub), Icons.Rounded.Folder,
                onClick = { onOpen("catalog_groups") }) }
            item { ProductToolRow(stringResource(R.string.flow_trakt_lists), stringResource(R.string.flow_trakt_lists_tool_sub), Icons.Rounded.PlaylistPlay,
                onClick = { onOpen("trakt_lists") }) }
            item { ProductToolRow(stringResource(R.string.flow_profiles), stringResource(R.string.flow_profiles_tool_sub), Icons.Rounded.People,
                onClick = { onOpen("profiles") }) }
        }
    }
}
