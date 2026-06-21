package com.torve.presentation.subscription

import com.torve.presentation.error.defaultMessage
import com.torve.data.auth.AuthClient
import com.torve.data.auth.AuthResult
import com.torve.data.billing.BillingApi
import com.torve.data.billing.StripePurchaseType
import com.torve.data.entitlement.EntitlementApi
import com.torve.data.entitlement.PurchaseVerifyErrorCode
import com.torve.data.subscription.RebateCodeApi
import com.torve.data.subscription.RebateResult
import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.repository.BackendPremiumResult
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.SubscriptionRepository
import com.torve.domain.subscription.PremiumEntitlementRecord
import com.torve.domain.subscription.ResolvedPremiumEntitlement
import com.torve.domain.subscription.resolvePremiumEntitlement
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.torve.platform.torveVerboseLog

private const val KEY_PENDING_AMAZON_VERIFICATION = "subscription_pending_amazon_verification"
private const val DEFAULT_AMAZON_PRODUCT_ID = "com.torve.pro.lifetime"

internal data class SubscriptionEntitlementUiDecision(
    val isPro: Boolean,
    val hasEntitlement: Boolean,
    val isDeviceActivated: Boolean,
    val deviceBlockReason: String?,
    val deviceCapReached: Boolean,
    val needsVerification: Boolean,
)

internal fun resolveSubscriptionEntitlementUiDecision(
    backendResult: BackendPremiumResult?,
): SubscriptionEntitlementUiDecision {
    val decision = when (backendResult) {
        BackendPremiumResult.Active -> SubscriptionEntitlementUiDecision(
            isPro = true,
            hasEntitlement = false,
            isDeviceActivated = true,
            deviceBlockReason = null,
            deviceCapReached = false,
            needsVerification = false,
        )
        is BackendPremiumResult.DeviceBlocked -> SubscriptionEntitlementUiDecision(
            isPro = true,
            hasEntitlement = false,
            isDeviceActivated = true,
            deviceBlockReason = null,
            deviceCapReached = false,
            needsVerification = backendResult.needsVerification,
        )
        BackendPremiumResult.NoEntitlement -> SubscriptionEntitlementUiDecision(
            isPro = true,
            hasEntitlement = false,
            isDeviceActivated = true,
            deviceBlockReason = null,
            deviceCapReached = false,
            needsVerification = false,
        )
        is BackendPremiumResult.Offline -> SubscriptionEntitlementUiDecision(
            isPro = backendResult.localIsPro,
            hasEntitlement = false,
            isDeviceActivated = true,
            deviceBlockReason = null,
            deviceCapReached = false,
            needsVerification = false,
        )
        null -> SubscriptionEntitlementUiDecision(
            isPro = true,
            hasEntitlement = false,
            isDeviceActivated = true,
            deviceBlockReason = null,
            deviceCapReached = false,
            needsVerification = false,
        )
    }
    com.torve.platform.torveVerboseLog {
        "ENTITLEMENT_DECISION: backend=${backendResult?.let { it::class.simpleName } ?: "null"} → isPro=${decision.isPro} hasEntitlement=${decision.hasEntitlement} isDeviceActivated=${decision.isDeviceActivated} deviceBlock=${decision.deviceBlockReason}"
    }
    return decision
}

private fun String?.isDeviceCapBlockReason(): Boolean {
    return when (this?.trim()?.lowercase()) {
        "device_cap_reached",
        "activation_slot_exhausted",
        "no_activation_slots",
        -> true
        else -> false
    }
}

