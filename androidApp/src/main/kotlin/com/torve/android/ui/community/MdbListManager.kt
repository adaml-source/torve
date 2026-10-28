package com.torve.android.ui.community

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.android.device.DeviceFormFactor
import com.torve.presentation.mdblist.MdbListViewModel
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

/** Independent actions make Add, Home visibility, and Remove unambiguous with a remote. */
@Composable
fun MdbListManager(onBack: () -> Unit, model: MdbListViewModel = koinInject(), settings: SettingsViewModel = koinInject()) {
    val state by model.state.collectAsState()
    val prefs by settings.state.collectAsState()
    var key by remember(prefs.personalDataUserId) { mutableStateOf("") }
    var section by remember(prefs.personalDataUserId) { mutableStateOf("own") }
    val backEntry = communityEntryFocus()
    val tv = DeviceFormFactor.isTv(LocalContext.current)
    LaunchedEffect(prefs.personalDataUserId) { model.reload() }
    BackHandler(onBack = onBack)
    PersonalBackground {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            CommunityAction(localizedUiText("Back"), onBack, backEntry)
            Text(localizedUiText("MDBList"), style = MaterialTheme.typography.headlineSmall)
            Text(localizedUiText(if (state.apiKey.isBlank()) "Connect your account to search and save lists." else "Connected · Saved lists appear as Home rows."))
        }
        item {
            OutlinedTextField(key, { key = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                label = { Text(localizedUiText(if (state.apiKey.isBlank()) "API key" else "Replace API key")) }, modifier = Modifier.fillMaxWidth())
            CommunityAction(localizedUiText("Save connection"), { model.saveApiKey(key); key = "" }, enabled = key.isNotBlank() && !state.isSaving)
        }
        if (state.apiKey.isNotBlank()) {
            item { Text(localizedUiText("Saved lists"), style = MaterialTheme.typography.titleMedium) }
            items(state.savedLists, key = { "saved:${it.listId}" }) { list ->
                Column {
                    Text(list.name, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CommunityAction(localizedUiText(if (list.enabled) "Hide from Home" else "Show on Home"), { model.toggleList(list.listId, !list.enabled) }, enabled = !state.isSaving)
                        CommunityAction(localizedUiText("Remove"), { model.removeList(list.listId) }, enabled = !state.isSaving)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CommunityAction(localizedUiText("My lists"), { section = "own" })
                    CommunityAction(localizedUiText("Popular"), { section = "popular" })
                    CommunityAction(localizedUiText("Search"), { section = "search" })
                }
            }
            if (section == "search") item {
                OutlinedTextField(state.searchQuery, model::setSearchQuery, singleLine = true, label = { Text(localizedUiText("Search lists")) }, modifier = Modifier.fillMaxWidth())
                CommunityAction(localizedUiText("Search lists"), model::search, enabled = !state.isSearching && state.searchQuery.isNotBlank())
            }
            val lists = when (section) { "own" -> state.ownLists; "popular" -> state.topLists; else -> state.searchResults }
            items(lists, key = { "result:${it.id}" }) { list ->
                val saved = state.savedLists.any { it.listId == list.id }
                CommunityAction(localizedUiText(if (saved) "${list.name} · Saved" else "Add ${list.name}"), { model.addList(list.id, list.name) },
                    Modifier.fillMaxWidth(), enabled = !saved && !state.isSaving)
            }
            if (!state.isLoadingTop && !state.isSearching && lists.isEmpty()) item { Text(localizedUiText("No lists found.")) }
        }
        if (state.isLoadingTop || state.isSearching || state.isSaving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { error -> item {
            Text(com.torve.android.error.resolveErrorKey(LocalContext.current, error) ?: error, color = MaterialTheme.colorScheme.error)
            CommunityAction(localizedUiText("Retry"), { if (section == "search") model.search() else model.loadTopLists() })
        } }
    }
    }
}
