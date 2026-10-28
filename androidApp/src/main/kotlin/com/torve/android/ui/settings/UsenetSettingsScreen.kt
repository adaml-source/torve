package com.torve.android.ui.settings

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.torve.android.R
import com.torve.android.remote.TorveRemoteDialog
import com.torve.android.tv.components.TvClickToEditOutlinedTextField
import com.torve.android.tv.components.TvSettingsDialogSurface
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Amber
import com.torve.presentation.panda.PandaSetupViewModel
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

/** Account-backed Usenet settings. There is no provider wizard or extension catalogue here. */
@Composable
fun UsenetSettingsScreen(onBack: () -> Unit, isTv: Boolean = false, embedded: Boolean = false,
    entryFocus: FocusRequester? = null, onFocused: (FocusRequester) -> Unit = {},
    viewModel: PandaSetupViewModel = koinInject()) {
    val state by viewModel.state.collectAsState()
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(viewModel) { viewModel.openUsenetSetup().join(); loading=false }
    Column(Modifier.fillMaxSize().padding(if (embedded) 0.dp else 20.dp).testTag("usenet.settings")) {
        if (!embedded) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BackButton(onClick = onBack)
            Text(stringResource(R.string.usenet_setup_menu), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading || state.isLoadingConfig) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.configLoadFailed) {
                Text(stringResource(R.string.usenet_setup_load_failed))
                UsenetAction(stringResource(R.string.usenet_setup_retry), onFocused = onFocused) { viewModel.openUsenetSetup() }
            }
            UsenetAction(stringResource(R.string.panda_setup_usenet_enable), if (state.enableUsenet) stringResource(R.string.common_on) else stringResource(R.string.common_off),
                entryFocus, onFocused) { viewModel.setEnableUsenet(!state.enableUsenet) }
            UsenetChoice(stringResource(R.string.panda_setup_usenet_provider), state.usenetProvider,
                listOf("easynews" to "Easynews", "generic" to stringResource(R.string.panda_setup_usenet_generic)), onFocused, viewModel::setUsenetProvider)
            if (state.usenetProvider == "generic") {
                UsenetField(stringResource(R.string.panda_setup_usenet_host), state.usenetHost, isTv, onFocused = onFocused, change = viewModel::setUsenetHost)
                UsenetField(stringResource(R.string.panda_setup_usenet_port), state.usenetPort.toString(), isTv, onFocused = onFocused) { it.toIntOrNull()?.let(viewModel::setUsenetPort) }
                UsenetAction(stringResource(R.string.panda_setup_usenet_ssl), if (state.usenetSSL) "SSL" else stringResource(R.string.common_off), onFocused = onFocused) { viewModel.setUsenetSSL(!state.usenetSSL) }
            }
            UsenetField(stringResource(R.string.panda_setup_usenet_username), state.usenetUsername, isTv, onFocused = onFocused, change = viewModel::setUsenetUsername)
            UsenetField(stringResource(R.string.panda_setup_usenet_password), state.usenetPassword, isTv, true, onFocused, viewModel::setUsenetPassword)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            state.nzbIndexers.forEachIndexed { index, row ->
                key(index) {
                    UsenetChoice("${stringResource(R.string.panda_setup_usenet_indexer)} ${index + 1}", row.type,
                        state.schema.nzbIndexers.map { it to indexerName(it) }, onFocused) { value -> viewModel.updateIndexer(index) { it.copy(type = value) } }
                    if (row.type == "custom") UsenetField(stringResource(R.string.panda_setup_usenet_indexer_url), row.url, isTv, onFocused = onFocused) { value -> viewModel.updateIndexer(index) { it.copy(url = value) } }
                    if (row.type != "none") {
                        UsenetField(stringResource(R.string.panda_setup_usenet_indexer_key), row.apiKey, isTv, true, onFocused) { value -> viewModel.updateIndexer(index) { it.copy(apiKey = value) } }
                        if (row.apiKey.isBlank() && "indexer_api_key_$index" in state.serverHasSecrets)
                            Text(stringResource(R.string.usenet_setup_saved_secrets), style = MaterialTheme.typography.labelSmall)
                    }
                    if (state.nzbIndexers.size > 1) UsenetAction(stringResource(R.string.usenet_setup_remove_indexer), onFocused = onFocused) { viewModel.removeIndexer(index) }
                }
            }
            UsenetAction(stringResource(R.string.panda_setup_usenet_add_indexer), onFocused = onFocused, click = viewModel::addIndexer)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            UsenetChoice(stringResource(R.string.panda_setup_usenet_client), state.downloadClient,
                state.schema.downloadClients.map { it to when(it) { "none" -> stringResource(R.string.panda_setup_usenet_client_none); "torbox" -> "TorBox"; "sabnzbd" -> "SABnzbd"; "nzbget" -> "NZBGet"; else -> it.replaceFirstChar(Char::uppercase) } }, onFocused, viewModel::setDownloadClient)
            if (state.downloadClient != "none") {
                if (state.downloadClient in setOf("sabnzbd", "nzbget")) UsenetField(stringResource(R.string.panda_setup_usenet_client_url), state.downloadClientUrl, isTv, onFocused = onFocused, change = viewModel::setDownloadClientUrl)
                if (state.downloadClient == "nzbget") {
                    UsenetField(stringResource(R.string.panda_setup_usenet_client_user), state.downloadClientUsername, isTv, onFocused = onFocused, change = viewModel::setDownloadClientUsername)
                    UsenetField(stringResource(R.string.panda_setup_usenet_client_pass), state.downloadClientPassword, isTv, true, onFocused, viewModel::setDownloadClientPassword)
                } else UsenetField(stringResource(R.string.panda_setup_usenet_client_key), state.downloadClientApiKey, isTv, true, onFocused, viewModel::setDownloadClientApiKey)
            }
            Button(onClick = viewModel::saveConfigAndInstall, enabled = !loading && !state.isSaving && !state.isLoadingConfig && !state.configLoadFailed,
                modifier = Modifier.fillMaxWidth().testTag("usenet.save")) { Text(stringResource(if(state.isSaving) R.string.common_loading else R.string.common_save)) }
            if (state.addonInstalled) Text(stringResource(R.string.usenet_setup_saved))
            if (state.saveError != null || state.error != null) Text(stringResource(R.string.usenet_setup_save_failed))
        }
    }
}

