package com.torve.android.premium

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.model.SubscriptionTier
import org.koin.compose.koinInject

@Composable
fun rememberEffectivePremiumAccessTier(
    subscriptionTier: SubscriptionTier?,
    subscriptionIsPro: Boolean,
): AccessTier {
    val preferencesRepository: PreferencesRepository = koinInject()
    val debugBypassEnabled by produceState(
        initialValue = false,
        key1 = subscriptionTier,
        key2 = subscriptionIsPro,
    ) {
        value = if (!com.torve.android.BuildConfig.DEBUG) {
            false
        } else if (com.torve.android.BuildConfig.ALLOW_DEBUG_PREMIUM_BYPASS) {
            true
        } else {
            preferencesRepository.getString(PremiumActionGate.KEY_DEBUG_PREMIUM_BYPASS_ENABLED)
                ?.toBooleanStrictOrNull() == true
        }
    }
    val effectiveTier = when {
        debugBypassEnabled -> AccessTier.LIFETIME
        else -> PremiumAccess.tierFrom(
            subscriptionTier = subscriptionTier,
            isPremiumActive = subscriptionIsPro,
        )
    }

    LaunchedEffect(subscriptionTier, subscriptionIsPro, debugBypassEnabled, effectiveTier) {
        if (com.torve.android.BuildConfig.DEBUG) {
            Log.d(
                "PremiumUiAccess",
                "subscriptionTier=$subscriptionTier subscriptionIsPro=$subscriptionIsPro debugBypassEnabled=$debugBypassEnabled effectiveTier=$effectiveTier",
            )
        }
    }

    return remember(effectiveTier) {
        effectiveTier
    }
}
