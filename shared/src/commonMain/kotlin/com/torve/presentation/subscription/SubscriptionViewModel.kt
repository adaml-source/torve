package com.torve.presentation.subscription

import com.torve.presentation.error.defaultMessage
import com.torve.data.auth.AuthClient
import com.torve.data.entitlement.EntitlementApi
import com.torve.data.subscription.RebateCodeApi
import com.torve.data.subscription.RebateResult
import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.repository.BackendPremiumResult
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.SubscriptionRepository
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
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
)

internal fun resolveSubscriptionEntitlementUiDecision(
    backendResult: BackendPremiumResult?,
): SubscriptionEntitlementUiDecision {
    val decision = when (backendResult) {
        BackendPremiumResult.Active -> SubscriptionEntitlementUiDecision(
            isPro = true,
            hasEntitlement = true,
            isDeviceActivated = true,
            deviceBlockReason = null,
            deviceCapReached = false,
        )
        is BackendPremiumResult.DeviceBlocked -> SubscriptionEntitlementUiDecision(
            isPro = false,
            hasEntitlement = true,
            isDeviceActivated = false,
            deviceBlockReason = backendResult.reason,
            deviceCapReached = true,
        )
        BackendPremiumResult.NoEntitlement -> SubscriptionEntitlementUiDecision(
            isPro = false,
            hasEntitlement = false,
            isDeviceActivated = false,
            deviceBlockReason = null,
            deviceCapReached = false,
        )
        is BackendPremiumResult.Offline -> SubscriptionEntitlementUiDecision(
            // Fail-closed: offline users do not get premium UI access.
            // The offline grace period is handled in the repository layer.
            isPro = false,
            hasEntitlement = false,
            isDeviceActivated = false,
            deviceBlockReason = null,
            deviceCapReached = false,
        )
        null -> SubscriptionEntitlementUiDecision(
            isPro = false,
            hasEntitlement = false,
            isDeviceActivated = false,
            deviceBlockReason = null,
            deviceCapReached = false,
        )
    }
    com.torve.platform.torveVerboseLog {
        "ENTITLEMENT_DECISION: backend=${backendResult?.let { it::class.simpleName } ?: "null"} → isPro=${decision.isPro} hasEntitlement=${decision.hasEntitlement} isDeviceActivated=${decision.isDeviceActivated} deviceBlock=${decision.deviceBlockReason}"
    }
    return decision
}

