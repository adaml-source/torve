package com.torve.android.tv.focus

import com.torve.android.tv.nav.TvRoutes

internal fun playbackReturnFocusRoute(
    currentDestinationRoute: String?,
    selectedTopRoute: String,
): String = if (currentDestinationRoute?.startsWith("tv_details/") == true) {
    TvRoutes.DETAILS
} else {
    selectedTopRoute
}

internal fun didPlaybackReturnFocusReachContent(
    focusRoute: String,
    destinationHasFocus: Boolean,
    contentFocusEpochBefore: Int,
    contentFocusEpochAfter: Int,
    focusedContentRoute: String?,
    subRouteFocusEpochBefore: Int,
    subRouteFocusEpochAfter: Int,
): Boolean {
    // requestFocus() does not dispatch another onFocusChanged event when the
    // destination node already owns focus. Treat the observed destination
    // surface as authoritative; otherwise the retry loop keeps reclaiming the
    // same control and traps D-pad navigation there.
    if (destinationHasFocus) return true

    return if (focusRoute == TvRoutes.DETAILS) {
        subRouteFocusEpochAfter != subRouteFocusEpochBefore
    } else {
        contentFocusEpochAfter != contentFocusEpochBefore && focusedContentRoute == focusRoute
    }
}

internal fun canPlaybackReturnFallbackToRail(
    focusRoute: String,
    isSubRouteActive: Boolean,
): Boolean = focusRoute != TvRoutes.DETAILS && !isSubRouteActive
