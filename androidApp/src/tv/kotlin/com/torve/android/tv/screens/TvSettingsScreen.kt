package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import com.torve.android.tv.components.tvLiquidGlass
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.pm.PackageInfoCompat
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.torve.android.ui.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.first
// LocaleListCompat removed — no longer applying locale inline
import com.torve.android.R
import com.torve.android.tv.TV_PAGE_BOTTOM_GUTTER
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.tv.TV_PAGE_END_GUTTER
import com.torve.android.tv.TV_PAGE_TOP_GUTTER
import com.torve.android.catalog.CatalogWarmupWorker
import com.torve.android.epg.EpgWarmupWorker
import com.torve.android.update.AppUpdateChecker
import com.torve.android.update.AppUpdateActivity
import com.torve.android.update.AvailableAppUpdate
import com.torve.android.update.usesVpsReleaseUpdates
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
import com.torve.android.tv.components.TvConnectionCenterDialog
import com.torve.android.tv.components.TvConnectionCenterAction
import com.torve.android.tv.focus.rememberTvSettingsFocusStateMachine
import com.torve.android.tv.focus.focusTvSettingsRow
import com.torve.android.tv.focus.tvSettingsRowNavigation
import com.torve.android.tv.components.TvDocumentContentState
import com.torve.android.tv.components.TvHtmlDocumentPane
import com.torve.android.tv.components.rememberTvDocumentContentState
import com.torve.android.tv.focus.TvSettingsFocusStateMachine
import com.torve.android.tv.focus.TvSettingsFocusTarget
import com.torve.android.tv.focus.TvSettingsItemIds
import com.torve.android.tv.focus.adjacentTvSettingsCategory
import com.torve.android.tv.focus.hasAdjacentTvSettingsControl
import com.torve.android.tv.focus.rememberRegisteredTvSettingsFocusRequester
import com.torve.android.tv.premium.TvEntitledFeature
import com.torve.android.tv.premium.TvPremiumAccess
import com.torve.android.ui.settings.POPULAR_ADDONS
import com.torve.data.ai.AiProvider
import com.torve.domain.model.StreamQuality
import com.torve.domain.model.NextEpisodeMode
import com.torve.domain.model.NextEpisodePreparationMode
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.SegmentActionMode
import com.torve.presentation.channels.EpgState
import com.torve.presentation.channels.userMessage
import com.torve.presentation.addon.AddonViewModel
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.mdblist.MdbListTab
import com.torve.presentation.mdblist.MdbListViewModel
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
import kotlinx.coroutines.CancellationException
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
    PROVIDER_ITEM,
}

private data class TvSettingsFocusMoveRequest(
    val nonce: Int,
    val target: TvSettingsFocusMoveTarget,
    val category: TvSettingsCategory? = null,
    val itemId: String? = null,
    val lazyItemKey: String? = null,
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

private sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data class UpdateAvailable(val update: AvailableAppUpdate) : UpdateCheckState
    data object Error : UpdateCheckState
}

private fun SegmentActionMode.next(): SegmentActionMode = when (this) {
    SegmentActionMode.OFF -> SegmentActionMode.SHOW_BUTTON
    SegmentActionMode.SHOW_BUTTON -> SegmentActionMode.AUTOMATIC
    SegmentActionMode.AUTOMATIC -> SegmentActionMode.OFF
}

private fun SegmentActionMode.tvLabel(): String = when (this) {
    SegmentActionMode.OFF -> "Off"
    SegmentActionMode.SHOW_BUTTON -> "Show Button"
    SegmentActionMode.AUTOMATIC -> "Automatic"
}

private sealed interface TvSettingsOverlayState {
    val originItemId: String

    data class PlaybackPresets(override val originItemId: String) : TvSettingsOverlayState
    data class AudioLanguage(override val originItemId: String) : TvSettingsOverlayState

    data class ConnectionCenter(
        override val originItemId: String,
    ) : TvSettingsOverlayState

    data class Support(
        override val originItemId: String,
    ) : TvSettingsOverlayState

    data class Terms(
        override val originItemId: String,
    ) : TvSettingsOverlayState

