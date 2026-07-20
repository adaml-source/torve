@file:OptIn(ExperimentalLayoutApi::class)

package com.torve.desktop.ui.v2.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.data.ai.AiProvider
import com.torve.data.kodi.KodiHost
import com.torve.desktop.DesktopReleaseInfo
import com.torve.desktop.auth.DesktopAuthController
import com.torve.desktop.auth.DesktopAuthPhase
import com.torve.desktop.auth.DesktopAuthUiState
import com.torve.desktop.player.VlcRuntimeLocator
import com.torve.desktop.platform.desktopDataDir
import com.torve.desktop.playback.DesktopPlayerMode
import com.torve.desktop.playback.DesktopPlaybackHotkeyAction
import com.torve.desktop.playback.PlayerModePreferences
import com.torve.desktop.playback.bindingFor
import com.torve.desktop.playback.isSupportedPlaybackHotkey
import com.torve.desktop.playback.withBinding
import com.torve.desktop.mpv.MpvRuntimeLocator
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveDropdownScaffold
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorveListRow
import com.torve.desktop.ui.components.TorvePageHeader
import com.torve.desktop.ui.components.TorvePlaceholderState
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSecondaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.components.TorveSidebarItem
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.l10n.ds
import com.torve.domain.repository.PlaylistAddProgress
import com.torve.desktop.ui.components.pickDirectory
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.model.AutoSourceMode
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.model.CodecPreference
import com.torve.domain.model.HdrMode
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingPillPosition
import com.torve.domain.model.RatingSource
import com.torve.domain.model.PlaylistType
import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamGroup
import com.torve.domain.model.StreamQuality
import com.torve.domain.model.channelIdentityCandidates
import com.torve.domain.model.stableChannelId
import com.torve.domain.repository.AddonRepository
import com.torve.presentation.channels.EpgState
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.session.AccountSessionCoordinator
import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsUiState
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.settings.ThemeMode
import com.torve.presentation.beta.BetaProgramCopy
import com.torve.presentation.beta.BetaProgramUiState
import com.torve.presentation.beta.BetaProgramViewModel
import com.torve.presentation.beta.shouldShowBetaProgramSettingsEntry
import com.torve.domain.streams.StreamRulePatternValidator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private enum class SettingsCategory(
    val label: String,
    val description: String,
) {
    SOURCES("Sources", "Setup intents and provider health"),
    CUSTOMIZATION("Preferences", "Playback, quality, language, browsing"),
    ACCOUNT("Account", "Session, access, identity"),
    INTEGRATIONS("Integrations", "Sync, APIs, media servers"),
    ADDONS("Add-ons", "Source extensions and manifests"),
    PLAYLISTS("Playlists", "M3U and Xtream live TV"),
    RECORDING("Recording", "Live TV recording defaults and behavior"),
    ABOUT("About", "Version, diagnostics, runtime"),
}

private fun desktopBugReportEmailBody(diagnosticsZip: java.io.File?): String = buildString {
    appendLine("Torve bug report")
    appendLine()
    appendLine("What happened:")
    appendLine()
    appendLine("Steps to reproduce:")
    appendLine()
    appendLine("Logs:")
    appendLine()
    appendLine("Diagnostics:")
    if (diagnosticsZip != null) {
        appendLine("Attach this redacted diagnostics zip: ${diagnosticsZip.absolutePath}")
    } else {
        appendLine("Diagnostics export failed. Paste any relevant logs above.")
    }
}

private fun openDesktopBugReportEmail(body: String): Boolean {
    val supportEmail = com.torve.presentation.legal.LegalUrls.SUPPORT_EMAIL
    val subject = encodeMailtoQuery("Torve bug report")
    val encodedBody = encodeMailtoQuery(body)
    return runCatching {
        java.awt.Desktop.getDesktop().mail(
            java.net.URI("mailto:$supportEmail?subject=$subject&body=$encodedBody"),
        )
    }.isSuccess
}

private fun openDesktopUrl(url: String): Boolean = runCatching {
    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
}.isSuccess

private const val DEFAULT_TORVE_DISCORD_INVITE_URL = "https://discord.gg/dVHFAh7Amx"

private fun resolveDesktopDiscordInviteUrl(backendUrl: String?): String? =
    backendUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: System.getProperty("torve.discord.invite")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        ?: System.getenv("TORVE_DISCORD_INVITE_URL")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_TORVE_DISCORD_INVITE_URL

private fun copyDesktopText(value: String): Boolean = runCatching {
    val selection = java.awt.datatransfer.StringSelection(value)
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
}.isSuccess

private fun encodeMailtoQuery(value: String): String =
    java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8.name())
        .replace("+", "%20")

