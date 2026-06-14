@file:OptIn(ExperimentalLayoutApi::class)

package com.torve.desktop.ui.panda

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorveListRow
import com.torve.desktop.ui.components.TorvePageHeader
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSecondaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.presentation.panda.PandaSetupMode
import com.torve.presentation.panda.PandaSetupStep
import com.torve.presentation.panda.PandaSetupUiState
import com.torve.presentation.panda.PandaSetupViewModel
import com.torve.presentation.panda.progressStepCount
import com.torve.presentation.panda.progressStepNumber
import java.awt.Desktop
import java.net.URI

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopPandaSetupScreen(
    viewModel: PandaSetupViewModel,
    onBack: () -> Unit,
    onComplete: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    // Close wizard automatically once the addon is installed and the user clicks done
    LaunchedEffect(state.addonInstalled) {
        // surfaced via review step - no-op here
    }

    val stepNumber = state.progressStepNumber()
    val totalSteps = state.progressStepCount()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Match every other page-level surface in V2App: clear the 72dp
            // nav rail on the left so content never sits under the icons.
            .padding(start = 72.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Header + persistent Close button. The Close button gives users
        // a guaranteed exit regardless of which step they're on; without
        // it the only way out was hitting Update on the Review step,
        // which leaves users stuck if they decided not to save.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TorvePageHeader(
                    title = ds("Panda guided setup"),
                    subtitle = if (state.isEditMode) {
                        ds("Reconfiguring existing Panda setup")
                    } else {
                        ds("Choose Debrid or Usenet only, then Panda installs itself as a Torve add-on.")
                    },
                )
            }
            TorveGhostButton(text = ds("Close"), onClick = onBack)
        }

        LinearProgressIndicator(
            progress = { stepNumber.toFloat() / totalSteps },
            modifier = Modifier.fillMaxWidth(),
        )

        // Recovery banner removed (2026-04-27) - Panda configs are now
        // bound to the Torve account on the backend (Panda commit 972fa4a),
        // so management_token recovery is no longer needed for the owner's
        // own configs. Edit operations authenticate via the Torve JWT plus
        // the X-Panda-Config-Id header - see PandaApiClient.

        when (state.currentStep) {
            PandaSetupStep.SETUP_TYPE -> SetupTypeStep(state, viewModel)
            PandaSetupStep.PROVIDER -> ProviderStep(state, viewModel)
            PandaSetupStep.AUTH -> AuthStep(state, viewModel)
            PandaSetupStep.SOURCES -> SourcesStep(state, viewModel)
            PandaSetupStep.USENET -> UsenetStep(state, viewModel)
            PandaSetupStep.QUALITY -> QualityStep(state, viewModel)
            PandaSetupStep.REVIEW -> ReviewStep(state, viewModel, onComplete)
        }

        state.error?.let { message ->
            TorveBanner(title = ds("Error"), description = message, tone = TorveBannerTone.Error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.currentStep == PandaSetupStep.SETUP_TYPE) {
                TorveGhostButton(text = ds("Close"), onClick = onBack)
            } else {
                TorveGhostButton(text = ds("Back"), onClick = { viewModel.previousStep() })
            }

            val canAdvance = when (state.currentStep) {
                PandaSetupStep.SETUP_TYPE,
                PandaSetupStep.PROVIDER -> false
                PandaSetupStep.AUTH -> state.authConnected
                PandaSetupStep.SOURCES, PandaSetupStep.USENET, PandaSetupStep.QUALITY -> true
                PandaSetupStep.REVIEW -> false
            }
            if (canAdvance) {
                TorvePrimaryButton(text = ds("Next"), onClick = { viewModel.nextStep() })
            }
        }
    }
}

@Composable
private fun SetupTypeStep(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    TorveSectionCard(
        title = ds("Choose setup type"),
        supportingText = ds("Start with debrid cloud streaming or skip straight to Usenet configuration."),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SetupTypeRow(
                title = ds("Debrid"),
                subtitle = ds("Use Real-Debrid, AllDebrid, Premiumize, TorBox, or another supported Panda debrid service."),
                badge = "D",
                selected = state.setupMode == PandaSetupMode.DEBRID,
                onClick = { viewModel.selectSetupMode(PandaSetupMode.DEBRID) },
            )
            SetupTypeRow(
                title = ds("Usenet only"),
                subtitle = ds("Skip debrid and configure Usenet, indexers, and a download client."),
                badge = "U",
                selected = state.setupMode == PandaSetupMode.USENET_ONLY,
                onClick = { viewModel.selectSetupMode(PandaSetupMode.USENET_ONLY) },
            )
        }
    }
}

