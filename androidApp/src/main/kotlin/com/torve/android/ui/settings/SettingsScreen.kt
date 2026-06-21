package com.torve.android.ui.settings

import com.torve.android.BuildConfig
import com.torve.android.ui.components.PandaSetupNudgeCard
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.torve.android.R
import com.torve.android.background.BackgroundWork
import com.torve.android.premium.AccessTier
import com.torve.android.premium.PremiumAccess
import com.torve.android.premium.PremiumFeature
import com.torve.android.session.PostSignInRefresh
import com.torve.android.util.findActivity
import com.torve.domain.model.StreamQuality
import com.torve.data.auth.AuthClient
import com.torve.android.ui.auth.VerificationBanner
import com.torve.android.ui.subscription.NeedsVerificationBanner
import com.torve.android.ui.subscription.NeedsVerificationToastEffect
import com.torve.domain.model.CodecPreference
import com.torve.domain.model.HdrMode
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.android.ui.theme.Torve
import com.torve.android.ui.transfer.RestoreSetupRecoveryCard
import com.torve.data.account.AccountSettingsRepository
import com.torve.android.sync.SyncCoordinator
import com.torve.presentation.addon.AddonViewModel
import com.torve.presentation.beta.BetaProgramCopy
import com.torve.presentation.beta.BetaProgramViewModel
import com.torve.presentation.beta.shouldShowBetaProgramSettingsEntry
import com.torve.presentation.beta.toSettingsCardState
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.settings.ThemeMode
import com.torve.presentation.session.AccountSessionCoordinator
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.subscription.accessPresentation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    accessTier: AccessTier = AccessTier.FREE,
    onLockedFeatureClick: (PremiumFeature) -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onSubscriptionClick: () -> Unit = {},
    onProfilesClick: () -> Unit = {},
    onCalendarClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onDevicesClick: () -> Unit = {},
    onSignInTvClick: () -> Unit = {},
    onManageDevicesClick: () -> Unit = {},
    onLoginClick: () -> Unit = {},
    onPrivacyPolicyClick: () -> Unit = {},
    onTermsClick: () -> Unit = {},
    onHelpClick: () -> Unit = {},
    onReportIssueClick: () -> Unit = {},
    onStreamingServicesClick: () -> Unit = {},
    onAddonCatalogClick: () -> Unit = {},
    onRegexPatternsClick: () -> Unit = {},
    onStreamGroupsClick: () -> Unit = {},
    onHomeLayoutClick: () -> Unit = {},
    onMdbListClick: () -> Unit = {},
    onSensitiveMaterialClick: () -> Unit = {},
    onRatingSettingsClick: () -> Unit = {},
    onCardStyleClick: () -> Unit = {},
    onIntegrationsClick: () -> Unit = {},
    onDiagnosticsClick: () -> Unit = {},
    onSendCredentialsClick: () -> Unit = {},
    onReceiveCredentialsClick: () -> Unit = {},
    onTransferDiagnosticsClick: () -> Unit = {},
    onStartSetupClick: () -> Unit = {},
    onSetupPandaClick: () -> Unit = {},
    onStatusRepairClick: () -> Unit = {},
    onBetaProgramClick: () -> Unit = {},
    viewModel: SettingsViewModel = koinInject(),
    syncCoordinator: SyncCoordinator = koinInject(),
    authClient: AuthClient = koinInject(),
    accountSettingsRepository: AccountSettingsRepository = koinInject(),
    accountSessionCoordinator: AccountSessionCoordinator = koinInject(),
    channelsViewModel: ChannelsViewModel = koinInject(),
    subscriptionViewModel: SubscriptionViewModel = koinInject(),
    betaProgramViewModel: BetaProgramViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val syncState by syncCoordinator.state.collectAsState()
    val accountSettingsState by accountSettingsRepository.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val betaProgramState by betaProgramViewModel.state.collectAsState()
    val subscriptionAccess = remember(
        subscriptionState.subscription,
        subscriptionState.isPro,
        subscriptionState.isLoggedIn,
        subscriptionState.hasEntitlement,
        subscriptionState.isDeviceActivated,
        subscriptionState.deviceBlockReason,
        subscriptionState.deviceCapReached,
        subscriptionState.needsVerification,
        subscriptionState.purchaseStatus,
    ) {
        val strings: com.torve.presentation.subscription.PurchaseStringResolver = org.koin.java.KoinJavaComponent.getKoin().get()
        subscriptionState.accessPresentation(strings)
    }
    val isLocked: (PremiumFeature) -> Boolean = remember(accessTier) {
        { feature -> PremiumAccess.isPremiumLocked(feature, accessTier) }
    }
    val onPremiumAction: (PremiumFeature, () -> Unit) -> Unit = remember(isLocked, onLockedFeatureClick) {
        { feature, allowedAction ->
            if (isLocked(feature)) {
                onLockedFeatureClick(feature)
            } else {
                allowedAction()
            }
        }
    }
    val deviceLinkingLocked = isLocked(PremiumFeature.DEVICE_LINKING)
    val pairingLocked = isLocked(PremiumFeature.PHONE_PAIRING)
    val customSourcesLocked = isLocked(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT)
    val addonLocked = isLocked(PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT)
    val mdbListLocked = isLocked(PremiumFeature.MDBLIST_SETUP)
    val traktLocked = isLocked(PremiumFeature.TRAKT_CONNECT)
    val diagnosticsLocked = isLocked(PremiumFeature.DIAGNOSTICS)
    val kodiLocked = isLocked(PremiumFeature.KODI_SETUP)
    val aiProviderLocked = isLocked(PremiumFeature.AI_PROVIDER_SETUP)
    val collectionsLocked = isLocked(PremiumFeature.PERSISTENT_COLLECTIONS)
    val customLayoutLocked = isLocked(PremiumFeature.SYNC_CUSTOM_LAYOUTS)
    val backupLocked = isLocked(PremiumFeature.CLOUD_BACKUP_RESTORE)
    val canOpenManageDevices = subscriptionState.hasEntitlement || !deviceLinkingLocked
    val settingsContext = LocalContext.current
    val activity = settingsContext.findActivity()
    // Observe the authoritative auth user flow — reacts immediately to
    // login, logout, and verification status changes without manual refresh.
    val authUser by authClient.authUserFlow.collectAsState()

    // Storage mode state for credential entry sections
    // Default to ACCOUNT when signed in — integrations sync to backend automatically.
    // User can opt out to DEVICE_ONLY if they prefer local-only storage.
    val defaultStorageMode = if (authUser != null) com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT
        else com.torve.domain.integrations.IntegrationStorageMode.DEVICE_ONLY
    var aiKeyStorageMode by remember(defaultStorageMode) { mutableStateOf(defaultStorageMode) }

    // Seed the flow on first composition if it's null (e.g. cold start).
    LaunchedEffect(Unit) {
        if (authClient.authUserFlow.value == null) {
            authClient.getAuthenticatedUser() // triggers persistAuth → emitCurrentUser
        }
    }
    LaunchedEffect(authUser?.id) {
        if (authUser != null) {
            accountSessionCoordinator.onSettingsOpened()
        }
    }
    // Region code auto-detection removed — setting is unused.
    LaunchedEffect(Unit) {
        if (BuildConfig.HAS_BILLING) {
            subscriptionViewModel.refreshAccess()
        }
        betaProgramViewModel.onOpenBetaProgram()
    }

    val scope = rememberCoroutineScope()
    var refreshAllQueued by remember { mutableStateOf(false) }
    var refreshAllRunId by remember { mutableIntStateOf(0) }
    var refreshAllStatus by remember { mutableStateOf<RefreshAllUiStatus?>(null) }
    val refreshAllStartedText = stringResource(R.string.settings_refresh_all_started)
    LaunchedEffect(refreshAllRunId) {
        if (refreshAllRunId == 0) return@LaunchedEffect
        val workManager = WorkManager.getInstance(settingsContext.applicationContext)
        val observedWorkIds = mutableSetOf<String>()
        var sawActiveWork = false
        repeat(180) {
            val workInfos = withContext(Dispatchers.IO) {
                workManager.getWorkInfosByTag(BackgroundWork.TAG_HEAVY_PRELOAD).get()
            }
            val active = workInfos
                .filter {
                    it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.BLOCKED
                }
                .onEach { observedWorkIds += it.id.toString() }
            val current = active.maxByOrNull {
                it.progress.getFloat(BackgroundWork.KEY_PROGRESS, -1f)
            }
            if (current != null) {
                sawActiveWork = true
                refreshAllStatus = RefreshAllUiStatus(
                    label = current.progress.getString(BackgroundWork.KEY_LABEL)
                        ?: refreshAllStartedText,
                    progress = current.progress
                        .getFloat(BackgroundWork.KEY_PROGRESS, 0f)
                        .coerceIn(0f, 1f),
                    complete = false,
                    failed = false,
                )
            } else if (sawActiveWork) {
                val terminal = workInfos.filter { it.id.toString() in observedWorkIds && it.state.isFinished }
                val failed = terminal.any { it.state == WorkInfo.State.FAILED }
                refreshAllStatus = RefreshAllUiStatus(
                    label = if (failed) {
                        "Refresh did not finish. Check the connection and try again."
                    } else {
                        "Refresh finished. Reopen affected tabs if they were already visible."
                    },
                    progress = 1f,
                    complete = !failed,
                    failed = failed,
                )
                if (!failed) {
                    viewModel.refreshSettings()
                    channelsViewModel.loadPlaylists(recoverEmptyCatalog = true)
                    channelsViewModel.loadFavorites()
                }
                delay(4_000L)
                refreshAllQueued = false
                refreshAllStatus = null
                return@LaunchedEffect
            } else {
                refreshAllStatus = RefreshAllUiStatus(
                    label = refreshAllStartedText,
                    progress = 0f,
                    complete = false,
                    failed = false,
                )
            }
            delay(500L)
        }
    }
    val premiumStatusLabel = subscriptionAccess.accessStatusLabel
    NeedsVerificationToastEffect(
        message = subscriptionState.verificationEmailMessage,
        onConsumed = subscriptionViewModel::consumeVerificationEmailMessage,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.displayMedium,
            color = Snow,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(16.dp))

        // Persistent nudge encouraging users to run the Panda wizard,
        // which is the easiest way to get debrid + indexer plumbing wired
        // up. Hidden once Panda is configured OR the user dismisses, AND
        // only shown to users who can actually finish the flow — Panda
        // depends on the premium-gated addon catalog, so signed-out,
        // unverified, or free-tier users would be told to set up
        // something they can't complete.
        val pandaNudgeEligible = authUser != null &&
            authUser?.isVerified == true
        PandaSetupNudgeCard(
            onSetupClick = onSetupPandaClick,
            eligible = pandaNudgeEligible,
        )

        // Prominent subscription summary card. Only rendered when the user
        // is signed in — for signed-out users the existing account card
        // already provides the sign-in CTA, and rendering this card on top
        // would duplicate it. When signed in this card is the single
        // source of truth for "which plan do I have and when does it
        // expire", so the duplicate status text in the account-card-
        // adjacent block below is suppressed accordingly.
        if (BuildConfig.HAS_BILLING && authUser != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Charcoal),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_subscription_card_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = Amber,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = subscriptionAccess.accessStatusLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = Snow,
                        fontWeight = FontWeight.Bold,
                    )
                    if (subscriptionAccess.accessHelperText.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = subscriptionAccess.accessHelperText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Torve.colors.textSecondary,
                        )
                    }
                    subscriptionMarketplaceLabel(subscriptionState.subscription?.platform)?.let { label ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Managed through $label",
                            style = MaterialTheme.typography.bodySmall,
                            color = Torve.colors.textTertiary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (subscriptionAccess.shouldShowBuy) {
                        Button(
                            onClick = onSubscriptionClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                        ) {
                            ButtonLabel(stringResource(R.string.settings_subscription_upgrade_cta))
                        }
                    } else {
                        OutlinedButton(
                            onClick = onSubscriptionClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                        ) {
                            ButtonLabel(stringResource(R.string.settings_subscription_manage_cta))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        val hasExistingPremiumAccess = subscriptionState.hasEntitlement || subscriptionState.isPro
        val showBetaProgramCard = shouldShowBetaProgramSettingsEntry(
            state = betaProgramState,
            hasPremiumAccess = hasExistingPremiumAccess,
        )
        if (showBetaProgramCard) {
        val betaCard = betaProgramState.run {
            com.torve.domain.beta.BetaProgramStatus(
                signedIn = isSignedIn,
                applicationStatus = applicationStatus,
                betaAccess = com.torve.domain.beta.BetaAccessState(
                    active = betaAccessActive,
                    expiresAt = betaAccessExpiresAt,
                    status = betaGrantStatus,
                ),
                daysRemaining = daysRemaining,
                eligibility = com.torve.domain.beta.BetaEligibilityState(
                    canApply = canApply,
                    blockedReason = blockedReason,
                    isEmailVerificationRequired = isEmailVerificationRequired,
                ),
                signupCloseAt = signupCloseAt,
                freeAccessEndAt = freeAccessEndAt,
                discordInviteUrl = discordInviteUrl,
            ).toSettingsCardState()
        }
        val betaSubtitle = when {
            hasExistingPremiumAccess &&
                betaProgramState.isSignedIn &&
                !betaProgramState.isEmailVerificationRequired &&
                !betaProgramState.betaAccessActive &&
                betaProgramState.blockedReason != com.torve.domain.beta.BetaBlockedReason.BETA_SIGNUP_CLOSED &&
                betaProgramState.blockedReason != com.torve.domain.beta.BetaBlockedReason.BETA_ACCESS_ENDED ->
                BetaProgramCopy.PREMIUM_TESTER_APPLICATION
            else -> betaCard.subtitle
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBetaProgramClick),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = betaCard.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Snow,
                        fontWeight = FontWeight.Bold,
                    )
                    betaCard.badge?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Amber,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = betaSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onBetaProgramClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                ) {
                    ButtonLabel("Open Beta Program")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        }

        // Account & Sync card (top of settings)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (authUser != null) {
                    Text(
                        text = "Signed in as ${authUser?.email ?: ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Snow,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (subscriptionState.needsVerification) {
                        Spacer(Modifier.height(8.dp))
                        NeedsVerificationBanner(
                            isSending = subscriptionState.isSendingVerificationEmail,
                            onSendVerificationEmail = subscriptionViewModel::sendVerificationEmail,
                            onRefreshAccess = subscriptionViewModel::refreshAccess,
                        )
                    } else if (authUser?.isVerified == false) {
                        Spacer(Modifier.height(8.dp))
                        VerificationBanner(
                            email = authUser?.email ?: "",
                            authClient = authClient,
                            onVerified = { /* flow updates automatically via checkVerificationStatus → emitCurrentUser */ },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    val pairedDeviceCount = syncState.devices.count { it.revokedAt == null }
                    Text(
                        text = if (pairedDeviceCount == 1) {
                            stringResource(R.string.settings_paired_count_one, pairedDeviceCount)
                        } else {
                            stringResource(R.string.settings_paired_count_other, pairedDeviceCount)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Torve.colors.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                onPremiumAction(PremiumFeature.PHONE_PAIRING) { onDevicesClick() }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                        ) {
                            if (pairingLocked) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (pairingLocked) stringResource(R.string.settings_manage_pairings_locked) else stringResource(R.string.settings_manage_pairings))
                        }
                        OutlinedButton(
                            onClick = {
                                if (canOpenManageDevices) {
                                    onManageDevicesClick()
                                } else {
                                    onLockedFeatureClick(PremiumFeature.DEVICE_LINKING)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                        ) {
                            if (!canOpenManageDevices && deviceLinkingLocked) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (!canOpenManageDevices && deviceLinkingLocked) stringResource(R.string.settings_manage_devices_locked) else stringResource(R.string.settings_manage_devices))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // "Sign in a TV with this phone" — entry point for the
                    // QR-sign-in flow. The TV displays a QR encoding
                    // `torve-signin:<CODE>`; native phone camera apps don't
                    // know how to interpret that scheme, so the scanner has
                    // to live inside Torve. Discoverable from the top-level
                    // Account card so users don't have to dig through the
                    // legacy "Devices" pairing screen.
                    OutlinedButton(
                        onClick = onSignInTvClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    ) {
                        Text(stringResource(R.string.settings_sign_in_tv_with_phone))
                    }
                    Spacer(Modifier.height(8.dp))
                    var showSignOutConfirm by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { showSignOutConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Torve.colors.textSecondary),
                    ) {
                        Text(stringResource(R.string.settings_sign_out))
                    }
                    if (showSignOutConfirm) {
                        AlertDialog(
                            onDismissRequest = { showSignOutConfirm = false },
                            title = { Text(stringResource(R.string.settings_sign_out_title)) },
                            text = {
                                Text(stringResource(R.string.settings_sign_out_body))
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showSignOutConfirm = false
                                    scope.launch {
                                        accountSessionCoordinator.signOut()
                                        authClient.logout()
                                    }
                                }) {
                                    Text(stringResource(R.string.settings_sign_out), color = Amber)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showSignOutConfirm = false }) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    var isDeletingAccount by remember { mutableStateOf(false) }
                    var deleteError by remember { mutableStateOf<String?>(null) }
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true; deleteError = null },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ruby),
                        enabled = !isDeletingAccount,
                    ) {
                        Text(
                            if (isDeletingAccount) stringResource(R.string.settings_delete_account_deleting)
                            else stringResource(R.string.settings_delete_account),
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_delete_account_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = Torve.colors.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { if (!isDeletingAccount) showDeleteConfirm = false },
                            containerColor = Charcoal,
                            title = { Text(stringResource(R.string.settings_delete_account_title), color = Snow) },
                            text = {
                                Column {
                                    Text(stringResource(R.string.settings_delete_account_body), color = Silver)
                                    deleteError?.let { err ->
                                        Spacer(Modifier.height(8.dp))
                                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        isDeletingAccount = true
                                        deleteError = null
                                        scope.launch {
                                            val result = authClient.deleteAccount()
                                            isDeletingAccount = false
                                            if (result.success) {
                                                showDeleteConfirm = false
                                                accountSessionCoordinator.signOut()
                                            } else {
                                                deleteError = result.error ?: "Failed to delete account"
                                            }
                                        }
                                    },
                                    enabled = !isDeletingAccount,
                                    colors = ButtonDefaults.buttonColors(containerColor = Ruby, contentColor = Snow),
                                ) {
                                    if (isDeletingAccount) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = Snow,
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Text(stringResource(R.string.settings_delete_account_confirm))
                                    }
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showDeleteConfirm = false },
                                    enabled = !isDeletingAccount,
                                ) {
                                    Text(stringResource(R.string.common_cancel), color = Silver)
                                }
                            },
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.settings_sign_in_manage),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Torve.colors.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onLoginClick() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = Obsidian,
                        ),
                    ) {
                        ButtonLabel(stringResource(R.string.settings_sign_in_create))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Quick links. The dedicated Subscription button is no longer
        // shown for signed-in users on billing-enabled builds — the top
        // Subscription card already exposes Manage Subscription / Upgrade
        // to Premium. For signed-out users we still surface a path into
        // the account access surface here without requiring sign-in.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (BuildConfig.HAS_BILLING && authUser == null) {
                OutlinedButton(
                    onClick = onSubscriptionClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                ) {
                    ButtonLabel(stringResource(R.string.settings_subscription))
                }
            }
            OutlinedButton(
                onClick = onProfilesClick,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                ButtonLabel(stringResource(R.string.settings_profiles))
            }
        }
        if (BuildConfig.HAS_BILLING) {
            // Status text only shown when the top subscription card is NOT
            // present (i.e. signed-out users) to avoid duplicating
            // "Premium active / Premium is active on this device" twice.
            if (authUser == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = premiumStatusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subscriptionAccess.accessHelperText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
                subscriptionMarketplaceLabel(subscriptionState.subscription?.platform)?.let { label ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Managed through $label",
                        style = MaterialTheme.typography.bodySmall,
                        color = Torve.colors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (subscriptionAccess.shouldShowManageDevices) {
                    Button(
                        onClick = onManageDevicesClick,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) {
                        ButtonLabel(stringResource(R.string.manage_devices_title))
                    }
                }
                if (subscriptionState.isLoggedIn) {
                    OutlinedButton(
                        onClick = { subscriptionViewModel.refreshAccess() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    ) {
                        ButtonLabel(stringResource(R.string.premium_refresh_access))
                    }
                }
            }
            if (subscriptionState.isLoggedIn) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        refreshAllQueued = true
                        refreshAllStatus = RefreshAllUiStatus(
                            label = "Syncing account data...",
                            progress = 0f,
                            complete = false,
                            failed = false,
                        )
                        scope.launch {
                            runCatching {
                                accountSessionCoordinator.refreshAccountDataAfterCredentialTransfer(
                                    initialMessage = "Refreshing account data...",
                                )
                            }
                            viewModel.refreshSettings()
                            subscriptionViewModel.refreshAccess()
                            syncCoordinator.refreshDevices()
                            channelsViewModel.loadPlaylists(recoverEmptyCatalog = true)
                            channelsViewModel.loadFavorites()
                            PostSignInRefresh.enqueueFullRefreshAfterCredentialImport(settingsContext)
                            refreshAllRunId += 1
                        }
                    },
                    enabled = !refreshAllQueued,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                ) {
                    ButtonLabel(stringResource(R.string.settings_refresh_all))
                }
                if (refreshAllQueued) {
                    Spacer(Modifier.height(6.dp))
                    RefreshAllStatusView(refreshAllStatus)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCalendarClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
        ) {
            ButtonLabel(stringResource(R.string.settings_calendar))
        }

        if (subscriptionState.isLoggedIn) {
            Spacer(Modifier.height(8.dp))
            ProviderStatusSummaryCard(onViewAll = onStatusRepairClick)
            Spacer(Modifier.height(16.dp))
            // ─── Configure Sources ──────────────────────────────────
            // The actual credential editors. Status/repair lives above
            // — these rows are configuration-only, no inline status.
            Text(
                text = "Configure Sources",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Snow,
            )
            Spacer(Modifier.height(8.dp))
            RestoreSetupRecoveryCard(
                onReceive = onReceiveCredentialsClick,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onStartSetupClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                ButtonLabel("Start setup again")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSendCredentialsClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                ButtonLabel(stringResource(R.string.settings_send_credentials))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onReceiveCredentialsClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                ButtonLabel(stringResource(R.string.settings_receive_credentials))
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Channels Section ──
        LiveTvSettingsSection(
            locked = collectionsLocked,
            onLockedClick = { onLockedFeatureClick(PremiumFeature.PERSISTENT_COLLECTIONS) },
        )

        Spacer(Modifier.height(24.dp))

        // ── Stream Quality & Size ──
        SectionHeader(title = stringResource(R.string.settings_stream_quality))
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Max quality dropdown
                var maxQualityExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = maxQualityExpanded,
                    onExpandedChange = { maxQualityExpanded = !maxQualityExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_max_quality), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    state.maxQuality.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Snow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = maxQualityExpanded,
                        onDismissRequest = { maxQualityExpanded = false },
                    ) {
                        StreamQuality.selectable.forEach { quality ->
                            DropdownMenuItem(
                                text = { MenuItemLabel(quality.label) },
                                onClick = {
                                    viewModel.setMaxQuality(quality)
                                    maxQualityExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Min quality dropdown
                var minQualityExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = minQualityExpanded,
                    onExpandedChange = { minQualityExpanded = !minQualityExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_min_quality), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    state.minQuality.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Snow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = minQualityExpanded,
                        onDismissRequest = { minQualityExpanded = false },
                    ) {
                        StreamQuality.selectable.forEach { quality ->
                            DropdownMenuItem(
                                text = { MenuItemLabel(quality.label) },
                                onClick = {
                                    viewModel.setMinQuality(quality)
                                    minQualityExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Max file size
                var fileSizeText by remember(state.maxFileSizeMb) {
                    mutableStateOf(state.maxFileSizeMb?.toString() ?: "")
                }
                SettingsTextField(
                    value = fileSizeText,
                    onValueChange = { text ->
                        fileSizeText = text.filter { it.isDigit() }
                        val value = fileSizeText.toIntOrNull()
                        viewModel.setMaxFileSizeMb(value)
                    },
                    label = stringResource(R.string.settings_max_file_size),
                    placeholder = stringResource(R.string.settings_no_limit),
                )

                Spacer(Modifier.height(12.dp))

                // Cached only toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_cached_only), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_cached_only_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = Torve.colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = state.cachedOnly,
                        onCheckedChange = { viewModel.setCachedOnly(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.3f),
                            uncheckedThumbColor = Silver,
                            uncheckedTrackColor = Gunmetal,
                        ),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // HDR toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_hdr_content), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_hdr_content_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = Torve.colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = state.hdrEnabled,
                        onCheckedChange = { viewModel.setHdrEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.3f),
                            uncheckedThumbColor = Silver,
                            uncheckedTrackColor = Gunmetal,
                        ),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Deduplicate results toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_dedupe_results), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_dedupe_results_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = Torve.colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = state.dedupeResults,
                        onCheckedChange = { viewModel.setDedupeResultsEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.3f),
                            uncheckedThumbColor = Silver,
                            uncheckedTrackColor = Gunmetal,
                        ),
                    )
                }

                // Region selector removed — not used by the app.
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Playback Section ──
        SectionHeader(title = stringResource(R.string.settings_playback))
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Auto-Play toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_auto_play), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_auto_play_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = Torve.colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = state.autoPlayEnabled,
                        onCheckedChange = { viewModel.setAutoPlayEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.3f),
                            uncheckedThumbColor = Silver,
                            uncheckedTrackColor = Gunmetal,
                        ),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Auto-Play Next Episode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_auto_play_next), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_auto_play_next_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = Torve.colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = state.autoPlayNextEpisodeEnabled,
                        onCheckedChange = { viewModel.setAutoPlayNextEpisodeEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.3f),
                            uncheckedThumbColor = Silver,
                            uncheckedTrackColor = Gunmetal,
                        ),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Codec Preference dropdown
                var codecExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = codecExpanded,
                    onExpandedChange = { codecExpanded = !codecExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_codec_preference), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    state.codecPreference.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Snow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = codecExpanded,
                        onDismissRequest = { codecExpanded = false },
                    ) {
                        CodecPreference.entries.forEach { pref ->
                            DropdownMenuItem(
                                text = { MenuItemLabel(pref.label) },
                                onClick = {
                                    viewModel.setCodecPreference(pref)
                                    codecExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // HDR Mode dropdown
                var hdrModeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = hdrModeExpanded,
                    onExpandedChange = { hdrModeExpanded = !hdrModeExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_hdr_mode), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    state.hdrMode.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Snow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = hdrModeExpanded,
                        onDismissRequest = { hdrModeExpanded = false },
                    ) {
                        HdrMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { MenuItemLabel(mode.label) },
                                onClick = {
                                    viewModel.setHdrMode(mode)
                                    hdrModeExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Content Management ──
        val addonViewModel: AddonViewModel = koinInject()
        val addonState by addonViewModel.state.collectAsState()
        SectionHeader(title = stringResource(R.string.settings_content_management))
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsNavRow(
                    title = stringResource(R.string.settings_home_layout),
                    subtitle = stringResource(R.string.settings_home_layout_sub),
                    locked = customLayoutLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.SYNC_CUSTOM_LAYOUTS) { onHomeLayoutClick() }
                    },
                )
                if (BuildConfig.FLAVOR.contains("google", ignoreCase = true)) {
                    HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsNavRow(
                        title = stringResource(R.string.settings_sensitive_material),
                        subtitle = stringResource(R.string.settings_sensitive_material_sub),
                        onClick = onSensitiveMaterialClick,
                    )
                }
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = stringResource(R.string.settings_card_style),
                    subtitle = stringResource(R.string.settings_card_style_sub),
                    onClick = onCardStyleClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = stringResource(R.string.settings_addons_title),
                    subtitle = stringResource(R.string.settings_addons_sub, addonState.addons.size),
                    locked = addonLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT) { onAddonCatalogClick() }
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = stringResource(R.string.settings_streaming_services),
                    subtitle = stringResource(R.string.settings_streaming_services_sub),
                    locked = customSourcesLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT) { onStreamingServicesClick() }
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = stringResource(R.string.settings_stream_groups),
                    subtitle = stringResource(R.string.settings_stream_groups_sub, state.streamGroups.size),
                    locked = customSourcesLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT) { onStreamGroupsClick() }
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = stringResource(R.string.settings_regex_patterns),
                    subtitle = stringResource(R.string.settings_regex_patterns_sub, state.regexPatterns.size),
                    locked = customSourcesLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.ADVANCED_CONNECTION_CONFIGURATION) { onRegexPatternsClick() }
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "MDBList",
                    subtitle = if (state.mdblistApiKey.isNotBlank()) "Connected" else "Curated community lists",
                    locked = mdbListLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.MDBLIST_SETUP) { onMdbListClick() }
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = stringResource(R.string.settings_ratings),
                    subtitle = stringResource(R.string.settings_ratings_sub),
                    onClick = onRatingSettingsClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = stringResource(R.string.settings_integrations),
                    subtitle = stringResource(R.string.settings_integrations_sub),
                    locked = traktLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.TRAKT_CONNECT) { onIntegrationsClick() }
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Kodi Remote ──
        SectionHeader(title = stringResource(R.string.settings_kodi_remote))
        Spacer(Modifier.height(8.dp))

        if (kodiLocked) {
            LockedSettingsCard(
                title = stringResource(R.string.settings_kodi_remote),
                description = stringResource(R.string.settings_kodi_desc),
                onUnlock = { onLockedFeatureClick(PremiumFeature.KODI_SETUP) },
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Charcoal),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                if (state.kodiHosts.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_no_kodi_hosts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Torve.colors.textSecondary,
                    )
                } else {
                    state.kodiHosts.forEach { host ->
                        val key = "${host.ip}:${host.port}"
                        val testResult = state.kodiTestResult[key]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(host.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(host.jsonRpcUrl, style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                            }
                            if (testResult != null) {
                                Icon(
                                    if (testResult) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (testResult) Emerald else Ruby,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            TextButton(onClick = { viewModel.testKodiHost(host) }) {
                                ButtonLabel(stringResource(R.string.common_test), color = Amber)
                            }
                            IconButton(onClick = { viewModel.removeKodiHost(host) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_remove), modifier = Modifier.size(18.dp), tint = Ruby)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Add Kodi host
                var showAddKodi by remember { mutableStateOf(false) }
                if (showAddKodi) {
                    var kodiName by remember { mutableStateOf("") }
                    var kodiIp by remember { mutableStateOf("") }
                    var kodiPort by remember { mutableStateOf("8080") }

                    SettingsTextField(
                        value = kodiName,
                        onValueChange = { kodiName = it },
                        label = stringResource(R.string.settings_kodi_name),
                        placeholder = stringResource(R.string.settings_kodi_name_hint),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsTextField(
                            value = kodiIp,
                            onValueChange = { kodiIp = it },
                            label = stringResource(R.string.settings_kodi_ip),
                            placeholder = "192.168.1.100",
                            modifier = Modifier.weight(2f),
                        )
                        SettingsTextField(
                            value = kodiPort,
                            onValueChange = { kodiPort = it.filter { c -> c.isDigit() } },
                            label = stringResource(R.string.settings_kodi_port),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (kodiName.isNotBlank() && kodiIp.isNotBlank()) {
                                    viewModel.addKodiHost(kodiName, kodiIp, kodiPort.toIntOrNull() ?: 8080)
                                    showAddKodi = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = Obsidian,
                            ),
                        ) {
                            ButtonLabel(stringResource(R.string.common_add))
                        }
                        OutlinedButton(
                            onClick = { showAddKodi = false },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                        ) {
                            ButtonLabel(stringResource(R.string.common_cancel))
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showAddKodi = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        ButtonLabel(stringResource(R.string.settings_add_kodi_host))
                    }
                }
            }
        }
        }

        Spacer(Modifier.height(24.dp))

        // ── AI Features ──
        SectionHeader(title = stringResource(R.string.settings_ai_features))
        Spacer(Modifier.height(8.dp))

        if (aiProviderLocked) {
            LockedSettingsCard(
                title = stringResource(R.string.settings_ai_features),
                description = stringResource(R.string.settings_ai_locked_desc),
                onUnlock = { onLockedFeatureClick(PremiumFeature.AI_PROVIDER_SETUP) },
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Charcoal),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Choose your AI provider and add your API key to enable AI-powered search. Your provider account must have active billing or credits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                )
                Spacer(Modifier.height(8.dp))

                // Provider selector
                var aiProviderExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = aiProviderExpanded,
                    onExpandedChange = { aiProviderExpanded = !aiProviderExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_ai_provider), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    state.aiProvider.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Snow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = aiProviderExpanded,
                        onDismissRequest = { aiProviderExpanded = false },
                    ) {
                        com.torve.data.ai.AiProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { MenuItemLabel(provider.label) },
                                onClick = {
                                    viewModel.setAiProvider(provider)
                                    aiProviderExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                SettingsTextField(
                    value = state.activeAiApiKey,
                    onValueChange = { viewModel.updateActiveAiApiKeyInput(it) },
                    label = "${state.aiProvider.label} API Key",
                    placeholder = state.aiProvider.keyPlaceholder,
                    isSensitive = true,
                )
                Spacer(Modifier.height(4.dp))
                com.torve.android.ui.components.StorageModeSelector(
                    selected = aiKeyStorageMode,
                    onModeSelected = { aiKeyStorageMode = it },
                    isSignedIn = authUser != null,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            viewModel.saveAndValidateAiApiKey()
                            if (aiKeyStorageMode == com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT) {
                                scope.launch {
                                    accountSessionCoordinator.saveIntegrationToBackend(
                                        integrationType = "${state.aiProvider.name}_API_KEY",
                                        credentials = mapOf("api_key" to state.activeAiApiKey),
                                        displayIdentifier = state.aiProvider.label,
                                    )
                                }
                            }
                        },
                        enabled = !state.aiKeyValidating && state.activeAiApiKey.isNotBlank(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Amber,
                            contentColor = Obsidian,
                        ),
                    ) {
                        if (state.aiKeyValidating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Obsidian, strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        ButtonLabel(if (state.aiKeyValidating) "Saving..." else "Save & Test")
                    }
                    state.aiKeyValidationResult?.let { result ->
                        when (result) {
                            "valid" -> Text(
                                "Key works! (${state.aiProvider.label})",
                                color = Emerald,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            else -> Text(
                                result,
                                color = Ruby,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "API keys only work when the provider account has funds. If your balance is zero, AI search will fail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
            }
        }
        }

        Spacer(Modifier.height(24.dp))

        // ── Appearance ──
        SectionHeader(title = stringResource(R.string.settings_appearance))
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                // Language selector

                var languageExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = !languageExpanded },
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.settings_language),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Torve.colors.textSecondary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    state.appLanguage.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Snow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                        AppLanguage.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { MenuItemLabel(lang.displayName) },
                                onClick = {
                                    viewModel.setAppLanguage(lang)
                                    languageExpanded = false
                                    AppCompatDelegate.setApplicationLocales(
                                        LocaleListCompat.forLanguageTags(lang.code),
                                    )
                                    activity?.recreate()
                                },
                            )
                        }
                    }
                }

            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Storage & Cache ──
        SectionHeader(title = stringResource(R.string.settings_storage))
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                var showClearConfirm by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_clear_cache), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_clear_cache_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = Torve.colors.textSecondary,
                        )
                    }
                    if (state.cacheCleared) {
                        Text(stringResource(R.string.settings_cleared), style = MaterialTheme.typography.bodySmall, color = Emerald)
                    } else {
                        OutlinedButton(
                            onClick = { showClearConfirm = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                        ) {
                            ButtonLabel(stringResource(R.string.common_clear))
                        }
                    }
                }

                if (showClearConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearConfirm = false },
                        containerColor = Charcoal,
                        title = { Text(stringResource(R.string.settings_clear_cache), color = Snow) },
                        text = { Text(stringResource(R.string.settings_clear_cache_confirm), color = Torve.colors.textSecondary) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.clearCache()
                                    showClearConfirm = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Ruby, contentColor = Snow),
                            ) {
                                ButtonLabel(stringResource(R.string.common_clear))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearConfirm = false }) {
                                Text(stringResource(R.string.common_cancel), color = Torve.colors.textSecondary)
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Backup & Sync ──
        SectionHeader(title = stringResource(R.string.settings_backup))
        Spacer(Modifier.height(8.dp))

        if (backupLocked) {
            LockedSettingsCard(
                title = stringResource(R.string.settings_backup),
                description = stringResource(R.string.settings_backup_desc),
                onUnlock = { onLockedFeatureClick(PremiumFeature.CLOUD_BACKUP_RESTORE) },
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Charcoal),
            ) {
            val backupContext = LocalContext.current

            // File picker for import
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                uri?.let {
                    try {
                        val jsonStr = backupContext.contentResolver.openInputStream(it)
                            ?.bufferedReader()?.use { r -> r.readText() } ?: return@let
                        viewModel.importBackup(jsonStr)
                    } catch (_: Exception) { }
                }
            }

            // File creator for export
            var pendingExportJson by remember { mutableStateOf<String?>(null) }
            val exportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                uri?.let {
                    try {
                        val json = pendingExportJson ?: return@let
                        backupContext.contentResolver.openOutputStream(it)?.use { out ->
                            out.write(json.toByteArray())
                        }
                        pendingExportJson = null
                    } catch (_: Exception) { }
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {
                // Last sync time
                state.lastSyncTime?.let { time ->
                    val dateStr = remember(time) {
                        java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
                            .format(java.util.Date(time))
                    }
                    Text(
                        stringResource(R.string.settings_last_backup, dateStr),
                        style = MaterialTheme.typography.bodySmall,
                        color = Torve.colors.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // Export button
                Button(
                    onClick = {
                        viewModel.exportBackup { json ->
                            pendingExportJson = json
                            exportLauncher.launch("torve_backup.json")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSyncing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Amber,
                        contentColor = Obsidian,
                    ),
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Obsidian,
                        )
                    } else {
                        ButtonLabel(stringResource(R.string.settings_export_backup))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Import button
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSyncing,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                ) {
                    ButtonLabel(stringResource(R.string.settings_import_backup))
                }

                // Success message
                state.syncSuccess?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Emerald,
                    )
                }

                // Error message
                state.syncError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ruby,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.settings_backup_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
            }
        }
        }

        Spacer(Modifier.height(24.dp))

        // ── About & Legal ──
        SectionHeader(title = stringResource(R.string.settings_about))
        Spacer(Modifier.height(8.dp))

        val context = LocalContext.current

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsLinkItem(
                    title = stringResource(R.string.settings_privacy_policy),
                    onClick = onPrivacyPolicyClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = stringResource(R.string.settings_terms),
                    onClick = onTermsClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = stringResource(R.string.settings_help),
                    onClick = onHelpClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = stringResource(R.string.settings_share_app),
                    onClick = {
                        val shareTitle = context.getString(R.string.settings_share_title)
                        val shareText = context.getString(R.string.settings_share_text)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, shareTitle))
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = stringResource(R.string.settings_diagnostics),
                    locked = diagnosticsLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.DIAGNOSTICS) { onDiagnosticsClick() }
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = stringResource(R.string.settings_report_issue),
                    onClick = onReportIssueClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                // Prompt 26: one-tap support bundle that includes
                // provider health + transfer relay state + active
                // engine. Pure shared builder (DiagnosticsBundleBuilder)
                // produces a redacted text blob the user pastes into
                // a support email or attaches to an issue.
                val providerCoordinator: com.torve.presentation.providerhealth.ProviderHealthCoordinator =
                    koinInject()
                val transferCollector: com.torve.presentation.transfer.TransferDiagnosticsCollector =
                    koinInject()
                val exportScope = rememberCoroutineScope()
                SettingsLinkItem(
                    title = stringResource(R.string.settings_export_diagnostics),
                    onClick = {
                        exportScope.launch {
                            val versionName = try {
                                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                            } catch (_: Exception) { "unknown" }
                            val versionCode = try {
                                @Suppress("DEPRECATION")
                                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
                            } catch (_: Exception) { "unknown" }
                            val transferSnapshot = runCatching { transferCollector.collect(probeRelay = false) }.getOrNull()
                            val bundle = com.torve.domain.diagnostics.DiagnosticsBundleBuilder.build(
                                app = com.torve.domain.diagnostics.DiagnosticsBundleBuilder.AppInfo(
                                    versionName = versionName,
                                    versionCode = versionCode,
                                    storeFlavor = com.torve.android.BuildConfig.FLAVOR_store
                                        .ifBlank { "unknown" },
                                    activeEngineId = "ExoPlayer",
                                ),
                                device = com.torve.domain.diagnostics.DiagnosticsBundleBuilder.DeviceInfo(
                                    platform = "Android",
                                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                                    osVersion = "API ${Build.VERSION.SDK_INT}",
                                    locale = java.util.Locale.getDefault().toLanguageTag(),
                                ),
                                providerEntries = providerCoordinator.entries.value,
                                transfer = transferSnapshot,
                                nowEpochMs = System.currentTimeMillis(),
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Torve diagnostics")
                                putExtra(Intent.EXTRA_TEXT, bundle)
                            }
                            context.startActivity(Intent.createChooser(intent, "Export diagnostics"))
                        }
                    },
                )
            }
        }

        // ── Reset Appearance ──
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Steel.copy(alpha = 0.3f))
        Spacer(Modifier.height(16.dp))

        var showResetAppearanceConfirm by remember { mutableStateOf(false) }

        Text(
            text = stringResource(R.string.settings_danger_zone),
            style = MaterialTheme.typography.titleSmall,
            color = Ruby,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showResetAppearanceConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ruby),
            border = androidx.compose.foundation.BorderStroke(1.dp, Ruby.copy(alpha = 0.5f)),
        ) {
            ButtonLabel(stringResource(R.string.settings_reset_appearance))
        }
        Text(
            text = stringResource(R.string.settings_reset_appearance_desc),
            style = MaterialTheme.typography.bodySmall,
            color = Torve.colors.textTertiary,
        )

        if (showResetAppearanceConfirm) {
            AlertDialog(
                onDismissRequest = { showResetAppearanceConfirm = false },
                title = { Text(stringResource(R.string.settings_reset_appearance_title)) },
                text = {
                    Text(stringResource(R.string.settings_reset_appearance_confirm))
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.resetAppearanceSettings()
                            showResetAppearanceConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Ruby),
                    ) {
                        ButtonLabel(stringResource(R.string.common_reset))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetAppearanceConfirm = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Steel.copy(alpha = 0.3f))
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_version, "0.5.0"),
            style = MaterialTheme.typography.bodySmall,
            color = Torve.colors.textTertiary,
        )
    }
}

