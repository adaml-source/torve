package com.torve.android.ui.settings

import com.torve.android.ui.community.parityLabel as localizedUiText

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import com.torve.android.ui.community.PhoneGroupedSettingsMenu
import com.torve.android.ui.community.SettingsMenuEntry
import com.torve.android.ui.community.SettingsMenus
import com.torve.android.ui.community.parityLabel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.AutomationInstanceRole
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.model.DebridServiceType
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.settings.IntegrationReadinessStatus
import com.torve.presentation.settings.IntegrationReadinessSummary
import com.torve.presentation.settings.buildIntegrationReadinessSummary
import com.torve.presentation.integrations.AutomationSettingsViewModel
import com.torve.presentation.usenet.NzbdavSetupViewModel
import com.torve.presentation.usenet.NzbdavStatus
import com.torve.presentation.usenet.NzbdavTestResult
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IntegrationsScreen(
    onBack: () -> Unit,
    onAutomationAdminClick: () -> Unit = {},
    initialService: String = "",
    viewModel: SettingsViewModel = koinInject(),
    automationViewModel: AutomationSettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val automationState by automationViewModel.state.collectAsState()
    val health: com.torve.presentation.providerhealth.ProviderHealthCoordinator = koinInject()
    val healthEntries by health.entries.collectAsState()
    val readiness = remember(state, automationState.instances, healthEntries) {
        buildIntegrationReadinessSummary(state, automationState.instances, healthEntries)
    }
    val context = LocalContext.current
    val integrationScroll = rememberScrollState()
    val serviceNames = remember { linkedMapOf("debrid" to "Streaming services", "omdb" to "OMDb", "trakt" to "Trakt", "simkl" to "SIMKL", "seerr" to "Seerr", "automation" to "Library automation", "jellyfin" to "Jellyfin", "plex" to "Plex", "nzbdav" to "NzbDAV", "opensubtitles" to "OpenSubtitles") }
    var selectedService by rememberSaveable(initialService) { mutableStateOf(initialService.takeIf { it in serviceNames }.orEmpty()) }
    var serviceNode by rememberSaveable { mutableStateOf<String?>(null) }
    fun goBack() { if (initialService.isBlank() && selectedService.isNotBlank()) selectedService = "" else onBack() }
    BackHandler(onBack = ::goBack)
    fun repairAnchor(service: String) = Modifier
    LaunchedEffect(selectedService) { integrationScroll.scrollTo(0) }
    val secretStore: IntegrationSecretStore = koinInject()
    val authClient: com.torve.data.auth.AuthClient = koinInject()
    val authUser by authClient.authUserFlow.collectAsState()
    val accountSessionCoordinator: com.torve.presentation.session.AccountSessionCoordinator = koinInject()
    val scope = rememberCoroutineScope()
    val defaultStorageMode = if (authUser != null) com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT
        else com.torve.domain.integrations.IntegrationStorageMode.DEVICE_ONLY
    var omdbStorageMode by remember(defaultStorageMode) { mutableStateOf(defaultStorageMode) }
    var jellyfinStorageMode by remember(defaultStorageMode) { mutableStateOf(defaultStorageMode) }
    var plexStorageMode by remember(defaultStorageMode) { mutableStateOf(defaultStorageMode) }
    var seerrStorageMode by remember(defaultStorageMode) { mutableStateOf(defaultStorageMode) }

    LaunchedEffect(selectedService) {
        when (selectedService) {
            "jellyfin" -> {
                secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY)?.let { stored ->
                    viewModel.setJellyfinApiKey(stored)
                    jellyfinStorageMode = secretStore.getStorageMode(IntegrationSecretKey.JELLYFIN_API_KEY)
                }
                viewModel.loadJellyfinProfiles()
            }
            "plex" -> secretStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN)?.let(viewModel::setPlexAccessToken)
            "seerr" -> secretStore.get(IntegrationSecretKey.SEERR_API_KEY)?.let(viewModel::updateSeerrApiKeyInput)
        }
    }
    if (selectedService.isBlank()) {
        fun supported(items: List<SettingsMenuEntry>): List<SettingsMenuEntry> = items.mapNotNull { item ->
            if (item.children.isEmpty()) item.takeIf { it.id in serviceNames }
            else supported(item.children).takeIf { it.isNotEmpty() }?.let { item.copy(children = it) }
        }
        val entries = remember { supported(SettingsMenus.sources()) }
        PhoneGroupedSettingsMenu(entries, localizedUiText("Sources & services"), serviceNode, { serviceNode = it }, onBack) { action ->
            selectedService = if (action == "Connections") "debrid" else action.lowercase()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(integrationScroll),
    ) {
        BackButton(onClick = ::goBack)
        Spacer(Modifier.height(8.dp))
        Text(
            localizedUiText(serviceNames.getValue(selectedService)),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        var showStorageHelp by rememberSaveable(selectedService) { mutableStateOf(false) }
        TextButton(onClick = { showStorageHelp = !showStorageHelp }) {
            Text(stringResource(R.string.storage_how_title) + if (showStorageHelp) " ▴" else " ▾")
        }
        if (showStorageHelp) com.torve.android.ui.components.StorageModeExplainer()
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.integrations_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))


        Spacer(Modifier.height(16.dp))

        if (selectedService == "debrid") {
        // ── Debrid ──────────────────────────────────────────────────────────
        IntegrationCard(
            title = localizedUiText("Debrid"),
            modifier = repairAnchor("debrid"),
            description = localizedUiText("Stream torrents instantly via Real-Debrid, AllDebrid, Premiumize, or TorBox."),
        ) {
            when {
                state.debridConnected && state.debridDeviceCode == null -> {
                    // Connected: show account info + disconnect / re-auth
                    val providerLabel = when (state.debridProvider) {
                        DebridServiceType.REAL_DEBRID -> "Real-Debrid"
                        DebridServiceType.ALL_DEBRID -> "AllDebrid"
                        DebridServiceType.PREMIUMIZE -> "Premiumize"
                        DebridServiceType.TORBOX -> "TorBox"
                    }
                    val accountLabel = state.debridUser?.username?.let { "$providerLabel — $it" } ?: "$providerLabel connected"
                    Text(
                        text = accountLabel,
                        color = Emerald,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    state.debridUser?.expiresAt?.let { exp ->
                        Spacer(Modifier.height(2.dp))
                        Text(localizedUiText("Premium until $exp"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.disconnectDebrid() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ruby),
                            modifier = Modifier.weight(1f),
                        ) { Text(localizedUiText("Disconnect")) }
                        Button(
                            onClick = { viewModel.startDebridDeviceAuth() },
                            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                            modifier = Modifier.weight(1f),
                        ) { Text(localizedUiText("Re-authenticate")) }
                    }
                }
                state.debridDeviceCode != null -> {
                    // Device auth in progress
                    val code = state.debridDeviceCode!!
                    Text(
                        localizedUiText("Visit the link below and enter the code to connect:"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    DeviceCodeSection(userCode = code.userCode, verificationUrl = code.verificationUrl)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.disconnectDebrid() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    ) { Text(localizedUiText("Cancel")) }
                }
                else -> {
                    // Not connected: provider picker + connect button
                    DebridServiceType.entries.forEach { provider ->
                        val label = when (provider) {
                            DebridServiceType.REAL_DEBRID -> "Real-Debrid"
                            DebridServiceType.ALL_DEBRID -> "AllDebrid"
                            DebridServiceType.PREMIUMIZE -> "Premiumize"
                            DebridServiceType.TORBOX -> "TorBox"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setDebridProvider(provider) }
                                .padding(vertical = 2.dp),
                        ) {
                            RadioButton(
                                selected = state.debridProvider == provider,
                                onClick = { viewModel.setDebridProvider(provider) },
                                colors = RadioButtonDefaults.colors(selectedColor = Amber),
                            )
                            Text(localizedUiText(label), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    state.debridError?.let { err ->
                        Spacer(Modifier.height(4.dp))
                        Text(err, color = Ruby, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.startDebridDeviceAuth() },
                        enabled = !state.debridLoading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) {
                        if (state.debridLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Obsidian)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(localizedUiText("Connect via device code"))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        }
        if (selectedService == "omdb") {
        // ── OMDB ──
        IntegrationCard(
            title = localizedUiText("OMDB"),
            description = stringResource(R.string.integrations_omdb_desc),
        ) {
            IntegrationTextField(
                label = stringResource(R.string.settings_api_key),
                value = state.omdbApiKey,
                onValueChange = { viewModel.updateOmdbApiKeyInput(it) },
                isSensitive = true,
            )
            Spacer(Modifier.height(4.dp))
            com.torve.android.ui.components.StorageModeSelector(
                selected = omdbStorageMode,
                onModeSelected = { omdbStorageMode = it },
                isSignedIn = authUser != null,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        viewModel.saveAndValidateOmdbApiKey()
                        if (omdbStorageMode == com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT) {
                            scope.launch {
                                accountSessionCoordinator.saveIntegrationToBackend(
                                    integrationType = "OMDB_API_KEY",
                                    credentials = mapOf("api_key" to state.omdbApiKey),
                                    displayIdentifier = "OMDB",
                                )
                            }
                        }
                    },
                    enabled = !state.omdbValidating && state.omdbApiKey.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                ) {
                    if (state.omdbValidating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Obsidian, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (state.omdbValidating) stringResource(R.string.integrations_testing) else stringResource(R.string.integrations_test_connection))
                }
                state.omdbValidationResult?.let { result ->
                    when (result) {
                        "valid" -> Text(stringResource(R.string.integrations_status_connected), color = Emerald, style = MaterialTheme.typography.bodySmall)
                        "invalid" -> Text(stringResource(R.string.integrations_key_invalid), color = Ruby, style = MaterialTheme.typography.bodySmall)
                        else -> Text(result, color = Ruby, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.integrations_omdb_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))

        }
        if (selectedService == "trakt") {
        // ── Trakt ──
        IntegrationCard(
            title = localizedUiText("Trakt"),
            modifier = repairAnchor("trakt"),
            description = stringResource(R.string.integrations_trakt_desc),
        ) {
            Text(
                if (state.traktConnected) stringResource(R.string.integrations_status_connected) else stringResource(R.string.integrations_status_not_connected),
                color = if (state.traktConnected) Snow else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            state.traktLastSyncTime?.let { lastSync ->
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.integrations_last_sync, java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(lastSync))),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.traktDeviceCode?.let { code ->
                Spacer(Modifier.height(8.dp))
                DeviceCodeSection(
                    userCode = code.userCode,
                    verificationUrl = code.verificationUrl,
                )
                if (state.isPollingTrakt) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Amber,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.settings_waiting_auth),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                if (!state.traktConnected) {
                    Button(
                        onClick = { viewModel.startTraktDeviceAuth() },
                        enabled = !state.traktLoading && !state.isPollingTrakt,
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) { Text(if (state.traktLoading) stringResource(R.string.integrations_connecting) else stringResource(R.string.common_connect)) }
                } else {
                    Button(
                        onClick = { viewModel.syncTraktNow() },
                        enabled = !state.traktSyncing,
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) {
                        if (state.traktSyncing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Obsidian,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.integrations_syncing))
                        } else if (state.traktSyncSuccess) {
                            Text(stringResource(R.string.integrations_sync_complete))
                        } else {
                            Text(stringResource(R.string.integrations_sync_now))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { viewModel.disconnectTrakt() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    ) { Text(stringResource(R.string.common_disconnect)) }
                }
            }
            state.traktError?.let {
                Spacer(Modifier.height(6.dp))
                Text(localizedUiText(it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        }
        if (selectedService == "simkl") {
        // ── SIMKL ──
        IntegrationCard(
            title = localizedUiText("SIMKL"),
            modifier = repairAnchor("simkl"),
            description = stringResource(R.string.integrations_simkl_desc),
        ) {
            val simklUser = state.simklUser
            val simklStatus = when {
                simklUser != null -> stringResource(R.string.integrations_status_connected) + " (${simklUser.username})"
                state.simklConnected -> "Token found - reconnect if this does not recover"
                else -> stringResource(R.string.integrations_status_not_connected)
            }
            Text(
                simklStatus,
                color = if (state.simklUser != null) Snow else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            state.simklDeviceCode?.let { code ->
                Spacer(Modifier.height(8.dp))
                DeviceCodeSection(
                    userCode = code.userCode,
                    verificationUrl = code.verificationUrl,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                if (!state.simklConnected) {
                    Button(
                        onClick = { viewModel.startSimklDeviceAuth() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) { Text(if (state.simklLoading) stringResource(R.string.integrations_connecting) else stringResource(R.string.common_connect)) }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.disconnectSimkl() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    ) { Text(stringResource(R.string.common_disconnect)) }
                }
            }
            state.simklError?.let {
                Spacer(Modifier.height(6.dp))
                Text(localizedUiText(it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        }
        if (selectedService == "seerr") {
        // ── Jellyfin ──
        IntegrationCard(
            title = stringResource(R.string.integrations_seerr_title),
            modifier = repairAnchor("seerr"),
            description = stringResource(R.string.integrations_seerr_desc),
        ) {
            IntegrationTextField(
                label = stringResource(R.string.integrations_server_url),
                value = state.seerrServerUrl,
                onValueChange = viewModel::setSeerrServerUrl,
            )
            Spacer(Modifier.height(8.dp))
            IntegrationTextField(
                label = stringResource(R.string.settings_api_key),
                value = state.seerrApiKey,
                onValueChange = viewModel::updateSeerrApiKeyInput,
                isSensitive = true,
            )
            Spacer(Modifier.height(4.dp))
            com.torve.android.ui.components.StorageModeSelector(
                selected = seerrStorageMode,
                onModeSelected = { seerrStorageMode = it },
                isSignedIn = authUser != null,
            )
            Spacer(Modifier.height(8.dp))
            if (!state.seerrConnected) {
                Button(
                    onClick = { viewModel.saveAndTestSeerrConnection(seerrStorageMode) },
                    enabled = !state.seerrLoading && state.seerrServerUrl.isNotBlank() && state.seerrApiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                ) {
                    if (state.seerrLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Obsidian,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.integrations_save_test))
                }
            } else {
                OutlinedButton(
                    onClick = viewModel::disconnectSeerr,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Text(stringResource(R.string.common_disconnect))
                }
            }
            state.seerrStatusMessage?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    localizedUiText(message),
                    color = if (state.seerrConnected) Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        }
        if (selectedService == "automation") {
        IntegrationCard(
            title = stringResource(R.string.integrations_automation_title),
            modifier = repairAnchor("automation"),
            description = stringResource(R.string.integrations_automation_desc),
        ) {
            Button(
                onClick = onAutomationAdminClick,
                enabled = automationState.instances.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
            ) {
                Text(localizedUiText("Open administration dashboard"))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.integrations_automation_admin_warning),
                color = Amber,
                style = MaterialTheme.typography.bodySmall,
            )
            automationState.instances.forEach { instance ->
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(instance.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        val supportsQualityRole = instance.serviceType == AutomationServiceType.SONARR ||
                            instance.serviceType == AutomationServiceType.RADARR
                        Text(
                            localizedUiText(buildString {
                                append(instance.serviceType.name.lowercase().replaceFirstChar { it.uppercase() })
                                if (supportsQualityRole) {
                                    append(" · ")
                                    append(if (instance.role == AutomationInstanceRole.UHD) "4K" else "Standard")
                                    if (instance.isDefault) append(" · Default")
                                }
                            }),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { automationViewModel.edit(instance) }) {
                        Text(stringResource(R.string.common_edit_cd))
                    }
                    TextButton(onClick = { automationViewModel.remove(instance) }) {
                        Text(stringResource(R.string.common_remove), color = Ruby)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.integrations_automation_service), color = MaterialTheme.colorScheme.onSurfaceVariant)
            AutomationServiceType.entries.forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !automationState.isBusy) {
                            automationViewModel.selectService(type)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = automationState.serviceType == type,
                        onClick = { automationViewModel.selectService(type) },
                        colors = RadioButtonDefaults.colors(selectedColor = Amber),
                    )
                    Text(type.name.lowercase().replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            IntegrationTextField(
                label = stringResource(R.string.integrations_automation_name),
                value = automationState.name,
                onValueChange = automationViewModel::updateName,
            )
            Spacer(Modifier.height(8.dp))
            IntegrationTextField(
                label = stringResource(R.string.integrations_server_url),
                value = automationState.serverUrl,
                onValueChange = automationViewModel::updateServerUrl,
            )
            Spacer(Modifier.height(8.dp))
            if (automationState.serviceType == AutomationServiceType.TDARR) {
                Text(
                    stringResource(R.string.integrations_tdarr_read_only_probe),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                IntegrationTextField(
                    label = stringResource(R.string.settings_api_key),
                    value = automationState.apiKey,
                    onValueChange = automationViewModel::updateApiKey,
                    isSensitive = true,
                )
            }
            val supportsQualityRole = automationState.serviceType == AutomationServiceType.SONARR ||
                automationState.serviceType == AutomationServiceType.RADARR
            if (supportsQualityRole) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.integrations_automation_role), color = MaterialTheme.colorScheme.onSurfaceVariant)
                AutomationInstanceRole.entries.forEach { role ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !automationState.isBusy) {
                                automationViewModel.selectRole(role)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = automationState.role == role,
                            onClick = { automationViewModel.selectRole(role) },
                            colors = RadioButtonDefaults.colors(selectedColor = Amber),
                        )
                        Text(
                            if (role == AutomationInstanceRole.UHD) {
                                stringResource(R.string.integrations_automation_uhd)
                            } else {
                                stringResource(R.string.integrations_automation_standard)
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !automationState.isBusy) {
                            automationViewModel.setDefault(!automationState.isDefault)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = automationState.isDefault,
                        onClick = { automationViewModel.setDefault(!automationState.isDefault) },
                        colors = RadioButtonDefaults.colors(selectedColor = Amber),
                    )
                    Text(stringResource(R.string.integrations_automation_default), color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = automationViewModel::saveAndTest,
                    enabled = !automationState.isBusy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                ) {
                    if (automationState.isBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Obsidian, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.integrations_save_test))
                }
                if (automationState.editingId != null) {
                    OutlinedButton(onClick = automationViewModel::cancelEdit) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
            automationState.message?.let {
                Spacer(Modifier.height(6.dp))
                Text(localizedUiText(it), color = Emerald, style = MaterialTheme.typography.bodySmall)
            }
            automationState.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(localizedUiText(it), color = Ruby, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        }
        if (selectedService == "jellyfin") {
        IntegrationCard(
            title = localizedUiText("Jellyfin"),
            modifier = repairAnchor("jellyfin"),
            description = stringResource(R.string.integrations_jellyfin_desc),
        ) {
            IntegrationTextField(
                label = stringResource(R.string.integrations_server_url),
                value = state.jellyfinServerUrl,
                onValueChange = viewModel::setJellyfinServerUrl,
            )
            Spacer(Modifier.height(8.dp))
            IntegrationTextField(
                label = stringResource(R.string.settings_api_key),
                value = state.jellyfinApiKey,
                onValueChange = { viewModel.updateJellyfinApiKeyInput(it) },
                isSensitive = true,
            )
            Spacer(Modifier.height(4.dp))
            com.torve.android.ui.components.StorageModeSelector(
                selected = jellyfinStorageMode,
                onModeSelected = { jellyfinStorageMode = it },
                isSignedIn = authUser != null,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    viewModel.saveAndTestJellyfinConnection(jellyfinStorageMode)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text(stringResource(R.string.integrations_test_connection))
            }
            state.jellyfinStatusMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(localizedUiText(it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }

            // Profile selector (visible after successful connection)
            if (state.jellyfinProfiles.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.integrations_user_profile), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                state.jellyfinProfiles.forEach { profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectJellyfinProfile(profile.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.selectedJellyfinUserId == profile.id,
                            onClick = { viewModel.selectJellyfinProfile(profile.id) },
                            colors = RadioButtonDefaults.colors(selectedColor = Amber, unselectedColor = Silver),
                        )
                        Text(
                            text = profile.name + if (profile.isAdmin) " (" + stringResource(R.string.integrations_admin) + ")" else "",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        }
        if (selectedService == "plex") {
        // ── Plex ──
        IntegrationCard(
            title = localizedUiText("Plex"),
            modifier = repairAnchor("plex"),
            description = stringResource(R.string.integrations_plex_desc),
        ) {
            IntegrationTextField(
                label = stringResource(R.string.integrations_plex_url_hint),
                value = state.plexServerUrl,
                onValueChange = viewModel::setPlexServerUrl,
            )
            Spacer(Modifier.height(8.dp))
            IntegrationTextField(
                label = stringResource(R.string.integrations_access_token),
                value = state.plexAccessToken,
                onValueChange = { viewModel.updatePlexAccessTokenInput(it) },
                isSensitive = true,
            )
            Spacer(Modifier.height(4.dp))
            com.torve.android.ui.components.StorageModeSelector(
                selected = plexStorageMode,
                onModeSelected = { plexStorageMode = it },
                isSignedIn = authUser != null,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.integrations_plex_token_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.saveAndConnectPlex()
                    if (plexStorageMode == com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT) {
                        scope.launch {
                            accountSessionCoordinator.saveIntegrationToBackend(
                                integrationType = "PLEX_ACCESS_TOKEN",
                                credentials = mapOf("access_token" to state.plexAccessToken),
                                displayIdentifier = "Plex",
                                config = mapOf("server_url" to state.plexServerUrl),
                            )
                        }
                    }
                },
                enabled = state.plexAccessToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
            ) {
                Text(stringResource(R.string.integrations_save_connect))
            }
            Spacer(Modifier.height(8.dp))

            if (state.plexConnected) {
                Text(
                    stringResource(R.string.integrations_status_connected),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
            }

            state.plexError?.let {
                Text(localizedUiText(it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }

            Row {
                if (!state.plexConnected) {
                    Button(
                        onClick = { viewModel.testPlexConnection() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) { Text(if (state.plexLoading) stringResource(R.string.integrations_testing) else stringResource(R.string.common_connect)) }
                } else {
                    Button(
                        onClick = { viewModel.testPlexConnection() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) { Text(stringResource(R.string.integrations_test_connection)) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { viewModel.disconnectPlex() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    ) { Text(stringResource(R.string.common_disconnect)) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        }
        if (selectedService == "nzbdav") {
        // ── NzbDAV ──
        // Backend-managed integration. The app never talks to the user's
        // NzbDAV instance directly — base URL + API key are forwarded to
        // the Torve backend which owns the connection.
        NzbdavSetupSection()        }

        if (selectedService == "opensubtitles") {
            var apiKey by remember { mutableStateOf("") }
            IntegrationCard(title = localizedUiText("OpenSubtitles"), description = localizedUiText("Subtitle service API key")) {
                Text(localizedUiText(if (state.opensubtitlesApiKey.isBlank()) "Not configured" else "Credentials saved"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                IntegrationTextField(localizedUiText("New API key"), apiKey, { apiKey = it }, isSensitive = true)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.setOpenSubtitlesApiKey(apiKey); apiKey = "" }, enabled = apiKey.isNotBlank()) { Text(stringResource(R.string.common_save)) }
            }
        }
        Spacer(Modifier.height(110.dp))

    }
}

@Composable
private fun NzbdavSetupSection(
    vm: NzbdavSetupViewModel = koinInject(),
) {
    val state by vm.state.collectAsState()
    IntegrationCard(
        title = stringResource(R.string.integrations_nzbdav_title),
        description = stringResource(R.string.integrations_nzbdav_desc),
    ) {
        // ── Status line ──
        NzbdavStatusLine(state.status)

        Spacer(Modifier.height(10.dp))

        IntegrationTextField(
            label = stringResource(R.string.integrations_nzbdav_base_url),
            value = state.baseUrl,
            onValueChange = { vm.updateBaseUrl(it) },
            isSensitive = false,
        )
        Spacer(Modifier.height(6.dp))
        IntegrationTextField(
            label = stringResource(R.string.integrations_nzbdav_api_key),
            value = state.apiKey,
            onValueChange = { vm.updateApiKey(it) },
            isSensitive = true,
        )

        // Inline transient action feedback. Neutral copy only — backend
        // tokens / exception bodies never reach this surface (enforced
        // by NzbdavSetupViewModel mapping + tests).
        state.lastTestResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            NzbdavLastActionLine(result)
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { vm.test() },
                enabled = !state.isTesting && !state.isSaving && !state.isRemoving,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Obsidian,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.integrations_nzbdav_test))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.save() },
                enabled = !state.isSaving && !state.isTesting && !state.isRemoving,
                colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Obsidian),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Obsidian,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.integrations_nzbdav_save))
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { vm.remove() },
                enabled = state.status !is NzbdavStatus.NotConfigured &&
                    !state.isRemoving && !state.isSaving && !state.isTesting,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                if (state.isRemoving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.integrations_nzbdav_remove))
            }
        }
    }
}

@Composable
private fun NzbdavStatusLine(status: NzbdavStatus) {
    val (text, color) = when (status) {
        NzbdavStatus.Loading -> stringResource(R.string.integrations_nzbdav_status_loading) to Silver
        NzbdavStatus.NotConfigured -> stringResource(R.string.integrations_nzbdav_status_not_configured) to Silver
        is NzbdavStatus.Connected ->
            if (status.degraded) stringResource(R.string.integrations_nzbdav_status_connected_degraded) to Amber
            else stringResource(R.string.integrations_nzbdav_status_connected) to Emerald
        NzbdavStatus.ConnectionFailed ->
            stringResource(R.string.integrations_nzbdav_status_failed) to Ruby
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun NzbdavLastActionLine(result: NzbdavTestResult) {
    val (text, color) = when (result) {
        NzbdavTestResult.Ok -> stringResource(R.string.integrations_nzbdav_action_test_ok) to Emerald
        NzbdavTestResult.DegradedOk -> stringResource(R.string.integrations_nzbdav_action_test_degraded) to Amber
        NzbdavTestResult.Failed -> stringResource(R.string.integrations_nzbdav_action_test_failed) to Ruby
        NzbdavTestResult.MissingFields -> stringResource(R.string.integrations_nzbdav_action_missing_fields) to Ruby
        NzbdavTestResult.Saved -> stringResource(R.string.integrations_nzbdav_action_saved) to Emerald
        NzbdavTestResult.Removed -> stringResource(R.string.integrations_nzbdav_action_removed) to Silver
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Reusable device-code auth section: clickable URL, copyable code, open-in-browser button. */
@Composable
private fun DeviceCodeSection(
    userCode: String,
    verificationUrl: String,
) {
    val context = LocalContext.current

    // Clickable verification URL
    Text(
        text = verificationUrl,
        color = Amber,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl)))
        },
    )

    Spacer(Modifier.height(8.dp))

    // Code with copy button
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(stringResource(R.string.integrations_your_code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = userCode,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                letterSpacing = MaterialTheme.typography.titleLarge.letterSpacing,
            )
        }
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Device Code", userCode))
                Toast.makeText(context, context.getString(R.string.integrations_code_copied), Toast.LENGTH_SHORT).show()
            },
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.diagnostics_copy),
                tint = Amber,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    // Open in browser button
    Button(
        onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl)))
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
    ) {
        Icon(
            Icons.Default.OpenInBrowser,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.integrations_open_browser))
    }
}

@Composable
private fun IntegrationReadinessCard(summary: IntegrationReadinessSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = localizedUiText("Your media setup"),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = localizedUiText("See what is ready before opening individual connection settings."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            summary.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = item.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = item.statusLabel,
                        color = when (item.status) {
                            IntegrationReadinessStatus.READY -> Emerald
                            IntegrationReadinessStatus.NEEDS_ATTENTION -> Amber
                            IntegrationReadinessStatus.NOT_CONFIGURED -> Silver
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntegrationCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (content != null) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

@Composable
private fun IntegrationTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isSensitive: Boolean = false,
) {
    var revealed by remember { mutableStateOf(false) }
    val peekTransformation = com.torve.android.ui.components.rememberPeekPasswordTransformation(value)
    Column {
        Text(localizedUiText(label), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(Amber),
            visualTransformation = if (isSensitive && !revealed) peekTransformation else VisualTransformation.None,
            keyboardOptions = if (isSensitive) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(start = 12.dp, end = if (isSensitive) 4.dp else 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isBlank()) {
                            Text(localizedUiText(label), color = Torve.colors.textHint, style = MaterialTheme.typography.bodySmall)
                        }
                        inner()
                    }
                    if (isSensitive && value.isNotEmpty()) {
                        IconButton(onClick = { revealed = !revealed }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = localizedUiText(if (revealed) "Hide" else "Show"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            },
        )
    }
}
