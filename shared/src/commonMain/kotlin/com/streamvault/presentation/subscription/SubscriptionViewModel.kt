package com.streamvault.presentation.subscription

import com.streamvault.data.subscription.RebateCodeApi
import com.streamvault.data.subscription.RebateResult
import com.streamvault.domain.model.PremiumFeature
import com.streamvault.domain.model.SubscriptionTier
import com.streamvault.domain.repository.SubscriptionRepository
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
                val isPro = subscriptionRepo.isPro()
                _state.update {
                    it.copy(
                        subscription = sub,
                        isPro = isPro,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

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
        _state.update { it.copy(rebateCode = code) }
    }

    fun redeemCode() {
        val code = _state.value.rebateCode.trim()
        if (code.isEmpty()) return
        scope.launch {
            _state.update { it.copy(isRedeeming = true, error = null, rebateSuccess = false) }
            try {
                when (val result = rebateCodeApi.redeemCode(code)) {
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

    /**
     * Check access before performing a premium action. Shows paywall if not subscribed.
     * Returns true if access is granted.
     */
    fun checkAccess(feature: PremiumFeature): Boolean {
        val isPro = _state.value.isPro
        if (!isPro) {
            val featureName = when (feature) {
                PremiumFeature.STREAM_PLAYBACK -> "Stream Playback"
                PremiumFeature.DOWNLOAD -> "Downloads"
                PremiumFeature.CHANNELS -> "Channels"
                PremiumFeature.MULTI_DEBRID -> "Multi-Debrid"
                PremiumFeature.ADVANCED_FILTERS -> "Advanced Filters"
            }
            _state.update { it.copy(showPaywall = true, paywallFeature = featureName) }
        }
        return isPro
    }

    fun dismissPaywall() {
        _state.update { it.copy(showPaywall = false, paywallFeature = null) }
    }
}
