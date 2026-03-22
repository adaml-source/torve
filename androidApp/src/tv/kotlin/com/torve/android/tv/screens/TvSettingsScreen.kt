package com.torve.android.tv.screens

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.torve.android.ui.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
// LocaleListCompat removed — no longer applying locale inline
import com.torve.android.R
import com.torve.android.sync.SyncCoordinator
import com.torve.data.account.AccountSettingsRepository
import com.torve.presentation.session.AccountSessionCoordinator
import com.torve.android.tv.settings.isTvReduceMotionEnabled
import com.torve.android.tv.settings.setTvReduceMotionEnabled
import com.torve.android.tv.components.TvClickToEditOutlinedTextField
import com.torve.android.tv.focus.TvSettingsFocusStateMachine
import com.torve.android.tv.focus.TvSettingsFocusTarget
import com.torve.android.tv.focus.TvSettingsItemIds
import com.torve.android.tv.focus.rememberRegisteredTvSettingsFocusRequester
import com.torve.android.tv.premium.TvEntitledFeature
import com.torve.android.tv.premium.TvPremiumAccess
import com.torve.android.ui.settings.AddonCategory
import com.torve.android.ui.settings.POPULAR_ADDONS
import com.torve.data.ai.AiProvider
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.StreamQuality
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.presentation.addon.AddonViewModel
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.mdblist.MdbListTab
import com.torve.presentation.mdblist.MdbListViewModel
import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.stats.StatsViewModel
import com.torve.presentation.subscription.PurchaseStatusTone
import com.torve.presentation.subscription.PurchaseVerificationState
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.android.tv.TvNotificationQueue
import com.torve.android.tv.NotificationType
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import com.torve.android.premium.rememberEffectivePremiumAccessTier
import org.koin.compose.koinInject

enum class TvSetupMode { ANDROID_PHONE, IOS_PHONE, TV_ONLY }

internal enum class TvSettingsCategory {
    ACCOUNT,
    PLAYBACK,
    APPEARANCE,
    LIBRARY,
    CONNECTIONS,
    ADVANCED,
    ABOUT,
}

internal enum class TvSettingRowType {
    NAVIGATION,
    TOGGLE,
    SELECTOR,
    ACTION,
    DANGEROUS,
}

internal enum class TvSettingEmphasis {
    PRIMARY,
    SECONDARY,
}

private enum class TvSettingsFocusMoveTarget {
    SELECTED_CATEGORY_CHIP,
    CATEGORY_CHIP,
    CATEGORY_DETAIL,
}

private data class TvSettingsFocusMoveRequest(
    val nonce: Int,
    val target: TvSettingsFocusMoveTarget,
    val category: TvSettingsCategory? = null,
    val delayMs: Long = 50L,
)

private const val PREF_KEY_OPEN_CONNECTIONS_ONCE = "tv_settings_open_connections_once"
private const val PREF_KEY_OPEN_SUBSCRIPTION_ONCE = "tv_settings_open_subscription_once"
private const val SETTINGS_FOCUS_LOG_TAG = "TvSettingsFocus"

private fun logSettingsFocus(
    message: String,
) {
    Log.i(SETTINGS_FOCUS_LOG_TAG, message)
}

private fun settingsCategoryFromRowKey(
    rowKey: String?,
): TvSettingsCategory? {
    if (rowKey.isNullOrBlank()) return null
    return TvSettingsCategory.entries.firstOrNull { it.name == rowKey }
}

