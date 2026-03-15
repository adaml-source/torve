package com.torve.android.premium

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.torve.domain.repository.PreferencesRepository
import org.koin.compose.koinInject

@Composable
fun rememberEffectivePremiumAccessTier(
    subscriptionIsPro: Boolean,
): AccessTier {
    val preferencesRepository: PreferencesRepository = koinInject()
    val debugBypassEnabled by produceState(
        initialValue = false,
        key1 = subscriptionIsPro,
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
    val hasEffectiveLifetimeAccess = subscriptionIsPro || debugBypassEnabled

    LaunchedEffect(subscriptionIsPro, debugBypassEnabled, hasEffectiveLifetimeAccess) {
        Log.d(
            "PremiumUiAccess",
            "subscriptionIsPro=$subscriptionIsPro debugBypassEnabled=$debugBypassEnabled effectiveLifetime=$hasEffectiveLifetimeAccess",
        )
    }

    return remember(hasEffectiveLifetimeAccess) {
        PremiumAccess.tierFrom(hasEffectiveLifetimeAccess)
    }
}
