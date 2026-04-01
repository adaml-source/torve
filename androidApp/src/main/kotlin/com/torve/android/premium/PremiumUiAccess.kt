package com.torve.android.premium

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.torve.domain.model.SubscriptionTier

@Composable
fun rememberEffectivePremiumAccessTier(
    subscriptionTier: SubscriptionTier?,
    subscriptionIsPro: Boolean,
): AccessTier {
    val debugBypassEnabled by produceState(
        initialValue = false,
        key1 = subscriptionTier,
        key2 = subscriptionIsPro,
    ) {
        value = com.torve.android.BuildConfig.DEBUG &&
            com.torve.android.BuildConfig.ALLOW_DEBUG_PREMIUM_BYPASS
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