private fun formatSettingsAccessDate(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    return formatter.format(
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate(),
    )
}

private data class RefreshAllUiStatus(
    val label: String,
    val progress: Float,
    val complete: Boolean,
    val failed: Boolean,
)

@Composable
private fun RefreshAllStatusView(status: RefreshAllUiStatus?) {
    val current = status ?: RefreshAllUiStatus(
        label = stringResource(R.string.settings_refresh_all_started),
        progress = 0f,
        complete = false,
        failed = false,
    )
    val textColor = when {
        current.failed -> Ruby
        current.complete -> Emerald
        else -> Silver
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LinearProgressIndicator(
            progress = { current.progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = if (current.failed) Ruby else Amber,
            trackColor = Snow.copy(alpha = 0.14f),
        )
        Text(
            text = current.label,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
    }
}

private fun subscriptionMarketplaceLabel(value: String?): String? {
    return when (value?.lowercase()) {
        "google_play" -> "Google Play"
        "amazon" -> "Amazon Appstore"
        "apple" -> "Apple App Store"
        else -> null
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Amber,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ButtonLabel(
    text: String,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = text,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun MenuItemLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LockedSettingsCard(
    title: String,
    description: String,
    onUnlock: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$title · ${stringResource(R.string.premium_locked)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Torve.colors.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onUnlock,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                ButtonLabel(stringResource(R.string.premium_unlock_with_lifetime))
            }
        }
    }
}

@Composable
private fun SettingsLinkItem(
    title: String,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (locked) Amber else Torve.colors.textPrimary,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (locked) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = ">",
            style = MaterialTheme.typography.bodyMedium,
            color = Torve.colors.textTertiary,
        )
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = label,
    isSensitive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var revealed by remember { mutableStateOf(false) }
    val peekTransformation = com.torve.android.ui.components.rememberPeekPasswordTransformation(value)
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Snow),
            singleLine = true,
            cursorBrush = SolidColor(Amber),
            visualTransformation = if (isSensitive && !revealed) peekTransformation else VisualTransformation.None,
            keyboardOptions = if (isSensitive) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Gunmetal, RoundedCornerShape(8.dp))
                        .padding(start = 14.dp, end = if (isSensitive) 4.dp else 14.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Torve.colors.textHint)
                        }
                        innerTextField()
                    }
                    if (isSensitive && value.isNotEmpty()) {
                        IconButton(onClick = { revealed = !revealed }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (revealed) "Hide" else "Show",
                                tint = Silver,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            },
        )
    }
}

