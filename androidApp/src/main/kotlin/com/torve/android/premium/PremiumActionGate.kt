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
            PremiumFeature.QR_PAIRING -> "Lifetime Access required to pair devices."
            PremiumFeature.DEVICE_LINKING -> "Lifetime Access required to manage paired devices."
            PremiumFeature.DEVICE_SYNC,
            PremiumFeature.CROSS_DEVICE_SYNC -> "Lifetime Access required to sync across devices."
            PremiumFeature.TV_PHONE_CONTINUATION -> "Lifetime Access required to continue playback on another device."
            PremiumFeature.STREAM_PLAYBACK -> "Lifetime Access required to watch channels and premium streams."
            else -> "Lifetime Access required for ${PremiumAccess.titleFor(feature)}."
        }
    }

    companion object {
        const val KEY_DEBUG_PREMIUM_BYPASS_ENABLED = "torve_debug_premium_bypass_enabled"
    }
}
