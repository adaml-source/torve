package com.torve.android.tv.focus

import com.torve.android.tv.nav.TvRoutes

internal fun isSettingsSubpageRoute(route: String?): Boolean {
    return route == TvRoutes.HOME_LAYOUT || route == TvRoutes.RATINGS_SETTINGS
}

internal fun resolveContentEntryRoute(
    pendingContentEntryRoute: String?,
    currentSubRoute: String?,
    isSubRouteActive: Boolean,
    isRailFocused: Boolean,
    confirmedTopRoute: String,
    selectedTopRoute: String,
): String {
    pendingContentEntryRoute?.let { return it }
    if (isSubRouteActive) {
        return if (isSettingsSubpageRoute(currentSubRoute)) {
            currentSubRoute ?: TvRoutes.DETAILS
        } else {
            TvRoutes.DETAILS
        }
    }
    return if (!isRailFocused) confirmedTopRoute else selectedTopRoute
}

internal fun shouldSuppressRailForSettingsSubpageEntry(
    pendingRoute: String?,
    currentSubRoute: String?,
): Boolean {
    val route = pendingRoute ?: return false
    if (!isSettingsSubpageRoute(route)) return false
    return currentSubRoute == null ||
        currentSubRoute == TvRoutes.SUB_NAV_START ||
        currentSubRoute == route
}