@Composable
private fun SetupTypeRow(
    title: String,
    subtitle: String,
    badge: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accentContainer else colors.fieldSurface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.accentContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badge,
                fontWeight = FontWeight.Bold,
                color = colors.accent,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        if (selected) {
            TorveBadge(ds("Selected"), tone = TorveBadgeTone.Success)
        }
    }
}

@Composable
private fun ProviderStep(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    TorveSectionCard(
        title = ds("Choose debrid provider"),
        supportingText = ds("Panda will route streams through your selected debrid service."),
    ) {
        if (state.providersLoading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.providers.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ds("Could not load providers."), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                TorveSecondaryButton(text = ds("Retry"), onClick = { viewModel.retryLoadProviders() })
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.providers.filter { it.id != "none" }.forEach { provider ->
                    val selected = state.selectedProvider?.id == provider.id
                    val colors = TorveDesktopThemeTokens.colors
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) colors.accentContainer else colors.fieldSurface)
                            .clickable { viewModel.selectProvider(provider) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(colors.accentContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                provider.name.take(1).uppercase(),
                                fontWeight = FontWeight.Bold,
                                color = colors.accent,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                provider.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val browserSignInLabel = ds("Browser sign-in")
                            val apiKeyLabel = ds("API key")
                            val subtitle = provider.authMethods.joinToString(" / ") { method ->
                                when (method) {
                                    "oauth" -> browserSignInLabel
                                    "apikey" -> apiKeyLabel
                                    else -> method
                                }
                            }
                            if (subtitle.isNotBlank()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                        if (selected) {
                            TorveBadge(ds("Selected"), tone = TorveBadgeTone.Success)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthStep(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    val provider = state.selectedProvider ?: state.providers.firstOrNull { it.id != "none" } ?: return
    val debridProviders = state.providers.filter { it.id != "none" }
    val supportsOAuth = "oauth" in provider.authMethods
    val providerConnected = state.debridApiKeys[provider.id]?.isNotBlank() == true ||
        (state.selectedProvider?.id == provider.id && state.debridApiKey.isNotBlank())

    TorveSectionCard(
        title = ds("Debrid connections"),
        supportingText = ds("Add every debrid account you want to use. The selected provider remains the primary fallback."),
        trailing = {
            if (state.debridApiKeys.isNotEmpty()) {
                TorveBadge("${state.debridApiKeys.size} ${ds("connected")}", tone = TorveBadgeTone.Success)
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                debridProviders.forEach { item ->
                    val selected = provider.id == item.id
                    val connected = state.debridApiKeys[item.id]?.isNotBlank() == true
                    val colors = TorveDesktopThemeTokens.colors
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) colors.accentContainer else colors.fieldSurface)
                            .clickable { viewModel.selectProvider(item) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (connected) ds("API key saved") else ds("Not connected"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                        }
                        if (connected) {
                            TorveBadge(ds("Ready"), tone = TorveBadgeTone.Success)
                        }
                    }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (providerConnected) {
                        TorveBadge(ds("Connected"), tone = TorveBadgeTone.Success)
                    }
                }

                if (providerConnected) {
                    TorveBanner(
                        title = ds("Credential saved"),
                        description = ds("This provider is available to Panda and Torve clients. Verify again to replace the key."),
                        tone = TorveBannerTone.Success,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TorveSecondaryButton(text = ds("Re-authenticate"), onClick = { viewModel.reconnectSelectedDebrid() })
                        TorveGhostButton(text = ds("Disconnect"), onClick = { viewModel.disconnectSelectedDebrid() })
                    }
                }

                if (supportsOAuth) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TorveFilterChip(
                            text = ds("Browser sign-in"),
                            selected = state.authMethod == "oauth",
                            onClick = { viewModel.setAuthMethod("oauth") },
                        )
                        TorveFilterChip(
                            text = ds("API key"),
                            selected = state.authMethod == "apikey",
                            onClick = { viewModel.setAuthMethod("apikey") },
                        )
                    }
                }

                if (state.authMethod == "oauth" && supportsOAuth) {
                    OAuthSection(state, viewModel)
                } else {
                    ApiKeySection(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun OAuthSection(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    LaunchedEffect(state.selectedProvider?.id) {
        val providerId = state.selectedProvider?.id
        val providerConnected = providerId != null && state.debridApiKeys[providerId]?.isNotBlank() == true
        if (state.deviceCode == null && !state.authLoading && !providerConnected) {
            viewModel.startOAuth()
        }
    }

    val code = state.deviceCode
    if (state.authLoading && code == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (code != null) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(ds("Enter this code in your browser:"), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                code.userCode,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            // Three-button row so the user can:
            //   * "Open in browser" (primary path — works on most desktops)
            //   * "Copy code" if "Open in browser" landed on the wrong tab /
            //     a different browser profile / a Sandbox without Default
            //     browser registration.
            //   * "Copy link" if the user wants to paste the verification
            //     URL into a different browser entirely.
            // Same belt-and-suspenders pattern Trakt's device-code step
            // already uses elsewhere in onboarding.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TorvePrimaryButton(
                    text = ds("Open in browser"),
                    onClick = { openUrl(code.verificationUrl) },
                )
                TorveSecondaryButton(
                    text = ds("Copy code"),
                    onClick = { copyTextToClipboard(code.userCode) },
                )
                TorveSecondaryButton(
                    text = ds("Copy link"),
                    onClick = { copyTextToClipboard(code.verificationUrl) },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(ds("Waiting for authorization..."), style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        TorveSecondaryButton(text = ds("Retry"), onClick = { viewModel.startOAuth() })
    }
}

@Composable
private fun ApiKeySection(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PandaSecretField(
            value = state.apiKeyInput,
            onValueChange = { viewModel.setApiKeyInput(it) },
            label = ds("API key"),
            modifier = Modifier.fillMaxWidth(),
        )
        TorvePrimaryButton(
            text = if (state.authLoading) ds("Validating...") else ds("Validate & connect"),
            onClick = { viewModel.validateApiKey() },
            enabled = state.apiKeyInput.isNotBlank() && !state.authLoading,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesStep(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TorveSectionCard(
            title = ds("Torrent sources"),
            supportingText = ds("Panda will search these indexers. Toggle any you want disabled."),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.sourceProviders.forEach { src ->
                    TorveFilterChip(
                        text =src.name,
                        selected = src.id in state.enabledSources,
                        onClick = { viewModel.toggleSource(src.id) },
                    )
                }
            }
        }
        // Surface the language picker here too - the website shows it
        // alongside debrid/quality at the top, but the desktop wizard
        // splits each section across steps. Putting it on Sources gives
        // users a chance to pick languages without having to walk all
        // the way to the Quality step.
        TorveSectionCard(
            title = ds("Preferred release languages"),
            supportingText = ds("Pick all languages you want in results. \"any\" disables the filter."),
        ) {
            ReleaseLanguageChips(state = state, viewModel = viewModel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseLanguageChips(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    val selectedLanguages = state.releaseLanguages.toSet()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.schema.releaseLanguages.forEach { id ->
            val isSelected = id in selectedLanguages
            TorveFilterChip(
                text = desktopLabelForLanguage(id),
                selected = isSelected,
                onClick = { viewModel.toggleLanguage(id, !isSelected) },
            )
        }
    }
}

@Composable
private fun UsenetStep(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    TorveSectionCard(
        title = ds("Usenet (optional)"),
        supportingText = ds("Connect a Usenet provider, indexer, and download client if you want NZB support."),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ds("Enable Usenet"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = state.enableUsenet, onCheckedChange = { viewModel.setEnableUsenet(it) })
        }

        if (state.enableUsenet) {
            Spacer(Modifier.height(12.dp))
            Text(ds("Provider"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.schema.usenetProviders.filter { it != "none" }.forEach { id ->
                    TorveFilterChip(
                        text = desktopLabelForUsenetProvider(id),
                        selected = state.usenetProvider == id,
                        onClick = { viewModel.setUsenetProvider(id) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            if (state.usenetProvider == "generic") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorveTextField(
                        value = state.usenetHost,
                        onValueChange = { viewModel.setUsenetHost(it) },
                        label = ds("Host"),
                        modifier = Modifier.weight(2f),
                    )
                    TorveTextField(
                        value = state.usenetPort.toString(),
                        onValueChange = { it.toIntOrNull()?.let(viewModel::setUsenetPort) },
                        label = ds("Port"),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            TorveTextField(
                value = state.usenetUsername,
                onValueChange = { viewModel.setUsenetUsername(it) },
                label = ds("Username"),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            PandaSecretField(
                value = state.usenetPassword,
                onValueChange = { viewModel.setUsenetPassword(it) },
                label = ds("Password"),
                modifier = Modifier.fillMaxWidth(),
                placeholder = if ("usenet_password" in state.serverHasSecrets) {
                    ds("Saved on server - type to replace")
                } else null,
            )
            if (state.usenetProvider == "generic") {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ds("SSL"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = state.usenetSSL, onCheckedChange = { viewModel.setUsenetSSL(it) })
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(ds("NZB indexers"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            state.nzbIndexers.forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                val keyOnServer = "indexer_api_key_$index" in state.serverHasSecrets ||
                    (index == 0 && "indexer_api_key_legacy" in state.serverHasSecrets)
                NzbIndexerRowEditor(
                    row = row,
                    indexerOptions = state.schema.nzbIndexers,
                    onTypeChange = { newType ->
                        viewModel.updateIndexer(index) { it.copy(type = newType) }
                    },
                    onUrlChange = { newUrl ->
                        viewModel.updateIndexer(index) { it.copy(url = newUrl) }
                    },
                    onKeyChange = { newKey ->
                        viewModel.updateIndexer(index) { it.copy(apiKey = newKey) }
                    },
                    onRemove = { viewModel.removeIndexer(index) },
                    canRemove = state.nzbIndexers.size > 1,
                    indexerKeyPlaceholder = if (keyOnServer) ds("Saved on server - type to replace") else null,
                )
            }
            Spacer(Modifier.height(8.dp))
            TorveSecondaryButton(
                text = ds("+ Add another indexer"),
                onClick = { viewModel.addIndexer() },
            )

            Spacer(Modifier.height(16.dp))
            Text(ds("Download client"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.schema.downloadClients.forEach { id ->
                    TorveFilterChip(
                        text = desktopLabelForDownloadClient(id),
                        selected = state.downloadClient == id,
                        onClick = { viewModel.setDownloadClient(id) },
                    )
                }
            }
            DownloadClientFields(state, viewModel)

            if (state.usenetProvider == "easynews") {
                Spacer(Modifier.height(16.dp))
                BandwidthSaverSection(state, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NzbIndexerRowEditor(
    row: com.torve.data.panda.NzbIndexerRow,
    indexerOptions: List<String>,
    onTypeChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
    indexerKeyPlaceholder: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TorveDesktopThemeTokens.colors.fieldSurface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                indexerOptions.forEach { id ->
                    TorveFilterChip(
                        text = desktopLabelForNzbIndexer(id),
                        selected = row.type == id,
                        onClick = { onTypeChange(id) },
                    )
                }
            }
            if (canRemove) {
                Spacer(Modifier.width(8.dp))
                TorveGhostButton(text = ds("Remove"), onClick = onRemove)
            }
        }
        if (row.type != "none") {
            if (row.type == "custom") {
                Spacer(Modifier.height(8.dp))
                TorveTextField(
                    value = row.url,
                    onValueChange = onUrlChange,
                    label = ds("Indexer URL"),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            PandaSecretField(
                value = row.apiKey,
                onValueChange = onKeyChange,
                label = ds("Indexer API key"),
                modifier = Modifier.fillMaxWidth(),
                placeholder = indexerKeyPlaceholder,
            )
        }
    }
}

@Composable
private fun BandwidthSaverSection(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    val cloudClients = setOf("premiumize", "torbox", "alldebrid")
    val hasIndexer = state.nzbIndexers.any { it.type != "none" && it.apiKey.isNotBlank() }
    val hasCloudClient = state.downloadClient in cloudClients
    val canEnable = state.enableUsenet && hasIndexer && hasCloudClient
    val colors = TorveDesktopThemeTokens.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fieldSurface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    ds("Bandwidth saver - use NZB path when available"),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    ds("When the same release is on both Easynews and one of your NZB indexers, route playback through your cloud download service. Saves Easynews data."),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = canEnable && state.easynewsPreferNzb,
                onCheckedChange = { viewModel.setBandwidthSaver(it) },
                enabled = canEnable,
            )
        }
        if (!canEnable) {
            Spacer(Modifier.height(8.dp))
            Text(
                ds("Configure at least one NZB indexer with an API key and a cloud download client (Premiumize / TorBox / AllDebrid) to enable."),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun DownloadClientFields(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    val spec = state.schema.downloadClientFields[state.downloadClient]
    val fields = spec?.fields.orEmpty()
    if (fields.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    fields.forEachIndexed { index, field ->
        if (index > 0) Spacer(Modifier.height(8.dp))
        when (field) {
            "url" -> TorveTextField(
                value = state.downloadClientUrl,
                onValueChange = { viewModel.setDownloadClientUrl(it) },
                label = ds("Client URL"),
                modifier = Modifier.fillMaxWidth(),
            )
            "username" -> TorveTextField(
                value = state.downloadClientUsername,
                onValueChange = { viewModel.setDownloadClientUsername(it) },
                label = ds("User"),
                modifier = Modifier.fillMaxWidth(),
            )
            "password" -> PandaSecretField(
                value = state.downloadClientPassword,
                onValueChange = { viewModel.setDownloadClientPassword(it) },
                label = ds("Password"),
                modifier = Modifier.fillMaxWidth(),
                placeholder = if ("download_client_password" in state.serverHasSecrets) {
                    ds("Saved on server - type to replace")
                } else null,
            )
            "apiKey" -> PandaSecretField(
                value = state.downloadClientApiKey,
                onValueChange = { viewModel.setDownloadClientApiKey(it) },
                label = ds("API key"),
                modifier = Modifier.fillMaxWidth(),
                placeholder = if ("download_client_api_key" in state.serverHasSecrets) {
                    ds("Saved on server - type to replace")
                } else null,
            )
        }
    }
}

private fun desktopLabelForUsenetProvider(id: String): String = when (id) {
    "easynews" -> "Easynews"
    "generic" -> "Generic NNTP"
    else -> id.replaceFirstChar { it.uppercase() }
}

@Composable
private fun desktopLabelForNzbIndexer(id: String): String = when (id) {
    "none" -> ds("None")
    "nzbgeek" -> "NZBgeek"
    "scenenzbs" -> "SceneNZBs"
    "dognzb" -> "DogNZB"
    "nzbplanet" -> "NZBPlanet"
    "custom" -> ds("Custom URL")
    else -> id.replaceFirstChar { it.uppercase() }
}

@Composable
private fun desktopLabelForDownloadClient(id: String): String = when (id) {
    "none" -> ds("None")
    "nzbget" -> "NZBget"
    "sabnzbd" -> "SABnzbd"
    "premiumize" -> "Premiumize"
    "torbox" -> "TorBox"
    "alldebrid" -> "AllDebrid"
    else -> id.replaceFirstChar { it.uppercase() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityStep(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    TorveSectionCard(title = ds("Quality defaults")) {
        Text(ds("Maximum quality"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.schema.qualityOptions.forEach { id ->
                TorveFilterChip(
                    text = desktopLabelForQuality(id),
                    selected = state.maxQuality == id,
                    onClick = { viewModel.setMaxQuality(id) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(ds("Quality profile"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.schema.qualityProfiles.forEach { id ->
                TorveFilterChip(
                    text = desktopLabelForQualityProfile(id),
                    selected = state.qualityProfile == id,
                    onClick = { viewModel.setQualityProfile(id) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(ds("Release language"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        ReleaseLanguageChips(state = state, viewModel = viewModel)
    }
}

private fun desktopLabelForQuality(id: String): String = when (id) {
    "2160p" -> "4K (2160p)"
    else -> id
}

@Composable
private fun desktopLabelForQualityProfile(id: String): String = when (id) {
    "balanced" -> ds("Balanced")
    "best_quality" -> ds("Best quality")
    "fast_start" -> ds("Fast start")
    "data_saver" -> ds("Data saver")
    else -> id.replace("_", " ").replaceFirstChar { it.uppercase() }
}

@Composable
private fun desktopLabelForLanguage(id: String): String = when (id) {
    "any" -> ds("Any")
    "english" -> ds("English")
    "german" -> "Deutsch"
    "spanish" -> "Español"
    "french" -> "Français"
    "italian" -> "Italiano"
    "portuguese" -> "Português"
    "turkish" -> "Türkçe"
    "japanese" -> "日本語"
    "korean" -> "한국어"
    "chinese" -> "中文"
    "hindi" -> "हिन्दी"
    "multi" -> "Multi"
    else -> id.replaceFirstChar { it.uppercase() }
}

@Composable
private fun ReviewStep(
    state: PandaSetupUiState,
    viewModel: PandaSetupViewModel,
    onComplete: () -> Unit,
) {
    // Watch the save-completion counter. On increment we show a "Saved"
    // banner briefly, then auto-close so the user gets back to whatever
    // page they came from (Adult page, Add-ons list, etc.).
    val baselineToken = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(state.saveCompletionToken)
    }
    val saveJustCompletedState = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val saveJustCompleted = saveJustCompletedState.value
    androidx.compose.runtime.LaunchedEffect(state.saveCompletionToken) {
        if (state.saveCompletionToken > baselineToken.value) {
            baselineToken.value = state.saveCompletionToken
            saveJustCompletedState.value = true
            kotlinx.coroutines.delay(1200)
            onComplete()
        }
    }

    TorveSectionCard(title = ds("Review & save")) {
        TorveListRow(
            title = ds("Provider"),
            subtitle = state.selectedProvider?.name ?: "-",
        )
        TorveListRow(
            title = ds("Auth"),
            subtitle = if (state.authConnected) ds("Connected") else ds("Not connected"),
        )
        TorveListRow(
            title = ds("Sources"),
            subtitle = ds("%1\$d enabled").format(state.enabledSources.size),
        )
        TorveListRow(
            title = ds("Max quality"),
            subtitle = state.maxQuality,
        )
        TorveListRow(
            title = ds("Profile"),
            subtitle = state.qualityProfile,
        )
        if (state.enableUsenet) {
            TorveListRow(
                title = ds("Usenet"),
                subtitle = state.usenetProvider,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Show a success banner whenever a save just completed - works
        // for both initial install AND edit-mode updates so the user
        // always gets confirmation. The auto-close LaunchedEffect above
        // will close the screen ~1.2s later.
        if (saveJustCompleted) {
            TorveBanner(
                title = if (state.isEditMode) ds("Panda updated") else ds("Panda installed"),
                description = ds("Saved. Closing setup..."),
                tone = TorveBannerTone.Success,
            )
            Spacer(Modifier.height(12.dp))
            TorvePrimaryButton(text = ds("Close now"), onClick = onComplete)
        } else {
            state.saveError?.let {
                TorveBanner(title = ds("Save failed"), description = it, tone = TorveBannerTone.Error)
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TorvePrimaryButton(
                    text = when {
                        state.isSaving -> ds("Saving...")
                        state.isEditMode -> ds("Update Panda")
                        else -> ds("Install Panda")
                    },
                    onClick = { viewModel.saveConfigAndInstall() },
                    enabled = !state.isSaving && state.selectedProvider != null && state.authConnected,
                )
                if (state.addonInstalled) {
                    TorveGhostButton(text = ds("Exit without saving"), onClick = onComplete)
                }
            }
        }
    }
}

private fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url))
        }
    }
}

/**
 * Masked text field with a trailing eye-toggle that flips between
 * [PasswordVisualTransformation] and [VisualTransformation.None]. Used for
 * every credential input on the Panda setup screen so users can verify what
 * they typed (or what was hydrated from the backend) without re-entering it.
 *
 * When the underlying value is blank but the server confirmed it has a
 * value (Panda returned a redacted placeholder on read), render a
 * prominent "Saved on server" badge above the field so users don't
 * mistake the blank input for a wiped credential. The placeholder
 * inside the field stays as a secondary cue.
 *
 * The reveal state lives inside the composable - each field tracks its
 * own visibility independently so toggling one doesn't expose the
 * others.
 */
@Composable
private fun PandaSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    val showSavedBadge = value.isBlank() && placeholder != null
    val colors = com.torve.desktop.ui.theme.TorveDesktopThemeTokens.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showSavedBadge) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TorveBadge(
                    text = ds("Saved on server"),
                    tone = TorveBadgeTone.Success,
                )
                Text(
                    text = ds("Type to replace; leave blank to keep the stored value."),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
        }
        InnerPandaSecretField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
        )
    }
}

@Composable
private fun InnerPandaSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    TorveTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = Modifier.fillMaxWidth(),
        placeholder = placeholder,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) ds("Hide") else ds("Show"),
                )
            }
        },
    )
}

/**
 * Best-effort clipboard write via AWT's cross-platform Toolkit
 * clipboard. No-op when the AWT bridge isn't available (headless
 * sessions, sandboxed containers); failure is silent because the
 * user still has the source value visible on screen.
 *
 * Same shape as the helpers in DesktopOnboardingShell.kt and
 * V2SettingsPage.kt.
 */
private fun copyTextToClipboard(value: String) {
    if (value.isEmpty()) return
    runCatching {
        val sel = java.awt.datatransfer.StringSelection(value)
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
    }
}
