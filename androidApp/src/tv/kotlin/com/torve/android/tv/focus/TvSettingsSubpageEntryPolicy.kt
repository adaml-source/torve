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

/**
 * A restore loop is finished once the active surface has reported focus.
 * This also applies to implicit sub-route entry where no pending route was
 * recorded; keeping retries alive in that case can steal focus from a modal.
 */
internal fun shouldStopContentFocusRestore(
    activeRoute: String,
    focusedContentRoute: String?,
    contentOwnsFocus: Boolean = true,
): Boolean = contentOwnsFocus && focusedContentRoute != null && focusedContentRoute == activeRoute

/**
 * Generic root retries are for top-level content and the two Settings
 * subpages whose controls attach late. Other sub-routes (Details, See all,
 * players, etc.) own their entry focus and must not be targeted repeatedly.
 */
internal fun shouldRunRootContentFocusRestore(
    currentSubRoute: String?,
    isSubRouteActive: Boolean,
): Boolean = !isSubRouteActive || isSettingsSubpageRoute(currentSubRoute)

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
