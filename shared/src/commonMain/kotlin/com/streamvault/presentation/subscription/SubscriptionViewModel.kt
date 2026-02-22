package com.streamvault.presentation.subscription

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

    fun selectTier(tier: SubscriptionTier) {
        _state.update { it.copy(selectedTier = tier) }
    }

    fun purchase(purchaseToken: String) {
        val tier = _state.value.selectedTier
        scope.launch {
            _state.update { it.copy(isPurchasing = true, error = null) }
            try {
                subscriptionRepo.activateSubscription(tier, purchaseToken)
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

    fun deactivate() {
        scope.launch {
            try {
                subscriptionRepo.deactivateSubscription()
                loadSubscription()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
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
                PremiumFeature.IPTV -> "IPTV"
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
