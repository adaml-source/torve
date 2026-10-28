package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.torve.android.tv.components.*
import com.torve.android.ui.theme.*
import com.torve.data.trakt.TraktPersonalList
import com.torve.domain.model.MediaItem
import com.torve.presentation.lists.TraktListsViewModel
import org.koin.compose.koinInject

@Composable
fun TvTraktListsScreen(onBack: () -> Unit, onConnect: () -> Unit, onMedia: (MediaItem) -> Unit,
    onContentFocused: (FocusRequester) -> Unit, forTitle: MediaItem? = null, viewModel: TraktListsViewModel = koinInject(),
    clearOnDispose: Boolean = true) {
    TvWorkflowSurface("trakt_lists") { TvTraktListsContent(onBack, onConnect, onMedia, onContentFocused, forTitle, viewModel, clearOnDispose) }
}

@Composable
private fun TvTraktListsContent(onBack: () -> Unit, onConnect: () -> Unit, onMedia: (MediaItem) -> Unit,
    onContentFocused: (FocusRequester) -> Unit, forTitle: MediaItem?, viewModel: TraktListsViewModel, clearOnDispose: Boolean) {
    val state by viewModel.state.collectAsState()
    val entry = remember { FocusRequester() }; val create = remember { FocusRequester() }
    var form by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TraktPersonalList?>(null) }
    var deleting by remember { mutableStateOf<TraktPersonalList?>(null) }
    var origin by remember { mutableStateOf<FocusRequester?>(null) }
    var openedListId by rememberSaveable { mutableStateOf<Int?>(null) }
    var returningListId by remember { mutableStateOf<Int?>(null) }
    val listRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    LaunchedEffect(forTitle?.id) { viewModel.load(forTitle) }
    DisposableEffect(viewModel) { onDispose { if (clearOnDispose) viewModel.clear() } }
    LaunchedEffect(state.selected?.ids?.trakt) {
        if (state.selected != null) { openedListId = state.selected?.ids?.trakt; withFrameNanos { }; entry.requestFocus() }
        else if (returningListId != null) { withFrameNanos { }
            if (listRequesters[returningListId]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } != true) entry.requestFocus()
            returningListId = null
        }
    }
    val back = { if (state.selected != null) { returningListId = openedListId; viewModel.closeList() } else onBack() }
    TvWorkflowPage(localizedUiText(state.selected?.name ?: if (forTitle != null) "Lists · ${forTitle.title}" else "Trakt lists"),
        localizedUiText(state.error ?: state.message ?: if (state.busy || state.loading) "Updating Trakt…" else
        if (forTitle != null) "Add this title to your lists or remove it from lists it already belongs to."
        else "Your personal Trakt collections. Changes sync through your Trakt account."), back, entry, onContentFocused, toolbar = {
            if (!state.connected) TvWorkflowAction(localizedUiText("Connect Trakt"), onConnect)
            else if (state.selected == null) {
                TvWorkflowAction(localizedUiText("Create list"), { origin = create; editing = null; form = true }, requester = create)
                TvWorkflowAction(localizedUiText("Refresh"), { viewModel.load(forTitle) })
            }
        }) {
        if (!state.connected) item { Text(localizedUiText("Connect Trakt in Settings → Connections to manage lists."), color = Silver) }
        else if (state.selected != null) {
            if (state.items.isEmpty() && !state.busy) item { Text(localizedUiText("This list is empty."), color = Silver) }
            items(state.items, key = { "${it.type}:${it.id}" }) { item ->
                val requester = remember(item.id) { FocusRequester() }
                TvWorkflowAction(item.title, { onMedia(item) }, Modifier.fillMaxWidth(), subtitle = localizedUiText("${item.year ?: ""} · ${if (item.type.name == "MOVIE") "Movie" else "Series"}"),
                    requester = requester, onFocused = { onContentFocused(requester) }, leading = {
                        coil3.compose.AsyncImage(item.posterUrl ?: item.backdropUrl, item.title,
                            Modifier.width(64.dp).height(96.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    })
            }
        } else {
            if (state.lists.isEmpty() && !state.loading) item { Text(localizedUiText("No personal lists yet. Create one to start a collection."), color = Silver) }
            items(state.lists, key = { it.ids.trakt!! }) { list ->
                val requester = remember(list.ids.trakt) { FocusRequester() }
                val renameRequester = remember(list.ids.trakt) { FocusRequester() }
                val deleteRequester = remember(list.ids.trakt) { FocusRequester() }
                SideEffect { listRequesters[list.ids.trakt!!] = requester }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvWorkflowAction(list.name, { viewModel.browse(list) }, Modifier.weight(1f), subtitle = localizedUiText("${list.itemCount} titles · ${list.privacy}"),
                        requester = requester, onFocused = { onContentFocused(requester) })
                    if (forTitle != null) TvWorkflowAction(localizedUiText(when(state.membership[list.ids.trakt]) { true -> "Remove title"; false -> "Add title"; null -> "Check list" }),
                        { viewModel.toggleMembership(list) })
                    TvWorkflowAction(localizedUiText("Rename"), { origin = renameRequester; editing = list; form = true }, requester = renameRequester)
                    TvWorkflowAction(localizedUiText("Delete"), { origin = deleteRequester; deleting = list }, destructive = true, requester = deleteRequester)
                }
            }
        }
    }
    if (form) {
        var name by remember(editing?.ids?.trakt) { mutableStateOf(editing?.name.orEmpty()) }
        TvWorkflowDialog(localizedUiText(if (editing == null) "Create Trakt list" else "Rename list"), { if (!state.busy) form = false }, origin) {
            TvClickToEditOutlinedTextField(name, { name = it }, { Text(localizedUiText("List name")) }, true, Modifier.fillMaxWidth())
            state.error?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error) }
            TvWorkflowAction(localizedUiText("Save list"), {
                if (editing == null) viewModel.create(name) { form = false }
                else viewModel.rename(editing!!, name) { form = false }
            }, subtitle = (if (state.busy) localizedUiText("Saving…") else null)?.let { com.torve.android.tv.components.tvSettingsLabel(it) })
        }
    }
    deleting?.let { list ->
        TvWorkflowDialog(localizedUiText("Delete ${list.name}?"), { if (!state.busy) deleting = null }, origin) {
            Text(localizedUiText("This permanently deletes the list from Trakt."), color = Silver)
            state.error?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error) }
            TvWorkflowAction(localizedUiText("Delete list"), { viewModel.delete(list) { deleting = null; origin = create } }, destructive = true)
        }
    }
}
