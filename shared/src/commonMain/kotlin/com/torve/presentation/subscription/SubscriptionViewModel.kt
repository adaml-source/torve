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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val KEY_PENDING_AMAZON_VERIFICATION = "subscription_pending_amazon_verification"
private const val DEFAULT_AMAZON_PRODUCT_ID = "com.torve.pro.lifetime"

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
        println(
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
            },
        )
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
            message = "Sign in to Torve before restoring Amazon Lifetime Access on this device.",
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
            message = "No Amazon Lifetime Access was found for this Torve account.",
            tone = PurchaseStatusTone.INFO,
        )
    }

    private fun buildVerifiedStatus(deviceAccess: Boolean): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.VERIFIED,
            title = "Purchase verified",
            message = if (deviceAccess) {
                "Lifetime Access is active on this device."
            } else {
                "Lifetime Access is linked to this account, but this device still needs an available slot."
            },
            tone = PurchaseStatusTone.SUCCESS,
        )
    }

    private fun buildRestoredStatus(deviceAccess: Boolean): PurchaseStatusMessage {
        return purchaseStatus(
            kind = PurchaseStatusKind.RESTORED,
            title = "Purchase restored",
            message = if (deviceAccess) {
                "Lifetime Access was restored and is active on this device."
            } else {
                "Lifetime Access was restored for this account, but this device still needs an available slot."
            },
            tone = PurchaseStatusTone.SUCCESS,
        )
    }

    private fun shouldClearPurchaseStatusForActiveSubscription(status: PurchaseStatusMessage?): Boolean {
        return status?.kind in setOf(
            PurchaseStatusKind.PENDING_VERIFICATION,
            PurchaseStatusKind.SIGN_IN_REQUIRED,
            PurchaseStatusKind.VERIFICATION_FAILED_TEMPORARILY,
            PurchaseStatusKind.BACKEND_UNAVAILABLE,
        )
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
                val localIsPro = sub?.isPro == true
                val isPro: Boolean
                val hasEntitlement: Boolean
                val deviceCapReached: Boolean
                when (backendResult) {
                    BackendPremiumResult.Active -> {
                        isPro = true
                        hasEntitlement = true
                        deviceCapReached = false
                    }
                    is BackendPremiumResult.DeviceBlocked -> {
                        isPro = false
                        hasEntitlement = true
                        deviceCapReached = true
                    }
                    BackendPremiumResult.NoEntitlement -> {
                        isPro = localIsPro
                        hasEntitlement = localIsPro
                        deviceCapReached = false
                    }
                    is BackendPremiumResult.Offline -> {
                        isPro = backendResult.localIsPro
                        hasEntitlement = backendResult.localIsPro
                        deviceCapReached = false
                    }
                    null -> {
                        isPro = localIsPro
                        hasEntitlement = localIsPro
                        deviceCapReached = false
                    }
                }

                _state.update { current ->
                    val pendingStatus = current.pendingAmazonVerification?.toPurchaseStatusMessage(isLoggedIn)
                    current.copy(
                        subscription = sub,
                        isPro = isPro,
                        isLoading = false,
                        isLoggedIn = isLoggedIn,
                        hasEntitlement = hasEntitlement,
                        deviceCapReached = deviceCapReached,
                        showDeviceLimitReached = deviceCapReached,
                        purchaseVerificationState = if (current.pendingAmazonVerification != null) {
                            PurchaseVerificationState.PENDING
                        } else {
                            current.purchaseVerificationState
                        },
                        purchaseStatus = when {
                            pendingStatus != null -> pendingStatus
                            isPro && shouldClearPurchaseStatusForActiveSubscription(current.purchaseStatus) -> null
                            else -> current.purchaseStatus
                        },
                    )
                }
                println(
                    "SUBSCRIPTION: Entitlement refresh result isPro=$isPro hasEntitlement=$hasEntitlement deviceCapReached=$deviceCapReached loggedIn=$isLoggedIn",
                )
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
                println("SUBSCRIPTION: Entitlement refresh failed: ${e.message ?: "unknown"}")
            }
        }
    }

    /**
     * Called after a native store purchase succeeds.
     * Sends the purchase data to the backend for verification.
     */
    fun verifyGooglePurchase(productId: String, purchaseToken: String, platform: String) {
        scope.launch {
            println(
                "SUBSCRIPTION: Google purchase callback received productId=$productId token=${maskToken(purchaseToken)} platform=$platform",
            )
            _state.update {
                it.copy(
                    isPurchasing = true,
                    error = null,
                    purchaseStatus = null,
                    purchaseVerificationState = PurchaseVerificationState.PENDING,
                )
            }
            try {
                val accessToken = authClient.getAccessToken()
                if (accessToken != null) {
                    println("SUBSCRIPTION: Sending Google verify request")
                    val result = entitlementApi.verifyGooglePurchase(
                        accessToken = accessToken,
                        productId = productId,
                        purchaseToken = purchaseToken,
                        platform = platform,
                    )
                    println(
                        "SUBSCRIPTION: Google verify response received premiumAccess=${result.premium_access} entitlements=${result.entitlements.size}",
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
                            deviceCapReached = hasEntitlement && !deviceAccess,
                            showDeviceLimitReached = hasEntitlement && !deviceAccess,
                            purchaseVerificationState = PurchaseVerificationState.VERIFIED,
                            purchaseStatus = buildVerifiedStatus(deviceAccess),
                        )
                    }
                } else {
                    subscriptionRepo.activateSubscription(SubscriptionTier.LIFETIME, purchaseToken)
                    loadSubscription()
                    _state.update {
                        it.copy(
                            isPurchasing = false,
                            showPaywall = false,
                            purchaseVerificationState = PurchaseVerificationState.PENDING,
                        )
                    }
                }
            } catch (e: Exception) {
                subscriptionRepo.activateSubscription(SubscriptionTier.LIFETIME, purchaseToken)
                loadSubscription()
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        showPaywall = false,
                        error = "Purchase activated locally. Sign in to sync across devices.",
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
                val accessToken = authClient.getAccessToken()
                if (accessToken != null) {
                    val result = entitlementApi.verifyApplePurchase(
                        accessToken = accessToken,
                        transactionJws = transactionJws,
                        productId = productId,
                        platform = "ios",
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
                            deviceCapReached = hasEntitlement && !deviceAccess,
                            showDeviceLimitReached = hasEntitlement && !deviceAccess,
                            purchaseVerificationState = PurchaseVerificationState.VERIFIED,
                            purchaseStatus = buildVerifiedStatus(deviceAccess),
                        )
                    }
                } else {
                    subscriptionRepo.activateSubscription(SubscriptionTier.LIFETIME, "apple_$productId")
                    loadSubscription()
                    _state.update {
                        it.copy(
                            isPurchasing = false,
                            showPaywall = false,
                            purchaseVerificationState = PurchaseVerificationState.PENDING,
                        )
                    }
                }
            } catch (e: Exception) {
                subscriptionRepo.activateSubscription(SubscriptionTier.LIFETIME, "apple_$productId")
                loadSubscription()
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        showPaywall = false,
                        error = "Purchase activated locally. Sign in to sync across devices.",
                        purchaseVerificationState = PurchaseVerificationState.FAILED,
                    )
                }
            }
        }
    }

    fun markAmazonPurchasePending(message: String) {
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
            println(
                "SUBSCRIPTION: Amazon purchase callback received receipt=${maskToken(sanitizedReceipt)} productId=$sanitizedProductId hasUserId=${sanitizedUserId.isNotBlank()} platform=$platform",
            )
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
            val accessToken = authClient.getAccessToken()

            if (accessToken.isNullOrBlank()) {
                val pending = buildPendingAmazonVerification(
                    receiptId = sanitizedReceipt,
                    amazonUserId = sanitizedUserId,
                    productId = sanitizedProductId,
                    platform = platform,
                    reason = previousPending?.reason ?: PendingAmazonVerificationReason.RETRY_VERIFICATION,
                    previous = previousPending,
                    incrementAttempt = false,
                    lastMessage = "Sign in to Torve, then choose Retry Verification to finish Lifetime Access activation.",
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
                val hasEntitlement = result.entitlements.isNotEmpty()
                val deviceAccess = result.premium_access
                if (deviceAccess) {
                    subscriptionRepo.onBackendEntitlementGranted(true)
                }
                persistPendingAmazonVerification(null)
                loadSubscription()
                val status = buildVerifiedStatus(deviceAccess)
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

            val accessToken = authClient.getAccessToken()
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
                val hasEntitlement = result.entitlements.isNotEmpty()
                val deviceAccess = result.premium_access

                if (hasEntitlement) {
                    if (deviceAccess) {
                        subscriptionRepo.onBackendEntitlementGranted(true)
                    }
                    persistPendingAmazonVerification(null)
                    loadSubscription()
                    val status = buildRestoredStatus(deviceAccess)
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
            try {
                subscriptionRepo.activateSubscription(SubscriptionTier.LIFETIME, purchaseToken)
                loadSubscription()
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        showPaywall = false,
                        purchaseVerificationState = PurchaseVerificationState.VERIFIED,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        error = e.message,
                        purchaseVerificationState = PurchaseVerificationState.FAILED,
                    )
                }
            }
        }
    }

    fun restorePurchase(purchaseToken: String) {
        scope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    purchaseStatus = null,
                )
            }
            try {
                subscriptionRepo.restorePurchase(purchaseToken)
                loadSubscription()
                _state.update { it.copy(purchaseVerificationState = PurchaseVerificationState.RESTORED) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = e.message,
                        purchaseVerificationState = PurchaseVerificationState.FAILED,
                    )
                }
            }
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
                        subscriptionRepo.activateSubscription(
                            SubscriptionTier.LIFETIME,
                            "rebate_$code",
                        )
                        _state.update { it.copy(isRedeeming = false, rebateSuccess = true, rebateCode = "") }
                        loadSubscription()
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
}
