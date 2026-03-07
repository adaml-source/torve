package com.torve.android.tv.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.torve.android.ui.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
// LocaleListCompat removed — no longer applying locale inline
import com.torve.android.R
import com.torve.android.sync.SyncCoordinator
import com.torve.android.tv.components.TvClickToEditOutlinedTextField
import com.torve.android.ui.settings.AddonCategory
import com.torve.android.ui.settings.POPULAR_ADDONS
import com.torve.data.ai.AiProvider
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.StreamQuality
import com.torve.presentation.addon.AddonViewModel
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.mdblist.MdbListTab
import com.torve.presentation.mdblist.MdbListViewModel
import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

enum class TvSetupMode { ANDROID_PHONE, IOS_PHONE, TV_ONLY }

@Composable
fun TvSettingsScreen(
    railFocusRequester: FocusRequester,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onNavigateToHomeLayout: () -> Unit = {},
    onNavigateToRatings: () -> Unit = {},
    isActive: Boolean = true,
    syncCoordinator: SyncCoordinator = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    channelsViewModel: ChannelsViewModel = koinInject(),
    addonViewModel: AddonViewModel = koinInject(),
    mdbListViewModel: MdbListViewModel = koinInject(),
) {
    val syncState by syncCoordinator.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val channelsState by channelsViewModel.state.collectAsState()
    val addonState by addonViewModel.state.collectAsState()
    val mdbListState by mdbListViewModel.state.collectAsState()
    val hasPairedPhone = syncState.devices.any { it.deviceType == "mobile" && it.revokedAt == null }
    var aboutTapCount by remember { mutableIntStateOf(0) }
    var showDebugPanel by remember { mutableStateOf(false) }

    // Inline text-input expansion states
    var expandedInput by remember { mutableStateOf<String?>(null) }

    // Playlist add form
    var showAddPlaylist by remember { mutableStateOf(false) }
    var showEditSelectedPlaylistEpg by remember { mutableStateOf(false) }
    var selectedPlaylistEpgDraft by remember { mutableStateOf("") }

    // Kodi add form
    var showAddKodi by remember { mutableStateOf(false) }
    var kodiName by remember { mutableStateOf("") }
    var kodiIp by remember { mutableStateOf("") }
    var kodiPort by remember { mutableStateOf("8080") }

    // Remove playlist confirmation
    var confirmRemoveId by remember { mutableStateOf<String?>(null) }

    // Addon management
    var showAddAddon by remember { mutableStateOf(false) }
    var confirmRemoveAddonUrl by remember { mutableStateOf<String?>(null) }

    // MDBList browse
    var showMdbListBrowse by remember { mutableStateOf(false) }
    var mdbListSearchQuery by remember { mutableStateOf("") }

    // Picker overlays
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showMaxQualityPicker by remember { mutableStateOf(false) }
    var showMinQualityPicker by remember { mutableStateOf(false) }

    // Addon catalog category
    var addonCategory by remember { mutableStateOf(AddonCategory.ALL) }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE) }
    var setupMode by remember {
        mutableStateOf(prefs.getString("setup_mode", null)?.let { TvSetupMode.valueOf(it) })
    }

    LaunchedEffect(setupMode) {
        if (setupMode != null) {
            prefs.edit().putString("setup_mode", setupMode!!.name).apply()
        } else {
            prefs.edit().remove("setup_mode").apply()
        }
    }

    // LazyColumn state — allows scrolling to make items visible before focusing
    val settingsListState = rememberLazyListState()

    // Stable focus anchor for settings content.
    // This requester is attached to the first actionable card in each settings mode,
    // so moving focus right from the nav rail always lands on a real focus target.
    val settingsContentRequester = remember { FocusRequester() }
    val addPlaylistCardRequester = remember { FocusRequester() }
    val editPlaylistEpgCardRequester = remember { FocusRequester() }
    val addKodiCardRequester = remember { FocusRequester() }
    val addAddonCardRequester = remember { FocusRequester() }
    var pendingFocusRestoreRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var previousPlaylistCount by remember { mutableIntStateOf(channelsState.playlists.size) }
    var previousShowAddPlaylist by remember { mutableStateOf(showAddPlaylist) }
    var settingsScreenHasFocus by remember { mutableStateOf(false) }
    onFirstContentRequester(settingsContentRequester)

    // When setup mode switches (e.g. picker -> TV-only), the previously focused node
    // is removed from composition. Explicitly re-focus the new first card.
    LaunchedEffect(setupMode) {
        if (!isActive || !settingsScreenHasFocus) return@LaunchedEffect
        repeat(20) {
            val focused = try {
                settingsContentRequester.requestFocus()
                true
            } catch (_: IllegalStateException) {
                false
            }
            if (focused) return@LaunchedEffect
            delay(40)
        }
    }

    LaunchedEffect(pendingFocusRestoreRequester) {
        if (!isActive) return@LaunchedEffect
        val target = pendingFocusRestoreRequester ?: return@LaunchedEffect

        // Scroll to the top so the first settings card is composed and focusable.
        // Off-screen LazyColumn items have detached FocusRequesters, so without
        // scrolling back all fallback targets may fail.
        try { settingsListState.scrollToItem(0) } catch (_: Exception) {}
        delay(80)

        val contentFallbacks = listOf(
            target,
            addPlaylistCardRequester,
            addKodiCardRequester,
            addAddonCardRequester,
            settingsContentRequester,
        ).distinct()

        contentFallbacks.forEach { focusTarget ->
            repeat(20) {
                val focused = try {
                    focusTarget.requestFocus()
                    true
                } catch (_: IllegalStateException) {
                    false
                }
                if (focused) {
                    onContentFocused(focusTarget)
                    pendingFocusRestoreRequester = null
                    return@LaunchedEffect
                }
                delay(40)
            }
        }

        repeat(20) {
            val focusedRail = try {
                railFocusRequester.requestFocus()
                true
            } catch (_: IllegalStateException) {
                false
            }
            if (focusedRail) {
                pendingFocusRestoreRequester = null
                return@LaunchedEffect
            }
            delay(40)
        }
        pendingFocusRestoreRequester = null
    }

    LaunchedEffect(showAddPlaylist) {
        if (previousShowAddPlaylist && !showAddPlaylist) {
            pendingFocusRestoreRequester = addPlaylistCardRequester
        }
        previousShowAddPlaylist = showAddPlaylist
    }

    LaunchedEffect(channelsState.playlists.size) {
        val currentCount = channelsState.playlists.size
        if (currentCount != previousPlaylistCount) {
            if (showAddPlaylist) {
                showAddPlaylist = false
            }
            pendingFocusRestoreRequester = addPlaylistCardRequester
        }
        previousPlaylistCount = currentCount
    }

    val selectedPlaylistForEpg = remember(channelsState.playlists, channelsState.selectedPlaylistId) {
        channelsState.playlists.firstOrNull { it.id == channelsState.selectedPlaylistId }
            ?: channelsState.playlists.firstOrNull()
    }

    LaunchedEffect(selectedPlaylistForEpg?.id, showEditSelectedPlaylistEpg) {
        if (!showEditSelectedPlaylistEpg) {
            selectedPlaylistEpgDraft = selectedPlaylistForEpg?.epgUrl.orEmpty()
        }
    }

    // Language cycling
    val languages = remember { AppLanguage.entries.toList() }
    val currentLanguageIndex = remember(settingsState.appLanguage) {
        languages.indexOf(settingsState.appLanguage).coerceAtLeast(0)
    }

    LaunchedEffect(hasPairedPhone, syncState.pairingCode, syncState.isLoading) {
        if (!hasPairedPhone && syncState.pairingCode == null && !syncState.isLoading) {
            syncCoordinator.startTvPairingFlow()
        }
    }

    // String resources captured in composition scope
    val accountSectionLabel = stringResource(R.string.tv_settings_section_account)
    val appSectionLabel = stringResource(R.string.tv_settings_section_app)
    val aboutSectionLabel = stringResource(R.string.tv_settings_section_about)
    val streamQualitySectionLabel = stringResource(R.string.tv_settings_section_stream_quality)
    val languageRegionSectionLabel = stringResource(R.string.tv_settings_section_language_region)
    val contentSectionLabel = stringResource(R.string.tv_settings_section_content)

    val cloudServiceLabel = stringResource(R.string.tv_settings_cloud_service)
    val traktLabel = stringResource(R.string.tv_settings_trakt)
    val simklLabel = stringResource(R.string.tv_settings_simkl)
    val phonePairingLabel = stringResource(R.string.tv_settings_phone_pairing)
    val languageLabel = stringResource(R.string.tv_settings_language)
    val playbackQualityLabel = stringResource(R.string.tv_settings_playback_quality)
    val versionLabel = stringResource(R.string.tv_settings_version)
    val regionLabel = stringResource(R.string.tv_settings_region)

    val connectedLabel = stringResource(R.string.tv_settings_connected)
    val notConnectedLabel = stringResource(R.string.tv_settings_not_connected)
    val pairedLabel = stringResource(R.string.tv_settings_paired)
    val pairingCodePrefix = stringResource(R.string.tv_settings_pairing_code)
    val generatingLabel = stringResource(R.string.tv_settings_generating_code)
    val notPairedLabel = stringResource(R.string.tv_settings_not_paired)
    val notSetLabel = stringResource(R.string.tv_settings_not_set)
    val setLabel = stringResource(R.string.tv_settings_set)

    // Build section data
    val pairingSubtitle = when {
        hasPairedPhone -> pairedLabel
        syncState.pairingCode != null -> "$pairingCodePrefix ${syncState.pairingCode?.code}"
        syncState.isLoading -> generatingLabel
        else -> notPairedLabel
    }
    val qualitySubtitle = "${settingsState.minQuality.name} – ${settingsState.maxQuality.name}"

    // Debrid subtitle for TV-only mode
    val debridSubtitle = when {
        settingsState.isPollingDebrid || settingsState.debridDeviceCode != null -> {
            val code = settingsState.debridDeviceCode
            if (code != null) "Visit ${code.verificationUrl}\nCode: ${code.userCode}" else "Starting…"
        }
        settingsState.debridConnected -> "${settingsState.debridProvider.label} — $connectedLabel"
        else -> "${settingsState.debridProvider.label} — $notConnectedLabel"
    }

    // Trakt subtitle
    val traktSubtitle = when {
        settingsState.isPollingTrakt || settingsState.traktDeviceCode != null -> {
            val code = settingsState.traktDeviceCode
            if (code != null) "Visit ${code.verificationUrl}\nCode: ${code.userCode}" else "Starting…"
        }
        settingsState.traktConnected -> {
            settingsState.traktUser?.username?.let { "$it — $connectedLabel" } ?: connectedLabel
        }
        else -> notConnectedLabel
    }

    // SIMKL subtitle
    val simklSubtitle = when {
        settingsState.isPollingSimkl || settingsState.simklDeviceCode != null -> {
            val code = settingsState.simklDeviceCode
            if (code != null) "Visit ${code.verificationUrl}\nCode: ${code.userCode}" else "Starting…"
        }
        settingsState.simklConnected -> {
            settingsState.simklUser?.username?.let { "$it — $connectedLabel" } ?: connectedLabel
        }
        else -> notConnectedLabel
    }

    // ── Setup mode picker ──
    if (setupMode == null) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { settingsScreenHasFocus = it.hasFocus },
            contentPadding = PaddingValues(start = 40.dp, top = 40.dp, end = 40.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "prompt") {
                Text(
                    text = stringResource(R.string.tv_settings_setup_prompt),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            item(key = "mode_android") {
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_mode_android),
                    subtitle = stringResource(R.string.tv_settings_mode_android_desc),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = settingsContentRequester,
                    onFocused = { onContentFocused(settingsContentRequester) },
                    onClick = { setupMode = TvSetupMode.ANDROID_PHONE },
                )
            }
            item(key = "mode_ios") {
                val requester = remember("mode_ios") { FocusRequester() }
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_mode_ios),
                    subtitle = stringResource(R.string.tv_settings_mode_ios_desc),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = { setupMode = TvSetupMode.IOS_PHONE },
                )
            }
            item(key = "mode_tv_only") {
                val requester = remember("mode_tv_only") { FocusRequester() }
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_mode_tv_only),
                    subtitle = stringResource(R.string.tv_settings_mode_tv_only_desc),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = { setupMode = TvSetupMode.TV_ONLY },
                )
            }
        }
        return
    }

    // ── Main settings (mode selected) ──
    LazyColumn(
        state = settingsListState,
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { settingsScreenHasFocus = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val focusRecoveryKeys = setOf(
                    Key.DirectionUp,
                    Key.DirectionDown,
                    Key.DirectionLeft,
                    Key.DirectionRight,
                    Key.DirectionCenter,
                    Key.Enter,
                    Key.NumPadEnter,
                )
                if (!settingsScreenHasFocus && event.key in focusRecoveryKeys) {
                    pendingFocusRestoreRequester = settingsContentRequester
                    true
                } else {
                    false
                }
            },
        contentPadding = PaddingValues(start = 40.dp, top = 20.dp, end = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Setup mode header — tap to change
        item(key = "setup_mode") {
            val modeLabel = when (setupMode) {
                TvSetupMode.ANDROID_PHONE -> stringResource(R.string.tv_settings_mode_android)
                TvSetupMode.IOS_PHONE -> stringResource(R.string.tv_settings_mode_ios)
                TvSetupMode.TV_ONLY -> stringResource(R.string.tv_settings_mode_tv_only)
                null -> ""
            }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_setup_mode),
                subtitle = "$modeLabel — ${stringResource(R.string.tv_settings_tap_to_change)}",
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = settingsContentRequester,
                onFocused = { onContentFocused(settingsContentRequester) },
                onClick = { setupMode = null },
            )
        }

        // Account section
        item(key = "section_account") {
            TvSectionHeader(text = accountSectionLabel)
        }

        // Phone pairing (all modes)
        item(key = "pairing") {
            val requester = remember("pairing") { FocusRequester() }
            TvSettingCard(
                title = phonePairingLabel,
                subtitle = pairingSubtitle,
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = { syncCoordinator.startTvPairingFlow() },
            )
        }

        when (setupMode) {
            TvSetupMode.ANDROID_PHONE, TvSetupMode.IOS_PHONE -> {
                // Phone mode: read-only statuses + sync button
                item(key = "cloud_service") {
                    val requester = remember("cloud_service") { FocusRequester() }
                    val sub = if (settingsState.debridConnected) {
                        "${settingsState.debridProvider.label} — $connectedLabel"
                    } else notConnectedLabel
                    TvSettingCard(
                        title = cloudServiceLabel,
                        subtitle = sub,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {},
                    )
                }

                item(key = "trakt") {
                    val requester = remember("trakt") { FocusRequester() }
                    val sub = if (settingsState.traktConnected) {
                        settingsState.traktUser?.username?.let { "$it — $connectedLabel" } ?: connectedLabel
                    } else notConnectedLabel
                    TvSettingCard(
                        title = traktLabel,
                        subtitle = sub,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {},
                    )
                }

                // Phone mode: read-only integration statuses
                item(key = "phone_omdb") {
                    val requester = remember("phone_omdb") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_omdb),
                        subtitle = if (settingsState.omdbApiKey.isNotBlank()) connectedLabel else notConnectedLabel,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {},
                    )
                }

                item(key = "phone_mdblist") {
                    val requester = remember("phone_mdblist") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_mdblist),
                        subtitle = if (settingsState.mdblistApiKey.isNotBlank()) connectedLabel else notConnectedLabel,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {},
                    )
                }

                item(key = "phone_jellyfin") {
                    val requester = remember("phone_jellyfin") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_jellyfin),
                        subtitle = if (settingsState.jellyfinServerUrl.isNotBlank()) connectedLabel else notConnectedLabel,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {},
                    )
                }

                item(key = "phone_plex") {
                    val requester = remember("phone_plex") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_plex),
                        subtitle = if (settingsState.plexConnected) connectedLabel else notConnectedLabel,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {},
                    )
                }

                item(key = "sync_from_phone") {
                    val requester = remember("sync_from_phone") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_sync_from_phone),
                        subtitle = if (hasPairedPhone) stringResource(R.string.tv_settings_sync_ready)
                                   else stringResource(R.string.tv_settings_pair_first),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            if (hasPairedPhone) {
                                syncCoordinator.refreshDevices()
                            } else {
                                syncCoordinator.startTvPairingFlow()
                            }
                        },
                    )
                }

                item(key = "sync_note") {
                    Text(
                        text = stringResource(R.string.tv_settings_sync_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ash,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                }
            }

            TvSetupMode.TV_ONLY -> {
                // TV-only: self-service device code auth

                // Cloud Provider (cycle)
                item(key = "cloud_provider") {
                    val requester = remember("cloud_provider") { FocusRequester() }
                    val providers = remember { DebridServiceType.entries.toList() }
                    val currentIdx = remember(settingsState.debridProvider) {
                        providers.indexOf(settingsState.debridProvider).coerceAtLeast(0)
                    }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_change_provider),
                        subtitle = settingsState.debridProvider.label,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            val next = (currentIdx + 1) % providers.size
                            settingsViewModel.setDebridProvider(providers[next])
                        },
                    )
                }

                // Cloud Service (device code auth)
                item(key = "cloud_service") {
                    val requester = remember("cloud_service") { FocusRequester() }
                    TvSettingCard(
                        title = cloudServiceLabel,
                        subtitle = debridSubtitle,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            if (!settingsState.debridConnected && !settingsState.isPollingDebrid) {
                                settingsViewModel.startDebridDeviceAuth()
                            }
                        },
                    )
                }

                // Trakt (device code auth)
                item(key = "trakt") {
                    val requester = remember("trakt") { FocusRequester() }
                    TvSettingCard(
                        title = traktLabel,
                        subtitle = traktSubtitle,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            if (!settingsState.traktConnected && !settingsState.isPollingTrakt) {
                                settingsViewModel.startTraktDeviceAuth()
                            }
                        },
                    )
                }

                // SIMKL (device code auth)
                item(key = "simkl") {
                    val requester = remember("simkl") { FocusRequester() }
                    TvSettingCard(
                        title = simklLabel,
                        subtitle = simklSubtitle,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            if (!settingsState.simklConnected && !settingsState.isPollingSimkl) {
                                settingsViewModel.startSimklDeviceAuth()
                            }
                        },
                    )
                }

                // ── Channels section (TV-only) ──
                item(key = "section_channels") {
                    TvSectionHeader(text = stringResource(R.string.tv_settings_section_channels))
                }

                if (channelsState.playlists.isEmpty()) {
                    item(key = "no_playlists") {
                        val requester = remember("no_playlists") { FocusRequester() }
                        TvSettingCard(
                            title = stringResource(R.string.tv_settings_no_playlists),
                            subtitle = stringResource(R.string.tv_settings_tap_to_edit),
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = requester,
                            onFocused = { onContentFocused(requester) },
                            onClick = { showAddPlaylist = true },
                        )
                    }
                } else {
                    items(
                        channelsState.playlists,
                        key = { "playlist_${it.id}" },
                    ) { playlist ->
                        val requester = remember("playlist_${playlist.id}") { FocusRequester() }
                        val isConfirming = confirmRemoveId == playlist.id
                        TvSettingCard(
                            title = playlist.name,
                            subtitle = if (isConfirming) {
                                stringResource(R.string.tv_settings_playlist_confirm_remove)
                            } else {
                                "${playlist.channelCount} ${stringResource(R.string.tv_settings_section_channels)}"
                            },
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = requester,
                            onFocused = { onContentFocused(requester) },
                            onClick = {
                                if (isConfirming) {
                                    channelsViewModel.removePlaylist(playlist.id)
                                    confirmRemoveId = null
                                    pendingFocusRestoreRequester = addPlaylistCardRequester
                                } else {
                                    confirmRemoveId = playlist.id
                                }
                            },
                        )
                    }
                }

                selectedPlaylistForEpg?.let { playlist ->
                    item(key = "playlist_epg_url") {
                        val subtitle = if (playlist.epgUrl.isNullOrBlank()) {
                            "${playlist.name} • ${stringResource(R.string.tv_settings_not_set)}"
                        } else {
                            "${playlist.name} • ${stringResource(R.string.tv_settings_set)}"
                        }
                        TvSettingCard(
                            title = stringResource(R.string.channels_epg_optional),
                            subtitle = subtitle,
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = editPlaylistEpgCardRequester,
                            onFocused = { onContentFocused(editPlaylistEpgCardRequester) },
                            onClick = {
                                if (showEditSelectedPlaylistEpg) {
                                    pendingFocusRestoreRequester = editPlaylistEpgCardRequester
                                }
                                showEditSelectedPlaylistEpg = !showEditSelectedPlaylistEpg
                            },
                        )
                    }
                }

                // Add playlist card
                item(key = "add_playlist") {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_add_playlist),
                        subtitle = if (showAddPlaylist) stringResource(R.string.tv_settings_tap_to_edit)
                                   else stringResource(R.string.tv_settings_tap_to_edit),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = addPlaylistCardRequester,
                        onFocused = { onContentFocused(addPlaylistCardRequester) },
                        onClick = {
                            if (showAddPlaylist) {
                                pendingFocusRestoreRequester = addPlaylistCardRequester
                            }
                            showAddPlaylist = !showAddPlaylist
                        },
                    )
                }

                if (showEditSelectedPlaylistEpg && selectedPlaylistForEpg != null) {
                    item(key = "playlist_epg_form") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Charcoal.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .border(1.dp, Steel.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TvClickToEditOutlinedTextField(
                                value = selectedPlaylistEpgDraft,
                                onValueChange = { selectedPlaylistEpgDraft = it },
                                label = { Text(stringResource(R.string.channels_epg_optional)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val saveRequester = remember("selected_playlist_epg_save") { FocusRequester() }
                            TvSettingCard(
                                title = stringResource(R.string.tv_settings_playlist_save),
                                subtitle = "",
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = saveRequester,
                                onFocused = { onContentFocused(saveRequester) },
                                onClick = {
                                    channelsViewModel.updatePlaylistEpgUrl(
                                        playlistId = selectedPlaylistForEpg.id,
                                        epgUrl = selectedPlaylistEpgDraft,
                                    )
                                    showEditSelectedPlaylistEpg = false
                                    pendingFocusRestoreRequester = editPlaylistEpgCardRequester
                                },
                            )
                        }
                    }
                }

                item(key = "channels_audio_passthrough") {
                    val requester = remember("channels_audio_passthrough") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_live_audio_passthrough),
                        subtitle = if (channelsState.audioPassthroughEnabled) {
                            stringResource(R.string.tv_settings_enabled)
                        } else {
                            stringResource(R.string.tv_settings_disabled)
                        },
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            channelsViewModel.setAudioPassthroughEnabled(!channelsState.audioPassthroughEnabled)
                        },
                    )
                }

                item(key = "channels_audio_surround") {
                    val requester = remember("channels_audio_surround") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_live_audio_surround),
                        subtitle = if (channelsState.preferSurroundCodecs) {
                            stringResource(R.string.tv_settings_enabled)
                        } else {
                            stringResource(R.string.tv_settings_disabled)
                        },
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            channelsViewModel.setPreferSurroundCodecs(!channelsState.preferSurroundCodecs)
                        },
                    )
                }

                // Inline playlist add form
                if (showAddPlaylist) {
                    item(key = "playlist_form") {
                        var addPlaylistType by remember { mutableStateOf("m3u") }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Charcoal.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .border(1.dp, Steel.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // Type selector pills
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                listOf(
                                    "m3u" to stringResource(R.string.tv_settings_playlist_type_m3u),
                                    "xtream" to stringResource(R.string.tv_settings_playlist_type_xtream),
                                ).forEach { (type, label) ->
                                    val selected = addPlaylistType == type
                                    var focused by remember { mutableStateOf(false) }
                                    val pillScale by animateFloatAsState(
                                        if (focused) 1.05f else if (selected) 1f else 0.95f,
                                        label = "pill",
                                    )
                                    val bg = when {
                                        selected -> Amber
                                        focused -> Steel.copy(alpha = 0.6f)
                                        else -> Charcoal
                                    }
                                    val borderColor = when {
                                        focused -> Amber
                                        selected -> Amber
                                        else -> Steel.copy(alpha = 0.4f)
                                    }
                                    Text(
                                        text = label,
                                        color = if (selected) Color.Black else Color.White,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier
                                            .scale(pillScale)
                                            .onFocusChanged { focused = it.isFocused }
                                            .focusable()
                                            .background(bg, RoundedCornerShape(20.dp))
                                            .border(
                                                if (focused) 2.dp else 1.dp,
                                                borderColor,
                                                RoundedCornerShape(20.dp),
                                            )
                                            .clickable {
                                                addPlaylistType = type
                                                channelsViewModel.setNewPlaylistType(type)
                                            }
                                            .padding(horizontal = 18.dp, vertical = 8.dp),
                                    )
                                }
                            }

                            // Name field — always visible
                            TvClickToEditOutlinedTextField(
                                value = channelsState.newPlaylistName,
                                onValueChange = { channelsViewModel.setNewPlaylistName(it) },
                                label = { Text(stringResource(R.string.tv_settings_playlist_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            if (addPlaylistType == "m3u") {
                                // M3U URL field
                                TvClickToEditOutlinedTextField(
                                    value = channelsState.newPlaylistUrl,
                                    onValueChange = { channelsViewModel.setNewPlaylistUrl(it) },
                                    label = { Text(stringResource(R.string.tv_settings_playlist_url)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                TvClickToEditOutlinedTextField(
                                    value = channelsState.newPlaylistEpgUrl,
                                    onValueChange = { channelsViewModel.setNewPlaylistEpgUrl(it) },
                                    label = { Text(stringResource(R.string.channels_epg_optional)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                // Xtream fields
                                TvClickToEditOutlinedTextField(
                                    value = channelsState.newXtreamServer,
                                    onValueChange = { channelsViewModel.setNewXtreamServer(it) },
                                    label = { Text(stringResource(R.string.tv_settings_xtream_server)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                TvClickToEditOutlinedTextField(
                                    value = channelsState.newXtreamUsername,
                                    onValueChange = { channelsViewModel.setNewXtreamUsername(it) },
                                    label = { Text(stringResource(R.string.tv_settings_xtream_username)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                TvClickToEditOutlinedTextField(
                                    value = channelsState.newXtreamPassword,
                                    onValueChange = { channelsViewModel.setNewXtreamPassword(it) },
                                    label = { Text(stringResource(R.string.tv_settings_xtream_password)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            val saveRequester = remember { FocusRequester() }
                            TvSettingCard(
                                title = if (channelsState.isAddingPlaylist) {
                                    stringResource(R.string.tv_settings_validating)
                                } else {
                                    stringResource(R.string.tv_settings_playlist_save)
                                },
                                subtitle = "",
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = saveRequester,
                                onFocused = { onContentFocused(saveRequester) },
                                onClick = {
                                    if (channelsState.isAddingPlaylist) return@TvSettingCard
                                    channelsViewModel.setNewPlaylistType(addPlaylistType)
                                    channelsViewModel.addPlaylist()
                                },
                            )

                            channelsState.error?.takeIf { it.isNotBlank() }?.let { error ->
                                Text(
                                    text = error,
                                    color = Color(0xFFFF8A80),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                // ── Integrations section (TV-only) ──
                item(key = "section_integrations") {
                    TvSectionHeader(text = stringResource(R.string.tv_settings_section_integrations))
                }

                // OMDB
                item(key = "omdb_key") {
                    TvTextInputCard(
                        key = "omdb_key",
                        title = stringResource(R.string.tv_settings_omdb_api_key),
                        value = settingsState.omdbApiKey,
                        expandedInput = expandedInput,
                        railFocusRequester = railFocusRequester,
                        onContentFocused = onContentFocused,
                        onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                        onValueChange = { settingsViewModel.setOmdbApiKey(it) },
                    )
                }

                item(key = "omdb_test") {
                    val requester = remember("omdb_test") { FocusRequester() }
                    val sub = when {
                        settingsState.omdbValidating -> stringResource(R.string.tv_settings_validating)
                        settingsState.omdbValidationResult == "valid" -> stringResource(R.string.tv_settings_valid)
                        settingsState.omdbValidationResult != null -> settingsState.omdbValidationResult!!
                        settingsState.omdbApiKey.isNotBlank() -> setLabel
                        else -> notSetLabel
                    }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_omdb_test),
                        subtitle = sub,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = { settingsViewModel.validateOmdbApiKey() },
                    )
                }

                // MDBList
                item(key = "mdblist_key") {
                    TvTextInputCard(
                        key = "mdblist_key",
                        title = stringResource(R.string.tv_settings_mdblist_api_key),
                        value = settingsState.mdblistApiKey,
                        expandedInput = expandedInput,
                        railFocusRequester = railFocusRequester,
                        onContentFocused = onContentFocused,
                        onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                        onValueChange = { settingsViewModel.setMdblistApiKey(it) },
                    )
                }

                // Jellyfin
                item(key = "jellyfin_url") {
                    TvTextInputCard(
                        key = "jellyfin_url",
                        title = stringResource(R.string.tv_settings_jellyfin_url),
                        value = settingsState.jellyfinServerUrl,
                        expandedInput = expandedInput,
                        railFocusRequester = railFocusRequester,
                        onContentFocused = onContentFocused,
                        onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                        onValueChange = { settingsViewModel.setJellyfinServerUrl(it) },
                    )
                }

                item(key = "jellyfin_api_key") {
                    TvTextInputCard(
                        key = "jellyfin_api_key",
                        title = stringResource(R.string.tv_settings_jellyfin_api_key),
                        value = settingsState.jellyfinApiKey,
                        expandedInput = expandedInput,
                        railFocusRequester = railFocusRequester,
                        onContentFocused = onContentFocused,
                        onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                        onValueChange = { settingsViewModel.setJellyfinApiKey(it) },
                    )
                }

                item(key = "jellyfin_test") {
                    val requester = remember("jellyfin_test") { FocusRequester() }
                    val sub = when {
                        settingsState.jellyfinStatusMessage != null -> settingsState.jellyfinStatusMessage!!
                        settingsState.jellyfinServerUrl.isNotBlank() -> setLabel
                        else -> notSetLabel
                    }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_jellyfin_test),
                        subtitle = sub,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = { settingsViewModel.testJellyfinConnection() },
                    )
                }

                // Plex
                item(key = "plex_url") {
                    TvTextInputCard(
                        key = "plex_url",
                        title = stringResource(R.string.tv_settings_plex_url),
                        value = settingsState.plexServerUrl,
                        expandedInput = expandedInput,
                        railFocusRequester = railFocusRequester,
                        onContentFocused = onContentFocused,
                        onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                        onValueChange = { settingsViewModel.setPlexServerUrl(it) },
                    )
                }

                item(key = "plex_token") {
                    TvTextInputCard(
                        key = "plex_token",
                        title = stringResource(R.string.tv_settings_plex_token),
                        value = settingsState.plexAccessToken,
                        expandedInput = expandedInput,
                        railFocusRequester = railFocusRequester,
                        onContentFocused = onContentFocused,
                        onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                        onValueChange = { settingsViewModel.setPlexAccessToken(it) },
                    )
                }

                item(key = "plex_test") {
                    val requester = remember("plex_test") { FocusRequester() }
                    val sub = when {
                        settingsState.plexLoading -> stringResource(R.string.tv_settings_validating)
                        settingsState.plexConnected -> stringResource(R.string.tv_settings_test_success)
                        settingsState.plexError != null -> settingsState.plexError!!
                        settingsState.plexServerUrl.isNotBlank() -> setLabel
                        else -> notSetLabel
                    }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_plex_test),
                        subtitle = sub,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = { settingsViewModel.testPlexConnection() },
                    )
                }

                // Kodi
                if (settingsState.kodiHosts.isNotEmpty()) {
                    items(
                        settingsState.kodiHosts,
                        key = { "kodi_${it.name}_${it.ip}" },
                    ) { host ->
                        val requester = remember("kodi_${host.name}") { FocusRequester() }
                        val testResult = settingsState.kodiTestResult[host.name]
                        val sub = when (testResult) {
                            true -> stringResource(R.string.tv_settings_test_success)
                            false -> stringResource(R.string.tv_settings_test_failed)
                            null -> "${host.ip}:${host.port}"
                        }
                        TvSettingCard(
                            title = "${stringResource(R.string.tv_settings_kodi)}: ${host.name}",
                            subtitle = sub,
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = requester,
                            onFocused = { onContentFocused(requester) },
                            onClick = { settingsViewModel.testKodiHost(host) },
                        )
                    }
                }

                item(key = "kodi_add") {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_kodi_add),
                        subtitle = stringResource(R.string.tv_settings_tap_to_edit),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = addKodiCardRequester,
                        onFocused = { onContentFocused(addKodiCardRequester) },
                        onClick = { showAddKodi = !showAddKodi },
                    )
                }

                if (showAddKodi) {
                    item(key = "kodi_form") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Charcoal.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .border(1.dp, Steel.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TvClickToEditOutlinedTextField(
                                value = kodiName,
                                onValueChange = { kodiName = it },
                                label = { Text(stringResource(R.string.tv_settings_kodi_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TvClickToEditOutlinedTextField(
                                value = kodiIp,
                                onValueChange = { kodiIp = it },
                                label = { Text(stringResource(R.string.tv_settings_kodi_ip)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TvClickToEditOutlinedTextField(
                                value = kodiPort,
                                onValueChange = { kodiPort = it },
                                label = { Text(stringResource(R.string.tv_settings_kodi_port)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val saveRequester = remember { FocusRequester() }
                            TvSettingCard(
                                title = stringResource(R.string.tv_settings_kodi_add),
                                subtitle = "",
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = saveRequester,
                                onFocused = { onContentFocused(saveRequester) },
                                onClick = {
                                    val port = kodiPort.toIntOrNull() ?: 8080
                                    if (kodiName.isNotBlank() && kodiIp.isNotBlank()) {
                                        settingsViewModel.addKodiHost(kodiName, kodiIp, port)
                                        kodiName = ""
                                        kodiIp = ""
                                        kodiPort = "8080"
                                        showAddKodi = false
                                        pendingFocusRestoreRequester = addKodiCardRequester
                                    }
                                },
                            )
                        }
                    }
                }

                // ── AI Search section (TV-only) ──
                item(key = "section_ai") {
                    TvSectionHeader(text = stringResource(R.string.tv_settings_section_ai))
                }

                item(key = "ai_provider") {
                    val requester = remember("ai_provider") { FocusRequester() }
                    val providers = remember { AiProvider.entries.toList() }
                    val currentIdx = remember(settingsState.aiProvider) {
                        providers.indexOf(settingsState.aiProvider).coerceAtLeast(0)
                    }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_ai_provider),
                        subtitle = settingsState.aiProvider.label,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            val next = (currentIdx + 1) % providers.size
                            settingsViewModel.setAiProvider(providers[next])
                        },
                    )
                }

                item(key = "ai_api_key") {
                    TvTextInputCard(
                        key = "ai_api_key",
                        title = stringResource(R.string.tv_settings_ai_api_key),
                        value = settingsState.activeAiApiKey,
                        expandedInput = expandedInput,
                        railFocusRequester = railFocusRequester,
                        onContentFocused = onContentFocused,
                        onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                        onValueChange = { settingsViewModel.setActiveAiApiKey(it) },
                    )
                }

                item(key = "ai_test") {
                    val requester = remember("ai_test") { FocusRequester() }
                    val sub = when {
                        settingsState.aiKeyValidating -> stringResource(R.string.tv_settings_validating)
                        settingsState.aiKeyValidationResult == "valid" -> stringResource(R.string.tv_settings_valid)
                        settingsState.aiKeyValidationResult != null -> settingsState.aiKeyValidationResult!!
                        settingsState.activeAiApiKey.isNotBlank() -> setLabel
                        else -> notSetLabel
                    }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_ai_test),
                        subtitle = sub,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = { settingsViewModel.validateAiApiKey() },
                    )
                }

                // ── Addons section (TV-only) ──
                item(key = "section_addons") {
                    TvSectionHeader(text = stringResource(R.string.tv_settings_section_addons))
                }

                // Category filter chips
                item(key = "addon_categories") {
                    val categoryLabels = mapOf(
                        AddonCategory.ALL to stringResource(R.string.tv_addon_category_all),
                        AddonCategory.STREAMS to stringResource(R.string.tv_addon_category_streams),
                        AddonCategory.CATALOGS to stringResource(R.string.tv_addon_category_catalogs),
                        AddonCategory.SUBTITLES to stringResource(R.string.tv_addon_category_subtitles),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(end = 12.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        itemsIndexed(
                            items = AddonCategory.entries.toList(),
                            key = { _, cat -> "addon_cat_${cat.name}" },
                        ) { _, category ->
                            val catRequester = remember("addon_cat_${category.name}") { FocusRequester() }
                            TvAddonCategoryChip(
                                label = categoryLabels[category] ?: category.label,
                                isSelected = addonCategory == category,
                                modifier = Modifier.focusRequester(catRequester),
                                onFocused = { onContentFocused(catRequester) },
                                onClick = { addonCategory = category },
                            )
                        }
                    }
                }

                // Installed addons
                if (addonState.addons.isNotEmpty()) {
                    item(key = "addon_installed_header") {
                        Text(
                            text = stringResource(R.string.tv_addon_installed),
                            style = MaterialTheme.typography.labelLarge,
                            color = Silver,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        )
                    }
                    items(
                        addonState.addons,
                        key = { "addon_${it.manifestUrl}" },
                    ) { addon ->
                        val requester = remember("addon_${addon.manifestUrl.hashCode()}") { FocusRequester() }
                        val isConfirming = confirmRemoveAddonUrl == addon.manifestUrl
                        TvSettingCard(
                            title = addon.manifest.name,
                            subtitle = if (isConfirming) {
                                stringResource(R.string.tv_settings_addon_confirm_remove)
                            } else if (addon.isEnabled) {
                                stringResource(R.string.tv_settings_addon_enabled)
                            } else {
                                stringResource(R.string.tv_settings_addon_disabled)
                            },
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = requester,
                            onFocused = { onContentFocused(requester) },
                            onClick = {
                                if (isConfirming) {
                                    addonViewModel.removeAddon(addon.manifestUrl)
                                    confirmRemoveAddonUrl = null
                                } else {
                                    addonViewModel.toggleAddon(addon.manifestUrl, !addon.isEnabled)
                                }
                            },
                        )
                    }
                }

                // Suggested addons
                item(key = "addon_suggested_header") {
                    Text(
                        text = stringResource(R.string.tv_addon_suggested),
                        style = MaterialTheme.typography.labelLarge,
                        color = Silver,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }

                val installedUrls = addonState.addons.map { it.manifestUrl }.toSet()
                val filteredSuggested = POPULAR_ADDONS.filter { addon ->
                    addon.url !in installedUrls &&
                        (addonCategory == AddonCategory.ALL || addonCategory in addon.categories)
                }

                items(
                    filteredSuggested,
                    key = { "suggested_${it.url}" },
                ) { suggested ->
                    val requester = remember("suggested_${suggested.url.hashCode()}") { FocusRequester() }
                    TvSettingCard(
                        title = suggested.name,
                        subtitle = suggested.description,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            addonViewModel.setInstallUrl(suggested.url)
                            addonViewModel.installAddon()
                            pendingFocusRestoreRequester = addAddonCardRequester
                        },
                    )
                }

                // Manual URL input
                item(key = "add_addon") {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_addon_install),
                        subtitle = if (addonState.isInstalling) stringResource(R.string.tv_settings_addon_installing)
                                   else stringResource(R.string.tv_settings_tap_to_edit),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = addAddonCardRequester,
                        onFocused = { onContentFocused(addAddonCardRequester) },
                        onClick = { showAddAddon = !showAddAddon },
                    )
                }

                if (showAddAddon) {
                    item(key = "addon_form") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Charcoal.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .border(1.dp, Steel.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TvClickToEditOutlinedTextField(
                                value = addonState.installUrl,
                                onValueChange = { addonViewModel.setInstallUrl(it) },
                                label = { Text(stringResource(R.string.tv_settings_addon_install_url)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            addonState.installError?.let { err ->
                                Text(text = err, style = MaterialTheme.typography.bodySmall, color = Ruby)
                            }
                            val installRequester = remember { FocusRequester() }
                            TvSettingCard(
                                title = stringResource(R.string.tv_settings_addon_install),
                                subtitle = "",
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = installRequester,
                                onFocused = { onContentFocused(installRequester) },
                                onClick = {
                                    addonViewModel.installAddon()
                                    showAddAddon = false
                                    pendingFocusRestoreRequester = addAddonCardRequester
                                },
                            )
                        }
                    }
                }

                // ── MDBList Browse section (TV-only) ──
                item(key = "section_mdblist_browse") {
                    TvSectionHeader(text = stringResource(R.string.tv_settings_mdblist_browse))
                }

                if (settingsState.mdblistApiKey.isBlank()) {
                    item(key = "mdblist_no_key") {
                        val requester = remember("mdblist_no_key") { FocusRequester() }
                        TvSettingCard(
                            title = stringResource(R.string.tv_settings_mdblist_browse),
                            subtitle = stringResource(R.string.tv_settings_mdblist_no_api_key),
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = requester,
                            onFocused = { onContentFocused(requester) },
                            onClick = {},
                        )
                    }
                } else {
                    // Saved lists
                    if (mdbListState.savedLists.isNotEmpty()) {
                        item(key = "mdblist_saved_header") {
                            Text(
                                text = stringResource(R.string.tv_settings_mdblist_saved),
                                style = MaterialTheme.typography.labelLarge,
                                color = Silver,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        items(
                            mdbListState.savedLists,
                            key = { "mdblist_saved_${it.listId}" },
                        ) { saved ->
                            val requester = remember("mdblist_saved_${saved.listId}") { FocusRequester() }
                            TvSettingCard(
                                title = saved.name,
                                subtitle = if (saved.enabled) stringResource(R.string.tv_settings_addon_enabled)
                                           else stringResource(R.string.tv_settings_addon_disabled),
                                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                                focusRequester = requester,
                                onFocused = { onContentFocused(requester) },
                                onClick = { mdbListViewModel.toggleList(saved.listId, !saved.enabled) },
                            )
                        }
                    }

                    // Tab toggle: Popular / Search
                    item(key = "mdblist_tabs") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            val popRequester = remember { FocusRequester() }
                            TvSettingCard(
                                title = stringResource(R.string.tv_settings_mdblist_popular),
                                subtitle = if (mdbListState.activeTab == MdbListTab.POPULAR) "●" else "",
                                modifier = Modifier.weight(1f),
                                focusRequester = popRequester,
                                onFocused = { onContentFocused(popRequester) },
                                onClick = {
                                    mdbListViewModel.setActiveTab(MdbListTab.POPULAR)
                                    if (mdbListState.topLists.isEmpty()) mdbListViewModel.loadTopLists()
                                },
                            )
                            val searchRequester = remember { FocusRequester() }
                            TvSettingCard(
                                title = stringResource(R.string.tv_settings_mdblist_search),
                                subtitle = if (mdbListState.activeTab == MdbListTab.SEARCH) "●" else "",
                                modifier = Modifier.weight(1f),
                                focusRequester = searchRequester,
                                onFocused = { onContentFocused(searchRequester) },
                                onClick = { mdbListViewModel.setActiveTab(MdbListTab.SEARCH) },
                            )
                        }
                    }

                    // Search field (when SEARCH tab)
                    if (mdbListState.activeTab == MdbListTab.SEARCH) {
                        item(key = "mdblist_search_field") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Charcoal.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                    .border(1.dp, Steel.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                TvClickToEditOutlinedTextField(
                                    value = mdbListState.searchQuery,
                                    onValueChange = { mdbListViewModel.setSearchQuery(it) },
                                    label = { Text(stringResource(R.string.tv_settings_mdblist_search_hint)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                val searchBtnRequester = remember { FocusRequester() }
                                TvSettingCard(
                                    title = stringResource(R.string.tv_settings_mdblist_search),
                                    subtitle = if (mdbListState.isSearching) stringResource(R.string.tv_settings_validating) else "",
                                    modifier = Modifier.fillMaxWidth(),
                                    focusRequester = searchBtnRequester,
                                    onFocused = { onContentFocused(searchBtnRequester) },
                                    onClick = { mdbListViewModel.search() },
                                )
                            }
                        }
                    }

                    // Results list (Popular or Search)
                    val displayLists = if (mdbListState.activeTab == MdbListTab.POPULAR) {
                        mdbListState.topLists
                    } else {
                        mdbListState.searchResults
                    }

                    if (mdbListState.isLoadingTop || mdbListState.isSearching) {
                        item(key = "mdblist_loading") {
                            Text(
                                text = stringResource(R.string.tv_settings_mdblist_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = Silver,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                            )
                        }
                    }

                    items(
                        displayLists,
                        key = { "mdblist_${mdbListState.activeTab.name}_${it.id}" },
                    ) { listInfo ->
                        val requester = remember("mdblist_${listInfo.id}") { FocusRequester() }
                        val isAdded = mdbListState.savedLists.any { it.listId == listInfo.id }
                        TvSettingCard(
                            title = listInfo.name,
                            subtitle = "${listInfo.items} items · ${listInfo.userName}" +
                                if (isAdded) " · ${stringResource(R.string.tv_settings_mdblist_added)}" else "",
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = requester,
                            onFocused = { onContentFocused(requester) },
                            onClick = {
                                if (!isAdded) {
                                    mdbListViewModel.addList(listInfo.id, listInfo.name)
                                }
                            },
                        )
                    }

                    // Load top lists on first open
                    item(key = "mdblist_init") {
                        LaunchedEffect(Unit) {
                            if (mdbListState.topLists.isEmpty() && !mdbListState.isLoadingTop) {
                                mdbListViewModel.refreshApiKey()
                                mdbListViewModel.loadTopLists()
                            }
                        }
                    }
                }
            }

            null -> {} // unreachable
        }

        // ── Stream Quality & Playback section (all modes) ──
        item(key = "section_stream_quality") {
            TvSectionHeader(text = streamQualitySectionLabel)
        }

        item(key = "max_quality") {
            val requester = remember("max_quality") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_max_quality),
                subtitle = settingsState.maxQuality.label,
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = { showMaxQualityPicker = true },
            )
        }

        item(key = "min_quality") {
            val requester = remember("min_quality") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_min_quality),
                subtitle = settingsState.minQuality.label,
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = { showMinQualityPicker = true },
            )
        }

        item(key = "autoplay") {
            val requester = remember("autoplay") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_autoplay),
                subtitle = if (settingsState.autoPlayEnabled) stringResource(R.string.tv_settings_autoplay_on)
                           else stringResource(R.string.tv_settings_autoplay_off),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = { settingsViewModel.setAutoPlayEnabled(!settingsState.autoPlayEnabled) },
            )
        }

        item(key = "autoplay_next") {
            val requester = remember("autoplay_next") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_autoplay_next),
                subtitle = if (settingsState.autoPlayNextEpisodeEnabled) stringResource(R.string.tv_settings_autoplay_next_on)
                           else stringResource(R.string.tv_settings_autoplay_next_off),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = { settingsViewModel.setAutoPlayNextEpisodeEnabled(!settingsState.autoPlayNextEpisodeEnabled) },
            )
        }

        // Deduplicate Streams toggle
        item(key = "dedupe") {
            val requester = remember("dedupe") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_dedupe),
                subtitle = if (settingsState.dedupeResults) stringResource(R.string.tv_settings_dedupe_on)
                           else stringResource(R.string.tv_settings_dedupe_off),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = { settingsViewModel.setDedupeResultsEnabled(!settingsState.dedupeResults) },
            )
        }

        // ── Language & Region section ──
        item(key = "section_language_region") {
            TvSectionHeader(text = languageRegionSectionLabel)
        }

        item(key = "language") {
            val requester = remember("language") { FocusRequester() }
            TvSettingCard(
                title = languageLabel,
                subtitle = settingsState.appLanguage.displayName,
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = { showLanguagePicker = true },
            )
        }

        if (setupMode == TvSetupMode.TV_ONLY) {
            item(key = "region_lang") {
                val requester = remember("region_lang") { FocusRequester() }
                val regions = remember {
                    listOf("US", "GB", "DE", "FR", "IT", "ES", "CA", "AU", "NL", "BR", "JP", "KR", "IN")
                }
                val currentIdx = remember(settingsState.regionCode) {
                    regions.indexOf(settingsState.regionCode).coerceAtLeast(0)
                }
                TvSettingCard(
                    title = regionLabel,
                    subtitle = settingsState.regionCode,
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = {
                        val next = (currentIdx + 1) % regions.size
                        settingsViewModel.setRegionCode(regions[next])
                    },
                )
            }
        }

        // ── Content Management section ──
        item(key = "section_content") {
            TvSectionHeader(text = contentSectionLabel)
        }

        // Home Layout navigation card
        item(key = "home_layout") {
            val requester = remember("home_layout") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_home_layout),
                subtitle = stringResource(R.string.tv_settings_home_layout_subtitle),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = onNavigateToHomeLayout,
            )
        }

        // Rating Providers navigation card
        item(key = "ratings") {
            val requester = remember("ratings") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_ratings),
                subtitle = stringResource(R.string.tv_settings_ratings_subtitle),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = onNavigateToRatings,
            )
        }

        // Poster Titles toggle
        item(key = "poster_titles") {
            val requester = remember("poster_titles") { FocusRequester() }
            var showPosterTitles by remember {
                mutableStateOf(prefs.getBoolean("tv_show_poster_titles", true))
            }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_poster_titles),
                subtitle = if (showPosterTitles) stringResource(R.string.tv_settings_poster_titles_on)
                           else stringResource(R.string.tv_settings_poster_titles_off),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    showPosterTitles = !showPosterTitles
                    prefs.edit().putBoolean("tv_show_poster_titles", showPosterTitles).apply()
                },
            )
        }

        // About section
        item(key = "section_about") {
            TvSectionHeader(text = aboutSectionLabel)
        }

        item(key = "about") {
            val requester = remember("about") { FocusRequester() }
            TvSettingCard(
                title = versionLabel,
                subtitle = "Torve TV",
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    aboutTapCount++
                    if (aboutTapCount >= 5) {
                        showDebugPanel = !showDebugPanel
                        aboutTapCount = 0
                    }
                },
            )
        }

        // Easter egg: debug panel
        if (showDebugPanel) {
            item(key = "section_debug") {
                TvSectionHeader(text = stringResource(R.string.tv_settings_debug))
            }

            item(key = "debug_sync") {
                val requester = remember("debug_sync") { FocusRequester() }
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_sync_status),
                    subtitle = "Transport: ${syncState.wsStatus}",
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = {},
                )
            }

            if (syncState.recentEvents.isNotEmpty()) {
                items(syncState.recentEvents, key = { it }) { entry ->
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodySmall,
                        color = Silver,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Charcoal.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }

            syncState.error?.let { err ->
                item(key = "debug_error") {
                    Text(
                        text = err,
                        color = Ruby.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    // ── Picker overlays ──

    if (showLanguagePicker) {
        TvListPickerOverlay(
            title = stringResource(R.string.tv_settings_select_language),
            items = languages.map { it.name to it.displayName },
            selectedKey = settingsState.appLanguage.name,
            onSelect = { key ->
                val lang = AppLanguage.valueOf(key)
                settingsViewModel.setAppLanguage(lang)
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(lang.code),
                )
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false },
        )
    }

    if (showMaxQualityPicker) {
        TvListPickerOverlay(
            title = stringResource(R.string.tv_settings_select_quality),
            items = StreamQuality.selectable.map { it.name to it.label },
            selectedKey = settingsState.maxQuality.name,
            onSelect = { key ->
                settingsViewModel.setMaxQuality(StreamQuality.valueOf(key))
                showMaxQualityPicker = false
            },
            onDismiss = { showMaxQualityPicker = false },
        )
    }

    if (showMinQualityPicker) {
        TvListPickerOverlay(
            title = stringResource(R.string.tv_settings_select_quality),
            items = StreamQuality.selectable.map { it.name to it.label },
            selectedKey = settingsState.minQuality.name,
            onSelect = { key ->
                settingsViewModel.setMinQuality(StreamQuality.valueOf(key))
                showMinQualityPicker = false
            },
            onDismiss = { showMinQualityPicker = false },
        )
    }
}

@Composable
private fun TvListPickerOverlay(
    title: String,
    items: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.92f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(400.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Charcoal)
                .border(1.dp, Steel.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .padding(24.dp)
                // No clickable here — it interferes with D-pad Enter on child items
                ,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, pair -> pair.first },
                ) { index, (key, label) ->
                    val isSelected = key == selectedKey
                    val requester = remember("picker_$key") { FocusRequester() }
                    var focused by remember { mutableStateOf(false) }
                    val scale by animateFloatAsState(
                        targetValue = if (focused) 1.03f else 1f,
                        label = "pickerScale",
                    )

                    // Auto-focus the selected item
                    if (isSelected) {
                        LaunchedEffect(Unit) {
                            try { requester.requestFocus() } catch (_: IllegalStateException) {}
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(scale)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    focused -> Graphite
                                    isSelected -> Gunmetal
                                    else -> Color.Transparent
                                },
                            )
                            .border(
                                width = if (focused || isSelected) 1.5.dp else 0.dp,
                                color = when {
                                    focused -> Amber
                                    isSelected -> Amber.copy(alpha = 0.5f)
                                    else -> Color.Transparent
                                },
                                shape = RoundedCornerShape(12.dp),
                            )
                            .focusRequester(requester)
                            .onFocusChanged { focused = it.isFocused }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(key) },
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected || focused) Snow else Silver,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvAddonCategoryChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.05f else 1f, label = "catScale")
    val borderColor = when {
        focused -> AmberLight
        isSelected -> Amber.copy(alpha = 0.67f)
        else -> Steel.copy(alpha = 0.4f)
    }
    val bgColor = when {
        focused -> Gunmetal
        isSelected -> Graphite
        else -> Charcoal
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (isSelected || focused) Snow else Silver,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TvTextInputCard(
    key: String,
    title: String,
    value: String,
    expandedInput: String?,
    railFocusRequester: FocusRequester,
    onContentFocused: (FocusRequester) -> Unit,
    onExpandToggle: (String) -> Unit,
    onValueChange: (String) -> Unit,
) {
    val isExpanded = expandedInput == key
    val requester = remember(key) { FocusRequester() }
    val maskedValue = if (value.isBlank()) stringResource(R.string.tv_settings_not_set)
                      else "${value.take(4)}${"*".repeat((value.length - 4).coerceAtLeast(0))}"

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        TvSettingCard(
            title = title,
            subtitle = maskedValue,
            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
            focusRequester = requester,
            onFocused = { onContentFocused(requester) },
            onClick = { onExpandToggle(key) },
        )
        AnimatedVisibility(visible = isExpanded) {
            TvClickToEditOutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                label = { Text(title) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TvSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = Ash,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
internal fun TvSettingCard(
    title: String,
    subtitle: String,
    modifier: Modifier,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.02f else 1f, label = "settingsScale")

    Column(
        modifier = modifier
            .scale(scale)
            .background(
                color = if (focused) Graphite.copy(alpha = 0.5f) else Charcoal.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
            )
            .border(
                width = 1.5.dp,
                color = if (focused) Amber else Steel.copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    onClick()
                    try {
                        focusRequester.requestFocus()
                    } catch (_: IllegalStateException) {
                        // Card may have been removed by click action; caller can restore elsewhere.
                    }
                },
            ),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Silver,
                maxLines = 3,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

