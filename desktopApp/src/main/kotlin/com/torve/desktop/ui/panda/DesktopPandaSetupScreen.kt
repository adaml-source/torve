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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.presentation.panda.PandaSetupStep
import com.torve.presentation.panda.PandaSetupUiState
import com.torve.presentation.panda.PandaSetupViewModel
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
        // surfaced via review step — no-op here
    }

    val stepIndex = PandaSetupStep.entries.indexOf(state.currentStep)
    val totalSteps = PandaSetupStep.entries.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TorvePageHeader(
            title = "Panda guided setup",
            subtitle = if (state.isEditMode) {
                "Reconfiguring existing Panda setup"
            } else {
                "Pick a cloud provider, connect it, and Panda installs itself as a Torve add-on."
            },
        )

        LinearProgressIndicator(
            progress = { (stepIndex + 1).toFloat() / totalSteps },
            modifier = Modifier.fillMaxWidth(),
        )

        when (state.currentStep) {
            PandaSetupStep.PROVIDER -> ProviderStep(state, viewModel)
            PandaSetupStep.AUTH -> AuthStep(state, viewModel)
            PandaSetupStep.SOURCES -> SourcesStep(state, viewModel)
            PandaSetupStep.USENET -> UsenetStep(state, viewModel)
            PandaSetupStep.QUALITY -> QualityStep(state, viewModel)
            PandaSetupStep.REVIEW -> ReviewStep(state, viewModel, onComplete)
        }

        state.error?.let { message ->
            TorveBanner(title = "Error", description = message, tone = TorveBannerTone.Error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.currentStep == PandaSetupStep.PROVIDER) {
                TorveGhostButton(text = "Close", onClick = onBack)
            } else {
                TorveGhostButton(text = "Back", onClick = { viewModel.previousStep() })
            }

            val canAdvance = when (state.currentStep) {
                PandaSetupStep.PROVIDER -> state.selectedProvider != null
                PandaSetupStep.AUTH -> state.authConnected
                PandaSetupStep.SOURCES, PandaSetupStep.USENET, PandaSetupStep.QUALITY -> true
                PandaSetupStep.REVIEW -> false
            }
            if (canAdvance) {
                TorvePrimaryButton(text = "Next", onClick = { viewModel.nextStep() })
            }
        }
    }
}

