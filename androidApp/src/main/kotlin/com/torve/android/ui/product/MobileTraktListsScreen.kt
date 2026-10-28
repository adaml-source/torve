package com.torve.android.ui.product

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.torve.data.trakt.TraktPersonalList
import com.torve.domain.model.MediaItem
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.lists.TraktListsViewModel
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

@Composable
fun MobileTraktListsScreen(onBack: () -> Unit, onMedia: (MediaItem) -> Unit, onConnect: () -> Unit,
    titleType: String = "movie", titleId: Int = -1, viewModel: TraktListsViewModel = koinInject(), metadata: MetadataRepository = koinInject()) {
    val state by viewModel.state.collectAsState()
    var target by remember(titleType, titleId) { mutableStateOf<MediaItem?>(null) }
    var targetError by remember(titleType, titleId) { mutableStateOf<String?>(null) }
    var loadRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(titleType, titleId, loadRevision) {
        targetError = null
        if (titleId > 0) {
            try { target = metadata.getDetail(titleType, titleId) }
            catch (e: Exception) { if (e is CancellationException) throw e; targetError = "Could not load this title. Try again." }
            if (target == null) { targetError = "Could not load this title. Try again."; return@LaunchedEffect }
        }
        viewModel.load(target)
    }
    DisposableEffect(viewModel) { onDispose { viewModel.clear() } }
    var create by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<TraktPersonalList?>(null) }
    var deleting by remember { mutableStateOf<TraktPersonalList?>(null) }
    val close = { if (state.selected != null) viewModel.closeList() else onBack() }
    BackHandler(onBack = close)
    ProductPage(localizedUiText(state.selected?.name ?: "Trakt lists"), localizedUiText(target?.let { "Add or remove ${it.title}." } ?: "Your personal collections on Trakt."), close, actions = {
        if (state.selected == null) IconButton(onClick = { create = true }, enabled = state.connected && !state.busy && !state.loading && (titleId <= 0 || target != null)) {
            Icon(Icons.Rounded.Add, contentDescription = localizedUiText("Create Trakt list"))
        }
    }) {
        targetError?.let { ProductFeedback(it, true, { loadRevision++ }, Modifier.padding(horizontal = 20.dp)) }
        if (state.selected != null) MobileMediaGrid(state.items, onMedia)
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { item("error") { ProductFeedback(it, true, { viewModel.load(target) }) } }
            state.message?.let { item("message") { ProductFeedback(it) } }
            if (!state.connected) item("connect") {
                ProductEmptyState(localizedUiText("Connect Trakt"), localizedUiText("Sign in to Trakt to manage your personal lists.")) {
                    Button(onClick = onConnect) { Text(localizedUiText("Open connections")) }
                }
            }
            else if (state.lists.isEmpty()) item("empty") {
                ProductEmptyState(localizedUiText(if (state.loading || titleId > 0 && target == null && targetError == null) "Loading your lists" else "Create your first list"),
                    localizedUiText("Keep collections such as weekend films or shows to watch with friends."))
            }
            items(state.lists, key = { it.ids.trakt!! }) { list ->
                val id = list.ids.trakt!!
                Column {
                    ProductToolRow(list.name, localizedUiText("${list.itemCount} titles · ${list.privacy}"),
                        if (target != null && state.membership[id] == true) Icons.Rounded.CheckCircle else Icons.Rounded.PlaylistPlay,
                        onClick = { if (target != null) viewModel.toggleMembership(list) else viewModel.browse(list) },
                        enabled = !state.busy && !state.loading && (titleId <= 0 || target != null), selected = target != null && state.membership[id] == true,
                        modifier = Modifier.testTag("trakt_list_$id"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (target != null) Text(localizedUiText(when (state.membership[id]) { true -> "Tap to remove"; false -> "Tap to add"; null -> "Tap to check membership" }), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { rename = list }, enabled = !state.busy) { Text(localizedUiText("Rename")) }
                        TextButton(onClick = { deleting = list }, enabled = !state.busy) { Text(localizedUiText("Delete")) }
                    }
                }
            }
        }
    }
    if (create || rename != null) {
        val list = rename
        var name by remember(list?.ids?.trakt) { mutableStateOf(list?.name.orEmpty()) }
        AlertDialog(onDismissRequest = { if (!state.busy) { create = false; rename = null } },
            title = { Text(localizedUiText(if (list == null) "Create list" else "Rename list")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(localizedUiText("List name")) }, singleLine = true, enabled = !state.busy)
                state.error?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(enabled = name.isNotBlank() && !state.busy, onClick = {
                val done = { create = false; rename = null }
                if (list == null) viewModel.create(name.trim(), done) else viewModel.rename(list, name.trim(), done)
            }) { Text(localizedUiText(if (state.busy) "Saving…" else "Save")) } },
            dismissButton = { TextButton(onClick = { create = false; rename = null }, enabled = !state.busy) { Text(localizedUiText("Cancel")) } })
    }
    deleting?.let { list -> AlertDialog(onDismissRequest = { if (!state.busy) deleting = null }, title = { Text(localizedUiText("Delete ${list.name}?")) },
        text = { Column { Text(localizedUiText("This removes the list from Trakt on all your devices.")); state.error?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(onClick = { viewModel.delete(list) { deleting = null } }, enabled = !state.busy) { Text(localizedUiText(if (state.busy) "Deleting…" else "Delete list")) } },
        dismissButton = { TextButton(onClick = { deleting = null }, enabled = !state.busy) { Text(localizedUiText("Cancel")) } }) }
}
