package com.torve.android.premium

import com.torve.android.BuildConfig
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.SubscriptionRepository

data class PremiumActionDecision(
    val feature: PremiumFeature,
    val allowed: Boolean,
    val bypassedForDebug: Boolean = false,
    val message: String,
)

class PremiumAccessDeniedException(
    val feature: PremiumFeature,
    override val message: String,
) : IllegalStateException(message)

class PremiumActionGate(
    private val subscriptionRepository: SubscriptionRepository,
    private val preferencesRepository: PreferencesRepository,
) {
    suspend fun evaluate(feature: PremiumFeature): PremiumActionDecision {
        if (isExplicitDebugBypassEnabled()) {
            return PremiumActionDecision(
                feature = feature,
                allowed = true,
                bypassedForDebug = true,
                message = "Debug premium bypass enabled for ${PremiumAccess.titleFor(feature)}.",
            )
        }

        val allowed = subscriptionRepository.isPro()
        return PremiumActionDecision(
            feature = feature,
            allowed = allowed,
            message = blockedMessageFor(feature),
        )
    }

    private suspend fun isExplicitDebugBypassEnabled(): Boolean {
        if (!BuildConfig.DEBUG) return false
        if (BuildConfig.ALLOW_DEBUG_PREMIUM_BYPASS) return true
        return preferencesRepository.getString(KEY_DEBUG_PREMIUM_BYPASS_ENABLED)
            ?.toBooleanStrictOrNull() == true
    }

    private fun blockedMessageFor(feature: PremiumFeature): String {
        return when (feature) {
            PremiumFeature.PHONE_PAIRING,
            PremiumFeature.QR_PAIRING -> "Premium is required to pair devices."
            PremiumFeature.DEVICE_LINKING -> "Premium is required to manage paired devices."
            PremiumFeature.DEVICE_SYNC,
            PremiumFeature.CROSS_DEVICE_SYNC -> "Premium is required to sync across devices."
            PremiumFeature.TV_PHONE_CONTINUATION -> "Premium is required to continue playback on another device."
            PremiumFeature.STREAM_PLAYBACK -> "Premium is required to watch channels and premium streams."
            else -> "Premium is required for ${PremiumAccess.titleFor(feature)}."
        }
    }

    companion object {
        const val KEY_DEBUG_PREMIUM_BYPASS_ENABLED = "torve_debug_premium_bypass_enabled"
    }
}