@Composable
private fun ProviderStep(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    TorveSectionCard(
        title = "Choose a cloud provider",
        supportingText = "Panda will route streams through your debrid service.",
    ) {
        if (state.providersLoading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.providers.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Could not load providers.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                TorveSecondaryButton(text = "Retry", onClick = { viewModel.retryLoadProviders() })
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.providers.forEach { provider ->
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
                            val subtitle = if (provider.id == "none") {
                                "No debrid — configure Usenet on the next steps"
                            } else {
                                provider.authMethods.joinToString(" / ") { method ->
                                    when (method) {
                                        "oauth" -> "Browser sign-in"
                                        "apikey" -> "API key"
                                        else -> method
                                    }
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
                            TorveBadge("Selected", tone = TorveBadgeTone.Success)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthStep(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    val provider = state.selectedProvider ?: return
    val supportsOAuth = "oauth" in provider.authMethods

    TorveSectionCard(
        title = "Connect ${provider.name}",
        supportingText = if (state.authConnected) {
            if (state.existingCredentialDetected) "Using existing ${provider.name} credentials." else "Connected."
        } else {
            "Authorize Panda to use your ${provider.name} account."
        },
        trailing = {
            if (state.authConnected) {
                TorveBadge("Connected", tone = TorveBadgeTone.Success)
            }
        },
    ) {
        if (state.authConnected) return@TorveSectionCard

        if (supportsOAuth) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveFilterChip(
                    text ="Browser sign-in",
                    selected = state.authMethod == "oauth",
                    onClick = { viewModel.setAuthMethod("oauth") },
                )
                TorveFilterChip(
                    text ="API key",
                    selected = state.authMethod == "apikey",
                    onClick = { viewModel.setAuthMethod("apikey") },
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (state.authMethod == "oauth" && supportsOAuth) {
            OAuthSection(state, viewModel)
        } else {
            ApiKeySection(state, viewModel)
        }
    }
}

@Composable
private fun OAuthSection(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    LaunchedEffect(state.selectedProvider?.id) {
        if (state.deviceCode == null && !state.authLoading && !state.authConnected) {
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
            Text("Enter this code in your browser:", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                code.userCode,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            TorvePrimaryButton(
                text = "Open in browser",
                onClick = { openUrl(code.verificationUrl) },
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Waiting for authorization…", style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        TorveSecondaryButton(text = "Retry", onClick = { viewModel.startOAuth() })
    }
}

@Composable
private fun ApiKeySection(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TorveTextField(
            value = state.apiKeyInput,
            onValueChange = { viewModel.setApiKeyInput(it) },
            label = "API key",
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        TorvePrimaryButton(
            text = if (state.authLoading) "Validating…" else "Validate & connect",
            onClick = { viewModel.validateApiKey() },
            enabled = state.apiKeyInput.isNotBlank() && !state.authLoading,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesStep(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    TorveSectionCard(
        title = "Torrent sources",
        supportingText = "Panda will search these indexers. Toggle any you want disabled.",
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
}

@Composable
private fun UsenetStep(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    TorveSectionCard(
        title = "Usenet (optional)",
        supportingText = "Connect a Usenet provider, indexer, and download client if you want NZB support.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable Usenet", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = state.enableUsenet, onCheckedChange = { viewModel.setEnableUsenet(it) })
        }

        if (state.enableUsenet) {
            Spacer(Modifier.height(12.dp))
            Text("Provider", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                        label = "Host",
                        modifier = Modifier.weight(2f),
                    )
                    TorveTextField(
                        value = state.usenetPort.toString(),
                        onValueChange = { it.toIntOrNull()?.let(viewModel::setUsenetPort) },
                        label = "Port",
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            TorveTextField(
                value = state.usenetUsername,
                onValueChange = { viewModel.setUsenetUsername(it) },
                label = "Username",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TorveTextField(
                value = state.usenetPassword,
                onValueChange = { viewModel.setUsenetPassword(it) },
                label = "Password",
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )
            if (state.usenetProvider == "generic") {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SSL", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = state.usenetSSL, onCheckedChange = { viewModel.setUsenetSSL(it) })
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("NZB indexers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            state.nzbIndexers.forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(12.dp))
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
                )
            }
            Spacer(Modifier.height(8.dp))
            TorveSecondaryButton(
                text = "+ Add another indexer",
                onClick = { viewModel.addIndexer() },
            )

            Spacer(Modifier.height(16.dp))
            Text("Download client", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                TorveGhostButton(text = "Remove", onClick = onRemove)
            }
        }
        if (row.type != "none") {
            if (row.type == "custom") {
                Spacer(Modifier.height(8.dp))
                TorveTextField(
                    value = row.url,
                    onValueChange = onUrlChange,
                    label = "Indexer URL",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            TorveTextField(
                value = row.apiKey,
                onValueChange = onKeyChange,
                label = "Indexer API key",
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
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
                    "Bandwidth saver — use NZB path when available",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "When the same release is on both Easynews and one of your NZB indexers, route playback through your cloud download service. Saves Easynews data.",
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
                "Configure at least one NZB indexer with an API key and a cloud download client (Premiumize / TorBox / AllDebrid) to enable.",
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
                label = "Client URL",
                modifier = Modifier.fillMaxWidth(),
            )
            "username" -> TorveTextField(
                value = state.downloadClientUsername,
                onValueChange = { viewModel.setDownloadClientUsername(it) },
                label = "User",
                modifier = Modifier.fillMaxWidth(),
            )
            "password" -> TorveTextField(
                value = state.downloadClientPassword,
                onValueChange = { viewModel.setDownloadClientPassword(it) },
                label = "Password",
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )
            "apiKey" -> TorveTextField(
                value = state.downloadClientApiKey,
                onValueChange = { viewModel.setDownloadClientApiKey(it) },
                label = "API key",
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )
        }
    }
}

private fun desktopLabelForUsenetProvider(id: String): String = when (id) {
    "easynews" -> "Easynews"
    "generic" -> "Generic NNTP"
    else -> id.replaceFirstChar { it.uppercase() }
}

private fun desktopLabelForNzbIndexer(id: String): String = when (id) {
    "none" -> "None"
    "nzbgeek" -> "NZBgeek"
    "scenenzbs" -> "SceneNZBs"
    "dognzb" -> "DogNZB"
    "nzbplanet" -> "NZBPlanet"
    "custom" -> "Custom URL"
    else -> id.replaceFirstChar { it.uppercase() }
}

private fun desktopLabelForDownloadClient(id: String): String = when (id) {
    "none" -> "None"
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
    TorveSectionCard(title = "Quality defaults") {
        Text("Maximum quality", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
        Text("Quality profile", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
        Text("Release language", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
}

private fun desktopLabelForQuality(id: String): String = when (id) {
    "2160p" -> "4K (2160p)"
    else -> id
}

private fun desktopLabelForQualityProfile(id: String): String = when (id) {
    "balanced" -> "Balanced"
    "best_quality" -> "Best quality"
    "fast_start" -> "Fast start"
    "data_saver" -> "Data saver"
    else -> id.replace("_", " ").replaceFirstChar { it.uppercase() }
}

private fun desktopLabelForLanguage(id: String): String = when (id) {
    "any" -> "Any"
    "english" -> "English"
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
    TorveSectionCard(title = "Review & save") {
        TorveListRow(
            title = "Provider",
            subtitle = state.selectedProvider?.name ?: "—",
        )
        TorveListRow(
            title = "Auth",
            subtitle = if (state.authConnected) "Connected" else "Not connected",
        )
        TorveListRow(
            title = "Sources",
            subtitle = "${state.enabledSources.size} enabled",
        )
        TorveListRow(
            title = "Max quality",
            subtitle = state.maxQuality,
        )
        TorveListRow(
            title = "Profile",
            subtitle = state.qualityProfile,
        )
        if (state.enableUsenet) {
            TorveListRow(
                title = "Usenet",
                subtitle = state.usenetProvider,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (state.addonInstalled) {
            TorveBanner(
                title = "Panda installed",
                description = "Panda is now configured and added to Torve. You can close this window.",
                tone = TorveBannerTone.Success,
            )
            Spacer(Modifier.height(12.dp))
            TorvePrimaryButton(text = "Done", onClick = onComplete)
        } else {
            state.saveError?.let {
                TorveBanner(title = "Save failed", description = it, tone = TorveBannerTone.Error)
                Spacer(Modifier.height(12.dp))
            }
            TorvePrimaryButton(
                text = if (state.isSaving) "Saving…" else if (state.isEditMode) "Update Panda" else "Install Panda",
                onClick = { viewModel.saveConfigAndInstall() },
                enabled = !state.isSaving && state.selectedProvider != null && state.authConnected,
            )
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
