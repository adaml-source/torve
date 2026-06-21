package com.torve.android.premium

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.torve.domain.model.SubscriptionTier

@Composable
fun rememberEffectivePremiumAccessTier(
    subscriptionTier: SubscriptionTier?,
    subscriptionIsPro: Boolean,
): AccessTier {
    val effectiveTier = PremiumAccess.tierFrom(
        subscriptionTier = subscriptionTier,
        isPremiumActive = subscriptionIsPro,
    )

    LaunchedEffect(subscriptionTier, subscriptionIsPro, effectiveTier) {
        if (com.torve.android.BuildConfig.DEBUG) {
            Log.d(
                "PremiumUiAccess",
                "subscriptionTier=$subscriptionTier subscriptionIsPro=$subscriptionIsPro effectiveTier=$effectiveTier",
            )
        }
    }

    return remember(effectiveTier) {
        effectiveTier
    }
}
