package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.disabled
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlinx.coroutines.flow.first
import com.torve.android.tv.components.tvSettingsFocusFrame
import com.torve.android.tv.components.tvLiquidGlass
import com.torve.android.tv.components.TvClickToEditOutlinedTextField
import com.torve.android.tv.components.tvSettingsLabel as parityLabel
import com.torve.domain.model.*
import com.torve.presentation.settings.*
import com.torve.presentation.usenet.NzbdavSetupViewModel
import com.torve.presentation.usenet.NzbdavTestResult
import org.koin.compose.koinInject

/** One category shell for every setting. Service pages contain only that service's controls. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun TvAlignedSettingsPanel(
    rail: FocusRequester, entry: FocusRequester?, isActive: Boolean, railFocused: Boolean,
    publish: (FocusRequester) -> Unit, contentFocused: (FocusRequester) -> Unit, moveToRail: () -> Unit,
    extensions: () -> Unit, mdbList: () -> Unit, appearance: () -> Unit, home: () -> Unit, ratings: () -> Unit,
    profiles: () -> Unit, backup: () -> Unit, receive: () -> Unit, jellyfin: () -> Unit, collections: () -> Unit,
    legacyForm: @Composable (TvSettingsCategory, FocusRequester, FocusRequester, (FocusRequester) -> Unit) -> Unit,
    initialAction: String? = null, entryNonce: Int = 0, onInitialActionHandled: () -> Unit = {},
    automation: (String) -> Unit = {}, webReplays: () -> Unit = {},
    vm: SettingsViewModel = koinInject(), nzb: NzbdavSetupViewModel = koinInject(),
) {
    val state by vm.state.collectAsState()
    val nzbState by nzb.state.collectAsState()
    val health: com.torve.presentation.providerhealth.ProviderHealthCoordinator = koinInject()
    val healthEntries by health.entries.collectAsState()
    val healthChecking by health.checking.collectAsState()
    val categoryForms = mapOf("account" to TvSettingsCategory.ACCOUNT,
        "Channels" to TvSettingsCategory.LIBRARY,
        "about" to TvSettingsCategory.ABOUT)
    fun openAction(action: String): Boolean = when (action) {
        "MDBList" -> { mdbList(); true }
        "Seerr" -> { automation("seerr"); true }
        "collections" -> { collections(); true }
        "background" -> { appearance(); true }
        "home" -> { home(); true }
        "ratings" -> { ratings(); true }
        "profiles" -> { profiles(); true }
        "backup" -> { backup(); true }
        "receive" -> { receive(); true }
        else -> false
    }
    TvGroupedSettingsBrowser(rail, entry, isActive, railFocused, publish, contentFocused, moveToRail,
        ::openAction, initialAction, entryNonce, onInitialActionHandled) { selection, first, categoryFocus, focused, openSetting, back ->
        key(selection, state.personalDataUserId) {
            val oldCategory = categoryForms[selection]
            if (oldCategory != null) {
                Box(Modifier.fillMaxSize().focusRequester(first).focusGroup()) {
                    legacyForm(oldCategory, first, categoryFocus, focused)
                }
            } else if (selection == "usenet_setup") {
                com.torve.android.tv.components.TvSettingsStyle {
                    com.torve.android.ui.settings.UsenetSettingsScreen(back, isTv = true, embedded = true,
                        entryFocus = first, onFocused = focused)
                }
            } else if (selection == "extensions") {
                com.torve.android.ui.community.ExtensionsScreen(onBack = back, isTv = true,
                    embedded = true, embeddedEntry = first, onEmbeddedFocus = focused)
            } else if (selection == "web_replays") {
                com.torve.android.ui.replays.WebReplaySettings(true, {}, webReplays,
                    embedded = true, entryFocus = first, onFocused = focused)
            } else Column(Modifier.fillMaxSize().focusRequester(first).focusGroup()
                .verticalScroll(rememberScrollState()).padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var choice by remember { mutableStateOf<TvSettingsChoice?>(null) }
                var choiceOrigin by remember { mutableStateOf<FocusRequester?>(null) }
                val window = LocalWindowInfo.current
                LaunchedEffect(choice) {
                    if (choice == null) choiceOrigin?.let { origin ->
                        snapshotFlow { window.isWindowFocused }.first { it }
                        withFrameNanos { }
                        origin.requestFocus(); choiceOrigin = null
                    }
                }
                choice?.let { TvSettingsChoiceDialog(it) { choice = null } }
                var controlIndex = 0
                var savedControlIndex by rememberSaveable(selection) { mutableIntStateOf(0) }
                val entryControlIndex = remember(selection) { savedControlIndex }
                val requesters = remember(selection) { mutableMapOf<Int, FocusRequester>() }
                val enabledControls = mutableSetOf<Int>()
                @Composable fun next(enabled: Boolean = true): FocusRequester {
                    val index = controlIndex++
                    if (enabled) enabledControls.add(index)
                    return requesters.getOrPut(index) { FocusRequester() }
                }
                LaunchedEffect(selection, isActive) {
                    if (isActive && !railFocused) {
                        withFrameNanos { }
                        val target = requesters[entryControlIndex].takeIf { entryControlIndex in enabledControls } ?: first
                        if (runCatching { target.requestFocus() }.isFailure) first.requestFocus()
                    }
                }
                fun recordFocus(requester: FocusRequester) {
                    requesters.entries.firstOrNull { it.value === requester }?.key?.let { savedControlIndex = it }
                    focused(requester)
                }
                fun Modifier.controlNavigation(requester: FocusRequester): Modifier = focusProperties {
                    val index = requesters.entries.first { it.value === requester }.key
                    up = enabledControls.filter { it < index }.maxOrNull()?.let(requesters::get) ?: FocusRequester.Cancel
                    down = enabledControls.filter { it > index }.minOrNull()?.let(requesters::get) ?: FocusRequester.Cancel
                }
                @Composable fun action(title: String, detail: String? = null, enabled: Boolean = true,
                    status: ProviderHealthStatus? = null, navigates: Boolean = false, onClick: () -> Unit) {
                    val requester = next(enabled)
                    TvSettingsActionRow(title, detail, enabled, Modifier.focusRequester(requester).controlNavigation(requester)
                        .onFocusChanged { if (it.isFocused) recordFocus(requester) }, status, navigates) {
                        choiceOrigin = requester
                        onClick()
                    }
                }
                @Composable fun field(label: String, value: String, secure: Boolean = false, change: (String) -> Unit) {
                    val requester = next()
                    TvClickToEditOutlinedTextField(value, change, { Text(parityLabel(label)) }, true,
                        modifier = Modifier.fillMaxWidth().focusRequester(requester).controlNavigation(requester)
                            .onFocusChanged { if (it.hasFocus) recordFocus(requester) },
                        visualTransformation = if (secure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
                }
                @Composable fun note(text: String?) {
                    if (!text.isNullOrBlank()) Text(parityLabel(text), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                }
                @Composable fun auth(title: String, url: String, code: String, polling: Boolean) {
                    TvDeviceAuthorizationPanel(parityLabel(title), url, code, parityLabel("Complete authorization on your phone"), false, polling)
                }
                @Composable fun <T> pick(title: String, selected: T, values: List<T>, name: (T) -> String, change: (T) -> Unit) {
                    action(title, name(selected)) {
                        choice = TvSettingsChoice(title, selected.toString(), values.map { it.toString() to name(it) }) { id ->
                            values.firstOrNull { it.toString() == id }?.let(change)
                        }
                    }
                }
                @Composable fun toggle(title: String, value: Boolean, change: (Boolean) -> Unit) {
                    action(title, localizedUiText(if (value) "On" else "Off")) { change(!value) }
                }
                when (selection) {
                    "Playback" -> {
                        pick(localizedUiText("Playback preset"), PlaybackPreset.recognize(vm.buildStreamPreferences()),
                            PlaybackPreset.entries.filter { it.isAvailable(vm.buildStreamPreferences()) }, { it.label }, vm::applyPlaybackPreset)
                        pick(localizedUiText("Automatic source selection"), state.autoSourceMode, AutoSourceMode.entries, { it.label }, vm::setAutoSourceMode)
                        pick(localizedUiText("Maximum quality"), state.maxQuality, StreamQuality.entries, { it.label }, vm::setMaxQuality)
                        pick(localizedUiText("Minimum quality"), state.minQuality, StreamQuality.entries, { it.label }, vm::setMinQuality)
                        toggle(localizedUiText("Match frame rate"), state.matchFrameRate, vm::setMatchFrameRate)
                        pick(localizedUiText("HDR"), state.hdrMode, HdrMode.entries, { it.label }, vm::setHdrMode)
                        pick(localizedUiText("Video codec"), state.codecPreference, CodecPreference.entries, { it.label }, vm::setCodecPreference)
                        toggle(localizedUiText("Start playback automatically"), state.autoPlayEnabled, vm::setAutoPlayEnabled)
                        note(localizedUiText("Additional source rules and playback controls are in Playback → Advanced playback."))
                    }
                    "Episodes" -> {
                        toggle(localizedUiText("Autoplay next episode"), state.autoPlayNextEpisodeEnabled, vm::setAutoPlayNextEpisodeEnabled)
                        pick(localizedUiText("Next episode starts"), state.nextEpisodeMode, NextEpisodeMode.entries, { it.label }, vm::setNextEpisodeMode)
                        val modes = com.torve.domain.player.SegmentActionMode.entries
                        pick(localizedUiText("Skip intro"), state.skipIntroMode, modes, { it.name.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }, vm::setSkipIntroMode)
                        pick(localizedUiText("Skip recap"), state.skipRecapMode, modes, { it.name.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }, vm::setSkipRecapMode)
                        pick(localizedUiText("During credits"), state.playNextDuringCreditsMode, modes, { it.name.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }, vm::setPlayNextDuringCreditsMode)
                        toggle(localizedUiText("Protect post-credit scenes"), state.protectPostCreditScenes, vm::setProtectPostCreditScenes)
                        toggle(localizedUiText("Detect intros and credits"), state.smartSegmentDetectionEnabled, vm::setSmartSegmentDetectionEnabled)
                        pick(localizedUiText("Prepare next episode"), state.nextEpisodePreparationMode, NextEpisodePreparationMode.entries, { it.label }, vm::setNextEpisodePreparationMode)
                    }
                    "advanced_playback" -> {
                        toggle(localizedUiText("Cached sources only"), state.cachedOnly, vm::setCachedOnly)
                        toggle(localizedUiText("Exclude season packs"), state.excludeSeasonPacks, vm::setExcludeSeasonPacks)
                        toggle(localizedUiText("Allow automatic 4K"), state.allow4kAuto, vm::setAllow4kAuto)
                        toggle(localizedUiText("Prefer compatible codecs"), state.preferCompatibleCodecs, vm::setPreferCompatibleCodecs)
                        pick(localizedUiText("Maximum file size"), state.maxFileSizeMb ?: 0, (listOf(0, 1024, 2048, 5120, 10240, 20480, 51200) + (state.maxFileSizeMb ?: 0)).distinct(), { if (it == 0) "No limit" else "$it MB" }) { vm.setMaxFileSizeMb(it.takeIf { v -> v > 0 }) }
                        pick(localizedUiText("Minimum size per hour"), state.minSourceSizePerHourMb, (listOf(0, 512, 1024, 2048, 4096) + state.minSourceSizePerHourMb).distinct(), { if (it == 0) "No minimum" else "$it MB" }, vm::setMinSourceSizePerHourMb)
                        pick(localizedUiText("Match source language"), state.sourceLanguageMatchMode, SourceLanguageMatchMode.entries, { it.label }, vm::setSourceLanguageMatchMode)
                        pick(localizedUiText("Unknown source size"), state.unknownSourceSizePolicy, UnknownSourceMetadataPolicy.entries, { it.label }, vm::setUnknownSourceSizePolicy)
                        pick(localizedUiText("Unknown source language"), state.unknownSourceLanguagePolicy, UnknownSourceMetadataPolicy.entries, { it.label }, vm::setUnknownSourceLanguagePolicy)
                        pick(localizedUiText("When no source matches"), state.sourceFallbackPolicy, SourceFallbackPolicy.entries, { it.label }, vm::setSourceFallbackPolicy)
                        pick(localizedUiText("Next episode buffer"), state.nextEpisodePreloadBufferSeconds, (listOf(10, 30, 60, 120) + state.nextEpisodePreloadBufferSeconds).distinct(), { "$it seconds" }, vm::setNextEpisodePreloadBufferSeconds)
                        pick(localizedUiText("Next episode buffer limit"), state.nextEpisodePreloadMaxMb, (listOf(64, 128, 256, 512) + state.nextEpisodePreloadMaxMb).distinct(), { "$it MB" }, vm::setNextEpisodePreloadMaxMb)
                        toggle(localizedUiText("Preload on Wi-Fi only"), state.nextEpisodePreloadWifiOnly, vm::setNextEpisodePreloadWifiOnly)
                        toggle(localizedUiText("Remote seek buttons"), state.tvTransportSkipEnabled, vm::setTvTransportSkipEnabled)
                        toggle(localizedUiText("Accelerate repeated skips"), state.tvProgressiveSkipEnabled, vm::setTvProgressiveSkipEnabled)
                        toggle(localizedUiText("Timeline scrubbing"), state.tvExplicitTimelineScrubEnabled, vm::setTvExplicitTimelineScrubEnabled)
                        pick(localizedUiText("Seek step"), state.seekStepSeconds, (listOf(5, 10, 15, 30, 60) + state.seekStepSeconds).distinct(), { "$it seconds" }, vm::setSeekStepSeconds)
                    }
                    "tests" -> {
                        action(localizedUiText("Test configured connections"), localizedUiText(if (healthChecking.isEmpty()) "Check availability without changing your setup" else "${healthChecking.size} checks running"), healthChecking.isEmpty()) { health.runAll() }
                        healthEntries.forEach { service ->
                            val destination = tvConnectionSettingsAction(service, state.jellyfinConnected)
                            action(service.label, localizedUiText(if (service.providerKey in healthChecking) "Checking…" else tvConnectionStatusLabel(service.status)),
                                status = service.status, navigates = destination != null) {
                                if (destination != null) openSetting(destination) else health.runCheck(service.providerKey)
                            }
                        }
                        if (healthEntries.isEmpty()) note(localizedUiText("Configured services appear here after their first connection check."))
                    }
                    "features" -> OptionalAppFeature.availableEntries.forEach { feature ->
                        val label = when(feature) { OptionalAppFeature.JELLYFIN -> "Jellyfin library"; OptionalAppFeature.CHANNELS -> "Live channels"; OptionalAppFeature.ARR_STACK -> "Library automation"; else -> feature.name }
                        toggle(label, state.optionalAppFeatures.isEnabled(feature)) { vm.setOptionalAppFeature(feature, it) }
                    }
                    "OMDb" -> {
                        field(localizedUiText("API key"), state.omdbApiKey, true, vm::updateOmdbApiKeyInput)
                        action(localizedUiText("Save & test"), enabled = !state.omdbValidating && state.omdbApiKey.isNotBlank(), onClick = vm::saveAndValidateOmdbApiKey)
                        note(state.omdbValidationResult)
                    }
                    "Kodi" -> {
                        var hostName by remember { mutableStateOf("") }; var hostIp by remember { mutableStateOf("") }; var hostPort by remember { mutableStateOf("8080") }
                        field(localizedUiText("Device name"), hostName) { hostName = it }
                        field(localizedUiText("Address"), hostIp) { hostIp = it }
                        field(localizedUiText("Port"), hostPort) { hostPort = it }
                        action(localizedUiText("Add device"), enabled = hostName.isNotBlank() && hostIp.isNotBlank() && hostPort.toIntOrNull() in 1..65535) { vm.addKodiHost(hostName, hostIp, hostPort.toInt()); hostName = ""; hostIp = "" }
                        state.kodiHosts.forEach { host ->
                            action(stringResource(R.string.tv_audit_test_device, host.name), localizedUiText(when(state.kodiTestResult[host.ip]) { true -> "Connected"; false -> "Check the address and port"; null -> host.ip })) { vm.testKodiHost(host) }
                            action(stringResource(R.string.tv_audit_remove_device, host.name)) { vm.removeKodiHost(host) }
                        }
                    }
                    "Audio & subtitles" -> {
                        val channelVm: com.torve.presentation.channels.ChannelsViewModel = koinInject()
                        val channelState by channelVm.state.collectAsState()
                        pick(localizedUiText("Live audio output"), channelState.liveAudioOutputMode, com.torve.domain.player.LiveAudioOutputMode.entries, {
                            when(it) { com.torve.domain.player.LiveAudioOutputMode.AUTO -> "Automatic"; com.torve.domain.player.LiveAudioOutputMode.PREFER_COMPATIBLE -> "Prefer compatible"; com.torve.domain.player.LiveAudioOutputMode.FORCE_STEREO_PCM -> "Stereo PCM" }
                        }, channelVm::setLiveAudioOutputMode)
                        toggle(localizedUiText("Audio passthrough"), channelState.audioPassthroughEnabled, channelVm::setAudioPassthroughEnabled)
                        toggle(localizedUiText("Prefer surround audio"), channelState.preferSurroundCodecs, channelVm::setPreferSurroundCodecs)
                        val languages = remember(state.appLanguage) { listOf("" to "Automatic") + java.util.Locale.getISOLanguages()
                            .map { it to java.util.Locale(it).getDisplayLanguage(java.util.Locale.getDefault()) }.sortedBy { it.second } }
                        fun languageName(value: String) = languages.firstOrNull { it.first == value }?.second ?: value
                        pick(localizedUiText("Audio language"), state.preferredAudioLanguage, (languages.map { it.first } + state.preferredAudioLanguage).distinct(), ::languageName, vm::setPreferredAudioLanguage)
                        pick(localizedUiText("Subtitle language"), state.preferredSubtitleLanguage, (languages.map { it.first } + state.preferredSubtitleLanguage).distinct(), ::languageName, vm::setPreferredSubtitleLanguage)
                        toggle(localizedUiText("Subtitles on by default"), state.subtitlesEnabledByDefault, vm::setSubtitlesEnabledByDefault)
                        toggle(localizedUiText("Allow another subtitle language"), state.allowSubtitleLanguageFallback, vm::setAllowSubtitleLanguageFallback)
                        toggle(localizedUiText("Keep subtitles above player controls"), state.keepSubtitlesAboveControls, vm::setKeepSubtitlesAboveControls)
                    }
                    "Display" -> {
                        pick(localizedUiText("Theme"), state.themeMode, ThemeMode.entries, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, vm::setThemeMode)
                        pick(localizedUiText("Language"), state.appLanguage, AppLanguage.entries, { it.displayName }, vm::setAppLanguage)
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val reduced = com.torve.android.tv.settings.rememberTvReduceMotionPreference()
                        toggle(localizedUiText("Reduce motion"), reduced) { com.torve.android.tv.settings.setTvReduceMotionEnabled(context, it) }
                        toggle(localizedUiText("Hide watched titles in discovery"), state.hideWatched, vm::setHideWatched)
                    }
                    "Trakt" -> {
                        TvServiceStatus("Trakt", state.traktConnected, state.traktUser?.username)
                        action(localizedUiText(if (state.traktConnected) "Sync now" else "Connect Trakt"),
                            com.torve.android.tv.components.tvSettingsLabel(if (state.traktSyncing) "Syncing your history…" else "History, ratings and lists"),
                            !state.traktLoading && !state.traktSyncing && !state.isPollingTrakt) {
                            if (state.traktConnected) vm.syncTraktNow() else vm.startTraktDeviceAuth()
                        }
                        state.traktDeviceCode?.let { auth(localizedUiText("Connect Trakt"), it.verificationUrl, it.userCode, state.isPollingTrakt) }
                        if (state.traktConnected) {
                            action(localizedUiText("Reconnect"), localizedUiText("Authorize Trakt again"), !state.traktLoading && !state.isPollingTrakt, onClick = vm::startTraktDeviceAuth)
                            action(localizedUiText("Disconnect"), localizedUiText("Remove the Trakt connection from this TV"), onClick = vm::disconnectTrakt)
                        }
                        note(if (state.traktSyncSuccess) "History is up to date." else state.traktError)
                    }
                    "SIMKL" -> {
                        TvServiceStatus("SIMKL", state.simklConnected, state.simklUser?.username)
                        action(localizedUiText(if (state.simklConnected) "Check connection" else "Connect SIMKL"), enabled = !state.simklLoading && !state.isPollingSimkl) {
                            if (state.simklConnected) vm.checkSimklConnection() else vm.startSimklDeviceAuth()
                        }
                        state.simklDeviceCode?.let { auth(localizedUiText("Connect SIMKL"), it.verificationUrl, it.userCode, state.isPollingSimkl) }
                        if (state.simklConnected) {
                            action(localizedUiText("Reconnect"), enabled = !state.simklLoading && !state.isPollingSimkl, onClick = vm::startSimklDeviceAuth)
                            action(localizedUiText("Disconnect"), onClick = vm::disconnectSimkl)
                        }
                        note(state.simklError)
                    }
                    "Connections" -> {
                        val labels = listOf("Real-Debrid", "AllDebrid", "Premiumize", "TorBox")
                        action(localizedUiText("Service"), labels[DebridServiceType.entries.indexOf(state.debridProvider)]) {
                            choice = TvSettingsChoice("Streaming service", state.debridProvider.name,
                                DebridServiceType.entries.mapIndexed { i, value -> value.name to labels[i] }) {
                                vm.setDebridProvider(DebridServiceType.valueOf(it))
                            }
                        }
                        TvServiceStatus(labels[DebridServiceType.entries.indexOf(state.debridProvider)], state.debridConnected, state.debridUser?.username)
                        if (state.debridProvider == DebridServiceType.REAL_DEBRID || state.debridProvider == DebridServiceType.ALL_DEBRID)
                            action(localizedUiText("Connect with a code"), localizedUiText("Authorize from your phone"), !state.debridLoading && !state.isPollingDebrid, onClick = vm::startDebridDeviceAuth)
                        state.debridDeviceCode?.let { auth(localizedUiText("Connect service"), it.verificationUrl, it.userCode, state.isPollingDebrid) }
                        field(localizedUiText("API key"), state.debridApiKey, true, vm::setDebridApiKey)
                        action(localizedUiText("Save & connect"), enabled = state.debridApiKey.isNotBlank() && !state.debridLoading, onClick = vm::connectDebridWithApiKey)
                        if (state.debridConnected) action(localizedUiText("Disconnect this service"), onClick = vm::disconnectDebrid)
                        note(state.debridError)
                    }
                    "Jellyfin" -> {
                        TvServiceStatus("Jellyfin", state.jellyfinConnected)
                        field(localizedUiText("Server URL"), state.jellyfinServerUrl, change = vm::setJellyfinServerUrl)
                        field(localizedUiText("API key"), state.jellyfinApiKey, true, vm::updateJellyfinApiKeyInput)
                        action(localizedUiText("Save & test connection"), enabled = state.jellyfinServerUrl.isNotBlank() && state.jellyfinApiKey.isNotBlank()) { vm.saveAndTestJellyfinConnection() }
                        note(state.jellyfinStatusMessage)
                        if (state.jellyfinProfiles.isNotEmpty()) action(localizedUiText("Library profile"), localizedUiText(state.jellyfinProfiles.firstOrNull { it.id == state.selectedJellyfinUserId }?.name ?: "All users")) {
                            choice = TvSettingsChoice("Jellyfin profile", state.selectedJellyfinUserId.orEmpty(),
                                listOf("" to "All users") + state.jellyfinProfiles.map { it.id to it.name }) { vm.selectJellyfinProfile(it.ifBlank { null }) }
                        }
                    }
                    "Plex" -> {
                        TvServiceStatus("Plex", state.plexConnected)
                        field(localizedUiText("Server URL"), state.plexServerUrl, change = vm::setPlexServerUrl)
                        field(localizedUiText("Access token"), state.plexAccessToken, true, vm::updatePlexAccessTokenInput)
                        action(localizedUiText("Save & connect"), enabled = !state.plexLoading && state.plexServerUrl.isNotBlank() && state.plexAccessToken.isNotBlank(), onClick = vm::saveAndConnectPlex)
                        if (state.plexConnected) action(localizedUiText("Disconnect"), onClick = vm::disconnectPlex)
                        note(state.plexError)
                    }
                    "OpenSubtitles" -> {
                        note(localizedUiText("Add your OpenSubtitles API key. Press OK on the field to edit."))
                        var key by remember(state.personalDataUserId, state.opensubtitlesApiKey) { mutableStateOf(state.opensubtitlesApiKey) }
                        field(localizedUiText("API key"), key, true) { key = it }
                        action(localizedUiText("Save API key")) { vm.setOpenSubtitlesApiKey(key) }
                    }
                    "AI" -> {
                        action(localizedUiText("Provider"), state.aiProvider.label) {
                            choice = TvSettingsChoice("AI Search provider", state.aiProvider.name,
                                com.torve.data.ai.AiProvider.entries.map { it.name to it.label }) { vm.setAiProvider(com.torve.data.ai.AiProvider.valueOf(it)) }
                        }
                        field(localizedUiText("API key"), state.activeAiApiKey, true, vm::updateActiveAiApiKeyInput)
                        action(localizedUiText("Save & test"), enabled = !state.aiKeyValidating && state.activeAiApiKey.isNotBlank(), onClick = vm::saveAndValidateAiApiKey)
                        note(state.aiKeyValidationResult)
                    }
                    "NzbDAV" -> {
                        field(localizedUiText("Server URL"), nzbState.baseUrl, change = nzb::updateBaseUrl)
                        field(localizedUiText("API key"), nzbState.apiKey, true, nzb::updateApiKey)
                        action(localizedUiText("Enabled"), localizedUiText(if (nzbState.enabled) "On" else "Off")) { nzb.setEnabled(!nzbState.enabled) }
                        action(localizedUiText("Test connection"), enabled = !nzbState.isTesting) { nzb.test() }
                        action(localizedUiText("Save"), enabled = !nzbState.isSaving) { nzb.save() }
                        action(localizedUiText("Remove"), enabled = !nzbState.isRemoving) { nzb.remove() }
                        note(when(nzbState.lastTestResult) { NzbdavTestResult.Saved -> "Connection saved"; NzbdavTestResult.Ok -> "Connection successful"; NzbdavTestResult.DegradedOk -> "Connected with limited availability"; NzbdavTestResult.Removed -> "Connection removed"; NzbdavTestResult.MissingFields -> "Enter a server URL and API key."; null -> null; else -> "Check the server URL and API key, then test again." })
                    }
                    "cards" -> {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val prefs = remember { context.getSharedPreferences("tv_prefs", android.content.Context.MODE_PRIVATE) }
                        var titles by remember { mutableStateOf(prefs.getBoolean("tv_show_poster_titles", true)) }
                        toggle(localizedUiText("Poster titles"), titles) { titles = it; prefs.edit().putBoolean("tv_show_poster_titles", it).apply() }
                        val columns = com.torve.android.tv.settings.rememberTvSeeAllPosterColumnsPreference()
                        pick(localizedUiText("Posters per row in See All"), columns, com.torve.android.tv.settings.TV_SEE_ALL_POSTER_COLUMN_OPTIONS, { it.toString() }) { com.torve.android.tv.settings.setTvSeeAllPosterColumns(context, it) }
                        if (state.cardStylePresets.isNotEmpty()) {
                            pick(localizedUiText("Default card preset"), state.globalDefaultPresetId ?: state.cardStylePresets.first().presetId,
                                state.cardStylePresets.map { it.presetId }, { id -> state.cardStylePresets.firstOrNull { it.presetId == id }?.name ?: id }, vm::setDefaultCardStylePreset)
                        }
                    }
                    "Browsing" -> {
                        val preview = com.torve.android.tv.settings.rememberTvFocusPreviewPolicy(state.personalDataUserId)
                        val context = androidx.compose.ui.platform.LocalContext.current
                        toggle(localizedUiText("Global Focus Preview"), preview.global) {
                            com.torve.android.tv.settings.setTvFocusPreview(context, state.personalDataUserId, "global", it)
                        }
                        note(localizedUiText(if (preview.global) "Expanding landscape on every movie and TV poster row. Your individual choices are kept."
                            else "Choose pages below. On any poster row, hold OK or press Menu to override that row. Expanded cards replace the hero while focused."))
                        if (!preview.global) {
                            com.torve.android.tv.settings.TV_FOCUS_PREVIEW_PAGES.forEach { (page, title) ->
                                toggle(title, preview.enabled(page)) {
                                    com.torve.android.tv.settings.setTvFocusPreview(context, state.personalDataUserId, "page:$page", it)
                                }
                            }
                        }
                        note(localizedUiText("Layout choices are saved for this profile on this Fire TV."))
                    }
                    "Badges" -> action(localizedUiText("Remaining episodes"), localizedUiText(if (state.showRemainingEpisodes) "Shown" else "Hidden")) { vm.setShowRemainingEpisodes(!state.showRemainingEpisodes) }
                    else -> note(localizedUiText("Choose a settings category on the left."))
                }
            }
        }
    }
}

@Composable
internal fun TvSettingsActionRow(title: String, detail: String? = null, enabled: Boolean = true,
    modifier: Modifier = Modifier, status: ProviderHealthStatus? = null, navigates: Boolean = false,
    onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val statusLabel = status?.let { parityLabel(tvConnectionStatusLabel(it)) }
    val shape = RoundedCornerShape(20.dp)
    // A save/check can disable its own row. Keep its focus owner alive until
    // the viewer moves, and keep the action disabled during that interval.
    Surface({ if (enabled) onClick() }, enabled = enabled || focused, shape = shape,
        color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().semantics { if (!enabled) disabled() }.onFocusChanged { focused = it.isFocused }
            .padding(4.dp).tvLiquidGlass(focused, enabled = enabled)) {
        Row(Modifier.heightIn(min = 60.dp).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(parityLabel(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                if (!detail.isNullOrBlank()) Text(parityLabel(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status != null) Box(Modifier.padding(start = 12.dp).size(10.dp)
                .background(when (status) {
                    ProviderHealthStatus.GREEN -> Color(0xFF48C78E)
                    ProviderHealthStatus.RED -> Color(0xFFF16D78)
                    ProviderHealthStatus.YELLOW -> Color(0xFFE5AC4C)
                    else -> Color(0xFF858B9C)
                }, CircleShape).testTag("settings.status.${status.name}")
                .semantics { stateDescription = statusLabel.orEmpty() })
            if (navigates) Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 12.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TvServiceStatus(name: String, connected: Boolean, account: String? = null) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            val status = parityLabel(if (connected) "Connected" else "Not connected")
            Text(account?.takeIf { connected }?.let { "$status · $it" } ?: status,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