@Composable private fun indexerName(id: String): String = when(id) {
    "none" -> stringResource(R.string.panda_setup_usenet_indexer_none)
    "scenenzbs" -> "Treasure Maps (SceneNZBs)"
    "nzbgeek" -> "NZBgeek"
    "dognzb" -> "DogNZB"
    "nzbplanet" -> "NZBPlanet"
    "custom" -> stringResource(R.string.panda_setup_usenet_indexer_custom)
    else -> id
}

@Composable private fun UsenetField(label: String, value: String, tv: Boolean, secret: Boolean = false,
    onFocused: (FocusRequester) -> Unit, change: (String) -> Unit) {
    val req = remember { FocusRequester() }
    val modifier = Modifier.fillMaxWidth().focusRequester(req).onFocusChanged { if(it.hasFocus) onFocused(req) }
    val transform = if(secret) PasswordVisualTransformation() else VisualTransformation.None
    if(tv) TvClickToEditOutlinedTextField(value, change, { Text(localizedUiText(label)) }, true, modifier, visualTransformation = transform)
    else OutlinedTextField(value, change, modifier, label = { Text(localizedUiText(label)) }, singleLine = true, visualTransformation = transform)
}

@Composable private fun UsenetAction(title: String, value: String? = null, entry: FocusRequester? = null,
    onFocused: (FocusRequester) -> Unit, click: () -> Unit) {
    val req = entry ?: remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = click, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().focusRequester(req).onFocusChanged { focused=it.isFocused; if(focused) onFocused(req) }
            .border(if(focused) 2.dp else 1.dp, if(focused) Amber else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(localizedUiText(title), Modifier.weight(1f)); value?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun UsenetChoice(title: String, selected: String, options: List<Pair<String,String>>,
    onFocused: (FocusRequester) -> Unit, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    val origin = remember { FocusRequester() }
    val window = LocalWindowInfo.current
    LaunchedEffect(open) { if(!open && restore) { snapshotFlow { window.isWindowFocused }.first { it }; withFrameNanos { }; origin.requestFocus(); restore=false } }
    UsenetAction(title, options.firstOrNull { it.first == selected }?.second, origin, onFocused) { open=true; restore=true }
    if(open) TorveRemoteDialog({ open=false }, DialogProperties(usePlatformDefaultWidth=false)) {
        val first = remember { FocusRequester() }
        Surface(color = TvSettingsDialogSurface, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.widthIn(max=460.dp).heightIn(max=480.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localizedUiText(title), style = MaterialTheme.typography.titleLarge)
                options.forEachIndexed { i, option -> UsenetAction(option.second, if(option.first==selected) "✓" else null,
                    if(i==0) first else null, {}) { select(option.first); open=false } }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
    }
}
