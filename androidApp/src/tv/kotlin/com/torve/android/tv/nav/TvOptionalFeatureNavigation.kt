package com.torve.android.tv.nav

import com.torve.presentation.settings.OptionalAppFeature
import com.torve.presentation.settings.OptionalAppFeatures

internal fun optionalFeatureForTvRoute(route: String): OptionalAppFeature? = when (route) {
    TvRoutes.JELLYFIN -> OptionalAppFeature.JELLYFIN
    TvRoutes.SPORTS -> OptionalAppFeature.SPORTS
    TvRoutes.IPTV -> OptionalAppFeature.CHANNELS
    else -> null
}

// Destinations remain discoverable for connection/setup even when their service is disabled.
internal fun enabledTvNavigationRoutes(allRoutes: List<String>, features: OptionalAppFeatures,
    loaded: Boolean, jellyfinConfigured: Boolean): List<String> = allRoutes.filterNot { it == TvRoutes.SPORTS }
