package com.torve.android.premium

import android.content.Context
import com.torve.android.R
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
    private val subscriptionRepository: SubscriptionRepository,
    private val preferencesRepository: PreferencesRepository,
    private val context: Context,
) {
    suspend fun evaluate(feature: PremiumFeature): PremiumActionDecision {
        // Features marked FREE in the feature matrix are never gated.
        if (!PremiumAccess.requiresPremiumAccess(feature)) {
            return PremiumActionDecision(
                feature = feature,
                allowed = true,
                message = "",
            )
        }

        val allowed = subscriptionRepository.isPro()
        return PremiumActionDecision(
            feature = feature,
            allowed = allowed,
            message = blockedMessageFor(feature),
        )
    }

    private fun blockedMessageFor(feature: PremiumFeature): String {
        return when (feature) {
            PremiumFeature.PHONE_PAIRING,
            PremiumFeature.QR_PAIRING -> context.getString(R.string.premium_gate_pair_devices)
            PremiumFeature.DEVICE_LINKING -> context.getString(R.string.premium_gate_manage_paired)
            PremiumFeature.DEVICE_SYNC,
            PremiumFeature.CROSS_DEVICE_SYNC -> context.getString(R.string.premium_gate_sync_devices)
            PremiumFeature.TV_PHONE_CONTINUATION -> context.getString(R.string.premium_gate_continue_playback)
            PremiumFeature.STREAM_PLAYBACK -> context.getString(R.string.premium_gate_stream_playback)
            else -> context.getString(R.string.premium_gate_generic, PremiumAccess.titleFor(context, feature))
        }
    }

}