@Composable
internal fun TvSettingsScreen(
    railFocusRequester: FocusRequester,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    mainEntryFocusRequester: FocusRequester? = null,
    homeLayoutFocusRequester: FocusRequester? = null,
    ratingsFocusRequester: FocusRequester? = null,
    onNavigateToHomeLayout: () -> Unit = {},
    onNavigateToRatings: () -> Unit = {},
    onNavigateToPairedDevices: () -> Unit = {},
    onNavigateToActivatedDevices: () -> Unit = {},
    onAuthSuccess: () -> Unit = {},
    pairedDevicesFocusRequester: FocusRequester? = null,
    activatedDevicesFocusRequester: FocusRequester? = null,
    settingsFocusController: TvSettingsFocusStateMachine,
    onRequestLifetimeUnlock: (TvEntitledFeature) -> Unit = {},
    openToChannelsTab: Boolean = false,
    onChannelsTabConsumed: () -> Unit = {},
    isActive: Boolean = true,
    isRailFocused: Boolean = false,
    syncCoordinator: SyncCoordinator = koinInject(),
    accountSessionCoordinator: AccountSessionCoordinator = koinInject(),
    accountSettingsRepository: AccountSettingsRepository = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    channelsViewModel: ChannelsViewModel = koinInject(),
    addonViewModel: AddonViewModel = koinInject(),
    mdbListViewModel: MdbListViewModel = koinInject(),
    subscriptionViewModel: SubscriptionViewModel = koinInject(),
    statsViewModel: StatsViewModel = koinInject(),
) {
    val syncState by syncCoordinator.state.collectAsState()
    val accountSettingsState by accountSettingsRepository.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val channelsState by channelsViewModel.state.collectAsState()
    val addonState by addonViewModel.state.collectAsState()
    val mdbListState by mdbListViewModel.state.collectAsState()
    val hasPairedPhone = syncState.devices.any { it.deviceType != "tv" && it.revokedAt == null }
    var aboutTapCount by remember { mutableIntStateOf(0) }
    var showDebugPanel by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmEnableDiagnostics by remember { mutableStateOf(false) }
    var confirmHideAllChannelGroups by remember { mutableStateOf(false) }

    // ── Auth state ──
    val authClient: com.torve.data.auth.AuthClient = koinInject()
    var authEmail by remember { mutableStateOf("") }
    var authPassword by remember { mutableStateOf("") }
    var authConfirmPassword by remember { mutableStateOf("") }
    var authIsLoading by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var authUser by remember { mutableStateOf<com.torve.data.auth.AuthUser?>(null) }
    var authShowRegister by remember { mutableStateOf(false) }
    val authScope = rememberCoroutineScope()

    // Check if already logged in, and sync account settings if so
    LaunchedEffect(Unit) {
        authUser = authClient.getAuthenticatedUser()
        if (authUser != null) {
            // Always force-fetch account settings on TV settings entry
            // so changes made on mobile are picked up immediately.
            runCatching {
                accountSettingsRepository.refreshIfStale(force = true)
                settingsViewModel.refreshSettings()
            }
        }
    }

    LaunchedEffect(subscriptionState.error) {
        subscriptionState.error?.let { message ->
            TvNotificationQueue.post(message, NotificationType.ERROR)
        }
    }

    LaunchedEffect(subscriptionState.purchaseStatus?.kind, subscriptionState.purchaseStatus?.message) {
        subscriptionState.purchaseStatus?.let { status ->
            val notificationType = when (status.tone) {
                PurchaseStatusTone.SUCCESS -> NotificationType.SUCCESS
                PurchaseStatusTone.ERROR -> NotificationType.ERROR
                PurchaseStatusTone.INFO -> NotificationType.INFO
            }
            TvNotificationQueue.post("${status.title}. ${status.message}", notificationType)
        }
    }

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

    // Channel manager
    var showChannelManager by remember { mutableStateOf(false) }
    var expandedCountry by remember { mutableStateOf<String?>(null) }

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
    var reduceMotionEnabled by remember(context) { mutableStateOf(isTvReduceMotionEnabled(context)) }
    val prefs = remember { context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE) }
    var setupMode by remember {
        mutableStateOf<TvSetupMode?>(
            prefs.getString("setup_mode", TvSetupMode.TV_ONLY.name)
                ?.let { raw -> runCatching { TvSetupMode.valueOf(raw) }.getOrDefault(TvSetupMode.TV_ONLY) }
                ?: TvSetupMode.TV_ONLY,
        )
    }
    val selectedCategory = settingsFocusController.selectedCategory
    val settingsListState = rememberLazyListState()
    val pendingSettingsOrigin = settingsFocusController.pendingRestore
    val hasPendingExactSettingsRestore = pendingSettingsOrigin != null

    LaunchedEffect(openToChannelsTab) {
        if (openToChannelsTab) {
            settingsFocusController.selectedCategory = TvSettingsCategory.LIBRARY
            onChannelsTabConsumed()
        }
    }
    val accessTier = rememberEffectivePremiumAccessTier(subscriptionState.isPro)
    val isLockedFeature: (TvEntitledFeature) -> Boolean = { feature ->
        TvPremiumAccess.isPremiumLocked(feature, accessTier)
    }
    val runPremiumAction: (TvEntitledFeature, () -> Unit) -> Unit = { feature, action ->
        if (isLockedFeature(feature)) {
            onRequestLifetimeUnlock(feature)
        } else {
            action()
        }
    }

    LaunchedEffect(selectedCategory) {
        logSettingsFocus(
            "selected_category_changed category=${selectedCategory.name} " +
                "pendingRestore=${pendingSettingsOrigin?.restoreToken ?: -1L}",
        )
        confirmSignOut = false
        confirmEnableDiagnostics = false
        confirmHideAllChannelGroups = false
        // Re-read auth user when returning to Account tab and refresh verification status
        if (selectedCategory == TvSettingsCategory.ACCOUNT) {
            authUser = authClient.getAuthenticatedUser()
            if (authUser?.isVerified == false) {
                val verified = authClient.checkVerificationStatus()
                if (verified) {
                    authUser = authClient.getAuthenticatedUser()
                }
            }
        }
    }

    LaunchedEffect(setupMode) {
        val modeName = setupMode?.name ?: TvSetupMode.TV_ONLY.name
        prefs.edit().putString("setup_mode", modeName).apply()
    }

    // Stable focus anchor for settings content.
    // This requester is attached to the first actionable card in each settings mode,
    // so moving focus right from the nav rail always lands on a real focus target.
    val settingsContentRequester = mainEntryFocusRequester ?: remember { FocusRequester() }
    val categoryRequesters = remember {
        TvSettingsCategory.entries.associateWith { FocusRequester() }
    }
    val pairingCardRequester = remember { FocusRequester() }
    val channelsTopRequester = remember { FocusRequester() }
    val maxQualityCardRequester = remember { FocusRequester() }
    val minQualityCardRequester = remember { FocusRequester() }
    val reduceMotionCardRequester = remember { FocusRequester() }
    val languageCardRequester = remember { FocusRequester() }
    val aboutVersionCardRequester = remember { FocusRequester() }
    val advancedPhoneEntryRequester = remember { FocusRequester() }
    val advancedTvEntryRequester = remember { FocusRequester() }
    val subscriptionCardRequester = remember { FocusRequester() }
    var categoryPaneHasFocus by remember { mutableStateOf(false) }
    val addPlaylistCardRequester = remember { FocusRequester() }
    val editPlaylistEpgCardRequester = remember { FocusRequester() }
    val addKodiCardRequester = remember { FocusRequester() }
    val channelManagerShowAllRequester = remember { FocusRequester() }
    val channelManagerHideAllRequester = remember { FocusRequester() }
    val addAddonCardRequester = remember { FocusRequester() }
    val authAccountRequester = remember { FocusRequester() }
    val authEmailRequester = remember { FocusRequester() }

    // Restore focus after addon installation completes.
    // The installed card is removed from composition — we focus the next suggested
    // addon at the same position, or fall back to the "Add Addon" card.
    val prevInstalling = remember { mutableStateOf(false) }
    val installedAddonIndex = remember { mutableStateOf(-1) }
    val suggestedAddonRequesters = remember { mutableMapOf<String, FocusRequester>() }
    LaunchedEffect(addonState.isInstalling) {
        if (prevInstalling.value && !addonState.isInstalling) {
            // Allow recomposition to settle (item removal + layout)
            kotlinx.coroutines.delay(80)
            val idx = installedAddonIndex.value
            val installedUrls = addonState.addons.map { it.manifestUrl }.toSet()
            val currentSuggested = POPULAR_ADDONS.filter { it.url !in installedUrls &&
                (addonCategory == AddonCategory.ALL || addonCategory in it.categories) }
            // Focus the addon now at the installed position, or the last one, or the add-card
            val target = currentSuggested.getOrNull(idx.coerceAtMost(currentSuggested.lastIndex))
            val requester = target?.let { suggestedAddonRequesters[it.url] }
            runCatching { (requester ?: addAddonCardRequester).requestFocus() }
            installedAddonIndex.value = -1
        }
        prevInstalling.value = addonState.isInstalling
    }

    LaunchedEffect(showChannelManager) {
        if (showChannelManager) {
            channelManagerShowAllRequester.requestFocus()
        } else {
            confirmHideAllChannelGroups = false
        }
    }
    val pairedDevicesTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIRED_DEVICES,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 1,
            focusTargetType = "card",
        )
    }
    val activatedDevicesTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_ACTIVATED_DEVICES,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 2,
            focusTargetType = "card",
        )
    }
    val maxQualityTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_MAX_QUALITY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 1,
            focusTargetType = "selector",
        )
    }
    val minQualityTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_MIN_QUALITY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 2,
            focusTargetType = "selector",
        )
    }
    val languageTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_LANGUAGE,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 2,
            focusTargetType = "selector",
        )
    }
    val homeLayoutTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_HOME_LAYOUT,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 4,
            focusTargetType = "navigation",
        )
    }
    val ratingsTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_RATINGS,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 5,
            focusTargetType = "navigation",
        )
    }
    val pairDeviceTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIR_DEVICE,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 0,
            focusTargetType = "action",
        )
    }
    val authAccountTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_ACCOUNT,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 10,
            focusTargetType = "card",
        )
    }
    val authEmailTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_EMAIL,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 10,
            focusTargetType = "input",
        )
    }
    val authLogoutTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_LOGOUT,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 12,
            focusTargetType = "action",
        )
    }
    val authSubmitTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_SUBMIT,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 13,
            focusTargetType = "action",
        )
    }
    val reduceMotionTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_REDUCE_MOTION,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 1,
            focusTargetType = "toggle",
        )
    }
    var previousPlaylistCount by remember { mutableIntStateOf(channelsState.playlists.size) }
    var previousShowAddPlaylist by remember { mutableStateOf(showAddPlaylist) }
    LaunchedEffect(isActive, isRailFocused, hasPendingExactSettingsRestore) {
        if (hasPendingExactSettingsRestore) return@LaunchedEffect
        if (!isActive || isRailFocused) return@LaunchedEffect
        if (prefs.getBoolean(PREF_KEY_OPEN_SUBSCRIPTION_ONCE, false)) {
            prefs.edit().putBoolean(PREF_KEY_OPEN_SUBSCRIPTION_ONCE, false).apply()
            settingsFocusController.selectedCategory = TvSettingsCategory.ACCOUNT
            settingsListState.scrollToItem(0)
            kotlinx.coroutines.delay(80)
            runCatching { subscriptionCardRequester.requestFocus() }
            return@LaunchedEffect
        }
        if (prefs.getBoolean(PREF_KEY_OPEN_CONNECTIONS_ONCE, false)) {
            prefs.edit().putBoolean(PREF_KEY_OPEN_CONNECTIONS_ONCE, false).apply()
            settingsFocusController.selectedCategory = TvSettingsCategory.CONNECTIONS
            settingsListState.scrollToItem(0)
            kotlinx.coroutines.delay(40)
            runCatching { categoryRequesters.getValue(TvSettingsCategory.CONNECTIONS).requestFocus() }
        }
    }

    // When setup mode changes, the LazyColumn swaps — refocus the first card
    LaunchedEffect(
        showLanguagePicker,
        showMaxQualityPicker,
        showMinQualityPicker,
        settingsFocusController.pendingRestore?.restoreToken,
        isActive,
    ) {
        if (!isActive) return@LaunchedEffect
        if (showLanguagePicker || showMaxQualityPicker || showMinQualityPicker) {
            return@LaunchedEffect
        }
        pendingSettingsOrigin?.let { origin ->
            if (settingsFocusController.selectedCategory != origin.category) {
                logSettingsFocus(
                    "restore_select_category targetCategory=${origin.category.name} " +
                        "token=${origin.restoreToken} reason=${origin.reason}",
                )
                settingsFocusController.selectedCategory = origin.category
            }
            settingsFocusController.restorePendingFocus(
                outerListState = settingsListState,
                isScreenActive = { isActive },
            )
        }
    }

    val playlistFormRequester = remember { FocusRequester() }

    LaunchedEffect(showAddPlaylist) {
        if (previousShowAddPlaylist && !showAddPlaylist) {
            onContentFocused(addPlaylistCardRequester)
        }
        if (!previousShowAddPlaylist && showAddPlaylist) {
            // Scroll to form and focus first interactive element
            kotlinx.coroutines.delay(100)
            val formIndex = settingsListState.layoutInfo.totalItemsCount - 1
            if (formIndex >= 0) {
                settingsListState.animateScrollToItem(
                    (formIndex - 2).coerceAtLeast(0),
                )
            }
            kotlinx.coroutines.delay(50)
            runCatching { playlistFormRequester.requestFocus() }
        }
        previousShowAddPlaylist = showAddPlaylist
    }

    LaunchedEffect(channelsState.playlists.size) {
        val currentCount = channelsState.playlists.size
        if (currentCount != previousPlaylistCount) {
            if (showAddPlaylist) {
                showAddPlaylist = false
            }
            onContentFocused(addPlaylistCardRequester)
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

    LaunchedEffect(hasPairedPhone, syncState.pairingCode, syncState.isLoading, syncState.error, accessTier) {
        if (
            !TvPremiumAccess.isPremiumLocked(TvEntitledFeature.PHONE_PAIRING, accessTier) &&
            !hasPairedPhone &&
            syncState.pairingCode == null &&
            !syncState.isLoading &&
            syncState.error == null
        ) {
            syncCoordinator.startTvPairingFlow()
        }
    }

    // String resources captured in composition scope
    val cloudServiceLabel = stringResource(R.string.tv_settings_cloud_service)
    val traktLabel = stringResource(R.string.tv_settings_trakt)
    val simklLabel = stringResource(R.string.tv_settings_simkl)
    val languageLabel = stringResource(R.string.tv_settings_language)
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
        syncState.error != null -> syncState.error!!
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
    val needsSetupLabel = stringResource(R.string.tv_settings_needs_setup)
    val categoryEntries = listOf(
        TvSettingsCategory.ACCOUNT to stringResource(R.string.tv_settings_category_account),
        TvSettingsCategory.PLAYBACK to stringResource(R.string.tv_settings_category_playback),
        TvSettingsCategory.APPEARANCE to stringResource(R.string.tv_settings_category_appearance),
        TvSettingsCategory.LIBRARY to stringResource(R.string.tv_settings_category_channels),
        TvSettingsCategory.CONNECTIONS to stringResource(R.string.tv_settings_category_connections),
        TvSettingsCategory.ADVANCED to stringResource(R.string.tv_settings_category_advanced),
        TvSettingsCategory.ABOUT to stringResource(R.string.tv_settings_category_about),
    )
    val categoryOrder = remember(categoryEntries) { categoryEntries.map { it.first } }
    val categoryLockFeature = remember {
        mapOf(
            TvSettingsCategory.ACCOUNT to TvEntitledFeature.ACCOUNT_SETUP,
            TvSettingsCategory.LIBRARY to TvEntitledFeature.PERSISTENT_COLLECTIONS,
            TvSettingsCategory.CONNECTIONS to TvEntitledFeature.CLOUD_PROVIDER_SETUP,
            TvSettingsCategory.ADVANCED to TvEntitledFeature.ADVANCED_CONNECTION_CONFIGURATION,
        )
    }
    val partiallyLockedCategories = remember {
        setOf(
            TvSettingsCategory.ACCOUNT,
            TvSettingsCategory.LIBRARY,
            TvSettingsCategory.CONNECTIONS,
        )
    }
    val categoryLockedState = remember(accessTier, categoryLockFeature) {
        categoryLockFeature.mapValues { (_, feature) ->
            TvPremiumAccess.isPremiumLocked(feature, accessTier)
        }
    }
    val libraryLocked = isLockedFeature(TvEntitledFeature.PERSISTENT_COLLECTIONS)
    val connectionsLocked = isLockedFeature(TvEntitledFeature.CLOUD_PROVIDER_SETUP)
    val advancedLocked = isLockedFeature(TvEntitledFeature.ADVANCED_CONNECTION_CONFIGURATION)

    val detailRequesterForCategory: (TvSettingsCategory) -> FocusRequester = {
        when (it) {
            TvSettingsCategory.ACCOUNT -> settingsContentRequester
            TvSettingsCategory.PLAYBACK -> maxQualityCardRequester
            TvSettingsCategory.APPEARANCE -> reduceMotionCardRequester
            TvSettingsCategory.LIBRARY -> channelsTopRequester
            TvSettingsCategory.CONNECTIONS -> pairingCardRequester
            TvSettingsCategory.ADVANCED -> {
                if (advancedLocked) {
                    advancedPhoneEntryRequester
                } else if (setupMode == TvSetupMode.TV_ONLY) {
                    advancedTvEntryRequester
                } else {
                    advancedPhoneEntryRequester
                }
            }
            TvSettingsCategory.ABOUT -> aboutVersionCardRequester
        }
    }
    val selectedCategoryFocusRequester = categoryRequesters.getValue(selectedCategory)

    LaunchedEffect(setupMode, selectedCategory, settingsFocusController.pendingRestore?.restoreToken, hasPendingExactSettingsRestore) {
        if (hasPendingExactSettingsRestore) return@LaunchedEffect
        val requester = if (setupMode == null) {
            settingsFocusController.entryRequesterForCurrentState() ?: settingsContentRequester
        } else {
            settingsFocusController.entryRequesterForCurrentState() ?: detailRequesterForCategory(selectedCategory)
        }
        logSettingsFocus(
            "publish_first_content category=${selectedCategory.name} " +
                "pendingRestore=${pendingSettingsOrigin?.restoreToken ?: -1L}",
        )
        onFirstContentRequester(requester)
    }

    if (setupMode == null) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
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
                    onClick = {
                        runPremiumAction(TvEntitledFeature.ACCOUNT_SETUP) {
                            setupMode = TvSetupMode.ANDROID_PHONE
                        }
                    },
                    premiumLocked = isLockedFeature(TvEntitledFeature.ACCOUNT_SETUP),
                )
            }
            item(key = "mode_ios") {
                val requester = remember("mode_ios") { FocusRequester() }
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_mode_ios),
                    subtitle = stringResource(R.string.tv_settings_mode_ios_desc),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = {
                        settingsFocusController.markFocused(authLogoutTarget.itemId, requester)
                        onContentFocused(requester)
                    },
                    onClick = {
                        runPremiumAction(TvEntitledFeature.ACCOUNT_SETUP) {
                            setupMode = TvSetupMode.IOS_PHONE
                        }
                    },
                    premiumLocked = isLockedFeature(TvEntitledFeature.ACCOUNT_SETUP),
                )
            }
            item(key = "mode_tv_only") {
                val requester = remember("mode_tv_only") { FocusRequester() }
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_mode_tv_only),
                    subtitle = stringResource(R.string.tv_settings_mode_tv_only_desc),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = {
                        settingsFocusController.markFocused(authSubmitTarget.itemId, requester)
                        onContentFocused(requester)
                    },
                    onClick = {
                        runPremiumAction(TvEntitledFeature.ACCOUNT_SETUP) {
                            setupMode = TvSetupMode.TV_ONLY
                        }
                    },
                    premiumLocked = isLockedFeature(TvEntitledFeature.ACCOUNT_SETUP),
                )
            }
        }
        return
    }

    // ── Main settings (mode selected) ──
    var isFirstItemFocused by remember { mutableStateOf(false) }
    var isLastItemFocused by remember { mutableStateOf(false) }
    val lastItemRequester = remember { FocusRequester() }
    var focusMoveNonce by remember { mutableIntStateOf(0) }
    var pendingFocusMove by remember { mutableStateOf<TvSettingsFocusMoveRequest?>(null) }
    LaunchedEffect(selectedCategory) {
        isFirstItemFocused = false
    }
    LaunchedEffect(pendingFocusMove) {
        val request = pendingFocusMove ?: return@LaunchedEffect
        try {
            settingsListState.scrollToItem(0)
            if (request.delayMs > 0) {
                kotlinx.coroutines.delay(request.delayMs)
            }
            val requester = when (request.target) {
                TvSettingsFocusMoveTarget.SELECTED_CATEGORY_CHIP -> selectedCategoryFocusRequester
                TvSettingsFocusMoveTarget.CATEGORY_CHIP -> categoryRequesters.getValue(
                    request.category ?: selectedCategory,
                )
                TvSettingsFocusMoveTarget.CATEGORY_DETAIL -> detailRequesterForCategory(
                    request.category ?: selectedCategory,
                )
            }
            runCatching { requester.requestFocus() }
                .onFailure { Log.w("TvSettings", "Deferred focus request failed for target=${request.target} category=${request.category ?: selectedCategory}: ${it.message}") }
        } finally {
            if (pendingFocusMove?.nonce == request.nonce) {
                pendingFocusMove = null
            }
        }
    }
    // Report the selected category chip as the content entry point so
    // Right/OK from the NavRail lands on the chip, not a LazyColumn card.
    LaunchedEffect(selectedCategory) {
        onFirstContentRequester(selectedCategoryFocusRequester)
    }

    BackHandler(
        enabled = isActive &&
            !isRailFocused &&
            !hasPendingExactSettingsRestore &&
            !showLanguagePicker &&
            !showMaxQualityPicker &&
            !showMinQualityPicker,
    ) {
        if (categoryPaneHasFocus) {
            runCatching { railFocusRequester.requestFocus() }
        } else {
            focusMoveNonce += 1
            pendingFocusMove = TvSettingsFocusMoveRequest(
                nonce = focusMoveNonce,
                target = TvSettingsFocusMoveTarget.SELECTED_CATEGORY_CHIP,
            )
        }
    }
    LazyColumn(
        state = settingsListState,
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (hasPendingExactSettingsRestore) {
                    logSettingsFocus(
                        "list_key_suppressed key=${event.key} token=${pendingSettingsOrigin?.restoreToken ?: -1L} " +
                            "reason=${pendingSettingsOrigin?.reason ?: "none"}",
                    )
                    return@onPreviewKeyEvent false
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val totalItems = settingsListState.layoutInfo.totalItemsCount
                if (totalItems == 0) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        if (isFirstItemFocused) {
                            focusMoveNonce += 1
                            pendingFocusMove = TvSettingsFocusMoveRequest(
                                nonce = focusMoveNonce,
                                target = TvSettingsFocusMoveTarget.CATEGORY_CHIP,
                                category = selectedCategory,
                            )
                            true
                        } else false
                    }
                    Key.DirectionDown -> {
                        if (isLastItemFocused) {
                            focusMoveNonce += 1
                            pendingFocusMove = TvSettingsFocusMoveRequest(
                                nonce = focusMoveNonce,
                                target = TvSettingsFocusMoveTarget.CATEGORY_DETAIL,
                                category = selectedCategory,
                            )
                            true
                        } else false
                    }
                    else -> false
                }
            },
        contentPadding = PaddingValues(start = 40.dp, top = 20.dp, end = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Setup mode header — tap to change
        item(key = "category_selector") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                items(categoryEntries, key = { "cat_${it.first.name}" }) { (category, label) ->
                    val requester = categoryRequesters.getValue(category)
                    val isCategoryLocked = categoryLockedState[category] == true
                    val badge = when {
                        isCategoryLocked && category in partiallyLockedCategories -> "Locked items"
                        isCategoryLocked && category == TvSettingsCategory.ADVANCED -> TvPremiumAccess.LOCKED_LABEL
                        category == TvSettingsCategory.ACCOUNT -> {
                            if (authUser != null) connectedLabel else needsSetupLabel
                        }
                        category == TvSettingsCategory.CONNECTIONS -> {
                            if (settingsState.debridConnected || settingsState.traktConnected || settingsState.simklConnected) {
                                connectedLabel
                            } else {
                                needsSetupLabel
                            }
                        }
                        category == TvSettingsCategory.ADVANCED -> {
                            if (
                                settingsState.omdbApiKey.isNotBlank() ||
                                settingsState.mdblistApiKey.isNotBlank() ||
                                settingsState.activeAiApiKey.isNotBlank()
                            ) {
                                connectedLabel
                            } else {
                                needsSetupLabel
                            }
                        }
                        else -> null
                    }
                    TvSettingsTopCategoryChip(
                        title = label,
                        badge = badge,
                        selected = selectedCategory == category,
                        isLocked = isCategoryLocked,
                        modifier = Modifier
                            .focusRequester(requester)
                            .focusProperties { left = railFocusRequester }
                            .onPreviewKeyEvent { event ->
                                if (hasPendingExactSettingsRestore) {
                                    logSettingsFocus(
                                        "category_key_suppressed category=${category.name} key=${event.key} " +
                                            "token=${pendingSettingsOrigin?.restoreToken ?: -1L}",
                                    )
                                    return@onPreviewKeyEvent false
                                }
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionLeft,
                                    Key.DirectionRight -> {
                                        val currentIndex = categoryOrder.indexOf(category)
                                        if (currentIndex < 0 || categoryOrder.isEmpty()) return@onPreviewKeyEvent false
                                        val step = if (event.key == Key.DirectionLeft) -1 else 1
                                        val targetIndex = currentIndex + step
                                        if (targetIndex !in categoryOrder.indices) {
                                            return@onPreviewKeyEvent false
                                        }
                                        val targetCategory = categoryOrder[targetIndex]
                                        settingsFocusController.selectedCategory = targetCategory
                                        focusMoveNonce += 1
                                        pendingFocusMove = TvSettingsFocusMoveRequest(
                                            nonce = focusMoveNonce,
                                            target = TvSettingsFocusMoveTarget.CATEGORY_CHIP,
                                            category = targetCategory,
                                            delayMs = 40L,
                                        )
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        settingsFocusController.selectedCategory = category
                                        focusMoveNonce += 1
                                        pendingFocusMove = TvSettingsFocusMoveRequest(
                                            nonce = focusMoveNonce,
                                            target = TvSettingsFocusMoveTarget.CATEGORY_DETAIL,
                                            category = category,
                                        )
                                        true
                                    }
                                    else -> false
                                }
                            },
                        onFocused = {
                            if (!hasPendingExactSettingsRestore) {
                                settingsFocusController.selectedCategory = category
                            } else {
                                logSettingsFocus(
                                    "category_focus_ignored category=${category.name} " +
                                        "token=${pendingSettingsOrigin?.restoreToken ?: -1L}",
                                )
                            }
                        },
                        onFocusStateChanged = { focused -> categoryPaneHasFocus = focused },
                        onClick = {
                            if (!hasPendingExactSettingsRestore) {
                                settingsFocusController.selectedCategory = category
                            }
                        },
                    )
                }
            }
        }

        if (selectedCategory == TvSettingsCategory.LIBRARY && libraryLocked) {
            item(key = "library_locked_header") {
                TvSectionHeader(
                    text = stringResource(R.string.tv_settings_channels_locked_title),
                    description = stringResource(R.string.tv_settings_channels_locked_desc),
                )
            }
            item(key = "library_locked_card") {
                Box(
                    Modifier.onFocusChanged {
                        isFirstItemFocused = it.hasFocus
                        isLastItemFocused = it.hasFocus
                    },
                ) {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_premium_channels),
                        subtitle = TvPremiumAccess.LIFETIME_REQUIRED_LABEL,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            up = categoryRequesters.getValue(TvSettingsCategory.LIBRARY)
                        },
                        focusRequester = channelsTopRequester,
                        onFocused = { onContentFocused(channelsTopRequester) },
                        onClick = { onRequestLifetimeUnlock(TvEntitledFeature.PERSISTENT_COLLECTIONS) },
                        rowType = TvSettingRowType.NAVIGATION,
                        premiumLocked = true,
                    )
                }
            }
        }

        if (selectedCategory == TvSettingsCategory.CONNECTIONS && connectionsLocked) {
            item(key = "connections_locked_header") {
                TvSectionHeader(
                    text = stringResource(R.string.tv_settings_connections_locked_title),
                    description = stringResource(R.string.tv_settings_connections_locked_desc),
                )
            }
            item(key = "connections_locked_card") {
                Box(
                    Modifier.onFocusChanged {
                        isFirstItemFocused = it.hasFocus
                        isLastItemFocused = it.hasFocus
                    },
                ) {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_premium_connections),
                        subtitle = TvPremiumAccess.LIFETIME_REQUIRED_LABEL,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            up = categoryRequesters.getValue(TvSettingsCategory.CONNECTIONS)
                        },
                        focusRequester = pairingCardRequester,
                        onFocused = { onContentFocused(pairingCardRequester) },
                        onClick = { onRequestLifetimeUnlock(TvEntitledFeature.CLOUD_PROVIDER_SETUP) },
                        rowType = TvSettingRowType.NAVIGATION,
                        premiumLocked = true,
                    )
                }
            }
        }

        if (selectedCategory == TvSettingsCategory.ADVANCED && advancedLocked) {
            item(key = "advanced_locked_header") {
                TvSectionHeader(
                    text = stringResource(R.string.tv_settings_advanced_locked_title),
                    description = stringResource(R.string.tv_settings_advanced_locked_desc),
                )
            }
            item(key = "advanced_locked_card") {
                Box(
                    Modifier.onFocusChanged {
                        isFirstItemFocused = it.hasFocus
                        isLastItemFocused = it.hasFocus
                    },
                ) {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_premium_advanced),
                        subtitle = TvPremiumAccess.LIFETIME_REQUIRED_LABEL,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            up = categoryRequesters.getValue(TvSettingsCategory.ADVANCED)
                        },
                        focusRequester = advancedPhoneEntryRequester,
                        onFocused = { onContentFocused(advancedPhoneEntryRequester) },
                        onClick = { onRequestLifetimeUnlock(TvEntitledFeature.ADVANCED_CONNECTION_CONFIGURATION) },
                        rowType = TvSettingRowType.NAVIGATION,
                        premiumLocked = true,
                    )
                }
            }
        }

        if (selectedCategory == TvSettingsCategory.ACCOUNT) {
            val pairedDeviceCount = syncState.devices.count { it.revokedAt == null && it.id != syncState.deviceId }
            item(key = "section_account_setup") {
                TvSectionHeader(
                    text = stringResource(R.string.tv_settings_section_pairing_sync),
                    description = stringResource(R.string.tv_settings_pairing_desc),
                )
            }
            item(key = "setup_mode") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = pairDeviceTarget,
                    externalRequester = settingsContentRequester,
                    isDefaultEntry = true,
                )
                Box(Modifier.onFocusChanged { isFirstItemFocused = it.hasFocus }) {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_pair_device),
                        subtitle = pairingSubtitle,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            up = categoryRequesters.getValue(TvSettingsCategory.ACCOUNT)
                        },
                        focusRequester = requester,
                        onFocused = {
                            settingsFocusController.markFocused(pairDeviceTarget.itemId, requester)
                            onContentFocused(requester)
                        },
                        onClick = {
                            runPremiumAction(TvEntitledFeature.PHONE_PAIRING) {
                                syncCoordinator.startTvPairingFlow()
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                        premiumLocked = isLockedFeature(TvEntitledFeature.PHONE_PAIRING),
                    )
                }
            }
            item(key = "account_paired_devices") {
                val baseRequester = pairedDevicesFocusRequester ?: remember("account_paired_devices") { FocusRequester() }
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = pairedDevicesTarget,
                    externalRequester = baseRequester,
                )
                val subtitle = if (pairedDeviceCount > 0) {
                    if (pairedDeviceCount == 1) stringResource(R.string.tv_settings_paired_count_one, pairedDeviceCount)
                    else stringResource(R.string.tv_settings_paired_count_other, pairedDeviceCount)
                } else {
                    stringResource(R.string.tv_settings_no_paired_yet)
                }
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_paired_devices),
                    subtitle = subtitle,
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = {
                        settingsFocusController.markFocused(pairedDevicesTarget.itemId, requester)
                        onContentFocused(requester)
                    },
                    onClick = {
                        logSettingsFocus("launch_subpage category=ACCOUNT row=ACCOUNT item=account_paired_devices reason=route_open")
                        settingsFocusController.captureOrigin(
                            itemId = pairedDevicesTarget.itemId,
                            outerListState = settingsListState,
                            reason = "route_open",
                        )
                        runPremiumAction(TvEntitledFeature.DEVICE_LINKING) {
                            onNavigateToPairedDevices()
                        }
                    },
                    rowType = TvSettingRowType.NAVIGATION,
                    premiumLocked = isLockedFeature(TvEntitledFeature.DEVICE_LINKING),
                )
            }
            item(key = "account_activated_devices") {
                val baseRequester = activatedDevicesFocusRequester ?: remember("account_activated_devices") { FocusRequester() }
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = activatedDevicesTarget,
                    externalRequester = baseRequester,
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_activated_devices),
                    subtitle = stringResource(R.string.tv_settings_activated_devices_subtitle),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = {
                        settingsFocusController.markFocused(activatedDevicesTarget.itemId, requester)
                        onContentFocused(requester)
                    },
                    onClick = {
                        logSettingsFocus("launch_subpage category=ACCOUNT row=ACCOUNT item=account_activated_devices reason=route_open")
                        settingsFocusController.captureOrigin(
                            itemId = activatedDevicesTarget.itemId,
                            outerListState = settingsListState,
                            reason = "route_open",
                        )
                        runPremiumAction(TvEntitledFeature.DEVICE_LINKING) {
                            onNavigateToActivatedDevices()
                        }
                    },
                    rowType = TvSettingRowType.NAVIGATION,
                    premiumLocked = isLockedFeature(TvEntitledFeature.DEVICE_LINKING),
                )
            }
            item(key = "account_sync_status") {
                val requester = remember("account_sync_status") { FocusRequester() }
                val accountSyncSubtitle = when {
                    accountSettingsState.isRefreshing -> "Syncing account settings..."
                    accountSettingsState.lastError != null -> "Last sync failed. Tap to retry."
                    accountSettingsState.lastFetchedAt != null -> "Settings synced via account sign-in."
                    else -> "Tap to sync shared settings from your account."
                }
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_account_sync_status),
                    subtitle = accountSyncSubtitle,
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = {
                        runPremiumAction(TvEntitledFeature.CROSS_DEVICE_SYNC) {
                            authScope.launch {
                                accountSettingsRepository.refreshIfStale(force = true)
                                settingsViewModel.refreshSettings()
                                TvNotificationQueue.post("Account settings refreshed.")
                            }
                        }
                    },
                    rowType = TvSettingRowType.ACTION,
                    premiumLocked = isLockedFeature(TvEntitledFeature.CROSS_DEVICE_SYNC),
                )
            }
            // Show account settings sync error (not pairing transport errors)
            accountSettingsState.lastError?.let { settingsSyncError ->
                item(key = "account_sync_error") {
                    val requester = remember("account_sync_error") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_sync_error),
                        subtitle = settingsSyncError,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            runPremiumAction(TvEntitledFeature.DEVICE_SYNC) {
                                authScope.launch {
                                    accountSettingsRepository.refreshIfStale(force = true)
                                    settingsViewModel.refreshSettings()
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                        premiumLocked = isLockedFeature(TvEntitledFeature.DEVICE_SYNC),
                    )
                }
            }

        // Account section
        item(key = "section_account") {
            TvSectionHeader(
                text = stringResource(R.string.tv_settings_section_profile_signin),
                description = stringResource(R.string.tv_settings_account_manage_desc),
            )
        }

        // Account benefit notice
        if (authUser == null) {
            item(key = "auth_info_banner") {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            com.torve.android.ui.theme.Amber.copy(alpha = 0.10f),
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        )
                        .border(
                            1.dp,
                            com.torve.android.ui.theme.Amber.copy(alpha = 0.25f),
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tv_settings_sign_in_share),
                        color = com.torve.android.ui.theme.Silver,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        // Torve account login/signup
        if (authUser != null) {
            // Logged in — show account info + logout
            item(key = "auth_account") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = authAccountTarget,
                    externalRequester = authAccountRequester,
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_torve_account),
                    subtitle = if (authUser?.isVerified == false)
                        "${authUser!!.email} (unverified)"
                    else authUser!!.email,
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = {
                        settingsFocusController.markFocused(authAccountTarget.itemId, requester)
                        onContentFocused(requester)
                    },
                    onClick = {},
                    emphasis = TvSettingEmphasis.SECONDARY,
                )
            }
            if (authUser?.isVerified == false) {
                item(key = "auth_verify") {
                    val requester = remember("auth_verify") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_email_not_verified),
                        subtitle = stringResource(R.string.tv_settings_resend_verification),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            val email = authUser?.email ?: return@TvSettingCard
                            authScope.launch {
                                val result = authClient.resendVerification(email)
                                if (result.success) {
                                    TvNotificationQueue.post("Verification email sent!", NotificationType.SUCCESS)
                                } else {
                                    TvNotificationQueue.post(result.error ?: "Failed to send", NotificationType.ERROR)
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                    )
                }
            }
            item(key = "auth_logout") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = authLogoutTarget,
                    externalRequester = remember("auth_logout") { FocusRequester() },
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_log_out),
                    subtitle = if (confirmSignOut) "Press again to sign out on this TV"
                        else "Sign out of your Torve account",
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = {
                        runPremiumAction(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD) {
                            if (confirmSignOut) {
                                authScope.launch {
                                    settingsFocusController.captureOrigin(
                                        itemId = authLogoutTarget.itemId,
                                        outerListState = settingsListState,
                                        reason = "confirm",
                                    )
                                    authClient.logout()
                                    accountSessionCoordinator.signOut()
                                    authUser = null
                                    authEmail = ""
                                    authPassword = ""
                                    confirmSignOut = false
                                    subscriptionViewModel.loadSubscription()
                                    settingsFocusController.requestRestore(
                                        itemId = authEmailTarget.itemId,
                                        reason = "confirm",
                                        outerListState = settingsListState,
                                    )
                                    TvNotificationQueue.post("Logged out", NotificationType.INFO)
                                }
                            } else {
                                confirmSignOut = true
                            }
                        }
                    },
                    rowType = TvSettingRowType.DANGEROUS,
                    focusedHint = "Requires a second press to avoid accidental sign-out.",
                    premiumLocked = isLockedFeature(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD),
                )
            }
            item(key = "auth_delete_account") {
                var showDeleteDialog by remember { mutableStateOf(false) }
                var isDeletingAccount by remember { mutableStateOf(false) }
                val deleteRequester = remember("auth_delete_account") { FocusRequester() }
                TvSettingCard(
                    title = stringResource(R.string.settings_delete_account),
                    subtitle = stringResource(R.string.tv_settings_delete_account_subtitle),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = deleteRequester,
                    onFocused = { onContentFocused(deleteRequester) },
                    onClick = { if (!isDeletingAccount) showDeleteDialog = true },
                    rowType = TvSettingRowType.DANGEROUS,
                )
                if (showDeleteDialog) {
                    AlertDialog(
                        onDismissRequest = { if (!isDeletingAccount) showDeleteDialog = false },
                        containerColor = Charcoal,
                        title = { Text(stringResource(R.string.tv_settings_delete_account_title), color = Snow) },
                        text = {
                            if (isDeletingAccount) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), color = Amber, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text(stringResource(R.string.settings_delete_account_deleting), color = Silver)
                                }
                            } else {
                                Text(stringResource(R.string.tv_settings_delete_account_body), color = Silver)
                            }
                        },
                        confirmButton = {
                            val cancelRequester = remember { FocusRequester() }
                            val confirmRequester = remember { FocusRequester() }
                            LaunchedEffect(Unit) { cancelRequester.requestFocus() }
                            Row {
                                androidx.compose.material3.TextButton(
                                    onClick = { showDeleteDialog = false },
                                    enabled = !isDeletingAccount,
                                    modifier = Modifier.focusRequester(cancelRequester),
                                ) { Text(stringResource(R.string.common_cancel), color = Silver) }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        isDeletingAccount = true
                                        authScope.launch {
                                            val result = authClient.deleteAccount()
                                            isDeletingAccount = false
                                            if (result.success) {
                                                showDeleteDialog = false
                                                accountSessionCoordinator.signOut()
                                                authUser = null
                                                authEmail = ""
                                                authPassword = ""
                                                subscriptionViewModel.loadSubscription()
                                                TvNotificationQueue.post(
                                                    context.getString(R.string.settings_delete_account_success),
                                                    NotificationType.SUCCESS,
                                                )
                                            } else {
                                                TvNotificationQueue.post(
                                                    result.error ?: context.getString(R.string.settings_delete_account_error_body),
                                                    NotificationType.ERROR,
                                                )
                                            }
                                        }
                                    },
                                    enabled = !isDeletingAccount,
                                    colors = ButtonDefaults.buttonColors(containerColor = Ruby, contentColor = Snow),
                                    modifier = Modifier.focusRequester(confirmRequester),
                                ) { Text(stringResource(R.string.settings_delete_account_confirm)) }
                            }
                        },
                        dismissButton = {},
                    )
                }
            }
        } else {
            // Not logged in — show email/password fields + login/register
            item(key = "auth_email") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = authEmailTarget,
                    externalRequester = authEmailRequester,
                )
                TvTextInputCard(
                    key = "auth_email",
                    title = stringResource(R.string.tv_settings_email),
                    value = authEmail,
                    focusRequester = requester,
                    expandedInput = expandedInput,
                    railFocusRequester = railFocusRequester,
                    onContentFocused = {
                        settingsFocusController.markFocused(authEmailTarget.itemId, requester)
                        onContentFocused(it)
                    },
                    onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                    onValueChange = { authEmail = it },
                    premiumFeature = TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD,
                    premiumLocked = isLockedFeature(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD),
                    onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD) },
                )
            }
            item(key = "auth_password") {
                TvTextInputCard(
                    key = "auth_password",
                    title = stringResource(R.string.tv_settings_password),
                    value = authPassword,
                    expandedInput = expandedInput,
                    railFocusRequester = railFocusRequester,
                    onContentFocused = onContentFocused,
                    onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                    onValueChange = { authPassword = it },
                    isPassword = true,
                    premiumFeature = TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD,
                    premiumLocked = isLockedFeature(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD),
                    onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD) },
                )
            }
            if (authShowRegister) {
                item(key = "auth_confirm_password") {
                    TvTextInputCard(
                        key = "auth_confirm_password",
                        title = stringResource(R.string.tv_settings_confirm_password),
                        value = authConfirmPassword,
                        expandedInput = expandedInput,
                        railFocusRequester = railFocusRequester,
                        onContentFocused = onContentFocused,
                        onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                        onValueChange = { authConfirmPassword = it },
                        isPassword = true,
                        premiumFeature = TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD,
                        premiumLocked = isLockedFeature(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD),
                        onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD) },
                    )
                }
            }
            item(key = "auth_submit") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = authSubmitTarget,
                    externalRequester = remember("auth_submit") { FocusRequester() },
                )
                TvSettingCard(
                    title = if (authShowRegister) "Create Account" else "Log In",
                    subtitle = if (authIsLoading) "Please wait…"
                              else if (authShowRegister) "Sign up with email and password"
                              else "Sign in to your Torve account",
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = {
                        runPremiumAction(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD) {
                            if (!authIsLoading) {
                                if (authShowRegister && authPassword != authConfirmPassword) {
                                    authError = "Passwords do not match"
                                    TvNotificationQueue.post("Passwords do not match", NotificationType.ERROR)
                                    return@runPremiumAction
                                }
                                authError = null
                                authIsLoading = true
                                authScope.launch {
                                    settingsFocusController.captureOrigin(
                                        itemId = authSubmitTarget.itemId,
                                        outerListState = settingsListState,
                                        reason = "confirm",
                                    )
                                    val result = if (authShowRegister) {
                                        authClient.register(authEmail, authPassword, null)
                                    } else {
                                        authClient.login(authEmail, authPassword)
                                    }
                                    authIsLoading = false
                                    if (result.success) {
                                        authUser = result.user
                                        authPassword = ""
                                        authConfirmPassword = ""
                                        subscriptionViewModel.loadSubscription()
                                        // Fetch and apply shared account settings (language, ratings, etc.)
                                        runCatching { accountSessionCoordinator.bootstrapAfterSignIn() }
                                        settingsViewModel.refreshSettings()
                                        settingsFocusController.requestRestore(
                                            itemId = authAccountTarget.itemId,
                                            reason = "confirm",
                                            outerListState = settingsListState,
                                        )
                                        onAuthSuccess()
                                        val msg = if (authShowRegister)
                                            "Account created! Check your email to verify your address."
                                        else "Logged in!"
                                        TvNotificationQueue.post(msg, NotificationType.SUCCESS)
                                    } else {
                                        authError = result.error
                                        TvNotificationQueue.post(result.error ?: "Failed", NotificationType.ERROR)
                                    }
                                }
                            }
                        }
                    },
                    rowType = TvSettingRowType.ACTION,
                    premiumLocked = isLockedFeature(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD),
                )
            }
            item(key = "auth_toggle") {
                val requester = remember("auth_toggle") { FocusRequester() }
                TvSettingCard(
                    title = if (authShowRegister) "Already have an account?" else "Don't have an account?",
                    subtitle = if (authShowRegister) "Switch to Log In" else "Switch to Sign Up",
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = {
                        runPremiumAction(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD) {
                            authShowRegister = !authShowRegister
                            authConfirmPassword = ""
                            authError = null
                        }
                    },
                    rowType = TvSettingRowType.NAVIGATION,
                    premiumLocked = isLockedFeature(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD),
                )
            }
            if (!authShowRegister) {
                item(key = "auth_forgot_password") {
                    val requester = remember("auth_forgot_password") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_forgot_password),
                        subtitle = stringResource(R.string.tv_settings_forgot_password_desc),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            if (!authIsLoading && authEmail.isNotBlank()) {
                                authError = null
                                authIsLoading = true
                                authScope.launch {
                                    val result = authClient.requestPasswordReset(authEmail)
                                    authIsLoading = false
                                    if (result.success) {
                                        TvNotificationQueue.post(
                                            "If that email exists, a reset link will be sent.",
                                            NotificationType.INFO,
                                        )
                                    } else {
                                        authError = result.error
                                        TvNotificationQueue.post(result.error ?: "Failed", NotificationType.ERROR)
                                    }
                                }
                            } else if (authEmail.isBlank()) {
                                authError = "Please enter your email first"
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                        premiumLocked = isLockedFeature(TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD),
                    )
                }
            }
            authError?.let { error ->
                item(key = "auth_error") {
                    Text(
                        text = error,
                        color = Ruby,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        }

        // Subscription and restore

        if (selectedCategory == TvSettingsCategory.ACCOUNT) {
            item(key = "section_account_subscription") {
                TvSectionHeader(
                    text = stringResource(R.string.tv_settings_subscription_title),
                    description = stringResource(R.string.tv_settings_subscription_desc),
                )
            }
            item(key = "subscription") {
            val requester = subscriptionCardRequester
            val billingManager: com.torve.android.billing.BillingManager = koinInject()
            val purchaseResult by billingManager.purchaseResult.collectAsState()
            val activity = LocalContext.current as? android.app.Activity
            val isAmazonBuild = com.torve.android.BuildConfig.FLAVOR.contains("amazon", ignoreCase = true)
            val purchasePlatform = if (isAmazonBuild) "amazon_fire_tv" else "google_play_tv"
            val purchaseStoreLabel = if (isAmazonBuild) "Amazon Appstore" else "Google Play"

            LaunchedEffect(Unit) { billingManager.initialize() }

            LaunchedEffect(purchaseResult) {
                when (val result = purchaseResult) {
                    is com.torve.android.billing.BillingManager.PurchaseResult.Success -> {
                        when (result.store) {
                            com.torve.android.billing.BillingManager.Store.AMAZON_APPSTORE -> {
                                subscriptionViewModel.verifyAmazonPurchase(
                                    receiptId = result.purchaseToken,
                                    amazonUserId = result.amazonUserId.orEmpty(),
                                    productId = result.productId.ifBlank { "com.torve.pro.lifetime" },
                                    platform = purchasePlatform,
                                )
                            }
                            com.torve.android.billing.BillingManager.Store.GOOGLE_PLAY -> {
                                subscriptionViewModel.verifyGooglePurchase(
                                    productId = "com.torve.pro.lifetime",
                                    purchaseToken = result.purchaseToken,
                                    platform = purchasePlatform,
                                )
                            }
                        }
                        billingManager.clearPurchaseResult()
                        TvNotificationQueue.post("Purchase received. Verifying Lifetime Access...", NotificationType.INFO)
                    }
                    is com.torve.android.billing.BillingManager.PurchaseResult.AlreadyOwned -> {
                        if (isAmazonBuild) {
                            subscriptionViewModel.restoreAmazonPurchases(platform = purchasePlatform)
                        } else {
                            subscriptionViewModel.restoreStorePurchases(
                                store = "google_play",
                                platform = purchasePlatform,
                                storeLabel = purchaseStoreLabel,
                            )
                        }
                        billingManager.clearPurchaseResult()
                        TvNotificationQueue.post("Restore started. Checking Lifetime Access...", NotificationType.INFO)
                    }
                    is com.torve.android.billing.BillingManager.PurchaseResult.Pending -> {
                        subscriptionViewModel.markAmazonPurchasePending(result.message)
                        billingManager.clearPurchaseResult()
                    }
                    is com.torve.android.billing.BillingManager.PurchaseResult.Cancelled -> {
                        TvNotificationQueue.post("Purchase cancelled.", NotificationType.INFO)
                        billingManager.clearPurchaseResult()
                    }
                    is com.torve.android.billing.BillingManager.PurchaseResult.Error -> {
                        TvNotificationQueue.post(result.message, NotificationType.ERROR)
                        billingManager.clearPurchaseResult()
                    }
                    else -> {}
                }
            }

            val formattedPrice = billingManager.getFormattedPrice()
            val purchaseBlocked = subscriptionState.purchaseVerificationState == PurchaseVerificationState.PENDING
            val subLabel = when {
                subscriptionState.isPro ->
                "Lifetime — Active"
                subscriptionState.purchaseStatus != null -> subscriptionState.purchaseStatus!!.title
                formattedPrice != null ->
                "Free — $formattedPrice for Lifetime"
                else ->
                "Free — Upgrade to Lifetime"
            }

            Column {
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_lifetime_access),
                    subtitle = subLabel,
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = {
                        if (!subscriptionState.isPro) {
                            if (purchaseBlocked) {
                                TvNotificationQueue.post(
                                    subscriptionState.purchaseStatus?.message
                                        ?: "Verification is already pending. Choose Retry Verification or Restore Purchase.",
                                    NotificationType.INFO,
                                )
                            } else {
                                subscriptionViewModel.requireAccountForPurchase(purchaseStoreLabel) {
                                    activity?.let { billingManager.launchPurchase(it) }
                                }
                            }
                        }
                    },
                    rowType = TvSettingRowType.ACTION,
                )

                subscriptionState.purchaseStatus?.let { status ->
                    TvStatusSummaryCard(
                        title = status.title,
                        message = status.message,
                        tone = status.tone,
                    )
                }

                if (!subscriptionState.isPro) {
                    if (subscriptionState.purchaseStatus?.showRetryVerification == true) {
                        val retryRequester = remember("subscription_retry") { FocusRequester() }
                        TvSettingCard(
                            title = stringResource(R.string.tv_settings_retry_verification),
                            subtitle = stringResource(R.string.tv_settings_retry_verification_desc),
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = retryRequester,
                            onFocused = { onContentFocused(retryRequester) },
                            onClick = { subscriptionViewModel.retryPendingAmazonVerification() },
                            rowType = TvSettingRowType.ACTION,
                            emphasis = TvSettingEmphasis.SECONDARY,
                        )
                    }

                    val restoreRequester = remember("subscription_restore") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_restore_purchase),
                        subtitle = stringResource(R.string.tv_settings_restore_purchase_desc),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = restoreRequester,
                        onFocused = { onContentFocused(restoreRequester) },
                        onClick = {
                            subscriptionViewModel.requireAccountForRestore(purchaseStoreLabel) {
                                billingManager.queryExistingPurchases()
                                if (isAmazonBuild) {
                                    subscriptionViewModel.restoreAmazonPurchases(platform = purchasePlatform)
                                } else {
                                    subscriptionViewModel.restoreStorePurchases(
                                        store = "google_play",
                                        platform = purchasePlatform,
                                        storeLabel = purchaseStoreLabel,
                                    )
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                    )
                }
            }
        }

        }

        when (setupMode) {
            TvSetupMode.ANDROID_PHONE, TvSetupMode.IOS_PHONE -> {
                // Phone mode: read-only service status managed by paired phone
                if (selectedCategory == TvSettingsCategory.CONNECTIONS && !connectionsLocked) {
                item(key = "section_connections_services") {
                    TvSectionHeader(
                        text = stringResource(R.string.tv_settings_linked_services),
                        description = stringResource(R.string.tv_settings_linked_services_desc),
                    )
                }
                item(key = "cloud_service") {
                    val requester = pairingCardRequester
                    val sub = if (settingsState.debridConnected) {
                        "${settingsState.debridProvider.label} — $connectedLabel"
                    } else notConnectedLabel
                    TvSettingCard(
                        title = cloudServiceLabel,
                        subtitle = sub,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            up = categoryRequesters.getValue(TvSettingsCategory.CONNECTIONS)
                        },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            onRequestLifetimeUnlock(TvEntitledFeature.CLOUD_PROVIDER_SETUP)
                        },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                        premiumLocked = isLockedFeature(TvEntitledFeature.CLOUD_PROVIDER_SETUP),
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
                        onClick = {
                            onRequestLifetimeUnlock(TvEntitledFeature.TRAKT_CONNECT)
                        },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                        premiumLocked = isLockedFeature(TvEntitledFeature.TRAKT_CONNECT),
                    )
                }
                }

                // Phone mode: read-only integration statuses
                if (selectedCategory == TvSettingsCategory.ADVANCED && !advancedLocked) {
                item(key = "section_advanced_phone_metadata") {
                    TvSectionHeader(
                        text = stringResource(R.string.tv_settings_metadata_providers),
                        description = stringResource(R.string.tv_settings_metadata_read_only),
                    )
                }
                item(key = "phone_omdb") {
                    val requester = advancedPhoneEntryRequester
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_omdb),
                        subtitle = if (settingsState.omdbApiKey.isNotBlank()) connectedLabel else notConnectedLabel,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            up = categoryRequesters.getValue(TvSettingsCategory.ADVANCED)
                        },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = { onRequestLifetimeUnlock(TvEntitledFeature.OMDB_SETUP) },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                        premiumLocked = isLockedFeature(TvEntitledFeature.OMDB_SETUP),
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
                        onClick = { onRequestLifetimeUnlock(TvEntitledFeature.MDBLIST_SETUP) },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                        premiumLocked = isLockedFeature(TvEntitledFeature.MDBLIST_SETUP),
                    )
                }

                item(key = "section_advanced_phone_integrations") {
                    TvSectionHeader(text = stringResource(R.string.tv_settings_integrations_title))
                }
                item(key = "phone_jellyfin") {
                    val requester = remember("phone_jellyfin") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_jellyfin),
                        subtitle = if (settingsState.jellyfinServerUrl.isNotBlank()) connectedLabel else notConnectedLabel,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = { onRequestLifetimeUnlock(TvEntitledFeature.JELLYFIN_SETUP) },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                        premiumLocked = isLockedFeature(TvEntitledFeature.JELLYFIN_SETUP),
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
                        onClick = { onRequestLifetimeUnlock(TvEntitledFeature.PLEX_SETUP) },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                        premiumLocked = isLockedFeature(TvEntitledFeature.PLEX_SETUP),
                    )
                }
                }

            }

            TvSetupMode.TV_ONLY -> {
                // TV-only: self-service device code auth

                // Cloud Provider (cycle)
                if (selectedCategory == TvSettingsCategory.CONNECTIONS && !connectionsLocked) {
                    item(key = "section_connections_tv_accounts") {
                        TvSectionHeader(
                            text = stringResource(R.string.tv_settings_streaming_accounts),
                            description = stringResource(R.string.tv_settings_streaming_accounts_desc),
                        )
                    }
                    item(key = "cloud_provider") {
                    val requester = pairingCardRequester
                    val providers = remember { DebridServiceType.entries.toList() }
                    val currentIdx = remember(settingsState.debridProvider) {
                        providers.indexOf(settingsState.debridProvider).coerceAtLeast(0)
                    }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_change_provider),
                        subtitle = settingsState.debridProvider.label,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            up = categoryRequesters.getValue(TvSettingsCategory.CONNECTIONS)
                        },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            runPremiumAction(TvEntitledFeature.CLOUD_PROVIDER_SETUP) {
                                val next = (currentIdx + 1) % providers.size
                                settingsViewModel.setDebridProvider(providers[next])
                            }
                        },
                        rowType = TvSettingRowType.SELECTOR,
                        premiumLocked = isLockedFeature(TvEntitledFeature.CLOUD_PROVIDER_SETUP),
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
                            runPremiumAction(TvEntitledFeature.CLOUD_PROVIDER_SETUP) {
                                if (!settingsState.debridConnected && !settingsState.isPollingDebrid) {
                                    settingsViewModel.startDebridDeviceAuth()
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                        premiumLocked = isLockedFeature(TvEntitledFeature.CLOUD_PROVIDER_SETUP),
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
                            runPremiumAction(TvEntitledFeature.TRAKT_CONNECT) {
                                if (!settingsState.traktConnected && !settingsState.isPollingTrakt) {
                                    settingsViewModel.startTraktDeviceAuth()
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                        premiumLocked = isLockedFeature(TvEntitledFeature.TRAKT_CONNECT),
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
                            runPremiumAction(TvEntitledFeature.SIMKL_CONNECT) {
                                if (!settingsState.simklConnected && !settingsState.isPollingSimkl) {
                                    settingsViewModel.startSimklDeviceAuth()
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                        premiumLocked = isLockedFeature(TvEntitledFeature.SIMKL_CONNECT),
                    )
                }

                // ── Integrations section (TV-only) ──
                }

                if (selectedCategory == TvSettingsCategory.ADVANCED && !advancedLocked) {
                    item(key = "section_integrations") {
                    TvSectionHeader(
                        text = stringResource(R.string.tv_settings_metadata_providers),
                        description = stringResource(R.string.tv_settings_metadata_keys_desc),
                    )
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
                        focusRequester = advancedTvEntryRequester,
                        upFocusRequester = categoryRequesters.getValue(TvSettingsCategory.ADVANCED),
                        premiumFeature = TvEntitledFeature.OMDB_SETUP,
                        premiumLocked = isLockedFeature(TvEntitledFeature.OMDB_SETUP),
                        onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.OMDB_SETUP) },
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
                        rowType = TvSettingRowType.ACTION,
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
                        premiumFeature = TvEntitledFeature.MDBLIST_SETUP,
                        premiumLocked = isLockedFeature(TvEntitledFeature.MDBLIST_SETUP),
                        onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.MDBLIST_SETUP) },
                    )
                }

                item(key = "section_advanced_integrations_tvonly") {
                    TvSectionHeader(
                        text = stringResource(R.string.tv_settings_integrations_title),
                        description = stringResource(R.string.tv_settings_integrations_desc),
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
                        premiumFeature = TvEntitledFeature.JELLYFIN_SETUP,
                        premiumLocked = isLockedFeature(TvEntitledFeature.JELLYFIN_SETUP),
                        onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.JELLYFIN_SETUP) },
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
                        premiumFeature = TvEntitledFeature.JELLYFIN_SETUP,
                        premiumLocked = isLockedFeature(TvEntitledFeature.JELLYFIN_SETUP),
                        onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.JELLYFIN_SETUP) },
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
                        rowType = TvSettingRowType.ACTION,
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
                        premiumFeature = TvEntitledFeature.PLEX_SETUP,
                        premiumLocked = isLockedFeature(TvEntitledFeature.PLEX_SETUP),
                        onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.PLEX_SETUP) },
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
                        premiumFeature = TvEntitledFeature.PLEX_SETUP,
                        premiumLocked = isLockedFeature(TvEntitledFeature.PLEX_SETUP),
                        onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.PLEX_SETUP) },
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
                        rowType = TvSettingRowType.ACTION,
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
                            rowType = TvSettingRowType.ACTION,
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
                        rowType = TvSettingRowType.NAVIGATION,
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
                                        onContentFocused(addKodiCardRequester)
                                    }
                                },
                            )
                        }
                    }
                }

                // ── AI Search section (TV-only) ──
                item(key = "section_ai") {
                    TvSectionHeader(
                        text = stringResource(R.string.tv_settings_ai_discovery),
                        description = stringResource(R.string.tv_settings_ai_discovery_desc),
                    )
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
                        rowType = TvSettingRowType.SELECTOR,
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
                        premiumFeature = TvEntitledFeature.AI_PROVIDER_SETUP,
                        premiumLocked = isLockedFeature(TvEntitledFeature.AI_PROVIDER_SETUP),
                        onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.AI_PROVIDER_SETUP) },
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
                        rowType = TvSettingRowType.ACTION,
                    )
                }

                // ── Addons section (TV-only) ──
                item(key = "section_addons") {
                    TvSectionHeader(
                        text = stringResource(R.string.tv_settings_external_addons),
                        description = stringResource(R.string.tv_settings_external_addons_desc),
                    )
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
                            rowType = if (isConfirming) TvSettingRowType.DANGEROUS else TvSettingRowType.TOGGLE,
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

                itemsIndexed(
                    filteredSuggested,
                    key = { _, s -> "suggested_${s.url}" },
                ) { index, suggested ->
                    val requester = remember("suggested_${suggested.url.hashCode()}") { FocusRequester() }
                    suggestedAddonRequesters[suggested.url] = requester
                    TvSettingCard(
                        title = suggested.name,
                        subtitle = suggested.description,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            installedAddonIndex.value = index
                            addonViewModel.setInstallUrl(suggested.url)
                            addonViewModel.installAddon()
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
                        rowType = TvSettingRowType.NAVIGATION,
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
                                    onContentFocused(addAddonCardRequester)
                                },
                            )
                        }
                    }
                }

                // ── MDBList Browse section (TV-only) ──
                item(key = "section_mdblist_browse") {
                    TvSectionHeader(
                        text = stringResource(R.string.tv_settings_metadata_collections),
                        description = stringResource(R.string.tv_settings_metadata_collections_desc),
                    )
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
                            rowType = TvSettingRowType.ACTION,
                            emphasis = TvSettingEmphasis.SECONDARY,
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
                                rowType = TvSettingRowType.TOGGLE,
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
                            val searchRequester = remember { FocusRequester() }
                            TvSettingCard(
                                title = stringResource(R.string.tv_settings_mdblist_popular),
                                subtitle = if (mdbListState.activeTab == MdbListTab.POPULAR) "●" else "",
                                modifier = Modifier.weight(1f).focusProperties {
                                    left = railFocusRequester
                                    right = searchRequester
                                },
                                focusRequester = popRequester,
                                onFocused = { onContentFocused(popRequester) },
                                onClick = {
                                    mdbListViewModel.setActiveTab(MdbListTab.POPULAR)
                                    if (mdbListState.topLists.isEmpty()) mdbListViewModel.loadTopLists()
                                },
                                rowType = TvSettingRowType.SELECTOR,
                            )
                            TvSettingCard(
                                title = stringResource(R.string.tv_settings_mdblist_search),
                                subtitle = if (mdbListState.activeTab == MdbListTab.SEARCH) "●" else "",
                                modifier = Modifier.weight(1f).focusProperties { left = popRequester },
                                focusRequester = searchRequester,
                                onFocused = { onContentFocused(searchRequester) },
                                onClick = { mdbListViewModel.setActiveTab(MdbListTab.SEARCH) },
                                rowType = TvSettingRowType.SELECTOR,
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
                                    rowType = TvSettingRowType.ACTION,
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
                            rowType = TvSettingRowType.ACTION,
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
            }

            null -> {} // unreachable
        }

        // ── Channels section (all modes) ──
        if (selectedCategory == TvSettingsCategory.LIBRARY && !libraryLocked) {
            item(key = "section_channels") {
            TvSectionHeader(
                text = stringResource(R.string.tv_settings_content_sources),
                description = stringResource(R.string.tv_settings_content_sources_desc),
            )
        }

        if (channelsState.playlists.isEmpty()) {
            item(key = "no_playlists") {
                val requester = channelsTopRequester
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_no_playlists),
                    subtitle = stringResource(R.string.tv_settings_tap_to_edit),
                    modifier = Modifier.fillMaxWidth().focusProperties {
                        left = railFocusRequester
                        up = categoryRequesters.getValue(TvSettingsCategory.LIBRARY)
                    },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = { showAddPlaylist = true },
                    rowType = TvSettingRowType.NAVIGATION,
                )
            }
        } else {
            itemsIndexed(
                channelsState.playlists,
                key = { _, playlist -> "playlist_${playlist.id}" },
            ) { index, playlist ->
                val requester = if (index == 0) {
                    channelsTopRequester
                } else {
                    remember("playlist_${playlist.id}") { FocusRequester() }
                }
                val isConfirming = confirmRemoveId == playlist.id
                TvSettingCard(
                    title = playlist.name,
                    subtitle = if (isConfirming) {
                        stringResource(R.string.tv_settings_playlist_confirm_remove)
                    } else {
                        "${playlist.channelCount} ${stringResource(R.string.tv_settings_section_channels)}"
                    },
                    modifier = Modifier.fillMaxWidth().focusProperties {
                        left = railFocusRequester
                        if (index == 0) {
                            up = categoryRequesters.getValue(TvSettingsCategory.LIBRARY)
                        }
                    },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = {
                        if (isConfirming) {
                            channelsViewModel.removePlaylist(playlist.id)
                            confirmRemoveId = null
                            onContentFocused(addPlaylistCardRequester)
                        } else {
                            confirmRemoveId = playlist.id
                        }
                    },
                    rowType = if (isConfirming) TvSettingRowType.DANGEROUS else TvSettingRowType.ACTION,
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
                            onContentFocused(editPlaylistEpgCardRequester)
                        }
                        showEditSelectedPlaylistEpg = !showEditSelectedPlaylistEpg
                    },
                    rowType = TvSettingRowType.NAVIGATION,
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
                        onContentFocused(addPlaylistCardRequester)
                    }
                    showAddPlaylist = !showAddPlaylist
                },
                rowType = TvSettingRowType.NAVIGATION,
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
                            onContentFocused(editPlaylistEpgCardRequester)
                        },
                    )
                }
            }
        }

        // ── Channel Manager ──
        item(key = "section_library_visibility") {
            TvSectionHeader(text = stringResource(R.string.tv_settings_channel_visibility))
        }

        item(key = "manage_channels") {
            val requester = remember("manage_channels") { FocusRequester() }
            val allCats = channelsState.allCategories
            val hiddenCount = allCats.count { it.name in channelsState.hiddenCategories }
            val visibleCount = allCats.size - hiddenCount
            TvSettingCard(
                title = stringResource(R.string.tv_settings_manage_channels),
                subtitle = if (allCats.isEmpty()) {
                    stringResource(R.string.tv_settings_no_categories)
                } else {
                    stringResource(R.string.tv_settings_channels_visible, visibleCount, allCats.size)
                },
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = { showChannelManager = !showChannelManager },
                rowType = TvSettingRowType.NAVIGATION,
            )
        }

        if (showChannelManager) {
            // Show All / Hide All buttons
            item(key = "channel_mgr_actions") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 4.dp).focusGroup(),
                ) {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_show_all),
                        subtitle = "",
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties {
                                left = railFocusRequester
                                right = channelManagerHideAllRequester
                            }
                            .onPreviewKeyEvent { event ->
                                when {
                                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> {
                                        channelManagerHideAllRequester.requestFocus()
                                        true
                                    }
                                    event.type == KeyEventType.KeyUp && event.key == Key.DirectionRight -> true
                                    else -> false
                                }
                            },
                        focusRequester = channelManagerShowAllRequester,
                        onFocused = { onContentFocused(channelManagerShowAllRequester) },
                        onClick = { channelsViewModel.showAllCategories() },
                        rowType = TvSettingRowType.ACTION,
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_hide_all),
                        subtitle = if (confirmHideAllChannelGroups) {
                            "Press again to hide all channel groups"
                        } else {
                            ""
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties { left = channelManagerShowAllRequester }
                            .onPreviewKeyEvent { event ->
                                when {
                                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> {
                                        channelManagerShowAllRequester.requestFocus()
                                        true
                                    }
                                    event.type == KeyEventType.KeyUp && event.key == Key.DirectionLeft -> true
                                    else -> false
                                }
                            },
                        focusRequester = channelManagerHideAllRequester,
                        onFocused = { onContentFocused(channelManagerHideAllRequester) },
                        onClick = {
                            if (confirmHideAllChannelGroups) {
                                channelsViewModel.hideAllCategories()
                                confirmHideAllChannelGroups = false
                            } else {
                                confirmHideAllChannelGroups = true
                            }
                        },
                        rowType = TvSettingRowType.DANGEROUS,
                    )
                }
            }

            if (channelsState.allCategories.isEmpty()) {
                item(key = "channel_mgr_loading") {
                    Text(
                        text = if (channelsState.isLoadingChannels) "Loading channel groups..." else "No channel groups found. Navigate to the Channels tab first to load your sources.",
                        color = Silver,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            // Group categories by country (computed outside LazyListScope)
            val allCats = channelsState.allCategories
            val hiddenCats = channelsState.hiddenCategories
            val countryCats = allCats
                .groupBy { it.countryCode?.uppercase() ?: "OTHER" }
                .toSortedMap(compareBy { if (it == "OTHER") "ZZZ" else it })

            countryCats.forEach { (country, cats) ->
                val visibleInCountry = cats.count { it.name !in hiddenCats }
                val allHiddenInCountry = visibleInCountry == 0
                val isExpanded = expandedCountry == country

                // Country header row — click to expand/collapse
                item(key = "country_$country") {
                    val req = remember("country_$country") { FocusRequester() }
                    TvSettingCard(
                        title = "$country  ($visibleInCountry / ${cats.size})",
                        subtitle = if (isExpanded) "Press to collapse" else "${cats.size} groups — press to expand",
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = req,
                        onFocused = { onContentFocused(req) },
                        onClick = {
                            expandedCountry = if (isExpanded) null else country
                        },
                        rowType = TvSettingRowType.NAVIGATION,
                    )
                }

                // Toggle all for this country when expanded
                if (isExpanded) {
                    item(key = "country_toggle_$country") {
                        val req = remember("country_toggle_$country") { FocusRequester() }
                        TvSettingCard(
                            title = if (allHiddenInCountry) "Show all $country" else "Hide all $country",
                            subtitle = "$visibleInCountry / ${cats.size} visible",
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = req,
                            onFocused = { onContentFocused(req) },
                            onClick = {
                                if (allHiddenInCountry) {
                                    channelsViewModel.showCountryCategories(country)
                                } else {
                                    channelsViewModel.hideCountryCategories(country)
                                }
                            },
                            rowType = TvSettingRowType.TOGGLE,
                        )
                    }
                }

                // Individual category rows when expanded
                if (isExpanded) {
                    items(cats, key = { "cat_${country}_${it.name}" }) { cat ->
                        val isHidden = cat.name in hiddenCats
                        val req = remember("cat_${cat.name}") { FocusRequester() }
                        TvSettingCard(
                            title = "    ${cat.name}",
                            subtitle = if (isHidden) {
                                stringResource(R.string.tv_live_hidden)
                            } else {
                                "${cat.channelCount} channels"
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusProperties { left = railFocusRequester },
                            focusRequester = req,
                            onFocused = { onContentFocused(req) },
                            onClick = { channelsViewModel.toggleHiddenCategory(cat.name) },
                            rowType = TvSettingRowType.TOGGLE,
                        )
                    }
                }
            }
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
                        ).forEachIndexed { pillIndex, (type, label) ->
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
                            val pillBorderColor by animateColorAsState(
                                targetValue = when {
                                    focused -> Amber
                                    selected -> Amber
                                    else -> Color.Transparent
                                },
                                label = "pillBorder",
                            )
                            Text(
                                text = label,
                                color = if (selected) Color.Black else Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .then(if (pillIndex == 0) Modifier.focusRequester(playlistFormRequester) else Modifier)
                                    .zIndex(if (focused) 1f else 0f)
                                    .scale(pillScale)
                                    .onFocusChanged { focused = it.isFocused }
                                    .background(bg, RoundedCornerShape(20.dp))
                                    .border(
                                        2.dp,
                                        pillBorderColor,
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

        // ── Stream Quality & Playback section (all modes) ──
        }

        if (selectedCategory == TvSettingsCategory.PLAYBACK) {
            item(key = "section_stream_quality") {
            TvSectionHeader(
                text = stringResource(R.string.tv_settings_section_quality),
                description = stringResource(R.string.tv_settings_section_quality_desc),
            )
        }

        item(key = "max_quality") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = maxQualityTarget,
                externalRequester = maxQualityCardRequester,
                isDefaultEntry = true,
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_max_quality),
                subtitle = settingsState.maxQuality.label,
                modifier = Modifier.fillMaxWidth().focusProperties {
                    left = railFocusRequester
                    up = categoryRequesters.getValue(TvSettingsCategory.PLAYBACK)
                },
                focusRequester = requester,
                onFocused = {
                    settingsFocusController.markFocused(maxQualityTarget.itemId, requester)
                    onContentFocused(requester)
                },
                onClick = {
                    logSettingsFocus("launch_selector category=PLAYBACK row=PLAYBACK item=max_quality reason=selector_open")
                    settingsFocusController.captureOrigin(
                        itemId = maxQualityTarget.itemId,
                        outerListState = settingsListState,
                        reason = "selector_open",
                    )
                    showMaxQualityPicker = true
                },
                rowType = TvSettingRowType.SELECTOR,
            )
        }

        item(key = "min_quality") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = minQualityTarget,
                externalRequester = minQualityCardRequester,
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_min_quality),
                subtitle = settingsState.minQuality.label,
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = {
                    settingsFocusController.markFocused(minQualityTarget.itemId, requester)
                    onContentFocused(requester)
                },
                onClick = {
                    logSettingsFocus("launch_selector category=PLAYBACK row=PLAYBACK item=min_quality reason=selector_open")
                    settingsFocusController.captureOrigin(
                        itemId = minQualityTarget.itemId,
                        outerListState = settingsListState,
                        reason = "selector_open",
                    )
                    showMinQualityPicker = true
                },
                rowType = TvSettingRowType.SELECTOR,
            )
        }

        item(key = "section_playback_behavior") {
            TvSectionHeader(text = stringResource(R.string.tv_settings_section_playback_behavior))
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
                rowType = TvSettingRowType.TOGGLE,
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
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        // Deduplicate Streams toggle
        item(key = "section_playback_streams") {
            TvSectionHeader(text = stringResource(R.string.tv_settings_section_stream_handling))
        }
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
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        // ── Language & Region section ──
        }

        if (selectedCategory == TvSettingsCategory.PLAYBACK) {
        item(key = "section_playback_preferences") {
            TvSectionHeader(text = stringResource(R.string.tv_settings_section_playback_preferences))
        }

        item(key = "playback_audio_mode") {
            val requester = remember("playback_audio_mode") { FocusRequester() }
            val modeLabel = when (channelsState.liveAudioOutputMode) {
                LiveAudioOutputMode.AUTO -> stringResource(R.string.tv_live_audio_mode_auto)
                LiveAudioOutputMode.PREFER_COMPATIBLE -> stringResource(R.string.tv_live_audio_mode_compatible)
                LiveAudioOutputMode.FORCE_STEREO_PCM -> stringResource(R.string.tv_live_audio_mode_stereo)
            }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_live_audio_mode),
                subtitle = modeLabel,
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    val nextMode = when (channelsState.liveAudioOutputMode) {
                        LiveAudioOutputMode.AUTO -> LiveAudioOutputMode.PREFER_COMPATIBLE
                        LiveAudioOutputMode.PREFER_COMPATIBLE -> LiveAudioOutputMode.FORCE_STEREO_PCM
                        LiveAudioOutputMode.FORCE_STEREO_PCM -> LiveAudioOutputMode.AUTO
                    }
                    channelsViewModel.setLiveAudioOutputMode(nextMode)
                },
                rowType = TvSettingRowType.SELECTOR,
            )
        }

        item(key = "playback_audio_passthrough") {
            val requester = remember("playback_audio_passthrough") { FocusRequester() }
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
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        item(key = "playback_audio_surround") {
            val requester = remember("playback_audio_surround") { FocusRequester() }
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
                rowType = TvSettingRowType.TOGGLE,
            )
        }
        }

        if (selectedCategory == TvSettingsCategory.APPEARANCE) {
            item(key = "section_appearance_display") {
                TvSectionHeader(text = stringResource(R.string.tv_settings_section_display))
            }
            item(key = "reduce_motion") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = reduceMotionTarget,
                externalRequester = reduceMotionCardRequester,
                isDefaultEntry = true,
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_reduce_motion),
                subtitle = if (reduceMotionEnabled) stringResource(R.string.common_on) else stringResource(R.string.common_off),
                modifier = Modifier.fillMaxWidth().focusProperties {
                    left = railFocusRequester
                    up = categoryRequesters.getValue(TvSettingsCategory.APPEARANCE)
                },
                focusRequester = requester,
                onFocused = {
                    settingsFocusController.markFocused(reduceMotionTarget.itemId, requester)
                    onContentFocused(requester)
                },
                onClick = {
                    reduceMotionEnabled = !reduceMotionEnabled
                    setTvReduceMotionEnabled(context, reduceMotionEnabled)
                },
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        item(key = "section_language_region") {
            TvSectionHeader(text = stringResource(R.string.tv_settings_section_language_region))
        }

        item(key = "language") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = languageTarget,
                externalRequester = languageCardRequester,
            )
            TvSettingCard(
                title = languageLabel,
                subtitle = settingsState.appLanguage.tvDisplayName(),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = {
                    settingsFocusController.markFocused(languageTarget.itemId, requester)
                    onContentFocused(requester)
                },
                onClick = {
                    logSettingsFocus("launch_selector category=APPEARANCE row=APPEARANCE item=language reason=selector_open")
                    settingsFocusController.captureOrigin(
                        itemId = languageTarget.itemId,
                        outerListState = settingsListState,
                        reason = "selector_open",
                    )
                    showLanguagePicker = true
                },
                rowType = TvSettingRowType.SELECTOR,
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
                    rowType = TvSettingRowType.SELECTOR,
                )
            }
        }

        // ── Content Management section ──
        item(key = "section_content") {
            TvSectionHeader(
                text = stringResource(R.string.tv_settings_section_home_experience),
                description = stringResource(R.string.tv_settings_section_home_experience_desc),
            )
        }

        // Home Layout navigation card
        item(key = "home_layout") {
            val baseRequester = homeLayoutFocusRequester ?: remember("home_layout") { FocusRequester() }
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = homeLayoutTarget,
                externalRequester = baseRequester,
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_home_layout),
                subtitle = stringResource(R.string.tv_settings_home_layout_subtitle),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = {
                    settingsFocusController.markFocused(homeLayoutTarget.itemId, requester)
                    onContentFocused(requester)
                },
                onClick = {
                    logSettingsFocus("launch_subpage category=APPEARANCE row=APPEARANCE item=home_layout reason=route_open")
                    settingsFocusController.captureOrigin(
                        itemId = homeLayoutTarget.itemId,
                        outerListState = settingsListState,
                        reason = "route_open",
                    )
                    runPremiumAction(TvEntitledFeature.SYNC_CUSTOM_LAYOUTS) {
                        onNavigateToHomeLayout()
                    }
                },
                rowType = TvSettingRowType.NAVIGATION,
                premiumLocked = isLockedFeature(TvEntitledFeature.SYNC_CUSTOM_LAYOUTS),
            )
        }

        }

        if (selectedCategory == TvSettingsCategory.APPEARANCE) {
            item(key = "section_appearance_ratings") {
                TvSectionHeader(text = stringResource(R.string.tv_settings_section_metadata_ratings))
            }
            item(key = "ratings") {
                val baseRequester = ratingsFocusRequester ?: remember("ratings") { FocusRequester() }
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = ratingsTarget,
                    externalRequester = baseRequester,
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_ratings),
                    subtitle = stringResource(R.string.tv_settings_ratings_subtitle),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = {
                        settingsFocusController.markFocused(ratingsTarget.itemId, requester)
                        onContentFocused(requester)
                    },
                    onClick = {
                        logSettingsFocus("launch_subpage category=APPEARANCE row=APPEARANCE item=ratings reason=route_open")
                        settingsFocusController.captureOrigin(
                            itemId = ratingsTarget.itemId,
                            outerListState = settingsListState,
                            reason = "route_open",
                        )
                        onNavigateToRatings()
                    },
                    rowType = TvSettingRowType.NAVIGATION,
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
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        // About section
        }

        if (selectedCategory == TvSettingsCategory.ADVANCED && !advancedLocked) {
            item(key = "section_advanced_diagnostics") {
                TvSectionHeader(
                    text = stringResource(R.string.tv_settings_diagnostics_dev),
                    description = stringResource(R.string.tv_settings_diagnostics_dev_desc),
                )
            }
            item(key = "advanced_diagnostics") {
                val requester = remember("advanced_diagnostics") { FocusRequester() }
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_diagnostics),
                    subtitle = when {
                        confirmEnableDiagnostics -> "Press again to enable diagnostics panel"
                        showDebugPanel -> "Visible"
                        else -> "Hidden"
                    },
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = {
                        if (!showDebugPanel && !confirmEnableDiagnostics) {
                            confirmEnableDiagnostics = true
                        } else {
                            showDebugPanel = !showDebugPanel
                            confirmEnableDiagnostics = false
                        }
                    },
                    rowType = TvSettingRowType.DANGEROUS,
                    focusedHint = "Use this for troubleshooting only.",
                )
            }
        }

        if (selectedCategory == TvSettingsCategory.ABOUT) {
            item(key = "section_about") {
            TvSectionHeader(
                text = stringResource(R.string.tv_settings_app_info),
                description = stringResource(R.string.tv_settings_app_info_desc),
            )
        }

        item(key = "about_version") {
            val requester = aboutVersionCardRequester
            val versionName = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            }.getOrDefault("1.0.0")
            TvSettingCard(
                title = versionLabel,
                subtitle = stringResource(R.string.tv_settings_tv_version, versionName),
                modifier = Modifier.fillMaxWidth().focusProperties {
                    left = railFocusRequester
                    up = categoryRequesters.getValue(TvSettingsCategory.ABOUT)
                },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    aboutTapCount++
                    if (aboutTapCount >= 5) {
                        showDebugPanel = !showDebugPanel
                        aboutTapCount = 0
                    }
                },
                rowType = TvSettingRowType.ACTION,
            )
        }

        item(key = "about_build") {
            val requester = remember("about_build") { FocusRequester() }
            val buildNumber = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            }.getOrDefault(0L)
            TvSettingCard(
                title = stringResource(R.string.tv_settings_build_number),
                subtitle = buildNumber.toString(),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {},
                emphasis = TvSettingEmphasis.SECONDARY,
            )
        }

        item(key = "section_about_support") {
            TvSectionHeader(text = stringResource(R.string.tv_settings_legal_support))
        }

        item(key = "about_support") {
            val requester = remember("about_support") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_support),
                subtitle = stringResource(R.string.tv_settings_support_desc),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://torve.tv/support"),
                    )
                    runCatching { context.startActivity(intent) }
                },
                rowType = TvSettingRowType.NAVIGATION,
            )
        }

        item(key = "about_terms") {
            val requester = remember("about_terms") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_terms),
                subtitle = stringResource(R.string.tv_settings_terms_desc),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://torve.tv/terms"),
                    )
                    runCatching { context.startActivity(intent) }
                },
                rowType = TvSettingRowType.NAVIGATION,
            )
        }

        item(key = "about_legal") {
            val requester = remember("about_legal") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_legal),
                subtitle = stringResource(R.string.tv_settings_legal_subtitle),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://torve.tv/privacy"),
                    )
                    runCatching { context.startActivity(intent) }
                },
                rowType = TvSettingRowType.NAVIGATION,
            )
        }

        item(key = "about_stats") {
            val requester = if (showDebugPanel) remember("about_stats") { FocusRequester() } else lastItemRequester
            val statsState by statsViewModel.state.collectAsState()
            LaunchedEffect(Unit) { statsViewModel.loadStats() }
            val hours = statsState.totalMinutes / 60
            Box(Modifier.onFocusChanged { if (!showDebugPanel) isLastItemFocused = it.hasFocus }) {
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_stats),
                    subtitle = "${statsState.totalMovies} movies · ${statsState.totalEpisodes} episodes · ${hours}h watched",
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = {},
                    emphasis = TvSettingEmphasis.SECONDARY,
                )
            }
        }

        }

        // Easter egg: debug panel
        if (showDebugPanel && selectedCategory == TvSettingsCategory.ADVANCED && !advancedLocked) {
            item(key = "section_debug") {
                TvSectionHeader(text = stringResource(R.string.tv_settings_debug))
            }

            item(key = "debug_sync") {
                Box(Modifier.onFocusChanged { isLastItemFocused = it.hasFocus }) {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_sync_status),
                        subtitle = "Transport: ${syncState.wsStatus}",
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = lastItemRequester,
                        onFocused = { onContentFocused(lastItemRequester) },
                        onClick = {},
                        emphasis = TvSettingEmphasis.SECONDARY,
                    )
                }
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
            items = languages.map { it.name to it.tvDisplayName() },
            selectedKey = settingsState.appLanguage.name,
            onSelect = { key ->
                val lang = AppLanguage.valueOf(key)
                logSettingsFocus("selector_confirm category=APPEARANCE row=APPEARANCE item=language reason=confirm")
                settingsViewModel.setAppLanguage(lang)
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(lang.code),
                )
                logSettingsFocus("language_applied code=${lang.code} display=${lang.tvDisplayName()}")
                settingsFocusController.selectedCategory = TvSettingsCategory.APPEARANCE
                showLanguagePicker = false
                settingsFocusController.requestRestore(
                    itemId = languageTarget.itemId,
                    reason = "confirm",
                    outerListState = settingsListState,
                )
                TvNotificationQueue.post(
                    context.getString(R.string.tv_settings_language_restart),
                    NotificationType.INFO,
                )
            },
            onDismiss = {
                logSettingsFocus("selector_dismiss category=APPEARANCE row=APPEARANCE item=language reason=back")
                settingsFocusController.selectedCategory = TvSettingsCategory.APPEARANCE
                showLanguagePicker = false
                settingsFocusController.requestRestore(
                    itemId = languageTarget.itemId,
                    reason = "back",
                    outerListState = settingsListState,
                )
            },
        )
    }

    if (showMaxQualityPicker) {
        TvListPickerOverlay(
            title = stringResource(R.string.tv_settings_select_quality),
            items = StreamQuality.selectable.map { it.name to it.label },
            selectedKey = settingsState.maxQuality.name,
            onSelect = { key ->
                logSettingsFocus("selector_confirm category=PLAYBACK row=PLAYBACK item=max_quality reason=confirm")
                settingsViewModel.setMaxQuality(StreamQuality.valueOf(key))
                settingsFocusController.selectedCategory = TvSettingsCategory.PLAYBACK
                showMaxQualityPicker = false
                settingsFocusController.requestRestore(
                    itemId = maxQualityTarget.itemId,
                    reason = "confirm",
                    outerListState = settingsListState,
                )
            },
            onDismiss = {
                logSettingsFocus("selector_dismiss category=PLAYBACK row=PLAYBACK item=max_quality reason=back")
                settingsFocusController.selectedCategory = TvSettingsCategory.PLAYBACK
                showMaxQualityPicker = false
                settingsFocusController.requestRestore(
                    itemId = maxQualityTarget.itemId,
                    reason = "back",
                    outerListState = settingsListState,
                )
            },
        )
    }

    if (showMinQualityPicker) {
        TvListPickerOverlay(
            title = stringResource(R.string.tv_settings_select_quality),
            items = StreamQuality.selectable.map { it.name to it.label },
            selectedKey = settingsState.minQuality.name,
            onSelect = { key ->
                logSettingsFocus("selector_confirm category=PLAYBACK row=PLAYBACK item=min_quality reason=confirm")
                settingsViewModel.setMinQuality(StreamQuality.valueOf(key))
                settingsFocusController.selectedCategory = TvSettingsCategory.PLAYBACK
                showMinQualityPicker = false
                settingsFocusController.requestRestore(
                    itemId = minQualityTarget.itemId,
                    reason = "confirm",
                    outerListState = settingsListState,
                )
            },
            onDismiss = {
                logSettingsFocus("selector_dismiss category=PLAYBACK row=PLAYBACK item=min_quality reason=back")
                settingsFocusController.selectedCategory = TvSettingsCategory.PLAYBACK
                showMinQualityPicker = false
                settingsFocusController.requestRestore(
                    itemId = minQualityTarget.itemId,
                    reason = "back",
                    outerListState = settingsListState,
                )
            },
        )
    }
}

