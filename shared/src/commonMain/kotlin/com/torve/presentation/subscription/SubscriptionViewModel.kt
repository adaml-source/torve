package com.torve.presentation.subscription

import com.torve.data.auth.AuthClient
import com.torve.data.entitlement.EntitlementApi
import com.torve.data.subscription.RebateCodeApi
import com.torve.data.subscription.RebateResult
import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.repository.BackendPremiumResult
import com.torve.domain.repository.SubscriptionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SubscriptionViewModel(
    private val subscriptionRepo: SubscriptionRepository,
    private val rebateCodeApi: RebateCodeApi,
    private val deviceIdProvider: DeviceIdProvider,
    private val authClient: AuthClient,
    private val entitlementApi: EntitlementApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SubscriptionUiState())
    val state: StateFlow<SubscriptionUiState> = _state.asStateFlow()

    init {
        loadSubscription()
    }

    fun loadSubscription() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                subscriptionRepo.ensureFreeTier()
                val sub = subscriptionRepo.getActiveSubscription()
                val isLoggedIn = authClient.isLoggedIn()

                // If logged in, check backend entitlements with device awareness
                var isPro = sub?.isPro == true
                var deviceCapReached = false
                if (isLoggedIn) {
                    try {
                        when (val result = subscriptionRepo.refreshFromBackendDetailed()) {
                            is BackendPremiumResult.Active -> isPro = true
                            is BackendPremiumResult.DeviceBlocked -> {
                                isPro = false
                                deviceCapReached = result.reason == "device_cap_reached"
                            }
                            is BackendPremiumResult.NoEntitlement -> isPro = false
                            is BackendPremiumResult.Offline -> isPro = result.localIsPro
                        }
                    } catch (_: Exception) {
                        // Keep local state
                    }
                }

                _state.update {
                    it.copy(
                        subscription = sub,
                        isPro = isPro,
                        isLoading = false,
                        isLoggedIn = isLoggedIn,
                        hasEntitlement = deviceCapReached || isPro,
                        deviceCapReached = deviceCapReached,
                        showDeviceLimitReached = deviceCapReached,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Called after a native store purchase succeeds.
     * Sends the purchase data to the backend for verification.
     */
    fun verifyGooglePurchase(productId: String, purchaseToken: String, platform: String) {
        scope.launch {
            _state.update { it.copy(isPurchasing = true, error = null) }
            try {
                val accessToken = authClient.getAccessToken()
                if (accessToken != null) {
                    val result = entitlementApi.verifyGooglePurchase(
                        accessToken = accessToken,
                        productId = productId,
                        purchaseToken = purchaseToken,
                        platform = platform,
                    )
                    // Entitlement granted, but premium_access may be false if device cap reached
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
                        )
                    }
                } else {
                    subscriptionRepo.activateSubscription(SubscriptionTier.LIFETIME, purchaseToken)
                    loadSubscription()
                    _state.update { it.copy(isPurchasing = false, showPaywall = false) }
                }
            } catch (e: Exception) {
                subscriptionRepo.activateSubscription(SubscriptionTier.LIFETIME, purchaseToken)
                loadSubscription()
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        showPaywall = false,
                        error = "Purchase activated locally. Sign in to sync across devices.",
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
            _state.update { it.copy(isPurchasing = true, error = null) }
            try {
                val accessToken = authClient.getAccessToken()
                if (accessToken != null) {
                    val result = entitlementApi.verifyApplePurchase(
                        accessToken = accessToken,
                        transactionJws = transactionJws,
                        productId = productId,
                        platform = "ios",
                    )
                    // Entitlement granted, but premium_access may be false if device cap reached
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
                        )
                    }
                } else {
                    // Not logged in — activate locally, prompt login later
                    subscriptionRepo.activateSubscription(SubscriptionTier.LIFETIME, "apple_$productId")
                    loadSubscription()
                    _state.update { it.copy(isPurchasing = false, showPaywall = false) }
                }
            } catch (e: Exception) {
                // Backend verification failed — still activate locally
                subscriptionRepo.activateSubscription(SubscriptionTier.LIFETIME, "apple_$productId")
                loadSubscription()
                _state.update {
                    it.copy(
                        isPurchasing = false,
                        showPaywall = false,
                        error = "Purchase activated locally. Sign in to sync across devices.",
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
            _state.update { it.copy(isPurchasing = true, error = null) }
            try {
                subscriptionRepo.activateSubscription(SubscriptionTier.LIFETIME, purchaseToken)
                loadSubscription()
                _state.update { it.copy(isPurchasing = false, showPaywall = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isPurchasing = false, error = e.message) }
            }
        }
    }

    fun restorePurchase(purchaseToken: String) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                subscriptionRepo.restorePurchase(purchaseToken)
                loadSubscription()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
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
