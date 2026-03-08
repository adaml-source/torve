package com.torve.presentation.subscription

import com.torve.data.subscription.RebateCodeApi
import com.torve.domain.model.Subscription

data class SubscriptionUiState(
    val subscription: Subscription? = null,
    val isPro: Boolean = false,
    val isLoading: Boolean = false,
    val isPurchasing: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
    val showPaywall: Boolean = false,
    val paywallFeature: String? = null,
    val rebateCode: String = "",
    val isRedeeming: Boolean = false,
    val rebateSuccess: Boolean = false,
    val rebateCodesEnabled: Boolean = RebateCodeApi.ENABLED,
    // Device governance
    val hasEntitlement: Boolean = false,
    val deviceCapReached: Boolean = false,
    val showDeviceLimitReached: Boolean = false,
)