@Composable
fun V2SettingsPage(
    authState: DesktopAuthUiState,
    authController: DesktopAuthController,
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
    accountSessionCoordinator: AccountSessionCoordinator,
    channelsState: ChannelsUiState,
    channelsViewModel: ChannelsViewModel,
    addonRepository: AddonRepository,
    homeViewModel: com.torve.presentation.home.HomeViewModel,
    setupIntentsViewModel: com.torve.presentation.setup.SetupIntentsViewModel? = null,
    onOpenPandaSetup: () -> Unit = {},
    onOpenManageDevices: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenRecordings: () -> Unit = {},
    /**
     * Onboarding deep-link entry: when the user picked "Set up
     * Plex/Jellyfin" from the credential-first hub the shell completes
     * onboarding with a request to land on this section. Null falls back
     * to the default SOURCES category. String form keeps SettingsCategory
     * private to this file.
     */
    initialCategoryName: String? = null,
) {
    var selectedCategory by remember {
        val resolved = initialCategoryName?.let { name ->
            SettingsCategory.entries.firstOrNull { it.name == name }
        }
        mutableStateOf(resolved ?: SettingsCategory.SOURCES)
    }
    var addonManifestUrl by remember { mutableStateOf("") }
    var addonMessage by remember { mutableStateOf<String?>(null) }
    var addonError by remember { mutableStateOf<String?>(null) }
    var addonBusy by remember { mutableStateOf(false) }
    var addonReloadToken by remember { mutableStateOf(0) }
    var installedAddons by remember { mutableStateOf<List<InstalledAddon>>(emptyList()) }
    var kodiName by remember { mutableStateOf("") }
    var kodiIp by remember { mutableStateOf("") }
    var kodiPort by remember { mutableStateOf("8080") }

    val scope = rememberCoroutineScope()

    LaunchedEffect(addonRepository, addonReloadToken) {
        addonBusy = true
        val result = runCatching {
            addonRepository.getInstalledAddons()
                .sortedWith(compareBy<InstalledAddon> { it.priority }.thenBy { it.manifest.name.lowercase() })
        }
        result.onSuccess {
            installedAddons = it
            addonError = null
        }.onFailure {
            addonError = it.message ?: "Failed to load add-ons"
        }
        addonBusy = false
    }

    fun runAddonAction(
        successMessage: String,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            addonBusy = true
            addonError = null
            val result = runCatching { block() }
            result.onSuccess {
                addonMessage = successMessage
                addonReloadToken += 1
            }.onFailure {
                addonError = it.message ?: "Add-on action failed"
            }
            addonBusy = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(
            modifier = Modifier
                .width(230.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TorvePageHeader(
                title = ds("Settings"),
                subtitle = ds("Desktop should expose the same control surface as the rest of Torve."),
            )
            SettingsCategory.entries.forEach { category ->
                TorveSidebarItem(
                    label = ds(category.label),
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    badge = categoryBadge(category, settingsState, installedAddons, channelsState),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TorvePageHeader(
                title = ds(selectedCategory.label),
                subtitle = ds(selectedCategory.description),
            )

            // Persistent (until-dismissed) Panda nudge - easiest path to
            // wiring debrid + indexer access. Shown to signed-in verified
            // users; add-ons are no longer payment-gated.
            val pandaNudgeEligible = authState.user != null &&
                authState.user?.isVerified == true
            DesktopPandaSetupNudgeCard(
                onSetupClick = onOpenPandaSetup,
                eligible = pandaNudgeEligible,
            )
            DesktopBetaProgramSection(
                compact = true,
                hasPremiumAccess = true,
                onOpenAccount = { selectedCategory = SettingsCategory.ACCOUNT },
            )

            when (selectedCategory) {
                SettingsCategory.SOURCES -> SourcesSection(
                    setupIntentsViewModel = setupIntentsViewModel,
                    settingsState = settingsState,
                    settingsViewModel = settingsViewModel,
                    onOpenPandaSetup = onOpenPandaSetup,
                    onSwitchToCategory = { target -> selectedCategory = target },
                )
                SettingsCategory.CUSTOMIZATION -> CustomizationSection(
                    settingsState = settingsState,
                    settingsViewModel = settingsViewModel,
                    homeViewModel = homeViewModel,
                )
                SettingsCategory.ACCOUNT -> AccountSection(
                    authState = authState,
                    authController = authController,
                    onOpenManageDevices = onOpenManageDevices,
                    onOpenStats = onOpenStats,
                )
                SettingsCategory.INTEGRATIONS -> IntegrationsSection(
                    settingsState = settingsState,
                    settingsViewModel = settingsViewModel,
                    accountSessionCoordinator = accountSessionCoordinator,
                    kodiName = kodiName,
                    onKodiNameChange = { kodiName = it },
                    kodiIp = kodiIp,
                    onKodiIpChange = { kodiIp = it },
                    kodiPort = kodiPort,
                    onKodiPortChange = { kodiPort = it },
                    onOpenPandaSetup = onOpenPandaSetup,
                )
                SettingsCategory.ADDONS -> AddonsSection(
                    addonManifestUrl = addonManifestUrl,
                    onAddonManifestUrlChange = { addonManifestUrl = it },
                    installedAddons = installedAddons,
                    addonBusy = addonBusy,
                    addonMessage = addonMessage,
                    addonError = addonError,
                    onInstall = {
                        val url = addonManifestUrl.trim()
                        if (url.isBlank()) {
                            addonError = "Enter an add-on manifest URL"
                        } else {
                            runAddonAction("Add-on installed") {
                                addonRepository.installAddon(url)
                                addonManifestUrl = ""
                            }
                        }
                    },
                    onToggleAddon = { addon ->
                        runAddonAction(
                            if (addon.isEnabled) "Add-on disabled" else "Add-on enabled",
                        ) {
                            addonRepository.toggleAddon(addon.manifestUrl, !addon.isEnabled)
                        }
                    },
                    onRemoveAddon = { addon ->
                        runAddonAction("Add-on removed") {
                            addonRepository.removeAddon(addon.manifestUrl)
                        }
                    },
                    onRefresh = { addonReloadToken += 1 },
                    onConfigurePanda = onOpenPandaSetup,
                )
                SettingsCategory.PLAYLISTS -> PlaylistsSection(
                    channelsState = channelsState,
                    channelsViewModel = channelsViewModel,
                    onOpenRecordings = onOpenRecordings,
                )
                SettingsCategory.RECORDING -> RecordingSection()
                SettingsCategory.ABOUT -> AboutSection(
                    settingsViewModel = settingsViewModel,
                )
            }
        }
    }
}

@Composable
private fun CustomizationSection(
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
    homeViewModel: com.torve.presentation.home.HomeViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TorveSectionCard(
            title = ds("Appearance"),
            supportingText = ds("Theme, language, and region for this device."),
        ) {
            SelectorBlock(
                label = ds("Theme"),
                options = ThemeMode.entries,
                selected = settingsState.themeMode,
                optionLabel = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelect = settingsViewModel::setThemeMode,
            )
            SelectorBlock(
                label = ds("Content Language"),
                options = AppLanguage.entries,
                selected = settingsState.appLanguage,
                optionLabel = { it.displayName },
                onSelect = settingsViewModel::setAppLanguage,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TorveTextField(
                    value = settingsState.regionCode,
                    onValueChange = { settingsViewModel.setRegionCode(it.uppercase()) },
                    label = ds("Region Code"),
                    modifier = Modifier.width(200.dp),
                )
                Text(
                    text = ds("Availability and metadata use this region."),
                    style = MaterialTheme.typography.bodySmall,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            TorveBanner(
                title = ds("Content language only"),
                description = ds("Changes the language used for movie and TV metadata from TMDb."),
                tone = TorveBannerTone.Info,
            )
        }

        TorveSectionCard(
            title = ds("Home Layout"),
            supportingText = ds("Choose whether desktop mirrors your mobile home layout or keeps its own."),
        ) {
            val useDesktopLayoutLabel = ds("Use desktop layout")
            val useMobileLayoutLabel = ds("Use mobile layout")
            SelectorBlock(
                label = ds("Source"),
                options = listOf("SHARED_WITH_MOBILE", "DESKTOP_OWN"),
                selected = settingsState.homeLayoutSource,
                optionLabel = {
                    if (it == "DESKTOP_OWN") useDesktopLayoutLabel else useMobileLayoutLabel
                },
                onSelect = settingsViewModel::setHomeLayoutSource,
            )
            TorveBanner(
                title = if (settingsState.homeLayoutSource == "DESKTOP_OWN")
                    ds("Desktop layout is independent")
                else
                    ds("Editing the layout shared with mobile"),
                description = if (settingsState.homeLayoutSource == "DESKTOP_OWN")
                    ds("Changes below only affect desktop.")
                else
                    ds("Changes below also apply to your mobile home screen."),
                tone = TorveBannerTone.Info,
            )
        }

        HomeShelfEditorCard(
            settingsState = settingsState,
            homeViewModel = homeViewModel,
        )

        CardStylePresetEditorCard(
            settingsState = settingsState,
            settingsViewModel = settingsViewModel,
        )

        TorveSectionCard(
            title = ds("Playback Strategy"),
            supportingText = ds("Quality filters and automation now apply directly from desktop."),
        ) {
            SelectorBlock(
                label = ds("Max Quality"),
                options = StreamQuality.selectable,
                selected = settingsState.maxQuality,
                optionLabel = { it.label },
                onSelect = settingsViewModel::setMaxQuality,
            )
            SelectorBlock(
                label = ds("Min Quality"),
                options = StreamQuality.selectable,
                selected = settingsState.minQuality,
                optionLabel = { it.label },
                onSelect = settingsViewModel::setMinQuality,
            )
            SelectorBlock(
                label = ds("Auto Source"),
                options = AutoSourceMode.entries,
                selected = settingsState.autoSourceMode,
                optionLabel = { it.label },
                onSelect = settingsViewModel::setAutoSourceMode,
            )
            SelectorBlock(
                label = ds("Codec Preference"),
                options = CodecPreference.entries,
                selected = settingsState.codecPreference,
                optionLabel = { it.label },
                onSelect = settingsViewModel::setCodecPreference,
            )
            SelectorBlock(
                label = ds("HDR Mode"),
                options = HdrMode.entries,
                selected = settingsState.hdrMode,
                optionLabel = { it.label },
                onSelect = settingsViewModel::setHdrMode,
            )
        }

        TorveSectionCard(
            title = ds("Automation and Browse Filters"),
            supportingText = ds("Desktop can now control autoplay, cache rules, and browse behavior."),
        ) {
            PreferenceToggleRow(
                title = ds("Cached Only"),
                subtitle = ds("Only keep cached-capable streams in the auto source pool."),
                checked = settingsState.cachedOnly,
                onCheckedChange = settingsViewModel::setCachedOnly,
            )
            PreferenceToggleRow(
                title = ds("Auto Play"),
                subtitle = ds("Start the selected stream immediately after resolution."),
                checked = settingsState.autoPlayEnabled,
                onCheckedChange = settingsViewModel::setAutoPlayEnabled,
            )
            PreferenceToggleRow(
                title = ds("Auto Play Next Episode"),
                subtitle = ds("Continue TV playback without reopening the episode picker."),
                checked = settingsState.autoPlayNextEpisodeEnabled,
                onCheckedChange = settingsViewModel::setAutoPlayNextEpisodeEnabled,
            )
            PreferenceToggleRow(
                title = ds("Dedupe Results"),
                subtitle = ds("Collapse duplicate source candidates from multiple add-ons."),
                checked = settingsState.dedupeResults,
                onCheckedChange = settingsViewModel::setDedupeResultsEnabled,
            )
            PreferenceToggleRow(
                title = ds("Allow 4K in Auto Mode"),
                subtitle = ds("Permit automatic source selection to choose 4K streams when appropriate."),
                checked = settingsState.allow4kAuto,
                onCheckedChange = settingsViewModel::setAllow4kAuto,
            )
            PreferenceToggleRow(
                title = ds("Prefer Compatible Codecs"),
                subtitle = ds("Bias selection away from files that are more likely to fail on the current player stack."),
                checked = settingsState.preferCompatibleCodecs,
                onCheckedChange = settingsViewModel::setPreferCompatibleCodecs,
            )
        }

        TorveSectionCard(
            title = ds("Desktop Playback"),
            supportingText = ds("Seek, subtitle, audio, and volume preferences stored locally."),
        ) {
            // Discovery is mutable here so the Re-check button can refresh
            // it without restarting the app. Snapshot derives the
            // displayed strings + selectable modes from it.
            var discoveryRefresh by remember { mutableStateOf(0) }
            val mpvDiscovery = remember(discoveryRefresh) { MpvRuntimeLocator.discover() }
            val savedMode = remember(discoveryRefresh) { PlayerModePreferences.read() }
            val labsSnapshot = remember(discoveryRefresh, mpvDiscovery, savedMode) {
                com.torve.desktop.playback.MpvLabsStatus.compute(mpvDiscovery, savedMode)
            }
            var chosenPlayerMode by remember(discoveryRefresh) {
                mutableStateOf(labsSnapshot.effectiveMode)
            }
            // Silent normalization: if saved preference was MPV but
            // libmpv is missing, rewrite to VLC. UI explains via the
            // resetNotice line; no warning toast.
            LaunchedEffect(labsSnapshot.wasResetFromMpv) {
                if (labsSnapshot.wasResetFromMpv) {
                    PlayerModePreferences.write(DesktopPlayerMode.VLC)
                    chosenPlayerMode = DesktopPlayerMode.VLC
                }
            }

            // Prompt 18: one-line engine status row above the picker.
            // Reads as quiet/positive when defaults work — never as
            // alarm — so a normal user doesn't see "fallback" /
            // "warning" wording for the supported VLC path.
            Text(
                text = com.torve.desktop.playback.MpvLabsStatus.engineStatusRow(labsSnapshot),
                style = MaterialTheme.typography.bodySmall,
                color = TorveDesktopThemeTokens.colors.textSecondary,
            )

            // Engine selector - VLC always selectable; MPV Labs always
            // visible but disabled when libmpv is unavailable. The
            // disabled row is the user's signal that MPV exists; the
            // premium info card below explains WHY it's disabled.
            Text(
                text = ds("Playback Engine"),
                style = MaterialTheme.typography.labelLarge,
                color = TorveDesktopThemeTokens.colors.textPrimary,
            )
            DesktopPlayerMode.entries.forEach { mode ->
                val isSelectable = mode in labsSnapshot.selectableModes
                EnginePickerRow(
                    label = mode.label,
                    selected = chosenPlayerMode == mode,
                    enabled = isSelectable,
                    trailingNote = when {
                        mode == DesktopPlayerMode.VLC -> "Active"
                        mode == DesktopPlayerMode.MPV && !isSelectable -> "Unavailable on this device"
                        mode == DesktopPlayerMode.MPV -> "Available - Labs"
                        else -> null
                    },
                    onClick = {
                        if (isSelectable) {
                            chosenPlayerMode = mode
                            PlayerModePreferences.write(mode)
                        }
                    },
                )
            }
            // Reset notice - only shown when the saved preference was
            // MPV and got normalized to VLC. Replaces the old playback
            // overlay/banner that used to ride along on every launch.
            labsSnapshot.resetNotice?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
            }
            if (chosenPlayerMode != labsSnapshot.effectiveMode) {
                Text(
                    text = "Restart Torve for the engine change to take effect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
            }

            // ── MPV Labs premium info card ──
            var showMpvSetupGuide by remember { mutableStateOf(false) }
            MpvLabsInfoCard(
                snapshot = labsSnapshot,
                onOpenSetupGuide = { showMpvSetupGuide = true },
                onRecheck = {
                    discoveryRefresh += 1
                },
            )
            if (showMpvSetupGuide) {
                MpvLabsSetupGuideDialog(
                    snapshot = labsSnapshot,
                    onDismiss = { showMpvSetupGuide = false },
                )
            }
            // Pre-existing detailed status row, only meaningful when
            // MPV is actually selected as the active engine.
            if (chosenPlayerMode == DesktopPlayerMode.MPV && mpvDiscovery.found) {
                MpvInstallStatusRow()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TorveTextField(
                    value = settingsState.seekStepSeconds.toString(),
                    onValueChange = { input ->
                        input.toIntOrNull()?.let { settingsViewModel.setSeekStepSeconds(it) }
                    },
                    label = "Seek Step (seconds)",
                    modifier = Modifier.width(200.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    text = "5-60 seconds per seek press.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            PreferenceToggleRow(
                title = "Enable Subtitles by Default",
                subtitle = "Automatically show subtitles when playback starts.",
                checked = settingsState.subtitlesEnabledByDefault,
                onCheckedChange = settingsViewModel::setSubtitlesEnabledByDefault,
            )
            TorveTextField(
                value = settingsState.preferredSubtitleLanguage,
                onValueChange = settingsViewModel::setPreferredSubtitleLanguage,
                label = "Preferred Subtitle Language (e.g. eng, deu, spa)",
                modifier = Modifier.fillMaxWidth(),
            )
            TorveTextField(
                value = settingsState.preferredAudioLanguage,
                onValueChange = settingsViewModel::setPreferredAudioLanguage,
                label = "Preferred Audio Language (e.g. eng, deu, jpn)",
                modifier = Modifier.fillMaxWidth(),
            )
            PreferenceToggleRow(
                title = "Remember Volume",
                subtitle = "Restore the last used volume level on next playback.",
                checked = settingsState.rememberVolume,
                onCheckedChange = settingsViewModel::setRememberVolume,
            )
            if (settingsState.rememberVolume) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TorveTextField(
                        value = settingsState.lastVolume.toString(),
                        onValueChange = { input ->
                            input.toIntOrNull()?.let { settingsViewModel.setLastVolume(it) }
                        },
                        label = "Saved Volume (0-100)",
                        modifier = Modifier.width(200.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(
                        text = "Current saved volume level.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TorveDesktopThemeTokens.colors.textSecondary,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
            DesktopHotkeysEditor(
                settingsState = settingsState,
                settingsViewModel = settingsViewModel,
            )
        }

        TorveSectionCard(
            title = "Downloads",
            supportingText = "Pick folders for movie and show downloads. Downloads are disabled until both are set. Extra scan folders surface existing local media (read-only).",
        ) {
            FolderPickerRow(
                label = "Movie Download Folder",
                path = settingsState.movieDownloadPath,
                onPick = { picked -> settingsViewModel.setMovieDownloadPath(picked) },
                onClear = { settingsViewModel.setMovieDownloadPath("") },
            )
            FolderPickerRow(
                label = "Show Download Folder",
                path = settingsState.showDownloadPath,
                onPick = { picked -> settingsViewModel.setShowDownloadPath(picked) },
                onClear = { settingsViewModel.setShowDownloadPath("") },
            )
            FolderPickerRow(
                label = "Adult Download Folder",
                path = settingsState.adultDownloadPath,
                onPick = { picked -> settingsViewModel.setAdultDownloadPath(picked) },
                onClear = { settingsViewModel.setAdultDownloadPath("") },
            )
            FolderPickerRow(
                label = "Sports Download Folder",
                path = settingsState.sportsDownloadPath,
                onPick = { picked -> settingsViewModel.setSportsDownloadPath(picked) },
                onClear = { settingsViewModel.setSportsDownloadPath("") },
            )
            FolderPickerRow(
                label = "Recordings Folder",
                path = settingsState.recordingDownloadPath,
                onPick = { picked -> settingsViewModel.setRecordingDownloadPath(picked) },
                onClear = { settingsViewModel.setRecordingDownloadPath("") },
            )
            if (settingsState.recordingDownloadPath.isBlank()) {
                Text(
                    text = "Live TV recording is disabled until a Recordings Folder is set. " +
                        "Recordings are filed under <folder>/<channel>/<title> - <timestamp>.ts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
            }
            Text(
                text = "Extra Scan Folders",
                style = MaterialTheme.typography.labelLarge,
                color = TorveDesktopThemeTokens.colors.textPrimary,
            )
            settingsState.downloadScanFolders.forEach { folder ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        folder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TorveDesktopThemeTokens.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    TorveGhostButton(
                        text = "Remove",
                        onClick = { settingsViewModel.removeDownloadScanFolder(folder) },
                    )
                }
            }
            TorveSecondaryButton(
                text = "Add Scan Folder",
                onClick = {
                    pickDirectory(title = "Select scan folder")?.let {
                        settingsViewModel.addDownloadScanFolder(it)
                    }
                },
            )
        }

        RatingsSection(settingsState.ratingPrefs, settingsViewModel)

        TorveSectionCard(
            title = "Adult Catalog",
            supportingText = "Surface a dedicated 'Adult' tab that queries TMDB with adult=true. Public torrent addons mostly will not have these titles - works best with a Panda Usenet indexer.",
        ) {
            var adultEnabled by remember {
                mutableStateOf(com.torve.desktop.adult.AdultModePreferences.isEnabled())
            }
            PreferenceToggleRow(
                title = "Enable Adult tab",
                subtitle = "Adds a new menu item with TMDB's adult catalog. Restart not required; nav rail picks it up immediately.",
                checked = adultEnabled,
                onCheckedChange = { next ->
                    adultEnabled = next
                    com.torve.desktop.adult.AdultModePreferences.setEnabled(next)
                },
            )
        }

        TorveSectionCard(
            title = "Maintenance",
            supportingText = "Keep desktop state under your control without leaving the app.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TorveGhostButton(text = "Clear Cache", onClick = settingsViewModel::clearCache)
            }
            if (settingsState.cacheCleared) {
                TorveBanner(
                    title = "Cache cleared",
                    description = "Metadata cache was cleared successfully.",
                    tone = TorveBannerTone.Success,
                )
            }
        }
    }
}

@Composable
private fun FolderPickerRow(
    label: String,
    path: String,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.textPrimary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = path.ifBlank { "Not set" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (path.isBlank()) colors.textSecondary else colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            TorveSecondaryButton(
                text = "Browse...",
                onClick = {
                    pickDirectory(title = label, initialPath = path)?.let { onPick(it) }
                },
            )
            if (path.isNotBlank()) {
                TorveGhostButton(text = "Clear", onClick = onClear)
            }
        }
    }
}


@Composable
private fun DesktopHotkeysEditor(
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
) {
    val colors = TorveDesktopThemeTokens.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Playback Hotkeys",
            style = MaterialTheme.typography.labelLarge,
            color = colors.textPrimary,
        )
        Text(
            text = "Defaults match desktop media-player expectations. IPTV channel changes use PageUp/PageDown so arrow keys remain volume and seek controls during Live TV.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        DesktopPlaybackHotkeyAction.entries.chunked(2).forEach { rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowActions.forEach { action ->
                    val value = settingsState.desktopPlaybackHotkeys.bindingFor(action)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        TorveTextField(
                            value = value,
                            onValueChange = { input ->
                                settingsViewModel.updateDesktopPlaybackHotkeys(
                                    settingsState.desktopPlaybackHotkeys.withBinding(action, input),
                                )
                            },
                            label = action.label,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = if (isSupportedPlaybackHotkey(value)) action.hint else "Unsupported key. Use Space, Esc, arrows, PageUp/PageDown, F, C, V, M, N, P, S.",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSupportedPlaybackHotkey(value)) colors.textMuted else colors.error,
                        )
                    }
                }
                if (rowActions.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        TorveGhostButton(
            text = "Reset Hotkeys",
            onClick = { settingsViewModel.updateDesktopPlaybackHotkeys(com.torve.domain.player.DesktopPlaybackHotkeys()) },
        )
    }
}

@Composable
private fun RatingsSection(
    prefs: RatingDisplayPrefs,
    settingsViewModel: SettingsViewModel,
) {
    fun update(block: RatingDisplayPrefs.() -> RatingDisplayPrefs) {
        settingsViewModel.updateRatingPrefs(prefs.block())
    }

    TorveSectionCard(
        title = "Ratings Display",
        supportingText = "Control which rating providers appear on cards and detail pages.",
    ) {
        PreferenceToggleRow(
            title = "Show Ratings on Detail Page",
            subtitle = "Display rating pills on the movie/show detail view.",
            checked = prefs.showRatingsOnDetailPage,
            onCheckedChange = { update { copy(showRatingsOnDetailPage = it) } },
        )
        PreferenceToggleRow(
            title = "Show Torve Score on Detail Page",
            subtitle = "Display the weighted composite score alongside provider ratings.",
            checked = prefs.showTorveScoreOnDetailPage,
            onCheckedChange = { update { copy(showTorveScoreOnDetailPage = it) } },
        )
        PreferenceToggleRow(
            title = "Show Torve Score on Cards",
            subtitle = "Include the composite score in poster card rating pills.",
            checked = prefs.showTorveScoreOnCards,
            onCheckedChange = { update { copy(showTorveScoreOnCards = it) } },
        )
        SelectorBlock(
            label = "Pill Position",
            options = RatingPillPosition.entries,
            selected = prefs.pillPosition,
            optionLabel = { it.displayName },
            onSelect = { update { copy(pillPosition = it) } },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TorveTextField(
                value = prefs.maxRatingsOnCard.toString(),
                onValueChange = { input ->
                    input.toIntOrNull()?.coerceIn(0, 9)?.let { update { copy(maxRatingsOnCard = it) } }
                },
                label = "Max Ratings on Card (0-9)",
                modifier = Modifier.width(200.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }

    TorveSectionCard(
        title = "Rating Providers",
        supportingText = "Enable or disable individual rating sources.",
    ) {
        RatingSource.entries.forEach { source ->
            val enabled = source in prefs.enabledProviders
            PreferenceToggleRow(
                title = source.displayName,
                subtitle = "Show ${source.displayName} ratings where available.",
                checked = enabled,
                onCheckedChange = { checked ->
                    val updated = if (checked) {
                        prefs.enabledProviders + source
                    } else {
                        prefs.enabledProviders - source
                    }
                    update { copy(enabledProviders = updated) }
                },
            )
        }
    }
}

@Composable
private fun SubscriptionSection() {
    TorveSectionCard(
        title = "Access",
        supportingText = "Torve is free software. There are no subscriptions or paid tiers.",
    ) {
        TorveListRow(
            title = "Product access",
            subtitle = "All product features are available by default for active accounts.",
            trailing = {
                TorveBadge(
                    text = "Free",
                    tone = TorveBadgeTone.Success,
                )
            },
        )
    }
}

private fun formatIsoDateForDisplay(iso: String): String {
    // Backend returns ISO-8601 (e.g. "2026-05-27T10:15:00Z"). Trim to date for display
    // unless the time-of-day is meaningful - keep "yyyy-MM-dd HH:mm" so renewals
    // showing the same day stay distinguishable.
    return runCatching {
        val instant = java.time.Instant.parse(iso)
        java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    }.getOrDefault(iso)
}

@Composable
private fun DesktopBetaProgramSection(
    compact: Boolean = false,
    hasPremiumAccess: Boolean = false,
    onOpenAccount: (() -> Unit)? = null,
) {
    val betaViewModel = remember {
        org.koin.mp.KoinPlatform.getKoin().get<BetaProgramViewModel>()
    }
    val state by betaViewModel.state.collectAsState()
    val discordInviteUrl = resolveDesktopDiscordInviteUrl(state.discordInviteUrl)
    LaunchedEffect(Unit) {
        betaViewModel.onOpenBetaProgram()
    }
    if (!shouldShowBetaProgramSettingsEntry(state, hasPremiumAccess = hasPremiumAccess)) {
        return
    }

    TorveSectionCard(
        title = ds("Torve Beta Program"),
        supportingText = if (compact) {
            ds("Want early access? Apply from Settings.")
        } else {
            BetaProgramCopy.DETAIL_INTRO
        },
        trailing = {
            TorveBadge(
                text = state.primaryBadge,
                tone = betaBadgeTone(state),
            )
        },
    ) {
        Text(
            text = BetaProgramCopy.DEADLINE,
            style = MaterialTheme.typography.bodyMedium,
            color = TorveDesktopThemeTokens.colors.textSecondary,
        )
        Text(
            text = state.body,
            style = MaterialTheme.typography.bodyMedium,
            color = TorveDesktopThemeTokens.colors.textPrimary,
        )
        if (hasPremiumAccess) {
            Text(
                text = BetaProgramCopy.PREMIUM_TESTER_APPLICATION,
                style = MaterialTheme.typography.bodyMedium,
                color = TorveDesktopThemeTokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = BetaProgramCopy.FREE_PREMIUM_NON_PREMIUM_ONLY,
                style = MaterialTheme.typography.bodySmall,
                color = TorveDesktopThemeTokens.colors.textSecondary,
            )
        }

        state.generatedCode?.takeIf { it.isNotBlank() }?.let { code ->
            Surface(
                color = TorveDesktopThemeTokens.colors.fieldSurface,
                shape = RoundedCornerShape(TorveDesktopThemeTokens.radii.md),
                border = BorderStroke(1.dp, TorveDesktopThemeTokens.colors.accentContainerStrong),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = ds("Discord link code"),
                        style = MaterialTheme.typography.labelMedium,
                        color = TorveDesktopThemeTokens.colors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = code,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TorveDesktopThemeTokens.colors.textPrimary,
                    )
                    state.generatedCodeExpiresAt?.let {
                        Text(
                            text = ds("Expires at") + " $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = TorveDesktopThemeTokens.colors.textSecondary,
                        )
                    }
                    Text(
                        text = BetaProgramCopy.DISCORD_INSTRUCTION,
                        style = MaterialTheme.typography.bodySmall,
                        color = TorveDesktopThemeTokens.colors.textSecondary,
                    )
                    Text(
                        text = ds("Torve Discord") + ": $discordInviteUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = TorveDesktopThemeTokens.colors.textSecondary,
                    )
                }
            }
        }

        if (!compact || state.generatedCode != null || state.errorMessage != null) {
            Text(
                text = BetaProgramCopy.SAFETY,
                style = MaterialTheme.typography.bodySmall,
                color = TorveDesktopThemeTokens.colors.textSecondary,
            )
        }

        state.errorMessage?.let { message ->
            TorveBanner(
                title = ds("Beta status issue"),
                description = message,
                tone = TorveBannerTone.Error,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TorvePrimaryButton(
                text = if (state.isGeneratingCode) ds("Generating...") else state.primaryActionLabel,
                onClick = {
                    handleDesktopBetaPrimaryAction(
                        state = state,
                        viewModel = betaViewModel,
                        onOpenAccount = onOpenAccount,
                    )
                },
                enabled = !state.isGeneratingCode && !state.isRefreshing && !state.isLoading,
            )
            if (discordInviteUrl != null) {
                TorveSecondaryButton(
                    text = ds("Open Discord"),
                    onClick = { openDesktopUrl(discordInviteUrl) },
                )
            }
            if (state.showVerifyEmail) {
                TorveSecondaryButton(
                    text = ds("Resend Verification Email"),
                    onClick = betaViewModel::onResendVerificationEmail,
                )
            }
            TorveGhostButton(
                text = if (state.isRefreshing) ds("Refreshing...") else ds("Refresh Status"),
                onClick = betaViewModel::onRefreshStatus,
                enabled = !state.isRefreshing && !state.isLoading,
            )
        }
    }
}

private fun handleDesktopBetaPrimaryAction(
    state: BetaProgramUiState,
    viewModel: BetaProgramViewModel,
    onOpenAccount: (() -> Unit)?,
) {
    when {
        !state.isSignedIn -> onOpenAccount?.invoke()
        state.showVerifyEmail -> viewModel.onVerifyEmail()
        state.showGenerateCode -> viewModel.onGenerateCode()
        state.showCopyCode && !state.generatedCode.isNullOrBlank() -> {
            if (copyDesktopText(state.generatedCode.orEmpty())) {
                viewModel.onCopyCode()
            }
        }
        else -> viewModel.onRefreshStatus()
    }
}

private fun betaBadgeTone(state: BetaProgramUiState): TorveBadgeTone = when {
    state.betaAccessActive -> TorveBadgeTone.Success
    state.isEmailVerificationRequired -> TorveBadgeTone.Warning
    state.errorMessage != null -> TorveBadgeTone.Error
    state.applicationStatus == com.torve.domain.beta.BetaApplicationStatus.SUBMITTED -> TorveBadgeTone.Accent
    else -> TorveBadgeTone.Accent
}

@Composable
private fun AccountSection(
    authState: DesktopAuthUiState,
    authController: DesktopAuthController,
    onOpenManageDevices: () -> Unit,
    onOpenStats: () -> Unit,
) {
    val openLabel = ds("Open")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TorveSectionCard(
            title = ds("Access"),
            supportingText = ds("Torve is free software. There are no subscriptions or paid tiers."),
        ) {
            TorveListRow(
                title = ds("Product access"),
                subtitle = ds("All product features are available by default for active accounts."),
                trailing = {
                    TorveBadge(text = ds("Free"), tone = TorveBadgeTone.Success)
                },
            )
        }
        DesktopBetaProgramSection(
            hasPremiumAccess = true,
        )
        TorveSectionCard(
            title = ds("Identity"),
            supportingText = ds("Desktop account status, verification, and access are visible here."),
        ) {
            TorveListRow(
                title = authState.user?.displayName?.takeIf { it.isNotBlank() }
                    ?: authState.user?.email
                    ?: ds("No signed-in account"),
                subtitle = authState.statusMessage,
                trailing = {
                    TorveBadge(
                        text = if (authState.user?.isVerified == true) ds("Verified") else ds("Unverified"),
                        tone = if (authState.user?.isVerified == true) TorveBadgeTone.Success else TorveBadgeTone.Warning,
                    )
                },
            )
            TorveListRow(
                title = ds("Access"),
                subtitle = authState.accessState.accessStatusLabel,
                trailing = { TorveBadge(authState.accessState.accessStatusLabel, tone = TorveBadgeTone.Accent) },
            )
            TorveListRow(
                title = ds("Manage Devices"),
                subtitle = ds("View active devices, remove them, and free up slots."),
                onClick = onOpenManageDevices,
                trailing = {
                    TorveGhostButton(text = openLabel, onClick = onOpenManageDevices)
                },
            )
            TorveListRow(
                title = ds("Watch Stats"),
                subtitle = ds("Hours watched, streaks, and top genres across your history."),
                onClick = onOpenStats,
                trailing = {
                    TorveGhostButton(text = openLabel, onClick = onOpenStats)
                },
            )
        }

        TorveSectionCard(
            title = ds("Session Controls"),
            supportingText = ds("Everything needed to refresh or end the current session stays in-app."),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TorvePrimaryButton(
                    text = ds("Refresh Session"),
                    onClick = authController::refreshSession,
                    enabled = authState.phase != DesktopAuthPhase.LOADING,
                )
                TorveSecondaryButton(
                    text = ds("Refresh Access"),
                    onClick = authController::refreshAccess,
                    enabled = authState.phase != DesktopAuthPhase.LOADING,
                )
                TorveGhostButton(
                    text = ds("Sign Out"),
                    onClick = authController::signOut,
                    enabled = authState.phase != DesktopAuthPhase.LOADING,
                )
            }
            authState.accountSessionState.lastError?.let {
                TorveBanner(
                    title = ds("Session issue"),
                    description = it,
                    tone = TorveBannerTone.Error,
                )
            }
        }
    }
}

@Composable
private fun IntegrationsSection(
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
    accountSessionCoordinator: AccountSessionCoordinator,
    kodiName: String,
    onKodiNameChange: (String) -> Unit,
    kodiIp: String,
    onKodiIpChange: (String) -> Unit,
    kodiPort: String,
    onKodiPortChange: (String) -> Unit,
    onOpenPandaSetup: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PandaSection(onOpenPandaSetup = onOpenPandaSetup)
        TraktSection(settingsState, settingsViewModel)
        SimklSection(settingsState, settingsViewModel)
        AiSection(settingsState, settingsViewModel, accountSessionCoordinator)
        MetadataKeysSection(settingsState, settingsViewModel, accountSessionCoordinator)
        MediaServersSection(settingsState, settingsViewModel, accountSessionCoordinator)
        KodiSection(
            settingsState = settingsState,
            settingsViewModel = settingsViewModel,
            kodiName = kodiName,
            onKodiNameChange = onKodiNameChange,
            kodiIp = kodiIp,
            onKodiIpChange = onKodiIpChange,
            kodiPort = kodiPort,
            onKodiPortChange = onKodiPortChange,
        )
    }
}

@Composable
private fun PandaSection(onOpenPandaSetup: () -> Unit) {
    TorveSectionCard(
        title = ds("Panda (guided setup)"),
        supportingText = ds("Configure debrid providers, torrent sources, usenet, and quality - Panda installs itself as an add-on."),
    ) {
        TorvePrimaryButton(
            text = ds("Open Panda setup"),
            onClick = onOpenPandaSetup,
        )
    }
}

@Composable
private fun TraktSection(
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
) {
    val connectedLabel = ds("Connected")
    val disconnectedLabel = ds("Disconnected")
    val connectPrompt = ds("Connect to sync watch history, ratings, and progress.")
    val lastSyncLabel = ds("Last sync")
    TorveSectionCard(
        title = "Trakt",
        supportingText = ds("Connect Trakt from desktop, sync now, and control scrobbling locally."),
        trailing = {
            TorveBadge(
                text = if (settingsState.traktConnected) connectedLabel else disconnectedLabel,
                tone = if (settingsState.traktConnected) TorveBadgeTone.Success else TorveBadgeTone.Neutral,
            )
        },
    ) {
        TorveListRow(
            title = settingsState.traktUser?.username ?: ds("No Trakt account connected"),
            subtitle = buildString {
                append(settingsState.traktStats?.let { "${it.moviesWatched} movies, ${it.episodesWatched} episodes" } ?: connectPrompt)
                settingsState.traktLastSyncTime?.let { append(" • $lastSyncLabel ${formatTimestamp(it)}") }
            },
        )
        PreferenceToggleRow(
            title = ds("Scrobble Playback"),
            subtitle = ds("Send live playback progress updates to Trakt."),
            checked = settingsState.traktScrobbleEnabled,
            onCheckedChange = settingsViewModel::setTraktScrobbleEnabled,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TorvePrimaryButton(
                text = ds("Connect Trakt"),
                onClick = settingsViewModel::startTraktDeviceAuth,
                enabled = !settingsState.traktLoading,
            )
            TorveSecondaryButton(
                text = ds("Sync Now"),
                onClick = settingsViewModel::syncTraktNow,
                enabled = settingsState.traktConnected && !settingsState.traktSyncing,
            )
            TorveGhostButton(
                text = ds("Disconnect"),
                onClick = settingsViewModel::disconnectTrakt,
                enabled = settingsState.traktConnected,
            )
        }
        settingsState.traktDeviceCode?.let {
            DeviceCodeBanner(
                title = ds("Trakt device auth"),
                userCode = it.userCode,
                verificationUrl = it.verificationUrl,
                waiting = settingsState.isPollingTrakt,
            )
        }
        settingsState.traktApiStatus?.let {
            TorveBanner(title = ds("Trakt status"), description = it, tone = TorveBannerTone.Info)
        }
        settingsState.traktError?.let {
            TorveBanner(title = ds("Trakt error"), description = it, tone = TorveBannerTone.Error)
        }
        if (settingsState.traktLoading || settingsState.traktSyncing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SimklSection(
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
) {
    val connectedLabel = ds("Connected")
    val disconnectedLabel = ds("Disconnected")
    TorveSectionCard(
        title = "SIMKL",
        supportingText = ds("Desktop can now configure the client ID and run SIMKL device auth without leaving the app."),
        trailing = {
            TorveBadge(
                text = if (settingsState.simklConnected) connectedLabel else disconnectedLabel,
                tone = if (settingsState.simklConnected) TorveBadgeTone.Success else TorveBadgeTone.Neutral,
            )
        },
    ) {
        TorveTextField(
            value = settingsState.simklClientId,
            onValueChange = settingsViewModel::setSimklClientId,
            label = ds("SIMKL Client ID"),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TorvePrimaryButton(
                text = ds("Connect SIMKL"),
                onClick = settingsViewModel::startSimklDeviceAuth,
                enabled = !settingsState.simklLoading,
            )
            TorveGhostButton(
                text = ds("Disconnect"),
                onClick = settingsViewModel::disconnectSimkl,
                enabled = settingsState.simklConnected,
            )
        }
        settingsState.simklUser?.let {
            TorveListRow(title = it.username, subtitle = ds("SIMKL account connected"))
        }
        settingsState.simklDeviceCode?.let {
            DeviceCodeBanner(
                title = ds("SIMKL device auth"),
                userCode = it.userCode,
                verificationUrl = it.verificationUrl,
                waiting = settingsState.isPollingSimkl,
            )
        }
        settingsState.simklError?.let {
            TorveBanner(title = ds("SIMKL error"), description = it, tone = TorveBannerTone.Error)
        }
        if (settingsState.simklLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AiSection(
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
    accountSessionCoordinator: AccountSessionCoordinator,
) {
    val scope = rememberCoroutineScope()
    TorveSectionCard(
        title = ds("AI Services"),
        supportingText = ds("Provider selection and API key validation are available directly on desktop."),
    ) {
        SelectorBlock(
            label = ds("Provider"),
            options = AiProvider.entries,
            selected = settingsState.aiProvider,
            optionLabel = { it.label },
            onSelect = settingsViewModel::setAiProvider,
        )
        TorveTextField(
            value = settingsState.activeAiApiKey,
            onValueChange = settingsViewModel::updateActiveAiApiKeyInput,
            label = "${settingsState.aiProvider.label} ${ds("API Key")}",
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TorvePrimaryButton(
                text = ds("Save and Validate"),
                onClick = {
                    settingsViewModel.saveAndValidateAiApiKey()
                    val apiKey = settingsState.activeAiApiKey
                    if (apiKey.isNotBlank()) {
                        scope.launch {
                            accountSessionCoordinator.saveIntegrationToBackend(
                                integrationType = "${settingsState.aiProvider.name}_API_KEY",
                                credentials = mapOf("api_key" to apiKey),
                                displayIdentifier = settingsState.aiProvider.label,
                            )
                        }
                    }
                },
                enabled = settingsState.activeAiApiKey.isNotBlank() && !settingsState.aiKeyValidating,
            )
        }
        settingsState.aiKeyValidationResult?.let {
            TorveBanner(
                title = if (it == "valid") ds("AI key validated") else ds("AI key status"),
                description = it,
                tone = if (it == "valid") TorveBannerTone.Success else TorveBannerTone.Info,
            )
        }
        if (settingsState.aiKeyValidating) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MetadataKeysSection(
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
    accountSessionCoordinator: AccountSessionCoordinator,
) {
    val scope = rememberCoroutineScope()
    TorveSectionCard(
        title = ds("Metadata APIs"),
        supportingText = ds("OMDb and MDBList keys are editable on desktop instead of being website-only."),
    ) {
        TorveTextField(
            value = settingsState.omdbApiKey,
            onValueChange = settingsViewModel::updateOmdbApiKeyInput,
            label = ds("OMDb API Key"),
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TorvePrimaryButton(
                text = ds("Save and Validate OMDb"),
                onClick = {
                    settingsViewModel.saveAndValidateOmdbApiKey()
                    val apiKey = settingsState.omdbApiKey
                    if (apiKey.isNotBlank()) {
                        scope.launch {
                            accountSessionCoordinator.saveIntegrationToBackend(
                                integrationType = "OMDB_API_KEY",
                                credentials = mapOf("api_key" to apiKey),
                                displayIdentifier = "OMDB",
                            )
                        }
                    }
                },
                enabled = settingsState.omdbApiKey.isNotBlank() && !settingsState.omdbValidating,
            )
        }
        settingsState.omdbValidationResult?.let {
            TorveBanner(
                title = if (it == "valid") ds("OMDb key validated") else ds("OMDb status"),
                description = it,
                tone = if (it == "valid") TorveBannerTone.Success else TorveBannerTone.Info,
            )
        }
        TorveTextField(
            value = settingsState.mdblistApiKey,
            onValueChange = settingsViewModel::setMdblistApiKey,
            label = ds("MDBList API Key"),
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Text(
            text = ds("MDBList is saved locally as you edit the field."),
            style = MaterialTheme.typography.bodySmall,
            color = TorveDesktopThemeTokens.colors.textSecondary,
        )
        TorvePrimaryButton(
            text = ds("Save MDBList"),
            onClick = {
                val apiKey = settingsState.mdblistApiKey
                if (apiKey.isNotBlank()) {
                    scope.launch {
                        accountSessionCoordinator.saveIntegrationToBackend(
                            integrationType = "MDBLIST_API_KEY",
                            credentials = mapOf("api_key" to apiKey),
                            displayIdentifier = "MDBList",
                        )
                    }
                }
            },
            enabled = settingsState.mdblistApiKey.isNotBlank(),
        )
    }
}

@Composable
private fun MediaServersSection(
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
    accountSessionCoordinator: AccountSessionCoordinator,
) {
    val scope = rememberCoroutineScope()
    val noneLabel = ds("None")
    TorveSectionCard(
        title = ds("Jellyfin and Plex"),
        supportingText = ds("Server URLs and auth tokens are configurable directly in the desktop shell."),
    ) {
        TorveTextField(
            value = settingsState.jellyfinServerUrl,
            onValueChange = settingsViewModel::setJellyfinServerUrl,
            label = ds("Jellyfin Server URL"),
            modifier = Modifier.fillMaxWidth(),
        )
        TorveTextField(
            value = settingsState.jellyfinApiKey,
            onValueChange = settingsViewModel::updateJellyfinApiKeyInput,
            label = ds("Jellyfin API Key"),
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TorvePrimaryButton(
                text = ds("Save and Test Jellyfin"),
                onClick = {
                    settingsViewModel.saveAndTestJellyfinConnection()
                    val apiKey = settingsState.jellyfinApiKey
                    if (apiKey.isNotBlank()) {
                        scope.launch {
                            accountSessionCoordinator.saveIntegrationToBackend(
                                integrationType = "JELLYFIN_API_KEY",
                                credentials = mapOf("api_key" to apiKey),
                                displayIdentifier = "Jellyfin",
                                config = mapOf("server_url" to settingsState.jellyfinServerUrl),
                            )
                        }
                    }
                },
                enabled = settingsState.jellyfinServerUrl.isNotBlank() && settingsState.jellyfinApiKey.isNotBlank(),
            )
        }
        settingsState.jellyfinStatusMessage?.let {
            TorveBanner(
                title = ds("Jellyfin status"),
                description = it,
                tone = if (it.contains("successful", ignoreCase = true)) TorveBannerTone.Success else TorveBannerTone.Info,
            )
        }
        settingsState.jellyfinProfiles.takeIf { it.isNotEmpty() }?.let { profiles ->
            SelectorBlock(
                label = ds("Jellyfin Profile"),
                options = profiles,
                selected = profiles.firstOrNull { it.id == settingsState.selectedJellyfinUserId },
                optionLabel = { it.name },
                allowNoSelection = true,
                noSelectionLabel = noneLabel,
                onSelectNullable = { settingsViewModel.selectJellyfinProfile(it?.id) },
            )
        }
        TorveTextField(
            value = settingsState.plexServerUrl,
            onValueChange = settingsViewModel::setPlexServerUrl,
            label = ds("Plex Server URL"),
            modifier = Modifier.fillMaxWidth(),
        )
        TorveTextField(
            value = settingsState.plexAccessToken,
            onValueChange = settingsViewModel::updatePlexAccessTokenInput,
            label = ds("Plex Access Token"),
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TorvePrimaryButton(
                text = ds("Save and Connect Plex"),
                onClick = {
                    settingsViewModel.saveAndConnectPlex()
                    val token = settingsState.plexAccessToken
                    if (token.isNotBlank()) {
                        scope.launch {
                            accountSessionCoordinator.saveIntegrationToBackend(
                                integrationType = "PLEX_ACCESS_TOKEN",
                                credentials = mapOf("access_token" to token),
                                displayIdentifier = "Plex",
                                config = mapOf("server_url" to settingsState.plexServerUrl),
                            )
                        }
                    }
                },
                enabled = settingsState.plexServerUrl.isNotBlank() && settingsState.plexAccessToken.isNotBlank() && !settingsState.plexLoading,
            )
            TorveGhostButton(
                text = ds("Disconnect Plex"),
                onClick = settingsViewModel::disconnectPlex,
                enabled = settingsState.plexConnected,
            )
        }
        settingsState.plexError?.let {
            TorveBanner(title = ds("Plex error"), description = it, tone = TorveBannerTone.Error)
        }
        if (settingsState.plexLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun KodiSection(
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
    kodiName: String,
    onKodiNameChange: (String) -> Unit,
    kodiIp: String,
    onKodiIpChange: (String) -> Unit,
    kodiPort: String,
    onKodiPortChange: (String) -> Unit,
) {
    TorveSectionCard(
        title = ds("Kodi Hosts"),
        supportingText = ds("Add, test, and remove Kodi endpoints locally from desktop."),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TorveTextField(
                value = kodiName,
                onValueChange = onKodiNameChange,
                label = ds("Name"),
                modifier = Modifier.weight(1f),
            )
            TorveTextField(
                value = kodiIp,
                onValueChange = onKodiIpChange,
                label = ds("IP / Host"),
                modifier = Modifier.weight(1f),
            )
            TorveTextField(
                value = kodiPort,
                onValueChange = onKodiPortChange,
                label = ds("Port"),
                modifier = Modifier.width(120.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TorvePrimaryButton(
                text = ds("Add Kodi Host"),
                onClick = {
                    val port = kodiPort.toIntOrNull()
                    if (kodiName.isNotBlank() && kodiIp.isNotBlank() && port != null) {
                        settingsViewModel.addKodiHost(kodiName.trim(), kodiIp.trim(), port)
                        onKodiNameChange("")
                        onKodiIpChange("")
                        onKodiPortChange("8080")
                    }
                },
                enabled = kodiName.isNotBlank() && kodiIp.isNotBlank() && kodiPort.toIntOrNull() != null,
            )
        }
        if (settingsState.kodiHosts.isEmpty()) {
            TorvePlaceholderState(
                title = ds("No Kodi hosts"),
                description = ds("Add at least one Kodi box if you want remote playback control or testing."),
            )
        } else {
            settingsState.kodiHosts.forEach { host ->
                KodiHostRow(
                    host = host,
                    testResult = settingsState.kodiTestResult["${host.ip}:${host.port}"],
                    onTest = { settingsViewModel.testKodiHost(host) },
                    onRemove = { settingsViewModel.removeKodiHost(host) },
                )
            }
        }
    }
}

@Composable
private fun AddonsSection(
    addonManifestUrl: String,
    onAddonManifestUrlChange: (String) -> Unit,
    installedAddons: List<InstalledAddon>,
    addonBusy: Boolean,
    addonMessage: String?,
    addonError: String?,
    onInstall: () -> Unit,
    onToggleAddon: (InstalledAddon) -> Unit,
    onRemoveAddon: (InstalledAddon) -> Unit,
    onRefresh: () -> Unit,
    onConfigurePanda: () -> Unit = {},
) {
    val enabledLabel = ds("Enabled")
    val disabledLabel = ds("Disabled")
    val configureLabel = ds("Configure")
    val enableLabel = ds("Enable")
    val disableLabel = ds("Disable")
    val removeLabel = ds("Remove")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TorveSectionCard(
            title = ds("Install Add-ons"),
            supportingText = ds("Paste a Stremio-compatible manifest URL and install it directly on desktop."),
        ) {
            TorveTextField(
                value = addonManifestUrl,
                onValueChange = onAddonManifestUrlChange,
                label = ds("Manifest URL"),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TorvePrimaryButton(
                    text = ds("Install"),
                    onClick = onInstall,
                    enabled = addonManifestUrl.isNotBlank() && !addonBusy,
                )
                TorveGhostButton(
                    text = ds("Refresh"),
                    onClick = onRefresh,
                    enabled = !addonBusy,
                )
            }
            addonMessage?.let {
                TorveBanner(title = ds("Add-on status"), description = it, tone = TorveBannerTone.Success)
            }
            addonError?.let {
                TorveBanner(title = ds("Add-on error"), description = it, tone = TorveBannerTone.Error)
            }
            if (addonBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        TorveSectionCard(
            title = ds("Installed Add-ons"),
            supportingText = ds("Enable, disable, or remove source extensions without using the website."),
        ) {
            if (installedAddons.isEmpty()) {
                TorvePlaceholderState(
                    title = ds("No add-ons installed"),
                    description = ds("Install a manifest URL above to populate desktop sources."),
                )
            } else {
                installedAddons.forEach { addon ->
                    TorveListRow(
                        title = addon.manifest.name,
                        subtitle = buildString {
                            append("v${addon.manifest.version}")
                            if (addon.manifest.types.isNotEmpty()) {
                                append(" • ")
                                append(addon.manifest.types.joinToString(", "))
                            }
                            if (addon.manifest.description.isNotBlank()) {
                                append(" • ")
                                append(addon.manifest.description)
                            }
                            // Surface the manifest URL on its own line so the
                            // user can see exactly which endpoint is registered
                            // (especially relevant for Panda's per-user URL).
                            // Token segments in URLs (e.g.
                            // panda.torve.app/u/<token>/manifest.json) are
                            // sensitive but the row already requires an
                            // authenticated user to see, so showing them
                            // matches the rest of the integrations surface.
                            if (addon.manifestUrl.isNotBlank()) {
                                append("\n")
                                append(addon.manifestUrl)
                            }
                        },
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TorveBadge(
                                    text = if (addon.isEnabled) enabledLabel else disabledLabel,
                                    tone = if (addon.isEnabled) TorveBadgeTone.Success else TorveBadgeTone.Neutral,
                                )
                                // Panda is the only configurable add-on; hand off to the
                                // guided setup screen so users can change provider, indexer,
                                // download client, etc. without going via the Integrations
                                // sub-page.
                                val isPanda = addon.manifest.id == "com.torve.panda" ||
                                    addon.manifestUrl.contains("panda.torve.app")
                                if (isPanda) {
                                    TorveGhostButton(
                                        text = configureLabel,
                                        onClick = onConfigurePanda,
                                        enabled = !addonBusy,
                                    )
                                }
                                TorveGhostButton(
                                    text = ds("Copy URL"),
                                    onClick = {
                                        val sel = java.awt.datatransfer.StringSelection(addon.manifestUrl)
                                        java.awt.Toolkit.getDefaultToolkit()
                                            .systemClipboard.setContents(sel, sel)
                                    },
                                    enabled = !addonBusy && addon.manifestUrl.isNotBlank(),
                                )
                                TorveGhostButton(
                                    text = if (addon.isEnabled) disableLabel else enableLabel,
                                    onClick = { onToggleAddon(addon) },
                                    enabled = !addonBusy,
                                )
                                TorveGhostButton(
                                    text = removeLabel,
                                    onClick = { onRemoveAddon(addon) },
                                    enabled = !addonBusy,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

private val desktopRegexPresets = listOf(
    "Hide CAM/TS" to "(?i)\\b(HDCAM|CAM|TS|TELESYNC)\\b",
    "Hide samples" to "(?i)\\b(sample|trailer|promo|teaser)\\b",
    "Hide low quality" to "(?i)\\b(480p|360p)\\b",
)

private val desktopStreamGroupPresets = listOf(
    Triple("4K DV Atmos", "(?i)\\b(2160p|4K|DV|Dolby Vision|Atmos)\\b", 0),
    Triple("1080p WEB-DL", "(?i)\\b(1080p|WEB-?DL)\\b", 10),
    Triple("Cached debrid", "(?i)\\b(cached|debrid|cloud)\\b", 0),
)

@Composable
private fun DesktopStreamRulesSection(
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
) {
    val colors = TorveDesktopThemeTokens.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TorveSectionCard(
            title = ds("Regex Patterns"),
            supportingText = ds("Stream filters hide matching source titles before the picker is shown."),
            trailing = {
                TorveBadge(
                    text = "${settingsState.regexPatterns.count { it.enabled }} active",
                    tone = if (settingsState.regexPatterns.any { it.enabled }) TorveBadgeTone.Accent else TorveBadgeTone.Neutral,
                )
            },
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                desktopRegexPresets.forEach { (label, pattern) ->
                    val exists = settingsState.regexPatterns.any { it.pattern == pattern }
                    TorveFilterChip(
                        text = if (exists) "$label added" else label,
                        selected = exists,
                        onClick = {
                            if (!exists) settingsViewModel.addRegexPattern(label, pattern)
                        },
                    )
                }
                TorveSecondaryButton(
                    text = ds("Add rule"),
                    onClick = { settingsViewModel.addRegexPattern() },
                )
            }
            if (settingsState.regexPatterns.isEmpty()) {
                TorvePlaceholderState(
                    title = ds("No regex patterns"),
                    description = ds("Add a rule to hide matching stream titles such as CAM releases or samples."),
                )
            } else {
                settingsState.regexPatterns.forEachIndexed { index, pattern ->
                        DesktopRegexPatternRow(
                            pattern = pattern,
                            enabled = true,
                        onUpdate = { settingsViewModel.updateRegexPattern(index, it) },
                        onToggle = { settingsViewModel.toggleRegexPattern(index) },
                        onDelete = { settingsViewModel.removeRegexPattern(index) },
                    )
                }
            }
            Text(
                text = ds("Matching uses visible stream metadata only. URLs, hashes, tokens, memory IDs, and provider payloads are never shown here."),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }

        TorveSectionCard(
            title = ds("Stream Groups"),
            supportingText = ds("Stream groups prioritize matching sources inside existing ranking buckets; they do not hide streams."),
            trailing = {
                TorveBadge(
                    text = "${settingsState.streamGroups.count { it.enabled }} active",
                    tone = if (settingsState.streamGroups.any { it.enabled }) TorveBadgeTone.Accent else TorveBadgeTone.Neutral,
                )
            },
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                desktopStreamGroupPresets.forEach { (name, pattern, priority) ->
                    val exists = settingsState.streamGroups.any { it.name == name && it.matchPattern == pattern }
                    TorveFilterChip(
                        text = if (exists) "$name added" else name,
                        selected = exists,
                        onClick = {
                            if (!exists) settingsViewModel.addStreamGroup(name, pattern, priority)
                        },
                    )
                }
                TorveSecondaryButton(
                    text = ds("Add group"),
                    onClick = { settingsViewModel.addStreamGroup() },
                )
                TorveGhostButton(
                    text = ds("Reset defaults"),
                    onClick = settingsViewModel::resetStreamGroups,
                )
            }
            if (settingsState.streamGroups.isEmpty()) {
                TorvePlaceholderState(
                    title = ds("No stream groups"),
                    description = ds("Add groups to prefer releases like 4K DV Atmos while preserving Torve's primary stream ranking."),
                )
            } else {
                settingsState.streamGroups
                    .mapIndexed { index, group -> index to group }
                    .sortedWith(compareBy<Pair<Int, StreamGroup>> { it.second.priority }.thenBy { it.first })
                    .forEach { (index, group) ->
                        DesktopStreamGroupRow(
                            group = group,
                            enabled = true,
                            onUpdate = { settingsViewModel.updateStreamGroup(index, it) },
                            onToggle = { settingsViewModel.toggleStreamGroup(index) },
                            onDelete = { settingsViewModel.removeStreamGroup(index) },
                        )
                    }
            }
        }
    }
}

@Composable
private fun DesktopRegexPatternRow(
    pattern: RegexPattern,
    enabled: Boolean,
    onUpdate: (RegexPattern) -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val canEnable = StreamRulePatternValidator.canEnable(pattern.pattern)
    val validationMessage = desktopRegexValidationMessage(pattern.pattern)
    RuleRowSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorveTextField(
                value = pattern.label,
                onValueChange = { onUpdate(pattern.copy(label = it)) },
                label = ds("Label"),
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = ds("No CAM releases"),
            )
            Switch(
                checked = pattern.enabled && canEnable,
                onCheckedChange = { onToggle() },
                enabled = enabled && canEnable,
            )
            TorveGhostButton(
                text = ds("Delete"),
                onClick = onDelete,
                enabled = enabled,
            )
        }
        TorveTextField(
            value = pattern.pattern,
            onValueChange = { onUpdate(pattern.copy(pattern = it)) },
            label = ds("Regex"),
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            placeholder = "(?i)\\b(HDCAM|CAM|TS)\\b",
        )
        validationMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TorveDesktopThemeTokens.colors.error,
            )
        }
    }
}

@Composable
private fun DesktopStreamGroupRow(
    group: StreamGroup,
    enabled: Boolean,
    onUpdate: (StreamGroup) -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val canEnable = StreamRulePatternValidator.canEnable(group.matchPattern)
    val validationMessage = desktopGroupValidationMessage(group.matchPattern)
    RuleRowSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorveTextField(
                value = group.name,
                onValueChange = { onUpdate(group.copy(name = it)) },
                label = ds("Name"),
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = ds("4K DV Atmos"),
            )
            TorveTextField(
                value = group.priority.toString(),
                onValueChange = { raw ->
                    onUpdate(group.copy(priority = raw.filter(Char::isDigit).toIntOrNull() ?: 99))
                },
                label = ds("Priority"),
                modifier = Modifier.width(110.dp),
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Switch(
                checked = group.enabled && canEnable,
                onCheckedChange = { onToggle() },
                enabled = enabled && canEnable,
            )
            TorveGhostButton(
                text = ds("Delete"),
                onClick = onDelete,
                enabled = enabled,
            )
        }
        TorveTextField(
            value = group.matchPattern,
            onValueChange = { onUpdate(group.copy(matchPattern = it)) },
            label = ds("Match regex"),
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            placeholder = "(?i)\\b(2160p|DV|Atmos)\\b",
        )
        validationMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TorveDesktopThemeTokens.colors.error,
            )
        }
    }
}

@Composable
private fun RuleRowSurface(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.cardSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

private fun desktopRegexValidationMessage(pattern: String): String? = when {
    pattern.isBlank() -> "Enter a regex pattern before enabling."
    else -> StreamRulePatternValidator.regexErrorMessage(pattern)
}

private fun desktopGroupValidationMessage(pattern: String): String? = when {
    pattern.isBlank() -> "Enter a group regex before enabling."
    else -> StreamRulePatternValidator.groupErrorMessage(pattern)
}

@Composable
private fun PlaylistsSection(
    channelsState: ChannelsUiState,
    channelsViewModel: ChannelsViewModel,
    onOpenRecordings: () -> Unit,
) {
    val selectedPlaylist = channelsState.playlists.firstOrNull { it.id == channelsState.selectedPlaylistId }
    val selectedPlaylistForEpg = selectedPlaylist
    var selectedPlaylistEpgDraft by remember { mutableStateOf("") }

    LaunchedEffect(channelsState.playlists, channelsState.selectedPlaylistId) {
        if (channelsState.selectedPlaylistId == null) {
            channelsState.playlists.firstOrNull()?.let { channelsViewModel.selectPlaylist(it.id) }
        }
    }
    LaunchedEffect(channelsState.selectedPlaylistId) {
        if (channelsState.selectedPlaylistId != null) {
            channelsViewModel.ensureEpgLoaded()
        }
    }
    LaunchedEffect(selectedPlaylistForEpg?.id, selectedPlaylistForEpg?.epgUrl) {
        selectedPlaylistEpgDraft = selectedPlaylistForEpg?.epgUrl.orEmpty()
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ── IPTV recordings + EPG correction (Prompt 10B) ────────
        // The selected playlist drives both surfaces: the recordings
        // list is global, but EPG correction is per-playlist (offset,
        // tvg-id remap, hidden categories).
        val recordingsPlaylistId = channelsState.selectedPlaylistId
        TorveSectionCard(
            title = ds("Live TV recordings"),
            supportingText = ds("Schedule recordings from the EPG. They land in your movies download folder."),
        ) {
            TorvePrimaryButton(
                text = ds("Open My Recordings"),
                onClick = onOpenRecordings,
            )
        }
        if (recordingsPlaylistId != null) {
            EpgCorrectionCard(
                playlistId = recordingsPlaylistId,
                channelsState = channelsState,
            )
        }

        val addingLabel = ds("Adding...")
        val addPlaylistLabel = ds("Add Playlist")
        TorveSectionCard(
            title = addPlaylistLabel,
            supportingText = ds("Desktop can now create M3U or Xtream playlists directly in settings."),
            trailing = {
                TorveBadge(
                    text = channelsState.newPlaylistType.uppercase(),
                    tone = TorveBadgeTone.Accent,
                )
            },
        ) {
            SelectorBlock(
                label = ds("Playlist Type"),
                options = listOf("m3u", "xtream"),
                selected = channelsState.newPlaylistType,
                optionLabel = { if (it == "xtream") "Xtream" else "M3U" },
                onSelect = channelsViewModel::setNewPlaylistType,
            )
            TorveTextField(
                value = channelsState.newPlaylistName,
                onValueChange = channelsViewModel::setNewPlaylistName,
                label = ds("Playlist Name"),
                modifier = Modifier.fillMaxWidth(),
            )
            if (channelsState.newPlaylistType == "xtream") {
                TorveTextField(
                    value = channelsState.newXtreamServer,
                    onValueChange = channelsViewModel::setNewXtreamServer,
                    label = ds("Xtream Server"),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TorveTextField(
                        value = channelsState.newXtreamUsername,
                        onValueChange = channelsViewModel::setNewXtreamUsername,
                        label = ds("Username"),
                        modifier = Modifier.weight(1f),
                    )
                    TorveTextField(
                        value = channelsState.newXtreamPassword,
                        onValueChange = channelsViewModel::setNewXtreamPassword,
                        label = ds("Password"),
                        modifier = Modifier.weight(1f),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                TorveTextField(
                    value = channelsState.newPlaylistEpgUrl,
                    onValueChange = channelsViewModel::setNewPlaylistEpgUrl,
                    label = ds("EPG URL"),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TorveGhostButton(
                        text = if (channelsState.isCheckingEpg) ds("Checking EPG...") else ds("Check EPG"),
                        onClick = channelsViewModel::checkNewPlaylistEpgUrl,
                        enabled = !channelsState.isCheckingEpg && channelsState.newPlaylistEpgUrl.isNotBlank(),
                    )
                    if (channelsState.isCheckingEpg) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                channelsState.epgCheckMessage?.let { message ->
                    TorveBanner(
                        title = if (channelsState.epgCheckSuccess == true) ds("EPG check passed") else ds("EPG check failed"),
                        description = message,
                        tone = if (channelsState.epgCheckSuccess == true) TorveBannerTone.Success else TorveBannerTone.Error,
                    )
                }
            } else {
                TorveTextField(
                    value = channelsState.newPlaylistUrl,
                    onValueChange = channelsViewModel::setNewPlaylistUrl,
                    label = ds("M3U URL"),
                    modifier = Modifier.fillMaxWidth(),
                )
                TorveTextField(
                    value = channelsState.newPlaylistEpgUrl,
                    onValueChange = channelsViewModel::setNewPlaylistEpgUrl,
                    label = ds("EPG URL"),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TorveGhostButton(
                        text = if (channelsState.isCheckingEpg) ds("Checking EPG...") else ds("Check EPG"),
                        onClick = channelsViewModel::checkNewPlaylistEpgUrl,
                        enabled = !channelsState.isCheckingEpg && channelsState.newPlaylistEpgUrl.isNotBlank(),
                    )
                    if (channelsState.isCheckingEpg) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                channelsState.epgCheckMessage?.let { message ->
                    TorveBanner(
                        title = if (channelsState.epgCheckSuccess == true) ds("EPG check passed") else ds("EPG check failed"),
                        description = message,
                        tone = if (channelsState.epgCheckSuccess == true) TorveBannerTone.Success else TorveBannerTone.Error,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TorvePrimaryButton(
                    text = if (channelsState.isAddingPlaylist) addingLabel else addPlaylistLabel,
                    onClick = channelsViewModel::addPlaylist,
                    enabled = !channelsState.isAddingPlaylist,
                )
                if (channelsState.isAddingPlaylist) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = formatPlaylistProgress(channelsState.addPlaylistProgress),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            channelsState.error?.let {
                TorveBanner(title = ds("Playlist error"), description = it, tone = TorveBannerTone.Error)
            }
        }

        TorveSectionCard(
            title = ds("Saved Playlists"),
            supportingText = ds("Select, refresh, and remove live TV playlists from desktop."),
        ) {
            if (channelsState.playlists.isEmpty()) {
                TorvePlaceholderState(
                    title = ds("No playlists saved"),
                    description = ds("Add an M3U or Xtream source above to populate Live TV."),
                )
            } else {
                channelsState.playlists.forEach { playlist ->
                    val isActive = channelsState.selectedPlaylistId == playlist.id
                    PlaylistRow(
                        playlist = playlist,
                        epgState = if (isActive) channelsState.epgState else null,
                        selected = isActive,
                        isRefreshing = isActive && channelsState.isLoadingChannels,
                        onSelect = { channelsViewModel.selectPlaylist(playlist.id) },
                        onRefresh = {
                            channelsViewModel.selectPlaylist(playlist.id)
                            channelsViewModel.refreshPlaylist()
                        },
                        onRemove = { channelsViewModel.removePlaylist(playlist.id) },
                    )
                }
            }
        }

        val refreshEpgLabel = ds("Refresh EPG")
        TorveSectionCard(
            title = ds("EPG Status"),
            supportingText = ds("EPG loading now starts automatically for the selected playlist and can be retried here."),
        ) {
            if (selectedPlaylistForEpg != null) {
                TorveTextField(
                    value = selectedPlaylistEpgDraft,
                    onValueChange = { selectedPlaylistEpgDraft = it },
                    label = ds("EPG URL"),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TorvePrimaryButton(
                        text = ds("Save"),
                        onClick = {
                            channelsViewModel.updatePlaylistEpgUrl(
                                playlistId = selectedPlaylistForEpg.id,
                                epgUrl = selectedPlaylistEpgDraft,
                            )
                        },
                    )
                    TorveGhostButton(
                        text = if (channelsState.isCheckingEpg) ds("Checking EPG...") else ds("Check EPG"),
                        onClick = { channelsViewModel.checkEpgUrl(selectedPlaylistEpgDraft) },
                        enabled = !channelsState.isCheckingEpg && selectedPlaylistEpgDraft.isNotBlank(),
                    )
                    TorveGhostButton(
                        text = ds("Clear"),
                        onClick = {
                            selectedPlaylistEpgDraft = ""
                            channelsViewModel.updatePlaylistEpgUrl(selectedPlaylistForEpg.id, "")
                        },
                    )
                }
                channelsState.epgCheckMessage?.let { message ->
                    TorveBanner(
                        title = if (channelsState.epgCheckSuccess == true) ds("EPG check passed") else ds("EPG check failed"),
                        description = message,
                        tone = if (channelsState.epgCheckSuccess == true) TorveBannerTone.Success else TorveBannerTone.Error,
                    )
                }
            }
            TorveListRow(
                title = selectedPlaylist?.name ?: ds("No playlist selected"),
                subtitle = describeEpgState(channelsState.epgState),
                trailing = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TorveBadge(
                            text = epgBadgeLabel(channelsState.epgState),
                            tone = epgBadgeTone(channelsState.epgState),
                        )
                        if (channelsState.epgState is EpgState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        TorveGhostButton(
                            text = refreshEpgLabel,
                            onClick = { channelsViewModel.ensureEpgLoaded(forceRefresh = true) },
                            enabled = channelsState.selectedPlaylistId != null &&
                                channelsState.epgState !is EpgState.Loading,
                        )
                    }
                },
            )
            channelsState.guideError?.takeIf { it.isNotBlank() }?.let { message ->
                TorveBanner(
                    title = ds("EPG issue"),
                    description = message,
                    tone = TorveBannerTone.Error,
                )
            }
        }

        val showAllLabel = ds("Show all")
        val hideAllLabel = ds("Hide all")
        val allHiddenLabel = ds("All hidden")
        val allVisibleLabel = ds("All visible")
        val showLabel = ds("Show")
        val hideLabel = ds("Hide")
        val hiddenBadge = ds("Hidden")
        val visibleBadge = ds("Visible")
        val ungroupedLabel = ds("Ungrouped")
        TorveSectionCard(
            title = ds("Manage Channel Lists"),
            supportingText = ds("Pick the IPTV category lists you want to keep. Country codes are shown ahead of each list when detected."),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TorveGhostButton(
                    text = ds("Show All Lists"),
                    onClick = channelsViewModel::showAllCategories,
                    enabled = channelsState.allCategories.isNotEmpty(),
                )
                TorveGhostButton(
                    text = ds("Hide All Lists"),
                    onClick = channelsViewModel::hideAllCategories,
                    enabled = channelsState.allCategories.isNotEmpty(),
                )
            }
            if (channelsState.allCategories.isEmpty()) {
                TorvePlaceholderState(
                    title = ds("No channel lists loaded"),
                    description = ds("Select a playlist and let desktop load the IPTV category catalog first."),
                )
            } else {
                // Group by country indicator - IPTV providers often ship
                // hundreds of categories (DE: News, DE: Sports, AT: News,
                // ...). Flat rendering scrolls forever, so collapse into
                // per-country sections with the count visible. Categories
                // without a code fall under "Other" at the bottom.
                val grouped = remember(channelsState.allCategories) {
                    channelsState.allCategories
                        .groupBy { c ->
                            c.countryCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: ""
                        }
                        .toList()
                        .sortedWith(
                            compareBy(
                                // Empty country code (Other) goes last.
                                { it.first.isEmpty() },
                                { it.first },
                            ),
                        )
                }
                var expandedCountries by remember(grouped) { mutableStateOf(setOf<String>()) }
                val otherLabel = ds("Other")
                grouped.forEach { (countryCode, categories) ->
                    val countryLabel = if (countryCode.isEmpty()) otherLabel else countryCode
                    val isExpanded = countryCode in expandedCountries
                    val totalChannels = categories.sumOf { it.channelCount }
                    // Group-level hidden state - drives the Hide-All /
                    // Show-All button label and the per-group status
                    // badge. Mixed = some hidden, some visible.
                    val hiddenInGroup = categories.count { it.name in channelsState.hiddenCategories }
                    val groupAllHidden = hiddenInGroup == categories.size && categories.isNotEmpty()
                    val groupAllVisible = hiddenInGroup == 0
                    TorveListRow(
                        title = countryLabel,
                        subtitle = buildString {
                            append("${categories.size} lists • $totalChannels channels")
                            if (!groupAllVisible && !groupAllHidden) {
                                append(" • $hiddenInGroup hidden")
                            }
                        },
                        onClick = {
                            expandedCountries = if (isExpanded) {
                                expandedCountries - countryCode
                            } else {
                                expandedCountries + countryCode
                            }
                        },
                        trailing = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TorveBadge(
                                    text = when {
                                        groupAllHidden -> allHiddenLabel
                                        groupAllVisible -> allVisibleLabel
                                        else -> "$hiddenInGroup hidden"
                                    },
                                    tone = when {
                                        groupAllHidden -> TorveBadgeTone.Warning
                                        groupAllVisible -> TorveBadgeTone.Success
                                        else -> TorveBadgeTone.Accent
                                    },
                                )
                                // Bulk toggle for the entire country
                                // group. Clicking does NOT expand the
                                // group - keeps the click target tight
                                // so the user can flip a group on/off
                                // without scrolling.
                                TorveGhostButton(
                                    text = if (groupAllHidden) showAllLabel else hideAllLabel,
                                    onClick = {
                                        if (groupAllHidden) {
                                            channelsViewModel.showCountryCategories(countryCode)
                                        } else {
                                            channelsViewModel.hideCountryCategories(countryCode)
                                        }
                                    },
                                )
                                Text(
                                    text = if (isExpanded) "▼" else "▶",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        },
                    )
                    if (isExpanded) {
                        Column(
                            modifier = Modifier.padding(start = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            categories.forEach { category ->
                                val hidden = category.name in channelsState.hiddenCategories
                                TorveListRow(
                                    title = category.name.takeIf { it.isNotBlank() } ?: ungroupedLabel,
                                    subtitle = "${category.channelCount} channels",
                                    selected = channelsState.selectedGroup == category.name,
                                    onClick = { channelsViewModel.loadCategoryChannels(category.name) },
                                    trailing = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TorveBadge(
                                                text = if (hidden) hiddenBadge else visibleBadge,
                                                tone = if (hidden) TorveBadgeTone.Warning else TorveBadgeTone.Success,
                                            )
                                            TorveGhostButton(
                                                text = if (hidden) showLabel else hideLabel,
                                                onClick = { channelsViewModel.toggleHiddenCategory(category.name) },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        TorveSectionCard(
            title = ds("Manage Channels"),
            supportingText = ds("Select a list above, then hide the individual channels you do not want in Live TV."),
        ) {
            when {
                channelsState.selectedGroup == null -> {
                    TorvePlaceholderState(
                        title = ds("No list selected"),
                        description = ds("Select a channel list from the section above to manage its channels."),
                    )
                }

                channelsState.categoryChannels.isEmpty() -> {
                    TorvePlaceholderState(
                        title = ds("No channels loaded"),
                        description = "Desktop is still loading channels for ${channelsState.selectedGroup}.",
                    )
                }

                else -> {
                    channelsState.categoryChannels.forEach { enriched ->
                        val channel = enriched.channel
                        val hidden = channelIdentityCandidates(channel).any(channelsState.hiddenChannels::contains)
                        TorveListRow(
                            title = channel.name,
                            subtitle = buildString {
                                append(formatCategoryLabel(channel.groupTitle, channel.tvgCountry))
                                enriched.currentProgramme?.title?.takeIf { it.isNotBlank() }?.let {
                                    append(" • Now: ")
                                    append(it)
                                }
                                enriched.nextProgramme?.title?.takeIf { it.isNotBlank() }?.let {
                                    append(" • Next: ")
                                    append(it)
                                }
                            },
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TorveBadge(
                                        text = if (hidden) hiddenBadge else visibleBadge,
                                        tone = if (hidden) TorveBadgeTone.Warning else TorveBadgeTone.Success,
                                    )
                                    TorveGhostButton(
                                        text = if (hidden) showLabel else hideLabel,
                                        onClick = { channelsViewModel.toggleHiddenChannel(stableChannelId(channel)) },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KodiHostRow(
    host: KodiHost,
    testResult: Boolean?,
    onTest: () -> Unit,
    onRemove: () -> Unit,
) {
    TorveListRow(
        title = host.name,
        subtitle = "${host.ip}:${host.port}",
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                testResult?.let {
                    TorveBadge(
                        text = if (it) "Reachable" else "Offline",
                        tone = if (it) TorveBadgeTone.Success else TorveBadgeTone.Warning,
                    )
                }
                TorveGhostButton(text = "Test", onClick = onTest)
                TorveGhostButton(text = "Remove", onClick = onRemove)
            }
        },
    )
}

@Composable
private fun PlaylistRow(
    playlist: ChannelPlaylist,
    epgState: EpgState?,
    selected: Boolean,
    isRefreshing: Boolean,
    onSelect: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    val activeBadge = ds("Active")
    val refreshLabel = ds("Refresh")
    val refreshingLabel = ds("Refreshing...")
    val removeLabel = ds("Remove")
    TorveListRow(
        title = playlist.name,
        subtitle = buildString {
            append(if (playlist.type == PlaylistType.XTREAM) "Xtream" else "M3U")
            append(" • ${playlist.channelCount} channels")
            playlist.lastUpdated?.let { append(" • Updated ${formatTimestamp(it)}") }
            playlist.epgUrl?.takeIf { it.isNotBlank() }?.let { append(" • EPG configured") }
        },
        selected = selected,
        onClick = onSelect,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) {
                    TorveBadge(activeBadge, tone = TorveBadgeTone.Accent)
                }
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                TorveGhostButton(
                    text = if (isRefreshing) refreshingLabel else refreshLabel,
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                )
                TorveGhostButton(text = removeLabel, onClick = onRemove)
            }
        },
    )
}

@Composable
private fun MpvInstallStatusRow() {
    var token by remember { mutableStateOf(0) }
    val result = remember(token) { MpvRuntimeLocator.discover() }
    val tone = if (result.found) TorveBadgeTone.Success else TorveBadgeTone.Warning
    val title = if (result.found) "libmpv detected" else "libmpv not found"
    val detail = result.mpvDirectory ?: result.diagnosticMessage
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TorveBadge(text = title, tone = tone)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = TorveDesktopThemeTokens.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TorveGhostButton(
                text = "Re-scan",
                onClick = { token++ },
            )
            if (!result.found) {
                TorveGhostButton(
                    text = "Open mpv.io",
                    onClick = {
                        runCatching {
                            java.awt.Desktop.getDesktop().browse(java.net.URI("https://mpv.io"))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PreferenceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    TorveListRow(
        title = title,
        subtitle = subtitle,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SelectorBlock(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TorveDesktopThemeTokens.colors.textPrimary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                TorveFilterChip(
                    text = optionLabel(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SelectorBlock(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    allowNoSelection: Boolean,
    noSelectionLabel: String,
    onSelectNullable: (T?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TorveDesktopThemeTokens.colors.textPrimary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (allowNoSelection) {
                TorveFilterChip(
                    text = noSelectionLabel,
                    selected = selected == null,
                    onClick = { onSelectNullable(null) },
                )
            }
            options.forEach { option ->
                TorveFilterChip(
                    text = optionLabel(option),
                    selected = option == selected,
                    onClick = { onSelectNullable(option) },
                )
            }
        }
    }
}

@Composable
private fun DeviceCodeBanner(
    title: String,
    userCode: String,
    verificationUrl: String,
    waiting: Boolean,
) {
    val colors = TorveDesktopThemeTokens.colors
    TorveSectionCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Code, large + bold so the user can read it from across
            // the room while typing it into the browser.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Code:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = userCode,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            // URL on its own line so it wraps cleanly. Slightly muted
            // colour because the action buttons below are the primary
            // affordance, not the URL text itself.
            Text(
                text = verificationUrl,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )

            // Three actions: open the URL in browser, copy the code,
            // copy the URL itself. Same belt-and-suspenders pattern
            // Panda's OAuth code uses elsewhere; lets the user
            // recover when "Open in browser" lands in the wrong
            // browser profile or when they want to scan a QR.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TorvePrimaryButton(
                    text = "Open in browser",
                    onClick = {
                        runCatching {
                            java.awt.Desktop.getDesktop().browse(java.net.URI(verificationUrl))
                        }
                    },
                )
                TorveSecondaryButton(
                    text = "Copy code",
                    onClick = {
                        val sel = java.awt.datatransfer.StringSelection(userCode)
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                    },
                )
                TorveSecondaryButton(
                    text = "Copy link",
                    onClick = {
                        val sel = java.awt.datatransfer.StringSelection(verificationUrl)
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                    },
                )
            }

            if (waiting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                    Text(
                        text = "Waiting for confirmation",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

private fun categoryBadge(
    category: SettingsCategory,
    settingsState: SettingsUiState,
    installedAddons: List<InstalledAddon>,
    channelsState: ChannelsUiState,
): String? {
    return when (category) {
        SettingsCategory.SOURCES -> null
        SettingsCategory.CUSTOMIZATION -> settingsState.themeMode.name
        SettingsCategory.ACCOUNT -> settingsState.regionCode
        SettingsCategory.INTEGRATIONS -> {
            val connected = listOf(
                settingsState.debridConnected,
                settingsState.traktConnected,
                settingsState.simklConnected,
                settingsState.plexConnected,
            ).count { it }
            "$connected"
        }

        SettingsCategory.ADDONS -> installedAddons.size.toString()
        SettingsCategory.PLAYLISTS -> channelsState.playlists.size.toString()
        SettingsCategory.RECORDING -> null
        SettingsCategory.ABOUT -> null
    }
}

@Composable
private fun RecordingSection() {
    val prefsRepo = remember {
        org.koin.mp.KoinPlatform.getKoin()
            .get<com.torve.domain.repository.PreferencesRepository>()
    }
    var prefs by remember {
        mutableStateOf(com.torve.domain.recording.RecordingPreferences())
    }
    LaunchedEffect(Unit) {
        prefs = com.torve.domain.recording.RecordingPreferences.load(prefsRepo)
    }
    val scope = rememberCoroutineScope()
    fun save(next: com.torve.domain.recording.RecordingPreferences) {
        prefs = next
        scope.launch {
            com.torve.domain.recording.RecordingPreferences.save(prefsRepo, next)
        }
    }
    val durationOptions = listOf(
        30 to "30 minutes",
        60 to "1 hour",
        120 to "2 hours",
        180 to "3 hours",
        240 to "4 hours",
        0 to "Until I stop",
    )
    val rollOptions = listOf(0, 1, 2, 5, 10, 15, 30)
    val concurrentOptions = listOf(1, 2, 3, 4, 5)
    var durationOpen by remember { mutableStateOf(false) }
    var preRollOpen by remember { mutableStateOf(false) }
    var postRollOpen by remember { mutableStateOf(false) }
    var concurrentOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TorveSectionCard(
            title = ds("Recording Defaults"),
            supportingText = "Used by the Live TV record button and EPG record menu. " +
                "Pre-roll starts the recording N minutes early; post-roll keeps it running " +
                "N minutes past the scheduled end. Set higher post-roll for sports.",
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = "Default record duration:",
                    modifier = Modifier.weight(1f),
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
                Box {
                    TorveGhostButton(
                        text = durationOptions.firstOrNull { it.first == prefs.defaultDurationMin }
                            ?.second ?: "${prefs.defaultDurationMin} min",
                        onClick = { durationOpen = true },
                    )
                    TorveDropdownScaffold(
                        expanded = durationOpen,
                        onDismissRequest = { durationOpen = false },
                        items = durationOptions.map { (min, label) ->
                            label to {
                                durationOpen = false
                                save(prefs.copy(defaultDurationMin = min))
                            }
                        },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = "Start N minutes early (pre-roll):",
                    modifier = Modifier.weight(1f),
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
                Box {
                    TorveGhostButton(
                        text = "${prefs.preRollMin} min",
                        onClick = { preRollOpen = true },
                    )
                    TorveDropdownScaffold(
                        expanded = preRollOpen,
                        onDismissRequest = { preRollOpen = false },
                        items = rollOptions.map { min ->
                            "$min min" to {
                                preRollOpen = false
                                save(prefs.copy(preRollMin = min))
                            }
                        },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = "Keep recording N minutes after end (post-roll):",
                    modifier = Modifier.weight(1f),
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
                Box {
                    TorveGhostButton(
                        text = "${prefs.postRollMin} min",
                        onClick = { postRollOpen = true },
                    )
                    TorveDropdownScaffold(
                        expanded = postRollOpen,
                        onDismissRequest = { postRollOpen = false },
                        items = rollOptions.map { min ->
                            "$min min" to {
                                postRollOpen = false
                                save(prefs.copy(postRollMin = min))
                            }
                        },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = "Max concurrent recordings:",
                    modifier = Modifier.weight(1f),
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
                Box {
                    TorveGhostButton(
                        text = "${prefs.maxConcurrent}",
                        onClick = { concurrentOpen = true },
                    )
                    TorveDropdownScaffold(
                        expanded = concurrentOpen,
                        onDismissRequest = { concurrentOpen = false },
                        items = concurrentOptions.map { n ->
                            "$n" to {
                                concurrentOpen = false
                                save(prefs.copy(maxConcurrent = n))
                            }
                        },
                    )
                }
            }
        }
        TorveSectionCard(
            title = ds("Behavior"),
            supportingText = "How partial recordings and stops are handled.",
        ) {
            TorveListRow(
                title = "Partial recordings preserved",
                subtitle = "When you press Stop on an in-progress recording, the bytes " +
                    "already on disk are saved as a Completed recording (instead of being " +
                    "discarded as Cancelled).",
            )
        }
    }
}

@Composable
private fun AboutSection(
    settingsViewModel: SettingsViewModel,
) {
    val settingsState by settingsViewModel.state.collectAsState()
    val releaseInfo = remember { DesktopReleaseInfo.current() }
    val vlcResult = remember { VlcRuntimeLocator.discover() }
    val dataDir = remember { desktopDataDir().absolutePath }
    val osInfo = remember {
        "${System.getProperty("os.name")} ${System.getProperty("os.arch")} (${System.getProperty("os.version")})"
    }
    val javaVersion = remember {
        "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"
    }

    val foundLabel = ds("Found")
    val notFoundLabel = ds("Not found")
    val missingLabel = ds("Missing")
    val unknownLabel = ds("Unknown")
    val enabledLabel = ds("Enabled")
    val disabledLabel = ds("Disabled")
    val notSetLabel = ds("Not set")
    val yesLabel = ds("Yes")
    val noLabel = ds("No")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TorveSectionCard(
            title = releaseInfo.appName,
            supportingText = releaseInfo.description,
        ) {
            TorveListRow(title = ds("Version"), subtitle = releaseInfo.version)
            TorveListRow(title = "Channel", subtitle = releaseInfo.channel)
            TorveListRow(title = "Vendor", subtitle = releaseInfo.vendor)
        }

        TorveSectionCard(
            title = ds("Runtime"),
            supportingText = ds("Platform and playback engine information."),
        ) {
            TorveListRow(title = ds("OS"), subtitle = osInfo)
            TorveListRow(title = ds("Java Runtime"), subtitle = javaVersion)
            TorveListRow(
                title = ds("VLC Runtime"),
                subtitle = if (vlcResult.found) vlcResult.vlcDirectory ?: foundLabel else notFoundLabel,
                trailing = {
                    TorveBadge(
                        text = if (vlcResult.found) foundLabel else missingLabel,
                        tone = if (vlcResult.found) TorveBadgeTone.Success else TorveBadgeTone.Warning,
                    )
                },
            )
            if (vlcResult.found) {
                TorveListRow(
                    title = ds("VLC Source"),
                    subtitle = vlcResult.discoverySource ?: unknownLabel,
                )
            }
            if (!vlcResult.found) {
                TorveBanner(
                    title = ds("VLC not found"),
                    description = vlcResult.diagnosticMessage,
                    tone = TorveBannerTone.Warning,
                )
            }
        }

        TorveSectionCard(
            title = ds("Effective Playback Settings"),
            supportingText = ds("Current runtime playback configuration snapshot."),
        ) {
            TorveListRow(title = ds("Seek Step"), subtitle = "${settingsState.seekStepSeconds}s")
            TorveListRow(title = ds("Subtitles by Default"), subtitle = if (settingsState.subtitlesEnabledByDefault) enabledLabel else disabledLabel)
            TorveListRow(title = ds("Preferred Subtitle Language"), subtitle = settingsState.preferredSubtitleLanguage.ifBlank { notSetLabel })
            TorveListRow(title = ds("Preferred Audio Language"), subtitle = settingsState.preferredAudioLanguage.ifBlank { notSetLabel })
            TorveListRow(title = ds("Remember Volume"), subtitle = if (settingsState.rememberVolume) "$yesLabel (${settingsState.lastVolume}%)" else noLabel)
        }

        TorveSectionCard(
            title = ds("Storage"),
            supportingText = ds("Local data and configuration paths."),
        ) {
            TorveListRow(title = ds("Data Directory"), subtitle = dataDir)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TorveGhostButton(
                    text = ds("Open Data Folder"),
                    onClick = {
                        runCatching {
                            java.awt.Desktop.getDesktop().open(java.io.File(dataDir))
                        }
                    },
                )
            }
        }


        val onLabel = ds("On")
        val offLabel = ds("Off")
        TorveSectionCard(
            title = ds("Diagnostics & Updates"),
            supportingText = ds("Crash reporting and the in-app update channel are configured via environment variables at launch. This section shows the live state - set these on the launcher script or system env to enable."),
        ) {
            // Sentry
            val sentryEnabled = System.getenv(com.torve.desktop.diagnostics.SentryBootstrap.DSN_ENV)
                ?.takeIf { it.isNotBlank() } != null
            TorveListRow(
                title = ds("Crash reporting (Sentry)"),
                subtitle = if (sentryEnabled) ds("Enabled") else ds("Disabled - DSN not set"),
                trailing = {
                    TorveBadge(
                        text = if (sentryEnabled) onLabel else offLabel,
                        tone = if (sentryEnabled) TorveBadgeTone.Success else TorveBadgeTone.Neutral,
                    )
                },
            )
            TorveListRow(
                title = ds("Sentry env var"),
                subtitle = com.torve.desktop.diagnostics.SentryBootstrap.DSN_ENV,
                trailing = {
                    TorveGhostButton(
                        text = ds("Copy"),
                        onClick = {
                            runCatching {
                                val sel = java.awt.datatransfer.StringSelection(
                                    com.torve.desktop.diagnostics.SentryBootstrap.DSN_ENV,
                                )
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                            }
                        },
                    )
                },
            )

            // Update channel
            val updateRepo = System.getenv(com.torve.desktop.updates.UpdateChecker.REPO_ENV)?.takeIf { it.isNotBlank() }
            val updateFeed = System.getenv(com.torve.desktop.updates.UpdateChecker.FEED_ENV)?.takeIf { it.isNotBlank() }
            val updateChannel = updateFeed ?: updateRepo?.let { "github.com/$it" }
            TorveListRow(
                title = ds("Update channel"),
                subtitle = updateChannel ?: ds("Disabled - no repo or feed configured"),
                trailing = {
                    TorveBadge(
                        text = if (updateChannel != null) onLabel else offLabel,
                        tone = if (updateChannel != null) TorveBadgeTone.Success else TorveBadgeTone.Neutral,
                    )
                },
            )
            TorveListRow(
                title = ds("Update env vars"),
                subtitle = "${com.torve.desktop.updates.UpdateChecker.REPO_ENV} (owner/name) or " +
                    "${com.torve.desktop.updates.UpdateChecker.FEED_ENV} (full URL - appcast XML or GitHub JSON)",
            )

            // Auto-check toggle. Persisted in Properties under
            // desktopDataDir(). Reads from the file each composition for
            // simplicity - switching it off doesn't have an immediate
            // effect (the launch-time check has already fired) but it
            // takes effect on the next launch.
            var autoCheck by remember {
                mutableStateOf(com.torve.desktop.updates.UpdateCheckerPreferences.isAutoCheckEnabled())
            }
            TorveListRow(
                title = ds("Check on launch"),
                subtitle = ds("Run the update check automatically when Torve starts."),
                trailing = {
                    androidx.compose.material3.Switch(
                        checked = autoCheck,
                        onCheckedChange = { v ->
                            autoCheck = v
                            com.torve.desktop.updates.UpdateCheckerPreferences.setAutoCheckEnabled(v)
                        },
                    )
                },
            )

            // Track local "in progress" state separately from the global
            // checker's StateFlow — `check()` doesn't expose an in-flight
            // signal, and the StateFlow only reflects the *result*. Without
            // this the button used to fire-and-forget with zero feedback;
            // caught by B4 smoke 2026-05-03 as a UX bug.
            val updaterState by (com.torve.desktop.globalUpdateChecker?.state
                ?: kotlinx.coroutines.flow.MutableStateFlow(
                    com.torve.desktop.updates.UpdateChecker.Result.UpToDate,
                ))
                .collectAsState()
            var checkInProgress by remember { mutableStateOf(false) }
            var lastCheckedAt by remember { mutableStateOf<Long?>(null) }
            val checkScope = rememberCoroutineScope()
            val checkerEnabled = com.torve.desktop.globalUpdateChecker?.isEnabled == true
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TorveGhostButton(
                    text = if (checkInProgress) ds("Checking…") else ds("Check for updates now"),
                    enabled = !checkInProgress && checkerEnabled,
                    onClick = {
                        val checker = com.torve.desktop.globalUpdateChecker ?: return@TorveGhostButton
                        checkInProgress = true
                        checkScope.launch {
                            try {
                                checker.check()
                            } finally {
                                lastCheckedAt = System.currentTimeMillis()
                                checkInProgress = false
                            }
                        }
                    },
                )
                val statusText: String? = when {
                    !checkerEnabled -> "Disabled — set TORVE_UPDATE_REPO or TORVE_UPDATE_FEED."
                    checkInProgress -> null  // button label already conveys this
                    lastCheckedAt == null -> null  // never run yet from this button
                    else -> when (val s = updaterState) {
                        is com.torve.desktop.updates.UpdateChecker.Result.Available ->
                            "Update available: ${s.info.tag}"
                        is com.torve.desktop.updates.UpdateChecker.Result.UpToDate ->
                            "You're on the latest version."
                        is com.torve.desktop.updates.UpdateChecker.Result.Failed ->
                            "Check failed: ${s.reason}"
                    }
                }
                if (statusText != null) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = TorveDesktopThemeTokens.colors.textSecondary,
                    )
                }
            }
        }

        // EPG reminders that survive restarts. Set from the Live TV →
        // Guide → programme context menu; managed (cancelled / inspected)
        // here.
        run {
            val reminders by com.torve.desktop.globalReminderStore.state.collectAsState()
            val activeReminders = remember(reminders) {
                val now = System.currentTimeMillis()
                reminders.filter { it.startMs > now }.sortedBy { it.startMs }
            }
            TorveSectionCard(
                title = ds("EPG reminders"),
                supportingText = "Programmes you've asked Torve to remind you about. " +
                    "Reminders fire as a tray notification one minute before air time.",
            ) {
                if (activeReminders.isEmpty()) {
                    Text(
                        text = "No active reminders. Open the Live TV Guide, click any " +
                            "future programme, and choose 🔔 Set reminder.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TorveDesktopThemeTokens.colors.textSecondary,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        activeReminders.forEach { reminder ->
                            ReminderRow(reminder = reminder)
                        }
                    }
                }
            }
        }

        TorveSectionCard(
            title = ds("Maintenance"),
            supportingText = ds("Cache and data management."),
        ) {
            var exportStatus by remember { mutableStateOf<String?>(null) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TorveGhostButton(
                    text = ds("Clear Metadata Cache"),
                    onClick = settingsViewModel::clearCache,
                )
                TorveGhostButton(
                    text = ds("Export diagnostics..."),
                    onClick = {
                        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                        kotlinx.coroutines.GlobalScope.launch {
                            val target = com.torve.desktop.diagnostics.DiagnosticsExporter.defaultTargetFile()
                            runCatching {
                                com.torve.desktop.diagnostics.DiagnosticsExporter.exportTo(target)
                            }.onSuccess {
                                exportStatus = "Exported to ${target.absolutePath}"
                                runCatching {
                                    java.awt.Desktop.getDesktop().open(target.parentFile)
                                }
                            }.onFailure { t ->
                                exportStatus = "Export failed: ${t.message}"
                            }
                        }
                    },
                )
            }
            exportStatus?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
            }
        }

        // ── Legal & Support (Prompt 12 hardening) ──
        // Required by every store policy and by general public-release
        // hygiene. URLs are sourced from the shared LegalUrls module so
        // a copy change is one PR, not seven.
        val openLabel = ds("Open")
        val bugReportScope = rememberCoroutineScope()
        var bugReportStatus by remember { mutableStateOf<String?>(null) }
        TorveSectionCard(
            title = ds("Legal & support"),
            supportingText = ds("Privacy, terms, and how to reach us."),
        ) {
            TorveListRow(
                title = ds("Privacy Policy"),
                subtitle = com.torve.presentation.legal.LegalUrls.PRIVACY_POLICY,
                trailing = {
                    TorveGhostButton(
                        text = openLabel,
                        onClick = {
                            runCatching {
                                java.awt.Desktop.getDesktop().browse(
                                    java.net.URI(com.torve.presentation.legal.LegalUrls.PRIVACY_POLICY),
                                )
                            }
                        },
                    )
                },
            )
            TorveListRow(
                title = ds("Terms of Service"),
                subtitle = com.torve.presentation.legal.LegalUrls.TERMS_OF_SERVICE,
                trailing = {
                    TorveGhostButton(
                        text = openLabel,
                        onClick = {
                            runCatching {
                                java.awt.Desktop.getDesktop().browse(
                                    java.net.URI(com.torve.presentation.legal.LegalUrls.TERMS_OF_SERVICE),
                                )
                            }
                        },
                    )
                },
            )
            TorveListRow(
                title = ds("Help & support"),
                subtitle = com.torve.presentation.legal.LegalUrls.HELP,
                trailing = {
                    TorveGhostButton(
                        text = openLabel,
                        onClick = {
                            runCatching {
                                java.awt.Desktop.getDesktop().browse(
                                    java.net.URI(com.torve.presentation.legal.LegalUrls.HELP),
                                )
                            }
                        },
                    )
                },
            )
            TorveListRow(
                title = ds("Report a problem"),
                subtitle = ds("Create a redacted diagnostics zip and open a support email."),
                trailing = {
                    TorveGhostButton(
                        text = ds("Report"),
                        onClick = {
                            bugReportScope.launch {
                                bugReportStatus = "Preparing bug report..."
                                val exported = runCatching {
                                    val target = com.torve.desktop.diagnostics.DiagnosticsExporter.defaultTargetFile()
                                    com.torve.desktop.diagnostics.DiagnosticsExporter.exportTo(target)
                                }
                                val target = exported.getOrNull()
                                val body = desktopBugReportEmailBody(target)
                                runCatching {
                                    val selection = java.awt.datatransfer.StringSelection(body)
                                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                                }
                                val mailOpened = openDesktopBugReportEmail(body)
                                target?.parentFile?.let { folder ->
                                    runCatching { java.awt.Desktop.getDesktop().open(folder) }
                                }
                                bugReportStatus = when {
                                    exported.isFailure -> "Bug report email prepared, but diagnostics export failed: ${exported.exceptionOrNull()?.message.orEmpty()}"
                                    mailOpened -> "Support email opened. Diagnostics exported to ${target?.absolutePath.orEmpty()}"
                                    else -> "Bug report copied. Diagnostics exported to ${target?.absolutePath.orEmpty()}"
                                }
                            }
                        },
                    )
                },
            )
            bugReportStatus?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
            }
            TorveListRow(
                title = ds("Email support"),
                subtitle = com.torve.presentation.legal.LegalUrls.SUPPORT_EMAIL,
                trailing = {
                    TorveGhostButton(
                        text = ds("Email"),
                        onClick = {
                            runCatching {
                                java.awt.Desktop.getDesktop().mail(
                                    java.net.URI("mailto:${com.torve.presentation.legal.LegalUrls.SUPPORT_EMAIL}"),
                                )
                            }
                        },
                    )
                },
            )
        }

        // ── Account deletion (Prompt 12 hardening) ──
        // Store-policy required. The in-app DELETE is the primary path;
        // the web mirror exists for users without the app installed.
        var showDeleteAccountDialog by remember { mutableStateOf(false) }
        var deleteAccountStatus by remember { mutableStateOf<String?>(null) }
        TorveSectionCard(
            title = ds("Delete account"),
            supportingText = ds("Permanently remove your Torve account and associated data from our servers. This action cannot be undone."),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TorveGhostButton(
                    text = ds("Delete account..."),
                    onClick = { showDeleteAccountDialog = true },
                )
            }
            deleteAccountStatus?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
            }
        }
        if (showDeleteAccountDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDeleteAccountDialog = false },
                title = { Text(ds("Delete your Torve account?")) },
                text = {
                    Text(
                        ds("This permanently deletes your account, devices, watch history, playlists, and entitlements on the Torve servers. Local downloads on this computer are not affected."),
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showDeleteAccountDialog = false
                        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                        kotlinx.coroutines.GlobalScope.launch {
                            val authClient = org.koin.mp.KoinPlatform.getKoin()
                                .get<com.torve.data.auth.AuthClient>()
                            val result = authClient.deleteAccount()
                            deleteAccountStatus = if (result.success) {
                                "Account deleted. Restart Torve to sign in again."
                            } else {
                                "Could not delete account: ${result.error ?: "unknown error"}"
                            }
                        }
                    }) { Text(ds("Delete account")) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showDeleteAccountDialog = false }) {
                        Text(ds("Cancel"))
                    }
                },
            )
        }
    }
}

private fun formatTimestamp(value: Long): String {
    return runCatching {
        DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")
            .format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
    }.getOrElse { "recently" }
}

@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun formatPlaylistProgress(progress: PlaylistAddProgress?): String {
    if (progress == null) return "Fetching playlist..."
    return when (progress.phase) {
        PlaylistAddProgress.Phase.DOWNLOADING -> {
            val read = formatBytes(progress.bytesRead)
            val total = progress.totalBytes
            val label = ds("Downloading")
            if (total != null && total > 0) "$label $read / ${formatBytes(total)}"
            else "$label $read"
        }
        PlaylistAddProgress.Phase.PARSING -> ds("Parsing channels...")
        PlaylistAddProgress.Phase.SAVING -> ds("Saving to database...")
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun formatCategoryLabel(category: ChannelCategory): String =
    formatCategoryLabel(category.name, category.countryCode)

private fun formatCategoryLabel(
    categoryName: String?,
    countryCode: String?,
): String {
    val resolvedName = categoryName?.takeIf { it.isNotBlank() } ?: "Ungrouped"
    val code = countryCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
    return if (code != null) "[$code] $resolvedName" else resolvedName
}

private fun describeEpgState(state: EpgState): String = when (state) {
    EpgState.Loading -> "Loading programme guide data for the selected playlist."
    EpgState.NotConfigured -> "No EPG source is configured for the selected playlist."
    is EpgState.Loaded -> buildString {
        append("Loaded ${state.sourceProgrammeCount} programmes across ${state.sourceChannelCount} EPG channels")
        if (state.matchedChannelCount > 0 || state.unmatchedChannelCount > 0) {
            append(" • matched ${state.matchedChannelCount}, unmatched ${state.unmatchedChannelCount}")
        }
    }
    is EpgState.Error -> state.message.ifBlank { "EPG failed to load." }
}

private fun epgBadgeLabel(state: EpgState): String = when (state) {
    EpgState.Loading -> "EPG Loading"
    EpgState.NotConfigured -> "EPG Missing"
    is EpgState.Loaded -> "EPG Ready"
    is EpgState.Error -> "EPG Error"
}

private fun epgBadgeTone(state: EpgState): TorveBadgeTone = when (state) {
    EpgState.Loading -> TorveBadgeTone.Accent
    EpgState.NotConfigured -> TorveBadgeTone.Warning
    is EpgState.Loaded -> TorveBadgeTone.Success
    is EpgState.Error -> TorveBadgeTone.Warning
}

@androidx.compose.runtime.Composable
private fun ReminderRow(reminder: com.torve.desktop.reminders.StoredReminder) {
    val colors = TorveDesktopThemeTokens.colors
    val airsAt = remember(reminder.startMs) {
        java.time.format.DateTimeFormatter
            .ofPattern("EEE d MMM, HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(reminder.startMs))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(8.dp))
            .background(colors.cardSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = reminder.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = "${reminder.channelName}  •  $airsAt",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        TorveGhostButton(
            text = "Snooze 5m",
            onClick = {
                com.torve.desktop.globalReminderStore.snooze(
                    key = reminder.key,
                    additionalMs = 5L * 60_000L,
                )
            },
        )
        TorveGhostButton(
            text = "Cancel",
            onClick = { com.torve.desktop.globalReminderStore.remove(reminder.key) },
        )
    }
}

// ── Home shelf editor ──────────────────────────────────────────────

@Composable
private fun HomeShelfEditorCard(
    settingsState: SettingsUiState,
    homeViewModel: com.torve.presentation.home.HomeViewModel,
) {
    val sectionConfigs by homeViewModel.sectionConfigs.collectAsState()
    val ordered = remember(sectionConfigs) { sectionConfigs.sortedBy { it.order } }

    TorveSectionCard(
        title = "Customize Home",
        supportingText = "Reorder, hide, or assign a card style preset to each home shelf.",
    ) {
        if (ordered.isEmpty()) {
            Text(
                text = "Loading...",
                style = MaterialTheme.typography.bodyMedium,
                color = TorveDesktopThemeTokens.colors.textSecondary,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ordered.forEachIndexed { index, config ->
                    HomeShelfRow(
                        config = config,
                        isFirst = index == 0,
                        isLast = index == ordered.lastIndex,
                        cardStylePresets = settingsState.cardStylePresets,
                        onMoveUp = {
                            if (index > 0) {
                                val swapped = ordered.toMutableList().apply {
                                    val a = this[index - 1]
                                    val b = this[index]
                                    this[index - 1] = b.copy(order = a.order)
                                    this[index] = a.copy(order = b.order)
                                }
                                homeViewModel.updateSectionOrder(swapped)
                            }
                        },
                        onMoveDown = {
                            if (index < ordered.lastIndex) {
                                val swapped = ordered.toMutableList().apply {
                                    val a = this[index]
                                    val b = this[index + 1]
                                    this[index] = b.copy(order = a.order)
                                    this[index + 1] = a.copy(order = b.order)
                                }
                                homeViewModel.updateSectionOrder(swapped)
                            }
                        },
                        onToggleEnabled = { enabled ->
                            homeViewModel.toggleSection(config.section, enabled)
                        },
                        onSelectPreset = { presetId ->
                            homeViewModel.updateSectionPreset(config.section, presetId)
                        },
                    )
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TorveGhostButton(
                        text = "Reset to defaults",
                        onClick = { homeViewModel.resetSections() },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeShelfRow(
    config: com.torve.domain.model.HomeSectionConfig,
    isFirst: Boolean,
    isLast: Boolean,
    cardStylePresets: List<com.torve.domain.model.CardStylePreset>,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectPreset: (String?) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val title = config.customTitle?.takeIf { it.isNotBlank() } ?: config.section.defaultTitle

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.fieldSurface, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Reorder controls (compact column)
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ReorderArrowButton(
                up = true,
                enabled = !isFirst,
                onClick = onMoveUp,
            )
            ReorderArrowButton(
                up = false,
                enabled = !isLast,
                onClick = onMoveDown,
            )
        }

        // Title
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (config.enabled) colors.textPrimary else colors.textMuted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )

        // Preset dropdown
        HomeShelfPresetDropdown(
            presets = cardStylePresets,
            selectedPresetId = config.presetId,
            onSelect = onSelectPreset,
            enabled = config.enabled,
        )

        // Enable toggle
        Switch(
            checked = config.enabled,
            onCheckedChange = onToggleEnabled,
        )
    }
}

@Composable
private fun ReorderArrowButton(
    up: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Box(
        modifier = Modifier
            .size(width = 26.dp, height = 18.dp)
            .background(
                color = if (enabled) colors.cardSurface else colors.cardSurface.copy(alpha = 0.4f),
                shape = RoundedCornerShape(5.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = if (up) androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp
                else androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
            contentDescription = if (up) "Move up" else "Move down",
            tint = if (enabled) colors.textSecondary else colors.textMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun HomeShelfPresetDropdown(
    presets: List<com.torve.domain.model.CardStylePreset>,
    selectedPresetId: String?,
    onSelect: (String?) -> Unit,
    enabled: Boolean,
) {
    val colors = TorveDesktopThemeTokens.colors
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = remember(selectedPresetId, presets) {
        when {
            selectedPresetId == null || selectedPresetId == "default" -> "Default"
            else -> presets.firstOrNull { it.presetId == selectedPresetId }?.name ?: "Default"
        }
    }
    Box {
        Row(
            modifier = Modifier
                .background(colors.cardSurface, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) colors.textPrimary else colors.textMuted,
                maxLines = 1,
            )
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Default") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            presets.forEach { preset ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(preset.name) },
                    onClick = {
                        expanded = false
                        onSelect(preset.presetId)
                    },
                )
            }
        }
    }
}

// ── Card style preset editor ─────────────────────────────────────

@Composable
private fun CardStylePresetEditorCard(
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
) {
    val colors = TorveDesktopThemeTokens.colors
    val presets = settingsState.cardStylePresets
    var selectedPresetId by remember(presets.size) {
        mutableStateOf(
            settingsState.globalDefaultPresetId
                ?: presets.firstOrNull()?.presetId
                ?: "",
        )
    }
    val selectedPreset = presets.firstOrNull { it.presetId == selectedPresetId }
        ?: presets.firstOrNull()

    TorveSectionCard(
        title = "Card Style Presets",
        supportingText = "Edit the visual styles you can assign to home shelves. Synced across devices.",
    ) {
        if (selectedPreset == null) {
            Text(
                text = "No presets yet - create one to start editing.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Row {
                TorvePrimaryButton(
                    text = "Create default preset",
                    onClick = {
                        val id = settingsViewModel.createCardStylePreset(
                            "Default",
                            com.torve.domain.model.CardStyle(),
                        )
                        selectedPresetId = id
                    },
                )
            }
            return@TorveSectionCard
        }

        val style = selectedPreset.cardStyle
        val isDefault = settingsState.globalDefaultPresetId == selectedPreset.presetId
        val canDelete = !isDefault && !selectedPreset.isBuiltIn && presets.size > 1

        // Top row: preset selector + actions
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Preset",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CardStylePresetSelector(
                    presets = presets,
                    selectedPresetId = selectedPreset.presetId,
                    onSelect = { id -> id?.let { selectedPresetId = it } },
                )
                if (isDefault) {
                    Text(
                        text = "Default preset",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    TorveGhostButton(
                        text = "Set as default",
                        onClick = {
                            settingsViewModel.setDefaultCardStylePreset(selectedPreset.presetId)
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveGhostButton(
                    text = "+ New",
                    onClick = {
                        val newId = settingsViewModel.createCardStylePreset(
                            "Preset ${presets.size + 1}",
                            com.torve.domain.model.CardStyle(),
                        )
                        selectedPresetId = newId
                    },
                )
                TorveGhostButton(
                    text = "Duplicate",
                    onClick = {
                        settingsViewModel.duplicateCardStylePreset(selectedPreset.presetId)
                            ?.let { selectedPresetId = it }
                    },
                )
                if (canDelete) {
                    TorveGhostButton(
                        text = "Delete",
                        onClick = {
                            settingsViewModel.deleteCardStylePreset(selectedPreset.presetId)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "Editing: ${selectedPreset.name}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )

        SelectorBlock(
            label = "Size",
            options = com.torve.domain.model.CardSizePreset.entries,
            selected = style.size.preset,
            optionLabel = { it.label },
            onSelect = { p ->
                settingsViewModel.updateCardStylePreset(
                    selectedPreset.presetId,
                    style.copy(size = style.size.copy(preset = p)),
                )
            },
        )
        if (style.size.preset == com.torve.domain.model.CardSizePreset.CUSTOM) {
            SliderRow(
                label = "Custom width (dp)",
                value = style.size.customWidthDp.toFloat(),
                range = 80f..280f,
                valueLabel = "${style.size.customWidthDp}",
                onChange = { v ->
                    settingsViewModel.updateCardStylePreset(
                        selectedPreset.presetId,
                        style.copy(size = style.size.copy(customWidthDp = v.toInt())),
                    )
                },
            )
        }
        SelectorBlock(
            label = "Orientation",
            options = com.torve.domain.model.CardOrientation.entries,
            selected = style.size.orientation,
            optionLabel = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
            onSelect = { o ->
                settingsViewModel.updateCardStylePreset(
                    selectedPreset.presetId,
                    style.copy(size = style.size.copy(orientation = o)),
                )
            },
        )
        SliderRow(
            label = "Corner radius (dp)",
            value = style.appearance.cornerRadiusDp.toFloat(),
            range = 0f..32f,
            valueLabel = "${style.appearance.cornerRadiusDp}",
            onChange = { v ->
                settingsViewModel.updateCardStylePreset(
                    selectedPreset.presetId,
                    style.copy(appearance = style.appearance.copy(cornerRadiusDp = v.toInt())),
                )
            },
        )
        SliderRow(
            label = "Hover scale (%)",
            value = style.hover.scalePercent.toFloat(),
            range = 100f..130f,
            valueLabel = "${style.hover.scalePercent}",
            onChange = { v ->
                settingsViewModel.updateCardStylePreset(
                    selectedPreset.presetId,
                    style.copy(hover = style.hover.copy(scalePercent = v.toInt())),
                )
            },
        )
        SelectorBlock(
            label = "Title position",
            options = com.torve.domain.model.CardTitlePosition.entries,
            selected = style.appearance.titlePosition,
            optionLabel = { it.name.lowercase().replaceFirstChar(Char::uppercase).replace("_", " ") },
            onSelect = { p ->
                settingsViewModel.updateCardStylePreset(
                    selectedPreset.presetId,
                    style.copy(appearance = style.appearance.copy(titlePosition = p)),
                )
            },
        )
        SelectorBlock(
            label = "Watched indicator",
            options = com.torve.domain.model.WatchedIndicatorStyle.entries,
            selected = style.watched.style,
            optionLabel = { it.label },
            onSelect = { s ->
                settingsViewModel.updateCardStylePreset(
                    selectedPreset.presetId,
                    style.copy(watched = style.watched.copy(style = s)),
                )
            },
        )
        PreferenceToggleRow(
            title = "Hover effect",
            subtitle = "Scale and elevate the card while hovering.",
            checked = style.hover.enabled,
            onCheckedChange = { v ->
                settingsViewModel.updateCardStylePreset(
                    selectedPreset.presetId,
                    style.copy(hover = style.hover.copy(enabled = v)),
                )
            },
        )
        PreferenceToggleRow(
            title = "Dim watched cards",
            subtitle = "Reduce the brightness of cards you've already watched.",
            checked = style.watched.dimWatched,
            onCheckedChange = { v ->
                settingsViewModel.updateCardStylePreset(
                    selectedPreset.presetId,
                    style.copy(watched = style.watched.copy(dimWatched = v)),
                )
            },
        )
        PreferenceToggleRow(
            title = "Show year",
            subtitle = "Display the release year next to the title.",
            checked = style.appearance.showYear,
            onCheckedChange = { v ->
                settingsViewModel.updateCardStylePreset(
                    selectedPreset.presetId,
                    style.copy(appearance = style.appearance.copy(showYear = v)),
                )
            },
        )
        PreferenceToggleRow(
            title = "Show genre tags",
            subtitle = "Display genre badges on each card.",
            checked = style.appearance.showGenreTags,
            onCheckedChange = { v ->
                settingsViewModel.updateCardStylePreset(
                    selectedPreset.presetId,
                    style.copy(appearance = style.appearance.copy(showGenreTags = v)),
                )
            },
        )
        PreferenceToggleRow(
            title = "Show runtime",
            subtitle = "Display duration alongside year and rating.",
            checked = style.appearance.showRuntime,
            onCheckedChange = { v ->
                settingsViewModel.updateCardStylePreset(
                    selectedPreset.presetId,
                    style.copy(appearance = style.appearance.copy(showRuntime = v)),
                )
            },
        )
        PreferenceToggleRow(
            title = "Show type badge",
            subtitle = "Display a Movie/TV indicator on each card.",
            checked = style.appearance.showTypeBadge,
            onCheckedChange = { v ->
                settingsViewModel.updateCardStylePreset(
                    selectedPreset.presetId,
                    style.copy(appearance = style.appearance.copy(showTypeBadge = v)),
                )
            },
        )
    }
}

@Composable
private fun CardStylePresetSelector(
    presets: List<com.torve.domain.model.CardStylePreset>,
    selectedPresetId: String,
    onSelect: (String?) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    var expanded by remember { mutableStateOf(false) }
    val selected = presets.firstOrNull { it.presetId == selectedPresetId }
    Box {
        Row(
            modifier = Modifier
                .background(colors.cardSurface, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = selected?.name ?: "Select preset",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            presets.forEach { preset ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(preset.name) },
                    onClick = {
                        expanded = false
                        onSelect(preset.presetId)
                    },
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

// ── Sources / setup-intent hub ───────────────────────────────────

@Composable
private fun SourcesSection(
    setupIntentsViewModel: com.torve.presentation.setup.SetupIntentsViewModel?,
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
    onOpenPandaSetup: () -> Unit,
    onSwitchToCategory: (SettingsCategory) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    if (setupIntentsViewModel == null) {
        TorveSectionCard(
            title = ds("Setup & Sources"),
            supportingText = ds("Provider health is initializing..."),
        ) {}
        return
    }
    val summaries by setupIntentsViewModel.summaries.collectAsState()
    val rawEntries by setupIntentsViewModel.rawEntries.collectAsState()

    TorveSectionCard(
        title = ds("Setup & Sources"),
        supportingText = ds("Tell Torve what you have. Each path is tested as soon as you save credentials."),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            summaries.forEach { summary ->
                SetupIntentCard(
                    summary = summary,
                    onRunCheck = {
                        // Re-check every entry that belongs to this intent.
                        summary.entries.forEach { row ->
                            setupIntentsViewModel.refresh(row.providerKey)
                        }
                        // If nothing's configured yet, kick a full pass so
                        // any newly-supplied credentials get probed.
                        if (summary.entries.isEmpty()) {
                            setupIntentsViewModel.refreshAll()
                        }
                    },
                    onOpenSetup = {
                        when (summary.intent) {
                            // Debrid + Usenet both flow through Panda's
                            // guided setup. Routing DEBRID to the
                            // Account category was the legacy path
                            // (account-attached API keys); the modern
                            // path is the Panda OAuth/API-key wizard
                            // which also handles AllDebrid / Premiumize
                            // / TorBox under the same surface.
                            com.torve.presentation.setup.SetupIntent.DEBRID ->
                                onOpenPandaSetup()
                            com.torve.presentation.setup.SetupIntent.IPTV ->
                                onSwitchToCategory(SettingsCategory.PLAYLISTS)
                            com.torve.presentation.setup.SetupIntent.PLEX_JELLYFIN ->
                                onSwitchToCategory(SettingsCategory.INTEGRATIONS)
                            com.torve.presentation.setup.SetupIntent.USENET ->
                                onOpenPandaSetup()
                        }
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TorveGhostButton(
                    text = ds("Re-check everything"),
                    onClick = { setupIntentsViewModel.refreshAll() },
                )
            }
        }
    }

    DesktopStreamRulesSection(
        settingsState = settingsState,
        settingsViewModel = settingsViewModel,
    )

    // State hoisted out of the transfer card below so health rows can
    // route their "Transfer from another device" action into the same
    // collapsible receive surface.
    var transferReceiveOpen by remember { mutableStateOf(false) }

    // Separate flag for the MODAL dialog launched from a Provider
    // Health row's "Transfer from another device" button. The card
    // version sits far below on the page; flipping its inline expander
    // alone gives the user no visible feedback (the affordance is
    // off-screen). Modal dialog gives immediate feedback.
    var transferReceiveDialogOpen by remember { mutableStateOf(false) }

    // Hoisted up: transient banner surfaced when a Provider Health
    // repair button (or the Restore Setup "Set up manually" button)
    // routes the user away from one panel toward another. Declared
    // before the recovery card so its onClick handler can write to it.
    var redirectNotice by remember { mutableStateOf<String?>(null) }

    // ── Recovery card: shown above Provider Health when 2+ transferable
    // categories are missing local credentials. Single-source-of-truth
    // signal pulled from the shared recovery-state provider. ─────────
    val recoveryProvider: com.torve.presentation.providerhealth.ProviderHealthRecoveryStateProvider =
        remember {
            org.koin.java.KoinJavaComponent.get(
                com.torve.presentation.providerhealth.ProviderHealthRecoveryStateProvider::class.java,
            )
        }
    val completionNotifier: com.torve.presentation.transfer.TransferImportCompletionNotifier =
        remember {
            org.koin.java.KoinJavaComponent.get(
                com.torve.presentation.transfer.TransferImportCompletionNotifier::class.java,
            )
        }
    val lastImportEpochMs by completionNotifier.lastImportEpochMs.collectAsState()
    var recoverySnap by remember {
        mutableStateOf<com.torve.presentation.providerhealth.ProviderHealthRecoverySnapshot?>(null)
    }
    // Recompute when health rows change OR a new import lands - both
    // paths can cause the recovery card to need to disappear.
    LaunchedEffect(rawEntries, lastImportEpochMs) {
        recoverySnap = recoveryProvider.snapshot(healthEntries = rawEntries)
        // Also re-run the registered provider-health checkers so the
        // inline repair actions reflect the freshly imported credentials.
        // refreshAll is a no-op if no checkers are registered for the
        // affected categories.
        if (lastImportEpochMs > 0L) {
            setupIntentsViewModel.refreshAll()
        }
    }
    recoverySnap?.takeIf { it.shouldShowRecoveryCard }?.let { snap ->
        // Human-readable category list - replaces the opaque
        // "3 categories" count. Each chip names the affected provider
        // family AND a one-line impact statement so the user sees what's
        // blocked before deciding between transfer vs manual setup.
        val missingDetails: List<Pair<String, String>> = snap.missingCategories.map { cat ->
            when (cat) {
                com.torve.domain.transfer.SecretCategory.DEBRID ->
                    "Debrid (Real-Debrid / AllDebrid / Premiumize / TorBox)" to
                        "Streaming and downloads from cached debrid sources are unavailable until configured."
                com.torve.domain.transfer.SecretCategory.IPTV ->
                    "IPTV playlists" to
                        "Live TV channels and EPG won't load. Recordings can't be scheduled."
                com.torve.domain.transfer.SecretCategory.PLEX_JELLYFIN ->
                    "Plex / Jellyfin" to
                        "Your local media server library won't appear in Torve."
                com.torve.domain.transfer.SecretCategory.TRAKT_SIMKL ->
                    "Trakt / SIMKL" to
                        "Watch history won't sync, and watchlist changes stay on this device only."
                com.torve.domain.transfer.SecretCategory.AI_KEYS ->
                    "AI provider keys" to
                        "Natural-language search (\"Ask AI\") will be off."
                com.torve.domain.transfer.SecretCategory.PANDA ->
                    "Usenet / Easynews / NZB indexers" to
                        "Usenet warming, downloads, and instant playback for cached items are off."
            }
        }
        TorveSectionCard(
            title = ds("Restore setup from another device"),
            supportingText = ds("Transfer encrypted credentials from a device that already works. Faster than re-entering each one by hand."),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${snap.missingTransferableCategoryCount} provider categor" +
                        (if (snap.missingTransferableCategoryCount == 1) "y is" else "ies are") +
                        " missing on this device",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Why: provider credentials (Trakt, Plex, debrid keys, etc.) are " +
                        "stored only on the device that authorized them - they never reach " +
                        "Torve servers, by design. So they don't sync down when you sign in " +
                        "on a new device. Watch history and watchlist do sync; connections " +
                        "themselves do not.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                Text(
                    text = "Until you fix one of the items below, the matching Torve features " +
                        "stay off on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                // Per-category impact list - gives the user the
                // "what's affected" answer without scrolling away.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    missingDetails.forEach { (label, impact) ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                            Column {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary,
                                )
                                Text(
                                    text = impact,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "Fix options: (1) receive an encrypted credential bundle from a " +
                        "device that already has these set up - Torve servers never see the " +
                        "decrypted contents; or (2) open each provider's setup card above " +
                        "and re-authenticate / re-enter the credential here. Either path " +
                        "resolves the same items.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // "Set up manually" used to just close the card -
                    // now it dismisses the recovery card AND nudges the
                    // user to use the per-provider Setup & Sources rows
                    // above. The card naturally re-appears on next
                    // refresh if categories are still missing.
                    TorveGhostButton(
                        text = ds("Set up manually"),
                        onClick = {
                            recoverySnap = null
                            redirectNotice = "Use the Setup & Sources cards above - " +
                                "tap \"Open setup\" on each row that says \"Not set up\"."
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    TorvePrimaryButton(
                        text = ds("Receive credentials"),
                        // Open the MODAL dialog (same one the per-row
                        // Transfer button uses). Flipping just the inline
                        // expander far below the page gave the user no
                        // visible feedback.
                        onClick = {
                            transferReceiveDialogOpen = true
                            transferReceiveOpen = true
                        },
                    )
                }
            }
        }
    }

    TorveSectionCard(
        title = ds("Provider Health"),
        supportingText = ds("Per-provider status and last check time."),
    ) {
        if (rawEntries.isEmpty()) {
            Text(
                text = "No checks have run yet. Open a setup path above to add credentials, " +
                    "then press \"Re-check everything\".",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                redirectNotice?.let { msg ->
                    TorveBanner(
                        title = "Heads up",
                        description = msg,
                        tone = TorveBannerTone.Info,
                    )
                }

                // Map a provider category to the right destination AND
                // a human-readable hint about where the credentials
                // actually live. Debrid (Real-Debrid / AllDebrid /
                // Premiumize / TorBox) is configured inside the Panda
                // setup wizard, NOT in Account - surfacing the wrong
                // destination silently was the cause of the previous
                // confusion.
                val routeToCategorySettings: (com.torve.domain.providerhealth.ProviderHealthCategory) -> Unit = { cat ->
                    when (cat) {
                        com.torve.domain.providerhealth.ProviderHealthCategory.DEBRID -> {
                            redirectNotice = "Debrid credentials (Real-Debrid / AllDebrid / " +
                                "Premiumize / TorBox) are configured inside the Panda setup " +
                                "wizard. Opening it now."
                            onOpenPandaSetup()
                        }
                        com.torve.domain.providerhealth.ProviderHealthCategory.IPTV,
                        com.torve.domain.providerhealth.ProviderHealthCategory.EPG -> {
                            redirectNotice = null
                            onSwitchToCategory(SettingsCategory.PLAYLISTS)
                        }
                        com.torve.domain.providerhealth.ProviderHealthCategory.PLEX_JELLYFIN,
                        com.torve.domain.providerhealth.ProviderHealthCategory.TRAKT,
                        com.torve.domain.providerhealth.ProviderHealthCategory.SIMKL -> {
                            redirectNotice = null
                            onSwitchToCategory(SettingsCategory.INTEGRATIONS)
                        }
                        com.torve.domain.providerhealth.ProviderHealthCategory.ADDON -> {
                            redirectNotice = null
                            onSwitchToCategory(SettingsCategory.ADDONS)
                        }
                        com.torve.domain.providerhealth.ProviderHealthCategory.USENET_INDEXER,
                        com.torve.domain.providerhealth.ProviderHealthCategory.USENET_PROVIDER,
                        com.torve.domain.providerhealth.ProviderHealthCategory.DOWNLOAD_CLIENT -> {
                            redirectNotice = "Usenet stack (NZB indexer + provider + download " +
                                "client) is configured inside the Panda setup wizard. " +
                                "Opening it now."
                            onOpenPandaSetup()
                        }
                        com.torve.domain.providerhealth.ProviderHealthCategory.PLAYBACK -> {
                            redirectNotice = null
                            onSwitchToCategory(SettingsCategory.CUSTOMIZATION)
                        }
                    }
                }
                rawEntries.sortedWith(
                    compareBy({ it.category.ordinal }, { it.label }),
                ).forEach { entry ->
                    ProviderHealthRow(
                        entry = entry,
                        onTransferReceive = {
                            // Open the modal AND keep the inline card
                            // primed in case the user dismisses the
                            // dialog and scrolls down later.
                            transferReceiveDialogOpen = true
                            transferReceiveOpen = true
                        },
                        onReenterManually = routeToCategorySettings,
                        // Diagnostics + crash-reporting state lives in the
                        // About tab on desktop. Send the user there so they
                        // can copy the env-var, run the diagnostics export,
                        // or read the runtime VLC state.
                        onOpenDiagnostics = {
                            redirectNotice = null
                            onSwitchToCategory(SettingsCategory.ABOUT)
                        },
                        onOpenProviderSettings = routeToCategorySettings,
                    )
                }
            }
        }
    }

    // Modal dialog hosting the receive screen - opened by the
    // Provider Health row buttons. Visible immediately on click
    // regardless of scroll position.
    if (transferReceiveDialogOpen) {
        val receiverVm: com.torve.presentation.transfer.SecretsTransferReceiverViewModel = remember {
            org.koin.java.KoinJavaComponent.get(
                com.torve.presentation.transfer.SecretsTransferReceiverViewModel::class.java,
            )
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { transferReceiveDialogOpen = false },
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.cardSurface,
                modifier = Modifier
                    .widthIn(min = 480.dp, max = 720.dp)
                    .padding(8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Receive credentials from another device",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        TorveGhostButton(
                            text = "Close",
                            onClick = { transferReceiveDialogOpen = false },
                        )
                    }
                    com.torve.desktop.transfer.SecretsTransferReceiveScreen(
                        viewModel = receiverVm,
                        onClose = { transferReceiveDialogOpen = false },
                    )
                }
            }
        }
    }

    // ── LAN library serving (Phase 3 Slice C / Prompt 9B) ─────────
    val lanStatusLabel = when {
        !settingsState.lanServingEnabled -> ds("Off - desktop talks only to itself.")
        !settingsState.lanServingBindToLan -> ds("Loopback only - peers on this LAN cannot connect.")
        else -> ds("LAN published - peers on this network can authenticate and stream.")
    }
    TorveSectionCard(
        title = ds("LAN library"),
        supportingText = ds("Let this desktop serve its downloaded media to other devices on your local network. Off by default. Enable serving first, then opt into LAN-bind to publish a hub other devices can discover."),
    ) {
        Text(
            text = lanStatusLabel,
            style = MaterialTheme.typography.bodySmall,
            color = TorveDesktopThemeTokens.colors.textSecondary,
        )
        PreferenceToggleRow(
            title = ds("Enable LAN serving"),
            subtitle = if (settingsState.lanServingEnabled)
                ds("Server is running. Sign-out or disabling this toggle stops it immediately.")
            else
                ds("Server is stopped."),
            checked = settingsState.lanServingEnabled,
            onCheckedChange = settingsViewModel::setLanServingEnabled,
        )
        PreferenceToggleRow(
            title = ds("Allow other devices on this network"),
            subtitle = if (settingsState.lanServingBindToLan)
                ds("Server is bound to your LAN interface; the hub is published to the registry. Disable to revert to loopback only.")
            else
                ds("Loopback only - flip to allow LAN-discovered TV / mobile to authenticate and stream."),
            checked = settingsState.lanServingBindToLan,
            onCheckedChange = settingsViewModel::setLanServingBindToLan,
        )
        PreferenceToggleRow(
            title = ds("LAN streams require Wi-Fi (peer setting)"),
            subtitle = ds("Mobile / TV peers refuse to play LAN streams on cellular when this is on. Local files always play."),
            checked = settingsState.lanPlaybackWifiOnly,
            onCheckedChange = settingsViewModel::setLanPlaybackWifiOnly,
        )
        Spacer(Modifier.height(8.dp))
        // Manual storage hygiene: deletes only watched + completed
        // downloads whose path lies in the configured allowlist.
        // Result is summarized in a transient banner state; the helper
        // never deletes files it can't prove are owned.
        var cleanupSummary by remember { mutableStateOf<String?>(null) }
        var cleanupRunning by remember { mutableStateOf(false) }
        val cleanupScope = rememberCoroutineScope()
        val cleanupSummaryTemplate = ds("Freed %1\$d MB across %2\$d title(s); skipped %3\$d.")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorveSecondaryButton(
                text = if (cleanupRunning) ds("Working...") else ds("Free up space (watched downloads)"),
                onClick = {
                    cleanupRunning = true
                    cleanupSummary = null
                    cleanupScope.launch {
                        val cleanup = runCatching {
                            org.koin.mp.KoinPlatform.getKoin()
                                .get<com.torve.desktop.lanlibrary.WatchedDownloadCleanup>()
                        }.getOrNull()
                        val outcomes = cleanup?.cleanWatched().orEmpty()
                        val deleted = outcomes
                            .filterIsInstance<com.torve.desktop.lanlibrary.WatchedDownloadCleanup.CleanupOutcome.Deleted>()
                        val skipped = outcomes
                            .filterIsInstance<com.torve.desktop.lanlibrary.WatchedDownloadCleanup.CleanupOutcome.Skipped>()
                        val freedMb = deleted.sumOf { it.freedBytes } / (1024 * 1024)
                        cleanupSummary = cleanupSummaryTemplate.format(freedMb, deleted.size, skipped.size)
                        cleanupRunning = false
                    }
                },
                enabled = !cleanupRunning,
            )
            cleanupSummary?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
            }
        }
    }

    // Credential transfer (Phase 3 sub-pass 2: receiver only).
    // State hoisted above the Provider Health card so repair-action
    // buttons there can flip this flag.
    TorveSectionCard(
        title = ds("Receive credentials from another device"),
        supportingText = ds("Show a one-time QR/session code for encrypted credential transfer. The handshake stays on this device; expires in 10 minutes."),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TorvePrimaryButton(
                text = if (transferReceiveOpen) ds("Hide") else ds("Show receive code"),
                onClick = { transferReceiveOpen = !transferReceiveOpen },
            )
        }
        if (transferReceiveOpen) {
            val receiverVm: com.torve.presentation.transfer.SecretsTransferReceiverViewModel = remember {
                org.koin.java.KoinJavaComponent.get(
                    com.torve.presentation.transfer.SecretsTransferReceiverViewModel::class.java,
                )
            }
            com.torve.desktop.transfer.SecretsTransferReceiveScreen(
                viewModel = receiverVm,
                onClose = { transferReceiveOpen = false },
            )
        }
    }

    var transferSendOpen by remember { mutableStateOf(false) }
    TorveSectionCard(
        title = ds("Send credentials to another device"),
        supportingText = ds("Sending starts on the device you want to set up. Open Receive credentials there first to get a code, then bring it back here."),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TorvePrimaryButton(
                text = if (transferSendOpen) ds("Hide") else ds("Open sender"),
                onClick = { transferSendOpen = !transferSendOpen },
            )
        }
        if (transferSendOpen) {
            val senderVm: com.torve.presentation.transfer.SecretsTransferSenderViewModel = remember {
                org.koin.java.KoinJavaComponent.get(
                    com.torve.presentation.transfer.SecretsTransferSenderViewModel::class.java,
                )
            }
            com.torve.desktop.transfer.SecretsTransferSendScreen(viewModel = senderVm)
        }
    }

    // Read-only diagnostics - closed-shape values only, no secrets.
    com.torve.desktop.transfer.TransferDiagnosticsCard()
}

@Composable
private fun SetupIntentCard(
    summary: com.torve.presentation.setup.SetupIntentSummary,
    onRunCheck: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.fieldSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusPill(status = summary.status)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = summary.intent.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = summary.primaryMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            TorveGhostButton(text = "Run check", onClick = onRunCheck)
            TorvePrimaryButton(text = "Open setup", onClick = onOpenSetup)
        }
    }
}

@Composable
private fun ProviderHealthRow(
    entry: com.torve.domain.providerhealth.ProviderHealthEntry,
    onTransferReceive: () -> Unit = {},
    onReenterManually: (com.torve.domain.providerhealth.ProviderHealthCategory) -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenProviderSettings: (com.torve.domain.providerhealth.ProviderHealthCategory) -> Unit = {},
) {
    val colors = TorveDesktopThemeTokens.colors
    val actions = remember(entry) {
        com.torve.presentation.providerhealth.ProviderRepairMapper.actionsFor(entry)
    }
    // Permanent location hint on rows whose credentials don't live in
    // a Settings tab - surfaces "Configured in Panda setup" right next
    // to the row label so the user knows where to go even before
    // they click any repair button.
    val locationHint = when (entry.category) {
        com.torve.domain.providerhealth.ProviderHealthCategory.DEBRID,
        com.torve.domain.providerhealth.ProviderHealthCategory.USENET_INDEXER,
        com.torve.domain.providerhealth.ProviderHealthCategory.USENET_PROVIDER,
        com.torve.domain.providerhealth.ProviderHealthCategory.DOWNLOAD_CLIENT ->
            "Configured in Panda setup"
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardSurface, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusPill(status = entry.status, compact = true)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    locationHint?.let { hint ->
                        TorveBadge(
                            text = hint,
                            tone = TorveBadgeTone.Neutral,
                        )
                    }
                }
                entry.message?.takeIf { it.isNotBlank() }?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
            Text(
                text = formatLastChecked(entry.lastCheckedAt),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        // Inline repair actions - only when the row is unhealthy AND
        // at least one action applies. Transfer-from-another-device
        // routes to the existing receive screen.
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { action ->
                    when (action) {
                        com.torve.presentation.providerhealth.ProviderRepairAction.TransferFromAnotherDevice ->
                            TorvePrimaryButton(
                                text = "Transfer from another device",
                                onClick = onTransferReceive,
                            )
                        com.torve.presentation.providerhealth.ProviderRepairAction.ReenterCredentials ->
                            TorveGhostButton(
                                text = "Re-enter manually",
                                onClick = { onReenterManually(entry.category) },
                            )
                        com.torve.presentation.providerhealth.ProviderRepairAction.OpenDiagnostics ->
                            TorveGhostButton(
                                text = "Diagnostics",
                                onClick = onOpenDiagnostics,
                            )
                        com.torve.presentation.providerhealth.ProviderRepairAction.OpenProviderSettings ->
                            TorveGhostButton(
                                text = "Open settings",
                                onClick = { onOpenProviderSettings(entry.category) },
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    status: com.torve.domain.providerhealth.ProviderHealthStatus,
    compact: Boolean = false,
) {
    val colors = TorveDesktopThemeTokens.colors
    val (label, container, content) = when (status) {
        com.torve.domain.providerhealth.ProviderHealthStatus.GREEN ->
            Triple("Ready", colors.success.copy(alpha = 0.18f), colors.success)
        com.torve.domain.providerhealth.ProviderHealthStatus.YELLOW ->
            Triple("Attention", colors.warning.copy(alpha = 0.20f), colors.warning)
        com.torve.domain.providerhealth.ProviderHealthStatus.RED ->
            Triple("Action needed", colors.error.copy(alpha = 0.18f), colors.error)
        com.torve.domain.providerhealth.ProviderHealthStatus.UNKNOWN ->
            Triple("Checking...", colors.info.copy(alpha = 0.18f), colors.info)
        com.torve.domain.providerhealth.ProviderHealthStatus.UNCONFIGURED ->
            Triple("Not set up", colors.borderStrong.copy(alpha = 0.45f), colors.textSecondary)
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(if (compact) 8.dp else 999.dp),
    ) {
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 3.dp else 6.dp,
            ),
        )
    }
}

private fun formatLastChecked(epochMs: Long?): String {
    if (epochMs == null) return "Never"
    val nowMs = java.time.Instant.now().toEpochMilli()
    val deltaSec = ((nowMs - epochMs) / 1000L).coerceAtLeast(0L)
    return when {
        deltaSec < 60L -> "Just now"
        deltaSec < 3600L -> "${deltaSec / 60L} min ago"
        deltaSec < 86_400L -> "${deltaSec / 3600L} h ago"
        else -> "${deltaSec / 86_400L} d ago"
    }
}

/**
 * EPG correction card (Prompt 10B). Three levers: per-playlist time
 * offset minutes, hidden categories (chips), and a tvg-id remap field.
 * Persistence is handled by [com.torve.presentation.recording.EpgCorrectionViewModel].
 */
@Composable
private fun EpgCorrectionCard(
    playlistId: String,
    channelsState: ChannelsUiState,
) {
    val vm = remember {
        org.koin.mp.KoinPlatform.getKoin()
            .get<com.torve.presentation.recording.EpgCorrectionViewModel>()
    }
    LaunchedEffect(playlistId) { vm.load(playlistId) }
    val state by vm.state.collectAsState()
    val correction = state.correction

    TorveSectionCard(
        title = "EPG correction",
        supportingText = "Fix common EPG issues without editing M3U or XMLTV. Changes apply to the selected playlist.",
    ) {
        // Time offset
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Time offset (minutes):",
                style = MaterialTheme.typography.bodyMedium,
            )
            TorveGhostButton(
                text = "−60",
                onClick = { vm.setOffsetMinutes(correction.offsetMinutes - 60) },
            )
            TorveGhostButton(
                text = "−15",
                onClick = { vm.setOffsetMinutes(correction.offsetMinutes - 15) },
            )
            Text(
                text = "${correction.offsetMinutes}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            TorveGhostButton(
                text = "+15",
                onClick = { vm.setOffsetMinutes(correction.offsetMinutes + 15) },
            )
            TorveGhostButton(
                text = "+60",
                onClick = { vm.setOffsetMinutes(correction.offsetMinutes + 60) },
            )
            if (correction.offsetMinutes != 0) {
                TorveGhostButton(
                    text = "Reset",
                    onClick = { vm.setOffsetMinutes(0) },
                )
            }
        }

        // Hidden categories
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Hide categories",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val allCategories = remember(channelsState.categories) {
            channelsState.categories.map { it.name }.distinct().sorted()
        }
        if (allCategories.isEmpty()) {
            Text(
                text = "No categories detected yet - load the channel list first.",
                style = MaterialTheme.typography.bodySmall,
                color = TorveDesktopThemeTokens.colors.textSecondary,
            )
        } else {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                allCategories.forEach { name ->
                    val hidden = name in correction.hiddenCategories
                    val tone = if (hidden) TorveBadgeTone.Error else TorveBadgeTone.Neutral
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .clickable { vm.toggleHiddenCategory(name) },
                    ) {
                        TorveBadge(text = (if (hidden) "✕ " else "") + name, tone = tone)
                    }
                }
            }
        }

        // Manual tvg-id remap - minimal text-based form for now.
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Channel id remap",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Use only when a channel doesn't pair with the EPG. Format: playlist_id → epg_id.",
            style = MaterialTheme.typography.bodySmall,
            color = TorveDesktopThemeTokens.colors.textSecondary,
        )
        var newPlaylistId by remember { mutableStateOf("") }
        var newEpgId by remember { mutableStateOf("") }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TorveTextField(
                value = newPlaylistId,
                onValueChange = { newPlaylistId = it },
                label = "Playlist id",
                modifier = Modifier.weight(1f),
            )
            Text("→", style = MaterialTheme.typography.bodyMedium)
            TorveTextField(
                value = newEpgId,
                onValueChange = { newEpgId = it },
                label = "EPG id",
                modifier = Modifier.weight(1f),
            )
            TorvePrimaryButton(
                text = "Add",
                onClick = {
                    if (newPlaylistId.isNotBlank() && newEpgId.isNotBlank()) {
                        vm.setMapping(newPlaylistId.trim(), newEpgId.trim())
                        newPlaylistId = ""
                        newEpgId = ""
                    }
                },
                enabled = newPlaylistId.isNotBlank() && newEpgId.isNotBlank(),
            )
        }
        if (correction.tvgIdRemap.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                correction.tvgIdRemap.forEach { (k, v) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "$k  →  $v",
                            style = MaterialTheme.typography.bodySmall,
                            color = TorveDesktopThemeTokens.colors.textSecondary,
                        )
                        TorveGhostButton(text = "Remove", onClick = { vm.setMapping(k, null) })
                    }
                }
            }
        }

        // Stale-EPG banner - only when the VM has been told.
        state.health?.takeIf { it.isStale }?.let {
            Spacer(Modifier.height(10.dp))
            TorveBanner(
                title = "EPG looks stale",
                description = "The latest programme on this playlist ended before now. " +
                    "Refresh from Settings → Live TV → Refresh, or check the EPG URL.",
                tone = TorveBannerTone.Warning,
            )
        }
    }
}

/**
 * Engine selector row used by Settings → Desktop Playback. Renders the
 * engine name + a trailing status note ("Active", "Available - Labs",
 * "Unavailable on this device") with an explicit disabled state. The
 * disabled state is the user-visible signal that MPV exists, even when
 * libmpv is missing - keeping the row visible (instead of hiding it
 * entirely) is the premium behaviour the spec asks for.
 */
@Composable
private fun EnginePickerRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    trailingNote: String?,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val containerColor = when {
        !enabled -> colors.cardSurface.copy(alpha = 0.55f)
        selected -> colors.cardSurface
        else -> colors.cardSurface
    }
    val borderColor = when {
        !enabled -> colors.borderSubtle.copy(alpha = 0.45f)
        selected -> colors.accent.copy(alpha = 0.7f)
        else -> colors.borderSubtle
    }
    val labelColor = if (enabled) colors.textPrimary else colors.textMuted
    val noteColor = if (enabled) colors.textSecondary else colors.textMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .let { m -> if (enabled) m.clickable(onClick = onClick) else m }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Custom radio dot - keeps the disabled visual neutral instead
        // of the platform RadioButton's "muted blue" which reads as
        // a yellow-warning to some users in our palette.
        Box(
            modifier = Modifier
                .size(16.dp)
                .border(1.5.dp, if (selected && enabled) colors.accent else colors.borderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected && enabled) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(colors.accent, CircleShape),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (trailingNote != null) {
            Text(
                text = trailingNote,
                style = MaterialTheme.typography.labelSmall,
                color = noteColor,
            )
        }
    }
}

/**
 * Premium info card for the MPV Labs engine. Replaces the old playback
 * overlay/warning that used to ride along during playback - the user
 * only sees this when they explicitly inspect Settings.
 */
@Composable
private fun MpvLabsInfoCard(
    snapshot: com.torve.desktop.playback.MpvLabsStatus.Snapshot,
    onOpenSetupGuide: () -> Unit,
    onRecheck: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val unavailable = snapshot.state == com.torve.desktop.playback.MpvLabsStatus.State.UNAVAILABLE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardSurface, RoundedCornerShape(12.dp))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = snapshot.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            // Neutral pill - explicitly NOT yellow/warning. Premium
            // visual hierarchy: state is information, not alarm.
            TorveBadge(
                text = snapshot.stateLabel,
                tone = if (unavailable) TorveBadgeTone.Neutral else TorveBadgeTone.Success,
            )
        }
        Text(
            text = snapshot.description,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorveGhostButton(
                text = "Setup guide",
                onClick = onOpenSetupGuide,
            )
            TorveGhostButton(
                text = "Re-check",
                onClick = onRecheck,
            )
        }
    }
}

/**
 * Modal containing actionable MPV Labs setup instructions. Pulls
 * search-path diagnostics from [MpvLabsStatus.setupGuideBody] so the
 * user sees exactly where Torve looked.
 */
@Composable
private fun MpvLabsSetupGuideDialog(
    snapshot: com.torve.desktop.playback.MpvLabsStatus.Snapshot,
    onDismiss: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val body = remember(snapshot) {
        com.torve.desktop.playback.MpvLabsStatus.setupGuideBody(snapshot)
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.cardSurface,
            modifier = Modifier
                .widthIn(min = 480.dp, max = 720.dp)
                .padding(8.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "MPV Labs setup guide",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    TorveGhostButton(text = "Close", onClick = onDismiss)
                }
                // Plain-text scrollable body - preserves whitespace from
                // the helper so the path list reads correctly.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .background(colors.fieldSurface, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TorveGhostButton(
                        text = "Open mpv.io",
                        onClick = {
                            runCatching {
                                java.awt.Desktop.getDesktop().browse(java.net.URI("https://mpv.io"))
                            }
                        },
                    )
                }
            }
        }
    }
}
