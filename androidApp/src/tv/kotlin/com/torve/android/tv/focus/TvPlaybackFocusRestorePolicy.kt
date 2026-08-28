package com.torve.android.tv.focus

import com.torve.android.tv.nav.TvRoutes

internal fun playbackReturnFocusRoute(
    returnDestinationRoute: String?,
    selectedTopRoute: String,
): String = when {
    returnDestinationRoute?.startsWith("tv_details/") == true -> TvRoutes.DETAILS
    returnDestinationRoute.isNullOrBlank() -> selectedTopRoute
    returnDestinationRoute == TvRoutes.SUB_NAV_START -> selectedTopRoute
    returnDestinationRoute == TvRoutes.PLAYER -> selectedTopRoute
    returnDestinationRoute == TvRoutes.LIVE_PLAYER -> selectedTopRoute
    else -> returnDestinationRoute
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
