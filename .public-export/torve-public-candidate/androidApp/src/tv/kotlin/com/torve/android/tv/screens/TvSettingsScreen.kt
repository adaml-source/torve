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
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
// LocaleListCompat removed — no longer applying locale inline
import com.torve.android.R
import com.torve.android.tv.TV_PAGE_BOTTOM_GUTTER
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.tv.TV_PAGE_END_GUTTER
import com.torve.android.tv.TV_PAGE_TOP_GUTTER
import com.torve.android.catalog.CatalogWarmupWorker
import com.torve.android.epg.EpgWarmupWorker
import com.torve.android.session.PostSignInRefresh
import com.torve.android.sync.SyncCoordinator
import com.torve.android.sync.TraktSyncWorker
import com.torve.data.account.AccountSettingsRepository
import com.torve.presentation.device.DeviceGovernanceViewModel
import com.torve.presentation.session.AccountSessionCoordinator
import com.torve.domain.model.PlaylistType
import com.torve.android.tv.settings.isTvReduceMotionEnabled
import com.torve.android.tv.settings.setTvReduceMotionEnabled
import com.torve.android.tv.settings.TV_SEE_ALL_POSTER_COLUMN_OPTIONS
import com.torve.android.tv.settings.setTvSeeAllPosterColumns
import com.torve.android.tv.settings.tvSeeAllPosterColumns
import com.torve.android.tv.components.TvClickToEditOutlinedTextField
import com.torve.android.tv.components.TvDocumentModal
import com.torve.android.tv.components.TvDocumentContentState
import com.torve.android.tv.components.TvHtmlDocumentPane
import com.torve.android.tv.components.rememberTvDocumentContentState
import com.torve.android.tv.focus.TvSettingsFocusStateMachine
import com.torve.android.tv.focus.TvSettingsFocusTarget
import com.torve.android.tv.focus.TvSettingsItemIds
import com.torve.android.tv.focus.rememberRegisteredTvSettingsFocusRequester
import com.torve.android.tv.premium.TvEntitledFeature
import com.torve.android.tv.premium.TvPremiumAccess
import com.torve.android.ui.settings.POPULAR_ADDONS
import com.torve.data.ai.AiProvider
import com.torve.domain.model.StreamQuality
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.presentation.channels.EpgState
import com.torve.presentation.addon.AddonViewModel
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.mdblist.MdbListTab
import com.torve.presentation.mdblist.MdbListViewModel
import com.torve.presentation.beta.BetaProgramCopy
import com.torve.presentation.beta.BetaProgramViewModel
import com.torve.presentation.beta.shouldShowBetaProgramSettingsEntry
import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.stats.WatchStatsUiText
import com.torve.presentation.stats.WatchStatsViewModel
import com.torve.presentation.subscription.PurchaseStatusTone
import com.torve.presentation.subscription.PurchaseVerificationState
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.subscription.accessPresentation
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.panda.PandaSetupUiState
import com.torve.presentation.panda.PandaSetupViewModel
import com.torve.presentation.providerhealth.ProviderStatusMapper
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
import com.torve.android.util.findActivity
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

private data class PendingSuggestedAddonInstallRestore(
    val suggestedUrl: String,
    val nextSuggestedUrl: String?,
    val previousSuggestedUrl: String?,
    val suggestedUrlsBeforeInstall: List<String>,
    val installedUrlsBeforeInstall: List<String>,
)

private data class PendingInstalledAddonUninstallRestore(
    val removedUrl: String,
    val removedIndex: Int,
    val installedUrlsBeforeRemove: List<String>,
)

private sealed interface TvAboutOverlayState {
    val originItemId: String

    data class Support(
        override val originItemId: String,
    ) : TvAboutOverlayState

    data class Terms(
        override val originItemId: String,
    ) : TvAboutOverlayState

    data class Legal(
        override val originItemId: String,
    ) : TvAboutOverlayState
}

private const val PREF_KEY_OPEN_CONNECTIONS_ONCE = "tv_settings_open_connections_once"
private const val SETTINGS_FOCUS_LOG_TAG = "TvSettingsFocus"

private fun formatTvAccessDate(epochMs: Long?): String {
    if (epochMs == null) return "later"
    val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)
    return formatter.format(
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate(),
    )
}