class SubscriptionViewModel(
    private val subscriptionRepo: SubscriptionRepository,
    private val rebateCodeApi: RebateCodeApi,
    private val deviceIdProvider: DeviceIdProvider,
    private val authClient: AuthClient,
    private val entitlementApi: EntitlementApi,
    private val prefsRepo: PreferencesRepository,
    private val billingApi: BillingApi? = null,
    private val strings: PurchaseStringResolver = DefaultPurchaseStringResolver(),
    coroutineScope: CoroutineScope? = null,
    private val resendVerificationEmail: suspend (String) -> AuthResult = authClient::resendVerification,
    private val deviceRegistrationNotifier: com.torve.presentation.session.DeviceRegistrationNotifier? = null,
) {
    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SubscriptionUiState())
    val state: StateFlow<SubscriptionUiState> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var externalBillingPollJob: Job? = null

    init {
        loadPersistedPendingAmazonVerification()
        loadSubscription()
        scope.launch {
            authClient.authUserFlow.collect {
                loadSubscription()
            }
        }
        // Re-fetch /me/access-state after the current device registers
        // with the backend. Without this the first access-state call on a
        // fresh install (which races registration) would pin a stale
        // device_not_registered snapshot and the user would land on the
        // account-access screen when they try to play during a device-state race despite having a
        // valid entitlement.
        deviceRegistrationNotifier?.let { notifier ->
            scope.launch {
                notifier.events.collect {
                    torveVerboseLog { "SUBSCRIPTION: device-registration event — refreshing access state" }
                    loadSubscription()
                }
            }
        }
    }

    private fun maskToken(token: String, visiblePrefix: Int = 8): String {
        if (token.isBlank()) return "<empty>"
        return "${token.take(visiblePrefix)}..."
    }

    private fun currentInstallationIdOrNull(): String? {
        return runCatching {
            deviceIdProvider.getDeviceId().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun logPurchaseMilestone(
        milestone: String,
        detail: String? = null,
        pending: PendingAmazonVerification? = null,
    ) {
        torveVerboseLog {
            buildString {
                append("SUBSCRIPTION_PURCHASE: milestone=$milestone")
                pending?.let {
                    append(" receipt=${maskToken(it.receiptId)}")
                    append(" amazonUser=${maskToken(it.amazonUserId, visiblePrefix = 6)}")
                    append(" productId=${it.productId}")
                    append(" reason=${it.reason}")
                    append(" attempts=${it.attemptCount}")
                }
                detail?.takeIf { it.isNotBlank() }?.let {
                    append(" detail=${it.take(220)}")
                }
            }
        }
    }

    private fun purchaseStatus(
        kind: PurchaseStatusKind,
        title: String,
        message: String,
        tone: PurchaseStatusTone,
        showRetryVerification: Boolean = false,
    ): PurchaseStatusMessage {
        return PurchaseStatusMessage(
            kind = kind,
            title = title,
            message = message,
            tone = tone,
            showRetryVerification = showRetryVerification,
        )
    }

    private fun buildAmazonCallbackPendingStatus(message: String): PurchaseStatusMessage {
        val summary = message.ifBlank { strings.amazonCallbackPendingDefault() }
        return purchaseStatus(
            kind = PurchaseStatusKind.PENDING_VERIFICATION,
            title = strings.purchaseReceivedTitle(),
            message = "$summary ${strings.purchaseReceivedSuffix()}",
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun buildTemporaryVerificationStatus(showRetryVerification: Boolean): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.VERIFICATION_FAILED_TEMPORARILY,
            title = strings.verificationNotFinishedTitle(),
            message = strings.verificationNotFinishedMessage(),
            tone = PurchaseStatusTone.ERROR,
            showRetryVerification = showRetryVerification,
        )
    }

    private fun buildBackendUnavailableStatus(showRetryVerification: Boolean): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.BACKEND_UNAVAILABLE,
            title = strings.verificationUnavailableTitle(),
            message = if (showRetryVerification) {
                strings.verificationUnavailableRetry()
            } else {
                strings.verificationUnavailableRestore()
            },
            tone = PurchaseStatusTone.ERROR,
            showRetryVerification = showRetryVerification,
        )
    }

    private fun buildRestoreSignInRequiredStatus(): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.SIGN_IN_REQUIRED,
            title = strings.signInRequiredTitle(),
            message = strings.signInAmazonRestore(),
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun buildPurchaseSignInRequiredStatus(storeLabel: String): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.SIGN_IN_REQUIRED,
            title = strings.signInRequiredTitle(),
            message = strings.signInBuy(storeLabel),
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun buildRestoreSignInRequiredStatus(storeLabel: String): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.SIGN_IN_REQUIRED,
            title = strings.signInRequiredTitle(),
            message = strings.signInRestore(storeLabel),
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun buildPurchaseConflictStatus(): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.PURCHASE_CONFLICT,
            title = strings.purchaseConflictTitle(),
            message = strings.purchaseConflictMessage(),
            tone = PurchaseStatusTone.ERROR,
        )
    }

    private fun buildRestoreFoundNothingStatus(): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.RESTORE_FOUND_NOTHING,
            title = strings.nothingToRestoreTitle(),
            message = strings.nothingToRestoreAmazon(),
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun premiumPlanLabel(tier: SubscriptionTier): String = strings.premiumPlanLabel(tier)

    private fun resolveEntitlement(records: List<PremiumEntitlementRecord>): ResolvedPremiumEntitlement {
        return resolvePremiumEntitlement(
            records = records,
            nowEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
    }

    private fun buildVerifiedStatus(
        resolvedEntitlement: ResolvedPremiumEntitlement,
        deviceAccess: Boolean,
    ): PurchaseStatusMessage {
        val premiumLabel = premiumPlanLabel(resolvedEntitlement.tier)
        return purchaseStatus(
            kind = PurchaseStatusKind.VERIFIED,
            title = strings.purchaseVerifiedTitle(),
            message = if (deviceAccess) {
                when (resolvedEntitlement.tier) {
                    SubscriptionTier.MONTHLY -> {
                        val date = resolvedEntitlement.expiresAtEpochMs?.let { formatShortDate(it) }
                        date?.let { strings.activeOnDeviceUntil(premiumLabel, it) }
                            ?: strings.activeOnDevice(premiumLabel)
                    }
                    else -> strings.activeOnDevice(premiumLabel)
                }
            } else {
                strings.needsDeviceSlot(premiumLabel)
            },
            tone = PurchaseStatusTone.SUCCESS,
        )
    }

    private fun buildRestoredStatus(
        resolvedEntitlement: ResolvedPremiumEntitlement,
        deviceAccess: Boolean,
    ): PurchaseStatusMessage {
        val premiumLabel = premiumPlanLabel(resolvedEntitlement.tier)
        return purchaseStatus(
            kind = PurchaseStatusKind.RESTORED,
            title = strings.purchaseRestoredTitle(),
            message = if (deviceAccess) {
                when (resolvedEntitlement.tier) {
                    SubscriptionTier.MONTHLY -> {
                        val date = resolvedEntitlement.expiresAtEpochMs?.let { formatShortDate(it) }
                        date?.let { strings.restoredActiveOnDeviceUntil(premiumLabel, it) }
                            ?: strings.restoredActiveOnDevice(premiumLabel)
                    }
                    else -> strings.restoredActiveOnDevice(premiumLabel)
                }
            } else {
                strings.restoredNeedsDeviceSlot(premiumLabel)
            },
            tone = PurchaseStatusTone.SUCCESS,
        )
    }

    private fun formatShortDate(epochMs: Long): String {
        return runCatching {
            val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs)
            val date = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            "${date.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${date.dayOfMonth}, ${date.year}"
        }.getOrElse { "later" }
    }

    private fun shouldClearPurchaseStatusForActiveSubscription(status: PurchaseStatusMessage?): Boolean {
        return status?.kind in setOf(
            PurchaseStatusKind.PENDING_VERIFICATION,
            PurchaseStatusKind.SIGN_IN_REQUIRED,
            PurchaseStatusKind.VERIFICATION_FAILED_TEMPORARILY,
            PurchaseStatusKind.BACKEND_UNAVAILABLE,
        )
    }

    private fun setSignInRequiredStatus(status: PurchaseStatusMessage) {
        _state.update { current ->
            current.copy(
                isPurchasing = false,
                isLoading = false,
                error = null,
                purchaseStatus = status,
                purchaseVerificationState = if (current.pendingAmazonVerification != null) {
                    PurchaseVerificationState.PENDING
                } else {
                    PurchaseVerificationState.FAILED
                },
            )
        }
    }

    private data class PurchaseGateSnapshot(
        val decision: SubscriptionEntitlementUiDecision,
        val tier: SubscriptionTier,
        val sourceStore: String?,
    )

    private fun normalizedPurchaseStore(storeLabel: String?): String? {
        val normalized = storeLabel?.trim()?.lowercase()?.replace('-', '_') ?: return null
        return when {
            normalized.contains("google") -> "google_play"
            normalized.contains("amazon") -> "amazon"
            normalized.contains("apple") -> "apple"
            normalized.contains("stripe") -> "stripe"
            normalized.isBlank() -> null
            else -> normalized
        }
    }

    private fun normalizedSubscriptionStore(value: String?): String? {
        val normalized = value?.trim()?.lowercase()?.replace('-', '_') ?: return null
        return when {
            normalized.contains("google") -> "google_play"
            normalized.contains("amazon") -> "amazon"
            normalized.contains("apple") -> "apple"
            normalized.contains("stripe") -> "stripe"
            normalized == "backend" || normalized == "admin" -> normalized
            normalized.isBlank() -> null
            else -> normalized
        }
    }

    private fun displayStoreLabel(value: String?): String? {
        return when (normalizedSubscriptionStore(value)) {
            "google_play" -> "Google Play"
            "amazon" -> "Amazon Appstore"
            "apple" -> "Apple App Store"
            "stripe" -> "Stripe"
            "backend" -> "Torve"
            "admin" -> "Torve"
            else -> value?.takeIf { it.isNotBlank() }
        }
    }

    private fun purchaseWouldDuplicateEntitlement(
        requestedTier: SubscriptionTier,
        snapshot: PurchaseGateSnapshot,
        requestedStoreLabel: String,
    ): Boolean {
        val hasKnownEntitlement = snapshot.decision.hasEntitlement ||
            snapshot.tier == SubscriptionTier.MONTHLY ||
            snapshot.tier == SubscriptionTier.LIFETIME
        if (!hasKnownEntitlement) return false
        if (requestedTier == SubscriptionTier.MONTHLY) return true
        if (requestedTier == SubscriptionTier.FREE) return true

        val currentStore = normalizedSubscriptionStore(snapshot.sourceStore)
        val requestedStore = normalizedPurchaseStore(requestedStoreLabel)
        val isSameStoreMonthlyUpgrade = snapshot.tier == SubscriptionTier.MONTHLY &&
            currentStore != null &&
            currentStore == requestedStore

        return !isSameStoreMonthlyUpgrade
    }

    private fun buildDuplicatePurchaseBlockedStatus(
        requestedTier: SubscriptionTier,
        snapshot: PurchaseGateSnapshot,
        requestedStoreLabel: String,
    ): PurchaseStatusMessage {
        val currentStoreLabel = displayStoreLabel(snapshot.sourceStore)
        val message = when {
            snapshot.tier == SubscriptionTier.LIFETIME -> {
                "Your account already has Lifetime Premium. Torve refreshed access and did not start another purchase."
            }
            currentStoreLabel != null &&
                normalizedPurchaseStore(currentStoreLabel) != normalizedPurchaseStore(requestedStoreLabel) -> {
                "Your account already has Premium through $currentStoreLabel. Torve will not start a second purchase through $requestedStoreLabel."
            }
            requestedTier == SubscriptionTier.MONTHLY -> {
                "Your account already has Premium. Torve refreshed access and did not start another monthly purchase."
            }
            else -> {
                "Your account already has Premium. Torve refreshed access and did not start another purchase."
            }
        }
        return purchaseStatus(
            kind = PurchaseStatusKind.VERIFIED,
            title = "Premium already active",
            message = message,
            tone = PurchaseStatusTone.INFO,
        )
    }

    private suspend fun refreshBackendAccessForPurchaseGate(): PurchaseGateSnapshot {
        subscriptionRepo.ensureFreeTier()
        val isLoggedIn = authClient.isLoggedIn()
        val backendResult = if (isLoggedIn) subscriptionRepo.refreshFromBackendDetailed() else null
        val sub = subscriptionRepo.getActiveSubscription()
        val entitlementDecision = resolveSubscriptionEntitlementUiDecision(backendResult)
        _state.update { current ->
            val pendingStatus = current.pendingAmazonVerification?.toPurchaseStatusMessage(isLoggedIn, strings)
            val clearTransientPending = current.pendingAmazonVerification == null &&
                current.purchaseStatus?.kind == PurchaseStatusKind.PENDING_VERIFICATION &&
                !entitlementDecision.hasEntitlement
            current.copy(
                subscription = sub,
                isPro = entitlementDecision.isPro,
                isLoading = false,
                isLoggedIn = isLoggedIn,
                hasEntitlement = entitlementDecision.hasEntitlement,
                isDeviceActivated = entitlementDecision.isDeviceActivated,
                deviceBlockReason = entitlementDecision.deviceBlockReason,
                deviceCapReached = entitlementDecision.deviceCapReached,
                needsVerification = entitlementDecision.needsVerification,
                showDeviceLimitReached = entitlementDecision.deviceCapReached,
                purchaseVerificationState = when {
                    current.pendingAmazonVerification != null -> PurchaseVerificationState.PENDING
                    clearTransientPending -> PurchaseVerificationState.IDLE
                    else -> current.purchaseVerificationState
                },
                purchaseStatus = when {
                    pendingStatus != null -> pendingStatus
                    clearTransientPending -> null
                    entitlementDecision.isPro && shouldClearPurchaseStatusForActiveSubscription(current.purchaseStatus) -> null
                    isLoggedIn && current.purchaseStatus?.kind == PurchaseStatusKind.SIGN_IN_REQUIRED -> null
                    else -> current.purchaseStatus
                },
            ).withDerivedBillingPolicy(
                subscription = sub,
                hasEntitlement = entitlementDecision.hasEntitlement,
            )
        }
        return PurchaseGateSnapshot(
            decision = entitlementDecision,
            tier = sub?.tier ?: SubscriptionTier.FREE,
            sourceStore = sub?.platform,
        )
    }

    fun requireAccountForPurchase(storeLabel: String, onAllowed: () -> Unit) {
        requireAccountForPurchase(storeLabel = storeLabel, requestedTier = null, onAllowed = onAllowed)
    }

    fun requireAccountForPurchase(
        storeLabel: String,
        requestedTier: SubscriptionTier?,
        onAllowed: () -> Unit,
    ) {
        scope.launch {
            val accessToken = authClient.getValidAccessToken()
            if (accessToken.isNullOrBlank()) {
                setSignInRequiredStatus(buildPurchaseSignInRequiredStatus(storeLabel))
                return@launch
            }

            _state.update { it.copy(isLoggedIn = true, isPurchasing = true, error = null) }
            val snapshot = runCatching { refreshBackendAccessForPurchaseGate() }.getOrElse {
                _state.update { current ->
                    current.copy(
                        isPurchasing = false,
                        isLoading = false,
                        error = null,
                        purchaseStatus = buildBackendUnavailableStatus(showRetryVerification = false),
                        purchaseVerificationState = PurchaseVerificationState.FAILED,
                    )
                }
                return@launch
            }

            if (requestedTier != null &&
                purchaseWouldDuplicateEntitlement(requestedTier, snapshot, storeLabel)
            ) {
                _state.update { current ->
                    current.copy(
                        isPurchasing = false,
                        error = null,
                        purchaseStatus = buildDuplicatePurchaseBlockedStatus(
                            requestedTier = requestedTier,
                            snapshot = snapshot,
                            requestedStoreLabel = storeLabel,
                        ),
                        purchaseVerificationState = PurchaseVerificationState.IDLE,
                    )
                }
                return@launch
            }

            _state.update { it.copy(isPurchasing = false, error = null) }
            onAllowed()
        }
    }

    fun requireAccountForRestore(storeLabel: String, onAllowed: () -> Unit) {
        scope.launch {
            val hasSession = authClient.getValidAccessToken() != null || authClient.isLoggedIn()
            if (hasSession) {
                _state.update { it.copy(isLoggedIn = true, error = null) }
                onAllowed()
            } else {
                setSignInRequiredStatus(buildRestoreSignInRequiredStatus(storeLabel))
            }
        }
    }

    private fun buildStripeCheckoutOpenedStatus(): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.PENDING_VERIFICATION,
            title = "Checkout opened",
            message = "Checkout is deprecated. Torve is free software and account access is refreshed without payment.",
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun buildStripePortalOpenedStatus(): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.VERIFIED,
            title = "Billing portal opened",
            message = "Billing portal is deprecated. Torve is free software and no billing is required for access.",
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun buildStripeBillingStatus(errorCode: String?, portal: Boolean): PurchaseStatusMessage {
        val title = if (portal) "Billing portal unavailable" else "Checkout unavailable"
        val message = when (errorCode) {
            "stripe_not_configured" ->
                "Billing is temporarily unavailable. Please try again later."
            "stripe_checkout_failed" ->
                "Checkout could not be started. Please try again."
            "stripe_customer_missing" ->
                "No Stripe billing customer is linked to this account. Billing is not required for access."
            "stripe_portal_failed" ->
                "The billing portal could not be opened. Please try again."
            "stripe_invalid_purchase_type" ->
                "That billing option is deprecated. Torve is free software with no paid tiers."
            "stripe_duplicate_subscription" ->
                "Historical Stripe subscription state does not affect access."
            "stripe_lifetime_already_owned" ->
                "Historical lifetime state does not affect access."
            "stripe_cross_store_purchase_blocked" ->
                "Historical store purchase state does not affect access."
            "stripe_upgrade_not_supported",
            "stripe_purchase_not_allowed",
            ->
                "Stripe purchases are deprecated and not required for access."
            else ->
                if (portal) {
                    "The billing portal could not be opened. Please try again."
                } else {
                    "Checkout could not be started. Please try again."
                }
        }
        return purchaseStatus(
            kind = PurchaseStatusKind.BACKEND_UNAVAILABLE,
            title = title,
            message = message,
            tone = PurchaseStatusTone.ERROR,
        )
    }

    fun beginStripeCheckout(
        purchaseType: StripePurchaseType,
        openUrl: (String) -> Unit,
    ) {
        scope.launch {
            _state.update {
                it.copy(
                    isPurchasing = false,
                    error = null,
                    showPaywall = false,
                    purchaseStatus = purchaseStatus(
                        kind = PurchaseStatusKind.RESTORE_FOUND_NOTHING,
                        title = strings.nothingToRestoreTitle(),
                        message = "Checkout is not required. Torve access is free.",
                        tone = PurchaseStatusTone.INFO,
                    ),
                    purchaseVerificationState = PurchaseVerificationState.IDLE,
                )
            }
        }
    }

    fun beginStripePortal(openUrl: (String) -> Unit) {
        scope.launch {
            _state.update {
                it.copy(
                    isPurchasing = false,
                    error = null,
                    showPaywall = false,
                    purchaseStatus = purchaseStatus(
                        kind = PurchaseStatusKind.RESTORE_FOUND_NOTHING,
                        title = strings.nothingToRestoreTitle(),
                        message = "Billing portal is not required. Torve access is free.",
                        tone = PurchaseStatusTone.INFO,
                    ),
                    purchaseVerificationState = PurchaseVerificationState.IDLE,
                )
            }
        }
    }

    private fun startExternalBillingAccessPolling(windowSeconds: Int = 300) {
        externalBillingPollJob?.cancel()
        externalBillingPollJob = scope.launch {
            val deadline = Clock.System.now().toEpochMilliseconds() + windowSeconds * 1000L
            while (isActive && Clock.System.now().toEpochMilliseconds() < deadline) {
                delay(3_000L)
                val snapshot = runCatching { refreshBackendAccessForPurchaseGate() }.getOrNull()
                if (snapshot?.decision?.hasEntitlement == true) {
                    _state.update {
                        it.copy(
                            isPurchasing = false,
                            showPaywall = false,
                            purchaseVerificationState = PurchaseVerificationState.VERIFIED,
                            purchaseStatus = purchaseStatus(
                                kind = PurchaseStatusKind.VERIFIED,
                                title = strings.purchaseVerifiedTitle(),
                                message = strings.activeOnDevice(strings.premiumGeneric()),
                                tone = PurchaseStatusTone.SUCCESS,
                            ),
                        )
                    }
                    return@launch
                }
            }
            _state.update { current ->
                if (
                    current.pendingAmazonVerification == null &&
                    current.purchaseStatus?.kind == PurchaseStatusKind.PENDING_VERIFICATION &&
                    !current.hasEntitlement
                ) {
                    current.copy(purchaseVerificationState = PurchaseVerificationState.IDLE)
                } else {
                    current
                }
            }
        }
    }

    private fun buildPendingAmazonVerification(
        receiptId: String,
        amazonUserId: String,
        productId: String,
        platform: String,
        reason: PendingAmazonVerificationReason,
        previous: PendingAmazonVerification? = null,
        incrementAttempt: Boolean,
        lastMessage: String? = null,
    ): PendingAmazonVerification {
        val now = Clock.System.now().toEpochMilliseconds()
        return PendingAmazonVerification(
            receiptId = receiptId,
            amazonUserId = amazonUserId,
            productId = productId,
            platform = platform,
            reason = reason,
            attemptCount = when {
                incrementAttempt -> (previous?.attemptCount ?: 0) + 1
                previous != null -> previous.attemptCount
                else -> 0
            },
            createdAtEpochMs = previous?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
            lastMessage = lastMessage,
        )
    }

    private suspend fun persistPendingAmazonVerification(pending: PendingAmazonVerification?) {
        if (pending == null) {
            prefsRepo.remove(KEY_PENDING_AMAZON_VERIFICATION)
        } else {
            prefsRepo.setString(KEY_PENDING_AMAZON_VERIFICATION, json.encodeToString(pending))
        }
    }

    private fun loadPersistedPendingAmazonVerification() {
        scope.launch {
            val raw = prefsRepo.getString(KEY_PENDING_AMAZON_VERIFICATION)
            if (raw.isNullOrBlank()) return@launch
            val pending = runCatching { json.decodeFromString<PendingAmazonVerification>(raw) }
                .getOrElse {
                    prefsRepo.remove(KEY_PENDING_AMAZON_VERIFICATION)
                    null
                } ?: return@launch
            val isLoggedIn = authClient.isLoggedIn()
            _state.update {
                it.copy(
                    pendingAmazonVerification = pending,
                    purchaseVerificationState = PurchaseVerificationState.PENDING,
                    purchaseStatus = pending.toPurchaseStatusMessage(isLoggedIn, strings),
                )
            }
            logPurchaseMilestone("pending_context_loaded", pending = pending)
        }
    }

    private suspend fun extractErrorDetail(error: ResponseException): String? {
        val body = runCatching { error.response.bodyAsText() }.getOrNull().orEmpty()
        val jsonDetail = Regex("\"detail\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
        return jsonDetail?.ifBlank { null } ?: body.trim().ifBlank { null }
    }

    /**
     * Parse a standardized `error_code` out of a JSON error body. Returns
     * null when the response isn't JSON, doesn't have the field, or the
     * body can't be read. The raw body is never surfaced to the UI — it
     * may contain backend internals (file paths, stack traces) that must
     * not reach end users.
     */
    private suspend fun extractErrorCode(error: ResponseException): String? {
        val body = runCatching { error.response.bodyAsText() }.getOrNull().orEmpty()
        if (body.isBlank()) return null
        val match = Regex("\"error_code\"\\s*:\\s*\"([a-zA-Z_]+)\"").find(body)
        return match?.groupValues?.getOrNull(1)?.ifBlank { null }
    }

    private suspend fun extractStripeErrorCode(error: ResponseException): String? {
        val body = runCatching { error.response.bodyAsText() }.getOrNull().orEmpty()
        if (body.isBlank()) return null
        val explicit = Regex("\"error_code\"\\s*:\\s*\"([a-zA-Z_]+)\"")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.ifBlank { null }
        if (explicit != null) return explicit
        val known = listOf(
            "stripe_not_configured",
            "stripe_checkout_failed",
            "stripe_customer_missing",
            "stripe_portal_failed",
            "stripe_invalid_purchase_type",
            "stripe_duplicate_subscription",
            "stripe_lifetime_already_owned",
            "stripe_cross_store_purchase_blocked",
            "stripe_upgrade_not_supported",
            "stripe_purchase_not_allowed",
        )
        return known.firstOrNull { body.contains(it, ignoreCase = true) }
    }

    /**
     * Map a Google Play verify error_code to a sanitized [PurchaseStatusMessage].
     * All messages are pre-translated string-resolver output — no raw
     * backend text is interpolated. The distinction between "temporary
     * backend problem" and "purchase can't be matched" drives which tone
     * the UI shows (BACKEND_UNAVAILABLE vs VERIFICATION_FAILED_TEMPORARILY).
     */
    private fun buildGoogleVerifyStatus(errorCode: String?): PurchaseStatusMessage {
        return when (errorCode) {
            PurchaseVerifyErrorCode.CONFIG_MISSING,
            PurchaseVerifyErrorCode.SERVICE_ACCOUNT_FAILURE -> purchaseStatus(
                // Operator-owned issues that the user cannot act on and
                // that should never leak ops detail into the UI.
                kind = PurchaseStatusKind.BACKEND_UNAVAILABLE,
                title = if (errorCode == PurchaseVerifyErrorCode.CONFIG_MISSING) {
                    strings.googleVerifyConfigMissingTitle()
                } else {
                    strings.googleVerifyServiceAccountFailureTitle()
                },
                message = if (errorCode == PurchaseVerifyErrorCode.CONFIG_MISSING) {
                    strings.googleVerifyConfigMissing()
                } else {
                    strings.googleVerifyServiceAccountFailure()
                },
                tone = PurchaseStatusTone.ERROR,
                showRetryVerification = false,
            )
            PurchaseVerifyErrorCode.UPSTREAM_UNREACHABLE -> purchaseStatus(
                kind = PurchaseStatusKind.BACKEND_UNAVAILABLE,
                title = strings.googleVerifyUpstreamUnreachableTitle(),
                message = strings.googleVerifyUpstreamUnreachable(),
                tone = PurchaseStatusTone.ERROR,
                showRetryVerification = false,
            )
            PurchaseVerifyErrorCode.PRODUCT_MISMATCH -> purchaseStatus(
                kind = PurchaseStatusKind.VERIFICATION_FAILED_TEMPORARILY,
                title = strings.googleVerifyProductMismatchTitle(),
                message = strings.googleVerifyProductMismatch(),
                tone = PurchaseStatusTone.ERROR,
                showRetryVerification = false,
            )
            PurchaseVerifyErrorCode.NOT_VERIFIED -> purchaseStatus(
                kind = PurchaseStatusKind.VERIFICATION_FAILED_TEMPORARILY,
                title = strings.googleVerifyNotVerifiedTitle(),
                message = strings.googleVerifyNotVerified(),
                tone = PurchaseStatusTone.ERROR,
                showRetryVerification = false,
            )
            PurchaseVerifyErrorCode.DUPLICATE_ACTIVE_ENTITLEMENT -> purchaseStatus(
                kind = PurchaseStatusKind.VERIFIED,
                title = "Premium already active",
                message = "Premium is already active on this account. Torve refreshed access and did not start another purchase.",
                tone = PurchaseStatusTone.INFO,
                showRetryVerification = false,
            )
            PurchaseVerifyErrorCode.CROSS_STORE_CONFLICT -> purchaseStatus(
                kind = PurchaseStatusKind.PURCHASE_CONFLICT,
                title = "Premium already active",
                message = "Premium is already active through another store. Manage billing there instead of starting a second purchase.",
                tone = PurchaseStatusTone.ERROR,
                showRetryVerification = false,
            )
            PurchaseVerifyErrorCode.ENTITLEMENT_EXPIRED,
            PurchaseVerifyErrorCode.ENTITLEMENT_REVOKED,
            -> purchaseStatus(
                kind = PurchaseStatusKind.VERIFICATION_FAILED_TEMPORARILY,
                title = strings.googleVerifyNotVerifiedTitle(),
                message = "This premium entitlement is no longer active. Torve refreshed account access.",
                tone = PurchaseStatusTone.ERROR,
                showRetryVerification = false,
            )
            // Unknown error_code or null → fall back to the existing
            // generic message. Still pre-translated, never raw.
            else -> purchaseStatus(
                kind = PurchaseStatusKind.VERIFICATION_FAILED_TEMPORARILY,
                title = strings.verificationNotFinishedTitle(),
                message = strings.googleVerifyFailed(),
                tone = PurchaseStatusTone.ERROR,
                showRetryVerification = false,
            )
        }
    }

    private fun isProbablyBackendUnavailable(error: Throwable): Boolean {
        if (error is ServerResponseException) return true
        val message = error.message.orEmpty().lowercase()
        return listOf(
            "timeout",
            "timed out",
            "connect",
            "connection",
            "network",
            "host",
            "unresolved",
            "resolve",
            "refused",
            "econn",
        ).any(message::contains)
    }

    private data class AmazonFailureResolution(
        val status: PurchaseStatusMessage,
        val pendingReason: PendingAmazonVerificationReason? = null,
        val clearPendingContext: Boolean = false,
    )

    private suspend fun classifyAmazonVerificationFailure(
        error: Throwable,
        retryAvailable: Boolean,
    ): AmazonFailureResolution {
        if (error is ClientRequestException) {
            val statusCode = error.response.status.value
            val detail = extractErrorDetail(error).orEmpty()
            if (statusCode == 409 || detail.contains("different account", ignoreCase = true)) {
                return AmazonFailureResolution(
                    status = buildPurchaseConflictStatus(),
                    clearPendingContext = true,
                )
            }
            if (statusCode >= 500) {
                return AmazonFailureResolution(
                    status = buildBackendUnavailableStatus(retryAvailable),
                    pendingReason = PendingAmazonVerificationReason.BACKEND_UNAVAILABLE.takeIf { retryAvailable },
                )
            }
            return AmazonFailureResolution(
                status = buildTemporaryVerificationStatus(retryAvailable),
                pendingReason = PendingAmazonVerificationReason.TEMPORARY_FAILURE.takeIf { retryAvailable },
            )
        }
        if (error is ServerResponseException || isProbablyBackendUnavailable(error)) {
            return AmazonFailureResolution(
                status = buildBackendUnavailableStatus(retryAvailable),
                pendingReason = PendingAmazonVerificationReason.BACKEND_UNAVAILABLE.takeIf { retryAvailable },
            )
        }
        return AmazonFailureResolution(
            status = buildTemporaryVerificationStatus(retryAvailable),
            pendingReason = PendingAmazonVerificationReason.TEMPORARY_FAILURE.takeIf { retryAvailable },
        )
    }

    fun loadSubscription() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                subscriptionRepo.ensureFreeTier()
                val isLoggedIn = authClient.isLoggedIn()
                val backendResult = if (isLoggedIn) subscriptionRepo.refreshFromBackendDetailed() else null
                val sub = subscriptionRepo.getActiveSubscription()
                val entitlementDecision = resolveSubscriptionEntitlementUiDecision(
                    backendResult = backendResult,
                )

                torveVerboseLog {
                    "SUBSCRIPTION_GATE decision backendResult=${backendResult?.let { it::class.simpleName } ?: "none"} isPro=${entitlementDecision.isPro} hasEntitlement=${entitlementDecision.hasEntitlement} isDeviceActivated=${entitlementDecision.isDeviceActivated} needsVerification=${entitlementDecision.needsVerification}"
                }
                _state.update { current ->
                    val pendingStatus = current.pendingAmazonVerification?.toPurchaseStatusMessage(isLoggedIn, strings)
                    current.copy(
                        subscription = sub,
                        isPro = entitlementDecision.isPro,
                        isLoading = false,
                        isLoggedIn = isLoggedIn,
                        hasEntitlement = entitlementDecision.hasEntitlement,
                        isDeviceActivated = entitlementDecision.isDeviceActivated,
                        deviceBlockReason = entitlementDecision.deviceBlockReason,
                        deviceCapReached = entitlementDecision.deviceCapReached,
                        needsVerification = entitlementDecision.needsVerification,
                        showDeviceLimitReached = entitlementDecision.deviceCapReached,
                        purchaseVerificationState = if (current.pendingAmazonVerification != null) {
                            PurchaseVerificationState.PENDING
                        } else {
                            current.purchaseVerificationState
                        },
                        purchaseStatus = when {
                            pendingStatus != null -> pendingStatus
                            entitlementDecision.isPro && shouldClearPurchaseStatusForActiveSubscription(current.purchaseStatus) -> null
                            isLoggedIn && current.purchaseStatus?.kind == PurchaseStatusKind.SIGN_IN_REQUIRED -> null
                            else -> current.purchaseStatus
                        },
                    ).withDerivedBillingPolicy(
                        subscription = sub,
                        hasEntitlement = entitlementDecision.hasEntitlement,
                    )
                }
                torveVerboseLog {
                    "SUBSCRIPTION: Entitlement refresh result isPro=${entitlementDecision.isPro} hasEntitlement=${entitlementDecision.hasEntitlement} isDeviceActivated=${entitlementDecision.isDeviceActivated} needsVerification=${entitlementDecision.needsVerification} deviceBlockReason=${entitlementDecision.deviceBlockReason} loggedIn=$isLoggedIn"
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = com.torve.presentation.error.UserFacingError.UNKNOWN.defaultMessage()) }
                torveVerboseLog { "SUBSCRIPTION: Entitlement refresh failed: ${e.message ?: "unknown"}" }
            }
        }
    }

    /**
     * Called after a native store purchase succeeds.
     * Sends the purchase data to the backend for verification.
     */
    fun verifyGooglePurchase(productId: String, purchaseToken: String, platform: String) {
        scope.launch {
            torveVerboseLog {
                "SUBSCRIPTION: Google purchase callback received productId=$productId token=${maskToken(purchaseToken)} platform=$platform"
            }
            _state.update {
                it.copy(
                    isPurchasing = true,
                    error = null,
                    purchaseStatus = null,
                    purchaseVerificationState = PurchaseVerificationState.PENDING,
                )
            }
            try {
                val accessToken = authClient.getValidAccessToken()
                if (accessToken.isNullOrBlank()) {
                    setSignInRequiredStatus(buildPurchaseSignInRequiredStatus("Google Play"))
                    return@launch
                }
                torveVerboseLog { "SUBSCRIPTION: Sending Google verify request" }
                val result = entitlementApi.verifyGooglePurchase(
                    accessToken = accessToken,
                    productId = productId,
                    purchaseToken = purchaseToken,
                    platform = platform,
                    installationId = currentInstallationIdOrNull(),
                )
                torveVerboseLog {
                    "SUBSCRIPTION: Google verify response received verified=${result.verified} entitlementGranted=${result.entitlement_granted} premiumAccess=${result.premium_access} entitlements=${result.entitlements.size} errorCode=${result.error_code ?: "none"}"
                }
                // Authoritative success criterion: backend says verified
                // == true, OR error_code == already_verified (idempotent
                // replay — still a success). Anything else is a real
                // failure that should surface a typed error message.
                val errorCode = result.error_code
                val isVerified = result.verified == true ||
                    errorCode == PurchaseVerifyErrorCode.ALREADY_VERIFIED
                if (!isVerified) {
                    _state.update {
                        it.copy(
                            isPurchasing = false,
                            error = null,
                            purchaseStatus = buildGoogleVerifyStatus(errorCode),
                            purchaseVerificationState = PurchaseVerificationState.FAILED,
                        )
                    }
                    return@launch
                }
                handleVerifySuccess()
            } catch (e: Exception) {
                // Backend returned non-2xx OR the response body didn't
                // match our DTO. ClientRequestException wraps the 4xx body
                // which may carry error_code; we still extract it so an
                // already_verified replay returned via an unexpected
                // status (or a deserialiser-rejected shape) reads as
                // success and triggers the same access-state refresh as
                // the happy path. Real failures fall through to the
                // sanitised status message.
                val errorCode = if (e is ResponseException) extractErrorCode(e) else null
                if (errorCode == PurchaseVerifyErrorCode.ALREADY_VERIFIED) {
                    torveVerboseLog { "SUBSCRIPTION: verify body indicated already_verified — treating as success" }
                    handleVerifySuccess()
                    return@launch
                }
                val statusMessage = buildGoogleVerifyStatus(errorCode)
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        error = null,
                        purchaseStatus = statusMessage,
                        purchaseVerificationState = PurchaseVerificationState.FAILED,
                    )
                }
            }
        }
    }

    /**
     * Common post-success path for both fresh-verify and idempotent
     * already_verified replays. Triggers a /me/access-state refresh —
     * which is the *only* authoritative source for premium UI flags.
     * The verify response's per-entitlement fields are unreliable now
     * that the backend's verify body is just {verified, message,
     * error_code}: the entitlement list and premium_access flag may be
     * absent, so we MUST read the server's recomputed access state.
     */
    private fun handleVerifySuccess() {
        scope.launch {
            externalBillingPollJob?.cancel()
            val snapshot = runCatching { refreshBackendAccessForPurchaseGate() }.getOrNull()
            val decision = snapshot?.decision
            val activeOnDevice = decision?.isPro == true
            val verifiedOnAccount = decision?.hasEntitlement == true
            // Refresh state from /me/access-state — replaces purchaseStatus,
            // hasEntitlement, isDeviceActivated, etc. with whatever the
            // server actually says.
            _state.update {
                it.copy(
                    isPurchasing = false,
                    showPaywall = !activeOnDevice,
                    purchaseVerificationState = if (verifiedOnAccount) {
                        PurchaseVerificationState.VERIFIED
                    } else {
                        PurchaseVerificationState.PENDING
                    },
                    purchaseStatus = when {
                        activeOnDevice -> purchaseStatus(
                            kind = PurchaseStatusKind.VERIFIED,
                            title = strings.purchaseVerifiedTitle(),
                        // Generic celebratory copy — the specific tier and
                        // expiry are shown by the access-status label,
                        // which loadSubscription() refreshes from the
                        // server's recomputed access state.
                            message = strings.activeOnDevice(strings.premiumGeneric()),
                            tone = PurchaseStatusTone.SUCCESS,
                        )
                        verifiedOnAccount -> purchaseStatus(
                            kind = PurchaseStatusKind.VERIFIED,
                            title = strings.purchaseVerifiedTitle(),
                            message = strings.needsDeviceSlot(strings.premiumGeneric()),
                            tone = PurchaseStatusTone.INFO,
                        )
                        else -> purchaseStatus(
                            kind = PurchaseStatusKind.PENDING_VERIFICATION,
                            title = strings.verificationPendingTitle(),
                            message = "Payment records are historical. Torve is refreshing account access.",
                            tone = PurchaseStatusTone.INFO,
                        )
                    },
                )
            }
        }
    }

    /**
     * Called after an Apple StoreKit purchase succeeds on iOS.
     * Sends the JWS transaction to the backend for verification.
     */
    fun verifyApplePurchase(transactionJws: String, productId: String) {
        scope.launch {
            _state.update {
                it.copy(
                    isPurchasing = true,
                    error = null,
                    purchaseStatus = null,
                    purchaseVerificationState = PurchaseVerificationState.PENDING,
                )
            }
            try {
                val accessToken = authClient.getValidAccessToken()
                if (accessToken.isNullOrBlank()) {
                    setSignInRequiredStatus(buildPurchaseSignInRequiredStatus("Apple App Store"))
                    return@launch
                }
                val result = entitlementApi.verifyApplePurchase(
                    accessToken = accessToken,
                    transactionJws = transactionJws,
                    productId = productId,
                    platform = "ios",
                )
                val resolvedEntitlement = resolveEntitlement(
                    result.entitlements.map { entitlement ->
                        PremiumEntitlementRecord(
                            key = entitlement.key,
                            status = entitlement.status,
                            sourceStore = entitlement.source_store,
                            endsAt = entitlement.ends_at,
                        )
                    },
                )
                val backendDecision = runCatching { refreshBackendAccessForPurchaseGate().decision }.getOrNull()
                val backendHasEntitlement = backendDecision?.hasEntitlement == true
                val backendDeviceAccess = backendDecision?.isPro == true
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        showPaywall = !backendDeviceAccess,
                        hasEntitlement = backendHasEntitlement,
                        isDeviceActivated = backendDeviceAccess,
                        deviceBlockReason = null,
                        deviceCapReached = backendHasEntitlement && !backendDeviceAccess,
                        showDeviceLimitReached = backendHasEntitlement && !backendDeviceAccess,
                        purchaseVerificationState = if (backendHasEntitlement) {
                            PurchaseVerificationState.VERIFIED
                        } else {
                            PurchaseVerificationState.PENDING
                        },
                        purchaseStatus = if (backendHasEntitlement) {
                            buildVerifiedStatus(resolvedEntitlement, backendDeviceAccess)
                        } else {
                            buildTemporaryVerificationStatus(showRetryVerification = false)
                        },
                    ).withDerivedBillingPolicy(hasEntitlement = backendHasEntitlement)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        error = strings.appleVerifyFailed(),
                        purchaseVerificationState = PurchaseVerificationState.FAILED,
                    )
                }
            }
        }
    }

    fun markPurchasePending(message: String) {
        val status = buildAmazonCallbackPendingStatus(message.trim())
        logPurchaseMilestone("amazon_callback_pending", detail = status.message)
        _state.update {
            it.copy(
                isPurchasing = false,
                error = null,
                purchaseStatus = status,
                purchaseVerificationState = PurchaseVerificationState.PENDING,
            )
        }
    }

    fun markAmazonPurchasePending(message: String) {
        markPurchasePending(message)
    }

    fun verifyAmazonPurchase(
        receiptId: String,
        amazonUserId: String,
        productId: String,
        platform: String = "amazon_fire_tv",
    ) {
        scope.launch {
            val sanitizedReceipt = receiptId.trim()
            val sanitizedUserId = amazonUserId.trim()
            val sanitizedProductId = productId.trim().ifBlank { DEFAULT_AMAZON_PRODUCT_ID }
            torveVerboseLog {
                "SUBSCRIPTION: Amazon purchase callback received receipt=${maskToken(sanitizedReceipt)} productId=$sanitizedProductId hasUserId=${sanitizedUserId.isNotBlank()} platform=$platform"
            }
            _state.update {
                it.copy(
                    isPurchasing = true,
                    error = null,
                    purchaseStatus = null,
                    purchaseVerificationState = PurchaseVerificationState.PENDING,
                )
            }

            if (sanitizedReceipt.isBlank() || sanitizedUserId.isBlank()) {
                val status = buildAmazonCallbackPendingStatus(
                    strings.amazonCallbackPendingDefault(),
                )
                logPurchaseMilestone("amazon_verify_waiting_for_account_data", detail = status.message)
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        purchaseStatus = status,
                        purchaseVerificationState = PurchaseVerificationState.PENDING,
                    )
                }
                return@launch
            }

            val previousPending = _state.value.pendingAmazonVerification
                ?.takeIf { it.receiptId == sanitizedReceipt }
            val accessToken = authClient.getValidAccessToken()

            if (accessToken.isNullOrBlank()) {
                val pending = buildPendingAmazonVerification(
                    receiptId = sanitizedReceipt,
                    amazonUserId = sanitizedUserId,
                    productId = sanitizedProductId,
                    platform = platform,
                    reason = previousPending?.reason ?: PendingAmazonVerificationReason.RETRY_VERIFICATION,
                    previous = previousPending,
                    incrementAttempt = false,
                    lastMessage = strings.pendingRetryMessage(),
                )
                persistPendingAmazonVerification(pending)
                val status = pending.toPurchaseStatusMessage(isLoggedIn = false, strings = strings)
                logPurchaseMilestone("amazon_verify_waiting_for_sign_in", detail = status.message, pending = pending)
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        error = null,
                        purchaseStatus = status,
                        purchaseVerificationState = PurchaseVerificationState.PENDING,
                        pendingAmazonVerification = pending,
                    )
                }
                return@launch
            }

            val pendingForAttempt = buildPendingAmazonVerification(
                receiptId = sanitizedReceipt,
                amazonUserId = sanitizedUserId,
                productId = sanitizedProductId,
                platform = platform,
                reason = PendingAmazonVerificationReason.RETRY_VERIFICATION,
                previous = previousPending,
                incrementAttempt = true,
            )
            persistPendingAmazonVerification(pendingForAttempt)
            _state.update { it.copy(pendingAmazonVerification = pendingForAttempt) }
            logPurchaseMilestone("amazon_verify_request_started", pending = pendingForAttempt)

            try {
                val result = entitlementApi.verifyAmazonPurchase(
                    accessToken = accessToken,
                    receiptId = sanitizedReceipt,
                    amazonUserId = sanitizedUserId,
                    productId = sanitizedProductId,
                    platform = platform,
                    installationId = currentInstallationIdOrNull(),
                )
                val resolvedEntitlement = resolveEntitlement(
                    result.entitlements.map { entitlement ->
                        PremiumEntitlementRecord(
                            key = entitlement.key,
                            status = entitlement.status,
                            sourceStore = entitlement.source_store,
                            endsAt = entitlement.ends_at,
                        )
                    },
                )
                val backendDecision = runCatching { refreshBackendAccessForPurchaseGate().decision }.getOrNull()
                val backendHasEntitlement = backendDecision?.hasEntitlement == true
                val backendDeviceAccess = backendDecision?.isPro == true
                persistPendingAmazonVerification(null)
                val status = if (backendHasEntitlement) {
                    buildVerifiedStatus(resolvedEntitlement, backendDeviceAccess)
                } else {
                    buildTemporaryVerificationStatus(showRetryVerification = true)
                }
                logPurchaseMilestone(
                    milestone = "amazon_verify_success",
                    detail = "premiumAccess=$backendDeviceAccess entitlements=${result.entitlements.size}",
                )
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        showPaywall = !backendDeviceAccess,
                        error = null,
                        hasEntitlement = backendHasEntitlement,
                        isDeviceActivated = backendDeviceAccess,
                        deviceBlockReason = backendDecision?.deviceBlockReason,
                        deviceCapReached = backendHasEntitlement && !backendDeviceAccess,
                        showDeviceLimitReached = backendHasEntitlement && !backendDeviceAccess,
                        purchaseVerificationState = if (backendHasEntitlement) {
                            PurchaseVerificationState.VERIFIED
                        } else {
                            PurchaseVerificationState.PENDING
                        },
                        purchaseStatus = status,
                        pendingAmazonVerification = null,
                    ).withDerivedBillingPolicy(hasEntitlement = backendHasEntitlement)
                }
            } catch (error: Throwable) {
                val resolution = classifyAmazonVerificationFailure(error, retryAvailable = true)
                if (resolution.clearPendingContext) {
                    persistPendingAmazonVerification(null)
                    _state.update {
                        it.copy(
                            isPurchasing = false,
                            error = null,
                            purchaseStatus = resolution.status,
                            purchaseVerificationState = PurchaseVerificationState.FAILED,
                            pendingAmazonVerification = null,
                        )
                    }
                    logPurchaseMilestone(
                        milestone = "amazon_verify_failed",
                        detail = "${resolution.status.title}: ${resolution.status.message}",
                    )
                    return@launch
                }

                val failedPending = buildPendingAmazonVerification(
                    receiptId = sanitizedReceipt,
                    amazonUserId = sanitizedUserId,
                    productId = sanitizedProductId,
                    platform = platform,
                    reason = resolution.pendingReason ?: PendingAmazonVerificationReason.RETRY_VERIFICATION,
                    previous = pendingForAttempt,
                    incrementAttempt = false,
                    lastMessage = resolution.status.message,
                )
                persistPendingAmazonVerification(failedPending)
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        error = null,
                        purchaseStatus = resolution.status,
                        purchaseVerificationState = PurchaseVerificationState.PENDING,
                        pendingAmazonVerification = failedPending,
                    )
                }
                logPurchaseMilestone(
                    milestone = "amazon_verify_failed",
                    detail = "${resolution.status.title}: ${resolution.status.message}",
                    pending = failedPending,
                )
            }
        }
    }

    fun retryPendingAmazonVerification() {
        val pending = _state.value.pendingAmazonVerification ?: return
        logPurchaseMilestone("amazon_verify_retry_requested", pending = pending)
        verifyAmazonPurchase(
            receiptId = pending.receiptId,
            amazonUserId = pending.amazonUserId,
            productId = pending.productId,
            platform = pending.platform,
        )
    }

    fun restoreAmazonPurchases(platform: String = "amazon_fire_tv") {
        scope.launch {
            val existingPending = _state.value.pendingAmazonVerification
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                )
            }

            val accessToken = authClient.getValidAccessToken()
            if (accessToken.isNullOrBlank()) {
                val status = existingPending?.toPurchaseStatusMessage(isLoggedIn = false, strings = strings)
                    ?: buildRestoreSignInRequiredStatus()
                logPurchaseMilestone("amazon_restore_waiting_for_sign_in", detail = status.message, pending = existingPending)
                _state.update {
                    it.copy(
                        isLoading = false,
                        purchaseStatus = status,
                        purchaseVerificationState = if (existingPending != null) {
                            PurchaseVerificationState.PENDING
                        } else {
                            PurchaseVerificationState.FAILED
                        },
                        pendingAmazonVerification = existingPending,
                    )
                }
                return@launch
            }

            logPurchaseMilestone("amazon_restore_started", detail = "platform=$platform", pending = existingPending)

            try {
                val result = entitlementApi.restorePurchases(
                    accessToken = accessToken,
                    store = "amazon",
                    platform = platform,
                )
                val resolvedEntitlement = resolveEntitlement(
                    result.entitlements.map { entitlement ->
                        PremiumEntitlementRecord(
                            key = entitlement.key,
                            status = entitlement.status,
                            sourceStore = entitlement.source_store,
                            endsAt = entitlement.ends_at,
                        )
                    },
                )
                val hasEntitlement = result.entitlements.isNotEmpty()

                if (hasEntitlement) {
                    val backendDecision = runCatching { refreshBackendAccessForPurchaseGate().decision }.getOrNull()
                    val backendHasEntitlement = backendDecision?.hasEntitlement == true
                    val backendDeviceAccess = backendDecision?.isPro == true
                    persistPendingAmazonVerification(null)
                    val status = if (backendHasEntitlement) {
                        buildRestoredStatus(resolvedEntitlement, backendDeviceAccess)
                    } else {
                        buildTemporaryVerificationStatus(showRetryVerification = true)
                    }
                    logPurchaseMilestone(
                        milestone = "amazon_restore_success",
                        detail = "premiumAccess=$backendDeviceAccess entitlements=${result.entitlements.size}",
                    )
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            showPaywall = !backendDeviceAccess,
                            hasEntitlement = backendHasEntitlement,
                            isDeviceActivated = backendDeviceAccess,
                            deviceBlockReason = backendDecision?.deviceBlockReason,
                            deviceCapReached = backendHasEntitlement && !backendDeviceAccess,
                            showDeviceLimitReached = backendHasEntitlement && !backendDeviceAccess,
                            purchaseVerificationState = if (backendHasEntitlement) {
                                PurchaseVerificationState.RESTORED
                            } else {
                                PurchaseVerificationState.PENDING
                            },
                            purchaseStatus = status,
                            pendingAmazonVerification = null,
                        ).withDerivedBillingPolicy(hasEntitlement = backendHasEntitlement)
                    }
                } else {
                    val status = existingPending?.toPurchaseStatusMessage(isLoggedIn = true, strings = strings)
                        ?: buildRestoreFoundNothingStatus()
                    logPurchaseMilestone(
                        milestone = if (existingPending != null) {
                            "amazon_restore_no_entitlement_pending_retained"
                        } else {
                            "amazon_restore_found_nothing"
                        },
                        detail = status.message,
                        pending = existingPending,
                    )
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            purchaseStatus = status,
                            purchaseVerificationState = if (existingPending != null) {
                                PurchaseVerificationState.PENDING
                            } else {
                                PurchaseVerificationState.FAILED
                            },
                            pendingAmazonVerification = existingPending,
                        )
                    }
                }
            } catch (error: Throwable) {
                val resolution = classifyAmazonVerificationFailure(
                    error = error,
                    retryAvailable = existingPending != null,
                )
                if (existingPending != null && resolution.pendingReason != null) {
                    val updatedPending = buildPendingAmazonVerification(
                        receiptId = existingPending.receiptId,
                        amazonUserId = existingPending.amazonUserId,
                        productId = existingPending.productId,
                        platform = existingPending.platform.ifBlank { platform },
                        reason = resolution.pendingReason,
                        previous = existingPending,
                        incrementAttempt = false,
                        lastMessage = resolution.status.message,
                    )
                    persistPendingAmazonVerification(updatedPending)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            purchaseStatus = updatedPending.toPurchaseStatusMessage(isLoggedIn = true, strings = strings),
                            purchaseVerificationState = PurchaseVerificationState.PENDING,
                            pendingAmazonVerification = updatedPending,
                        )
                    }
                    logPurchaseMilestone(
                        milestone = "amazon_restore_failed_pending_retained",
                        detail = resolution.status.message,
                        pending = updatedPending,
                    )
                } else {
                    if (resolution.clearPendingContext) {
                        persistPendingAmazonVerification(null)
                    }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            purchaseStatus = resolution.status,
                            purchaseVerificationState = PurchaseVerificationState.FAILED,
                            pendingAmazonVerification = if (resolution.clearPendingContext) null else existingPending,
                        )
                    }
                    logPurchaseMilestone(
                        milestone = "amazon_restore_failed",
                        detail = "${resolution.status.title}: ${resolution.status.message}",
                        pending = existingPending.takeUnless { resolution.clearPendingContext },
                    )
                }
            }
        }
    }

    fun restoreStorePurchases(store: String, platform: String, storeLabel: String) {
        scope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    purchaseStatus = null,
                )
            }

            val accessToken = authClient.getValidAccessToken()
            if (accessToken.isNullOrBlank()) {
                setSignInRequiredStatus(buildRestoreSignInRequiredStatus(storeLabel))
                return@launch
            }

            try {
                val result = entitlementApi.restorePurchases(
                    accessToken = accessToken,
                    store = store,
                    platform = platform,
                )
                val resolvedEntitlement = resolveEntitlement(
                    result.entitlements.map { entitlement ->
                        PremiumEntitlementRecord(
                            key = entitlement.key,
                            status = entitlement.status,
                            sourceStore = entitlement.source_store,
                            endsAt = entitlement.ends_at,
                        )
                    },
                )
                val hasEntitlement = result.entitlements.isNotEmpty()

                if (hasEntitlement) {
                    val backendDecision = runCatching { refreshBackendAccessForPurchaseGate().decision }.getOrNull()
                    val backendHasEntitlement = backendDecision?.hasEntitlement == true
                    val backendDeviceAccess = backendDecision?.isPro == true
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            showPaywall = !backendDeviceAccess,
                            hasEntitlement = backendHasEntitlement,
                            isDeviceActivated = backendDeviceAccess,
                            deviceBlockReason = backendDecision?.deviceBlockReason,
                            deviceCapReached = backendHasEntitlement && !backendDeviceAccess,
                            showDeviceLimitReached = backendHasEntitlement && !backendDeviceAccess,
                            purchaseVerificationState = if (backendHasEntitlement) {
                                PurchaseVerificationState.RESTORED
                            } else {
                                PurchaseVerificationState.PENDING
                            },
                            purchaseStatus = if (backendHasEntitlement) {
                                buildRestoredStatus(resolvedEntitlement, backendDeviceAccess)
                            } else {
                                buildTemporaryVerificationStatus(showRetryVerification = false)
                            },
                        ).withDerivedBillingPolicy(hasEntitlement = backendHasEntitlement)
                    }
                } else {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            purchaseStatus = purchaseStatus(
                                kind = PurchaseStatusKind.RESTORE_FOUND_NOTHING,
                                title = strings.nothingToRestoreTitle(),
                                message = strings.nothingToRestoreStore(storeLabel),
                                tone = PurchaseStatusTone.INFO,
                            ),
                            purchaseVerificationState = PurchaseVerificationState.FAILED,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = strings.storeRestoreFailed(storeLabel),
                        purchaseVerificationState = PurchaseVerificationState.FAILED,
                    )
                }
            }
        }
    }

    /**
     * Client-driven Google Play restore. The user taps "Restore Purchases".
     *
     * Flow (matches the production backend's expectation, 2026-04-26):
     *  1. Caller passes the result of `BillingClient.queryPurchasesAsync`
     *     for SUBS + INAPP — every active Play purchase the device knows
     *     about.
     *  2. We POST each token to `/me/purchases/google-play/verify`.
     *     Backend treats already-verified replays as success (returns
     *     `error_code: "already_verified"`). Verify failures are
     *     surfaced and abort.
     *  3. POST `/me/purchases/restore` (canonical, NOT the legacy
     *     `/purchases/restore`) so the backend recomputes premium flags
     *     and includes any ledger-only lifetime grant (admin grants,
     *     rebate codes).
     *  4. `loadSubscription()` refreshes /me/access-state — the
     *     authoritative source of premium UI flags.
     *
     * If [activePlayPurchases] is empty AND no ledger-only grant exists
     * server-side, `restored=false` comes back and we surface
     * "nothing to restore". Lifetime ledger grants survive this path
     * because step 3 doesn't depend on Play tokens.
     */
    fun restoreGooglePlayPurchases(
        activePlayPurchases: List<GooglePlayActivePurchase>,
        platform: String,
        storeLabel: String,
    ) {
        scope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    purchaseStatus = null,
                )
            }
            val accessToken = authClient.getValidAccessToken()
            if (accessToken.isNullOrBlank()) {
                setSignInRequiredStatus(buildRestoreSignInRequiredStatus(storeLabel))
                return@launch
            }

            // ── Step 1+2: per-token verify ───────────────────────────
            // Each token POSTs to /me/purchases/google-play/verify. The
            // backend handles idempotency via error_code=already_verified;
            // we treat that as success and continue. Any non-success code
            // aborts with the typed error message — do not silently
            // swallow because that's how users miss real billing config
            // failures (config_missing, service_account_failure).
            for (purchase in activePlayPurchases) {
                val verifyResult = runCatching {
                    entitlementApi.verifyGooglePurchase(
                        accessToken = accessToken,
                        productId = purchase.productId,
                        purchaseToken = purchase.purchaseToken,
                        platform = platform,
                        installationId = currentInstallationIdOrNull(),
                    )
                }
                val verified = verifyResult.getOrNull()
                val errorCode = verified?.error_code
                    ?: (verifyResult.exceptionOrNull() as? ResponseException)?.let { extractErrorCode(it) }
                val isOk = verified?.verified == true ||
                    errorCode == PurchaseVerifyErrorCode.ALREADY_VERIFIED
                if (!isOk) {
                    torveVerboseLog {
                        "SUBSCRIPTION: restore verify failed productId=${purchase.productId} errorCode=${errorCode ?: "unknown"}"
                    }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            purchaseStatus = buildGoogleVerifyStatus(errorCode),
                            purchaseVerificationState = PurchaseVerificationState.FAILED,
                        )
                    }
                    return@launch
                }
                torveVerboseLog {
                    "SUBSCRIPTION: restore verify ok productId=${purchase.productId} errorCode=${errorCode ?: "fresh"}"
                }
            }

            // ── Step 3: canonical recompute ──────────────────────────
            // /me/purchases/restore picks up ledger-only lifetime grants
            // that Play doesn't know about (admin grants, rebate codes).
            // Always called even when activePlayPurchases was empty.
            val restoreResult = runCatching {
                entitlementApi.restorePurchasesCanonical(accessToken)
            }.getOrNull()

            // ── Step 4: refresh /me/access-state — source of truth ──
            val backendDecision = runCatching { refreshBackendAccessForPurchaseGate().decision }.getOrNull()

            val anythingRestored = restoreResult?.restored == true ||
                backendDecision?.hasEntitlement == true ||
                activePlayPurchases.isNotEmpty()
            val activeOnDevice = backendDecision?.isPro == true
            _state.update {
                it.copy(
                    isLoading = false,
                    error = null,
                    showPaywall = !activeOnDevice,
                    purchaseVerificationState = if (anythingRestored) {
                        PurchaseVerificationState.RESTORED
                    } else {
                        PurchaseVerificationState.FAILED
                    },
                    purchaseStatus = if (anythingRestored) {
                        purchaseStatus(
                            kind = PurchaseStatusKind.RESTORED,
                            title = strings.purchaseRestoredTitle(),
                            message = if (activeOnDevice) {
                                strings.restoredActiveOnDevice(strings.premiumGeneric())
                            } else {
                                "Purchase restore is deprecated. Torve is refreshing account access."
                            },
                            tone = if (activeOnDevice) PurchaseStatusTone.SUCCESS else PurchaseStatusTone.INFO,
                        )
                    } else {
                        purchaseStatus(
                            kind = PurchaseStatusKind.RESTORE_FOUND_NOTHING,
                            title = strings.nothingToRestoreTitle(),
                            message = strings.nothingToRestoreStore(storeLabel),
                            tone = PurchaseStatusTone.INFO,
                        )
                    },
                )
            }
        }
    }

    /**
     * Legacy: local-only purchase activation.
     * Use verifyGooglePurchase / verifyApplePurchase for backend-verified flow.
     */
    fun purchase(purchaseToken: String) {
        scope.launch {
            _state.update {
                it.copy(
                    isPurchasing = true,
                    error = null,
                    purchaseStatus = null,
                )
            }
            _state.update {
                it.copy(
                    isPurchasing = false,
                    error = strings.localPurchaseDisabled(),
                    purchaseVerificationState = PurchaseVerificationState.FAILED,
                )
            }
        }
    }

    fun restorePurchase(purchaseToken: String) {
        scope.launch {
            setSignInRequiredStatus(buildRestoreSignInRequiredStatus("Google Play"))
        }
    }

    fun setPurchaseError(message: String?) {
        _state.update {
            it.copy(
                isPurchasing = false,
                isLoading = false,
                error = message,
                purchaseStatus = null,
                purchaseVerificationState = if (message != null) {
                    PurchaseVerificationState.FAILED
                } else if (it.pendingAmazonVerification != null) {
                    PurchaseVerificationState.PENDING
                } else {
                    PurchaseVerificationState.IDLE
                },
            )
        }
    }

    fun updateRebateCode(code: String) {
        if (!RebateCodeApi.ENABLED) return
        _state.update { it.copy(rebateCode = code) }
    }

    fun redeemCode() {
        if (!RebateCodeApi.ENABLED) return
        val code = _state.value.rebateCode.trim()
        if (code.isEmpty()) return
        scope.launch {
            _state.update { it.copy(isRedeeming = true, error = null, rebateSuccess = false) }
            try {
                val deviceId = deviceIdProvider.getDeviceId()
                when (val result = rebateCodeApi.redeemCode(code, deviceId)) {
                    is RebateResult.Success -> {
                        val backendResult = if (authClient.isLoggedIn()) {
                            subscriptionRepo.refreshFromBackendDetailed()
                        } else {
                            null
                        }
                        if (backendResult == BackendPremiumResult.Active) {
                            _state.update { it.copy(isRedeeming = false, rebateSuccess = true, rebateCode = "") }
                            loadSubscription()
                        } else {
                            _state.update {
                                it.copy(
                                    isRedeeming = false,
                                    error = strings.rebateCodeBackendPending(),
                                )
                            }
                        }
                    }
                    is RebateResult.Error -> {
                        _state.update { it.copy(isRedeeming = false, error = result.message) }
                    }
                }
            } catch (e: Exception) {
                torveVerboseLog { "SUBSCRIPTION: Rebate redemption failed: ${e.message}" }
                _state.update { it.copy(isRedeeming = false, error = com.torve.presentation.error.UserFacingError.UNKNOWN.defaultMessage()) }
            }
        }
    }

    fun checkAccess(feature: PremiumFeature): Boolean {
        _state.update { it.copy(showPaywall = false, paywallFeature = null) }
        return true
    }

    fun dismissPaywall() {
        _state.update { it.copy(showPaywall = false, paywallFeature = null) }
    }

    fun dismissDeviceLimitReached() {
        _state.update { it.copy(showDeviceLimitReached = false) }
    }

    fun sendVerificationEmail() {
        var shouldSend = false
        _state.update {
            if (it.isSendingVerificationEmail) {
                it
            } else {
                shouldSend = true
                it.copy(
                    isSendingVerificationEmail = true,
                    verificationEmailMessage = null,
                )
            }
        }
        if (!shouldSend) return
        scope.launch {
            val user = authClient.getCurrentUser()
            if (user == null) {
                _state.update {
                    it.copy(
                        isSendingVerificationEmail = false,
                        verificationEmailMessage = strings.verificationSignInRequired(),
                    )
                }
                return@launch
            }
            val result = resendVerificationEmail(user.email)
            _state.update {
                it.copy(
                    isSendingVerificationEmail = false,
                    verificationEmailMessage = when {
                        result.success -> strings.verificationEmailSent()
                        result.error?.contains("wait", ignoreCase = true) == true ->
                            strings.verificationEmailWaitBeforeResend()
                        else -> result.error ?: strings.verificationEmailSendFailed()
                    },
                )
            }
        }
    }

    fun consumeVerificationEmailMessage() {
        _state.update { it.copy(verificationEmailMessage = null) }
    }

    fun refreshAccess() {
        loadSubscription()
    }
}
