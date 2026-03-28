package com.torve.presentation.subscription

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
    return when (backendResult) {
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
}

class SubscriptionViewModel(
    private val subscriptionRepo: SubscriptionRepository,
    private val rebateCodeApi: RebateCodeApi,
    private val deviceIdProvider: DeviceIdProvider,
    private val authClient: AuthClient,
    private val entitlementApi: EntitlementApi,
    private val prefsRepo: PreferencesRepository,
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
        val summary = message.ifBlank {
            "Amazon completed the purchase. Torve is waiting for account details to finish verification."
        }
        return purchaseStatus(
            kind = PurchaseStatusKind.PENDING_VERIFICATION,
            title = "Purchase received",
            message = "$summary If this does not finish shortly, choose Restore Purchase.",
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun buildTemporaryVerificationStatus(showRetryVerification: Boolean): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.VERIFICATION_FAILED_TEMPORARILY,
            title = "Verification not finished",
            message = "Amazon completed the purchase, but Torve could not confirm it yet. Choose Retry Verification or Restore Purchase.",
            tone = PurchaseStatusTone.ERROR,
            showRetryVerification = showRetryVerification,
        )
    }

    private fun buildBackendUnavailableStatus(showRetryVerification: Boolean): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.BACKEND_UNAVAILABLE,
            title = "Verification service unavailable",
            message = if (showRetryVerification) {
                "Your Amazon purchase info is saved, but Torve cannot reach the verification service right now. Choose Retry Verification again shortly."
            } else {
                "Torve cannot reach the verification service right now. Please try Restore Purchase again shortly."
            },
            tone = PurchaseStatusTone.ERROR,
            showRetryVerification = showRetryVerification,
        )
    }

    private fun buildRestoreSignInRequiredStatus(): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.SIGN_IN_REQUIRED,
            title = "Sign in required",
            message = "Sign in to Torve before restoring Amazon Premium on this device.",
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun buildPurchaseSignInRequiredStatus(storeLabel: String): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.SIGN_IN_REQUIRED,
            title = "Sign in required",
            message = "Sign in to Torve before buying Premium through $storeLabel on this device.",
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun buildRestoreSignInRequiredStatus(storeLabel: String): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.SIGN_IN_REQUIRED,
            title = "Sign in required",
            message = "Sign in to Torve before restoring Premium from $storeLabel on this device.",
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun buildPurchaseConflictStatus(): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.PURCHASE_CONFLICT,
            title = "Purchase linked to another account",
            message = "This Amazon purchase is already linked to a different Torve account. Sign in with the original account or contact support.",
            tone = PurchaseStatusTone.ERROR,
        )
    }

    private fun buildRestoreFoundNothingStatus(): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.RESTORE_FOUND_NOTHING,
            title = "Nothing to restore",
            message = "No Amazon premium purchase was found for this Torve account.",
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun premiumPlanLabel(tier: SubscriptionTier): String {
        return when (tier) {
            SubscriptionTier.MONTHLY -> "Premium Monthly"
            SubscriptionTier.LIFETIME -> "Premium Lifetime"
            SubscriptionTier.FREE -> "Premium"
        }
    }

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
            title = "Purchase verified",
            message = if (deviceAccess) {
                when (resolvedEntitlement.tier) {
                    SubscriptionTier.MONTHLY -> {
                        val until = resolvedEntitlement.expiresAtEpochMs?.let { " until ${formatShortDate(it)}" }.orEmpty()
                        "$premiumLabel is active on this device$until."
                    }
                    SubscriptionTier.LIFETIME -> "$premiumLabel is active on this device."
                    SubscriptionTier.FREE -> "Premium is active on this device."
                }
            } else {
                "$premiumLabel is linked to this account, but this device still needs an available slot."
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
            title = "Purchase restored",
            message = if (deviceAccess) {
                when (resolvedEntitlement.tier) {
                    SubscriptionTier.MONTHLY -> {
                        val until = resolvedEntitlement.expiresAtEpochMs?.let { " until ${formatShortDate(it)}" }.orEmpty()
                        "$premiumLabel was restored and is active on this device$until."
                    }
                    SubscriptionTier.LIFETIME -> "$premiumLabel was restored and is active on this device."
                    SubscriptionTier.FREE -> "Premium was restored and is active on this device."
                }
            } else {
                "$premiumLabel was restored for this account, but this device still needs an available slot."
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
                    purchaseStatus = pending.toPurchaseStatusMessage(isLoggedIn),
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
                    val pendingStatus = current.pendingAmazonVerification?.toPurchaseStatusMessage(isLoggedIn)
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
                            else -> current.purchaseStatus
                        },
                    )
                }
                torveVerboseLog {
                    "SUBSCRIPTION: Entitlement refresh result isPro=${entitlementDecision.isPro} hasEntitlement=${entitlementDecision.hasEntitlement} isDeviceActivated=${entitlementDecision.isDeviceActivated} deviceBlockReason=${entitlementDecision.deviceBlockReason} loggedIn=$isLoggedIn"
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
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
                        error = "Google Play purchase could not be verified. Try Restore Purchase again after signing in.",
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
                        error = "Apple purchase could not be verified. Try Restore Purchase again after signing in.",
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
                    "Amazon completed the purchase. Torve is waiting for account details to finish verification.",
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
                    lastMessage = "Sign in to Torve, then choose Retry Verification to finish Premium activation.",
                )
                persistPendingAmazonVerification(pending)
                val status = pending.toPurchaseStatusMessage(isLoggedIn = false)
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
                val status = existingPending?.toPurchaseStatusMessage(isLoggedIn = false)
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
                    val status = existingPending?.toPurchaseStatusMessage(isLoggedIn = true)
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
                            purchaseStatus = updatedPending.toPurchaseStatusMessage(isLoggedIn = true),
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
                                title = "Nothing to restore",
                                message = "No premium purchase was found for this Torve account on $storeLabel.",
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
                        error = "$storeLabel restore could not be completed. Try again after signing in.",
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
                    error = "Local purchase activation is disabled. Verify purchases through the store restore flow.",
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
                                    error = "Code accepted, but premium access must still be confirmed by the Torve backend.",
                                )
                            }
                        }
                    }
                    is RebateResult.Error -> {
                        _state.update { it.copy(isRedeeming = false, error = result.message) }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isRedeeming = false, error = e.message) }
            }
        }
    }

    fun checkAccess(feature: PremiumFeature): Boolean {
        val isPro = _state.value.isPro
        if (!isPro) {
            val featureName = when (feature) {
                PremiumFeature.STREAM_PLAYBACK -> "Stream Playback"
                PremiumFeature.DOWNLOAD -> "Downloads"
                PremiumFeature.CHANNELS -> "Channels"
                PremiumFeature.MULTI_DEBRID -> "Multi-Cloud"
                PremiumFeature.ADVANCED_FILTERS -> "Advanced Filters"
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