private fun subscriptionMarketplaceLabel(value: String?): String? {
    val normalized = value?.lowercase()?.replace('-', '_') ?: return null
    return when {
        normalized.contains("google") -> "Google Play"
        normalized.contains("amazon") -> "Amazon Appstore"
        normalized.contains("apple") -> "Apple App Store"
        normalized.contains("stripe") -> "Stripe"
        else -> null
    }
}

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
    onMoveFocusToRail: () -> Unit = {},
    mainEntryFocusRequester: FocusRequester? = null,
    homeLayoutFocusRequester: FocusRequester? = null,
    ratingsFocusRequester: FocusRequester? = null,
    onNavigateToHomeLayout: () -> Unit = {},
    onNavigateToRatings: () -> Unit = {},
    onNavigateToPandaSetup: () -> Unit = {},
    onNavigateToPairedDevices: (String) -> Unit = {},
    onNavigateToActivatedDevices: (String) -> Unit = {},
    onNavigateToSendCredentials: () -> Unit = {},
    onNavigateToReceiveCredentials: () -> Unit = {},
    onNavigateToTransferDiagnostics: () -> Unit = {},
    onNavigateToReportIssue: () -> Unit = {},
    onNavigateToWatchStats: () -> Unit = {},
    onNavigateToBetaProgram: () -> Unit = {},
    onNavigateToPairingSignIn: () -> Unit = {},
    onAuthSuccess: () -> Unit = {},
    pairedDevicesFocusRequester: FocusRequester? = null,
    activatedDevicesFocusRequester: FocusRequester? = null,
    settingsFocusController: TvSettingsFocusStateMachine,
    onRequestLifetimeUnlock: (TvEntitledFeature) -> Unit = {},
    openToChannelsTab: Boolean = false,
    onChannelsTabConsumed: () -> Unit = {},
    openToSubscriptionSection: Boolean = false,
    onSubscriptionSectionConsumed: () -> Unit = {},
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
    watchStatsViewModel: WatchStatsViewModel = koinInject(),
    betaProgramViewModel: BetaProgramViewModel = koinInject(),
    deviceGovernanceViewModel: DeviceGovernanceViewModel = koinInject(),
) {
    val syncState by syncCoordinator.state.collectAsState()
    val accountSettingsState by accountSettingsRepository.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val betaProgramState by betaProgramViewModel.state.collectAsState()
    val deviceGovernanceState by deviceGovernanceViewModel.state.collectAsState()
    val pandaSetupViewModel: PandaSetupViewModel = koinInject()
    val pandaSetupState by pandaSetupViewModel.state.collectAsState()
    val purchaseStrings: com.torve.presentation.subscription.PurchaseStringResolver = org.koin.compose.koinInject()
    val subscriptionAccess = subscriptionState.accessPresentation(purchaseStrings)
    val channelsState by channelsViewModel.state.collectAsState()
    val addonState by addonViewModel.state.collectAsState()
    val pandaAddon = remember(addonState.addons) {
        addonState.addons.firstOrNull { addon ->
            addon.manifest.id.equals("com.torve.panda", ignoreCase = true) ||
                addon.manifest.name.contains("panda", ignoreCase = true) ||
                addon.manifestUrl.contains("panda.torve.app", ignoreCase = true)
        }
    }
    val pandaConfigured = pandaAddon != null
    val pandaEnabled = pandaAddon?.isEnabled == true
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
    var authIsSigningOut by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var authUser by remember { mutableStateOf<com.torve.data.auth.AuthUser?>(null) }
    var authShowRegister by remember { mutableStateOf(false) }
    var importedSetupRefreshRunning by remember { mutableStateOf(false) }
    val passwordsMismatchText = stringResource(R.string.tv_auth_passwords_mismatch)
    val authScope = rememberCoroutineScope()

    // Recovery-card snapshot: shown when 2+ transferable provider
    // categories have no local credentials. Recomputes on Settings
    // entry AND whenever a credential transfer just imported (the
    // shared notifier ticks the flow value).
    val recoveryProvider: com.torve.presentation.providerhealth.ProviderHealthRecoveryStateProvider =
        org.koin.compose.koinInject()
    val transferCompletionNotifier: com.torve.presentation.transfer.TransferImportCompletionNotifier =
        org.koin.compose.koinInject()
    val transferLastImportMs by transferCompletionNotifier.lastImportEpochMs.collectAsState()
    var recoverySnap by remember {
        mutableStateOf<com.torve.presentation.providerhealth.ProviderHealthRecoverySnapshot?>(null)
    }
    var recoveryDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(transferLastImportMs) {
        recoverySnap = recoveryProvider.snapshot()
    }
    val showRecoveryRow = !recoveryDismissed &&
        (recoverySnap?.shouldShowRecoveryCard == true)

    // Provider-health entries (when a future Android init lands).
    // Today the coordinator has no checkers on Android → entries is
    // empty and the section renders nothing. No fake green/red.
    val providerHealthCoordinator: com.torve.presentation.providerhealth.ProviderHealthCoordinator =
        org.koin.compose.koinInject()
    val providerHealthEntries by providerHealthCoordinator.entries.collectAsState()
    val tvHealthRows = remember(providerHealthEntries) {
        providerHealthEntries
            .map { entry ->
                entry to com.torve.presentation.providerhealth.ProviderRepairMapper.actionsFor(entry)
            }
            .sortedWith(compareBy({ it.first.category.ordinal }, { it.first.label }))
    }
    val tvRepairRows = remember(tvHealthRows) {
        tvHealthRows
            // Only show repair rows we can act on with a remote: Transfer
            // or Diagnostics has a TV destination. The About health monitor
            // below intentionally shows every provider row.
            .filter { (_, actions) ->
                actions.any {
                    it == com.torve.presentation.providerhealth.ProviderRepairAction.TransferFromAnotherDevice ||
                        it == com.torve.presentation.providerhealth.ProviderRepairAction.OpenDiagnostics
                }
            }
    }
    val pandaProviderSummary = remember(providerHealthEntries, pandaSetupState) {
        buildPandaProviderSummary(providerHealthEntries, pandaSetupState)
    }
    val debridProviderSummary = remember(providerHealthEntries, pandaSetupState) {
        buildDebridProviderSummary(providerHealthEntries, pandaSetupState)
    }
    var providerHealthAutoRefreshStarted by remember { mutableStateOf(false) }

    // Check if already logged in, and sync account settings if so
    LaunchedEffect(Unit) {
        authUser = authClient.getAuthenticatedUser()
        subscriptionViewModel.refreshAccess()
        betaProgramViewModel.onOpenBetaProgram()
        if (authUser != null) {
            // Always force-fetch account settings on TV settings entry
            // so changes made on mobile are picked up immediately.
            runCatching {
                accountSettingsRepository.refreshIfStale(force = true)
                settingsViewModel.refreshSettings()
            }
        }
    }

    // Observe live auth-state changes — covers paths that don't go through
    // the local `authUser = ...` mutations on this screen, most importantly
    // the QR sign-in flow where AuthClient.signInViaPairing persists tokens
    // and emits via authUserFlow but the user stays on the Account tab the
    // whole time, so neither the LaunchedEffect(Unit) above nor the
    // LaunchedEffect(selectedCategory) on line ~565 re-fires. Without this,
    // the screen keeps showing email+password login fields after a
    // successful TV sign-in.
    LaunchedEffect(Unit) {
        authClient.authUserFlow.collect { user ->
            if (user != authUser) {
                authUser = user
                if (user != null) {
                    runCatching {
                        accountSettingsRepository.refreshIfStale(force = true)
                        settingsViewModel.refreshSettings()
                        subscriptionViewModel.refreshAccess()
                    }
                }
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
    var aboutOverlayState by remember { mutableStateOf<TvAboutOverlayState?>(null) }
    var pendingAuthTransitionFocusTarget by remember { mutableStateOf<TvSettingsFocusTarget?>(null) }

    var pendingSuggestedAddonInstallRestore by remember { mutableStateOf<PendingSuggestedAddonInstallRestore?>(null) }
    var pendingInstalledAddonUninstallRestore by remember { mutableStateOf<PendingInstalledAddonUninstallRestore?>(null) }
    val allowDebugPanel = com.torve.android.BuildConfig.DEBUG

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
    LaunchedEffect(selectedCategory, providerHealthAutoRefreshStarted) {
        if (providerHealthAutoRefreshStarted) return@LaunchedEffect
        if (selectedCategory != TvSettingsCategory.ADVANCED &&
            selectedCategory != TvSettingsCategory.ABOUT
        ) return@LaunchedEffect
        providerHealthAutoRefreshStarted = true
        runCatching {
            org.koin.java.KoinJavaComponent.getKoin()
                .get<com.torve.android.providerhealth.AndroidProviderHealthInit>()
                .start()
        }
        runCatching { providerHealthCoordinator.runAll().join() }
    }
    LaunchedEffect(authUser, selectedCategory) {
        if (authUser == null && selectedCategory != TvSettingsCategory.ACCOUNT) {
            settingsFocusController.selectedCategory = TvSettingsCategory.ACCOUNT
        }
    }
    val pendingSettingsOrigin = settingsFocusController.pendingRestore
    val hasPendingExactSettingsRestore = pendingSettingsOrigin != null
    val allowPendingRestoreFromRail = pendingSettingsOrigin?.reason == "app_link"
    val hasExistingPremiumAccess = subscriptionState.hasEntitlement || subscriptionState.isPro
    val showBetaProgramSettingsEntry = shouldShowBetaProgramSettingsEntry(
        state = betaProgramState,
        hasPremiumAccess = hasExistingPremiumAccess,
    )
    val subscriptionSectionHeaderScrollIndex = remember(authUser, authShowRegister, authError, showBetaProgramSettingsEntry) {
        var index = 0
        index += 1 // section_account
        if (showBetaProgramSettingsEntry) index += 1 // beta_program
        if (authUser == null) {
            index += 1 // auth_info_banner
            index += 2 // auth_email + auth_password
            if (authShowRegister) index += 1 // auth_confirm_password
            index += 2 // auth_submit + auth_toggle
            if (!authShowRegister) index += 1 // auth_forgot_password
            if (authError != null) index += 1 // auth_error
        } else {
            index += 1 // auth_account
            if (authUser?.isVerified == false) index += 1 // auth_verify
            index += 1 // account_import_setup
            index += 1 // account_import_refresh
            index += 1 // auth_logout
            index += 1 // auth_delete_account
        }
        if (com.torve.android.BuildConfig.HAS_BILLING) index += 1 // section_account_subscription
        index
    }
    val subscriptionEntryScrollIndex = remember(subscriptionSectionHeaderScrollIndex) {
        subscriptionSectionHeaderScrollIndex + 1
    }
    val subscriptionCardRequester = remember { FocusRequester() }

    LaunchedEffect(openToChannelsTab) {
        if (openToChannelsTab) {
            settingsFocusController.selectedCategory = TvSettingsCategory.LIBRARY
            onChannelsTabConsumed()
        }
    }
    LaunchedEffect(openToSubscriptionSection) {
        if (openToSubscriptionSection) {
            if (com.torve.android.BuildConfig.HAS_BILLING) {
                settingsFocusController.selectedCategory = TvSettingsCategory.ACCOUNT
                settingsFocusController.requestRestore(
                    itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_MONTHLY,
                    reason = "open_subscription_section",
                    outerListState = settingsListState,
                )
                settingsListState.scrollToItem(subscriptionEntryScrollIndex)
                kotlinx.coroutines.delay(120)
                runCatching { subscriptionCardRequester.requestFocus() }
            } else {
                settingsFocusController.selectedCategory = TvSettingsCategory.ACCOUNT
            }
            onSubscriptionSectionConsumed()
        }
    }
    val accessTier = rememberEffectivePremiumAccessTier(
        subscriptionTier = subscriptionState.subscription?.tier,
        subscriptionIsPro = subscriptionState.isPro,
    )
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
    fun buildVisibleSuggestedAddons(installedUrls: Set<String>): List<com.torve.android.ui.settings.PopularAddon> {
        return POPULAR_ADDONS.filter { addon ->
            val isPanda = addon.url.contains("panda", ignoreCase = true) ||
                addon.actionUrl.contains("panda", ignoreCase = true)
            !isPanda && addon.url !in installedUrls
        }
    }
    fun resolveSuggestedInstallRestoreTarget(
        pendingRestore: PendingSuggestedAddonInstallRestore,
        filteredSuggested: List<com.torve.android.ui.settings.PopularAddon>,
    ): Pair<String, String> {
        val visibleSuggestedUrls = filteredSuggested.map { it.url }.toSet()
        pendingRestore.nextSuggestedUrl?.takeIf { it in visibleSuggestedUrls }?.let { url ->
            return "addon_suggested_$url" to "next_suggested"
        }
        pendingRestore.previousSuggestedUrl?.takeIf { it in visibleSuggestedUrls }?.let { url ->
            return "addon_suggested_$url" to "previous_suggested"
        }
        return "addon_add_manual" to "add_addon"
    }
    fun resolveUninstallRestoreTarget(
        pendingRestore: PendingInstalledAddonUninstallRestore,
        filteredSuggested: List<com.torve.android.ui.settings.PopularAddon>,
    ): Pair<String, String> {
        addonState.addons.getOrNull(pendingRestore.removedIndex)?.manifestUrl?.let { manifestUrl ->
            return "addon_installed_$manifestUrl" to "next_installed"
        }
        addonState.addons.getOrNull(pendingRestore.removedIndex - 1)?.manifestUrl?.let { manifestUrl ->
            return "addon_installed_$manifestUrl" to "previous_installed"
        }
        filteredSuggested.firstOrNull()?.url?.let { url ->
            return "addon_suggested_$url" to "first_suggested"
        }
        return "addon_add_manual" to "add_addon"
    }
    suspend fun ensureAddonRestoreTargetComposed(
        targetItemId: String,
        targetKind: String,
        filteredSuggested: List<com.torve.android.ui.settings.PopularAddon>,
        targetInstalledIndex: Int? = null,
    ) {
        if (settingsFocusController.isItemRegistered(targetItemId)) return
        val installedCount = addonState.addons.size
        val suggestedCount = filteredSuggested.size
        val visibleItems = settingsListState.layoutInfo.visibleItemsInfo
        val installedHeaderIndex = visibleItems
            .firstOrNull { it.key == "addon_installed_header" }
            ?.index
        val suggestedHeaderIndex = visibleItems
            .firstOrNull { it.key == "addon_suggested_header" }
            ?.index
        val addAddonIndex = visibleItems
            .firstOrNull { it.key == "add_addon" }
            ?.index
        val targetIndex = when (targetKind) {
            "next_installed",
            "previous_installed",
            "first_installed",
            -> {
                val installedIndex = targetInstalledIndex ?: 0
                when {
                    installedHeaderIndex != null -> installedHeaderIndex + 1 + installedIndex
                    suggestedHeaderIndex != null -> suggestedHeaderIndex - installedCount + installedIndex
                    addAddonIndex != null -> addAddonIndex - suggestedCount - installedCount - 1 + installedIndex
                    else -> null
                }
            }
            "first_suggested" -> when {
                suggestedHeaderIndex != null -> suggestedHeaderIndex + 1
                addAddonIndex != null -> addAddonIndex - suggestedCount
                installedHeaderIndex != null -> installedHeaderIndex + 2 + installedCount
                else -> null
            }
            "add_addon" -> when {
                addAddonIndex != null -> addAddonIndex
                suggestedHeaderIndex != null -> suggestedHeaderIndex + 1 + suggestedCount
                installedHeaderIndex != null -> installedHeaderIndex + 2 + installedCount + suggestedCount
                else -> null
            }
            else -> null
        }?.coerceAtLeast(0)
        if (targetIndex != null) {
            settingsListState.scrollToItem(targetIndex)
            withFrameNanos { }
        }
    }
    fun dismissAboutOverlay() {
        val originItemId = aboutOverlayState?.originItemId
        aboutOverlayState = null
        originItemId?.let { itemId ->
            settingsFocusController.requestRestore(
                itemId = itemId,
                reason = "about_overlay_dismiss",
                outerListState = settingsListState,
            )
        }
    }

    LaunchedEffect(selectedCategory) {
        logSettingsFocus(
            "selected_category_changed category=${selectedCategory.name} " +
                "pendingRestore=${pendingSettingsOrigin?.restoreToken ?: -1L}",
        )
        settingsFocusController.pendingFocusRepair
            ?.takeIf { it.category != selectedCategory }
            ?.let {
                logSettingsFocus(
                    "clear_stale_mutation_repair selectedCategory=${selectedCategory.name} " +
                        "pendingCategory=${it.category.name} item=${it.itemId} token=${it.restoreToken}",
                )
                settingsFocusController.clearPendingFocusRepair()
            }
        pendingAuthTransitionFocusTarget
            ?.takeIf { it.category != selectedCategory }
            ?.let {
                logSettingsFocus(
                    "clear_stale_auth_transition selectedCategory=${selectedCategory.name} " +
                        "pendingCategory=${it.category.name} item=${it.itemId}",
                )
                pendingAuthTransitionFocusTarget = null
            }
        confirmSignOut = false
        confirmEnableDiagnostics = false
        confirmHideAllChannelGroups = false
        confirmRemoveAddonUrl = null
        pendingInstalledAddonUninstallRestore = null
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
    val homeLayoutCardRequester = homeLayoutFocusRequester ?: remember("home_layout_card") { FocusRequester() }
    val ratingsCardRequester = ratingsFocusRequester ?: remember("ratings_card") { FocusRequester() }
    val posterTitlesCardRequester = remember { FocusRequester() }
    val seeAllPosterColumnsCardRequester = remember { FocusRequester() }
    val aboutVersionCardRequester = remember { FocusRequester() }
    val advancedPhoneEntryRequester = remember { FocusRequester() }
    val advancedTvEntryRequester = remember { FocusRequester() }
    val advancedPandaCardRequester = remember { FocusRequester() }
    val advancedRealDebridCardRequester = remember { FocusRequester() }
    val aboutPandaHealthCardRequester = remember { FocusRequester() }
    val traktCardRequester = remember { FocusRequester() }
    val traktReconnectCardRequester = remember { FocusRequester() }
    val traktDisconnectCardRequester = remember { FocusRequester() }
    val simklCardRequester = remember { FocusRequester() }
    val simklDisconnectCardRequester = remember { FocusRequester() }
    var categoryPaneHasFocus by remember { mutableStateOf(false) }
    val addPlaylistCardRequester = remember { FocusRequester() }
    val editPlaylistEpgCardRequester = remember { FocusRequester() }
    val addKodiCardRequester = remember { FocusRequester() }
    val channelManagerShowAllRequester = remember { FocusRequester() }
    val channelManagerHideAllRequester = remember { FocusRequester() }
    // Registered with the focus state machine so it participates in fallback resolution.
    // addAddonTarget category is set dynamically below where selectedCategory is available
    val addAddonCardRequester = remember { FocusRequester() }
    val accountBetaProgramRequester = remember { FocusRequester() }
    val authAccountRequester = remember { FocusRequester() }
    val authPrimaryActionRequester = remember { FocusRequester() }
    val authEmailRequester = remember { FocusRequester() }
    val accountSyncStatusCardRequester = remember { FocusRequester() }
    val accountSyncErrorCardRequester = remember { FocusRequester() }

    // Restore focus after addon list changes (install or uninstall).
    // Observe both isInstalling and addon count. When either transitions and a card
    // was removed/added, the disposed card's DisposableEffect has already run unregisterItem().
    // requestRestore() finds the nearest valid fallback in the same category.
    val prevAddonCount = remember { mutableStateOf(addonState.addons.size) }
    LaunchedEffect(addonState.addons.size) {
        if (prevAddonCount.value > addonState.addons.size) {
            confirmRemoveAddonUrl = null
            val pendingUninstall = pendingInstalledAddonUninstallRestore
            val filteredSuggested = buildVisibleSuggestedAddons(addonState.addons.map { it.manifestUrl }.toSet())
            if (
                pendingUninstall != null &&
                addonState.addons.none { it.manifestUrl == pendingUninstall.removedUrl }
            ) {
                val (targetItemId, targetKind) = resolveUninstallRestoreTarget(
                    pendingRestore = pendingUninstall,
                    filteredSuggested = filteredSuggested,
                )
                val targetInstalledIndex = when (targetKind) {
                    "next_installed" -> pendingUninstall.removedIndex
                    "previous_installed" -> pendingUninstall.removedIndex - 1
                    else -> null
                }
                val targetRegisteredBeforeEnsure = settingsFocusController.isItemRegistered(targetItemId)
                ensureAddonRestoreTargetComposed(
                    targetItemId = targetItemId,
                    targetKind = targetKind,
                    filteredSuggested = filteredSuggested,
                    targetInstalledIndex = targetInstalledIndex,
                )
                val targetRegisteredAfterEnsure = settingsFocusController.isItemRegistered(targetItemId)
                Log.d(
                    SETTINGS_FOCUS_LOG_TAG,
                    "addon_uninstall_completed " +
                        "removedUrl=${pendingUninstall.removedUrl} " +
                        "removedIndex=${pendingUninstall.removedIndex} " +
                        "installedBefore=${pendingUninstall.installedUrlsBeforeRemove.joinToString()} " +
                        "installedAfter=${addonState.addons.map { it.manifestUrl }.joinToString()} " +
                        "suggestedAfter=${filteredSuggested.map { it.url }.joinToString()} " +
                        "resolvedTarget=$targetItemId " +
                        "resolvedTargetKind=$targetKind " +
                        "targetRegisteredBeforeEnsure=$targetRegisteredBeforeEnsure " +
                        "targetRegisteredAfterEnsure=$targetRegisteredAfterEnsure " +
                        "chipsPresent=false " +
                        "entryItem=${settingsFocusController.entryItemIdForCurrentState() ?: "none"}",
                )
                val restoreIssued = settingsFocusController.requestRestore(
                    itemId = targetItemId,
                    reason = "addon_uninstall_exact",
                    outerListState = settingsListState,
                ) != null
                Log.d(
                    SETTINGS_FOCUS_LOG_TAG,
                    "addon_uninstall_restore_requested " +
                        "removedUrl=${pendingUninstall.removedUrl} " +
                        "resolvedTarget=$targetItemId " +
                        "resolvedTargetKind=$targetKind " +
                        "restoreIssued=$restoreIssued " +
                        "chipsPresent=false " +
                        "entryItemAfterRequest=${settingsFocusController.entryItemIdForCurrentState() ?: "none"}",
                )
            } else {
                settingsFocusController.requestRestore(reason = "addon_uninstall", outerListState = settingsListState)
            }
            pendingInstalledAddonUninstallRestore = null
        } else if (prevAddonCount.value < addonState.addons.size) {
            confirmRemoveAddonUrl = null
            showAddAddon = false
            pendingInstalledAddonUninstallRestore = null
            val pendingSuggestedInstall = pendingSuggestedAddonInstallRestore
            if (
                pendingSuggestedInstall != null &&
                addonState.addons.any { it.manifestUrl == pendingSuggestedInstall.suggestedUrl }
            ) {
                val filteredSuggested = buildVisibleSuggestedAddons(addonState.addons.map { it.manifestUrl }.toSet())
                val (targetItemId, targetKind) = resolveSuggestedInstallRestoreTarget(
                    pendingRestore = pendingSuggestedInstall,
                    filteredSuggested = filteredSuggested,
                )
                val targetRegisteredBeforeScroll = settingsFocusController.isItemRegistered(targetItemId)
                ensureAddonRestoreTargetComposed(
                    targetItemId = targetItemId,
                    targetKind = targetKind,
                    filteredSuggested = filteredSuggested,
                    targetInstalledIndex = if (targetKind == "first_installed") 0 else null,
                )
                val targetRegisteredAfterScroll = settingsFocusController.isItemRegistered(targetItemId)
                Log.d(
                    SETTINGS_FOCUS_LOG_TAG,
                    "addon_suggested_install_completed " +
                        "installedUrl=${pendingSuggestedInstall.suggestedUrl} " +
                        "suggestedBefore=${pendingSuggestedInstall.suggestedUrlsBeforeInstall.joinToString()} " +
                        "suggestedAfter=${filteredSuggested.map { it.url }.joinToString()} " +
                        "installedBefore=${pendingSuggestedInstall.installedUrlsBeforeInstall.joinToString()} " +
                        "installedAfter=${addonState.addons.map { it.manifestUrl }.joinToString()} " +
                        "resolvedTarget=$targetItemId " +
                        "resolvedTargetKind=$targetKind " +
                        "targetRegisteredBeforeScroll=$targetRegisteredBeforeScroll " +
                        "targetRegisteredAfterScroll=$targetRegisteredAfterScroll " +
                        "entryItem=${settingsFocusController.entryItemIdForCurrentState() ?: "none"}",
                )
                settingsFocusController.requestRestore(
                    itemId = targetItemId,
                    reason = "addon_install_suggested",
                    outerListState = settingsListState,
                )
                Log.d(
                    SETTINGS_FOCUS_LOG_TAG,
                    "addon_suggested_install_restore_requested " +
                        "installedUrl=${pendingSuggestedInstall.suggestedUrl} " +
                        "resolvedTarget=$targetItemId " +
                        "resolvedTargetKind=$targetKind " +
                        "entryItemAfterRequest=${settingsFocusController.entryItemIdForCurrentState() ?: "none"}",
                )
            } else {
                settingsFocusController.requestRestore(reason = "addon_install", outerListState = settingsListState)
            }
            pendingSuggestedAddonInstallRestore = null
        }
        prevAddonCount.value = addonState.addons.size
    }
    LaunchedEffect(addonState.addons.map { it.manifestUrl }) {
        if (confirmRemoveAddonUrl != null && addonState.addons.none { it.manifestUrl == confirmRemoveAddonUrl }) {
            confirmRemoveAddonUrl = null
        }
    }
    LaunchedEffect(addonState.isInstalling, addonState.installError, pendingSuggestedAddonInstallRestore) {
        val pendingSuggestedInstall = pendingSuggestedAddonInstallRestore ?: return@LaunchedEffect
        if (!addonState.isInstalling && addonState.installError != null) {
            Log.d(
                SETTINGS_FOCUS_LOG_TAG,
                "addon_suggested_install_failed " +
                    "installedUrl=${pendingSuggestedInstall.suggestedUrl} " +
                    "error=${addonState.installError}",
            )
            pendingSuggestedAddonInstallRestore = null
        }
    }
    LaunchedEffect(showChannelManager) {
        if (showChannelManager) {
            withFrameNanos { }
            runCatching { channelManagerShowAllRequester.requestFocus() }
        } else {
            confirmHideAllChannelGroups = false
        }
    }
    val pairedDevicesTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIRED_DEVICES,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 1,
            focusTargetType = "card",
        )
    }
    val activatedDevicesTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_ACTIVATED_DEVICES,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 2,
            focusTargetType = "card",
        )
    }
    val accountSyncStatusTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SYNC_STATUS,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 3,
            focusTargetType = "action",
        )
    }
    val accountSyncErrorTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SYNC_ERROR,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 4,
            focusTargetType = "action",
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
    val autoplayTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_AUTOPLAY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 10,
            focusTargetType = "toggle",
        )
    }
    val autoplayNextTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_AUTOPLAY_NEXT,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 11,
            focusTargetType = "toggle",
        )
    }
    val dedupeTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_DEDUPE,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 20,
            focusTargetType = "toggle",
        )
    }
    val playbackAudioModeTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_AUDIO_MODE,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 30,
            focusTargetType = "selector",
        )
    }
    val playbackAudioPassthroughTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_AUDIO_PASSTHROUGH,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 31,
            focusTargetType = "toggle",
        )
    }
    val playbackAudioSurroundTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_AUDIO_SURROUND,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 32,
            focusTargetType = "toggle",
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
    val appearanceRegionTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_REGION,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 3,
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
            category = TvSettingsCategory.CONNECTIONS,
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
    val accountBetaProgramTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_BETA_PROGRAM,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 9,
            focusTargetType = "navigation",
        )
    }
    val authVerifyTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_VERIFY,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 11,
            focusTargetType = "action",
        )
    }
    val accountImportSetupTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_IMPORT_SETUP,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 12,
            focusTargetType = "navigation",
        )
    }
    val accountImportRefreshTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_IMPORT_REFRESH,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 13,
            focusTargetType = "action",
        )
    }
    val authEmailTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_EMAIL,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 20,
            focusTargetType = "input",
        )
    }
    val authPasswordTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_PASSWORD,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 21,
            focusTargetType = "input",
        )
    }
    val authConfirmPasswordTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_CONFIRM_PASSWORD,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 22,
            focusTargetType = "input",
        )
    }
    val authLogoutTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_PRIMARY_ACTION,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 13,
            focusTargetType = "action",
        )
    }
    val authDeleteTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_DELETE,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 14,
            focusTargetType = "action",
        )
    }
    val authSubmitTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_PRIMARY_ACTION,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 23,
            focusTargetType = "action",
        )
    }
    val authToggleTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_TOGGLE,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 24,
            focusTargetType = "navigation",
        )
    }
    val authPairWithPhoneTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_PAIR_WITH_PHONE,
            category = TvSettingsCategory.ACCOUNT,
            // Sit between auth_toggle (24) and the existing auth_forgot_password (25)
            // is the natural reading order; use 26 to stay above the
            // subscription-management cluster (30+) without colliding.
            listIndex = 26,
            focusTargetType = "navigation",
        )
    }
    val authForgotPasswordTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_FORGOT_PASSWORD,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 25,
            focusTargetType = "action",
        )
    }
    val subscriptionManageDevicesTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_MANAGE_DEVICES,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 30,
            focusTargetType = "navigation",
        )
    }
    val subscriptionMonthlyTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_MONTHLY,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 31,
            focusTargetType = "action",
        )
    }
    val subscriptionLifetimeTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_LIFETIME,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 32,
            focusTargetType = "action",
        )
    }
    val subscriptionManageBillingTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_MANAGE_BILLING,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 33,
            focusTargetType = "action",
        )
    }
    val subscriptionRefreshTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_REFRESH,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 34,
            focusTargetType = "action",
        )
    }
    val subscriptionRetryTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_RETRY,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 35,
            focusTargetType = "action",
        )
    }
    val subscriptionRestoreTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_RESTORE,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 36,
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
    val posterTitlesTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_POSTER_TITLES,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 6,
            focusTargetType = "toggle",
        )
    }
    val seeAllPosterColumnsTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_SEE_ALL_POSTER_COLUMNS,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 7,
            focusTargetType = "selector",
        )
    }
    val libraryTopTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.LIBRARY_CHANNELS,
            category = TvSettingsCategory.LIBRARY,
            listIndex = 0,
            focusTargetType = "navigation",
        )
    }
    val libraryAddPlaylistTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.LIBRARY_ADD_PLAYLIST,
            category = TvSettingsCategory.LIBRARY,
            listIndex = 30,
            focusTargetType = "navigation",
        )
    }
    val libraryRefreshEpgTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.LIBRARY_REFRESH_EPG,
            category = TvSettingsCategory.LIBRARY,
            listIndex = 35,
            focusTargetType = "action",
        )
    }
    val libraryManageChannelsTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.LIBRARY_MANAGE_CHANNELS,
            category = TvSettingsCategory.LIBRARY,
            listIndex = 40,
            focusTargetType = "navigation",
        )
    }
    val connectionsPairingTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_PAIRING,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 0,
            focusTargetType = "navigation",
        )
    }
    val connectionsTraktTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_TRAKT,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 12,
            focusTargetType = "action",
        )
    }
    val connectionsTraktReconnectTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_TRAKT_RECONNECT,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 13,
            focusTargetType = "action",
        )
    }
    val connectionsTraktDisconnectTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_TRAKT_DISCONNECT,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 14,
            focusTargetType = "dangerous",
        )
    }
    val connectionsSimklTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_SIMKL,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 20,
            focusTargetType = "action",
        )
    }
    val connectionsSimklDisconnectTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_SIMKL_DISCONNECT,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 21,
            focusTargetType = "dangerous",
        )
    }
    val advancedEntryTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_ENTRY,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 0,
            focusTargetType = "entry",
        )
    }
    val advancedPandaTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_PANDA,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 1,
            focusTargetType = "action",
        )
    }
    val advancedRealDebridTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_REAL_DEBRID,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 2,
            focusTargetType = "action",
        )
    }
    val advancedPhoneMdblistTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_PHONE_MDBLIST,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 1,
            focusTargetType = "action",
        )
    }
    val advancedPhoneJellyfinTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_PHONE_JELLYFIN,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 10,
            focusTargetType = "action",
        )
    }
    val advancedPhonePlexTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_PHONE_PLEX,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 11,
            focusTargetType = "action",
        )
    }
    val advancedOmdbKeyTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_OMDB_KEY,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 10,
            focusTargetType = "input",
        )
    }
    val advancedOmdbTestTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_OMDB_TEST,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 11,
            focusTargetType = "action",
        )
    }
    val advancedOpenSubtitlesKeyTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_OPENSUBTITLES_KEY,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 12,
            focusTargetType = "input",
        )
    }
    val advancedMdblistKeyTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_MDBLIST_KEY,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 13,
            focusTargetType = "input",
        )
    }
    val advancedJellyfinUrlTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_JELLYFIN_URL,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 10,
            focusTargetType = "input",
        )
    }
    val advancedJellyfinApiKeyTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_JELLYFIN_API_KEY,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 11,
            focusTargetType = "input",
        )
    }
    val advancedJellyfinTestTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_JELLYFIN_TEST,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 12,
            focusTargetType = "action",
        )
    }
    val advancedPlexUrlTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_PLEX_URL,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 20,
            focusTargetType = "input",
        )
    }
    val advancedPlexTokenTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_PLEX_TOKEN,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 21,
            focusTargetType = "input",
        )
    }
    val advancedPlexTestTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_PLEX_TEST,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 22,
            focusTargetType = "action",
        )
    }
    val advancedKodiAddTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_KODI_ADD,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 40,
            focusTargetType = "navigation",
        )
    }
    val advancedAiProviderTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_AI_PROVIDER,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 50,
            focusTargetType = "selector",
        )
    }
    val advancedAiApiKeyTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_AI_API_KEY,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 51,
            focusTargetType = "input",
        )
    }
    val advancedAiTestTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_AI_TEST,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 52,
            focusTargetType = "action",
        )
    }
    val advancedDiagnosticsTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ADVANCED_DIAGNOSTICS,
            category = TvSettingsCategory.ADVANCED,
            listIndex = 500,
            focusTargetType = "action",
        )
    }
    val aboutVersionTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_VERSION,
            category = TvSettingsCategory.ABOUT,
            listIndex = 0,
            focusTargetType = "action",
        )
    }
    val aboutBuildTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_BUILD,
            category = TvSettingsCategory.ABOUT,
            listIndex = 1,
            focusTargetType = "action",
        )
    }
    val aboutSupportTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_SUPPORT,
            category = TvSettingsCategory.ABOUT,
            listIndex = 2,
            focusTargetType = "navigation",
        )
    }
    val aboutReportIssueTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_REPORT_ISSUE,
            category = TvSettingsCategory.ABOUT,
            listIndex = 3,
            focusTargetType = "navigation",
        )
    }
    val aboutTermsTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_TERMS,
            category = TvSettingsCategory.ABOUT,
            listIndex = 4,
            focusTargetType = "navigation",
        )
    }
    val aboutLegalTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_LEGAL,
            category = TvSettingsCategory.ABOUT,
            listIndex = 5,
            focusTargetType = "navigation",
        )
    }
    val aboutStatsTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_STATS,
            category = TvSettingsCategory.ABOUT,
            listIndex = 6,
            focusTargetType = "action",
        )
    }
    @Composable
    fun rememberSettingsRowRequester(
        target: TvSettingsFocusTarget,
        externalRequester: FocusRequester? = null,
        isDefaultEntry: Boolean = false,
    ): FocusRequester = rememberRegisteredTvSettingsFocusRequester(
        controller = settingsFocusController,
        target = target,
        externalRequester = externalRequester,
        isDefaultEntry = isDefaultEntry,
    )

    var isFirstItemFocused by remember { mutableStateOf(false) }
    var isLastItemFocused by remember { mutableStateOf(false) }

    fun onSettingsRowFocused(target: TvSettingsFocusTarget, requester: FocusRequester) {
        settingsFocusController.markFocused(target.itemId, requester)
        val visibleInCategory = settingsFocusController.visibleItemIdsInCategory(target.category)
        isFirstItemFocused = visibleInCategory.firstOrNull() == target.itemId
        isLastItemFocused = visibleInCategory.lastOrNull() == target.itemId
        onContentFocused(requester)
    }

    fun restoreConnectionFocus(itemId: String, requester: FocusRequester, reason: String) {
        settingsFocusController.requestRestore(
            itemId = itemId,
            reason = reason,
            outerListState = settingsListState,
        )
        runCatching { requester.requestFocus() }
        onContentFocused(requester)
        authScope.launch {
            kotlinx.coroutines.delay(90)
            settingsFocusController.requestRestore(
                itemId = itemId,
                reason = "${reason}_settled",
                outerListState = settingsListState,
            )
            runCatching { requester.requestFocus() }
            onContentFocused(requester)
        }
    }

    fun runProviderHealthCheckWithFeedback(entry: ProviderHealthEntry) {
        val job = providerHealthCoordinator.runCheck(entry.providerKey)
        if (job == null) {
            TvNotificationQueue.post("No check is available for ${entry.label}", NotificationType.INFO)
            return
        }
        TvNotificationQueue.post("Checking ${entry.label}...", NotificationType.INFO)
        authScope.launch {
            job.join()
            val updated = providerHealthCoordinator.entries.value
                .firstOrNull { it.providerKey == entry.providerKey }
            val status = updated?.status ?: ProviderHealthStatus.UNKNOWN
            val (message, type) = when (status) {
                ProviderHealthStatus.GREEN -> "${entry.label}: connection looks good" to NotificationType.SUCCESS
                ProviderHealthStatus.YELLOW -> "${entry.label}: needs attention" to NotificationType.INFO
                ProviderHealthStatus.RED -> "${entry.label}: check failed" to NotificationType.ERROR
                ProviderHealthStatus.UNCONFIGURED -> "${entry.label}: not configured" to NotificationType.INFO
                ProviderHealthStatus.UNKNOWN -> "${entry.label}: check finished" to NotificationType.INFO
            }
            TvNotificationQueue.post(message, type)
        }
    }

    fun refreshImportedSetup() {
        if (importedSetupRefreshRunning) return
        authScope.launch {
            importedSetupRefreshRunning = true
            TvNotificationQueue.post("Checking setup...", NotificationType.INFO)
            try {
                val result = accountSessionCoordinator.refreshAccountDataAfterCredentialTransfer()
                settingsViewModel.refreshSettings()
                channelsViewModel.loadPlaylists(recoverEmptyCatalog = true)
                channelsViewModel.loadFavorites()
                runCatching {
                    org.koin.java.KoinJavaComponent.getKoin()
                        .get<com.torve.android.providerhealth.AndroidProviderHealthInit>()
                        .start()
                }
                runCatching { providerHealthCoordinator.runAll().join() }
                PostSignInRefresh.enqueueContentWarmupAfterAccountActivation(context)
                TvNotificationQueue.post(
                    result.error ?: context.getString(R.string.tv_settings_account_refreshed_epg_hint),
                    if (result.error == null) NotificationType.SUCCESS else NotificationType.ERROR,
                )
            } finally {
                importedSetupRefreshRunning = false
            }
        }
    }
    var previousPlaylistCount by remember { mutableIntStateOf(channelsState.playlists.size) }
    var previousShowAddPlaylist by remember { mutableStateOf(showAddPlaylist) }
    LaunchedEffect(isActive, isRailFocused, hasPendingExactSettingsRestore, aboutOverlayState) {
        if (aboutOverlayState != null) return@LaunchedEffect
        if (hasPendingExactSettingsRestore) return@LaunchedEffect
        if (!isActive) return@LaunchedEffect
        if (isRailFocused) return@LaunchedEffect
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
        aboutOverlayState,
        settingsFocusController.pendingRestore?.restoreToken,
        isActive,
        isRailFocused,
    ) {
        if (!isActive) return@LaunchedEffect
        if (isRailFocused && !allowPendingRestoreFromRail) return@LaunchedEffect
        if (aboutOverlayState != null || showLanguagePicker || showMaxQualityPicker || showMinQualityPicker) {
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
            onContentFocused(if (channelsState.playlists.isEmpty()) channelsTopRequester else addPlaylistCardRequester)
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
            onContentFocused(if (currentCount == 0) channelsTopRequester else addPlaylistCardRequester)
        }
        previousPlaylistCount = currentCount
    }

    val selectedPlaylistForEpg = remember(channelsState.playlists, channelsState.selectedPlaylistId) {
        channelsState.playlists.firstOrNull { it.id == channelsState.selectedPlaylistId }
            ?: channelsState.playlists.firstOrNull()
    }
    val selectedPlaylistForActions = remember(channelsState.playlists, channelsState.selectedPlaylistId) {
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

    // Trakt subtitle
    val traktSubtitle = when {
        settingsState.isPollingTrakt || settingsState.traktDeviceCode != null -> {
            val code = settingsState.traktDeviceCode
            if (code != null) "Authorization in progress - use the code panel below" else "Starting..."
        }
        settingsState.traktConnected -> {
            settingsState.traktUser?.username?.let { "$it - $connectedLabel" }
                ?: when {
                    settingsState.traktApiStatus.equals("Online", ignoreCase = true) -> connectedLabel
                    settingsState.traktApiStatus?.contains("rate", ignoreCase = true) == true ->
                        "Connected - Trakt is temporarily limited"
                    else -> "Token found - press OK to verify or reconnect"
                }
        }
        else -> notConnectedLabel
    }

    // SIMKL subtitle
    val simklSubtitle = when {
        settingsState.isPollingSimkl || settingsState.simklDeviceCode != null -> {
            val code = settingsState.simklDeviceCode
            if (code != null) "Authorization in progress - use the code panel below" else "Starting..."
        }
        settingsState.simklConnected -> {
            settingsState.simklUser?.username?.let { "$it - $connectedLabel" } ?: when {
                settingsState.simklLoading -> "Checking SIMKL connection..."
                settingsState.simklError != null -> "Token found - SIMKL needs attention"
                else -> "Token found - press OK to verify or reconnect"
            }
        }
        else -> notConnectedLabel
    }

    // ── Setup mode picker ──
    val needsSetupLabel = stringResource(R.string.tv_settings_needs_setup)
    val categoryEntries = if (authUser == null) {
        listOf(TvSettingsCategory.ACCOUNT to stringResource(R.string.tv_settings_category_account))
    } else {
        listOf(
            TvSettingsCategory.ACCOUNT to stringResource(R.string.tv_settings_category_account),
            TvSettingsCategory.PLAYBACK to stringResource(R.string.tv_settings_category_playback),
            TvSettingsCategory.APPEARANCE to stringResource(R.string.tv_settings_category_appearance),
            TvSettingsCategory.LIBRARY to stringResource(R.string.tv_settings_category_channels),
            TvSettingsCategory.CONNECTIONS to stringResource(R.string.tv_settings_category_connections),
            TvSettingsCategory.ADVANCED to stringResource(R.string.tv_settings_category_advanced),
            TvSettingsCategory.ABOUT to stringResource(R.string.tv_settings_category_about),
        )
    }
    val categoryOrder = remember(categoryEntries) { categoryEntries.map { it.first } }
    val categoryLockFeature = remember {
        mapOf(
            TvSettingsCategory.LIBRARY to TvEntitledFeature.PERSISTENT_COLLECTIONS,
            TvSettingsCategory.CONNECTIONS to TvEntitledFeature.CLOUD_PROVIDER_SETUP,
            TvSettingsCategory.ADVANCED to TvEntitledFeature.ADVANCED_CONNECTION_CONFIGURATION,
        )
    }
    val partiallyLockedCategories = remember {
        setOf(
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
            TvSettingsCategory.ACCOUNT -> when {
                showBetaProgramSettingsEntry -> accountBetaProgramRequester
                authUser != null -> authAccountRequester
                else -> authEmailRequester
            }
            TvSettingsCategory.PLAYBACK -> maxQualityCardRequester
            TvSettingsCategory.APPEARANCE -> reduceMotionCardRequester
            TvSettingsCategory.LIBRARY -> channelsTopRequester
            TvSettingsCategory.CONNECTIONS -> pairingCardRequester
            TvSettingsCategory.ADVANCED -> {
                if (advancedLocked) {
                    advancedPhoneEntryRequester
                } else if (setupMode == TvSetupMode.TV_ONLY) {
                    advancedPandaCardRequester
                } else {
                    advancedPhoneEntryRequester
                }
            }
            TvSettingsCategory.ABOUT -> aboutVersionCardRequester
        }
    }
    val selectedCategoryFocusRequester = categoryRequesters.getValue(selectedCategory)
    val settingsRegistrationVersion = settingsFocusController.currentRegistrationVersion
    val visibleRegisteredItemIds = remember(selectedCategory, settingsRegistrationVersion) {
        settingsFocusController.visibleItemIdsInCategory(selectedCategory)
    }

    LaunchedEffect(
        setupMode,
        selectedCategory,
        hasPendingExactSettingsRestore,
        pendingAuthTransitionFocusTarget?.itemId,
        authUser != null,
    ) {
        if (hasPendingExactSettingsRestore && pendingAuthTransitionFocusTarget == null) return@LaunchedEffect
        val fallbackRequester = if (setupMode == null) {
            settingsContentRequester
        } else {
            detailRequesterForCategory(selectedCategory)
        }
        logSettingsFocus(
            "publish_first_content category=${selectedCategory.name} " +
                "pendingRestore=${pendingSettingsOrigin?.restoreToken ?: -1L} " +
                "entryItem=${settingsFocusController.entryItemIdForCurrentState() ?: "none"}",
        )
        onFirstContentRequester(fallbackRequester)
    }
    var previousRailFocused by remember { mutableStateOf(isRailFocused) }
    LaunchedEffect(
        isActive,
        isRailFocused,
        hasPendingExactSettingsRestore,
        settingsFocusController.pendingFocusRepair?.restoreToken,
        pendingAuthTransitionFocusTarget?.itemId,
        aboutOverlayState,
        showLanguagePicker,
        showMaxQualityPicker,
        showMinQualityPicker,
    ) {
        val movedToRail = !previousRailFocused && isRailFocused
        previousRailFocused = isRailFocused
        if (
            !movedToRail ||
            !isActive ||
            hasPendingExactSettingsRestore ||
            settingsFocusController.pendingFocusRepair != null ||
            pendingAuthTransitionFocusTarget != null ||
            aboutOverlayState != null ||
            showLanguagePicker ||
            showMaxQualityPicker ||
            showMinQualityPicker
        ) {
            return@LaunchedEffect
        }
        val returnTarget = settingsFocusController.saveReturnTarget(
            reason = "rail_exit",
            outerListState = settingsListState,
        )
        logSettingsFocus(
            "capture_rail_exit category=${selectedCategory.name} " +
                "target=${returnTarget?.itemId ?: "none"} " +
                "token=${returnTarget?.restoreToken ?: -1L}",
        )
    }
    // Card mutations must be able to recover focus even if the rail briefly takes it.
    LaunchedEffect(
        settingsFocusController.pendingFocusRepair?.restoreToken,
        settingsRegistrationVersion,
        isActive,
        selectedCategory,
        categoryPaneHasFocus,
        isRailFocused,
        aboutOverlayState,
        showLanguagePicker,
        showMaxQualityPicker,
        showMinQualityPicker,
    ) {
        val origin = settingsFocusController.pendingFocusRepair ?: return@LaunchedEffect
        if (!isActive) return@LaunchedEffect
        if (categoryPaneHasFocus) return@LaunchedEffect
        // If the user is currently on the rail, do NOT pull focus back into
        // Settings content — they'd see Settings auto-enter content as soon
        // as they hovered the rail item. The repair stays pending; the
        // LaunchedEffect is keyed on `isRailFocused`, so it re-fires the
        // moment they explicitly move into content (OK/RIGHT, or sub-route
        // return) and restores focus to the saved card then.
        if (isRailFocused) return@LaunchedEffect
        if (aboutOverlayState != null || showLanguagePicker || showMaxQualityPicker || showMinQualityPicker) {
            return@LaunchedEffect
        }
        if (settingsFocusController.selectedCategory != origin.category) {
            settingsFocusController.selectedCategory = origin.category
        }
        logSettingsFocus(
            "mutation_repair_begin category=${origin.category.name} " +
                "item=${origin.itemId} visible=${settingsFocusController.visibleItemIdsInCategory(origin.category).joinToString()} " +
                "railFocused=$isRailFocused token=${origin.restoreToken}",
        )
        repeat(24) {
            withFrameNanos { }
            val candidates = settingsFocusController.resolveMutationRepairCandidates(origin)
            for (candidate in candidates) {
                val requester = settingsFocusController.requesterForItemId(candidate.itemId) ?: continue
                runCatching { requester.requestFocus() }
                withFrameNanos { }
                if (settingsFocusController.focusedItemId == candidate.itemId) {
                    settingsFocusController.markFocused(candidate.itemId, requester)
                    settingsFocusController.clearPendingFocusRepair()
                    onContentFocused(requester)
                    logSettingsFocus(
                        "mutation_repair_success category=${candidate.category.name} " +
                            "item=${candidate.itemId} token=${origin.restoreToken}",
                    )
                    return@LaunchedEffect
                }
            }

            // Do not force-scroll to the category default during mutation
            // repair. Long settings sections such as Advanced can virtualize
            // offscreen cards while the user is moving quickly; jumping to 0
            // makes an ordinary card status update look like focus was stolen.
        }

        settingsFocusController.defaultItemIdForCategory(origin.category)?.let { defaultItemId ->
            val fallbackRequester = settingsFocusController.requesterForItemId(defaultItemId)
            if (fallbackRequester != null) {
                runCatching { fallbackRequester.requestFocus() }
                withFrameNanos { }
                if (settingsFocusController.focusedItemId == defaultItemId) {
                    settingsFocusController.markFocused(defaultItemId, fallbackRequester)
                    settingsFocusController.clearPendingFocusRepair()
                    onContentFocused(fallbackRequester)
                    logSettingsFocus(
                        "mutation_repair_fallback category=${origin.category.name} " +
                            "item=$defaultItemId token=${origin.restoreToken}",
                    )
                    return@LaunchedEffect
                }
            }
        }
        logSettingsFocus(
            "mutation_repair_failed category=${origin.category.name} " +
                "item=${origin.itemId} visible=${visibleRegisteredItemIds.joinToString()} token=${origin.restoreToken}",
        )
        settingsFocusController.clearPendingFocusRepair()
    }
    LaunchedEffect(
        pendingAuthTransitionFocusTarget?.itemId,
        settingsRegistrationVersion,
        isActive,
        aboutOverlayState,
        showLanguagePicker,
        showMaxQualityPicker,
        showMinQualityPicker,
    ) {
        val target = pendingAuthTransitionFocusTarget ?: return@LaunchedEffect
        val targetItemId = target.itemId
        if (!isActive) return@LaunchedEffect
        if (aboutOverlayState != null || showLanguagePicker || showMaxQualityPicker || showMinQualityPicker) {
            return@LaunchedEffect
        }
        if (settingsFocusController.selectedCategory != target.category) {
            settingsFocusController.selectedCategory = target.category
        }
        // Allow recomposition to settle (auth form removed, account card added)
        kotlinx.coroutines.delay(150)
        repeat(48) {
            withFrameNanos { }
            if (!settingsFocusController.isItemRegistered(targetItemId)) {
                settingsListState.scrollToItem(target.listIndex.coerceAtLeast(0))
                withFrameNanos { }
            }
            val requester = settingsFocusController.requesterForItemId(targetItemId) ?: return@repeat
            runCatching { requester.requestFocus() }
            withFrameNanos { }
            if (settingsFocusController.focusedItemId == targetItemId) {
                settingsFocusController.markFocused(targetItemId, requester)
                settingsFocusController.clearPendingRestore()
                settingsFocusController.clearPendingFocusRepair()
                pendingAuthTransitionFocusTarget = null
                onContentFocused(requester)
                logSettingsFocus("auth_transition_restore_success item=$targetItemId")
                return@LaunchedEffect
            }
        }
        logSettingsFocus(
            "auth_transition_restore_failed item=$targetItemId " +
                "registered=${settingsFocusController.isItemRegistered(targetItemId)} " +
                "category=${target.category.name} rowIndex=${target.listIndex}",
        )
        // Keep target alive so settingsRegistrationVersion retriggers this LaunchedEffect
        // when the item eventually registers. Clear via a timeout fallback instead.
        if (!settingsFocusController.isItemRegistered(targetItemId)) {
            kotlinx.coroutines.delay(2000)
            if (pendingAuthTransitionFocusTarget?.itemId == targetItemId) {
                pendingAuthTransitionFocusTarget = null
            }
        } else {
            pendingAuthTransitionFocusTarget = null
        }
        settingsFocusController.clearPendingFocusRepair()
    }

    if (setupMode == null) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = TV_PAGE_CONTENT_GUTTER,
                top = TV_PAGE_TOP_GUTTER + 2.dp,
                end = TV_PAGE_END_GUTTER,
                bottom = TV_PAGE_BOTTOM_GUTTER,
            ),
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
    fun moveFocusToRailFromSettings() {
        settingsFocusController.clearPendingRestore()
        settingsFocusController.clearPendingFocusRepair()
        pendingFocusMove = null
        onMoveFocusToRail()
        runCatching { railFocusRequester.requestFocus() }
    }
    BackHandler(
        enabled = isActive &&
            !isRailFocused &&
            !hasPendingExactSettingsRestore &&
            aboutOverlayState == null &&
            !showLanguagePicker &&
            !showMaxQualityPicker &&
            !showMinQualityPicker,
    ) {
        if (confirmHideAllChannelGroups) {
            confirmHideAllChannelGroups = false
        } else {
            moveFocusToRailFromSettings()
        }
    }
    LazyColumn(
        state = settingsListState,
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (hasPendingExactSettingsRestore) {
                    logSettingsFocus(
                        "list_key_suppressed key=${event.key} token=${pendingSettingsOrigin.restoreToken} " +
                            "reason=${pendingSettingsOrigin.reason}",
                    )
                    return@onPreviewKeyEvent false
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val totalItems = settingsListState.layoutInfo.totalItemsCount
                if (totalItems == 0) return@onPreviewKeyEvent false
                val focusedSettingsItemId = settingsFocusController.focusedItemId
                val visibleSettingsItemIds = settingsFocusController.visibleItemIdsInCategory(selectedCategory)
                val focusedFirstSettingsItem = focusedSettingsItemId != null &&
                    visibleSettingsItemIds.firstOrNull() == focusedSettingsItemId
                val focusedLastSettingsItem = focusedSettingsItemId != null &&
                    visibleSettingsItemIds.lastOrNull() == focusedSettingsItemId
                when (event.key) {
                    Key.DirectionUp -> {
                        if (focusedFirstSettingsItem || (focusedSettingsItemId == null && isFirstItemFocused)) {
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
                        // Don't consume Down here. The previous logic redirected to the
                        // category-detail panel when the focus controller thought we were
                        // on the last item — but its `visibleItemIds.lastOrNull()` was
                        // unreliable (gaps in listIndex sequences, items not yet registered)
                        // and trapped the user mid-list. Let the LazyColumn handle Down
                        // naturally: it moves to the next focusable item, or does nothing
                        // at the true bottom of the list.
                        false
                    }
                    else -> false
                }
        },
        contentPadding = PaddingValues(
            start = TV_PAGE_CONTENT_GUTTER,
            top = TV_PAGE_TOP_GUTTER,
            end = TV_PAGE_END_GUTTER,
            bottom = TV_PAGE_BOTTOM_GUTTER,
        ),
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
                                            "token=${pendingSettingsOrigin.restoreToken}",
                                    )
                                    return@onPreviewKeyEvent false
                                }
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.Back,
                                    Key.Escape -> {
                                        moveFocusToRailFromSettings()
                                        true
                                    }
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
                                        "token=${pendingSettingsOrigin.restoreToken}",
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
                val requester = rememberSettingsRowRequester(
                    target = libraryTopTarget,
                    externalRequester = channelsTopRequester,
                    isDefaultEntry = true,
                )
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
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(libraryTopTarget, requester) },
                        onClick = { onRequestLifetimeUnlock(TvEntitledFeature.PERSISTENT_COLLECTIONS) },
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
                val requester = rememberSettingsRowRequester(
                    target = advancedEntryTarget,
                    externalRequester = advancedPhoneEntryRequester,
                    isDefaultEntry = true,
                )
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
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(advancedEntryTarget, requester) },
                        onClick = { onRequestLifetimeUnlock(TvEntitledFeature.ADVANCED_CONNECTION_CONFIGURATION) },
                        rowType = TvSettingRowType.NAVIGATION,
                        premiumLocked = true,
                    )
                }
            }
        }

        if (selectedCategory == TvSettingsCategory.CONNECTIONS) {
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
                    target = connectionsPairingTarget,
                    externalRequester = pairingCardRequester,
                    isDefaultEntry = true,
                )
                Box(Modifier.onFocusChanged { isFirstItemFocused = it.hasFocus }) {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_pair_device),
                        subtitle = pairingSubtitle,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            up = categoryRequesters.getValue(TvSettingsCategory.CONNECTIONS)
                        },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(connectionsPairingTarget, requester) },
                        onClick = {
                            syncCoordinator.startTvPairingFlow()
                        },
                        rowType = TvSettingRowType.ACTION,
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
                    onFocused = { onSettingsRowFocused(pairedDevicesTarget, requester) },
                    onClick = {
                        logSettingsFocus("launch_subpage category=CONNECTIONS row=PAIRING item=account_paired_devices reason=route_open")
                        settingsFocusController.captureOrigin(
                            itemId = pairedDevicesTarget.itemId,
                            outerListState = settingsListState,
                            reason = "route_open",
                        )
                        onNavigateToPairedDevices(pairedDevicesTarget.itemId)
                    },
                    rowType = TvSettingRowType.NAVIGATION,
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
                    onFocused = { onSettingsRowFocused(activatedDevicesTarget, requester) },
                    onClick = {
                        logSettingsFocus("launch_subpage category=CONNECTIONS row=PAIRING item=account_activated_devices reason=route_open")
                        settingsFocusController.captureOrigin(
                            itemId = activatedDevicesTarget.itemId,
                            outerListState = settingsListState,
                            reason = "route_open",
                        )
                        onNavigateToActivatedDevices(activatedDevicesTarget.itemId)
                    },
                    rowType = TvSettingRowType.NAVIGATION,
                )
            }
            item(key = "account_sync_status") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = accountSyncStatusTarget,
                    externalRequester = accountSyncStatusCardRequester,
                )
                val accountSyncSubtitle = when {
                    accountSettingsState.isRefreshing -> stringResource(R.string.tv_settings_account_syncing)
                    accountSettingsState.lastError != null -> stringResource(R.string.tv_settings_account_sync_failed_retry)
                    accountSettingsState.lastFetchedAt != null -> stringResource(R.string.tv_settings_account_sync_synced)
                    else -> stringResource(R.string.tv_settings_account_sync_tap_sync)
                }
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_account_sync_status),
                    subtitle = accountSyncSubtitle,
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(accountSyncStatusTarget, requester) },
                    onClick = { refreshImportedSetup() },
                    rowType = TvSettingRowType.ACTION,
                )
            }
            // Show account settings sync error (not pairing transport errors)
            accountSettingsState.lastError?.let { errorKey ->
                val resolvedSyncError = com.torve.android.error.resolveErrorKey(context, errorKey)
                    ?: com.torve.presentation.error.defaultUserFacingMessages[errorKey]
                    ?: errorKey
                item(key = "account_sync_error") {
                    val requester = rememberRegisteredTvSettingsFocusRequester(
                        controller = settingsFocusController,
                        target = accountSyncErrorTarget,
                        externalRequester = accountSyncErrorCardRequester,
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_sync_error),
                        subtitle = resolvedSyncError,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(accountSyncErrorTarget, requester) },
                        onClick = { refreshImportedSetup() },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                    )
                }
            }
        }

        // Account section
        if (selectedCategory == TvSettingsCategory.ACCOUNT) {
        item(key = "section_account") {
            TvSectionHeader(
                text = stringResource(R.string.tv_settings_section_profile_signin),
                description = stringResource(R.string.tv_settings_account_manage_desc),
            )
        }

        if (showBetaProgramSettingsEntry) {
        item(key = "account_beta_program") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = accountBetaProgramTarget,
                externalRequester = accountBetaProgramRequester,
                isDefaultEntry = true,
            )
            val betaSubtitle = when {
                !betaProgramState.isSignedIn -> "Sign in to apply for the Torve Beta Program."
                betaProgramState.isEmailVerificationRequired -> "Verify your email to apply."
                hasExistingPremiumAccess &&
                    !betaProgramState.betaAccessActive &&
                    betaProgramState.blockedReason != com.torve.domain.beta.BetaBlockedReason.BETA_SIGNUP_CLOSED &&
                    betaProgramState.blockedReason != com.torve.domain.beta.BetaBlockedReason.BETA_ACCESS_ENDED ->
                    BetaProgramCopy.PREMIUM_TESTER_APPLICATION
                betaProgramState.betaAccessActive -> betaProgramState.betaAccessExpiresAt
                    ?.let { "Free beta access active until ${it.take(10)}." }
                    ?: "Beta tester access is active."
                betaProgramState.applicationStatus == com.torve.domain.beta.BetaApplicationStatus.SUBMITTED ->
                    "Your application is waiting for review."
                betaProgramState.blockedReason == com.torve.domain.beta.BetaBlockedReason.BETA_SIGNUP_CLOSED ->
                    "Beta applications are currently closed."
                betaProgramState.blockedReason == com.torve.domain.beta.BetaBlockedReason.BETA_ACCESS_ENDED ->
                    "Free beta access ended. Tester access may still be available."
                else -> BetaProgramCopy.SETTINGS_DEFAULT_SUBTITLE
            }
            TvSettingCard(
                title = "Torve Beta Program",
                subtitle = betaSubtitle,
                modifier = Modifier.fillMaxWidth().focusProperties {
                    left = railFocusRequester
                    up = categoryRequesters.getValue(TvSettingsCategory.ACCOUNT)
                    down = if (authUser != null) authAccountRequester else authEmailRequester
                },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(accountBetaProgramTarget, requester) },
                onClick = {
                    logSettingsFocus("launch_subpage category=ACCOUNT row=ACCOUNT item=beta_program reason=route_open")
                    settingsFocusController.captureOrigin(
                        itemId = accountBetaProgramTarget.itemId,
                        outerListState = settingsListState,
                        reason = "route_open",
                    )
                    onNavigateToBetaProgram()
                },
                rowType = TvSettingRowType.NAVIGATION,
            )
        }
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
                        stringResource(R.string.tv_auth_email_unverified, authUser!!.email)
                    else authUser!!.email,
                    modifier = Modifier.fillMaxWidth().focusProperties {
                        left = railFocusRequester
                        up = if (showBetaProgramSettingsEntry) {
                            accountBetaProgramRequester
                        } else {
                            categoryRequesters.getValue(TvSettingsCategory.ACCOUNT)
                        }
                    },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(authAccountTarget, requester) },
                    onClick = {},
                    emphasis = TvSettingEmphasis.SECONDARY,
                )
            }
            if (authUser?.isVerified == false) {
                item(key = "auth_verify") {
                    val requester = rememberRegisteredTvSettingsFocusRequester(
                        controller = settingsFocusController,
                        target = authVerifyTarget,
                        externalRequester = remember("auth_verify") { FocusRequester() },
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_email_not_verified),
                        subtitle = stringResource(R.string.tv_settings_resend_verification),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(authVerifyTarget, requester) },
                        onClick = {
                            val email = authUser?.email ?: return@TvSettingCard
                            authScope.launch {
                                val result = authClient.resendVerification(email)
                                if (result.success) {
                                    TvNotificationQueue.post(
                                        context.getString(R.string.verify_email_sent),
                                        NotificationType.SUCCESS,
                                    )
                                } else {
                                    TvNotificationQueue.post(
                                        result.error ?: context.getString(R.string.tv_auth_failed_to_send),
                                        NotificationType.ERROR,
                                    )
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                    )
                }
            }
            item(key = "account_import_setup") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = accountImportSetupTarget,
                    externalRequester = remember("account_import_setup") { FocusRequester() },
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_import_setup_title),
                    subtitle = stringResource(R.string.tv_settings_import_setup_subtitle),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(accountImportSetupTarget, requester) },
                    onClick = { onNavigateToReceiveCredentials() },
                    rowType = TvSettingRowType.NAVIGATION,
                )
            }
            item(key = "account_import_refresh") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = accountImportRefreshTarget,
                    externalRequester = remember("account_import_refresh") { FocusRequester() },
                )
                TvSettingCard(
                    title = if (importedSetupRefreshRunning) {
                        stringResource(R.string.transfer_refresh_all_after_import_running)
                    } else {
                        stringResource(R.string.transfer_refresh_all_after_import)
                    },
                    subtitle = stringResource(R.string.tv_settings_import_refresh_subtitle),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(accountImportRefreshTarget, requester) },
                    onClick = { refreshImportedSetup() },
                    rowType = TvSettingRowType.ACTION,
                )
            }
            item(key = "auth_logout") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = authLogoutTarget,
                    externalRequester = authPrimaryActionRequester,
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_log_out),
                    subtitle = if (authIsSigningOut) {
                        stringResource(R.string.tv_auth_please_wait)
                    } else if (confirmSignOut) {
                        stringResource(R.string.tv_auth_sign_out_confirm)
                    } else {
                        stringResource(R.string.tv_auth_sign_out_desc)
                    },
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(authLogoutTarget, requester) },
                    onClick = {
                        if (authIsSigningOut) return@TvSettingCard
                        if (confirmSignOut) {
                            authScope.launch {
                                authIsSigningOut = true
                                settingsFocusController.captureOrigin(
                                    itemId = authLogoutTarget.itemId,
                                    outerListState = settingsListState,
                                    reason = "confirm",
                                )
                                pendingAuthTransitionFocusTarget = null
                                try {
                                    CatalogWarmupWorker.cancel(context.applicationContext)
                                    EpgWarmupWorker.cancel(context.applicationContext)
                                    TraktSyncWorker.cancel(context.applicationContext)
                                    accountSessionCoordinator.signOut()
                                    authClient.logout()
                                    authUser = null
                                    authEmail = ""
                                    authPassword = ""
                                    authConfirmPassword = ""
                                    confirmSignOut = false
                                    subscriptionViewModel.loadSubscription()
                                    TvNotificationQueue.post(
                                        context.getString(R.string.tv_auth_logged_out),
                                        NotificationType.INFO,
                                    )
                                } finally {
                                    authIsSigningOut = false
                                }
                            }
                        } else {
                            confirmSignOut = true
                        }
                    },
                    rowType = TvSettingRowType.DANGEROUS,
                    focusedHint = stringResource(R.string.tv_auth_sign_out_second_press_hint),
                )
                if (authIsSigningOut) {
                    AlertDialog(
                        onDismissRequest = {},
                        containerColor = Charcoal,
                        title = { Text(stringResource(R.string.tv_settings_log_out), color = Snow) },
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(color = Amber, strokeWidth = 3.dp)
                                Text(stringResource(R.string.tv_auth_please_wait), color = Silver)
                            }
                        },
                        confirmButton = {},
                        dismissButton = {},
                    )
                }
            }
            item(key = "auth_delete_account") {
                var showDeleteDialog by remember { mutableStateOf(false) }
                var isDeletingAccount by remember { mutableStateOf(false) }
                val deleteRequester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = authDeleteTarget,
                    externalRequester = remember("auth_delete_account") { FocusRequester() },
                )
                TvSettingCard(
                    title = stringResource(R.string.settings_delete_account),
                    subtitle = stringResource(R.string.tv_settings_delete_account_subtitle),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = deleteRequester,
                    onFocused = { onSettingsRowFocused(authDeleteTarget, deleteRequester) },
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
                            LaunchedEffect(Unit) {
                                withFrameNanos { }
                                runCatching { cancelRequester.requestFocus() }
                            }
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
                                                pendingAuthTransitionFocusTarget = authEmailTarget
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
                    upFocusRequester = if (showBetaProgramSettingsEntry) {
                        accountBetaProgramRequester
                    } else {
                        categoryRequesters.getValue(TvSettingsCategory.ACCOUNT)
                    },
                    onContentFocused = {
                        onSettingsRowFocused(authEmailTarget, requester)
                    },
                    onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                    onValueChange = { authEmail = it },
                )
            }
            item(key = "auth_password") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = authPasswordTarget,
                    externalRequester = remember("auth_password") { FocusRequester() },
                )
                TvTextInputCard(
                    key = "auth_password",
                    title = stringResource(R.string.tv_settings_password),
                    value = authPassword,
                    focusRequester = requester,
                    expandedInput = expandedInput,
                    railFocusRequester = railFocusRequester,
                    onContentFocused = {
                        onSettingsRowFocused(authPasswordTarget, requester)
                    },
                    onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                    onValueChange = { authPassword = it },
                    isPassword = true,
                )
            }
            if (authShowRegister) {
                item(key = "auth_confirm_password") {
                    val requester = rememberRegisteredTvSettingsFocusRequester(
                        controller = settingsFocusController,
                        target = authConfirmPasswordTarget,
                        externalRequester = remember("auth_confirm_password") { FocusRequester() },
                    )
                    TvTextInputCard(
                        key = "auth_confirm_password",
                        title = stringResource(R.string.tv_settings_confirm_password),
                        value = authConfirmPassword,
                        focusRequester = requester,
                        expandedInput = expandedInput,
                        railFocusRequester = railFocusRequester,
                        onContentFocused = {
                            onSettingsRowFocused(authConfirmPasswordTarget, requester)
                        },
                        onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                        onValueChange = { authConfirmPassword = it },
                        isPassword = true,
                    )
                }
            }
            item(key = "auth_submit") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = authSubmitTarget,
                    externalRequester = authPrimaryActionRequester,
                )
                TvSettingCard(
                    title = if (authShowRegister) stringResource(R.string.tv_auth_create_account) else stringResource(R.string.tv_auth_log_in),
                    subtitle = if (authIsLoading) stringResource(R.string.tv_auth_please_wait)
                              else if (authShowRegister) stringResource(R.string.tv_auth_sign_up_desc)
                              else stringResource(R.string.tv_auth_sign_in_desc),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(authSubmitTarget, requester) },
                    onClick = {
                        if (!authIsLoading) {
                            if (authShowRegister && authPassword != authConfirmPassword) {
                                authError = passwordsMismatchText
                                TvNotificationQueue.post(passwordsMismatchText, NotificationType.ERROR)
                                return@TvSettingCard
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
                                if (result.success) {
                                    settingsFocusController.clearPendingRestore()
                                    settingsFocusController.clearSavedReturnTarget()
                                    settingsFocusController.clearPendingFocusRepair()
                                    pendingAuthTransitionFocusTarget = null
                                    authUser = result.user
                                    authPassword = ""
                                    authConfirmPassword = ""
                                    subscriptionViewModel.loadSubscription()
                                    // Fetch and apply shared account settings (language, ratings, etc.)
                                    runCatching { accountSessionCoordinator.bootstrapAfterSignIn() }
                                    deviceGovernanceViewModel.fetchAccessState()
                                    PostSignInRefresh.enqueueAfterAccountRestore(context, accountSessionCoordinator)
                                    settingsViewModel.refreshSettings()
                                    authIsLoading = false
                                    onAuthSuccess()
                                    val msg = if (authShowRegister) {
                                        context.getString(R.string.tv_auth_account_created_verify)
                                    } else {
                                        context.getString(R.string.tv_auth_logged_in)
                                    }
                                    TvNotificationQueue.post(msg, NotificationType.SUCCESS)
                                } else {
                                    authIsLoading = false
                                    authError = result.error
                                    TvNotificationQueue.post(
                                        result.error ?: context.getString(R.string.tv_auth_failed),
                                        NotificationType.ERROR,
                                    )
                                }
                            }
                        }
                    },
                    rowType = TvSettingRowType.ACTION,
                )
            }
            item(key = "auth_toggle") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = authToggleTarget,
                    externalRequester = remember("auth_toggle") { FocusRequester() },
                )
                TvSettingCard(
                    title = if (authShowRegister) stringResource(R.string.tv_auth_already_have_account) else stringResource(R.string.tv_auth_no_account),
                    subtitle = if (authShowRegister) stringResource(R.string.tv_auth_switch_login) else stringResource(R.string.tv_auth_switch_signup),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(authToggleTarget, requester) },
                    onClick = {
                        authShowRegister = !authShowRegister
                        authConfirmPassword = ""
                        authError = null
                    },
                    rowType = TvSettingRowType.NAVIGATION,
                )
            }
            // QR sign-in escape hatch — typing email + password on a TV
            // remote is the worst onboarding moment in the app. This card
            // takes the user to a screen that displays a one-time pairing
            // code as a QR; their phone scans, claims, and the TV silently
            // signs in via the same persisted-tokens path as email login.
            item(key = "auth_pair_with_phone") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = authPairWithPhoneTarget,
                    externalRequester = remember("auth_pair_with_phone") { FocusRequester() },
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_pair_phone_title),
                    subtitle = stringResource(R.string.tv_settings_pair_phone_subtitle),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(authPairWithPhoneTarget, requester) },
                    onClick = onNavigateToPairingSignIn,
                    rowType = TvSettingRowType.NAVIGATION,
                )
            }
            if (!authShowRegister) {
                item(key = "auth_forgot_password") {
                    val requester = rememberRegisteredTvSettingsFocusRequester(
                        controller = settingsFocusController,
                        target = authForgotPasswordTarget,
                        externalRequester = remember("auth_forgot_password") { FocusRequester() },
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_forgot_password),
                        subtitle = stringResource(R.string.tv_settings_forgot_password_desc),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(authForgotPasswordTarget, requester) },
                        onClick = {
                            if (!authIsLoading && authEmail.isNotBlank()) {
                                authError = null
                                authIsLoading = true
                                authScope.launch {
                                    val result = authClient.requestPasswordReset(authEmail)
                                    authIsLoading = false
                                    if (result.success) {
                                        TvNotificationQueue.post(
                                            context.getString(R.string.login_reset_sent),
                                            NotificationType.INFO,
                                    )
                                } else {
                                    authIsLoading = false
                                    authError = result.error
                                    TvNotificationQueue.post(
                                        result.error ?: context.getString(R.string.tv_auth_failed),
                                            NotificationType.ERROR,
                                        )
                                    }
                                }
                            } else if (authEmail.isBlank()) {
                                authError = context.getString(R.string.tv_auth_email_required)
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
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

        if (selectedCategory == TvSettingsCategory.ACCOUNT && com.torve.android.BuildConfig.HAS_BILLING) {
            item(key = "section_account_subscription") {
                TvSectionHeader(
                    text = stringResource(R.string.tv_settings_subscription_title),
                    description = stringResource(R.string.tv_settings_subscription_desc),
                )
            }
            item(key = "subscription") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = subscriptionMonthlyTarget,
                externalRequester = subscriptionCardRequester,
            )
            val billingManager: com.torve.android.billing.BillingManager = koinInject()
            val purchaseResult by billingManager.purchaseResult.collectAsState()
            val billingContext = LocalContext.current
            val activity = billingContext as? android.app.Activity
            val isAmazonBuild = com.torve.android.BuildConfig.FLAVOR.contains("amazon", ignoreCase = true)
            val useStripeBilling = com.torve.android.billing.isStripeFireTvBillingBuild()
            val purchasePlatform = when {
                useStripeBilling -> "stripe_fire_tv"
                isAmazonBuild -> "amazon_fire_tv"
                else -> "google_play_tv"
            }
            val purchaseStoreLabel = when {
                useStripeBilling -> "Stripe"
                isAmazonBuild -> "Amazon Appstore"
                else -> "Google Play"
            }

            LaunchedEffect(Unit) {
                if (!useStripeBilling) {
                    billingManager.initialize()
                }
            }

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
                                    productId = result.productId.ifBlank { "com.torve.pro.lifetime" },
                                    purchaseToken = result.purchaseToken,
                                    platform = purchasePlatform,
                                )
                            }
                        }
                        billingManager.clearPurchaseResult()
                        TvNotificationQueue.post(
                            context.getString(R.string.tv_purchase_verifying_premium),
                            NotificationType.INFO,
                        )
                    }
                    is com.torve.android.billing.BillingManager.PurchaseResult.AlreadyOwned -> {
                        if (useStripeBilling) {
                            subscriptionViewModel.refreshAccess()
                        } else if (isAmazonBuild) {
                            subscriptionViewModel.restoreAmazonPurchases(platform = purchasePlatform)
                        } else {
                            subscriptionViewModel.restoreStorePurchases(
                                store = "google_play",
                                platform = purchasePlatform,
                                storeLabel = purchaseStoreLabel,
                            )
                        }
                        billingManager.clearPurchaseResult()
                        TvNotificationQueue.post(
                            context.getString(R.string.tv_purchase_restore_started),
                            NotificationType.INFO,
                        )
                    }
                    is com.torve.android.billing.BillingManager.PurchaseResult.Pending -> {
                        subscriptionViewModel.markPurchasePending(result.message)
                        billingManager.clearPurchaseResult()
                    }
                    is com.torve.android.billing.BillingManager.PurchaseResult.Cancelled -> {
                        TvNotificationQueue.post(
                            context.getString(R.string.tv_purchase_cancelled),
                            NotificationType.INFO,
                        )
                        billingManager.clearPurchaseResult()
                    }
                    is com.torve.android.billing.BillingManager.PurchaseResult.Error -> {
                        TvNotificationQueue.post(result.message, NotificationType.ERROR)
                        billingManager.clearPurchaseResult()
                    }
                    else -> {}
                }
            }

            val billingState by billingManager.billingState.collectAsState()
            val monthlyOffer = if (useStripeBilling) {
                remember {
                    com.torve.android.billing.BillingManager.BillingOffer(
                        productType = com.torve.android.billing.BillingManager.ProductType.MONTHLY,
                        productId = "stripe_monthly",
                        formattedPrice = "Stripe checkout",
                        billingDetails = "Opens secure checkout in your browser",
                    )
                }
            } else {
                remember(billingState) {
                    billingManager.getOffer(com.torve.android.billing.BillingManager.ProductType.MONTHLY)
                }
            }
            val lifetimeOffer = if (useStripeBilling) {
                remember {
                    com.torve.android.billing.BillingManager.BillingOffer(
                        productType = com.torve.android.billing.BillingManager.ProductType.LIFETIME,
                        productId = "stripe_lifetime",
                        formattedPrice = "Stripe checkout",
                        billingDetails = "Opens secure checkout in your browser",
                    )
                }
            } else {
                remember(billingState) {
                    billingManager.getOffer(com.torve.android.billing.BillingManager.ProductType.LIFETIME)
                }
            }
            val billingErrorMessage = if (useStripeBilling) null else (billingState as? com.torve.android.billing.BillingManager.BillingState.Error)?.message
            val purchaseBlocked = subscriptionState.purchaseVerificationState == PurchaseVerificationState.PENDING
            val currentStatus = subscriptionAccess.accessStatusLabel
            val currentMarketplace = subscriptionMarketplaceLabel(subscriptionState.subscription?.platform)
            val showLifetimeForThisStore = subscriptionAccess.shouldShowBuyLifetime &&
                (!subscriptionAccess.hasPremiumEntitlement || currentMarketplace == purchaseStoreLabel)
            val priceUnavailableLabel = stringResource(R.string.paywall_price)
            val priceLoadingLabel = stringResource(R.string.paywall_price_loading)

            Column {
                if (subscriptionAccess.shouldShowManageDevices) {
                    val manageDevicesRequester = rememberRegisteredTvSettingsFocusRequester(
                        controller = settingsFocusController,
                        target = subscriptionManageDevicesTarget,
                        externalRequester = remember("subscription_manage_devices_primary") { FocusRequester() },
                    )
                    TvSettingCard(
                        title = stringResource(R.string.manage_devices_title),
                        subtitle = stringResource(R.string.tv_premium_needs_activation),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = manageDevicesRequester,
                        onFocused = { onSettingsRowFocused(subscriptionManageDevicesTarget, manageDevicesRequester) },
                        onClick = {
                            settingsFocusController.captureOrigin(
                                itemId = subscriptionManageDevicesTarget.itemId,
                                outerListState = settingsListState,
                                reason = "route_open",
                            )
                            onNavigateToActivatedDevices(subscriptionManageDevicesTarget.itemId)
                        },
                        rowType = TvSettingRowType.ACTION,
                    )
                }

                TvSettingCard(
                    title = stringResource(R.string.tv_settings_monthly_access),
                    subtitle = when {
                        subscriptionState.hasEntitlement -> currentStatus
                        monthlyOffer != null -> "${monthlyOffer.formattedPrice ?: "$1.99"} - ${monthlyOffer.billingDetails}"
                        billingState is com.torve.android.billing.BillingManager.BillingState.Connecting ||
                            billingState is com.torve.android.billing.BillingManager.BillingState.Disconnected ->
                            priceLoadingLabel
                        else -> billingErrorMessage ?: priceUnavailableLabel
                    },
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(subscriptionMonthlyTarget, requester) },
                    onClick = {
                        if (subscriptionAccess.shouldShowBuyMonthly) {
                            if (monthlyOffer == null) {
                                TvNotificationQueue.post(
                                    billingErrorMessage ?: priceUnavailableLabel,
                                    NotificationType.INFO,
                                )
                            } else if (purchaseBlocked) {
                                TvNotificationQueue.post(
                                    subscriptionState.purchaseStatus?.message
                                        ?: context.getString(R.string.tv_purchase_verification_pending),
                                    NotificationType.INFO,
                                )
                            } else {
                                if (useStripeBilling) {
                                    com.torve.android.billing.launchStripeCheckout(
                                        context = billingContext,
                                        viewModel = subscriptionViewModel,
                                        productType = com.torve.android.billing.BillingManager.ProductType.MONTHLY,
                                    )
                                } else {
                                    subscriptionViewModel.requireAccountForPurchase(
                                        purchaseStoreLabel,
                                        com.torve.domain.model.SubscriptionTier.MONTHLY,
                                    ) {
                                        activity?.let {
                                            billingManager.launchPurchase(
                                                it,
                                                com.torve.android.billing.BillingManager.ProductType.MONTHLY,
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (subscriptionState.hasEntitlement) {
                            TvNotificationQueue.post(currentStatus, NotificationType.INFO)
                        }
                    },
                    rowType = TvSettingRowType.ACTION,
                )

                val lifetimeRequester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = subscriptionLifetimeTarget,
                    externalRequester = remember("subscription_lifetime") { FocusRequester() },
                )
                val visibleLifetimeOfferForCard = lifetimeOffer.takeIf { showLifetimeForThisStore }
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_lifetime_access),
                    subtitle = when {
                        visibleLifetimeOfferForCard != null -> "${visibleLifetimeOfferForCard.formattedPrice ?: "$23.99"} - ${visibleLifetimeOfferForCard.billingDetails}"
                        !showLifetimeForThisStore && subscriptionState.hasEntitlement -> currentStatus
                        lifetimeOffer != null && showLifetimeForThisStore -> "${lifetimeOffer.formattedPrice ?: "$23.99"} - ${lifetimeOffer.billingDetails}"
                        billingState is com.torve.android.billing.BillingManager.BillingState.Connecting ||
                            billingState is com.torve.android.billing.BillingManager.BillingState.Disconnected ->
                            priceLoadingLabel
                        else -> billingErrorMessage ?: priceUnavailableLabel
                    },
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = lifetimeRequester,
                    onFocused = { onSettingsRowFocused(subscriptionLifetimeTarget, lifetimeRequester) },
                    onClick = {
                        if (showLifetimeForThisStore) {
                            if (lifetimeOffer == null) {
                                TvNotificationQueue.post(
                                    billingErrorMessage ?: priceUnavailableLabel,
                                    NotificationType.INFO,
                                )
                            } else if (purchaseBlocked) {
                                TvNotificationQueue.post(
                                    subscriptionState.purchaseStatus?.message
                                        ?: context.getString(R.string.tv_purchase_verification_pending),
                                    NotificationType.INFO,
                                )
                            } else {
                                if (useStripeBilling) {
                                    com.torve.android.billing.launchStripeCheckout(
                                        context = billingContext,
                                        viewModel = subscriptionViewModel,
                                        productType = com.torve.android.billing.BillingManager.ProductType.LIFETIME,
                                    )
                                } else {
                                    subscriptionViewModel.requireAccountForPurchase(
                                        purchaseStoreLabel,
                                        com.torve.domain.model.SubscriptionTier.LIFETIME,
                                    ) {
                                        activity?.let {
                                            billingManager.launchPurchase(
                                                it,
                                                com.torve.android.billing.BillingManager.ProductType.LIFETIME,
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (subscriptionState.hasEntitlement) {
                            TvNotificationQueue.post(currentStatus, NotificationType.INFO)
                        }
                    },
                    rowType = TvSettingRowType.ACTION,
                )

                if (useStripeBilling && subscriptionState.canManageStripeBilling) {
                    val manageBillingRequester = rememberRegisteredTvSettingsFocusRequester(
                        controller = settingsFocusController,
                        target = subscriptionManageBillingTarget,
                        externalRequester = remember("subscription_manage_billing") { FocusRequester() },
                    )
                    TvSettingCard(
                        title = "Manage Stripe billing",
                        subtitle = "Open Stripe billing for invoices, cards, and cancellation.",
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = manageBillingRequester,
                        onFocused = { onSettingsRowFocused(subscriptionManageBillingTarget, manageBillingRequester) },
                        onClick = {
                            com.torve.android.billing.launchStripePortal(
                                context = billingContext,
                                viewModel = subscriptionViewModel,
                            )
                        },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                    )
                }

                TvStatusSummaryCard(
                    title = subscriptionAccess.accessStatusLabel,
                    message = when {
                        subscriptionAccess.isPremiumButBlockedOnThisDevice ->
                            stringResource(R.string.tv_premium_needs_activation)
                        subscriptionAccess.hasPremiumEntitlement ->
                            stringResource(
                                R.string.tv_purchase_billing_managed_by,
                                subscriptionMarketplaceLabel(subscriptionState.subscription?.platform)
                                    ?: purchaseStoreLabel,
                            )
                        else ->
                            stringResource(R.string.tv_purchase_choose_billing_store, purchaseStoreLabel)
                    },
                    tone = if (subscriptionAccess.isPremiumButBlockedOnThisDevice) PurchaseStatusTone.ERROR else PurchaseStatusTone.INFO,
                )

                subscriptionState.purchaseStatus?.let { status ->
                    TvStatusSummaryCard(
                        title = status.title,
                        message = status.message,
                        tone = status.tone,
                    )
                }

                val refreshRequester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = subscriptionRefreshTarget,
                    externalRequester = remember("subscription_refresh") { FocusRequester() },
                )
                TvSettingCard(
                    title = stringResource(R.string.premium_refresh_access),
                    subtitle = stringResource(R.string.tv_premium_check_access),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = refreshRequester,
                    onFocused = { onSettingsRowFocused(subscriptionRefreshTarget, refreshRequester) },
                    onClick = { subscriptionViewModel.refreshAccess() },
                    rowType = TvSettingRowType.ACTION,
                    emphasis = TvSettingEmphasis.SECONDARY,
                )

                if (subscriptionState.purchaseStatus?.showRetryVerification == true) {
                    val retryRequester = rememberRegisteredTvSettingsFocusRequester(
                        controller = settingsFocusController,
                        target = subscriptionRetryTarget,
                        externalRequester = remember("subscription_retry") { FocusRequester() },
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_retry_verification),
                        subtitle = stringResource(R.string.tv_settings_retry_verification_desc),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = retryRequester,
                        onFocused = { onSettingsRowFocused(subscriptionRetryTarget, retryRequester) },
                        onClick = { subscriptionViewModel.retryPendingAmazonVerification() },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                    )
                }

                val restoreRequester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = subscriptionRestoreTarget,
                    externalRequester = remember("subscription_restore") { FocusRequester() },
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_restore_purchase),
                    subtitle = stringResource(R.string.tv_settings_restore_purchase_desc),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = restoreRequester,
                    onFocused = { onSettingsRowFocused(subscriptionRestoreTarget, restoreRequester) },
                    onClick = {
                        subscriptionViewModel.requireAccountForRestore(purchaseStoreLabel) {
                            if (useStripeBilling) {
                                subscriptionViewModel.refreshAccess()
                            } else if (isAmazonBuild) {
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

        when (setupMode) {
            TvSetupMode.ANDROID_PHONE, TvSetupMode.IOS_PHONE -> {
                // Phone mode: read-only service status managed by paired phone
                if (selectedCategory == TvSettingsCategory.CONNECTIONS) {
                item(key = "section_connections_services") {
                    TvSectionHeader(
                        text = stringResource(R.string.tv_settings_linked_services),
                        description = stringResource(R.string.tv_settings_linked_services_desc),
                    )
                }
                if (showRecoveryRow) {
                    item(key = "restore_setup") {
                        val requester = remember("restore_setup") { FocusRequester() }
                        val missingCount = recoverySnap?.missingTransferableCategoryCount ?: 0
                        TvSettingCard(
                            title = stringResource(R.string.recovery_card_title),
                            subtitle = stringResource(
                                R.string.recovery_card_summary_format,
                                missingCount,
                            ),
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = requester,
                            onFocused = { },
                            onClick = { onNavigateToReceiveCredentials() },
                            rowType = TvSettingRowType.ACTION,
                        )
                    }
                }
                // Prompt 11: unconditional "Receive credentials" entry
                // so a fresh TV install can pick up secrets from a
                // signed-in desktop without typing API keys on a
                // remote. Sits next to the recovery card so all
                // transfer-related actions cluster.
                item(key = "transfer_receive_unconditional") {
                    val requester = remember("transfer_receive_unconditional") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.settings_receive_credentials),
                        subtitle = stringResource(R.string.tv_settings_receive_credentials_import_desc),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { },
                        onClick = { onNavigateToReceiveCredentials() },
                        rowType = TvSettingRowType.ACTION,
                    )
                }

                // Provider-health repair rows. Each row shows the
                // first remote-reachable action: Transfer beats
                // Diagnostics when both apply (matches mapper order).
                // No-op until an Android checker init exists.
                tvRepairRows.forEach { (entry, actions) ->
                    item(key = "health:${entry.providerKey}") {
                        val requester = remember("health:${entry.providerKey}") { FocusRequester() }
                        val primary = actions.firstOrNull {
                            it == com.torve.presentation.providerhealth.ProviderRepairAction.TransferFromAnotherDevice ||
                                it == com.torve.presentation.providerhealth.ProviderRepairAction.OpenDiagnostics
                        }
                        val (subtitleSuffix, onTap) = when (primary) {
                            com.torve.presentation.providerhealth.ProviderRepairAction.TransferFromAnotherDevice ->
                                stringResource(R.string.tv_settings_open_transfer_arrow) to { onNavigateToReceiveCredentials() }
                            com.torve.presentation.providerhealth.ProviderRepairAction.OpenDiagnostics ->
                                stringResource(R.string.tv_settings_open_diagnostics_arrow) to { onNavigateToTransferDiagnostics() }
                            else -> "" to { /* unreachable */ }
                        }
                        val message = entry.message?.takeIf { it.isNotBlank() }
                        val combined = if (message != null) "$message  •  $subtitleSuffix"
                        else subtitleSuffix
                        TvSettingCard(
                            title = entry.label,
                            subtitle = combined,
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = requester,
                            onFocused = { },
                            onClick = onTap,
                            rowType = TvSettingRowType.ACTION,
                        )
                    }
                }
                item(key = "send_credentials") {
                    val requester = remember("send_credentials") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.settings_send_credentials),
                        subtitle = stringResource(R.string.tv_send_credentials_subtitle),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { },
                        onClick = { onNavigateToSendCredentials() },
                        rowType = TvSettingRowType.ACTION,
                    )
                }
                item(key = "receive_credentials") {
                    val requester = remember("receive_credentials") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.settings_receive_credentials),
                        subtitle = stringResource(R.string.tv_receive_credentials_subtitle),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { },
                        onClick = { onNavigateToReceiveCredentials() },
                        rowType = TvSettingRowType.ACTION,
                    )
                }
                item(key = "transfer_diagnostics") {
                    val requester = remember("transfer_diagnostics") { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.settings_transfer_diagnostics),
                        subtitle = stringResource(R.string.diag_transfer_redaction_note),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { },
                        onClick = { onNavigateToTransferDiagnostics() },
                        rowType = TvSettingRowType.ACTION,
                    )
                }
                item(key = "trakt") {
                    val requester = remember("trakt") { FocusRequester() }
                    TvSettingCard(
                        title = traktLabel,
                        subtitle = traktSubtitle,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(connectionsTraktTarget, requester) },
                        onClick = {
                            when {
                                settingsState.traktConnected -> {
                                    TvNotificationQueue.post("Checking Trakt connection...", NotificationType.INFO)
                                    settingsViewModel.syncTraktNow()
                                }
                                settingsState.isPollingTrakt -> {
                                    TvNotificationQueue.post("Waiting for Trakt authorization", NotificationType.INFO)
                                }
                                else -> {
                                    TvNotificationQueue.post("Starting Trakt authorization", NotificationType.INFO)
                                    settingsViewModel.startTraktDeviceAuth()
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                    )
                }
                item(key = "simkl") {
                    val requester = remember("simkl") { FocusRequester() }
                    val simklUser = settingsState.simklUser
                    val sub = when {
                        !settingsState.simklConnected -> notConnectedLabel
                        simklUser != null -> "${simklUser.username} - $connectedLabel"
                        settingsState.simklLoading -> "Checking SIMKL connection..."
                        settingsState.simklError != null -> "Token found - SIMKL needs attention"
                        else -> "Token found - press OK to verify or reconnect"
                    }
                    TvSettingCard(
                        title = simklLabel,
                        subtitle = sub,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(connectionsSimklTarget, requester) },
                        onClick = {
                            when {
                                settingsState.simklConnected && settingsState.simklUser == null -> {
                                    TvNotificationQueue.post("Checking SIMKL connection...", NotificationType.INFO)
                                    settingsViewModel.checkSimklConnection()
                                }
                                settingsState.simklConnected -> {
                                    TvNotificationQueue.post("Starting SIMKL reauthorization", NotificationType.INFO)
                                    settingsViewModel.startSimklDeviceAuth()
                                }
                                settingsState.isPollingSimkl -> {
                                    TvNotificationQueue.post("Waiting for SIMKL authorization", NotificationType.INFO)
                                }
                                else -> {
                                    TvNotificationQueue.post("Starting SIMKL authorization", NotificationType.INFO)
                                    settingsViewModel.startSimklDeviceAuth()
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                    )
                }
                }

                // Phone mode: read-only integration statuses
                settingsState.simklDeviceCode?.let { code ->
                    item(key = "simkl_device_code_panel") {
                        TvDeviceAuthorizationPanel(
                            title = "Connect SIMKL",
                            verificationUrl = code.verificationUrl,
                            userCode = code.userCode,
                            status = when {
                                settingsState.simklError != null -> settingsState.simklError!!
                                settingsState.isPollingSimkl -> stringResource(R.string.tv_settings_auth_waiting_authorization)
                                else -> stringResource(R.string.tv_settings_auth_open_url_code)
                            },
                            isError = settingsState.simklError != null,
                            isPolling = settingsState.isPollingSimkl,
                        )
                    }
                }

                if (selectedCategory == TvSettingsCategory.ADVANCED && !advancedLocked) {
                item(key = "section_advanced_phone_metadata") {
                    TvSectionHeader(
                        text = stringResource(R.string.tv_settings_metadata_providers),
                        description = stringResource(R.string.tv_settings_metadata_read_only),
                    )
                }
                item(key = "phone_omdb") {
                    val requester = rememberSettingsRowRequester(
                        target = advancedEntryTarget,
                        externalRequester = advancedPhoneEntryRequester,
                        isDefaultEntry = true,
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_omdb),
                        subtitle = if (settingsState.omdbApiKey.isNotBlank()) connectedLabel else notConnectedLabel,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            up = categoryRequesters.getValue(TvSettingsCategory.ADVANCED)
                        },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(advancedEntryTarget, requester) },
                        onClick = { onRequestLifetimeUnlock(TvEntitledFeature.OMDB_SETUP) },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                        premiumLocked = isLockedFeature(TvEntitledFeature.OMDB_SETUP),
                    )
                }

                item(key = "phone_mdblist") {
                    val requester = rememberSettingsRowRequester(
                        target = advancedPhoneMdblistTarget,
                        externalRequester = remember("phone_mdblist") { FocusRequester() },
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_mdblist),
                        subtitle = if (settingsState.mdblistApiKey.isNotBlank()) connectedLabel else notConnectedLabel,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(advancedPhoneMdblistTarget, requester) },
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
                    val requester = rememberSettingsRowRequester(
                        target = advancedPhoneJellyfinTarget,
                        externalRequester = remember("phone_jellyfin") { FocusRequester() },
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_jellyfin),
                        subtitle = if (settingsState.jellyfinServerUrl.isNotBlank()) connectedLabel else notConnectedLabel,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(advancedPhoneJellyfinTarget, requester) },
                        onClick = { onRequestLifetimeUnlock(TvEntitledFeature.JELLYFIN_SETUP) },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                        premiumLocked = isLockedFeature(TvEntitledFeature.JELLYFIN_SETUP),
                    )
                }

                item(key = "phone_plex") {
                    val requester = rememberSettingsRowRequester(
                        target = advancedPhonePlexTarget,
                        externalRequester = remember("phone_plex") { FocusRequester() },
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_plex),
                        subtitle = if (settingsState.plexConnected) connectedLabel else notConnectedLabel,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(advancedPhonePlexTarget, requester) },
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
                if (selectedCategory == TvSettingsCategory.CONNECTIONS) {
                    item(key = "section_connections_tv_accounts") {
                        TvSectionHeader(
                            text = stringResource(R.string.tv_settings_streaming_accounts),
                            description = stringResource(R.string.tv_settings_streaming_accounts_desc),
                        )
                    }
                    // Prompt 15 fix: "Receive credentials" must be reachable
                    // on a fresh TV install, not only on phone-paired ones.
                    // The earlier "unconditional" entry was nested inside the
                    // ANDROID_PHONE / IOS_PHONE branch only — meaning a
                    // brand-new TV (default setupMode = TV_ONLY) had no way
                    // to receive credentials over the remote at all. Place
                    // it FIRST in this section so a non-technical user lands
                    // on it immediately when they open Connections.
                    item(key = "transfer_receive_tv_only") {
                        val requester = remember("transfer_receive_tv_only") { FocusRequester() }
                        TvSettingCard(
                            title = stringResource(R.string.settings_receive_credentials),
                            subtitle = stringResource(R.string.tv_settings_receive_credentials_tv_desc),
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = requester,
                            onFocused = { },
                            onClick = { onNavigateToReceiveCredentials() },
                            rowType = TvSettingRowType.ACTION,
                        )
                    }
                    // ── Debrid management ──
                    if (false) {
                    item(key = "section_debrid") {
                        TvSectionHeader(text = "Cloud Services (Debrid)")
                    }
                    when {
                        settingsState.debridDeviceCode != null -> {
                            val code = settingsState.debridDeviceCode!!
                            item(key = "debrid_code_info") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Graphite, RoundedCornerShape(10.dp))
                                        .border(1.dp, Steel, RoundedCornerShape(10.dp))
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.tv_settings_debrid_connecting_to,
                                            settingsState.debridProvider.name.replace('_', ' ').lowercase()
                                                .replaceFirstChar { it.uppercase() },
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Amber,
                                    )
                                    Text(
                                        stringResource(R.string.tv_settings_debrid_visit_url, code.verificationUrl),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Snow,
                                    )
                                    Text(
                                        stringResource(R.string.tv_settings_debrid_enter_code, code.userCode),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Amber,
                                    )
                                }
                            }
                            item(key = "debrid_cancel") {
                                val requester = remember("debrid_cancel") { FocusRequester() }
                                TvSettingCard(
                                    title = stringResource(R.string.common_cancel),
                                    subtitle = stringResource(R.string.tv_settings_debrid_cancel_auth_desc),
                                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                                    focusRequester = requester,
                                    onFocused = { },
                                    onClick = { settingsViewModel.disconnectDebrid() },
                                    rowType = TvSettingRowType.DANGEROUS,
                                )
                            }
                        }
                        settingsState.debridConnected -> {
                            item(key = "debrid_status") {
                                val providerLabel = when (settingsState.debridProvider) {
                                    com.torve.domain.model.DebridServiceType.REAL_DEBRID -> "Real-Debrid"
                                    com.torve.domain.model.DebridServiceType.ALL_DEBRID -> "AllDebrid"
                                    com.torve.domain.model.DebridServiceType.PREMIUMIZE -> "Premiumize"
                                    com.torve.domain.model.DebridServiceType.TORBOX -> "TorBox"
                                }
                                val username = settingsState.debridUser?.username
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Graphite, RoundedCornerShape(10.dp))
                                        .border(1.dp, Steel, RoundedCornerShape(10.dp))
                                        .padding(16.dp),
                                ) {
                                    Text(providerLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Snow)
                                    if (!username.isNullOrBlank()) {
                                        Text(username, style = MaterialTheme.typography.bodySmall, color = Silver)
                                    }
                                    Text(stringResource(R.string.tv_settings_connected), style = MaterialTheme.typography.labelSmall, color = Emerald)
                                }
                            }
                            item(key = "debrid_disconnect") {
                                val requester = remember("debrid_disconnect") { FocusRequester() }
                                TvSettingCard(
                                    title = stringResource(R.string.common_disconnect),
                                    subtitle = stringResource(R.string.tv_settings_debrid_disconnect_desc),
                                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                                    focusRequester = requester,
                                    onFocused = { },
                                    onClick = { settingsViewModel.disconnectDebrid() },
                                    rowType = TvSettingRowType.DANGEROUS,
                                )
                            }
                            item(key = "debrid_reauth") {
                                val requester = remember("debrid_reauth") { FocusRequester() }
                                TvSettingCard(
                                    title = stringResource(R.string.tv_settings_debrid_reauth),
                                    subtitle = stringResource(R.string.tv_settings_debrid_reauth_desc),
                                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                                    focusRequester = requester,
                                    onFocused = { },
                                    onClick = { settingsViewModel.startDebridDeviceAuth() },
                                    rowType = TvSettingRowType.ACTION,
                                )
                            }
                        }
                        else -> {
                            item(key = "debrid_connect") {
                                val requester = remember("debrid_connect") { FocusRequester() }
                                TvSettingCard(
                                    title = stringResource(R.string.tv_settings_realdebrid_connect),
                                    subtitle = stringResource(R.string.tv_settings_realdebrid_authorize_desc),
                                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                                    focusRequester = requester,
                                    onFocused = { },
                                    onClick = {
                                        settingsViewModel.setDebridProvider(com.torve.domain.model.DebridServiceType.REAL_DEBRID)
                                        settingsViewModel.startDebridDeviceAuth()
                                    },
                                    rowType = TvSettingRowType.ACTION,
                                )
                            }
                        }
                    }

                    }

                    // Trakt (device code auth)
                item(key = "trakt") {
                    val requester = rememberSettingsRowRequester(
                        target = connectionsTraktTarget,
                        externalRequester = traktCardRequester,
                    )
                    TvSettingCard(
                        title = traktLabel,
                        subtitle = traktSubtitle,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            if (settingsState.traktConnected) {
                                down = traktReconnectCardRequester
                            }
                        },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(connectionsTraktTarget, requester) },
                        onClick = {
                            when {
                                settingsState.isPollingTrakt -> {
                                    TvNotificationQueue.post("Waiting for Trakt authorization", NotificationType.INFO)
                                }
                                settingsState.traktConnected -> {
                                    TvNotificationQueue.post("Checking Trakt connection...", NotificationType.INFO)
                                    settingsViewModel.syncTraktNow()
                                }
                                else -> {
                                    TvNotificationQueue.post("Starting Trakt authorization", NotificationType.INFO)
                                    settingsViewModel.startTraktDeviceAuth()
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                    )
                }

                if (settingsState.traktConnected) {
                    item(key = "trakt_reconnect") {
                        val requester = rememberSettingsRowRequester(
                            target = connectionsTraktReconnectTarget,
                            externalRequester = traktReconnectCardRequester,
                        )
                        TvSettingCard(
                            title = "Reconnect Trakt",
                            subtitle = "Start a fresh Trakt device authorization.",
                            modifier = Modifier.fillMaxWidth().focusProperties {
                                left = railFocusRequester
                                up = traktCardRequester
                                down = traktDisconnectCardRequester
                            },
                            focusRequester = requester,
                            onFocused = { onSettingsRowFocused(connectionsTraktReconnectTarget, requester) },
                            onClick = {
                                TvNotificationQueue.post("Starting Trakt reauthorization", NotificationType.INFO)
                                settingsViewModel.startTraktDeviceAuth()
                            },
                            rowType = TvSettingRowType.ACTION,
                        )
                    }
                    item(key = "trakt_disconnect") {
                        val requester = rememberSettingsRowRequester(
                            target = connectionsTraktDisconnectTarget,
                            externalRequester = traktDisconnectCardRequester,
                        )
                        TvSettingCard(
                            title = "Disconnect Trakt",
                            subtitle = "Clear the Trakt connection on this TV.",
                            modifier = Modifier.fillMaxWidth().focusProperties {
                                left = railFocusRequester
                                up = traktReconnectCardRequester
                                down = simklCardRequester
                            },
                            focusRequester = requester,
                            onFocused = { onSettingsRowFocused(connectionsTraktDisconnectTarget, requester) },
                            onClick = {
                                restoreConnectionFocus(
                                    itemId = TvSettingsItemIds.CONNECTIONS_TRAKT,
                                    requester = traktCardRequester,
                                    reason = "trakt_disconnect",
                                )
                                settingsViewModel.disconnectTrakt()
                                TvNotificationQueue.post("Trakt disconnected", NotificationType.SUCCESS)
                            },
                            rowType = TvSettingRowType.DANGEROUS,
                        )
                    }
                }

                settingsState.traktDeviceCode?.let { code ->
                    item(key = "trakt_device_code_panel") {
                        TvDeviceAuthorizationPanel(
                            title = "Connect Trakt",
                            verificationUrl = code.verificationUrl,
                            userCode = code.userCode,
                            status = when {
                                settingsState.traktError != null -> settingsState.traktError!!
                                settingsState.isPollingTrakt -> stringResource(R.string.tv_settings_auth_waiting_authorization)
                                else -> stringResource(R.string.tv_settings_auth_open_url_code)
                            },
                            isError = settingsState.traktError != null,
                            isPolling = settingsState.isPollingTrakt,
                        )
                    }
                }

                // SIMKL (device code auth)
                item(key = "simkl") {
                    val requester = rememberSettingsRowRequester(
                        target = connectionsSimklTarget,
                        externalRequester = simklCardRequester,
                    )
                    TvSettingCard(
                        title = simklLabel,
                        subtitle = simklSubtitle,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            if (settingsState.traktConnected) {
                                up = traktDisconnectCardRequester
                            }
                            if (settingsState.simklConnected) {
                                down = simklDisconnectCardRequester
                            }
                        },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(connectionsSimklTarget, requester) },
                        onClick = {
                            when {
                                settingsState.isPollingSimkl -> {
                                    TvNotificationQueue.post("Waiting for SIMKL authorization", NotificationType.INFO)
                                }
                                settingsState.simklConnected && settingsState.simklUser == null -> {
                                    TvNotificationQueue.post("Checking SIMKL connection...", NotificationType.INFO)
                                    settingsViewModel.checkSimklConnection()
                                }
                                settingsState.simklConnected -> {
                                    TvNotificationQueue.post("Starting SIMKL reauthorization", NotificationType.INFO)
                                    settingsViewModel.startSimklDeviceAuth()
                                }
                                else -> {
                                    TvNotificationQueue.post("Starting SIMKL authorization", NotificationType.INFO)
                                    settingsViewModel.startSimklDeviceAuth()
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                    )
                    if (settingsState.simklConnected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val disconnectRequester = rememberSettingsRowRequester(
                            target = connectionsSimklDisconnectTarget,
                            externalRequester = simklDisconnectCardRequester,
                        )
                        TvSettingCard(
                            title = "Disconnect SIMKL",
                            subtitle = "Clear the SIMKL connection on this TV.",
                            modifier = Modifier.fillMaxWidth().focusProperties {
                                left = railFocusRequester
                                up = simklCardRequester
                            },
                            focusRequester = disconnectRequester,
                            onFocused = { onSettingsRowFocused(connectionsSimklDisconnectTarget, disconnectRequester) },
                            onClick = {
                                restoreConnectionFocus(
                                    itemId = TvSettingsItemIds.CONNECTIONS_SIMKL,
                                    requester = simklCardRequester,
                                    reason = "simkl_disconnect",
                                )
                                settingsViewModel.disconnectSimkl()
                                TvNotificationQueue.post("SIMKL disconnected", NotificationType.SUCCESS)
                            },
                            rowType = TvSettingRowType.DANGEROUS,
                        )
                    }
                }

                settingsState.simklDeviceCode?.let { code ->
                    item(key = "simkl_device_code_panel") {
                        TvDeviceAuthorizationPanel(
                            title = "Connect SIMKL",
                            verificationUrl = code.verificationUrl,
                            userCode = code.userCode,
                            status = when {
                                settingsState.simklError != null -> settingsState.simklError!!
                                settingsState.isPollingSimkl -> stringResource(R.string.tv_settings_auth_waiting_authorization)
                                else -> stringResource(R.string.tv_settings_auth_open_url_code)
                            },
                            isError = settingsState.simklError != null,
                            isPolling = settingsState.isPollingSimkl,
                        )
                    }
                }

                // ── Integrations section (TV-only) ──
                }

                if (selectedCategory == TvSettingsCategory.ADVANCED && !advancedLocked) {
                    item(key = "section_panda_streaming_hub") {
                        TvSectionHeader(
                            text = stringResource(R.string.tv_settings_panda_streaming_setup),
                            description = stringResource(R.string.tv_settings_panda_streaming_setup_desc),
                        )
                    }
                    item(key = "advanced_panda_hub") {
                        val requester = rememberSettingsRowRequester(
                            target = advancedPandaTarget,
                            externalRequester = advancedPandaCardRequester,
                            isDefaultEntry = true,
                        )
                        val subtitle = when {
                            pandaProviderSummary != null -> pandaProviderSummary
                            pandaConfigured && pandaEnabled -> stringResource(R.string.tv_settings_panda_configured_enabled)
                            pandaConfigured -> stringResource(R.string.tv_settings_panda_configured_disabled)
                            else -> stringResource(R.string.tv_settings_panda_setup_desc)
                        }
                        TvSettingCard(
                            title = "Panda",
                            subtitle = subtitle,
                            modifier = Modifier.fillMaxWidth().focusProperties {
                                left = railFocusRequester
                                up = categoryRequesters.getValue(TvSettingsCategory.ADVANCED)
                            },
                            focusRequester = requester,
                            onFocused = { onSettingsRowFocused(advancedPandaTarget, requester) },
                            onClick = { onNavigateToPandaSetup() },
                            rowType = TvSettingRowType.ACTION,
                        )
                    }
                    item(key = "advanced_panda_debrid_services") {
                        val requester = rememberSettingsRowRequester(
                            target = advancedRealDebridTarget,
                            externalRequester = advancedRealDebridCardRequester,
                        )
                        val debridError = settingsState.debridError
                        val providerLabel = when (settingsState.debridProvider) {
                            com.torve.domain.model.DebridServiceType.REAL_DEBRID -> "Real-Debrid"
                            com.torve.domain.model.DebridServiceType.ALL_DEBRID -> "AllDebrid"
                            com.torve.domain.model.DebridServiceType.PREMIUMIZE -> "Premiumize"
                            com.torve.domain.model.DebridServiceType.TORBOX -> "TorBox"
                        }
                        val rdSubtitle = when {
                            debridProviderSummary != null -> debridProviderSummary
                            settingsState.debridDeviceCode != null -> "$providerLabel authorization is in progress"
                            settingsState.debridConnected -> settingsState.debridUser?.username?.let {
                                "$providerLabel connected as $it - managed by Panda"
                            } ?: "$providerLabel connected - managed by Panda"
                            debridError != null -> stringResource(
                                R.string.tv_settings_realdebrid_managed_reconnect,
                                debridError,
                            )
                            else -> "Managed by Panda: Real-Debrid, AllDebrid, Premiumize, TorBox"
                        }
                        TvSettingCard(
                            title = "Debrid services",
                            subtitle = rdSubtitle,
                            modifier = Modifier.fillMaxWidth().focusProperties {
                                left = railFocusRequester
                            },
                            focusRequester = requester,
                            onFocused = { onSettingsRowFocused(advancedRealDebridTarget, requester) },
                            onClick = { onNavigateToPandaSetup() },
                            rowType = TvSettingRowType.ACTION,
                        )
                    }
                    settingsState.debridDeviceCode?.let { code ->
                        item(key = "advanced_real_debrid_code_panel") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Charcoal.copy(alpha = 0.62f), RoundedCornerShape(16.dp))
                                    .border(1.dp, Amber.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 22.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Connect debrid through Panda",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Snow,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = code.userCode,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Amber,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = code.verificationUrl,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Snow,
                                    maxLines = 2,
                                    overflow = TextOverflow.Visible,
                                )
                                Text(
                                    text = if (settingsState.isPollingDebrid) {
                                        stringResource(R.string.tv_settings_auth_waiting_authorization)
                                    } else {
                                        stringResource(R.string.tv_settings_auth_open_url_code)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Silver,
                                )
                            }
                        }
                    }
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
                        settingsFocusController = settingsFocusController,
                        focusTarget = advancedOmdbKeyTarget,
                        upFocusRequester = advancedRealDebridCardRequester,
                        premiumFeature = TvEntitledFeature.OMDB_SETUP,
                        premiumLocked = isLockedFeature(TvEntitledFeature.OMDB_SETUP),
                        onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.OMDB_SETUP) },
                    )
                }

                item(key = "omdb_test") {
                    val requester = rememberSettingsRowRequester(
                        target = advancedOmdbTestTarget,
                        externalRequester = remember("omdb_test") { FocusRequester() },
                    )
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
                        onFocused = { onSettingsRowFocused(advancedOmdbTestTarget, requester) },
                        onClick = { settingsViewModel.validateOmdbApiKey() },
                        rowType = TvSettingRowType.ACTION,
                    )
                }

                // OpenSubtitles
                item(key = "opensubtitles_key") {
                    TvTextInputCard(
                        key = "opensubtitles_key",
                        title = stringResource(R.string.tv_settings_opensubtitles_api_key),
                        value = settingsState.opensubtitlesApiKey,
                        expandedInput = expandedInput,
                        railFocusRequester = railFocusRequester,
                        onContentFocused = onContentFocused,
                        onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                        onValueChange = { settingsViewModel.setOpenSubtitlesApiKey(it) },
                        settingsFocusController = settingsFocusController,
                        focusTarget = advancedOpenSubtitlesKeyTarget,
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
                        settingsFocusController = settingsFocusController,
                        focusTarget = advancedMdblistKeyTarget,
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
                        settingsFocusController = settingsFocusController,
                        focusTarget = advancedJellyfinUrlTarget,
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
                        settingsFocusController = settingsFocusController,
                        focusTarget = advancedJellyfinApiKeyTarget,
                        premiumFeature = TvEntitledFeature.JELLYFIN_SETUP,
                        premiumLocked = isLockedFeature(TvEntitledFeature.JELLYFIN_SETUP),
                        onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.JELLYFIN_SETUP) },
                    )
                }

                item(key = "jellyfin_test") {
                    val requester = rememberSettingsRowRequester(
                        target = advancedJellyfinTestTarget,
                        externalRequester = remember("jellyfin_test") { FocusRequester() },
                    )
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
                        onFocused = { onSettingsRowFocused(advancedJellyfinTestTarget, requester) },
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
                        settingsFocusController = settingsFocusController,
                        focusTarget = advancedPlexUrlTarget,
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
                        settingsFocusController = settingsFocusController,
                        focusTarget = advancedPlexTokenTarget,
                        premiumFeature = TvEntitledFeature.PLEX_SETUP,
                        premiumLocked = isLockedFeature(TvEntitledFeature.PLEX_SETUP),
                        onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.PLEX_SETUP) },
                    )
                }

                item(key = "plex_test") {
                    val requester = rememberSettingsRowRequester(
                        target = advancedPlexTestTarget,
                        externalRequester = remember("plex_test") { FocusRequester() },
                    )
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
                        onFocused = { onSettingsRowFocused(advancedPlexTestTarget, requester) },
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
                        val kodiHostTarget = remember(host.name, host.ip, host.port) {
                            TvSettingsFocusTarget(
                                itemId = "settings/advanced/kodi/${host.name}/${host.ip}:${host.port}",
                                category = TvSettingsCategory.ADVANCED,
                                listIndex = 30 + settingsState.kodiHosts.indexOfFirst { it.name == host.name && it.ip == host.ip && it.port == host.port }
                                    .coerceAtLeast(0),
                                focusTargetType = "action",
                            )
                        }
                        val requester = rememberSettingsRowRequester(
                            target = kodiHostTarget,
                            externalRequester = remember("kodi_${host.name}") { FocusRequester() },
                        )
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
                            onFocused = { onSettingsRowFocused(kodiHostTarget, requester) },
                            onClick = { settingsViewModel.testKodiHost(host) },
                            rowType = TvSettingRowType.ACTION,
                        )
                    }
                }

                item(key = "kodi_add") {
                    val requester = rememberSettingsRowRequester(
                        target = advancedKodiAddTarget,
                        externalRequester = addKodiCardRequester,
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_kodi_add),
                        subtitle = stringResource(R.string.tv_settings_tap_to_edit),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(advancedKodiAddTarget, requester) },
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
                            val saveTarget = remember {
                                TvSettingsFocusTarget(
                                    itemId = "settings/advanced/kodi_form/save",
                                    category = TvSettingsCategory.ADVANCED,
                                    listIndex = 36,
                                    focusTargetType = "action",
                                )
                            }
                            val registeredSaveRequester = rememberSettingsRowRequester(
                                target = saveTarget,
                                externalRequester = saveRequester,
                            )
                            TvSettingCard(
                                title = stringResource(R.string.tv_settings_kodi_add),
                                subtitle = "",
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = registeredSaveRequester,
                                onFocused = { onSettingsRowFocused(saveTarget, registeredSaveRequester) },
                                onClick = {
                                    val port = kodiPort.toIntOrNull() ?: 8080
                                    if (kodiName.isNotBlank() && kodiIp.isNotBlank()) {
                                        settingsFocusController.requestRestore(
                                            itemId = advancedKodiAddTarget.itemId,
                                            reason = "kodi_saved",
                                            outerListState = settingsListState,
                                        )
                                        settingsViewModel.addKodiHost(kodiName, kodiIp, port)
                                        kodiName = ""
                                        kodiIp = ""
                                        kodiPort = "8080"
                                        showAddKodi = false
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
                    val requester = rememberSettingsRowRequester(
                        target = advancedAiProviderTarget,
                        externalRequester = remember("ai_provider") { FocusRequester() },
                    )
                    val providers = remember { AiProvider.entries.toList() }
                    val currentIdx = remember(settingsState.aiProvider) {
                        providers.indexOf(settingsState.aiProvider).coerceAtLeast(0)
                    }
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_ai_provider),
                        subtitle = settingsState.aiProvider.label,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(advancedAiProviderTarget, requester) },
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
                        settingsFocusController = settingsFocusController,
                        focusTarget = advancedAiApiKeyTarget,
                        premiumFeature = TvEntitledFeature.AI_PROVIDER_SETUP,
                        premiumLocked = isLockedFeature(TvEntitledFeature.AI_PROVIDER_SETUP),
                        onLockedClick = { onRequestLifetimeUnlock(TvEntitledFeature.AI_PROVIDER_SETUP) },
                    )
                }

                item(key = "ai_test") {
                    val requester = rememberSettingsRowRequester(
                        target = advancedAiTestTarget,
                        externalRequester = remember("ai_test") { FocusRequester() },
                    )
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
                        onFocused = { onSettingsRowFocused(advancedAiTestTarget, requester) },
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
                    itemsIndexed(
                        addonState.addons,
                        key = { _, addon -> "addon_${addon.manifestUrl}" },
                    ) { index, addon ->
                        val installedTarget = TvSettingsFocusTarget(
                            itemId = "addon_installed_${addon.manifestUrl}",
                            category = TvSettingsCategory.ADVANCED,
                            listIndex = 90 + index,
                            focusTargetType = "action",
                        )
                        val requester = rememberRegisteredTvSettingsFocusRequester(controller = settingsFocusController, target = installedTarget)
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
                            onFocused = {
                                settingsFocusController.markFocused(installedTarget.itemId, requester)
                                onContentFocused(requester)
                            },
                            onFocusLost = {
                                if (confirmRemoveAddonUrl == addon.manifestUrl) {
                                    confirmRemoveAddonUrl = null
                                }
                            },
                            onClick = {
                                if (isConfirming) {
                                    pendingInstalledAddonUninstallRestore = PendingInstalledAddonUninstallRestore(
                                        removedUrl = addon.manifestUrl,
                                        removedIndex = index,
                                        installedUrlsBeforeRemove = addonState.addons.map { it.manifestUrl },
                                    )
                                    Log.d(
                                        SETTINGS_FOCUS_LOG_TAG,
                                        "addon_uninstall_requested " +
                                            "removedUrl=${addon.manifestUrl} " +
                                            "removedIndex=$index " +
                                            "installedBefore=${addonState.addons.map { it.manifestUrl }.joinToString()} " +
                                            "chipsPresent=false " +
                                            "entryItemBefore=${settingsFocusController.entryItemIdForCurrentState() ?: "none"}",
                                    )
                                    addonViewModel.removeAddon(addon.manifestUrl)
                                    confirmRemoveAddonUrl = null
                                } else {
                                    confirmRemoveAddonUrl = addon.manifestUrl
                                }
                            },
                            rowType = if (isConfirming) TvSettingRowType.DANGEROUS else TvSettingRowType.ACTION,
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
                val filteredSuggested = buildVisibleSuggestedAddons(installedUrls)

                itemsIndexed(
                    filteredSuggested,
                    key = { _, s -> "suggested_${s.url}" },
                ) { index, suggested ->
                    // Register with the focus state machine so disposal triggers
                    // proper fallback resolution instead of dumping focus to NavRail.
                    val addonTarget = TvSettingsFocusTarget(
                        itemId = "addon_suggested_${suggested.url}",
                        category = TvSettingsCategory.ADVANCED,
                        listIndex = 100 + index,
                        focusTargetType = "action",
                    )
                    val requester = rememberRegisteredTvSettingsFocusRequester(
                        controller = settingsFocusController,
                        target = addonTarget,
                    )
                    TvSettingCard(
                        title = stringResource(suggested.nameRes),
                        subtitle = stringResource(suggested.descriptionRes),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = {
                            if (confirmRemoveAddonUrl != null) {
                                confirmRemoveAddonUrl = null
                            }
                            settingsFocusController.markFocused(addonTarget.itemId, requester)
                            onContentFocused(requester)
                        },
                        onClick = {
                            pendingSuggestedAddonInstallRestore = PendingSuggestedAddonInstallRestore(
                                suggestedUrl = suggested.url,
                                nextSuggestedUrl = filteredSuggested.getOrNull(index + 1)?.url,
                                previousSuggestedUrl = filteredSuggested.getOrNull(index - 1)?.url,
                                suggestedUrlsBeforeInstall = filteredSuggested.map { it.url },
                                installedUrlsBeforeInstall = addonState.addons.map { it.manifestUrl },
                            )
                            Log.d(
                                SETTINGS_FOCUS_LOG_TAG,
                                "addon_suggested_install_requested " +
                                    "installedUrl=${suggested.url} " +
                                    "focusedItem=${addonTarget.itemId} " +
                                    "suggestedBefore=${filteredSuggested.map { it.url }.joinToString()} " +
                                    "installedBefore=${addonState.addons.map { it.manifestUrl }.joinToString()} " +
                                    "entryItemBefore=${settingsFocusController.entryItemIdForCurrentState() ?: "none"}",
                            )
                            addonViewModel.setInstallUrl(suggested.url)
                            addonViewModel.installAddon()
                        },
                    )
                }

                // Manual URL input
                item(key = "add_addon") {
                    val registeredAddAddonRequester = rememberRegisteredTvSettingsFocusRequester(
                        controller = settingsFocusController,
                        target = TvSettingsFocusTarget(
                            itemId = "addon_add_manual",
                            category = TvSettingsCategory.ADVANCED,
                            listIndex = 200,
                            focusTargetType = "navigation",
                        ),
                        externalRequester = addAddonCardRequester,
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_addon_install),
                        subtitle = if (addonState.isInstalling) stringResource(R.string.tv_settings_addon_installing)
                                   else stringResource(R.string.tv_settings_tap_to_edit),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = registeredAddAddonRequester,
                        onFocused = {
                            if (confirmRemoveAddonUrl != null) {
                                confirmRemoveAddonUrl = null
                            }
                            settingsFocusController.markFocused("addon_add_manual", registeredAddAddonRequester)
                            onContentFocused(registeredAddAddonRequester)
                        },
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
                            val installTarget = remember {
                                TvSettingsFocusTarget(
                                    itemId = "settings/advanced/addon_form/install",
                                    category = TvSettingsCategory.ADVANCED,
                                    listIndex = 201,
                                    focusTargetType = "action",
                                )
                            }
                            val registeredInstallRequester = rememberSettingsRowRequester(
                                target = installTarget,
                                externalRequester = installRequester,
                            )
                            TvSettingCard(
                                title = stringResource(R.string.tv_settings_addon_install),
                                subtitle = "",
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = registeredInstallRequester,
                                onFocused = { onSettingsRowFocused(installTarget, registeredInstallRequester) },
                                onClick = {
                                    addonViewModel.installAddon()
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
                        val target = remember {
                            // MDBList items render AFTER `addon_add_manual` (listIndex 200)
                            // and `addon_form/install` (201) in the LazyColumn. Their listIndex
                            // must therefore be > 201 so the focus controller's sorted
                            // visibleItemIds reflects visual order — otherwise the controller
                            // treats `addon_add_manual` as the last item and pressing Down
                            // jumps the user out instead of moving to mdblist items below.
                            TvSettingsFocusTarget(
                                itemId = "settings/advanced/mdblist/no_key",
                                category = TvSettingsCategory.ADVANCED,
                                listIndex = 250,
                                focusTargetType = "action",
                            )
                        }
                        val requester = rememberSettingsRowRequester(
                            target = target,
                            externalRequester = remember("mdblist_no_key") { FocusRequester() },
                        )
                        TvSettingCard(
                            title = stringResource(R.string.tv_settings_mdblist_browse),
                            subtitle = stringResource(R.string.tv_settings_mdblist_no_api_key),
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = requester,
                            onFocused = { onSettingsRowFocused(target, requester) },
                            onClick = {},
                            rowType = TvSettingRowType.ACTION,
                            emphasis = TvSettingEmphasis.SECONDARY,
                        )
                    }
                } else {
                    // Saved lists
                    val savedListCount = mdbListState.savedLists.size
                    val mdblistTabsBaseIndex = 251 + savedListCount
                    val mdblistSearchBaseIndex = mdblistTabsBaseIndex + 2
                    val mdblistResultsBaseIndex = mdblistSearchBaseIndex + 1

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
                        itemsIndexed(
                            mdbListState.savedLists,
                            key = { _, saved -> "mdblist_saved_${saved.listId}" },
                        ) { savedIndex, saved ->
                            val target = remember(saved.listId, savedIndex) {
                                TvSettingsFocusTarget(
                                    itemId = "settings/advanced/mdblist/saved/${saved.listId}",
                                    category = TvSettingsCategory.ADVANCED,
                                    listIndex = 251 + savedIndex,
                                    focusTargetType = "toggle",
                                )
                            }
                            val requester = rememberSettingsRowRequester(
                                target = target,
                                externalRequester = remember("mdblist_saved_${saved.listId}") { FocusRequester() },
                            )
                            TvSettingCard(
                                title = saved.name,
                                subtitle = if (saved.enabled) stringResource(R.string.tv_settings_addon_enabled)
                                           else stringResource(R.string.tv_settings_addon_disabled),
                                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                                focusRequester = requester,
                                onFocused = { onSettingsRowFocused(target, requester) },
                                onClick = {
                                    settingsFocusController.requestRestore(
                                        itemId = target.itemId,
                                        reason = "mdblist_saved_toggle",
                                        outerListState = settingsListState,
                                    )
                                    mdbListViewModel.toggleList(saved.listId, !saved.enabled)
                                },
                                rowType = TvSettingRowType.TOGGLE,
                            )
                        }
                    }

                    // Tab toggle: Popular / Search
                    item(key = "mdblist_tabs") {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            val popularTarget = remember(mdblistTabsBaseIndex) {
                                TvSettingsFocusTarget(
                                    itemId = "settings/advanced/mdblist/tab_popular",
                                    category = TvSettingsCategory.ADVANCED,
                                    listIndex = mdblistTabsBaseIndex,
                                    focusTargetType = "selector",
                                )
                            }
                            val searchTarget = remember(mdblistTabsBaseIndex) {
                                TvSettingsFocusTarget(
                                    itemId = "settings/advanced/mdblist/tab_search",
                                    category = TvSettingsCategory.ADVANCED,
                                    listIndex = mdblistTabsBaseIndex + 1,
                                    focusTargetType = "selector",
                                )
                            }
                            val popRequester = rememberSettingsRowRequester(
                                target = popularTarget,
                                externalRequester = remember("mdblist_tab_popular") { FocusRequester() },
                            )
                            val searchRequester = rememberSettingsRowRequester(
                                target = searchTarget,
                                externalRequester = remember("mdblist_tab_search") { FocusRequester() },
                            )
                            TvSettingCard(
                                title = stringResource(R.string.tv_settings_mdblist_popular),
                                subtitle = if (mdbListState.activeTab == MdbListTab.POPULAR) "●" else "",
                                modifier = Modifier.fillMaxWidth().focusProperties {
                                    left = railFocusRequester
                                },
                                focusRequester = popRequester,
                                onFocused = { onSettingsRowFocused(popularTarget, popRequester) },
                                onClick = {
                                    mdbListViewModel.setActiveTab(MdbListTab.POPULAR)
                                    if (mdbListState.topLists.isEmpty()) mdbListViewModel.loadTopLists()
                                },
                                rowType = TvSettingRowType.SELECTOR,
                            )
                            TvSettingCard(
                                title = stringResource(R.string.tv_settings_mdblist_search),
                                subtitle = if (mdbListState.activeTab == MdbListTab.SEARCH) "●" else "",
                                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                                focusRequester = searchRequester,
                                onFocused = { onSettingsRowFocused(searchTarget, searchRequester) },
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
                                val searchBtnTarget = remember(mdblistSearchBaseIndex) {
                                    TvSettingsFocusTarget(
                                        itemId = "settings/advanced/mdblist/search_submit",
                                        category = TvSettingsCategory.ADVANCED,
                                        listIndex = mdblistSearchBaseIndex,
                                        focusTargetType = "action",
                                    )
                                }
                                val registeredSearchBtnRequester = rememberSettingsRowRequester(
                                    target = searchBtnTarget,
                                    externalRequester = searchBtnRequester,
                                )
                                TvSettingCard(
                                    title = stringResource(R.string.tv_settings_mdblist_search),
                                    subtitle = if (mdbListState.isSearching) stringResource(R.string.tv_settings_validating) else "",
                                    modifier = Modifier.fillMaxWidth(),
                                    focusRequester = registeredSearchBtnRequester,
                                    onFocused = { onSettingsRowFocused(searchBtnTarget, registeredSearchBtnRequester) },
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

                    itemsIndexed(
                        displayLists,
                        key = { _, listInfo -> "mdblist_${mdbListState.activeTab.name}_${listInfo.id}" },
                    ) { listIndex, listInfo ->
                        val target = remember(mdbListState.activeTab, listInfo.id, listIndex, mdblistResultsBaseIndex) {
                            TvSettingsFocusTarget(
                                itemId = "settings/advanced/mdblist/${mdbListState.activeTab.name.lowercase()}/${listInfo.id}",
                                category = TvSettingsCategory.ADVANCED,
                                listIndex = mdblistResultsBaseIndex + listIndex,
                                focusTargetType = "action",
                            )
                        }
                        val requester = rememberSettingsRowRequester(
                            target = target,
                            externalRequester = remember("mdblist_${mdbListState.activeTab.name}_${listInfo.id}") { FocusRequester() },
                        )
                        val isAdded = mdbListState.savedLists.any { it.listId == listInfo.id }
                        TvSettingCard(
                            title = listInfo.name,
                            subtitle = "${listInfo.items} items · ${listInfo.userName}" +
                                if (isAdded) " · ${stringResource(R.string.tv_settings_mdblist_added)}" else "",
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = requester,
                            onFocused = { onSettingsRowFocused(target, requester) },
                            onClick = {
                                if (!isAdded) {
                                    settingsFocusController.requestRestore(
                                        itemId = target.itemId,
                                        reason = "mdblist_add",
                                        outerListState = settingsListState,
                                    )
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
                val requester = rememberSettingsRowRequester(
                    target = libraryTopTarget,
                    externalRequester = channelsTopRequester,
                    isDefaultEntry = true,
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_add_playlist),
                    subtitle = stringResource(R.string.tv_settings_no_playlists),
                    modifier = Modifier.fillMaxWidth().focusProperties {
                        left = railFocusRequester
                        up = categoryRequesters.getValue(TvSettingsCategory.LIBRARY)
                    },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(libraryTopTarget, requester) },
                    onClick = {
                        if (showAddPlaylist) {
                            onContentFocused(channelsTopRequester)
                        }
                        showAddPlaylist = !showAddPlaylist
                    },
                    rowType = TvSettingRowType.NAVIGATION,
                )
            }
        } else {
            itemsIndexed(
                channelsState.playlists,
                key = { _, playlist -> "playlist_${playlist.id}" },
            ) { index, playlist ->
                val target = remember(playlist.id, index) {
                    TvSettingsFocusTarget(
                        itemId = "settings/library/playlist/${playlist.id}",
                        category = TvSettingsCategory.LIBRARY,
                        listIndex = index,
                        focusTargetType = "action",
                    )
                }
                val requester = rememberSettingsRowRequester(
                    target = target,
                    externalRequester = if (index == 0) channelsTopRequester else remember("playlist_${playlist.id}") { FocusRequester() },
                    isDefaultEntry = index == 0,
                )
                val isConfirming = confirmRemoveId == playlist.id
                val loadedChannelCount = if (playlist.id == channelsState.selectedPlaylistId) {
                    maxOf(
                        channelsState.channels.size,
                        channelsState.categoryChannels.size,
                        channelsState.allCategories.sumOf { it.channelCount },
                    )
                } else {
                    0
                }
                val displayChannelCount = maxOf(playlist.channelCount, loadedChannelCount)
                TvSettingCard(
                    title = playlist.name,
                    subtitle = if (isConfirming) {
                        stringResource(R.string.tv_settings_playlist_confirm_remove)
                    } else if (displayChannelCount > 0) {
                        stringResource(R.string.tv_settings_channels_count, displayChannelCount)
                    } else {
                        stringResource(R.string.tv_settings_channels_count_pending)
                    },
                    modifier = Modifier.fillMaxWidth().focusProperties {
                        left = railFocusRequester
                        if (index == 0) {
                            up = categoryRequesters.getValue(TvSettingsCategory.LIBRARY)
                        }
                    },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(target, requester) },
                    onFocusLost = {
                        if (confirmRemoveId == playlist.id) {
                            confirmRemoveId = null
                        }
                    },
                    onClick = {
                        if (isConfirming) {
                            channelsViewModel.removePlaylist(playlist.id)
                            confirmRemoveId = null
                            onContentFocused(addPlaylistCardRequester)
                        } else {
                            confirmRemoveId = null
                            channelsViewModel.selectPlaylist(playlist.id)
                            selectedPlaylistEpgDraft = playlist.epgUrl.orEmpty()
                            showEditSelectedPlaylistEpg = true
                            onContentFocused(editPlaylistEpgCardRequester)
                        }
                    },
                    rowType = if (isConfirming) TvSettingRowType.DANGEROUS else TvSettingRowType.NAVIGATION,
                )
            }
        }

        selectedPlaylistForEpg?.let { playlist ->
            item(key = "playlist_epg_url") {
                val target = remember(playlist.id) {
                    TvSettingsFocusTarget(
                        itemId = "settings/library/playlist_epg/${playlist.id}",
                        category = TvSettingsCategory.LIBRARY,
                        listIndex = 20,
                        focusTargetType = "navigation",
                    )
                }
                val requester = rememberSettingsRowRequester(
                    target = target,
                    externalRequester = editPlaylistEpgCardRequester,
                )
                val subtitle = if (playlist.epgUrl.isNullOrBlank()) {
                    "${playlist.name} • ${stringResource(R.string.tv_settings_not_set)}"
                } else {
                    "${playlist.name} • ${stringResource(R.string.tv_settings_set)}"
                }
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_custom_epg_url),
                    subtitle = subtitle,
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(target, requester) },
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
        if (channelsState.playlists.isNotEmpty()) {
            item(key = "add_playlist") {
                val requester = rememberSettingsRowRequester(
                    target = libraryAddPlaylistTarget,
                    externalRequester = addPlaylistCardRequester,
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_add_playlist),
                    subtitle = stringResource(R.string.tv_settings_tap_to_edit),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(libraryAddPlaylistTarget, requester) },
                    onClick = {
                        if (showAddPlaylist) {
                            onContentFocused(addPlaylistCardRequester)
                        }
                        showAddPlaylist = !showAddPlaylist
                    },
                    rowType = TvSettingRowType.NAVIGATION,
                )
            }
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
                        label = { Text(stringResource(R.string.tv_settings_custom_epg_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val checkRequester = remember("selected_playlist_epg_check") { FocusRequester() }
                    val checkTarget = remember(selectedPlaylistForEpg.id) {
                        TvSettingsFocusTarget(
                            itemId = "settings/library/playlist_epg/${selectedPlaylistForEpg.id}/check",
                            category = TvSettingsCategory.LIBRARY,
                            listIndex = 22,
                            focusTargetType = "action",
                        )
                    }
                    val registeredCheckRequester = rememberSettingsRowRequester(
                        target = checkTarget,
                        externalRequester = checkRequester,
                    )
                    TvSettingCard(
                        title = if (channelsState.isCheckingEpg) {
                            stringResource(R.string.channels_check_epg_checking)
                        } else {
                            stringResource(R.string.channels_check_epg)
                        },
                        subtitle = "",
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = registeredCheckRequester,
                        onFocused = { onSettingsRowFocused(checkTarget, registeredCheckRequester) },
                        onClick = {
                            if (!channelsState.isCheckingEpg) {
                                channelsViewModel.checkEpgUrl(selectedPlaylistEpgDraft)
                            }
                        },
                    )
                    channelsState.epgCheckMessage?.takeIf { it.isNotBlank() }?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (channelsState.epgCheckSuccess == true) Amber else MaterialTheme.colorScheme.error,
                        )
                    }
                    val saveRequester = remember("selected_playlist_epg_save") { FocusRequester() }
                    val saveTarget = remember(selectedPlaylistForEpg.id) {
                        TvSettingsFocusTarget(
                            itemId = "settings/library/playlist_epg/${selectedPlaylistForEpg.id}/save",
                            category = TvSettingsCategory.LIBRARY,
                            listIndex = 21,
                            focusTargetType = "action",
                        )
                    }
                    val registeredSaveRequester = rememberSettingsRowRequester(
                        target = saveTarget,
                        externalRequester = saveRequester,
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_playlist_save),
                        subtitle = "",
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = registeredSaveRequester,
                        onFocused = { onSettingsRowFocused(saveTarget, registeredSaveRequester) },
                        onClick = {
                            settingsFocusController.requestRestore(
                                itemId = libraryManageChannelsTarget.itemId,
                                reason = "playlist_epg_saved",
                                outerListState = settingsListState,
                            )
                            channelsViewModel.updatePlaylistEpgUrl(
                                playlistId = selectedPlaylistForEpg.id,
                                epgUrl = selectedPlaylistEpgDraft,
                            )
                            showEditSelectedPlaylistEpg = false
                        },
                    )
                }
            }
        }

        // ── Channel Manager ──
        selectedPlaylistForActions?.let { playlist ->
            item(key = "remove_selected_playlist") {
                val isConfirming = confirmRemoveId == playlist.id
                val target = remember(playlist.id) {
                    TvSettingsFocusTarget(
                        itemId = "settings/library/remove_playlist/${playlist.id}",
                        category = TvSettingsCategory.LIBRARY,
                        listIndex = 23,
                        focusTargetType = "action",
                    )
                }
                val requester = rememberSettingsRowRequester(
                    target = target,
                    externalRequester = remember("remove_playlist_${playlist.id}") { FocusRequester() },
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_playlist_remove),
                    subtitle = if (isConfirming) {
                        stringResource(R.string.tv_settings_playlist_confirm_remove)
                    } else {
                        playlist.name
                    },
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(target, requester) },
                    onFocusLost = {
                        if (confirmRemoveId == playlist.id) {
                            confirmRemoveId = null
                        }
                    },
                    onClick = {
                        if (confirmRemoveId == playlist.id) {
                            channelsViewModel.removePlaylist(playlist.id)
                            confirmRemoveId = null
                            showEditSelectedPlaylistEpg = false
                            onContentFocused(addPlaylistCardRequester)
                        } else {
                            confirmRemoveId = playlist.id
                        }
                    },
                    rowType = if (isConfirming) TvSettingRowType.DANGEROUS else TvSettingRowType.ACTION,
                )
            }
        }

        if (channelsState.playlists.isNotEmpty()) {
            item(key = "refresh_epg_now") {
                val requester = rememberSettingsRowRequester(
                    target = libraryRefreshEpgTarget,
                    externalRequester = remember("refresh_epg_now") { FocusRequester() },
                )
                val selectedPlaylist = channelsState.playlists.firstOrNull { it.id == channelsState.selectedPlaylistId }
                    ?: channelsState.playlists.firstOrNull()
                val subtitle = when (val epgState = channelsState.epgState) {
                    EpgState.Loading -> stringResource(R.string.tv_settings_refresh_epg_loading)
                    EpgState.NotConfigured -> stringResource(R.string.tv_settings_refresh_epg_not_configured)
                    is EpgState.Error -> epgState.message
                    is EpgState.Loaded -> stringResource(
                        R.string.tv_settings_refresh_epg_subtitle_loaded,
                        epgState.sourceProgrammeCount,
                    )
                }
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_refresh_epg_title),
                    subtitle = subtitle,
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(libraryRefreshEpgTarget, requester) },
                    onClick = {
                        val canRefreshGuide = selectedPlaylist != null &&
                            (selectedPlaylist.type == PlaylistType.XTREAM || !selectedPlaylist.epgUrl.isNullOrBlank())
                        if (!canRefreshGuide) {
                            TvNotificationQueue.post(
                                context.getString(R.string.tv_settings_refresh_epg_not_configured),
                                NotificationType.ERROR,
                            )
                        } else {
                            channelsViewModel.ensureEpgLoaded(forceRefresh = true)
                            TvNotificationQueue.post(
                                context.getString(R.string.tv_settings_refresh_epg_started),
                                NotificationType.INFO,
                            )
                        }
                    },
                    rowType = TvSettingRowType.ACTION,
                )
            }
        }

        item(key = "section_library_visibility") {
            TvSectionHeader(text = stringResource(R.string.tv_settings_channel_visibility))
        }

        item(key = "manage_channels") {
            val requester = rememberSettingsRowRequester(
                target = libraryManageChannelsTarget,
                externalRequester = remember("manage_channels") { FocusRequester() },
            )
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
                onFocused = { onSettingsRowFocused(libraryManageChannelsTarget, requester) },
                onClick = { showChannelManager = !showChannelManager },
                rowType = TvSettingRowType.NAVIGATION,
            )
        }

        if (showChannelManager) {
            // Show All / Hide All buttons
            item(key = "channel_mgr_actions") {
                val showAllTarget = remember {
                    TvSettingsFocusTarget(
                        itemId = "settings/library/channel_manager/show_all",
                        category = TvSettingsCategory.LIBRARY,
                        listIndex = 41,
                        focusTargetType = "action",
                    )
                }
                val hideAllTarget = remember {
                    TvSettingsFocusTarget(
                        itemId = "settings/library/channel_manager/hide_all",
                        category = TvSettingsCategory.LIBRARY,
                        listIndex = 42,
                        focusTargetType = "dangerous",
                    )
                }
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
                                        runCatching { channelManagerHideAllRequester.requestFocus() }
                                        true
                                    }
                                    event.type == KeyEventType.KeyUp && event.key == Key.DirectionRight -> true
                                    else -> false
                                }
                            },
                        focusRequester = channelManagerShowAllRequester,
                        onFocused = { onSettingsRowFocused(showAllTarget, channelManagerShowAllRequester) },
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
                                        runCatching { channelManagerShowAllRequester.requestFocus() }
                                        true
                                    }
                                    event.type == KeyEventType.KeyUp && event.key == Key.DirectionLeft -> true
                                    else -> false
                                }
                            },
                        focusRequester = channelManagerHideAllRequester,
                        onFocused = { onSettingsRowFocused(hideAllTarget, channelManagerHideAllRequester) },
                        onFocusLost = { confirmHideAllChannelGroups = false },
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
                    val target = remember(country) {
                        TvSettingsFocusTarget(
                            itemId = "settings/library/country/$country",
                            category = TvSettingsCategory.LIBRARY,
                            listIndex = 50 + Math.abs(country.hashCode() % 1000),
                            focusTargetType = "navigation",
                        )
                    }
                    val req = rememberSettingsRowRequester(
                        target = target,
                        externalRequester = remember("country_$country") { FocusRequester() },
                    )
                    TvSettingCard(
                        title = "$country  ($visibleInCountry / ${cats.size})",
                        subtitle = if (isExpanded) "Press to collapse" else "${cats.size} groups — press to expand",
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = req,
                        onFocused = { onSettingsRowFocused(target, req) },
                        onClick = {
                            expandedCountry = if (isExpanded) null else country
                        },
                        rowType = TvSettingRowType.NAVIGATION,
                    )
                }

                // Toggle all for this country when expanded
                if (isExpanded) {
                    item(key = "country_toggle_$country") {
                        val target = remember(country) {
                            TvSettingsFocusTarget(
                                itemId = "settings/library/country_toggle/$country",
                                category = TvSettingsCategory.LIBRARY,
                                listIndex = 1050 + Math.abs(country.hashCode() % 1000),
                                focusTargetType = "toggle",
                            )
                        }
                        val req = rememberSettingsRowRequester(
                            target = target,
                            externalRequester = remember("country_toggle_$country") { FocusRequester() },
                        )
                        TvSettingCard(
                            title = if (allHiddenInCountry) "Show all $country" else "Hide all $country",
                            subtitle = "$visibleInCountry / ${cats.size} visible",
                            modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                            focusRequester = req,
                            onFocused = { onSettingsRowFocused(target, req) },
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
                        val target = remember(country, cat.name) {
                            TvSettingsFocusTarget(
                                itemId = "settings/library/category/$country/${cat.name}",
                                category = TvSettingsCategory.LIBRARY,
                                listIndex = 2050 + Math.abs("${country}_${cat.name}".hashCode() % 1000),
                                focusTargetType = "toggle",
                            )
                        }
                        val req = rememberSettingsRowRequester(
                            target = target,
                            externalRequester = remember("cat_${cat.name}") { FocusRequester() },
                        )
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
                            onFocused = { onSettingsRowFocused(target, req) },
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
                            label = { Text(stringResource(R.string.tv_settings_custom_epg_url)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val checkRequester = remember { FocusRequester() }
                        val checkTarget = remember {
                            TvSettingsFocusTarget(
                                itemId = "settings/library/playlist_form/check_epg",
                                category = TvSettingsCategory.LIBRARY,
                                listIndex = 2999,
                                focusTargetType = "action",
                            )
                        }
                        val registeredCheckRequester = rememberSettingsRowRequester(
                            target = checkTarget,
                            externalRequester = checkRequester,
                        )
                        TvSettingCard(
                            title = if (channelsState.isCheckingEpg) {
                                stringResource(R.string.channels_check_epg_checking)
                            } else {
                                stringResource(R.string.channels_check_epg)
                            },
                            subtitle = channelsState.epgCheckMessage.orEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = registeredCheckRequester,
                            onFocused = { onSettingsRowFocused(checkTarget, registeredCheckRequester) },
                            onClick = {
                                if (!channelsState.isCheckingEpg) {
                                    channelsViewModel.checkNewPlaylistEpgUrl()
                                }
                            },
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
                        TvClickToEditOutlinedTextField(
                            value = channelsState.newPlaylistEpgUrl,
                            onValueChange = { channelsViewModel.setNewPlaylistEpgUrl(it) },
                            label = { Text(stringResource(R.string.tv_settings_custom_epg_url)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val checkRequester = remember { FocusRequester() }
                        val checkTarget = remember {
                            TvSettingsFocusTarget(
                                itemId = "settings/library/playlist_form/xtream_check_epg",
                                category = TvSettingsCategory.LIBRARY,
                                listIndex = 2999,
                                focusTargetType = "action",
                            )
                        }
                        val registeredCheckRequester = rememberSettingsRowRequester(
                            target = checkTarget,
                            externalRequester = checkRequester,
                        )
                        TvSettingCard(
                            title = if (channelsState.isCheckingEpg) {
                                stringResource(R.string.channels_check_epg_checking)
                            } else {
                                stringResource(R.string.channels_check_epg)
                            },
                            subtitle = channelsState.epgCheckMessage.orEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = registeredCheckRequester,
                            onFocused = { onSettingsRowFocused(checkTarget, registeredCheckRequester) },
                            onClick = {
                                if (!channelsState.isCheckingEpg) {
                                    channelsViewModel.checkNewPlaylistEpgUrl()
                                }
                            },
                        )
                    }

                    val saveRequester = remember { FocusRequester() }
                    val saveTarget = remember {
                        TvSettingsFocusTarget(
                            itemId = "settings/library/playlist_form/save",
                            category = TvSettingsCategory.LIBRARY,
                            listIndex = 3000,
                            focusTargetType = "action",
                        )
                    }
                    val registeredSaveRequester = rememberSettingsRowRequester(
                        target = saveTarget,
                        externalRequester = saveRequester,
                    )
                    TvSettingCard(
                        title = if (channelsState.isAddingPlaylist) {
                            stringResource(R.string.tv_settings_validating)
                        } else {
                            stringResource(R.string.tv_settings_playlist_save)
                        },
                        subtitle = "",
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = registeredSaveRequester,
                        onFocused = { onSettingsRowFocused(saveTarget, registeredSaveRequester) },
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
                onFocused = { onSettingsRowFocused(maxQualityTarget, requester) },
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
                onFocused = { onSettingsRowFocused(minQualityTarget, requester) },
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
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = autoplayTarget,
                externalRequester = remember("autoplay") { FocusRequester() },
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_autoplay),
                subtitle = if (settingsState.autoPlayEnabled) stringResource(R.string.tv_settings_autoplay_on)
                           else stringResource(R.string.tv_settings_autoplay_off),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(autoplayTarget, requester) },
                onClick = { settingsViewModel.setAutoPlayEnabled(!settingsState.autoPlayEnabled) },
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        item(key = "autoplay_next") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = autoplayNextTarget,
                externalRequester = remember("autoplay_next") { FocusRequester() },
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_autoplay_next),
                subtitle = if (settingsState.autoPlayNextEpisodeEnabled) stringResource(R.string.tv_settings_autoplay_next_on)
                           else stringResource(R.string.tv_settings_autoplay_next_off),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(autoplayNextTarget, requester) },
                onClick = { settingsViewModel.setAutoPlayNextEpisodeEnabled(!settingsState.autoPlayNextEpisodeEnabled) },
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        // Deduplicate Streams toggle
        item(key = "section_playback_streams") {
            TvSectionHeader(text = stringResource(R.string.tv_settings_section_stream_handling))
        }
        item(key = "dedupe") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = dedupeTarget,
                externalRequester = remember("dedupe") { FocusRequester() },
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_dedupe),
                subtitle = if (settingsState.dedupeResults) stringResource(R.string.tv_settings_dedupe_on)
                           else stringResource(R.string.tv_settings_dedupe_off),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(dedupeTarget, requester) },
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
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = playbackAudioModeTarget,
                externalRequester = remember("playback_audio_mode") { FocusRequester() },
            )
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
                onFocused = { onSettingsRowFocused(playbackAudioModeTarget, requester) },
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
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = playbackAudioPassthroughTarget,
                externalRequester = remember("playback_audio_passthrough") { FocusRequester() },
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_live_audio_passthrough),
                subtitle = if (channelsState.audioPassthroughEnabled) {
                    stringResource(R.string.tv_settings_enabled)
                } else {
                    stringResource(R.string.tv_settings_disabled)
                },
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(playbackAudioPassthroughTarget, requester) },
                onClick = {
                    channelsViewModel.setAudioPassthroughEnabled(!channelsState.audioPassthroughEnabled)
                },
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        item(key = "playback_audio_surround") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = playbackAudioSurroundTarget,
                externalRequester = remember("playback_audio_surround") { FocusRequester() },
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_live_audio_surround),
                subtitle = if (channelsState.preferSurroundCodecs) {
                    stringResource(R.string.tv_settings_enabled)
                } else {
                    stringResource(R.string.tv_settings_disabled)
                },
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(playbackAudioSurroundTarget, requester) },
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
                    down = languageCardRequester
                },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(reduceMotionTarget, requester) },
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
                modifier = Modifier.fillMaxWidth().focusProperties {
                    left = railFocusRequester
                    up = reduceMotionCardRequester
                    down = homeLayoutCardRequester
                },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(languageTarget, requester) },
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

        // Region selector removed — not used by the app.

        // ── Content Management section ──
        item(key = "section_content") {
            TvSectionHeader(
                text = stringResource(R.string.tv_settings_section_home_experience),
                description = stringResource(R.string.tv_settings_section_home_experience_desc),
            )
        }

        // Home Layout navigation card
        item(key = "home_layout") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = homeLayoutTarget,
                externalRequester = homeLayoutCardRequester,
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_home_layout),
                subtitle = stringResource(R.string.tv_settings_home_layout_subtitle),
                modifier = Modifier.fillMaxWidth().focusProperties {
                    left = railFocusRequester
                    up = languageCardRequester
                    down = ratingsCardRequester
                },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(homeLayoutTarget, requester) },
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
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = ratingsTarget,
                    externalRequester = ratingsCardRequester,
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_ratings),
                    subtitle = stringResource(R.string.tv_settings_ratings_subtitle),
                    modifier = Modifier.fillMaxWidth().focusProperties {
                        left = railFocusRequester
                        up = homeLayoutCardRequester
                        down = posterTitlesCardRequester
                    },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(ratingsTarget, requester) },
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
            val requester = rememberSettingsRowRequester(
                target = posterTitlesTarget,
                externalRequester = posterTitlesCardRequester,
            )
            var showPosterTitles by remember {
                mutableStateOf(prefs.getBoolean("tv_show_poster_titles", true))
            }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_poster_titles),
                subtitle = if (showPosterTitles) stringResource(R.string.tv_settings_poster_titles_on)
                           else stringResource(R.string.tv_settings_poster_titles_off),
                modifier = Modifier.fillMaxWidth().focusProperties {
                    left = railFocusRequester
                    up = ratingsCardRequester
                    down = seeAllPosterColumnsCardRequester
                },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(posterTitlesTarget, requester) },
                onClick = {
                    showPosterTitles = !showPosterTitles
                    prefs.edit().putBoolean("tv_show_poster_titles", showPosterTitles).apply()
                },
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        item(key = "see_all_poster_columns") {
            val requester = rememberSettingsRowRequester(
                target = seeAllPosterColumnsTarget,
                externalRequester = seeAllPosterColumnsCardRequester,
            )
            var posterColumns by remember {
                mutableIntStateOf(tvSeeAllPosterColumns(context))
            }
            TvSettingCard(
                title = stringResource(R.string.tv_settings_see_all_poster_columns),
                subtitle = stringResource(R.string.tv_settings_see_all_poster_columns_value, posterColumns),
                modifier = Modifier.fillMaxWidth().focusProperties {
                    left = railFocusRequester
                    up = posterTitlesCardRequester
                },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(seeAllPosterColumnsTarget, requester) },
                onClick = {
                    val currentIndex = TV_SEE_ALL_POSTER_COLUMN_OPTIONS.indexOf(posterColumns).coerceAtLeast(0)
                    posterColumns = TV_SEE_ALL_POSTER_COLUMN_OPTIONS[
                        (currentIndex + 1) % TV_SEE_ALL_POSTER_COLUMN_OPTIONS.size
                    ]
                    setTvSeeAllPosterColumns(context, posterColumns)
                },
                rowType = TvSettingRowType.SELECTOR,
            )
        }

        // About section
        }

        if (allowDebugPanel && selectedCategory == TvSettingsCategory.ADVANCED && !advancedLocked) {
            item(key = "section_advanced_diagnostics") {
                TvSectionHeader(
                    text = stringResource(R.string.tv_settings_diagnostics_dev),
                    description = stringResource(R.string.tv_settings_diagnostics_dev_desc),
                )
            }
            item(key = "advanced_diagnostics") {
                val requester = rememberSettingsRowRequester(
                    target = advancedDiagnosticsTarget,
                    externalRequester = remember("advanced_diagnostics") { FocusRequester() },
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_diagnostics),
                    subtitle = when {
                        confirmEnableDiagnostics -> "Press again to enable diagnostics panel"
                        showDebugPanel -> "Visible"
                        else -> "Hidden"
                    },
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(advancedDiagnosticsTarget, requester) },
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
            val requester = rememberSettingsRowRequester(
                target = aboutVersionTarget,
                externalRequester = aboutVersionCardRequester,
                isDefaultEntry = true,
            )
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
                onFocused = { onSettingsRowFocused(aboutVersionTarget, requester) },
                onClick = {
                    if (allowDebugPanel) {
                        aboutTapCount++
                        if (aboutTapCount >= 5) {
                            showDebugPanel = !showDebugPanel
                            aboutTapCount = 0
                        }
                    }
                },
                rowType = TvSettingRowType.ACTION,
            )
        }

        item(key = "about_build") {
            val requester = rememberSettingsRowRequester(
                target = aboutBuildTarget,
                externalRequester = remember("about_build") { FocusRequester() },
            )
            val buildNumber = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            }.getOrDefault(0L)
            TvSettingCard(
                title = stringResource(R.string.tv_settings_build_number),
                subtitle = buildNumber.toString(),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(aboutBuildTarget, requester) },
                onClick = {},
                emphasis = TvSettingEmphasis.SECONDARY,
            )
        }

        item(key = "section_about_support") {
            TvSectionHeader(text = stringResource(R.string.tv_settings_legal_support))
        }

        item(key = "about_support") {
            val baseRequester = remember("about_support") { FocusRequester() }
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = aboutSupportTarget,
                externalRequester = baseRequester,
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_support),
                subtitle = stringResource(R.string.tv_settings_support_desc),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = {
                    settingsFocusController.markFocused(aboutSupportTarget.itemId, requester)
                    onContentFocused(requester)
                },
                onClick = {
                    aboutOverlayState = TvAboutOverlayState.Support(originItemId = aboutSupportTarget.itemId)
                },
                rowType = TvSettingRowType.NAVIGATION,
            )
        }

        item(key = "about_report_issue") {
            val baseRequester = remember("about_report_issue") { FocusRequester() }
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = aboutReportIssueTarget,
                externalRequester = baseRequester,
            )
            TvSettingCard(
                title = stringResource(R.string.bug_report_title),
                subtitle = stringResource(R.string.bug_report_tv_settings_subtitle),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = {
                    settingsFocusController.markFocused(aboutReportIssueTarget.itemId, requester)
                    onContentFocused(requester)
                },
                onClick = onNavigateToReportIssue,
                rowType = TvSettingRowType.NAVIGATION,
            )
        }

        item(key = "about_terms") {
            val baseRequester = remember("about_terms") { FocusRequester() }
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = aboutTermsTarget,
                externalRequester = baseRequester,
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_terms),
                subtitle = stringResource(R.string.tv_settings_terms_desc),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = {
                    settingsFocusController.markFocused(aboutTermsTarget.itemId, requester)
                    onContentFocused(requester)
                },
                onClick = {
                    aboutOverlayState = TvAboutOverlayState.Terms(originItemId = aboutTermsTarget.itemId)
                },
                rowType = TvSettingRowType.NAVIGATION,
            )
        }

        item(key = "about_legal") {
            val baseRequester = remember("about_legal") { FocusRequester() }
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = aboutLegalTarget,
                externalRequester = baseRequester,
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_legal),
                subtitle = stringResource(R.string.tv_settings_legal_subtitle),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = {
                    settingsFocusController.markFocused(aboutLegalTarget.itemId, requester)
                    onContentFocused(requester)
                },
                onClick = {
                    aboutOverlayState = TvAboutOverlayState.Legal(originItemId = aboutLegalTarget.itemId)
                },
                rowType = TvSettingRowType.NAVIGATION,
            )
        }

        item(key = "about_stats") {
            val requester = rememberSettingsRowRequester(
                target = aboutStatsTarget,
                externalRequester = remember("about_stats") { FocusRequester() },
            )
            val statsState by watchStatsViewModel.state.collectAsState()
            LaunchedEffect(Unit) { watchStatsViewModel.load() }
            val statsCopy = WatchStatsUiText.aboutCard(statsState.summary)
            TvSettingCard(
                title = statsCopy.title,
                subtitle = statsCopy.body,
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(aboutStatsTarget, requester) },
                onClick = {
                    logSettingsFocus("launch_subpage category=ABOUT row=ABOUT item=watch_stats reason=route_open")
                    settingsFocusController.captureOrigin(
                        itemId = aboutStatsTarget.itemId,
                        outerListState = settingsListState,
                        reason = "route_open",
                    )
                    onNavigateToWatchStats()
                },
                rowType = TvSettingRowType.NAVIGATION,
            )
        }

        item(key = "section_about_panda_health") {
            TvSectionHeader(
                text = stringResource(R.string.tv_settings_panda_health_monitor),
                description = stringResource(R.string.tv_settings_panda_health_monitor_desc),
            )
        }
        if (tvHealthRows.isEmpty()) {
            item(key = "about_panda_health_clear") {
                val target = remember {
                    TvSettingsFocusTarget(
                        itemId = "settings/about/panda_health/clear",
                        category = TvSettingsCategory.ABOUT,
                        listIndex = 50,
                        focusTargetType = "status",
                    )
                }
                val requester = rememberSettingsRowRequester(
                    target = target,
                    externalRequester = aboutPandaHealthCardRequester,
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_streaming_health),
                    subtitle = stringResource(R.string.tv_settings_streaming_health_clear),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(target, requester) },
                    onClick = { refreshImportedSetup() },
                    rowType = TvSettingRowType.ACTION,
                    emphasis = TvSettingEmphasis.SECONDARY,
                )
            }
        } else {
            tvHealthRows.forEachIndexed { index, (entry, actions) ->
                item(key = "about_health:${entry.providerKey}") {
                    val target = remember(entry.providerKey, index) {
                        TvSettingsFocusTarget(
                            itemId = "settings/about/panda_health/${entry.providerKey}",
                            category = TvSettingsCategory.ABOUT,
                            listIndex = 50 + index,
                            focusTargetType = "action",
                        )
                    }
                    val requester = rememberSettingsRowRequester(
                        target = target,
                        externalRequester = if (index == 0) {
                            aboutPandaHealthCardRequester
                        } else {
                            remember("about_health:${entry.providerKey}") { FocusRequester() }
                        },
                    )
                    val primary = actions.firstOrNull {
                        it == com.torve.presentation.providerhealth.ProviderRepairAction.TransferFromAnotherDevice ||
                            it == com.torve.presentation.providerhealth.ProviderRepairAction.OpenDiagnostics
                    }
                    val statusView = ProviderStatusMapper.map(entry)
                    val pandaOwned = entry.category.isPandaOwnedProviderCategory()
                    val (subtitleSuffix, onTap) = when (primary) {
                        null -> statusView.primaryActionLabel.orEmpty() to {
                            runProviderHealthCheckWithFeedback(entry)
                        }
                        else -> if (pandaOwned) {
                            stringResource(R.string.tv_settings_open_panda_setup) to { onNavigateToPandaSetup() }
                        } else {
                            when (primary) {
                                com.torve.presentation.providerhealth.ProviderRepairAction.TransferFromAnotherDevice ->
                                    stringResource(R.string.tv_settings_open_credential_transfer) to { onNavigateToReceiveCredentials() }
                                com.torve.presentation.providerhealth.ProviderRepairAction.OpenDiagnostics ->
                                    stringResource(R.string.tv_settings_open_diagnostics) to { onNavigateToTransferDiagnostics() }
                                else -> "" to {
                                    runProviderHealthCheckWithFeedback(entry)
                                }
                            }
                        }
                    }
                    val detail = statusView.detail?.takeIf { it.isNotBlank() }
                    val combined = listOfNotNull(
                        statusView.headline.takeIf { it.isNotBlank() },
                        detail,
                        subtitleSuffix.takeIf { it.isNotBlank() },
                    ).joinToString(" - ")
                    TvSettingCard(
                        title = entry.label,
                        subtitle = combined,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(target, requester) },
                        onClick = onTap,
                        rowType = TvSettingRowType.ACTION,
                    )
                }
            }
        }

        }

        // Easter egg: debug panel
        if (allowDebugPanel && showDebugPanel && selectedCategory == TvSettingsCategory.ADVANCED && !advancedLocked) {
            item(key = "section_debug") {
                TvSectionHeader(text = stringResource(R.string.tv_settings_debug))
            }

            item(key = "debug_sync") {
                val debugSyncTarget = remember {
                    TvSettingsFocusTarget(
                        itemId = "settings/advanced/debug_sync",
                        category = TvSettingsCategory.ADVANCED,
                        listIndex = 600,
                        focusTargetType = "action",
                    )
                }
                Box(Modifier.onFocusChanged { isLastItemFocused = it.hasFocus }) {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_sync_status),
                        subtitle = stringResource(R.string.tv_settings_transport_status, syncState.wsStatus),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = lastItemRequester,
                        onFocused = { onSettingsRowFocused(debugSyncTarget, lastItemRequester) },
                        onClick = {},
                        emphasis = TvSettingEmphasis.SECONDARY,
                    )
                }
            }

            item(key = "debug_device_limit") {
                val debugDeviceLimitTarget = remember {
                    TvSettingsFocusTarget(
                        itemId = "settings/advanced/debug_device_limit",
                        category = TvSettingsCategory.ADVANCED,
                        listIndex = 601,
                        focusTargetType = "action",
                    )
                }
                val deviceLimitRequester = remember { FocusRequester() }
                TvSettingCard(
                    title = "Device limit",
                    subtitle = "device_limit=${deviceGovernanceState.deviceLimitDebugText} device_cap_override=${deviceGovernanceState.deviceCapOverrideDebugText}",
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = deviceLimitRequester,
                    onFocused = { onSettingsRowFocused(debugDeviceLimitTarget, deviceLimitRequester) },
                    onClick = { deviceGovernanceViewModel.fetchAccessState() },
                    emphasis = TvSettingEmphasis.SECONDARY,
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

    when (val overlay = aboutOverlayState) {
        is TvAboutOverlayState.Support -> {
            val emailRequester = remember(overlay.originItemId) { FocusRequester() }
            TvDocumentModal(
                title = stringResource(R.string.tv_settings_support),
                initialFocusRequester = emailRequester,
                onDismiss = ::dismissAboutOverlay,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tv_settings_support_modal_body),
                        style = MaterialTheme.typography.titleLarge,
                        color = Snow,
                    )
                    Text(
                        text = stringResource(R.string.tv_settings_support_modal_subtext),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Silver,
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_support_email),
                        subtitle = stringResource(R.string.tv_settings_support_email_action),
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = emailRequester,
                        onFocused = { },
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_SENDTO,
                                android.net.Uri.parse("mailto:${context.getString(R.string.tv_settings_support_email)}"),
                            ).apply {
                                putExtra(
                                    android.content.Intent.EXTRA_EMAIL,
                                    arrayOf(context.getString(R.string.tv_settings_support_email)),
                                )
                            }
                            val launched = runCatching {
                                context.startActivity(intent)
                                true
                            }.getOrDefault(false)
                            if (!launched) {
                                TvNotificationQueue.post(
                                    context.getString(R.string.tv_settings_support_email_unavailable),
                                    NotificationType.INFO,
                                )
                            }
                        },
                        rowType = TvSettingRowType.NAVIGATION,
                        focusedHint = stringResource(R.string.tv_settings_support_email_action),
                    )
                }
            }
        }
        is TvAboutOverlayState.Terms,
        is TvAboutOverlayState.Legal,
        -> {
            val documentRequester = remember(overlay.originItemId) { FocusRequester() }
            val title = when (overlay) {
                is TvAboutOverlayState.Terms -> stringResource(R.string.tv_settings_terms)
                is TvAboutOverlayState.Legal -> stringResource(R.string.tv_settings_legal)
            }
            val url = when (overlay) {
                is TvAboutOverlayState.Terms -> "https://torve.app/terms.html"
                is TvAboutOverlayState.Legal -> "https://torve.app/privacy.html"
            }
            val fallbackAsset = when (overlay) {
                is TvAboutOverlayState.Terms -> "terms.html"
                is TvAboutOverlayState.Legal -> "privacy.html"
            }
            val documentState = rememberTvDocumentContentState(
                url = url,
                fallbackAssetName = fallbackAsset,
            )
            TvDocumentModal(
                title = title,
                initialFocusRequester = documentRequester,
                onDismiss = ::dismissAboutOverlay,
            ) {
                when (documentState) {
                    TvDocumentContentState.Loading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(color = Amber)
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.tv_settings_document_loading),
                                style = MaterialTheme.typography.titleMedium,
                                color = Snow,
                            )
                        }
                    }
                    is TvDocumentContentState.Ready -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (documentState.source != com.torve.android.tv.components.TvDocumentSource.REMOTE) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Amber.copy(alpha = 0.12f))
                                        .border(1.dp, Amber.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.tv_settings_document_fallback_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Amber,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = stringResource(R.string.tv_settings_document_fallback_body),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Silver,
                                    )
                                    documentState.warningMessage?.takeIf { it.isNotBlank() }?.let { warning ->
                                        Text(
                                            text = warning,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Silver.copy(alpha = 0.8f),
                                        )
                                    }
                                }
                            }
                            TvHtmlDocumentPane(
                                html = documentState.html,
                                baseUrl = when (documentState.source) {
                                    com.torve.android.tv.components.TvDocumentSource.REMOTE -> url
                                    com.torve.android.tv.components.TvDocumentSource.ASSET_FALLBACK -> "file:///android_asset/$fallbackAsset"
                                },
                                focusRequester = documentRequester,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    is TvDocumentContentState.Error -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.tv_settings_document_error_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = Snow,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.tv_settings_document_error_body),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Silver,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = documentState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Silver.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
        }
        null -> Unit
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
                context.findActivity()?.recreate()
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
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
    settingsFocusController: TvSettingsFocusStateMachine? = null,
    focusTarget: TvSettingsFocusTarget? = null,
    isDefaultEntry: Boolean = false,
    upFocusRequester: FocusRequester? = null,
    isPassword: Boolean = false,
    premiumFeature: TvEntitledFeature? = null,
    premiumLocked: Boolean = false,
    onLockedClick: (() -> Unit)? = null,
) {
    val isExpanded = expandedInput == key
    val localRequester = remember(key) { FocusRequester() }
    val baseRequester = focusRequester ?: localRequester
    val requester = if (settingsFocusController != null && focusTarget != null) {
        rememberRegisteredTvSettingsFocusRequester(
            controller = settingsFocusController,
            target = focusTarget,
            externalRequester = baseRequester,
            isDefaultEntry = isDefaultEntry,
        )
    } else {
        baseRequester
    }
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
            onFocused = {
                if (settingsFocusController != null && focusTarget != null) {
                    settingsFocusController.markFocused(focusTarget.itemId, requester)
                }
                onContentFocused(requester)
            },
            onClick = {
                if (locked) {
                    onLockedClick?.invoke()
                } else {
                    onExpandToggle(key)
                }
            },
            rowType = TvSettingRowType.NAVIGATION,
            focusedHint = if (locked) {
                "Press OK to continue."
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

private fun ProviderHealthCategory.isPandaOwnedProviderCategory(): Boolean = when (this) {
    ProviderHealthCategory.DEBRID,
    ProviderHealthCategory.USENET_INDEXER,
    ProviderHealthCategory.USENET_PROVIDER,
    ProviderHealthCategory.DOWNLOAD_CLIENT -> true
    ProviderHealthCategory.IPTV,
    ProviderHealthCategory.PLEX_JELLYFIN,
    ProviderHealthCategory.ADDON,
    ProviderHealthCategory.EPG,
    ProviderHealthCategory.TRAKT,
    ProviderHealthCategory.SIMKL,
    ProviderHealthCategory.PLAYBACK -> false
}

private fun buildPandaProviderSummary(
    entries: List<ProviderHealthEntry>,
    pandaState: PandaSetupUiState,
): String? {
    val parts = mutableListOf<String>()
    connectedDebridLabels(entries, pandaState).takeIf { it.isNotEmpty() }?.let {
        parts += "Debrid: ${it.joinToString(", ")}"
    }
    pandaUsenetProviderLabel(pandaState)?.let {
        parts += "Usenet: $it"
    }
    pandaIndexerLabels(pandaState).takeIf { it.isNotEmpty() }?.let {
        parts += "Indexer: ${it.joinToString(", ")}"
    }
    pandaDownloadClientLabel(pandaState)?.let {
        parts += "Download: $it"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" | ")
}

private fun buildDebridProviderSummary(
    entries: List<ProviderHealthEntry>,
    pandaState: PandaSetupUiState,
): String? {
    val connected = connectedDebridLabels(entries, pandaState)
    if (connected.isNotEmpty()) {
        return "Connected: ${connected.joinToString(", ")}"
    }
    val anyDebridRows = entries.any { it.category == ProviderHealthCategory.DEBRID }
    return if (anyDebridRows) {
        "No debrid services connected. Open Panda to add Real-Debrid, TorBox, AllDebrid, or Premiumize."
    } else {
        null
    }
}

private fun connectedDebridLabels(
    entries: List<ProviderHealthEntry>,
    pandaState: PandaSetupUiState,
): List<String> {
    val ids = linkedSetOf<String>()
    pandaState.debridApiKeys.forEach { (provider, value) ->
        if (isUsableCredentialForDisplay(value)) ids += provider
    }
    pandaState.serverHasSecrets.forEach { key ->
        if (key.startsWith("debrid_api_key_")) {
            ids += key.removePrefix("debrid_api_key_")
        }
    }
    val selectedId = pandaState.selectedProvider?.id
    if (!selectedId.isNullOrBlank() && selectedId != "none" &&
        (isUsableCredentialForDisplay(pandaState.debridApiKey) || pandaState.authConnected)
    ) {
        ids += selectedId
    }
    if (pandaState.downloadClient == "torbox" &&
        (isUsableCredentialForDisplay(pandaState.downloadClientApiKey) ||
            "download_client_api_key" in pandaState.serverHasSecrets)
    ) {
        ids += "torbox"
    }
    entries
        .filter { it.category == ProviderHealthCategory.DEBRID && it.status == ProviderHealthStatus.GREEN }
        .forEach { ids += it.providerKey.substringAfter("debrid:", it.providerKey) }
    return ids
        .filter { it.isNotBlank() && it != "none" }
        .map { pandaDebridLabel(it) }
        .distinct()
}

private fun pandaUsenetProviderLabel(state: PandaSetupUiState): String? {
    val provider = state.usenetProvider.takeIf { state.enableUsenet && it.isNotBlank() && it != "none" }
        ?: return null
    val hasCredential = isUsableCredentialForDisplay(state.usenetPassword) ||
        "usenet_password" in state.serverHasSecrets
    return if (hasCredential) prettifyProviderId(provider) else null
}

private fun pandaIndexerLabels(state: PandaSetupUiState): List<String> =
    state.nzbIndexers
        .mapIndexedNotNull { index, row ->
            val type = row.type.takeIf { it.isNotBlank() && it != "none" } ?: return@mapIndexedNotNull null
            val hasCredential = isUsableCredentialForDisplay(row.apiKey) ||
                "indexer_api_key_$index" in state.serverHasSecrets
            if (hasCredential) prettifyProviderId(type) else null
        }
        .distinct()

private fun pandaDownloadClientLabel(state: PandaSetupUiState): String? {
    val client = state.downloadClient.takeIf { it.isNotBlank() && it != "none" } ?: return null
    val hasCredential = isUsableCredentialForDisplay(state.downloadClientApiKey) ||
        isUsableCredentialForDisplay(state.downloadClientPassword) ||
        "download_client_api_key" in state.serverHasSecrets ||
        "download_client_password" in state.serverHasSecrets
    return if (hasCredential) prettifyProviderId(client) else null
}

private fun isUsableCredentialForDisplay(value: String?): Boolean {
    val trimmed = value?.trim().orEmpty()
    return trimmed.isNotBlank() && !trimmed.contains("redact", ignoreCase = true)
}

private fun pandaDebridLabel(id: String): String = when (id.lowercase()) {
    "realdebrid", "real_debrid", "real-debrid" -> "Real-Debrid"
    "alldebrid", "all_debrid", "all-debrid" -> "AllDebrid"
    "premiumize" -> "Premiumize"
    "torbox" -> "TorBox"
    else -> prettifyProviderId(id)
}

private fun prettifyProviderId(id: String): String =
    id.replace("-", " ")
        .replace("_", " ")
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

private fun tvReadableAuthUrl(url: String): String =
    url.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')

@Composable
private fun TvDeviceAuthorizationPanel(
    title: String,
    verificationUrl: String,
    userCode: String,
    status: String,
    isError: Boolean,
    isPolling: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Charcoal.copy(alpha = 0.62f), RoundedCornerShape(16.dp))
            .border(1.dp, Amber.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (isPolling) {
                CircularProgressIndicator(
                    modifier = Modifier.width(22.dp).height(22.dp),
                    strokeWidth = 2.dp,
                    color = Amber,
                )
            }
        }
        Text(
            text = "1. On your phone or computer, open:",
            style = MaterialTheme.typography.bodyLarge,
            color = Silver,
        )
        Text(
            text = tvReadableAuthUrl(verificationUrl),
            style = MaterialTheme.typography.headlineSmall,
            color = AmberLight,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
        Text(
            text = "2. Enter this code:",
            style = MaterialTheme.typography.bodyLarge,
            color = Silver,
        )
        Text(
            text = userCode,
            style = MaterialTheme.typography.headlineLarge,
            color = Snow,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) Ruby else Silver,
        )
        if (!isError) {
            Text(
                text = "Torve will continue automatically after authorization.",
                style = MaterialTheme.typography.bodySmall,
                color = Ash,
            )
        }
    }
}

@Composable
internal fun TvSettingCard(
    title: String,
    subtitle: String,
    modifier: Modifier,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onFocusLost: (() -> Unit)? = null,
    onClick: () -> Unit,
    rowType: TvSettingRowType = TvSettingRowType.ACTION,
    emphasis: TvSettingEmphasis = TvSettingEmphasis.PRIMARY,
    focusedHint: String? = null,
    premiumLocked: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    var keyDownReceivedHere by remember { mutableStateOf(false) }
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
        "Press OK to continue."
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
                val wasFocused = focused
                focused = it.isFocused
                if (it.isFocused && !wasFocused) {
                    keyDownReceivedHere = false
                }
                if (it.isFocused) onFocused()
                else if (wasFocused) onFocusLost?.invoke()
            }
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        keyDownReceivedHere = true
                        true
                    }
                    event.type == KeyEventType.KeyUp &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        if (keyDownReceivedHere) {
                            keyDownReceivedHere = false
                            onClick()
                        }
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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


