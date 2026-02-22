package com.streamvault.presentation.subscription

import com.streamvault.domain.model.Subscription
import com.streamvault.domain.model.SubscriptionTier

data class SubscriptionUiState(
    val subscription: Subscription? = null,
    val isPro: Boolean = false,
    val isLoading: Boolean = false,
    val isPurchasing: Boolean = false,
    val error: String? = null,
    val selectedTier: SubscriptionTier = SubscriptionTier.MONTHLY,
    val showPaywall: Boolean = false,
    val paywallFeature: String? = null,
)
