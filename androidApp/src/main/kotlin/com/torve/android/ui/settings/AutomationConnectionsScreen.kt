package com.torve.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.data.auth.AuthClient
import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationInstanceRole
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.presentation.integrations.AutomationSettingsViewModel
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

@Composable
fun AutomationConnectionsScreen(
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
    viewModel: AutomationSettingsViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    authClient: AuthClient = koinInject(),
    secretStore: IntegrationSecretStore = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val authUser by authClient.authUserFlow.collectAsState()
    var pendingRemoval by remember { mutableStateOf<AutomationInstance?>(null) }
    val tvBackFocusRequester = remember { FocusRequester() }
    val saveFocusRequester = remember { FocusRequester() }
    val seerrSaveFocusRequester = remember { FocusRequester() }
    val isTvFocusMode = initialFocusRequester != null
    var wasBusy by remember { mutableStateOf(false) }
    var wasSeerrBusy by remember { mutableStateOf(false) }
    var seerrStorageMode by remember { mutableStateOf(IntegrationStorageMode.DEVICE_ONLY) }

    // A TV sub-page must own the remote Back button. Relying only on the
    // focusable arrow left users trapped when focus was further down the form.
    BackHandler(enabled = isTvFocusMode && pendingRemoval == null, onBack = onBack)

    LaunchedEffect(authUser) {
        val hasSavedKey = secretStore.get(IntegrationSecretKey.SEERR_API_KEY).isNullOrBlank().not()
        seerrStorageMode = if (hasSavedKey) {
            secretStore.getStorageMode(IntegrationSecretKey.SEERR_API_KEY)
        } else if (authUser != null) {
            IntegrationStorageMode.ACCOUNT
        } else {
            IntegrationStorageMode.DEVICE_ONLY
        }
    }

    LaunchedEffect(initialFocusRequester) {
        initialFocusRequester?.let { requester ->
            delay(120)
            runCatching { requester.requestFocus() }
        }
    }

    LaunchedEffect(state.isBusy) {
        if (isTvFocusMode && wasBusy && !state.isBusy) {
            delay(80)
            runCatching { saveFocusRequester.requestFocus() }
        }
        wasBusy = state.isBusy
    }

    LaunchedEffect(settingsState.seerrLoading) {
        if (isTvFocusMode && wasSeerrBusy && !settingsState.seerrLoading) {
            delay(80)
            runCatching { seerrSaveFocusRequester.requestFocus() }
        }
        wasSeerrBusy = settingsState.seerrLoading
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = if (isTvFocusMode) Modifier.focusGroup() else Modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = if (isTvFocusMode) {
                    Modifier
                        .focusRequester(tvBackFocusRequester)
                        .focusProperties {
                            right = initialFocusRequester
                            down = initialFocusRequester
                        }
                } else {
                    Modifier
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Snow)
            }
            Column(Modifier.weight(1f)) {
                Text("Library downloads & *Arr", color = Snow, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Connect Seerr for title requests, then manage the services that acquire them", color = Silver, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Gunmetal.copy(alpha = 0.92f)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Seerr request manager", color = Snow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Required for Download to library. Seerr sends movie requests to Radarr and selected-season requests to Sonarr.",
                    color = Silver,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = settingsState.seerrServerUrl,
                    onValueChange = settingsViewModel::setSeerrServerUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isTvFocusMode) {
                                Modifier
                                    .focusRequester(initialFocusRequester!!)
                                    .focusProperties { up = tvBackFocusRequester }
                            } else {
                                Modifier
                            },
                        ),
                    label = { Text("Seerr server URL, for example http://192.168.1.20:5055") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = settingsState.seerrApiKey,
                    onValueChange = settingsViewModel::updateSeerrApiKeyInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Seerr API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (
                    settingsState.seerrServerUrl.contains("localhost", ignoreCase = true) ||
                    settingsState.seerrServerUrl.contains("127.0.0.1")
                ) {
                    Text(
                        "Use the server computer's LAN address. localhost points back to this Fire TV.",
                        color = Ruby,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Where should this Seerr setup be kept?", color = Snow, fontWeight = FontWeight.Medium)
                Row(
                    modifier = Modifier.fillMaxWidth().focusGroup().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { seerrStorageMode = IntegrationStorageMode.ACCOUNT },
                        enabled = authUser != null,
                        colors = adminConnectionChoiceButtonColors(seerrStorageMode == IntegrationStorageMode.ACCOUNT),
                    ) { Text("Sync with my account") }
                    OutlinedButton(
                        onClick = { seerrStorageMode = IntegrationStorageMode.DEVICE_ONLY },
                        colors = adminConnectionChoiceButtonColors(seerrStorageMode == IntegrationStorageMode.DEVICE_ONLY),
                    ) { Text("Only on this device") }
                }
                Text(
                    when {
                        seerrStorageMode == IntegrationStorageMode.ACCOUNT ->
                            "The URL and API key are encrypted in your Torve account and restored on your other devices."
                        authUser == null ->
                            "Sign in to Torve to sync this connection, or keep it only on this Fire TV."
                        else ->
                            "The URL and API key stay only on this Fire TV."
                    },
                    color = Silver,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { settingsViewModel.saveAndTestSeerrConnection(seerrStorageMode) },
                        modifier = if (isTvFocusMode) Modifier.focusRequester(seerrSaveFocusRequester) else Modifier,
                        enabled = !settingsState.seerrLoading &&
                            settingsState.seerrServerUrl.isNotBlank() &&
                            settingsState.seerrApiKey.isNotBlank(),
                        colors = adminConnectionButtonColors(),
                    ) {
                        if (settingsState.seerrLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Obsidian, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Save and test")
                    }
                    if (settingsState.seerrConnected) {
                        OutlinedButton(onClick = settingsViewModel::disconnectSeerr) {
                            Text("Disconnect", color = Ruby)
                        }
                    }
                }
                settingsState.seerrStatusMessage?.let { message ->
                    Text(message, color = if (settingsState.seerrConnected) Emerald else Ruby)
                }
                if (settingsState.seerrConnected && settingsState.seerrStatusMessage == null) {
                    Text("Connected · Download to library is enabled on title pages", color = Emerald)
                }
            }
        }

        if (state.instances.any { it.storageMode == IntegrationStorageMode.DEVICE_ONLY }) {
            OutlinedButton(
                onClick = viewModel::syncAllWithAccount,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy,
                colors = adminConnectionChoiceButtonColors(false),
            ) {
                Text("Sync all existing connections with my account")
            }
        }

        state.instances.forEachIndexed { index, instance ->
            key(instance.id) {
                Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Gunmetal),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().focusGroup().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(instance.name, color = Snow, fontWeight = FontWeight.SemiBold)
                        Text("${serviceLabel(instance.serviceType)} · ${instance.serverUrl}", color = Silver, style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (instance.storageMode == IntegrationStorageMode.ACCOUNT) "Restores with your Torve account" else "This device only",
                            color = if (instance.storageMode == IntegrationStorageMode.ACCOUNT) Amber else Silver,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.edit(instance) },
                        modifier = Modifier,
                    ) { Text("Edit", color = Amber) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { pendingRemoval = instance }) { Text("Remove", color = Ruby) }
                }
                }
            }
        }

        key("connection-form") {
            Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Gunmetal.copy(alpha = 0.86f)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (state.editingId == null) "Add connection" else "Edit connection", color = Snow, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth().focusGroup().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AutomationServiceType.entries.forEachIndexed { index, type ->
                        OutlinedButton(
                            onClick = { viewModel.selectService(type) },
                            modifier = Modifier,
                            colors = adminConnectionChoiceButtonColors(state.serviceType == type),
                        ) { Text(serviceLabel(type)) }
                    }
                }
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::updateName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Connection name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = viewModel::updateServerUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server URL, including http(s) and port") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::updateApiKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (state.serviceType == AutomationServiceType.TDARR) "Optional API key" else "API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Text("Where should this setup be kept?", color = Snow, fontWeight = FontWeight.Medium)
                Row(
                    modifier = Modifier.fillMaxWidth().focusGroup().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.selectStorageMode(IntegrationStorageMode.ACCOUNT) },
                        colors = adminConnectionChoiceButtonColors(state.storageMode == IntegrationStorageMode.ACCOUNT),
                    ) { Text("Sync with my account") }
                    OutlinedButton(
                        onClick = { viewModel.selectStorageMode(IntegrationStorageMode.DEVICE_ONLY) },
                        colors = adminConnectionChoiceButtonColors(state.storageMode == IntegrationStorageMode.DEVICE_ONLY),
                    ) { Text("Only on this device") }
                }
                Text(
                    if (state.storageMode == IntegrationStorageMode.ACCOUNT) {
                        "The URL and API key are encrypted in your Torve account and restored on your other signed-in devices."
                    } else {
                        "The URL and API key stay on this device and are not sent to Torve."
                    },
                    color = Silver,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.serviceType == AutomationServiceType.SONARR || state.serviceType == AutomationServiceType.RADARR) {
                    Row(
                        modifier = Modifier.fillMaxWidth().focusGroup().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AutomationInstanceRole.entries.forEach { role ->
                            val label = if (role == AutomationInstanceRole.UHD) "4K" else "Standard"
                            OutlinedButton(
                                onClick = { viewModel.selectRole(role) },
                                colors = adminConnectionChoiceButtonColors(state.role == role),
                            ) { Text(label) }
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.setDefault(!state.isDefault) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(checked = state.isDefault, onCheckedChange = null)
                        Text("Default for this quality role", color = Snow)
                    }
                }
                Row(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (!state.isBusy) viewModel.saveAndTest() },
                        modifier = if (isTvFocusMode) Modifier.focusRequester(saveFocusRequester) else Modifier,
                        enabled = isTvFocusMode || !state.isBusy,
                        colors = adminConnectionButtonColors(),
                    ) {
                        if (state.isBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Obsidian, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Save and test")
                    }
                    if (state.editingId != null) {
                        OutlinedButton(onClick = viewModel::cancelEdit) { Text("Cancel", color = Snow) }
                    }
                }
                state.message?.let { Text(it, color = Emerald) }
                state.error?.let { Text(it, color = Ruby) }
            }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    pendingRemoval?.let { instance ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove ${instance.name}?") },
            text = {
                Text(
                    if (instance.storageMode == IntegrationStorageMode.ACCOUNT) {
                        "This removes the connection from your Torve account and all devices after their next refresh."
                    } else {
                        "The connection metadata and encrypted API key will be deleted from this device."
                    },
                )
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    viewModel.remove(instance)
                }) { Text("Remove", color = Ruby) }
            },
        )
    }
}

@Composable
private fun adminConnectionButtonColors() = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian)

@Composable
private fun adminConnectionChoiceButtonColors(selected: Boolean) = ButtonDefaults.outlinedButtonColors(
    containerColor = if (selected) Amber else androidx.compose.ui.graphics.Color.Transparent,
    contentColor = if (selected) Obsidian else Snow,
)

private fun serviceLabel(type: AutomationServiceType): String =
    type.name.lowercase().replaceFirstChar { it.uppercase() }