private fun AppLanguage.tvDisplayName(): String = when (this) {
    AppLanguage.ENGLISH -> "English"
    AppLanguage.GERMAN -> "Deutsch"
    AppLanguage.SPANISH -> "Espanol"
    AppLanguage.FRENCH -> "Francais"
    AppLanguage.ITALIAN -> "Italiano"
    AppLanguage.PORTUGUESE -> "Portugues"
    AppLanguage.TURKISH -> "Turkce"
}

@Composable
private fun TvStatusSummaryCard(
    title: String,
    message: String,
    tone: PurchaseStatusTone,
) {
    val accent = when (tone) {
        PurchaseStatusTone.INFO -> Amber
        PurchaseStatusTone.SUCCESS -> Color(0xFF22C55E)
        PurchaseStatusTone.ERROR -> Ruby
    }
    val background = when (tone) {
        PurchaseStatusTone.INFO -> Graphite.copy(alpha = 0.45f)
        PurchaseStatusTone.SUCCESS -> Color(0xFF153725).copy(alpha = 0.9f)
        PurchaseStatusTone.ERROR -> Color(0xFF3C252B).copy(alpha = 0.9f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .border(2.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Snow,
        )
    }
}

@Composable
private fun TvSettingsTopCategoryChip(
    title: String,
    badge: String?,
    selected: Boolean,
    isLocked: Boolean = false,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onFocusStateChanged: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AmberLight
            selected -> Amber.copy(alpha = 0.65f)
            else -> Color.Transparent
        },
        label = "settingsCategoryChipBorder",
    )
    val backgroundColor = when {
        focused -> Graphite
        selected -> Gunmetal
        else -> Charcoal
    }

    Row(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .onFocusChanged {
                focused = it.isFocused
                onFocusStateChanged?.invoke(it.isFocused)
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused || selected) Snow else Silver,
            fontWeight = FontWeight.SemiBold,
        )
        if (isLocked) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = if (focused || selected) Amber else Silver,
                modifier = Modifier.width(14.dp),
            )
        }
        badge?.let { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = Ash,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.25f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
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
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Back || event.key == Key.Escape) -> {
                        onDismiss()
                        true
                    }
                    event.type == KeyEventType.KeyUp &&
                        (event.key == Key.Back || event.key == Key.Escape) -> {
                        true
                    }
                    else -> false
                }
            }
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
                .border(1.dp, Steel.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(Charcoal)
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
                    val pickerBorderColor by animateColorAsState(
                        targetValue = when {
                            focused -> Amber
                            isSelected -> Amber.copy(alpha = 0.5f)
                            else -> Color.Transparent
                        },
                        label = "pickerBorder",
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
                            .zIndex(if (focused) 1f else 0f)
                            .scale(scale)
                            .border(
                                width = 2.dp,
                                color = pickerBorderColor,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    focused -> Graphite
                                    isSelected -> Gunmetal
                                    else -> Color.Transparent
                                },
                            )
                            .focusRequester(requester)
                            .onFocusChanged { focused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                when {
                                    event.type == KeyEventType.KeyDown &&
                                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                                        onSelect(key)
                                        true
                                    }
                                    event.type == KeyEventType.KeyUp &&
                                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                                        true
                                    }
                                    else -> false
                                }
                            }
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
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AmberLight
            isSelected -> Amber.copy(alpha = 0.67f)
            else -> Color.Transparent
        },
        label = "catBorder",
    )
    val bgColor = when {
        focused -> Gunmetal
        isSelected -> Graphite
        else -> Charcoal
    }

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
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
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    isPassword: Boolean = false,
    premiumFeature: TvEntitledFeature? = null,
    premiumLocked: Boolean = false,
    onLockedClick: (() -> Unit)? = null,
) {
    val isExpanded = expandedInput == key
    val localRequester = remember(key) { FocusRequester() }
    val requester = focusRequester ?: localRequester
    val locked = premiumFeature != null && premiumLocked
    var passwordRevealed by remember { mutableStateOf(false) }
    val maskedValue = if (value.isBlank()) stringResource(R.string.tv_settings_not_set)
                      else if (isPassword && !passwordRevealed) "\u2022".repeat(value.length)
                      else SettingsViewModel.maskSecret(value)

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        TvSettingCard(
            title = title,
            subtitle = maskedValue,
            modifier = Modifier.fillMaxWidth().focusProperties {
                left = railFocusRequester
                if (upFocusRequester != null) {
                    up = upFocusRequester
                }
            },
            focusRequester = requester,
            onFocused = { onContentFocused(requester) },
            onClick = {
                if (locked) {
                    onLockedClick?.invoke()
                } else {
                    onExpandToggle(key)
                }
            },
            rowType = TvSettingRowType.NAVIGATION,
            focusedHint = if (locked) {
                "Press OK to unlock with Lifetime Access."
            } else {
                "Press OK to edit this value."
            },
            premiumLocked = locked,
        )
        AnimatedVisibility(visible = isExpanded && !locked) {
            Column {
                TvClickToEditOutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    label = { Text(title) },
                    visualTransformation = if (isPassword && !passwordRevealed) {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    } else {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                if (isPassword && value.isNotEmpty()) {
                    val toggleRequester = remember { FocusRequester() }
                    TvSettingCard(
                        title = if (passwordRevealed) "Hide value" else "Show value",
                        subtitle = "",
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        focusRequester = toggleRequester,
                        onFocused = { },
                        onClick = { passwordRevealed = !passwordRevealed },
                        rowType = TvSettingRowType.TOGGLE,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSectionHeader(
    text: String,
    description: String? = null,
) {
    Column(
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Ash,
            )
        }
    }
}

@Composable
private fun TvSettingRowTypeChip(
    rowType: TvSettingRowType,
    focused: Boolean,
) {
    val label = when (rowType) {
        TvSettingRowType.NAVIGATION -> "Navigation"
        TvSettingRowType.TOGGLE -> "Toggle"
        TvSettingRowType.SELECTOR -> "Selector"
        TvSettingRowType.DANGEROUS -> "Sensitive"
        TvSettingRowType.ACTION -> null
    } ?: return

    val backgroundColor = when (rowType) {
        TvSettingRowType.NAVIGATION -> Color(0xFF35404A)
        TvSettingRowType.TOGGLE -> Color(0xFF2A4A3D)
        TvSettingRowType.SELECTOR -> Color(0xFF3E3A57)
        TvSettingRowType.DANGEROUS -> Color(0xFF5A2C35)
        TvSettingRowType.ACTION -> Color.Transparent
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (focused) Snow else Silver,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor.copy(alpha = if (focused) 0.95f else 0.7f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun TvPremiumLockChip(focused: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF5B4A1F).copy(alpha = if (focused) 0.95f else 0.75f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = if (focused) Snow else Silver,
            modifier = Modifier.width(12.dp),
        )
        Text(
            text = TvPremiumAccess.LOCKED_LABEL,
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) Snow else Silver,
        )
    }
}

@Composable
internal fun TvSettingCard(
    title: String,
    subtitle: String,
    modifier: Modifier,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    rowType: TvSettingRowType = TvSettingRowType.ACTION,
    emphasis: TvSettingEmphasis = TvSettingEmphasis.PRIMARY,
    focusedHint: String? = null,
    premiumLocked: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val isDanger = rowType == TvSettingRowType.DANGEROUS
    val scale by animateFloatAsState(targetValue = if (focused) 1.03f else 1f, label = "settingsScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            focused && isDanger -> Ruby
            focused && premiumLocked -> AmberLight
            focused -> Amber
            isDanger -> Ruby.copy(alpha = 0.45f)
            premiumLocked -> Amber.copy(alpha = 0.4f)
            else -> Color.Transparent
        },
        label = "settingsBorder",
    )

    val hintText = if (premiumLocked) {
        "Press OK to unlock with Lifetime Access."
    } else {
        focusedHint ?: when (rowType) {
            TvSettingRowType.NAVIGATION -> "Press OK to open."
            TvSettingRowType.SELECTOR -> "Press OK to change."
            TvSettingRowType.TOGGLE -> "Press OK to switch."
            TvSettingRowType.DANGEROUS -> "Press OK twice to confirm."
            TvSettingRowType.ACTION -> null
        }
    }

    val restingBackground = when (emphasis) {
        TvSettingEmphasis.PRIMARY -> {
            if (premiumLocked) Color(0xFF3A3222).copy(alpha = 0.55f) else Charcoal.copy(alpha = 0.52f)
        }
        TvSettingEmphasis.SECONDARY -> {
            if (premiumLocked) Color(0xFF3A3222).copy(alpha = 0.4f) else Charcoal.copy(alpha = 0.35f)
        }
    }
    val focusedBackground = when {
        isDanger -> Color(0xFF3C252B)
        premiumLocked -> Color(0xFF493E24)
        emphasis == TvSettingEmphasis.SECONDARY -> Graphite.copy(alpha = 0.42f)
        else -> Graphite.copy(alpha = 0.6f)
    }

    Column(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .background(
                color = if (focused) focusedBackground else restingBackground,
                shape = RoundedCornerShape(16.dp),
            )
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 22.dp, vertical = 20.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        onClick()
                        true
                    }
                    event.type == KeyEventType.KeyUp &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = when {
                    premiumLocked && focused -> AmberLight
                    focused && !isDanger -> Amber
                    else -> Snow
                },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (premiumLocked) {
                TvPremiumLockChip(focused = focused)
            }
            TvSettingRowTypeChip(rowType = rowType, focused = focused)
        }
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) Snow else Silver,
                maxLines = if (focused) 3 else 1,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (focused && !hintText.isNullOrBlank()) {
            Text(
                text = hintText,
                style = MaterialTheme.typography.bodySmall,
                color = Ash,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}