private fun buildIssuePayload(
    appVersion: String,
    deviceModel: String,
    sdkInt: Int,
    settingsState: com.torve.presentation.settings.SettingsUiState,
): String {
    val redactedSettings = mapOf(
        "themeMode" to settingsState.themeMode.name,
        "appLanguage" to settingsState.appLanguage.code,
        "debridProvider" to settingsState.debridProvider.name,
        "debridConnected" to settingsState.debridConnected.toString(),
        "traktConnected" to settingsState.traktConnected.toString(),
        "traktLastSyncTime" to (settingsState.traktLastSyncTime?.toString() ?: ""),
        "availabilityLastSyncTime" to (settingsState.availabilityLastSyncTime?.toString() ?: ""),
        "libraryOverlayLastSyncTime" to (settingsState.libraryOverlayLastSyncTime?.toString() ?: ""),
        "simklConnected" to settingsState.simklConnected.toString(),
        "mdblistConfigured" to settingsState.mdblistApiKey.isNotBlank().toString(),
        "jellyfinConfigured" to settingsState.jellyfinApiKey.isNotBlank().toString(),
        "jellyfinServerUrl" to settingsState.jellyfinServerUrl,
        "regionCode" to settingsState.regionCode,
        "ratingProviders" to settingsState.ratingPrefs.enabledProviders.joinToString(",") { it.name },
        "maxRatingsOnCard" to settingsState.ratingPrefs.maxRatingsOnCard.toString(),
        "allowRatingsOnLandscapeCards" to settingsState.ratingPrefs.allowRatingsOnLandscapeCards.toString(),
        "pillPosition" to settingsState.ratingPrefs.pillPosition.name,
        "torveWeights" to settingsState.ratingPrefs.torveWeights.entries.joinToString(",") { "${it.key.name}:${it.value}" },
        "globalDefaultPresetId" to (settingsState.globalDefaultPresetId ?: ""),
        "presetCount" to settingsState.cardStylePresets.size.toString(),
    )
    return buildString {
        appendLine("Torve issue report")
        appendLine("appVersion=$appVersion")
        appendLine("device=$deviceModel")
        appendLine("sdkInt=$sdkInt")
        appendLine("settings=${redactedSettings.entries.joinToString(";") { "${it.key}=${it.value}" }}")
    }
}

