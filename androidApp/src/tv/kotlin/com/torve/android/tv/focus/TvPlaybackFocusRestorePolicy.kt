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

/**
 * Combines the root focus-restoration epoch with the destination-owned player
 * return epoch. A player OSD Stop can pop its route before Details is composed,
 * so the destination epoch is stored on the Details back-stack entry. Keeping
 * both epochs in the effect key prevents either owner from masking the other.
 */
internal fun playbackFocusRestoreEffectId(
    rootRequestId: Int,
    destinationRequestId: Int,
): Long =
    (rootRequestId.toLong() shl 32) or (destinationRequestId.toLong() and 0xffff_ffffL)

internal fun nextPlaybackDestinationRequestId(current: Int): Int =
    if (current == Int.MAX_VALUE) 1 else (current + 1).coerceAtLeast(1)

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
