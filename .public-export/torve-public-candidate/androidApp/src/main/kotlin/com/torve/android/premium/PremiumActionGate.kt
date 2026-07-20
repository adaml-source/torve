package com.torve.android.premium

import android.content.Context
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.SubscriptionRepository

data class PremiumActionDecision(
    val feature: PremiumFeature,
    val allowed: Boolean,
    val message: String,
)

class PremiumAccessDeniedException(
    val feature: PremiumFeature,
    override val message: String,
) : IllegalStateException(message)

class PremiumActionGate(
    subscriptionRepository: SubscriptionRepository,
    preferencesRepository: PreferencesRepository,
    context: Context,
) {
    suspend fun evaluate(feature: PremiumFeature): PremiumActionDecision {
        return PremiumActionDecision(
            feature = feature,
            allowed = true,
            message = "",
        )
    }
}