@Composable
private fun SettingsNavRow(
    title: String,
    subtitle: String,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Snow,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (locked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Amber,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                text = if (locked) {
                    "$subtitle · ${stringResource(R.string.premium_requires_lifetime)}"
                } else {
                    subtitle
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (locked) Amber.copy(alpha = 0.9f) else Torve.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(">", style = MaterialTheme.typography.bodyMedium, color = Torve.colors.textTertiary)
    }
}

@Composable
private fun LiveTvSettingsSection(
    locked: Boolean = false,
    onLockedClick: () -> Unit = {},
) {
    if (locked) {
        SectionHeader(title = stringResource(R.string.settings_live_tv))
        Spacer(Modifier.height(8.dp))
        LockedSettingsCard(
            title = stringResource(R.string.settings_live_tv),
            description = stringResource(R.string.settings_channels_desc),
            onUnlock = onLockedClick,
        )
        return
    }

    val channelsViewModel: ChannelsViewModel = koinInject()
    val channelsState by channelsViewModel.state.collectAsState()
    var editingEpgPlaylistId by remember { mutableStateOf<String?>(null) }
    var editingEpgUrl by remember { mutableStateOf("") }

    LaunchedEffect(editingEpgPlaylistId, channelsState.playlists) {
        val playlist = channelsState.playlists.firstOrNull { it.id == editingEpgPlaylistId }
        if (playlist != null) {
            editingEpgUrl = playlist.epgUrl.orEmpty()
        }
    }

    SectionHeader(title = stringResource(R.string.settings_live_tv))
    Spacer(Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (channelsState.playlists.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_playlists),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Torve.colors.textTertiary,
                )
            } else {
                channelsState.playlists.forEach { playlist ->
                    val channelCountText = stringResource(R.string.settings_channels_count, playlist.channelCount)
                    val guideStatusText = if (playlist.epgUrl.isNullOrBlank()) {
                        stringResource(R.string.tv_settings_not_set)
                    } else {
                        stringResource(R.string.tv_settings_set)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Snow,
                            )
                            Text(
                                text = "$channelCountText - $guideStatusText",
                                style = MaterialTheme.typography.bodySmall,
                                color = Torve.colors.textTertiary,
                            )
                        }
                        TextButton(
                            onClick = {
                                editingEpgPlaylistId = playlist.id
                                editingEpgUrl = playlist.epgUrl.orEmpty()
                            },
                        ) {
                            Text(stringResource(R.string.channels_epg_optional), color = Amber)
                        }
                        IconButton(
                            onClick = { channelsViewModel.deletePlaylist(playlist.id) },
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = Ruby,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            val editingPlaylist = channelsState.playlists.firstOrNull { it.id == editingEpgPlaylistId }
            if (editingPlaylist != null) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Gunmetal, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = editingPlaylist.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Snow,
                    )
                    SettingsTextField(
                        value = editingEpgUrl,
                        onValueChange = { editingEpgUrl = it },
                        label = stringResource(R.string.channels_epg_optional),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                channelsViewModel.updatePlaylistEpgUrl(editingPlaylist.id, editingEpgUrl)
                                editingEpgPlaylistId = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = Obsidian,
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(stringResource(R.string.common_save), fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = {
                                editingEpgUrl = ""
                                channelsViewModel.updatePlaylistEpgUrl(editingPlaylist.id, "")
                                editingEpgPlaylistId = null
                            },
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(stringResource(R.string.common_clear))
                        }
                        TextButton(onClick = { editingEpgPlaylistId = null }) {
                            Text(stringResource(R.string.common_cancel), color = Torve.colors.textTertiary)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { channelsViewModel.showAddPlaylistDialog() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = Obsidian,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                ButtonLabel(stringResource(R.string.settings_add_playlist), fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // Add Playlist Dialog (shared with ChannelsScreen)
    if (channelsState.showAddPlaylist) {
        com.torve.android.ui.channels.AddPlaylistDialog(
            name = channelsState.newPlaylistName,
            url = channelsState.newPlaylistUrl,
            epgUrl = channelsState.newPlaylistEpgUrl,
            playlistType = channelsState.newPlaylistType,
            xtreamServer = channelsState.newXtreamServer,
            xtreamUsername = channelsState.newXtreamUsername,
            xtreamPassword = channelsState.newXtreamPassword,
            isLoading = channelsState.isAddingPlaylist,
            isCheckingEpg = channelsState.isCheckingEpg,
            epgCheckMessage = channelsState.epgCheckMessage,
            epgCheckSuccess = channelsState.epgCheckSuccess,
            onNameChange = { channelsViewModel.setNewPlaylistName(it) },
            onUrlChange = { channelsViewModel.setNewPlaylistUrl(it) },
            onEpgUrlChange = { channelsViewModel.setNewPlaylistEpgUrl(it) },
            onTypeChange = { channelsViewModel.setNewPlaylistType(it) },
            onXtreamServerChange = { channelsViewModel.setNewXtreamServer(it) },
            onXtreamUsernameChange = { channelsViewModel.setNewXtreamUsername(it) },
            onXtreamPasswordChange = { channelsViewModel.setNewXtreamPassword(it) },
            onCheckEpg = { channelsViewModel.checkNewPlaylistEpgUrl() },
            onConfirm = { channelsViewModel.addPlaylist() },
            onDismiss = { channelsViewModel.dismissAddPlaylistDialog() },
        )
    }
}
