package com.torve.android.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.torve.android.R
import com.torve.android.files.*
import com.torve.data.auth.UserIdProvider
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.repository.ProfileRepository
import com.torve.domain.repository.WatchProgressRepository
import org.koin.compose.koinInject

@Composable
fun FilesBrowserScreen(onBack: () -> Unit, onPlay: (WebDavEntry) -> Unit, isTv: Boolean = false,
    onContentFocused: (FocusRequester) -> Unit = {}, ids: UserIdProvider = koinInject(),
    secrets: IntegrationSecretStore = koinInject(), profiles: ProfileRepository = koinInject(),
    progress: WatchProgressRepository = koinInject()) {
    val controller = remember(ids.currentDataUserId()) { FilesBrowserController(ids, secrets, profiles, progress) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val state by controller.state.collectAsState()
    var setup by remember { mutableStateOf(false) }
    val back = remember { FocusRequester() }
    BackHandler { if (!controller.parent()) onBack() }
    LaunchedEffect(isTv) { if (isTv) { withFrameNanos { }; runCatching { back.requestFocus() } } }
    Surface(Modifier.fillMaxSize().testTag("files_browser")) {
    Column(Modifier.fillMaxSize().padding(horizontal = if (isTv) 44.dp else 20.dp, vertical = if (isTv) 28.dp else 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.torve.android.ui.components.BackButton({ if (!controller.parent()) onBack() })
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.gap_files_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.gap_files_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton({ setup = true }, enabled = !state.restricted, modifier = Modifier.heightIn(min = 48.dp).testTag("files_connection")) {
                Text(stringResource(if (state.configured) R.string.gap_files_connection else R.string.gap_files_connect))
            }
        }
        if (state.restricted) Text(stringResource(R.string.gap_files_restricted), Modifier.padding(top = 24.dp))
        else {
            if (state.configured) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(java.net.URI(state.folder).path, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                TextButton(controller::refresh, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gap_refresh)) }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            state.error?.let { failure ->
                Text(stringResource(when (failure) {
                    WebDavFailure.AUTHENTICATION -> R.string.gap_files_auth_error
                    WebDavFailure.ADDRESS -> R.string.gap_files_address_error
                    WebDavFailure.NETWORK -> R.string.gap_files_network_error
                }), Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.error)
                if (state.configured) TextButton(controller::refresh, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_retry)) }
            }
            if (!state.loading && state.entries.isEmpty() && state.error == null) Text(stringResource(
                if (state.configured) R.string.gap_files_empty else R.string.gap_files_not_connected), Modifier.padding(top = 24.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.entries, key = { it.url }) { entry ->
                    val focus = remember { FocusRequester() }
                    var focused by remember { mutableStateOf(false) }
                    OutlinedCard(Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused; if (it.isFocused) onContentFocused(focus) }
                        .clickable(enabled = !state.loading) { if (entry.folder) controller.openFolder(entry) else controller.play(entry, onPlay) },
                        border = androidx.compose.foundation.BorderStroke(if (focused) 2.dp else 1.dp,
                            if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (entry.folder) Icons.Filled.Folder else Icons.Filled.PlayCircle, null, Modifier.size(28.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                val saved = state.progress[entry.mediaId]
                                if (saved != null && saved.positionMs > 0 && saved.progressPercent < .95f) Text(
                                    stringResource(R.string.gap_files_resume, com.torve.presentation.detail.playbackPositionLabel(saved.positionMs)), style = MaterialTheme.typography.bodySmall)
                                else entry.size?.let { size -> Text(android.text.format.Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, size), style = MaterialTheme.typography.bodySmall) }
                            }
                            Icon(Icons.Filled.ChevronRight, null)
                        }
                    }
                }
            }
        }
    } }
    if (setup) FilesConnectionDialog(state.configured, onDismiss = { setup = false; if (isTv) runCatching { back.requestFocus() } },
        onConnect = { url, user, pass -> controller.connect(url, user, pass); setup = false },
        onDisconnect = { controller.disconnect(); setup = false })
}

@Composable
private fun FilesConnectionDialog(configured: Boolean, onDismiss: () -> Unit,
    onConnect: (String, String, String) -> Unit, onDisconnect: () -> Unit) {
    var url by remember { mutableStateOf("") }; var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 520.dp).padding(20.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.gap_files_connect), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.gap_files_device_scope), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().focusRequester(first), label = { Text(stringResource(R.string.gap_files_address)) }, singleLine = true)
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.gap_files_username)) }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.gap_files_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    if (configured) TextButton(onDisconnect) { Text(stringResource(R.string.gap_files_disconnect)) }
                    Button({ onConnect(url, user, pass) }, enabled = url.isNotBlank()) { Text(stringResource(R.string.common_connect)) }
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
}