class SubscriptionViewModel(
    private val subscriptionRepo: SubscriptionRepository,
    private val rebateCodeApi: RebateCodeApi,
    private val deviceIdProvider: DeviceIdProvider,
    private val authClient: AuthClient,
    private val entitlementApi: EntitlementApi,
    private val prefsRepo: PreferencesRepository,
    private val strings: PurchaseStringResolver = DefaultPurchaseStringResolver(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SubscriptionUiState())
    val state: StateFlow<SubscriptionUiState> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        loadPersistedPendingAmazonVerification()
        loadSubscription()
        scope.launch {
            authClient.authUserFlow.collect {
                loadSubscription()
            }
        }
    }

    private fun maskToken(token: String, visiblePrefix: Int = 8): String {
        if (token.isBlank()) return "<empty>"
        return "${token.take(visiblePrefix)}..."
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

    fun requireAccountForPurchase(storeLabel: String, onAllowed: () -> Unit) {
        scope.launch {
            val hasSession = authClient.getValidAccessToken() != null || authClient.isLoggedIn()
            if (hasSession) {
                _state.update { it.copy(isLoggedIn = true, error = null) }
                onAllowed()
            } else {
                setSignInRequiredStatus(buildPurchaseSignInRequiredStatus(storeLabel))
            }
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
                    "SUBSCRIPTION_GATE decision backendResult=${backendResult?.let { it::class.simpleName } ?: "none"} isPro=${entitlementDecision.isPro} hasEntitlement=${entitlementDecision.hasEntitlement} isDeviceActivated=${entitlementDecision.isDeviceActivated}"
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
                    )
                }
                torveVerboseLog {
                    "SUBSCRIPTION: Entitlement refresh result isPro=${entitlementDecision.isPro} hasEntitlement=${entitlementDecision.hasEntitlement} isDeviceActivated=${entitlementDecision.isDeviceActivated} deviceBlockReason=${entitlementDecision.deviceBlockReason} loggedIn=$isLoggedIn"
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
                )
                torveVerboseLog {
                    "SUBSCRIPTION: Google verify response received premiumAccess=${result.premium_access} entitlements=${result.entitlements.size}"
                }
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
                val deviceAccess = result.premium_access
                if (deviceAccess) {
                    subscriptionRepo.onBackendEntitlementGranted(true)
                }
                loadSubscription()
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        showPaywall = false,
                        hasEntitlement = hasEntitlement,
                        isDeviceActivated = deviceAccess,
                        deviceBlockReason = null,
                        deviceCapReached = hasEntitlement && !deviceAccess,
                        showDeviceLimitReached = hasEntitlement && !deviceAccess,
                        purchaseVerificationState = PurchaseVerificationState.VERIFIED,
                        purchaseStatus = buildVerifiedStatus(resolvedEntitlement, deviceAccess),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        error = strings.googleVerifyFailed(),
                        purchaseVerificationState = PurchaseVerificationState.FAILED,
                    )
                }
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
                val hasEntitlement = result.entitlements.isNotEmpty()
                val deviceAccess = result.premium_access
                if (deviceAccess) {
                    subscriptionRepo.onBackendEntitlementGranted(true)
                }
                loadSubscription()
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        showPaywall = false,
                        hasEntitlement = hasEntitlement,
                        isDeviceActivated = deviceAccess,
                        deviceBlockReason = null,
                        deviceCapReached = hasEntitlement && !deviceAccess,
                        showDeviceLimitReached = hasEntitlement && !deviceAccess,
                        purchaseVerificationState = PurchaseVerificationState.VERIFIED,
                        purchaseStatus = buildVerifiedStatus(resolvedEntitlement, deviceAccess),
                    )
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
                val deviceAccess = result.premium_access
                if (deviceAccess) {
                    subscriptionRepo.onBackendEntitlementGranted(true)
                }
                persistPendingAmazonVerification(null)
                loadSubscription()
                val status = buildVerifiedStatus(resolvedEntitlement, deviceAccess)
                logPurchaseMilestone(
                    milestone = "amazon_verify_success",
                    detail = "premiumAccess=$deviceAccess entitlements=${result.entitlements.size}",
                )
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        showPaywall = false,
                        error = null,
                        hasEntitlement = hasEntitlement,
                        isDeviceActivated = deviceAccess,
                        deviceBlockReason = null,
                        deviceCapReached = hasEntitlement && !deviceAccess,
                        showDeviceLimitReached = hasEntitlement && !deviceAccess,
                        purchaseVerificationState = PurchaseVerificationState.VERIFIED,
                        purchaseStatus = status,
                        pendingAmazonVerification = null,
                    )
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
                val deviceAccess = result.premium_access

                if (hasEntitlement) {
                    if (deviceAccess) {
                        subscriptionRepo.onBackendEntitlementGranted(true)
                    }
                    persistPendingAmazonVerification(null)
                    loadSubscription()
                    val status = buildRestoredStatus(resolvedEntitlement, deviceAccess)
                    logPurchaseMilestone(
                        milestone = "amazon_restore_success",
                        detail = "premiumAccess=$deviceAccess entitlements=${result.entitlements.size}",
                    )
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            showPaywall = false,
                            hasEntitlement = true,
                            isDeviceActivated = deviceAccess,
                            deviceBlockReason = null,
                            deviceCapReached = !deviceAccess,
                            showDeviceLimitReached = !deviceAccess,
                            purchaseVerificationState = PurchaseVerificationState.RESTORED,
                            purchaseStatus = status,
                            pendingAmazonVerification = null,
                        )
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
                val deviceAccess = result.premium_access

                if (hasEntitlement) {
                    if (deviceAccess) {
                        subscriptionRepo.onBackendEntitlementGranted(true)
                    }
                    loadSubscription()
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            showPaywall = false,
                            hasEntitlement = true,
                            isDeviceActivated = deviceAccess,
                            deviceBlockReason = null,
                            deviceCapReached = !deviceAccess,
                            showDeviceLimitReached = !deviceAccess,
                            purchaseVerificationState = PurchaseVerificationState.RESTORED,
                            purchaseStatus = buildRestoredStatus(resolvedEntitlement, deviceAccess),
                        )
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
        val isPro = _state.value.isPro
        if (!isPro) {
            val featureName = when (feature) {
                PremiumFeature.STREAM_PLAYBACK -> strings.featureStreamPlayback()
                PremiumFeature.DOWNLOAD -> strings.featureDownloads()
                PremiumFeature.CHANNELS -> strings.featureChannels()
                PremiumFeature.MULTI_DEBRID -> strings.featureMultiCloud()
                PremiumFeature.ADVANCED_FILTERS -> strings.featureAdvancedFilters()
            }
            _state.update { it.copy(showPaywall = true, paywallFeature = featureName) }
        }
        return isPro
    }

    fun dismissPaywall() {
        _state.update { it.copy(showPaywall = false, paywallFeature = null) }
    }

    fun dismissDeviceLimitReached() {
        _state.update { it.copy(showDeviceLimitReached = false) }
    }

    fun refreshAccess() {
        loadSubscription()
    }
}