    data class Legal(
        override val originItemId: String,
    ) : TvSettingsOverlayState
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
    embeddedCategory: TvSettingsCategory? = null,
    entryAction: String? = null, entryNonce: Int = 0, onEntryConsumed: () -> Unit = {},
    mainEntryFocusRequester: FocusRequester? = null,
    homeLayoutFocusRequester: FocusRequester? = null,
    ratingsFocusRequester: FocusRequester? = null,
    onNavigateToHomeLayout: () -> Unit = {},
    onNavigateToRatings: () -> Unit = {},
    onNavigateToPandaSetup: () -> Unit = {},
    onNavigateToAutomationAdmin: () -> Unit = {},
    onNavigateToAutomationConnections: (String) -> Unit = {},
    providerAttentionEntryNonce: Int = 0,
    onNavigateToPairedDevices: (String) -> Unit = {},
    onNavigateToActivatedDevices: (String) -> Unit = {},
    onNavigateToSendCredentials: () -> Unit = {},
    onNavigateToReceiveCredentials: () -> Unit = {},
    onNavigateToTransferDiagnostics: () -> Unit = {},
    onNavigateToReportIssue: () -> Unit = {},
    onNavigateToWatchStats: () -> Unit = {},
    onNavigateToPairingSignIn: () -> Unit = {},
    onAuthSuccess: () -> Unit = {},
    pairedDevicesFocusRequester: FocusRequester? = null,
    activatedDevicesFocusRequester: FocusRequester? = null,
    settingsFocusController: TvSettingsFocusStateMachine,
    onRequestLifetimeUnlock: (TvEntitledFeature) -> Unit = {},
    openToChannelsTab: Boolean = false,
    channelSettingsEntryItemId: String? = null,
    onChannelSettingsBack: (() -> Unit)? = null,
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
    onMdbListManageClick: () -> Unit = {},
    onWebReplaySettingsClick: () -> Unit = {},
    onCommunityAppearanceClick: () -> Unit = {},
    onSavedCollectionsClick: () -> Unit = {},
    onCloudBackupClick: () -> Unit = {},
    onProfilesClick: () -> Unit = {},
    onJellyfinClick: () -> Unit = {},
    mdbListViewModel: MdbListViewModel = koinInject(),
    subscriptionViewModel: SubscriptionViewModel = koinInject(),
    watchStatsViewModel: WatchStatsViewModel = koinInject(),
    deviceGovernanceViewModel: DeviceGovernanceViewModel = koinInject(),
    authClient: com.torve.data.auth.AuthClient = koinInject(),
    providerHealthCoordinator: com.torve.presentation.providerhealth.ProviderHealthCoordinator = koinInject(),
) {
    if (com.torve.android.ui.profile.FamilySettingsLocked()) return

    var alignedLegacy by remember { mutableStateOf(embeddedCategory != null) }
    var legacyEntryCategory by remember { mutableStateOf(embeddedCategory) }
    var legacyEntryItem by remember { mutableStateOf<String?>(null) }
    var legacyEntryKey by remember { mutableStateOf<String?>(null) }
    var handledProviderAttentionNonce by remember { mutableIntStateOf(0) }
    if (embeddedCategory == null) {
        LaunchedEffect(isActive, settingsFocusController.pendingRestore?.restoreToken) {
            // The grouped browser owns entry/return focus. Retired row identities
            // must not send a returning route to Account's fallback requester.
            if (isActive) {
                settingsFocusController.clearPendingRestore()
                settingsFocusController.clearPendingFocusRepair()
            }
        }
        LaunchedEffect(openToChannelsTab, openToSubscriptionSection) {
            if (openToChannelsTab) onChannelsTabConsumed()
            if (openToSubscriptionSection) onSubscriptionSectionConsumed()
        }
        TvAlignedSettingsPanel(railFocusRequester, mainEntryFocusRequester, isActive, isRailFocused,
            onFirstContentRequester, onContentFocused, onMoveFocusToRail,
            onNavigateToPandaSetup, onMdbListManageClick, onCommunityAppearanceClick, onNavigateToHomeLayout,
            onNavigateToRatings, onProfilesClick, onCloudBackupClick, onNavigateToReceiveCredentials, onJellyfinClick, onSavedCollectionsClick,
            legacyForm = { category, first, categoryFocus, focused ->
                val controller = rememberTvSettingsFocusStateMachine("settings_detail_${category.name}", category)
                TvSettingsScreen(railFocusRequester = categoryFocus,
                    onFirstContentRequester = {}, onContentFocused = focused, onMoveFocusToRail = onMoveFocusToRail,
                    embeddedCategory = category, mainEntryFocusRequester = first,
                    settingsFocusController = controller, isActive = isActive, isRailFocused = isRailFocused,
                    onNavigateToPandaSetup = onNavigateToPandaSetup, onNavigateToAutomationAdmin = onNavigateToAutomationAdmin,
                    onNavigateToAutomationConnections = onNavigateToAutomationConnections,
                    onNavigateToPairedDevices = { itemId ->
                        controller.requestRestore(itemId = itemId, reason = "settings_subpage_return")
                        onNavigateToPairedDevices(itemId)
                    }, onNavigateToActivatedDevices = { itemId ->
                        controller.requestRestore(itemId = itemId, reason = "settings_subpage_return")
                        onNavigateToActivatedDevices(itemId)
                    },
                    onNavigateToSendCredentials = onNavigateToSendCredentials, onNavigateToReceiveCredentials = onNavigateToReceiveCredentials,
                    onNavigateToTransferDiagnostics = onNavigateToTransferDiagnostics, onNavigateToReportIssue = onNavigateToReportIssue,
                    onNavigateToWatchStats = onNavigateToWatchStats, onNavigateToPairingSignIn = onNavigateToPairingSignIn,
                    onAuthSuccess = onAuthSuccess, onRequestLifetimeUnlock = onRequestLifetimeUnlock,
                    onMdbListManageClick = onMdbListManageClick, onCommunityAppearanceClick = onCommunityAppearanceClick,
                    onSavedCollectionsClick = onSavedCollectionsClick, onCloudBackupClick = onCloudBackupClick,
                    onProfilesClick = onProfilesClick, onJellyfinClick = onJellyfinClick,
                    onNavigateToHomeLayout = onNavigateToHomeLayout, onNavigateToRatings = onNavigateToRatings,
                    settingsViewModel = settingsViewModel, channelsViewModel = channelsViewModel, addonViewModel = addonViewModel,
                    syncCoordinator = syncCoordinator, accountSessionCoordinator = accountSessionCoordinator,
                    accountSettingsRepository = accountSettingsRepository, providerHealthCoordinator = providerHealthCoordinator)
            },
            initialAction = entryAction ?: when { openToChannelsTab -> "Channels"; openToSubscriptionSection -> "account"; providerAttentionEntryNonce > handledProviderAttentionNonce -> "tests"; else -> null },
            entryNonce = entryNonce, onInitialActionHandled = {
                handledProviderAttentionNonce = providerAttentionEntryNonce
                onEntryConsumed()
            },
            automation = onNavigateToAutomationConnections, webReplays = onWebReplaySettingsClick)
        return
    }

    val syncState by syncCoordinator.state.collectAsState()
    val accountSettingsState by accountSettingsRepository.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val automationSettingsViewModel: com.torve.presentation.integrations.AutomationSettingsViewModel = koinInject()
    val automationSettingsState by automationSettingsViewModel.state.collectAsState()
    val providerHealthEntries by providerHealthCoordinator.entries.collectAsState()
    val integrationReadiness = remember(settingsState, automationSettingsState.instances, providerHealthEntries) {
        com.torve.presentation.settings.buildIntegrationReadinessSummary(
            settingsState,
            automationSettingsState.instances,
            providerHealthEntries,
        )
    }
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val deviceGovernanceState by deviceGovernanceViewModel.state.collectAsState()
    val pandaSetupState = remember { PandaSetupUiState() }
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
    var authEmail by remember { mutableStateOf("") }
    var authPassword by remember { mutableStateOf("") }
    var authConfirmPassword by remember { mutableStateOf("") }
    var authIsLoading by remember { mutableStateOf(false) }
    var authIsSigningOut by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var authUser by remember { mutableStateOf<com.torve.data.auth.AuthUser?>(null) }
    var authShowRegister by remember { mutableStateOf(false) }
    var authEntryStep by remember { mutableStateOf<TvAccountEntryStep?>(null) }
    var importedSetupRefreshRunning by remember { mutableStateOf(false) }
    val accountRefreshProgress by accountSessionCoordinator.restoreProgress.collectAsState()
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

    val attentionEntries = remember(providerHealthEntries) { providerAttentionEntries(providerHealthEntries) }
    val attentionCategories = remember(attentionEntries) { providerAttentionCategories(attentionEntries) }
    val attentionRowOffset = attentionEntries.size
    var focusedProviderAttentionItemId by remember { mutableStateOf<String?>(null) }
    val attentionRequesters = remember { mutableMapOf<String, FocusRequester>() }
    attentionEntries.forEach { entry ->
        attentionRequesters.getOrPut(providerAttentionItemId(entry)) {
            FocusRequester()
        }
    }
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
    var settingsOverlayState by remember { mutableStateOf<TvSettingsOverlayState?>(null) }
    var pendingAuthTransitionFocusTarget by remember { mutableStateOf<TvSettingsFocusTarget?>(null) }

    var pendingSuggestedAddonInstallRestore by remember { mutableStateOf<PendingSuggestedAddonInstallRestore?>(null) }
    var pendingInstalledAddonUninstallRestore by remember { mutableStateOf<PendingInstalledAddonUninstallRestore?>(null) }
    val allowDebugPanel = com.torve.android.BuildConfig.DEBUG

    val context = LocalContext.current
    var reduceMotionEnabled by remember(context) { mutableStateOf(isTvReduceMotionEnabled(context)) }
    val prefs = remember { context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE) }
    // Every settings entry uses the same editable TV forms, including paired TVs.
    var setupMode by remember { mutableStateOf<TvSetupMode?>(TvSetupMode.TV_ONLY) }
    val selectedCategory = embeddedCategory ?: settingsFocusController.selectedCategory
    val settingsListState = rememberLazyListState()
    val settingsWindowInfo = LocalWindowInfo.current
    val appUpdateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        settingsFocusController.requestRestore(
            itemId = TvSettingsItemIds.ABOUT_CHECK_FOR_UPDATES,
            reason = "app_update_return",
            outerListState = settingsListState,
        )
    }
    LaunchedEffect(selectedCategory, providerHealthAutoRefreshStarted) {
        if (providerHealthAutoRefreshStarted) return@LaunchedEffect
        if (selectedCategory != TvSettingsCategory.ADVANCED &&
            selectedCategory != TvSettingsCategory.ABOUT &&
            selectedCategory != TvSettingsCategory.CONNECTIONS
        ) return@LaunchedEffect
        providerHealthAutoRefreshStarted = true
        runCatching {
            org.koin.java.KoinJavaComponent.getKoin()
                .get<com.torve.android.providerhealth.AndroidProviderHealthInit>()
                .start()
        }
        runCatching { providerHealthCoordinator.runAll().join() }
    }
    val pendingSettingsOrigin = settingsFocusController.pendingRestore
    val hasPendingExactSettingsRestore = pendingSettingsOrigin != null
    val allowPendingRestoreFromRail = pendingSettingsOrigin?.reason in
        setOf("app_link", "settings_overlay_dismiss")
    val subscriptionSectionHeaderScrollIndex = remember(authUser, authShowRegister, authError) {
        var index = 0
        index += 1 // section_account
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
    fun dismissSettingsOverlay() {
        val originItemId = settingsOverlayState?.originItemId
        settingsOverlayState = null
        originItemId?.let { itemId ->
            settingsFocusController.requestRestore(
                itemId = itemId,
                reason = "settings_overlay_dismiss",
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
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun categoryUpRequester(category: TvSettingsCategory): FocusRequester =
        if (embeddedCategory != null) FocusRequester.Cancel else categoryRequesters.getValue(category)
    val automationCardRequester = remember { FocusRequester() }
    val pairingCardRequester = remember { FocusRequester() }
    val pairedDevicesCardRequester = pairedDevicesFocusRequester
        ?: remember("account_paired_devices") { FocusRequester() }
    val activatedDevicesCardRequester = activatedDevicesFocusRequester
        ?: remember("account_activated_devices") { FocusRequester() }
    val channelsTopRequester = remember { FocusRequester() }
    val maxQualityCardRequester = remember { FocusRequester() }
    val minQualityCardRequester = remember { FocusRequester() }
    val communityAppearanceRequester = remember { FocusRequester() }
    val savedCollectionsRequester = remember { FocusRequester() }
    val reduceMotionCardRequester = remember { FocusRequester() }
    val optionalFeatureRequesters = remember {
        com.torve.presentation.settings.OptionalAppFeature.availableEntries.associateWith { FocusRequester() }
    }
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
    val authAccountRequester = remember { FocusRequester() }
    val authPrimaryActionRequester = remember { FocusRequester() }
    val authEmailRequester = remember { FocusRequester() }
    val accountSyncStatusCardRequester = remember { FocusRequester() }
    val accountSyncErrorCardRequester = remember { FocusRequester() }
    val connectionCenterCardRequester = remember { FocusRequester() }
    val receiveCredentialsCardRequester = remember { FocusRequester() }
    var connectionRowFocusJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

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
    val pairedDevicesTarget = remember(attentionRowOffset) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIRED_DEVICES,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 4 + attentionRowOffset,
            focusTargetType = "card",
        )
    }
    val activatedDevicesTarget = remember(attentionRowOffset) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_ACTIVATED_DEVICES,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 5 + attentionRowOffset,
            focusTargetType = "card",
        )
    }
    val accountSyncStatusTarget = remember(attentionRowOffset) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SYNC_STATUS,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 6 + attentionRowOffset,
            focusTargetType = "action",
        )
    }
    val accountSyncErrorTarget = remember(attentionRowOffset) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SYNC_ERROR,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 7 + attentionRowOffset,
            focusTargetType = "action",
        )
    }
    val playbackPresetTarget = remember {
        TvSettingsFocusTarget("settings/playback/preset", TvSettingsCategory.PLAYBACK, 1, "selector")
    }
    val maxQualityTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_MAX_QUALITY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 2,
            focusTargetType = "selector",
        )
    }
    val minQualityTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_MIN_QUALITY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 3,
            focusTargetType = "selector",
        )
    }
    val autoplayTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_AUTOPLAY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 11,
            focusTargetType = "toggle",
        )
    }
    val autoplayNextTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_AUTOPLAY_NEXT,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 12,
            focusTargetType = "toggle",
        )
    }
    val dedupeTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_DEDUPE,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 21,
            focusTargetType = "toggle",
        )
    }
    val playbackAudioModeTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_AUDIO_MODE,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 31,
            focusTargetType = "selector",
        )
    }
    val playbackAudioPassthroughTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_AUDIO_PASSTHROUGH,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 32,
            focusTargetType = "toggle",
        )
    }
    val playbackAudioSurroundTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_AUDIO_SURROUND,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 33,
            focusTargetType = "toggle",
        )
    }
    // Keep the lazy content closure below Android's captured-parameter limit.
    val playbackRowTargets = remember {
        mapOf(
            "audioLanguage" to TvSettingsFocusTarget("playback_audio_language", TvSettingsCategory.PLAYBACK, 22, "selector"),
            "subtitleFallback" to TvSettingsFocusTarget("playback_subtitle_fallback", TvSettingsCategory.PLAYBACK, 23, "toggle"),
            "playbackPresetTarget" to playbackPresetTarget,
            "maxQualityTarget" to maxQualityTarget,
            "minQualityTarget" to minQualityTarget,
            "autoplayTarget" to autoplayTarget,
            "autoplayNextTarget" to autoplayNextTarget,
            "dedupeTarget" to dedupeTarget,
            "playbackAudioModeTarget" to playbackAudioModeTarget,
            "playbackAudioPassthroughTarget" to playbackAudioPassthroughTarget,
            "playbackAudioSurroundTarget" to playbackAudioSurroundTarget,
        )
    }
    val playbackPresetCardRequester = remember { FocusRequester() }

    val languageTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_LANGUAGE,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 7,
            focusTargetType = "selector",
        )
    }
    val appearanceRegionTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_REGION,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 8,
            focusTargetType = "selector",
        )
    }
    val homeLayoutTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_HOME_LAYOUT,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 9,
            focusTargetType = "navigation",
        )
    }
    val ratingsTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_RATINGS,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 10,
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
            listIndex = 11,
            focusTargetType = "toggle",
        )
    }
    val seeAllPosterColumnsTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_SEE_ALL_POSTER_COLUMNS,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 12,
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
    val libraryRefreshChannelsTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.LIBRARY_REFRESH_CHANNELS,
            category = TvSettingsCategory.LIBRARY,
            listIndex = 33,
            focusTargetType = "action",
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
    val connectionsAutomationTarget = remember(attentionRowOffset) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_AUTOMATION,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 1 + attentionRowOffset,
            focusTargetType = "navigation",
        )
    }
    val connectionsPairingTarget = remember(attentionRowOffset) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_PAIRING,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 3 + attentionRowOffset,
            focusTargetType = "navigation",
        )
    }
    val connectionCenterTarget = remember(accountSettingsState.lastError != null, attentionRowOffset) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_CENTER,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 8 + attentionRowOffset + if (accountSettingsState.lastError != null) 1 else 0,
            focusTargetType = "navigation",
        )
    }
    val receiveCredentialsTarget = remember(accountSettingsState.lastError != null, attentionRowOffset) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_RECEIVE_CREDENTIALS,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = connectionCenterTarget.listIndex + 1,
            focusTargetType = "navigation",
        )
    }
    val phoneConnectionsMode = setupMode == TvSetupMode.ANDROID_PHONE || setupMode == TvSetupMode.IOS_PHONE
    val connectionsTraktIndex = receiveCredentialsTarget.listIndex + 1 +
        if (phoneConnectionsMode) (if (showRecoveryRow) 1 else 0) + tvRepairRows.size + 2 else 0
    val connectionsTraktTarget = remember(connectionsTraktIndex) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_TRAKT,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = connectionsTraktIndex,
            focusTargetType = "action",
        )
    }
    val connectionsTraktReconnectTarget = remember(connectionsTraktIndex) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_TRAKT_RECONNECT,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = connectionsTraktIndex + 1,
            focusTargetType = "action",
        )
    }
    val connectionsTraktDisconnectTarget = remember(connectionsTraktIndex) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_TRAKT_DISCONNECT,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = connectionsTraktIndex + 2,
            focusTargetType = "dangerous",
        )
    }
    val connectionsSimklTarget = remember(attentionRowOffset) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_SIMKL,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 20 + attentionRowOffset,
            focusTargetType = "action",
        )
    }
    val connectionsSimklDisconnectTarget = remember(attentionRowOffset) {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.CONNECTIONS_SIMKL_DISCONNECT,
            category = TvSettingsCategory.CONNECTIONS,
            listIndex = 21 + attentionRowOffset,
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
    val aboutCheckForUpdatesTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_CHECK_FOR_UPDATES,
            category = TvSettingsCategory.ABOUT,
            listIndex = 2,
            focusTargetType = "action",
        )
    }
    val aboutSupportTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_SUPPORT,
            category = TvSettingsCategory.ABOUT,
            listIndex = 3,
            focusTargetType = "navigation",
        )
    }
    val aboutReportIssueTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_REPORT_ISSUE,
            category = TvSettingsCategory.ABOUT,
            listIndex = 4,
            focusTargetType = "navigation",
        )
    }
    val aboutTermsTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_TERMS,
            category = TvSettingsCategory.ABOUT,
            listIndex = 5,
            focusTargetType = "navigation",
        )
    }
    val aboutLegalTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_LEGAL,
            category = TvSettingsCategory.ABOUT,
            listIndex = 6,
            focusTargetType = "navigation",
        )
    }
    val aboutStatsTarget = remember {
        TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ABOUT_STATS,
            category = TvSettingsCategory.ABOUT,
            listIndex = 7,
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

    fun onSettingsRowFocused(target: TvSettingsFocusTarget, requester: FocusRequester) {
        focusedProviderAttentionItemId = target.itemId.takeIf { it.startsWith("settings/connections/provider_attention/") }
        settingsFocusController.markFocused(target.itemId, requester)
        onContentFocused(requester)
    }

    fun focusConnectionRow(target: TvSettingsFocusTarget, requester: FocusRequester) {
        connectionRowFocusJob?.cancel()
        connectionRowFocusJob = authScope.launch {
            snapshotFlow { settingsWindowInfo.isWindowFocused }.first { it }
            val restored = focusTvSettingsRow(settingsListState, target.listIndex, requester) {
                settingsFocusController.focusedItemId == target.itemId
            }
            if (!restored) runCatching { railFocusRequester.requestFocus() }
        }
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
                val result = accountSessionCoordinator.refreshAccountDataAfterCredentialTransfer(
                    promoteLegacyTvJellyfin = true,
                )
                settingsViewModel.refreshSettings()
                channelsViewModel.loadPlaylists(recoverEmptyCatalog = true)
                channelsViewModel.loadFavorites()
                runCatching {
                    org.koin.java.KoinJavaComponent.getKoin()
                        .get<com.torve.android.providerhealth.AndroidProviderHealthInit>()
                        .start()
                }
                runCatching { providerHealthCoordinator.runAll().join() }
                val healthIssues = providerHealthCoordinator.entries.value
                    .filter { it.status == ProviderHealthStatus.RED }
                    .map { "${it.label} check failed." }
                val issues = (result.issues + healthIssues).distinct()
                val completionMessage = if (issues.isNotEmpty()) {
                    "Refresh completed with issues: ${issues.joinToString(" ")}"
                } else {
                    context.getString(R.string.tv_settings_account_refreshed_epg_hint)
                }
                PostSignInRefresh.enqueueContentWarmupAfterAccountActivation(context)
                TvNotificationQueue.post(
                    completionMessage,
                    if (issues.isEmpty()) NotificationType.SUCCESS else NotificationType.ERROR,
                )
            } finally {
                importedSetupRefreshRunning = false
            }
        }
    }
    var previousPlaylistCount by remember { mutableIntStateOf(channelsState.playlists.size) }
    var previousShowAddPlaylist by remember { mutableStateOf(showAddPlaylist) }
    LaunchedEffect(isActive, isRailFocused, hasPendingExactSettingsRestore, settingsOverlayState) {
        if (settingsOverlayState != null) return@LaunchedEffect
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
        settingsOverlayState,
        settingsFocusController.pendingRestore?.restoreToken,
        isActive,
        isRailFocused,
        settingsWindowInfo.isWindowFocused,
    ) {
        if (!isActive) return@LaunchedEffect
        if (!settingsWindowInfo.isWindowFocused) return@LaunchedEffect
        if (isRailFocused && !allowPendingRestoreFromRail) return@LaunchedEffect
        if (settingsOverlayState != null || showLanguagePicker || showMaxQualityPicker || showMinQualityPicker) {
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
            val restored = settingsFocusController.restorePendingFocus(
                outerListState = settingsListState,
                isScreenActive = { isActive && settingsWindowInfo.isWindowFocused },
            )
            if (restored) {
                settingsFocusController.focusedItemId
                    ?.let(settingsFocusController::requesterForItemId)
                    ?.let(onContentFocused)
            }
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

    fun submitAuth() {
        if (authEmail.isBlank()) { authEntryStep = TvAccountEntryStep.EMAIL; return }
        if (authPassword.isBlank()) { authEntryStep = TvAccountEntryStep.PASSWORD; return }
        if (!authIsLoading) {
            if (authShowRegister && authPassword != authConfirmPassword) {
                authError = passwordsMismatchText
                TvNotificationQueue.post(passwordsMismatchText, NotificationType.ERROR)
                return
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
    }
    authEntryStep?.let { step ->
        TvAccountEntryDialog(step, when (step) {
            TvAccountEntryStep.EMAIL -> authEmail
            TvAccountEntryStep.PASSWORD -> authPassword
            TvAccountEntryStep.CONFIRM_PASSWORD -> authConfirmPassword
        }, authShowRegister, onConfirm = { value ->
            authError = null
            when (step) {
                TvAccountEntryStep.EMAIL -> { authEmail = value.trim(); authEntryStep = TvAccountEntryStep.PASSWORD }
                TvAccountEntryStep.PASSWORD -> {
                    authPassword = value
                    if (authShowRegister) authEntryStep = TvAccountEntryStep.CONFIRM_PASSWORD
                    else { authEntryStep = null; submitAuth() }
                }
                TvAccountEntryStep.CONFIRM_PASSWORD -> {
                    authConfirmPassword = value; authEntryStep = null; submitAuth()
                }
            }
        }, onDismiss = { authEntryStep = null })
    }

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
        settingsState.traktError != null && settingsState.traktConnected -> "Trakt needs attention - press OK to retry or reconnect"
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
    val nextEpisodePreparationTarget = remember {
        TvSettingsFocusTarget(
            itemId = "playback_next_episode_preparation",
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 13,
            focusTargetType = "selector",
        )
    }
    val skipIntroTarget = remember { TvSettingsFocusTarget("playback_skip_intro", TvSettingsCategory.PLAYBACK, 14, "selector") }
    val skipRecapTarget = remember { TvSettingsFocusTarget("playback_skip_recap", TvSettingsCategory.PLAYBACK, 15, "selector") }
    val playNextCreditsTarget = remember { TvSettingsFocusTarget("playback_play_next_credits", TvSettingsCategory.PLAYBACK, 16, "selector") }
    val protectPostCreditTarget = remember { TvSettingsFocusTarget("playback_protect_post_credit", TvSettingsCategory.PLAYBACK, 17, "toggle") }
    val smartSegmentsTarget = remember { TvSettingsFocusTarget("playback_smart_segments", TvSettingsCategory.PLAYBACK, 18, "toggle") }
    val minimumSourceSizeTarget = remember {
        TvSettingsFocusTarget(
            itemId = "playback_minimum_source_size",
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 14,
            focusTargetType = "selector",
        )
    }
    val traktNeedsReauthorization = settingsState.traktConnected &&
        settingsState.traktUser == null &&
        !settingsState.traktApiStatus.equals("Online", ignoreCase = true) &&
        settingsState.traktApiStatus?.contains("rate", ignoreCase = true) != true

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
            TvSettingsCategory.ACCOUNT -> if (authUser != null) authAccountRequester else authEmailRequester
            TvSettingsCategory.PLAYBACK -> playbackPresetCardRequester
            TvSettingsCategory.APPEARANCE -> communityAppearanceRequester
            TvSettingsCategory.LIBRARY -> channelsTopRequester
            TvSettingsCategory.CONNECTIONS -> attentionEntries.firstOrNull()?.let {
                attentionRequesters.getValue(providerAttentionItemId(it))
            } ?: automationCardRequester
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
        attentionEntries.firstOrNull()?.let(::providerAttentionItemId),
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
        settingsOverlayState,
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
            settingsOverlayState != null ||
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
        settingsOverlayState,
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
        if (settingsOverlayState != null || showLanguagePicker || showMaxQualityPicker || showMinQualityPicker) {
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
        settingsOverlayState,
        showLanguagePicker,
        showMaxQualityPicker,
        showMinQualityPicker,
    ) {
        val target = pendingAuthTransitionFocusTarget ?: return@LaunchedEffect
        val targetItemId = target.itemId
        if (!isActive) return@LaunchedEffect
        if (settingsOverlayState != null || showLanguagePicker || showMaxQualityPicker || showMinQualityPicker) {
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
                    color = MaterialTheme.colorScheme.onSurface,
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
    LaunchedEffect(legacyEntryCategory, isActive, isRailFocused) {
        val category = legacyEntryCategory ?: return@LaunchedEffect
        if (!isActive || isRailFocused) return@LaunchedEffect
        if (settingsFocusController.pendingRestore != null) {
            legacyEntryCategory = null
            return@LaunchedEffect
        }
        settingsFocusController.beginCategorySwitch(category)
        focusMoveNonce++
        pendingFocusMove = if (legacyEntryItem != null) {
            TvSettingsFocusMoveRequest(focusMoveNonce, TvSettingsFocusMoveTarget.PROVIDER_ITEM, category, legacyEntryItem, legacyEntryKey)
        } else if (category == TvSettingsCategory.APPEARANCE) {
            TvSettingsFocusMoveRequest(focusMoveNonce, TvSettingsFocusMoveTarget.PROVIDER_ITEM, category,
                TvSettingsItemIds.APPEARANCE_POSTER_TITLES, "poster_titles")
        } else TvSettingsFocusMoveRequest(focusMoveNonce, TvSettingsFocusMoveTarget.CATEGORY_DETAIL, category)
        legacyEntryCategory = null; legacyEntryItem = null; legacyEntryKey = null
    }

    LaunchedEffect(openToChannelsTab, channelSettingsEntryItemId, isActive) {
        if (openToChannelsTab && isActive) {
            settingsFocusController.selectedCategory = TvSettingsCategory.LIBRARY
            val itemId = channelSettingsEntryItemId ?: TvSettingsItemIds.LIBRARY_MANAGE_CHANNELS
            val lazyKey = when {
                itemId.startsWith("settings/library/playlist/") -> "playlist_${itemId.substringAfterLast('/')}"
                itemId == TvSettingsItemIds.LIBRARY_CHANNELS -> "no_playlists"
                else -> "manage_channels"
            }
            focusMoveNonce += 1
            pendingFocusMove = TvSettingsFocusMoveRequest(focusMoveNonce, TvSettingsFocusMoveTarget.PROVIDER_ITEM,
                TvSettingsCategory.LIBRARY, itemId, lazyKey)
            logSettingsFocus("channel_entry_request item=$itemId")
        }
    }
    // Capture the request used as the effect key. The channel-entry effect can
    // queue a request before this initial effect starts; reading mutable state
    // here would let the old effect cancel and clear that new request.
    val focusMoveRequest = pendingFocusMove
    LaunchedEffect(focusMoveRequest) {
        val request = focusMoveRequest ?: return@LaunchedEffect
        try {
            settingsListState.scrollToItem(0)
            if (request.target == TvSettingsFocusMoveTarget.PROVIDER_ITEM) {
                // Rows further down a settings tab may be virtualized. Reveal
                // their stable lazy key before requesting their registered focus.
                repeat(3) { withFrameNanos { } }
                val total = settingsListState.layoutInfo.totalItemsCount
                var index = 0
                while (index < total) {
                    if (pendingFocusMove?.nonce != request.nonce) return@LaunchedEffect
                    settingsListState.scrollToItem(index)
                    withFrameNanos { }
                    val visible = settingsListState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == request.lazyItemKey }
                    if (visible != null) {
                        settingsListState.scrollToItem(visible.index)
                        repeat(12) {
                            withFrameNanos { }
                            request.itemId?.let(settingsFocusController::requesterForItemId)?.let { requester ->
                                runCatching { requester.requestFocus() }
                                withFrameNanos { }
                                if (settingsFocusController.focusedItemId == request.itemId) {
                                    if (openToChannelsTab && request.itemId == channelSettingsEntryItemId) {
                                        settingsFocusController.clearPendingFocusRepair()
                                        logSettingsFocus("channel_entry_focused item=${request.itemId}")
                                        onChannelsTabConsumed()
                                    }
                                    return@LaunchedEffect
                                }
                            }
                        }
                        break
                    }
                    index = (settingsListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: index) + 1
                }
                settingsListState.scrollToItem(0)
            }
            // Category changes replace the detail subtree. Wait for attachment by
            // observing Compose frames, not by guessing a device-dependent delay.
            repeat(12) {
                withFrameNanos { }
                if (pendingFocusMove?.nonce != request.nonce) return@LaunchedEffect
                val requester = when (request.target) {
                    TvSettingsFocusMoveTarget.SELECTED_CATEGORY_CHIP -> selectedCategoryFocusRequester
                    TvSettingsFocusMoveTarget.CATEGORY_CHIP -> categoryRequesters.getValue(
                        request.category ?: selectedCategory,
                    )
                    TvSettingsFocusMoveTarget.CATEGORY_DETAIL,
                    TvSettingsFocusMoveTarget.PROVIDER_ITEM -> detailRequesterForCategory(
                        request.category ?: selectedCategory,
                    )
                }
                runCatching { requester.requestFocus() }
                withFrameNanos { }
                val targetCategory = request.category ?: selectedCategory
                val reachedTarget = when (request.target) {
                    TvSettingsFocusMoveTarget.CATEGORY_DETAIL,
                    TvSettingsFocusMoveTarget.PROVIDER_ITEM ->
                        settingsFocusController.focusedTarget()?.category == targetCategory
                    TvSettingsFocusMoveTarget.SELECTED_CATEGORY_CHIP,
                    TvSettingsFocusMoveTarget.CATEGORY_CHIP ->
                        categoryPaneHasFocus && selectedCategory == targetCategory
                }
                if (reachedTarget) {
                    return@LaunchedEffect
                }
            }
            Log.w(
                "TvSettings",
                "Focus target was not attached target=${request.target} " +
                    "category=${request.category ?: selectedCategory}",
            )
            val categoryFallback = categoryRequesters.getValue(request.category ?: selectedCategory)
            runCatching { categoryFallback.requestFocus() }
            withFrameNanos { }
            if (!categoryPaneHasFocus) {
                // Absolute invariant fallback: never leave the TV with no focus owner.
                runCatching { railFocusRequester.requestFocus() }
            }
        } finally {
            if (pendingFocusMove?.nonce == request.nonce) {
                pendingFocusMove = null
            }
        }
    }
    LaunchedEffect(providerAttentionEntryNonce) {
        if (providerAttentionEntryNonce == 0 || attentionEntries.isEmpty() || authUser == null) return@LaunchedEffect
        settingsFocusController.beginCategorySwitch(TvSettingsCategory.CONNECTIONS)
        focusMoveNonce++
        pendingFocusMove = TvSettingsFocusMoveRequest(
            nonce = focusMoveNonce,
            target = TvSettingsFocusMoveTarget.CATEGORY_DETAIL,
            category = TvSettingsCategory.CONNECTIONS,
        )
    }
    LaunchedEffect(attentionEntries.map(::providerAttentionItemId), isActive, isRailFocused, authUser != null) {
        val previousItemId = focusedProviderAttentionItemId ?: return@LaunchedEffect
        if (attentionEntries.any { providerAttentionItemId(it) == previousItemId }) return@LaunchedEffect
        if (!isActive || isRailFocused || authUser == null) return@LaunchedEffect
        // Removing the focused warning can make Compose select the first tab.
        // Keep the user in Connections and focus the next warning or normal row.
        focusedProviderAttentionItemId = null
        settingsFocusController.beginCategorySwitch(TvSettingsCategory.CONNECTIONS)
        focusMoveNonce++
        pendingFocusMove = TvSettingsFocusMoveRequest(
            nonce = focusMoveNonce,
            target = TvSettingsFocusMoveTarget.CATEGORY_DETAIL,
            category = TvSettingsCategory.CONNECTIONS,
        )
    }
    fun openProviderSettings(entry: ProviderHealthEntry) {
        val destination = providerAttentionDestination(entry)
        if (destination == TvProviderAttentionDestination.STREAMING_SETUP ||
            destination == TvProviderAttentionDestination.SEERR ||
            destination == TvProviderAttentionDestination.AUTOMATION
        ) {
            settingsFocusController.captureOrigin(providerAttentionItemId(entry), settingsListState, "provider_attention")
            when (destination) {
                TvProviderAttentionDestination.STREAMING_SETUP -> onNavigateToPandaSetup()
                TvProviderAttentionDestination.SEERR -> onNavigateToAutomationConnections("seerr")
                else -> onNavigateToAutomationConnections("automation")
            }
            return
        }
        val (itemId, lazyKey) = when (destination) {
            TvProviderAttentionDestination.TRAKT -> TvSettingsItemIds.CONNECTIONS_TRAKT to "trakt"
            TvProviderAttentionDestination.SIMKL -> TvSettingsItemIds.CONNECTIONS_SIMKL to "simkl"
            TvProviderAttentionDestination.PLEX -> if (phoneConnectionsMode) {
                TvSettingsItemIds.ADVANCED_PHONE_PLEX to "phone_plex"
            } else TvSettingsItemIds.ADVANCED_PLEX_URL to "plex_url"
            TvProviderAttentionDestination.JELLYFIN -> if (phoneConnectionsMode) {
                TvSettingsItemIds.ADVANCED_PHONE_JELLYFIN to "phone_jellyfin"
            } else TvSettingsItemIds.ADVANCED_JELLYFIN_URL to "jellyfin_url"
            TvProviderAttentionDestination.ADDONS -> addonState.addons.firstOrNull {
                entry.providerKey.contains(it.manifestUrl) || entry.label.equals(it.manifest.name, ignoreCase = true)
            }?.let { "addon_installed_${it.manifestUrl}" to "addon_${it.manifestUrl}" } ?: (null to null)
            TvProviderAttentionDestination.CHANNELS -> channelsState.playlists.firstOrNull {
                entry.providerKey.contains(it.id) || entry.label.equals(it.name, ignoreCase = true)
            }?.let { "settings/library/playlist/${it.id}" to "playlist_${it.id}" } ?: (null to null)
            TvProviderAttentionDestination.PLAYBACK -> TvSettingsItemIds.PLAYBACK_MAX_QUALITY to "max_quality"
            else -> null to null
        }
        settingsFocusController.beginCategorySwitch(destination.category)
        focusMoveNonce++
        pendingFocusMove = TvSettingsFocusMoveRequest(
            nonce = focusMoveNonce,
            target = if (itemId != null) TvSettingsFocusMoveTarget.PROVIDER_ITEM else TvSettingsFocusMoveTarget.CATEGORY_DETAIL,
            category = destination.category,
            itemId = itemId,
            lazyItemKey = lazyKey,
        )
    }
    val attentionUi = TvProviderAttentionUi(
        entries = attentionEntries,
        categories = attentionCategories,
        requesters = attentionRequesters,
        controller = settingsFocusController,
        railRequester = railFocusRequester,
        categoryRequester = categoryRequesters.getValue(TvSettingsCategory.CONNECTIONS),
        automationRequester = automationCardRequester,
        automationTarget = connectionsAutomationTarget,
        openSettings = { openProviderSettings(it) },
        focusRow = { target, requester -> focusConnectionRow(target, requester) },
        onFocused = { target, requester -> onSettingsRowFocused(target, requester) },
    )
    fun moveFocusToRailFromSettings() {
        focusedProviderAttentionItemId = null
        settingsFocusController.clearPendingRestore()
        settingsFocusController.clearPendingFocusRepair()
        pendingFocusMove = null
        onMoveFocusToRail()
        runCatching { railFocusRequester.requestFocus() }
    }
    fun handleSettingsBack() {
        when {
            confirmHideAllChannelGroups -> confirmHideAllChannelGroups = false
            showChannelManager -> {
                showChannelManager = false
                focusMoveNonce += 1
                pendingFocusMove = TvSettingsFocusMoveRequest(focusMoveNonce, TvSettingsFocusMoveTarget.PROVIDER_ITEM,
                    TvSettingsCategory.LIBRARY, TvSettingsItemIds.LIBRARY_MANAGE_CHANNELS, "manage_channels")
            }
            onChannelSettingsBack != null -> onChannelSettingsBack()
            else -> { alignedLegacy = false }
        }
    }
    BackHandler(
        enabled = (embeddedCategory == null || showChannelManager) && isActive &&
            !isRailFocused &&
            !hasPendingExactSettingsRestore &&
            settingsOverlayState == null &&
            !showLanguagePicker &&
            !showMaxQualityPicker &&
            !showMinQualityPicker,
    ) {
        handleSettingsBack()
    }
    Row(Modifier.fillMaxSize()) {
        if (embeddedCategory == null) LazyColumn(Modifier.width(204.dp).fillMaxHeight().padding(start = 20.dp, top = TV_PAGE_TOP_GUTTER, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                item { Button(onClick = { alignedLegacy = false }) { Text(localizedUiText("Back to settings")) } }
                items(categoryEntries.filter { it.first == selectedCategory }, key = { "cat_${it.first.name}" }) { (category, label) ->
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
                        needsAttention = category in attentionUi.categories,
                        selected = selectedCategory == category,
                        isLocked = isCategoryLocked,
                        modifier = Modifier
                            .focusRequester(requester)
                            .focusProperties { left = railFocusRequester }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) focusedProviderAttentionItemId = null
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
                                        handleSettingsBack()
                                        true
                                    }
                                    Key.DirectionLeft -> { handleSettingsBack(); true }
                                    Key.DirectionRight -> {

                                        settingsFocusController.beginCategorySwitch(category)
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
                            if (pendingFocusMove?.target == TvSettingsFocusMoveTarget.CATEGORY_DETAIL ||
                                pendingFocusMove?.target == TvSettingsFocusMoveTarget.PROVIDER_ITEM
                            ) return@TvSettingsTopCategoryChip
                            if (!hasPendingExactSettingsRestore) {
                                settingsFocusController.beginCategorySwitch(category)
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
                                settingsFocusController.beginCategorySwitch(category)
                                focusMoveNonce += 1
                                pendingFocusMove = TvSettingsFocusMoveRequest(focusMoveNonce, TvSettingsFocusMoveTarget.CATEGORY_DETAIL, category)
                            }
                        },
                    )
                }
        }
    LazyColumn(
        state = settingsListState,
        modifier = Modifier
            .weight(1f).fillMaxHeight()
            .testTag("tv_settings_list")
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
                if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                    val direction = if (event.key == Key.DirectionLeft) -1 else 1
                    if (hasAdjacentTvSettingsControl(settingsFocusController.focusedItemId, direction)) {
                        // Let the focused card handle movement within a horizontal
                        // control group before the page-level category shortcut.
                        return@onPreviewKeyEvent false
                    }
                    if (event.key == Key.DirectionLeft && embeddedCategory == null) {
                        categoryRequesters.getValue(selectedCategory).requestFocus()
                        return@onPreviewKeyEvent true
                    }
                }
                when (event.key) {
                    Key.DirectionUp -> {
                        // Registered focus targets only cover currently composed LazyColumn
                        // items. Treating the first registered item as the first logical row
                        // skips rows that are just above the viewport. Let Compose scroll to
                        // the previous focusable row; the actual top row has an explicit Up
                        // target to its category chip.
                        false
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
        contentPadding = if (embeddedCategory != null) PaddingValues(bottom = 24.dp) else PaddingValues(
            start = TV_PAGE_CONTENT_GUTTER, top = TV_PAGE_TOP_GUTTER,
            end = TV_PAGE_END_GUTTER, bottom = TV_PAGE_BOTTOM_GUTTER),
        verticalArrangement = Arrangement.spacedBy(if (embeddedCategory != null) 4.dp else 12.dp),
    ) {
        // Setup mode header — tap to change
        item(key = "category_selector") { Spacer(Modifier.height(0.dp)) }

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
                Box {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_premium_channels),
                        subtitle = TvPremiumAccess.LIFETIME_REQUIRED_LABEL,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            up = categoryUpRequester(TvSettingsCategory.LIBRARY)
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
                Box {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_premium_advanced),
                        subtitle = TvPremiumAccess.LIFETIME_REQUIRED_LABEL,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            up = categoryUpRequester(TvSettingsCategory.ADVANCED)
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
            tvProviderAttentionRows(attentionUi)
            item(key = "automation_administration_connections") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = connectionsAutomationTarget,
                    externalRequester = automationCardRequester,
                    isDefaultEntry = attentionUi.entries.isEmpty(),
                )
                Box {
                    TvSettingCard(
                        title = localizedUiText("*Arr media automation"),
                        subtitle = localizedUiText(integrationReadiness
                            .item(com.torve.presentation.settings.IntegrationWorkflow.AUTOMATION)
                            .detail + " Press OK to manage."),
                        modifier = Modifier.fillMaxWidth().tvSettingsRowNavigation(
                            onUp = attentionUi.entries.lastOrNull()?.let { entry -> ({
                                attentionUi.focusRow(
                                    TvSettingsFocusTarget(providerAttentionItemId(entry), TvSettingsCategory.CONNECTIONS, attentionUi.entries.size, "navigation"),
                                    attentionUi.requesters.getValue(providerAttentionItemId(entry)),
                                )
                            }) },
                        ).focusProperties {
                            left = railFocusRequester
                            if (attentionUi.entries.isEmpty()) up = categoryUpRequester(TvSettingsCategory.CONNECTIONS)
                            down = pairingCardRequester
                        },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(connectionsAutomationTarget, requester) },
                        onClick = onNavigateToAutomationAdmin,
                        rowType = TvSettingRowType.NAVIGATION,
                    )
                }
            }
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
                )
                Box {
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_pair_device),
                        subtitle = pairingSubtitle,
                        modifier = Modifier.fillMaxWidth().focusProperties {
                            left = railFocusRequester
                            up = automationCardRequester
                            down = pairedDevicesCardRequester
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
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = pairedDevicesTarget,
                    externalRequester = pairedDevicesCardRequester,
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
                    modifier = Modifier.fillMaxWidth().focusProperties {
                        left = railFocusRequester
                        up = pairingCardRequester
                        down = activatedDevicesCardRequester
                    },
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
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = activatedDevicesTarget,
                    externalRequester = activatedDevicesCardRequester,
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_activated_devices),
                    subtitle = stringResource(R.string.tv_settings_activated_devices_subtitle),
                    modifier = Modifier.fillMaxWidth().focusProperties {
                        left = railFocusRequester
                        up = pairedDevicesCardRequester
                        down = accountSyncStatusCardRequester
                    },
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
                    modifier = Modifier.fillMaxWidth().tvSettingsRowNavigation(onDown = {
                        if (accountSettingsState.lastError != null) {
                            focusConnectionRow(accountSyncErrorTarget, accountSyncErrorCardRequester)
                        } else {
                            focusConnectionRow(connectionCenterTarget, connectionCenterCardRequester)
                        }
                    }).focusProperties {
                        left = railFocusRequester
                        up = activatedDevicesCardRequester
                        if (accountSettingsState.lastError != null) {
                            down = accountSyncErrorCardRequester
                        }
                    },
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
                        modifier = Modifier.fillMaxWidth().tvSettingsRowNavigation(
                            onUp = { focusConnectionRow(accountSyncStatusTarget, accountSyncStatusCardRequester) },
                            onDown = { focusConnectionRow(connectionCenterTarget, connectionCenterCardRequester) },
                        ).focusProperties {
                            left = railFocusRequester
                            up = accountSyncStatusCardRequester
                        },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(accountSyncErrorTarget, requester) },
                        onClick = { refreshImportedSetup() },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                    )
                }
            }
        }

        if (selectedCategory == TvSettingsCategory.CONNECTIONS) {
            item(key = "section_connections_cloud_accounts") {
                TvSectionHeader(
                    text = stringResource(R.string.tv_settings_streaming_accounts),
                    description = integrationReadiness.compactStatusLine,
                )
            }
            item(key = "connections_readiness_panel") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = connectionCenterTarget,
                    externalRequester = connectionCenterCardRequester,
                )
                TvSettingCard(
                    title = localizedUiText("Connection center"),
                    needsAttention = attentionUi.entries.isNotEmpty(),
                    subtitle = localizedUiText("View readiness, find missing setup, and open the relevant settings."),
                    modifier = Modifier.fillMaxWidth().tvSettingsRowNavigation(
                        onUp = {
                            if (accountSettingsState.lastError != null) {
                                focusConnectionRow(accountSyncErrorTarget, accountSyncErrorCardRequester)
                            } else {
                                focusConnectionRow(accountSyncStatusTarget, accountSyncStatusCardRequester)
                            }
                        },
                        onDown = { focusConnectionRow(receiveCredentialsTarget, receiveCredentialsCardRequester) },
                    ).focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(connectionCenterTarget, requester) },
                    onClick = {
                        settingsFocusController.captureOrigin(connectionCenterTarget.itemId, settingsListState, "connection_center")
                        settingsOverlayState = TvSettingsOverlayState.ConnectionCenter(connectionCenterTarget.itemId)
                    },
                    rowType = TvSettingRowType.NAVIGATION,
                )
            }
            item(key = "receive_credentials") {
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController,
                    target = receiveCredentialsTarget,
                    externalRequester = receiveCredentialsCardRequester,
                )
                TvSettingCard(
                    title = stringResource(R.string.settings_receive_credentials),
                    subtitle = stringResource(R.string.tv_settings_receive_credentials_import_desc),
                    modifier = Modifier.fillMaxWidth().tvSettingsRowNavigation(
                        onUp = { focusConnectionRow(connectionCenterTarget, connectionCenterCardRequester) },
                        onDown = if (phoneConnectionsMode) null else {
                            { focusConnectionRow(connectionsTraktTarget, traktCardRequester) }
                        },
                    ).focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(receiveCredentialsTarget, requester) },
                    onClick = {
                        settingsFocusController.captureOrigin(receiveCredentialsTarget.itemId, settingsListState, "route_open")
                        onNavigateToReceiveCredentials()
                    },
                    rowType = TvSettingRowType.NAVIGATION,
                )
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
                        up = categoryUpRequester(TvSettingsCategory.ACCOUNT)
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
                    subtitle = localizedUiText(when {
                        importedSetupRefreshRunning -> accountRefreshProgress.message.ifBlank {
                            stringResource(R.string.transfer_refresh_all_after_import_running)
                        }
                        accountRefreshProgress.issues.isNotEmpty() ->
                            "Last refresh issues: ${accountRefreshProgress.issues.joinToString(" ")}"
                        else -> stringResource(R.string.tv_settings_import_refresh_subtitle)
                    }),
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
                        title = { Text(stringResource(R.string.tv_settings_log_out), color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            com.torve.android.remote.TorveRemoteInputSurface({})
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(color = Amber, strokeWidth = 3.dp)
                                Text(stringResource(R.string.tv_auth_please_wait), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        title = { Text(stringResource(R.string.tv_settings_delete_account_title), color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            com.torve.android.remote.TorveRemoteInputSurface { if (!isDeletingAccount) showDeleteDialog = false }
                            if (isDeletingAccount) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), color = Amber, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text(stringResource(R.string.settings_delete_account_deleting), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                Text(stringResource(R.string.tv_settings_delete_account_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                ) { Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                    upFocusRequester = categoryUpRequester(TvSettingsCategory.ACCOUNT),
                    onContentFocused = {
                        onSettingsRowFocused(authEmailTarget, requester)
                    },
                    onExpandToggle = { expandedInput = if (expandedInput == it) null else it },
                    onValueChange = { authEmail = it },
                    onEditRequested = { authEntryStep = TvAccountEntryStep.EMAIL },
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
                    onEditRequested = { authEntryStep = if (authEmail.isBlank()) TvAccountEntryStep.EMAIL else TvAccountEntryStep.PASSWORD },
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
                        onEditRequested = { authEntryStep = TvAccountEntryStep.CONFIRM_PASSWORD },
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
                    onClick = ::submitAuth,
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
                        text = localizedUiText(error),
                        color = Ruby,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        }

        if (selectedCategory == TvSettingsCategory.ACCOUNT && !com.torve.android.BuildConfig.HAS_BILLING) {
            item(key = "account_access_free") {
                TvStatusSummaryCard(
                    title = localizedUiText("Product access: Free"),
                    message = localizedUiText("Torve has no subscription or paid feature tier. All product features are available."),
                    tone = PurchaseStatusTone.INFO,
                )
            }
        }

        // Legacy store purchase recovery. No release flavor currently enables billing.

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
                        title = localizedUiText("Manage Stripe billing"),
                        subtitle = localizedUiText("Open Stripe billing for invoices, cards, and cancellation."),
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
                    val requester = rememberSettingsRowRequester(
                        target = connectionsTraktTarget,
                        externalRequester = traktCardRequester,
                    )
                    TvSettingCard(
                        title = traktLabel,
                        needsAttention = attentionUi.entries.any { it.category == ProviderHealthCategory.TRAKT },
                        subtitle = traktSubtitle,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(connectionsTraktTarget, requester) },
                        onClick = {
                            when {
                                settingsState.traktSyncing -> {
                                    TvNotificationQueue.post("Trakt sync is already in progress", NotificationType.INFO)
                                }
                                settingsState.isPollingTrakt -> {
                                    TvNotificationQueue.post("Waiting for Trakt authorization", NotificationType.INFO)
                                }
                                settingsState.traktConnected -> {
                                    if (traktNeedsReauthorization) {
                                        TvNotificationQueue.post("Starting Trakt reauthorization", NotificationType.INFO)
                                        settingsViewModel.startTraktDeviceAuth()
                                    } else {
                                        TvNotificationQueue.post("Checking Trakt connection...", NotificationType.INFO)
                                        settingsViewModel.syncTraktNow()
                                    }
                                }
                                else -> {
                                    TvNotificationQueue.post("Starting Trakt authorization", NotificationType.INFO)
                                    settingsViewModel.startTraktDeviceAuth()
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                        emphasis = TvSettingEmphasis.SECONDARY,
                        focusedHint = when {
                            settingsState.traktSyncing -> "Syncing Trakt. You can keep browsing."
                            !settingsState.traktConnected -> "Press OK to authorize Trakt."
                            traktNeedsReauthorization -> "Press OK to reconnect and sync Trakt."
                            settingsState.traktSyncSuccess -> "Trakt synced. Press OK to sync Trakt only."
                            else -> "Press OK to sync Trakt only."
                        },
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
                        needsAttention = attentionUi.entries.any { it.category == ProviderHealthCategory.SIMKL },
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
                            title = localizedUiText("Connect SIMKL"),
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
                            up = categoryUpRequester(TvSettingsCategory.ADVANCED)
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
                    // ── Debrid management ──
                    if (false) {
                    item(key = "section_debrid") {
                        TvSectionHeader(text = localizedUiText("Cloud Services (Debrid)"))
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
                                        color = MaterialTheme.colorScheme.onSurface,
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
                                    Text(providerLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    if (!username.isNullOrBlank()) {
                                        Text(username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        needsAttention = attentionUi.entries.any { it.category == ProviderHealthCategory.TRAKT },
                        subtitle = traktSubtitle,
                        modifier = Modifier.fillMaxWidth().tvSettingsRowNavigation(
                            onUp = { focusConnectionRow(receiveCredentialsTarget, receiveCredentialsCardRequester) },
                            onDown = if (settingsState.traktConnected) {
                                { focusConnectionRow(connectionsTraktReconnectTarget, traktReconnectCardRequester) }
                            } else null,
                        ).focusProperties {
                            left = railFocusRequester
                            if (settingsState.traktConnected) {
                                down = traktReconnectCardRequester
                            }
                        },
                        focusRequester = requester,
                        onFocused = { onSettingsRowFocused(connectionsTraktTarget, requester) },
                        onClick = {
                            when {
                                settingsState.traktSyncing -> {
                                    TvNotificationQueue.post("Trakt sync is already in progress", NotificationType.INFO)
                                }
                                settingsState.isPollingTrakt -> {
                                    TvNotificationQueue.post("Waiting for Trakt authorization", NotificationType.INFO)
                                }
                                settingsState.traktConnected -> {
                                    if (traktNeedsReauthorization) {
                                        TvNotificationQueue.post("Starting Trakt reauthorization", NotificationType.INFO)
                                        settingsViewModel.startTraktDeviceAuth()
                                    } else {
                                        TvNotificationQueue.post("Checking Trakt connection...", NotificationType.INFO)
                                        settingsViewModel.syncTraktNow()
                                    }
                                }
                                else -> {
                                    TvNotificationQueue.post("Starting Trakt authorization", NotificationType.INFO)
                                    settingsViewModel.startTraktDeviceAuth()
                                }
                            }
                        },
                        rowType = TvSettingRowType.ACTION,
                        focusedHint = when {
                            settingsState.traktSyncing -> "Syncing Trakt. You can keep browsing."
                            !settingsState.traktConnected -> "Press OK to authorize Trakt."
                            traktNeedsReauthorization -> "Press OK to reconnect and sync Trakt."
                            settingsState.traktSyncSuccess -> "Trakt synced. Press OK to sync Trakt only."
                            else -> "Press OK to sync Trakt only."
                        },
                    )
                }

                if (settingsState.traktConnected) {
                    item(key = "trakt_reconnect") {
                        val requester = rememberSettingsRowRequester(
                            target = connectionsTraktReconnectTarget,
                            externalRequester = traktReconnectCardRequester,
                        )
                        TvSettingCard(
                            title = localizedUiText("Reconnect Trakt"),
                            subtitle = localizedUiText("Start a fresh Trakt device authorization."),
                            modifier = Modifier.fillMaxWidth().focusProperties {
                                left = railFocusRequester
                                up = traktCardRequester
                                down = traktDisconnectCardRequester
                            },
                            focusRequester = requester,
                            onFocused = { onSettingsRowFocused(connectionsTraktReconnectTarget, requester) },
                            onClick = {
                                if (settingsState.traktLoading || settingsState.isPollingTrakt) {
                                    TvNotificationQueue.post("Waiting for Trakt authorization", NotificationType.INFO)
                                } else {
                                    TvNotificationQueue.post("Starting Trakt reauthorization", NotificationType.INFO)
                                    settingsViewModel.startTraktDeviceAuth()
                                }
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
                            title = localizedUiText("Disconnect Trakt"),
                            subtitle = localizedUiText("Clear the Trakt connection on this TV."),
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
                            title = localizedUiText("Connect Trakt"),
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
                        needsAttention = attentionUi.entries.any { it.category == ProviderHealthCategory.SIMKL },
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
                            title = localizedUiText("Disconnect SIMKL"),
                            subtitle = localizedUiText("Clear the SIMKL connection on this TV."),
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
                            title = localizedUiText("Connect SIMKL"),
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
                            text = localizedUiText("Extensions"),
                            description = localizedUiText("Manage the providers you choose using their manifest or collection links."),
                        )
                    }
                    item(key = "advanced_panda_hub") {
                        val requester = rememberSettingsRowRequester(
                            target = advancedPandaTarget,
                            externalRequester = advancedPandaCardRequester,
                            isDefaultEntry = true,
                        )
                        val subtitle = "Add from URL, optional TMDB catalogue and metadata enrichment"
                        TvSettingCard(
                            title = localizedUiText("Manage extensions"),
                            needsAttention = attentionUi.entries.any { providerAttentionDestination(it) == TvProviderAttentionDestination.STREAMING_SETUP },
                            subtitle = subtitle,
                            modifier = Modifier.fillMaxWidth().focusProperties {
                                left = railFocusRequester
                                up = categoryUpRequester(TvSettingsCategory.ADVANCED)
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
                                "$providerLabel connected as $it"
                            } ?: "$providerLabel connected"
                            debridError != null -> stringResource(
                                R.string.tv_settings_realdebrid_managed_reconnect,
                                debridError,
                            )
                            else -> "Connect Real-Debrid, AllDebrid, Premiumize, or TorBox"
                        }
                        TvSettingCard(
                            title = localizedUiText("Debrid services"),
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
                                    text = localizedUiText("Connect a Debrid service"),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
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
                                    color = MaterialTheme.colorScheme.onSurface,
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        needsAttention = attentionUi.entries.any { providerAttentionDestination(it) == TvProviderAttentionDestination.JELLYFIN },
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
                        onClick = { settingsViewModel.saveAndTestJellyfinConnection() },
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
                        needsAttention = attentionUi.entries.any { providerAttentionDestination(it) == TvProviderAttentionDestination.PLEX },
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
                        onClick = { settingsViewModel.saveAndConnectPlex() },
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

                item(key = "mdblist_manage") {
                    val target = remember { TvSettingsFocusTarget("settings/advanced/mdblist/manage", TvSettingsCategory.ADVANCED, 250, "action") }
                    val requester = rememberSettingsRowRequester(target = target, externalRequester = remember { FocusRequester() })
                    TvSettingCard(title = localizedUiText("Manage MDBList lists"), subtitle = localizedUiText("My lists, search, Home visibility and removal"),
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester, onFocused = { onSettingsRowFocused(target, requester) },
                        onClick = onMdbListManageClick, rowType = TvSettingRowType.ACTION)
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
                        up = categoryUpRequester(TvSettingsCategory.LIBRARY)
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
                            up = categoryUpRequester(TvSettingsCategory.LIBRARY)
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
                            text = localizedUiText(message),
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
            item(key = "refresh_channels_now") {
                val requester = rememberSettingsRowRequester(
                    target = libraryRefreshChannelsTarget,
                    externalRequester = remember("refresh_channels_now") { FocusRequester() },
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_refresh_channels_title),
                    subtitle = stringResource(R.string.tv_settings_refresh_channels_subtitle),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(libraryRefreshChannelsTarget, requester) },
                    onClick = {
                        channelsViewModel.refreshChannelsCatalog()
                        TvNotificationQueue.post(
                            context.getString(R.string.tv_settings_refresh_channels_started),
                            NotificationType.INFO,
                        )
                    },
                    rowType = TvSettingRowType.ACTION,
                )
            }

            item(key = "refresh_epg_now") {
                val requester = rememberSettingsRowRequester(
                    target = libraryRefreshEpgTarget,
                    externalRequester = remember("refresh_epg_now") { FocusRequester() },
                )
                val selectedPlaylist = channelsState.playlists.firstOrNull { it.id == channelsState.selectedPlaylistId }
                    ?: channelsState.playlists.firstOrNull()
                val subtitle = when (val epgState = channelsState.epgState) {
                    EpgState.Loading -> channelsState.epgRefreshProgress?.let {
                        it.userMessage()
                    } ?: stringResource(R.string.tv_settings_refresh_epg_loading)
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
                        itemId = TvSettingsItemIds.LIBRARY_CHANNEL_MANAGER_SHOW_ALL,
                        category = TvSettingsCategory.LIBRARY,
                        listIndex = 41,
                        focusTargetType = "action",
                    )
                }
                val hideAllTarget = remember {
                    TvSettingsFocusTarget(
                        itemId = TvSettingsItemIds.LIBRARY_CHANNEL_MANAGER_HIDE_ALL,
                        category = TvSettingsCategory.LIBRARY,
                        listIndex = 42,
                        focusTargetType = "dangerous",
                    )
                }
                val registeredShowAllRequester = rememberSettingsRowRequester(
                    target = showAllTarget,
                    externalRequester = channelManagerShowAllRequester,
                )
                val registeredHideAllRequester = rememberSettingsRowRequester(
                    target = hideAllTarget,
                    externalRequester = channelManagerHideAllRequester,
                )
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
                        focusRequester = registeredShowAllRequester,
                        onFocused = { onSettingsRowFocused(showAllTarget, registeredShowAllRequester) },
                        onClick = { channelsViewModel.showAllCategories() },
                        rowType = TvSettingRowType.ACTION,
                    )
                    TvSettingCard(
                        title = stringResource(R.string.tv_settings_hide_all),
                        subtitle = localizedUiText(if (confirmHideAllChannelGroups) {
                            "Press again to hide all channel groups"
                        } else {
                            ""
                        }),
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
                        focusRequester = registeredHideAllRequester,
                        onFocused = { onSettingsRowFocused(hideAllTarget, registeredHideAllRequester) },
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

            if (channelsState.isLoadingChannels) {
                item(key = "channel_mgr_refreshing") {
                    Text(
                        text = localizedUiText("Refreshing channel groups..."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            if (channelsState.allCategories.isEmpty()) {
                item(key = "channel_mgr_loading") {
                    Text(
                        text = localizedUiText(if (channelsState.isLoadingChannels) "Loading channel groups..." else "No channel groups found. Navigate to the Channels tab first to load your sources."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        subtitle = localizedUiText(if (isExpanded) "Press to collapse" else "${cats.size} groups — press to expand"),
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
                            title = localizedUiText(if (allHiddenInCountry) "Show all $country" else "Hide all $country"),
                            subtitle = localizedUiText("$visibleInCountry / ${cats.size} visible"),
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
                            subtitle = localizedUiText(if (isHidden) {
                                stringResource(R.string.tv_live_hidden)
                            } else {
                                "${cat.channelCount} channels"
                            }),
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
                                text = localizedUiText(label),
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
                            text = localizedUiText(error),
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

        item(key = "playback_preset") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                settingsFocusController, playbackRowTargets.getValue("playbackPresetTarget"), playbackPresetCardRequester,
                isDefaultEntry = true,
            )
            val preferences = settingsViewModel.buildStreamPreferences()
            val preset = com.torve.domain.model.PlaybackPreset.recognize(preferences)
            TvSettingCard(
                title = localizedUiText("Playback preset"),
                subtitle = "${preset.label} · ${preset.description}",
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(playbackRowTargets.getValue("playbackPresetTarget"), requester) },
                onClick = {
                    settingsFocusController.captureOrigin(playbackRowTargets.getValue("playbackPresetTarget").itemId, settingsListState, "preset_open")
                    settingsOverlayState = TvSettingsOverlayState.PlaybackPresets(playbackRowTargets.getValue("playbackPresetTarget").itemId)
                },
                rowType = TvSettingRowType.SELECTOR,
            )
        }

        item(key = "max_quality") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = playbackRowTargets.getValue("maxQualityTarget"),
                externalRequester = maxQualityCardRequester,
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_max_quality),
                subtitle = settingsState.maxQuality.label,
                modifier = Modifier.fillMaxWidth().focusProperties {
                    left = railFocusRequester
                },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(playbackRowTargets.getValue("maxQualityTarget"), requester) },
                onClick = {
                    logSettingsFocus("launch_selector category=PLAYBACK row=PLAYBACK item=max_quality reason=selector_open")
                    settingsFocusController.captureOrigin(
                        itemId = playbackRowTargets.getValue("maxQualityTarget").itemId,
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
                target = playbackRowTargets.getValue("minQualityTarget"),
                externalRequester = minQualityCardRequester,
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_min_quality),
                subtitle = settingsState.minQuality.label,
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(playbackRowTargets.getValue("minQualityTarget"), requester) },
                onClick = {
                    logSettingsFocus("launch_selector category=PLAYBACK row=PLAYBACK item=min_quality reason=selector_open")
                    settingsFocusController.captureOrigin(
                        itemId = playbackRowTargets.getValue("minQualityTarget").itemId,
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
                target = playbackRowTargets.getValue("autoplayTarget"),
                externalRequester = remember("autoplay") { FocusRequester() },
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_autoplay),
                subtitle = if (settingsState.autoPlayEnabled) stringResource(R.string.tv_settings_autoplay_on)
                           else stringResource(R.string.tv_settings_autoplay_off),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(playbackRowTargets.getValue("autoplayTarget"), requester) },
                onClick = { settingsViewModel.setAutoPlayEnabled(!settingsState.autoPlayEnabled) },
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        item(key = "autoplay_next") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = playbackRowTargets.getValue("autoplayNextTarget"),
                externalRequester = remember("autoplay_next") { FocusRequester() },
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_autoplay_next),
                subtitle = localizedUiText(when (settingsState.nextEpisodeMode) {
                    NextEpisodeMode.AT_END -> "At the actual end"
                    NextEpisodeMode.AT_CREDITS -> "During the final credits"
                    NextEpisodeMode.OFF -> stringResource(R.string.tv_settings_autoplay_next_off)
                }),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(playbackRowTargets.getValue("autoplayNextTarget"), requester) },
                onClick = {
                    val next = when (settingsState.nextEpisodeMode) {
                        NextEpisodeMode.AT_END -> NextEpisodeMode.AT_CREDITS
                        NextEpisodeMode.AT_CREDITS -> NextEpisodeMode.OFF
                        NextEpisodeMode.OFF -> NextEpisodeMode.AT_END
                    }
                    settingsViewModel.setNextEpisodeMode(next)
                },
                rowType = TvSettingRowType.SELECTOR,
            )
        }

        item(key = "next_episode_preparation") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = nextEpisodePreparationTarget,
                externalRequester = remember("next_episode_preparation") { FocusRequester() },
            )
            TvSettingCard(
                title = localizedUiText("Prepare next episode"),
                subtitle = localizedUiText(when (settingsState.nextEpisodePreparationMode) {
                    NextEpisodePreparationMode.OFF -> "Off"
                    NextEpisodePreparationMode.RESOLVE_ONLY -> "Resolve source early"
                    NextEpisodePreparationMode.RESOLVE_AND_BUFFER -> "Resolve and warm buffer"
                }),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(nextEpisodePreparationTarget, requester) },
                onClick = {
                    val next = when (settingsState.nextEpisodePreparationMode) {
                        NextEpisodePreparationMode.OFF -> NextEpisodePreparationMode.RESOLVE_ONLY
                        NextEpisodePreparationMode.RESOLVE_ONLY -> NextEpisodePreparationMode.RESOLVE_AND_BUFFER
                        NextEpisodePreparationMode.RESOLVE_AND_BUFFER -> NextEpisodePreparationMode.OFF
                    }
                    settingsViewModel.setNextEpisodePreparationMode(next)
                },
                rowType = TvSettingRowType.SELECTOR,
            )
        }

        item(key = "skip_intro_mode") {
            val requester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, skipIntroTarget, remember("skip_intro_mode") { FocusRequester() })
            TvSettingCard(
                title = stringResource(R.string.tv_settings_skip_intros),
                subtitle = settingsState.skipIntroMode.tvLabel(),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(skipIntroTarget, requester) },
                onClick = { settingsViewModel.setSkipIntroMode(settingsState.skipIntroMode.next()) },
                rowType = TvSettingRowType.SELECTOR,
            )
        }

        item(key = "skip_recap_mode") {
            val requester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, skipRecapTarget, remember("skip_recap_mode") { FocusRequester() })
            TvSettingCard(
                title = stringResource(R.string.tv_settings_skip_recaps),
                subtitle = settingsState.skipRecapMode.tvLabel(),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(skipRecapTarget, requester) },
                onClick = { settingsViewModel.setSkipRecapMode(settingsState.skipRecapMode.next()) },
                rowType = TvSettingRowType.SELECTOR,
            )
        }

        item(key = "play_next_credits_mode") {
            val requester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, playNextCreditsTarget, remember("play_next_credits_mode") { FocusRequester() })
            TvSettingCard(
                title = stringResource(R.string.tv_settings_play_next_credits),
                subtitle = settingsState.playNextDuringCreditsMode.tvLabel(),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(playNextCreditsTarget, requester) },
                onClick = { settingsViewModel.setPlayNextDuringCreditsMode(settingsState.playNextDuringCreditsMode.next()) },
                rowType = TvSettingRowType.SELECTOR,
            )
        }

        item(key = "protect_post_credit") {
            val requester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, protectPostCreditTarget, remember("protect_post_credit") { FocusRequester() })
            TvSettingCard(
                title = stringResource(R.string.tv_settings_protect_post_credit),
                subtitle = localizedUiText(if (settingsState.protectPostCreditScenes) "On" else "Off"),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(protectPostCreditTarget, requester) },
                onClick = { settingsViewModel.setProtectPostCreditScenes(!settingsState.protectPostCreditScenes) },
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        item(key = "smart_segment_detection") {
            val requester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, smartSegmentsTarget, remember("smart_segment_detection") { FocusRequester() })
            TvSettingCard(
                title = stringResource(R.string.tv_settings_smart_segment_detection),
                subtitle = localizedUiText(if (settingsState.smartSegmentDetectionEnabled) "On" else "Off"),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(smartSegmentsTarget, requester) },
                onClick = { settingsViewModel.setSmartSegmentDetectionEnabled(!settingsState.smartSegmentDetectionEnabled) },
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        item(key = "minimum_source_size") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = minimumSourceSizeTarget,
                externalRequester = remember("minimum_source_size") { FocusRequester() },
            )
            TvSettingCard(
                title = localizedUiText("Minimum source size"),
                subtitle = localizedUiText(if (settingsState.minSourceSizePerHourMb <= 0) {
                    "No minimum"
                } else {
                    "${settingsState.minSourceSizePerHourMb} MB per hour"
                }),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(minimumSourceSizeTarget, requester) },
                onClick = {
                    val next = when (settingsState.minSourceSizePerHourMb) {
                        in Int.MIN_VALUE..0 -> 1024
                        in 1..1024 -> 2048
                        in 1025..2048 -> 3072
                        else -> 0
                    }
                    settingsViewModel.setMinSourceSizePerHourMb(next)
                },
                rowType = TvSettingRowType.SELECTOR,
            )
        }

        // Deduplicate Streams toggle
        item(key = "section_playback_streams") {
            TvSectionHeader(text = stringResource(R.string.tv_settings_section_stream_handling))
        }
        item(key = "dedupe") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = playbackRowTargets.getValue("dedupeTarget"),
                externalRequester = remember("dedupe") { FocusRequester() },
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvSettingCard(
                title = stringResource(R.string.tv_settings_dedupe),
                subtitle = if (settingsState.dedupeResults) stringResource(R.string.tv_settings_dedupe_on)
                           else stringResource(R.string.tv_settings_dedupe_off),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onSettingsRowFocused(playbackRowTargets.getValue("dedupeTarget"), requester) },
                onClick = { settingsViewModel.setDedupeResultsEnabled(!settingsState.dedupeResults) },
                rowType = TvSettingRowType.TOGGLE,
            )
                        val hideWatchedTarget = remember { TvSettingsFocusTarget("upgrade_hideWatched", TvSettingsCategory.PLAYBACK, dedupeTarget.listIndex, "toggle") }
                val hideWatchedRequester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, hideWatchedTarget, remember { FocusRequester() })
                TvSettingCard(title = stringResource(R.string.upgrade_hideWatched),
                    subtitle = (if (settingsState.hideWatched) stringResource(R.string.common_on) else stringResource(R.string.common_off)) + " · " + stringResource(R.string.upgrade_hideWatched_desc),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = hideWatchedRequester,
                    onFocused = { onSettingsRowFocused(hideWatchedTarget, hideWatchedRequester) },
                    onClick = { settingsViewModel.setHideWatched(!settingsState.hideWatched) },
                    rowType = TvSettingRowType.TOGGLE)
                        val excludeSeasonPacksTarget = remember { TvSettingsFocusTarget("upgrade_excludeSeasonPacks", TvSettingsCategory.PLAYBACK, dedupeTarget.listIndex, "toggle") }
                val excludeSeasonPacksRequester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, excludeSeasonPacksTarget, remember { FocusRequester() })
                TvSettingCard(title = stringResource(R.string.upgrade_excludeSeasonPacks),
                    subtitle = (if (settingsState.excludeSeasonPacks) stringResource(R.string.common_on) else stringResource(R.string.common_off)) + " · " + stringResource(R.string.upgrade_excludeSeasonPacks_desc),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = excludeSeasonPacksRequester,
                    onFocused = { onSettingsRowFocused(excludeSeasonPacksTarget, excludeSeasonPacksRequester) },
                    onClick = { settingsViewModel.setExcludeSeasonPacks(!settingsState.excludeSeasonPacks) },
                    rowType = TvSettingRowType.TOGGLE)
                        val matchFrameRateTarget = remember { TvSettingsFocusTarget("upgrade_matchFrameRate", TvSettingsCategory.PLAYBACK, dedupeTarget.listIndex, "toggle") }
                val matchFrameRateRequester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, matchFrameRateTarget, remember { FocusRequester() })
                TvSettingCard(title = stringResource(R.string.upgrade_matchFrameRate),
                    subtitle = (if (settingsState.matchFrameRate) stringResource(R.string.common_on) else stringResource(R.string.common_off)) + " · " + stringResource(R.string.upgrade_matchFrameRate_desc),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = matchFrameRateRequester,
                    onFocused = { onSettingsRowFocused(matchFrameRateTarget, matchFrameRateRequester) },
                    onClick = { settingsViewModel.setMatchFrameRate(!settingsState.matchFrameRate) },
                    rowType = TvSettingRowType.TOGGLE)
                        val keepSubtitlesAboveControlsTarget = remember { TvSettingsFocusTarget("upgrade_keepSubtitlesAboveControls", TvSettingsCategory.PLAYBACK, dedupeTarget.listIndex, "toggle") }
                val keepSubtitlesAboveControlsRequester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, keepSubtitlesAboveControlsTarget, remember { FocusRequester() })
                TvSettingCard(title = stringResource(R.string.upgrade_keepSubtitlesAboveControls),
                    subtitle = (if (settingsState.keepSubtitlesAboveControls) stringResource(R.string.common_on) else stringResource(R.string.common_off)) + " · " + stringResource(R.string.upgrade_keepSubtitlesAboveControls_desc),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = keepSubtitlesAboveControlsRequester,
                    onFocused = { onSettingsRowFocused(keepSubtitlesAboveControlsTarget, keepSubtitlesAboveControlsRequester) },
                    onClick = { settingsViewModel.setKeepSubtitlesAboveControls(!settingsState.keepSubtitlesAboveControls) },
                    rowType = TvSettingRowType.TOGGLE)
                    }
        }

        // ── Language & Region section ──
        }

        if (selectedCategory == TvSettingsCategory.PLAYBACK) {
        item(key = "section_playback_preferences") {
            TvSectionHeader(text = stringResource(R.string.tv_settings_section_playback_preferences))
        }

        item(key = "playback_additional_preferences") {
            var choice by remember { mutableStateOf<TvSettingsChoice?>(null) }
            choice?.let { TvSettingsChoiceDialog(it) { choice = null } }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                @Composable fun setting(id: String, title: String, value: String, action: () -> Unit) {
                    val target = remember(id) { TvSettingsFocusTarget(id, TvSettingsCategory.PLAYBACK, 260, "selector") }
                    val requester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, target, remember(id) { FocusRequester() })
                    TvSettingCard(title = title, subtitle = value,
                        modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                        focusRequester = requester, onFocused = { onSettingsRowFocused(target, requester) }, onClick = action)
                }
                var size by remember(settingsState.maxFileSizeMb) { mutableStateOf(settingsState.maxFileSizeMb?.let { (it / 1024.0).toString() }.orEmpty()) }
                val sizeTarget = remember { TvSettingsFocusTarget("playback_file_size", TvSettingsCategory.PLAYBACK, 260, "input") }
                TvTextInputCard(key = "playback_file_size", title = localizedUiText("Maximum file size (GB)"), value = size,
                    expandedInput = expandedInput, railFocusRequester = railFocusRequester, onContentFocused = onContentFocused,
                    onExpandToggle = { expandedInput = if (expandedInput == it) null else it }, onValueChange = { size = it },
                    settingsFocusController = settingsFocusController, focusTarget = sizeTarget)
                val mb = size.replace(',', '.').toDoubleOrNull()?.times(1024)?.takeIf { it.isFinite() && it >= 1 && it <= Int.MAX_VALUE }?.toInt()
                setting(localizedUiText("playback_save_file_size"), localizedUiText("Save file size limit"), if (size.isBlank()) "No limit" else if (mb == null) "Enter a valid size in GB" else "$size GB") {
                    if (size.isBlank() || mb != null) settingsViewModel.setMaxFileSizeMb(mb)
                }
                val languages = listOf("" to "No preference") + com.torve.data.addon.SourceLanguages.allOptions().map { it.code to it.label }
                setting(localizedUiText("playback_subtitle_language"), localizedUiText("Preferred subtitles"), languages.firstOrNull { it.first == settingsState.preferredSubtitleLanguage }?.second ?: settingsState.preferredSubtitleLanguage) {
                    choice = TvSettingsChoice("Preferred subtitles", settingsState.preferredSubtitleLanguage, languages, settingsViewModel::setPreferredSubtitleLanguage)
                }
                setting(localizedUiText("playback_subtitles_default"), localizedUiText("Subtitles on by default"), if (settingsState.subtitlesEnabledByDefault) "On" else "Off") {
                    settingsViewModel.setSubtitlesEnabledByDefault(!settingsState.subtitlesEnabledByDefault)
                }
                val inspector = koinInject<com.torve.android.player.AndroidSourceTrackInspector>()
                var scan by remember(settingsState.personalDataUserId) { mutableStateOf(inspector.scanningEnabled()) }
                setting(localizedUiText("playback_audio_scan"), localizedUiText("Scan source audio"), if (scan) "On" else "Off") { scan = !scan; inspector.setScanningEnabled(scan) }
            }
        }

        item(key = "playback_audio_language") {
            val target = playbackRowTargets.getValue("audioLanguage")
            val requester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, target,
                remember { FocusRequester() })
            val selected = settingsState.preferredAudioLanguage
            val effective = com.torve.domain.player.PlaybackLanguagePolicy.preferredLanguage(selected, settingsState.appLanguage.code)
            TvSettingCard(title = stringResource(R.string.playback_audio_language),
                subtitle = localizedUiText(when (selected) {
                    "", "system" -> stringResource(R.string.playback_language_interface)
                    "any" -> stringResource(R.string.source_language_any)
                    else -> com.torve.data.addon.SourceLanguages.allOptions().firstOrNull { it.code == effective }?.label ?: effective
                }),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester, onFocused = { onSettingsRowFocused(target, requester) },
                onClick = {
                    settingsFocusController.captureOrigin(target.itemId, settingsListState, "audio_language_open")
                    settingsOverlayState = TvSettingsOverlayState.AudioLanguage(target.itemId)
                }, rowType = TvSettingRowType.SELECTOR)
        }
        item(key = "playback_subtitle_fallback") {
            val target = playbackRowTargets.getValue("subtitleFallback")
            val requester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, target,
                remember { FocusRequester() })
            TvSettingCard(title = stringResource(R.string.playback_language_subtitle_fallback),
                subtitle = localizedUiText((if (settingsState.allowSubtitleLanguageFallback) "On · " else "Off · ") + stringResource(R.string.playback_language_subtitle_description)),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester, onFocused = { onSettingsRowFocused(target, requester) },
                onClick = { settingsViewModel.setAllowSubtitleLanguageFallback(!settingsState.allowSubtitleLanguageFallback) },
                rowType = TvSettingRowType.TOGGLE)
        }

        item(key = "playback_audio_mode") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = playbackRowTargets.getValue("playbackAudioModeTarget"),
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
                onFocused = { onSettingsRowFocused(playbackRowTargets.getValue("playbackAudioModeTarget"), requester) },
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
                target = playbackRowTargets.getValue("playbackAudioPassthroughTarget"),
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
                onFocused = { onSettingsRowFocused(playbackRowTargets.getValue("playbackAudioPassthroughTarget"), requester) },
                onClick = {
                    channelsViewModel.setAudioPassthroughEnabled(!channelsState.audioPassthroughEnabled)
                },
                rowType = TvSettingRowType.TOGGLE,
            )
        }

        item(key = "playback_audio_surround") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = playbackRowTargets.getValue("playbackAudioSurroundTarget"),
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
                onFocused = { onSettingsRowFocused(playbackRowTargets.getValue("playbackAudioSurroundTarget"), requester) },
                onClick = {
                    channelsViewModel.setPreferSurroundCodecs(!channelsState.preferSurroundCodecs)
                },
                rowType = TvSettingRowType.TOGGLE,
            )
        }
        }

        if (selectedCategory == TvSettingsCategory.APPEARANCE) {
            item(key = "community_appearance") {
                val target = remember { TvSettingsFocusTarget("settings/appearance/background_rails", TvSettingsCategory.APPEARANCE, -2, "action") }
                val requester = rememberRegisteredTvSettingsFocusRequester(controller = settingsFocusController, target = target,
                    externalRequester = communityAppearanceRequester, isDefaultEntry = true)
                TvSettingCard(title = localizedUiText("App background & rail spacing"), subtitle = localizedUiText("Appearance across all pages"),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester; up = categoryUpRequester(TvSettingsCategory.APPEARANCE); down = savedCollectionsRequester },
                    focusRequester = requester, onFocused = { onSettingsRowFocused(target, requester) },
                    onClick = onCommunityAppearanceClick, rowType = TvSettingRowType.ACTION)
            }
            item(key = "community_collections") {
                val target = remember { TvSettingsFocusTarget("settings/appearance/saved_collections", TvSettingsCategory.APPEARANCE, -1, "action") }
                val requester = rememberRegisteredTvSettingsFocusRequester(controller = settingsFocusController, target = target, externalRequester = savedCollectionsRequester)
                TvSettingCard(title = localizedUiText("Saved collections"), subtitle = localizedUiText("Franchises and community lists"),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester; up = communityAppearanceRequester; down = reduceMotionCardRequester },
                    focusRequester = requester, onFocused = { onSettingsRowFocused(target, requester) },
                    onClick = onSavedCollectionsClick, rowType = TvSettingRowType.ACTION)
            }

            item(key = "appearance_theme") {
                val target = remember { TvSettingsFocusTarget("appearance_theme", TvSettingsCategory.APPEARANCE, -3, "selector") }
                val requester = rememberRegisteredTvSettingsFocusRequester(settingsFocusController, target, remember { FocusRequester() })
                TvSettingCard(title = localizedUiText("Theme"), subtitle = settingsState.themeMode.name.lowercase().replaceFirstChar { it.uppercase() },
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester, onFocused = { onSettingsRowFocused(target, requester) },
                    onClick = { val modes = com.torve.presentation.settings.ThemeMode.entries; settingsViewModel.setThemeMode(modes[(modes.indexOf(settingsState.themeMode) + 1) % modes.size]) })
            }
            item(key = "section_appearance_display") {
                TvSectionHeader(text = stringResource(R.string.tv_settings_section_display))
            }
            item(key = "reduce_motion") {
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = settingsFocusController,
                target = reduceMotionTarget,
                externalRequester = reduceMotionCardRequester,
                isDefaultEntry = false,
            )
            TvSettingCard(
                title = stringResource(R.string.tv_settings_reduce_motion),
                subtitle = if (reduceMotionEnabled) stringResource(R.string.common_on) else stringResource(R.string.common_off),
                modifier = Modifier.fillMaxWidth().focusProperties {
                    left = railFocusRequester
                    up = savedCollectionsRequester
                    down = optionalFeatureRequesters.getValue(com.torve.presentation.settings.OptionalAppFeature.JELLYFIN)
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

        item(key = "section_optional_features") {
            TvSectionHeader(text = localizedUiText("App features"), description = localizedUiText("Turn off features you don’t use. Saved connections are kept."))
        }
        com.torve.presentation.settings.OptionalAppFeature.availableEntries.forEachIndexed { index, feature ->
            item(key = feature.preferenceKey) {
                val target = remember(feature) {
                    TvSettingsFocusTarget(feature.preferenceKey, TvSettingsCategory.APPEARANCE, 3 + index, "toggle")
                }
                val requester = rememberRegisteredTvSettingsFocusRequester(
                    controller = settingsFocusController, target = target,
                    externalRequester = optionalFeatureRequesters.getValue(feature))
                val enabled = settingsState.optionalAppFeatures.isEnabled(feature)
                val label = when (feature) {
                    com.torve.presentation.settings.OptionalAppFeature.JELLYFIN -> "Jellyfin"
                    com.torve.presentation.settings.OptionalAppFeature.SPORTS -> "Sports"
                    com.torve.presentation.settings.OptionalAppFeature.CHANNELS -> "Channels"
                    com.torve.presentation.settings.OptionalAppFeature.ARR_STACK -> "ARR stack"
                }
                TvSettingCard(title = label,
                    subtitle = localizedUiText(if (enabled) "On" else "Off · Hidden, with background checks and alerts stopped"),
                    modifier = Modifier.fillMaxWidth().focusProperties {
                        left = railFocusRequester
                        up = if (index == 0) reduceMotionCardRequester else optionalFeatureRequesters.getValue(
                            com.torve.presentation.settings.OptionalAppFeature.availableEntries[index - 1])
                        down = if (index == com.torve.presentation.settings.OptionalAppFeature.availableEntries.lastIndex) languageCardRequester else optionalFeatureRequesters.getValue(
                            com.torve.presentation.settings.OptionalAppFeature.availableEntries[index + 1])
                    }, focusRequester = requester, onFocused = { onSettingsRowFocused(target, requester) },
                    onClick = {
                        if (settingsState.optionalAppFeaturesLoaded) settingsViewModel.setOptionalAppFeature(feature, !enabled)
                    }, rowType = TvSettingRowType.TOGGLE)
            }
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
                    up = optionalFeatureRequesters.getValue(com.torve.presentation.settings.OptionalAppFeature.ARR_STACK)
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
                    subtitle = localizedUiText(when {
                        confirmEnableDiagnostics -> "Press again to enable diagnostics panel"
                        showDebugPanel -> "Visible"
                        else -> "Hidden"
                    }),
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
                    up = categoryUpRequester(TvSettingsCategory.ABOUT)
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
                PackageInfoCompat.getLongVersionCode(
                    context.packageManager.getPackageInfo(context.packageName, 0),
                )
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

        if (usesVpsReleaseUpdates(com.torve.android.BuildConfig.FLAVOR)) {
            item(key = "about_check_for_updates") {
                val checkScope = rememberCoroutineScope()
                var checkState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
                val requester = rememberSettingsRowRequester(
                    target = aboutCheckForUpdatesTarget,
                    externalRequester = remember("about_check_for_updates") { FocusRequester() },
                )
                TvSettingCard(
                    title = stringResource(R.string.tv_settings_check_for_updates),
                    subtitle = when (val s = checkState) {
                        UpdateCheckState.Idle -> stringResource(R.string.tv_settings_check_for_updates_desc)
                        UpdateCheckState.Checking -> stringResource(R.string.tv_settings_check_for_updates_checking)
                        UpdateCheckState.UpToDate -> stringResource(R.string.tv_settings_check_for_updates_up_to_date)
                        UpdateCheckState.Error -> stringResource(R.string.tv_settings_check_for_updates_failed)
                        is UpdateCheckState.UpdateAvailable ->
                            stringResource(R.string.tv_settings_check_for_updates_available, s.update.version)
                    },
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onSettingsRowFocused(aboutCheckForUpdatesTarget, requester) },
                    onClick = {
                        when (val s = checkState) {
                            is UpdateCheckState.UpdateAvailable -> {
                                settingsFocusController.captureOrigin(
                                    itemId = TvSettingsItemIds.ABOUT_CHECK_FOR_UPDATES,
                                    outerListState = settingsListState,
                                    reason = "app_update_launch",
                                )
                                appUpdateLauncher.launch(AppUpdateActivity.createIntent(context, s.update))
                            }
                            UpdateCheckState.Checking -> Unit
                            else -> {
                                checkState = UpdateCheckState.Checking
                                checkScope.launch {
                                    checkState = try {
                                        val update = AppUpdateChecker.checkForUpdate()
                                        if (update != null) {
                                            UpdateCheckState.UpdateAvailable(update)
                                        } else {
                                            UpdateCheckState.UpToDate
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        Log.w("TorveUpdate", "Manual update check failed", error)
                                        UpdateCheckState.Error
                                    }
                                }
                            }
                        }
                    },
                    rowType = if (checkState is UpdateCheckState.UpdateAvailable) {
                        TvSettingRowType.NAVIGATION
                    } else {
                        TvSettingRowType.ACTION
                    },
                )
            }
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
                    settingsOverlayState = TvSettingsOverlayState.Support(originItemId = aboutSupportTarget.itemId)
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
                    settingsOverlayState = TvSettingsOverlayState.Terms(originItemId = aboutTermsTarget.itemId)
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
                    settingsOverlayState = TvSettingsOverlayState.Legal(originItemId = aboutLegalTarget.itemId)
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
                text = localizedUiText("Connection health"),
                description = localizedUiText("Check the status of your configured services."),
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
                            "Manage extensions" to { onNavigateToPandaSetup() }
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
                Box {
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
                    title = localizedUiText("Device limit"),
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    }
    when (val overlay = settingsOverlayState) {
        is TvSettingsOverlayState.AudioLanguage -> {
            val choices = listOf("" to stringResource(R.string.playback_language_interface),
                "any" to stringResource(R.string.source_language_any)) +
                com.torve.data.addon.SourceLanguages.allOptions().map { it.code to "${it.label} (${it.code})" }
            TvListPickerOverlay(title = stringResource(R.string.playback_audio_language), items = choices,
                selectedKey = when (settingsState.preferredAudioLanguage) {
                    "", "system" -> ""
                    "any" -> "any"
                    else -> com.torve.domain.player.PlaybackLanguagePolicy.preferredLanguage(settingsState.preferredAudioLanguage, settingsState.appLanguage.code)
                }, onSelect = { settingsViewModel.setPreferredAudioLanguage(it); dismissSettingsOverlay() },
                onDismiss = ::dismissSettingsOverlay)
        }
        is TvSettingsOverlayState.PlaybackPresets -> {
            val preferences = settingsViewModel.buildStreamPreferences()
            TvListPickerOverlay(
                title = localizedUiText("Playback preset"),
                items = com.torve.domain.model.PlaybackPreset.entries
                    .filter { it != com.torve.domain.model.PlaybackPreset.CUSTOM && it.isAvailable(preferences) }
                    .map { it.name to it.label },
                selectedKey = com.torve.domain.model.PlaybackPreset.recognize(preferences).name,
                descriptions = com.torve.domain.model.PlaybackPreset.entries.associate { it.name to it.description },
                footer = if (com.torve.domain.model.PlaybackPreset.PREFERRED_AUDIO.isAvailable(preferences)) null else
                    "Choose an audio language in Playback settings to enable Preferred audio.",
                onSelect = { key ->
                    settingsViewModel.applyPlaybackPreset(com.torve.domain.model.PlaybackPreset.valueOf(key))
                    dismissSettingsOverlay()
                },
                onDismiss = ::dismissSettingsOverlay,
            )
        }
        is TvSettingsOverlayState.ConnectionCenter -> TvConnectionCenterDialog(
            summary = integrationReadiness,
            settings = settingsState,
            onDismiss = ::dismissSettingsOverlay,
            onAction = { action ->
                when (action) {
                    TvConnectionCenterAction.SYNC_TRAKT -> settingsViewModel.syncTraktNow()
                    TvConnectionCenterAction.CHECK_SIMKL -> settingsViewModel.checkSimklConnection()
                    TvConnectionCenterAction.OPEN_STREAMING_SETUP -> {
                        settingsOverlayState = null
                        settingsFocusController.captureOrigin(connectionCenterTarget.itemId, settingsListState, "route_open")
                        onNavigateToPandaSetup()
                    }
                    TvConnectionCenterAction.RECEIVE_CREDENTIALS -> {
                        settingsOverlayState = null
                        settingsFocusController.captureOrigin(connectionCenterTarget.itemId, settingsListState, "route_open")
                        onNavigateToReceiveCredentials()
                    }
                    TvConnectionCenterAction.OPEN_ADDONS -> {
                        settingsOverlayState = null
                        settingsFocusController.clearPendingRestore()
                        settingsFocusController.beginCategorySwitch(TvSettingsCategory.ADVANCED)
                        val addon = addonState.addons.firstOrNull()
                        pendingFocusMove = TvSettingsFocusMoveRequest(
                            target = TvSettingsFocusMoveTarget.PROVIDER_ITEM, category = TvSettingsCategory.ADVANCED,
                            nonce = ++focusMoveNonce, itemId = addon?.let { "addon_installed_${it.manifestUrl}" } ?: "addon_add_manual",
                            lazyItemKey = addon?.let { "addon_${it.manifestUrl}" } ?: "add_addon")
                    }
                    TvConnectionCenterAction.RECONNECT_TRAKT -> {
                        settingsOverlayState = null
                        settingsFocusController.clearPendingRestore()
                        focusConnectionRow(connectionsTraktTarget, traktCardRequester)
                        settingsViewModel.startTraktDeviceAuth()
                    }
                    TvConnectionCenterAction.CONNECT_SIMKL -> {
                        settingsOverlayState = null
                        settingsFocusController.clearPendingRestore()
                        focusConnectionRow(connectionsSimklTarget, simklCardRequester)
                        settingsViewModel.startSimklDeviceAuth()
                    }
                }
            },
            onOpenSettings = { workflow ->
                settingsOverlayState = null
                settingsFocusController.clearPendingRestore()
                when (workflow) {
                    com.torve.presentation.settings.IntegrationWorkflow.SAVE_TO_LIBRARY -> {
                        settingsFocusController.captureOrigin(connectionCenterTarget.itemId, settingsListState, "route_open")
                        onNavigateToAutomationConnections("seerr")
                    }
                    com.torve.presentation.settings.IntegrationWorkflow.AUTOMATION -> {
                        settingsFocusController.captureOrigin(connectionCenterTarget.itemId, settingsListState, "route_open")
                        onNavigateToAutomationAdmin()
                    }
                    com.torve.presentation.settings.IntegrationWorkflow.TRACKING -> {
                        if (com.torve.presentation.settings.integrationRepairDestination(workflow, settingsState, providerHealthEntries) == com.torve.presentation.settings.ConnectionDestination.SIMKL)
                            focusConnectionRow(connectionsSimklTarget, simklCardRequester)
                        else focusConnectionRow(connectionsTraktTarget, traktCardRequester)
                    }
                    else -> {
                        settingsFocusController.beginCategorySwitch(TvSettingsCategory.ADVANCED)
                        val destination = com.torve.presentation.settings.integrationRepairDestination(workflow, settingsState, providerHealthEntries)
                        val (itemId, lazyKey) = when (destination) {
                            com.torve.presentation.settings.ConnectionDestination.JELLYFIN -> if (phoneConnectionsMode)
                                TvSettingsItemIds.ADVANCED_PHONE_JELLYFIN to "phone_jellyfin" else TvSettingsItemIds.ADVANCED_JELLYFIN_URL to "jellyfin_url"
                            com.torve.presentation.settings.ConnectionDestination.PLEX -> if (phoneConnectionsMode)
                                TvSettingsItemIds.ADVANCED_PHONE_PLEX to "phone_plex" else TvSettingsItemIds.ADVANCED_PLEX_URL to "plex_url"
                            else -> TvSettingsItemIds.ADVANCED_REAL_DEBRID to "advanced_panda_debrid_services"
                        }
                        pendingFocusMove = TvSettingsFocusMoveRequest(target = TvSettingsFocusMoveTarget.PROVIDER_ITEM,
                            category = TvSettingsCategory.ADVANCED, nonce = ++focusMoveNonce, itemId = itemId, lazyItemKey = lazyKey)
                    }
                }
            },
        )
        is TvSettingsOverlayState.Support -> {
            val emailRequester = remember(overlay.originItemId) { FocusRequester() }
            TvDocumentModal(
                title = stringResource(R.string.tv_settings_support),
                initialFocusRequester = emailRequester,
                onDismiss = ::dismissSettingsOverlay,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tv_settings_support_modal_body),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.tv_settings_support_modal_subtext),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        is TvSettingsOverlayState.Terms,
        is TvSettingsOverlayState.Legal,
        -> {
            val documentRequester = remember(overlay.originItemId) { FocusRequester() }
            val title = when (overlay) {
                is TvSettingsOverlayState.Terms -> stringResource(R.string.tv_settings_terms)
                is TvSettingsOverlayState.Legal -> stringResource(R.string.tv_settings_legal)
            }
            val url = when (overlay) {
                is TvSettingsOverlayState.Terms -> "https://torve.app/terms.html"
                is TvSettingsOverlayState.Legal -> "https://torve.app/privacy.html"
            }
            val fallbackAsset = when (overlay) {
                is TvSettingsOverlayState.Terms -> "terms.html"
                is TvSettingsOverlayState.Legal -> "privacy.html"
            }
            val documentState = rememberTvDocumentContentState(
                url = url,
                fallbackAssetName = fallbackAsset,
            )
            TvDocumentModal(
                title = title,
                initialFocusRequester = documentRequester,
                onDismiss = ::dismissSettingsOverlay,
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
                                color = MaterialTheme.colorScheme.onSurface,
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    documentState.warningMessage?.takeIf { it.isNotBlank() }?.let { warning ->
                                        Text(
                                            text = warning,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
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
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.tv_settings_document_error_body),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = localizedUiText(documentState.message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
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
                    itemId = playbackRowTargets.getValue("maxQualityTarget").itemId,
                    reason = "confirm",
                    outerListState = settingsListState,
                )
            },
            onDismiss = {
                logSettingsFocus("selector_dismiss category=PLAYBACK row=PLAYBACK item=max_quality reason=back")
                settingsFocusController.selectedCategory = TvSettingsCategory.PLAYBACK
                showMaxQualityPicker = false
                settingsFocusController.requestRestore(
                    itemId = playbackRowTargets.getValue("maxQualityTarget").itemId,
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
                    itemId = playbackRowTargets.getValue("minQualityTarget").itemId,
                    reason = "confirm",
                    outerListState = settingsListState,
                )
            },
            onDismiss = {
                logSettingsFocus("selector_dismiss category=PLAYBACK row=PLAYBACK item=min_quality reason=back")
                settingsFocusController.selectedCategory = TvSettingsCategory.PLAYBACK
                showMinQualityPicker = false
                settingsFocusController.requestRestore(
                    itemId = playbackRowTargets.getValue("minQualityTarget").itemId,
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
            text = localizedUiText(title),
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = localizedUiText(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TvSettingsTopCategoryChip(
    title: String,
    badge: String?,
    selected: Boolean,
    isLocked: Boolean = false,
    needsAttention: Boolean = false,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onFocusStateChanged: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AmberLight
            selected -> Steel.copy(alpha = 0.65f)
            else -> Color.Transparent
        },
        label = "settingsCategoryChipBorder",
    )
    val backgroundColor = when {
        focused -> MaterialTheme.colorScheme.primary.copy(alpha = .16f)
        selected -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
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
        if (needsAttention) com.torve.android.tv.components.TvAttentionDot()
        Text(
            text = localizedUiText(title),
            style = MaterialTheme.typography.titleMedium,
            color = if (focused || selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
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
        badge?.takeIf { isLocked }?.let { value ->
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
internal fun TvListPickerOverlay(
    title: String,
    items: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    descriptions: Map<String, String> = emptyMap(),
    footer: String? = null,
) {
    BackHandler(onBack = onDismiss)
    val initialIndex = items.indexOfFirst { it.first == selectedKey }.coerceAtLeast(0)

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
                .width(if (descriptions.isEmpty()) 400.dp else 600.dp)
                .heightIn(max = 480.dp)
                .border(1.dp, Steel.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(Charcoal)
                .padding(24.dp)
                // No clickable here — it interferes with D-pad Enter on child items
                ,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = localizedUiText(title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
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

                    // Custom settings do not correspond to a preset row. Start
                    // at the first choice so the picker always owns remote focus.
                    if (index == initialIndex) {
                        LaunchedEffect(Unit) {
                            withFrameNanos { }
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
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                text = localizedUiText(label),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isSelected || focused) Snow else Silver,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            descriptions[key]?.let { description ->
                                Text(localizedUiText(description), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            footer?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)) }
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
    needsAttention: Boolean = false,
    onEditRequested: (() -> Unit)? = null,
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
                    if (onEditRequested != null) onEditRequested() else onExpandToggle(key)
                }
            },
            rowType = TvSettingRowType.NAVIGATION,
            focusedHint = if (locked) {
                "Press OK to continue."
            } else {
                "Press OK to edit this value."
            },
            premiumLocked = locked,
            needsAttention = needsAttention,
        )
        AnimatedVisibility(visible = isExpanded && !locked) {
            Column {
                TvClickToEditOutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    label = { Text(localizedUiText(title)) },
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
                        title = localizedUiText(if (passwordRevealed) "Hide value" else "Show value"),
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
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = localizedUiText(description),
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
        text = localizedUiText(label),
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
    ProviderHealthCategory.PLAYBACK,
    ProviderHealthCategory.REQUEST_MANAGER -> false
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
        "No debrid services connected. Open streaming setup to add Real-Debrid, TorBox, AllDebrid, or Premiumize."
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
internal fun TvDeviceAuthorizationPanel(
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
                text = localizedUiText(title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
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
            text = localizedUiText("1. On your phone or computer, open:"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            text = localizedUiText("2. Enter this code:"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = userCode,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Text(
            text = localizedUiText(status),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) Ruby else Silver,
        )
        if (!isError) {
            Text(
                text = localizedUiText("Torve will continue automatically after authorization."),
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
    needsAttention: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    var keyDownReceivedHere by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo.isWindowFocused) {
        // Opening a popup can send the matching OK release to its new window.
        keyDownReceivedHere = false
    }
    val isDanger = rowType == TvSettingRowType.DANGEROUS
    val settingsChrome = com.torve.android.tv.components.LocalTvSettingsStyle.current
    val scale = 1f
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
            if (premiumLocked) Color(0xFF3A3222).copy(alpha = 0.55f) else com.torve.android.ui.theme.menuPanelColor()
        }
        TvSettingEmphasis.SECONDARY -> {
            if (premiumLocked) Color(0xFF3A3222).copy(alpha = 0.4f) else com.torve.android.ui.theme.menuPanelColor().copy(alpha = .36f)
        }
    }
    val focusedBackground = when {
        isDanger -> Color(0xFF3C252B)
        premiumLocked -> Color(0xFF493E24)
        emphasis == TvSettingEmphasis.SECONDARY -> Graphite.copy(alpha = 0.42f)
        else -> com.torve.android.ui.theme.menuPanelColor(true)
    }

    Column(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .then(if (settingsChrome) Modifier.padding(4.dp).tvLiquidGlass(focused) else Modifier.background(
                color = if (focused) focusedBackground else restingBackground,
                shape = RoundedCornerShape(20.dp),
            )
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(20.dp),
            )
            )
            .padding(horizontal = 18.dp, vertical = 12.dp)
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
                        if (!keyDownReceivedHere) {
                            keyDownReceivedHere = true
                            onClick()
                        }
                        true
                    }
                    event.type == KeyEventType.KeyUp &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        keyDownReceivedHere = false
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
            if (needsAttention) com.torve.android.tv.components.TvAttentionDot(Modifier.padding(top = 8.dp))
            Text(
                text = localizedUiText(title),
                style = if (settingsChrome) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                color = when {
                    settingsChrome && isDanger -> MaterialTheme.colorScheme.error
                    settingsChrome -> MaterialTheme.colorScheme.onSurface
                    premiumLocked && focused -> AmberLight
                    focused && !isDanger -> Amber
                    else -> Snow
                },
                fontWeight = if (settingsChrome) FontWeight.Medium else FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (premiumLocked) {
                TvPremiumLockChip(focused = focused)
            }
            if (!settingsChrome) TvSettingRowTypeChip(rowType = rowType, focused = focused)
        }
        if (subtitle.isNotEmpty()) {
            Text(
                text = localizedUiText(subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = if (settingsChrome) MaterialTheme.colorScheme.onSurfaceVariant else if (focused) Snow else Silver,
                maxLines = if (needsAttention) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (focused && !settingsChrome && !hintText.isNullOrBlank()) {
            Text(
                text = hintText,
                style = MaterialTheme.typography.bodySmall,
                color = Ash,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}


