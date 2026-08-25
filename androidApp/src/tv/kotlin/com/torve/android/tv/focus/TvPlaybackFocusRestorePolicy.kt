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
    contentFocusEpochBefore: Int,
    contentFocusEpochAfter: Int,
    focusedContentRoute: String?,
    subRouteFocusEpochBefore: Int,
    subRouteFocusEpochAfter: Int,
): Boolean = if (focusRoute == TvRoutes.DETAILS) {
    subRouteFocusEpochAfter != subRouteFocusEpochBefore
} else {
    contentFocusEpochAfter != contentFocusEpochBefore && focusedContentRoute == focusRoute
}

internal fun canPlaybackReturnFallbackToRail(
    focusRoute: String,
    isSubRouteActive: Boolean,
): Boolean = focusRoute != TvRoutes.DETAILS && !isSubRouteActive

