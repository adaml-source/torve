package com.torve.android.tv.focus

import com.torve.android.tv.nav.TvRoutes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TvPlaybackFocusRestorePolicyTest {

    @Test
    fun destinationOwnedStopSignalCannotBeMaskedByRootEpoch() {
        val first = playbackFocusRestoreEffectId(
            rootRequestId = 42,
            destinationRequestId = 1,
        )
        val second = playbackFocusRestoreEffectId(
            rootRequestId = 42,
            destinationRequestId = 2,
        )

        assertFalse(first == second)
        assertEquals(1, nextPlaybackDestinationRequestId(Int.MAX_VALUE))
        assertEquals(8, nextPlaybackDestinationRequestId(7))
    }

    @Test
    fun capturedReturnRouteTargetsDetailsEvenWhenPlayerPopHasNotSettled() {
        assertEquals(
            TvRoutes.DETAILS,
            playbackReturnFocusRoute("tv_details/tv/123", TvRoutes.SHOWS),
        )
        assertEquals(
            TvRoutes.IPTV,
            playbackReturnFocusRoute(TvRoutes.SUB_NAV_START, TvRoutes.IPTV),
        )
        assertEquals(
            TvRoutes.VOD_SERIES_DETAILS,
            playbackReturnFocusRoute(TvRoutes.VOD_SERIES_DETAILS, TvRoutes.LIBRARY),
        )
    }

    @Test
    fun requestFocusIsNotSuccessUntilTheExpectedSurfaceReportsFocus() {
        assertFalse(
            didPlaybackReturnFocusReachContent(
                focusRoute = TvRoutes.SHOWS,
                destinationHasFocus = false,
                contentFocusEpochBefore = 7,
                contentFocusEpochAfter = 7,
                focusedContentRoute = TvRoutes.SHOWS,
                subRouteFocusEpochBefore = 0,
                subRouteFocusEpochAfter = 0,
            ),
        )
        assertTrue(
            didPlaybackReturnFocusReachContent(
                focusRoute = TvRoutes.SHOWS,
                destinationHasFocus = false,
                contentFocusEpochBefore = 7,
                contentFocusEpochAfter = 8,
                focusedContentRoute = TvRoutes.SHOWS,
                subRouteFocusEpochBefore = 0,
                subRouteFocusEpochAfter = 0,
            ),
        )
        assertTrue(
            didPlaybackReturnFocusReachContent(
                focusRoute = TvRoutes.DETAILS,
                destinationHasFocus = false,
                contentFocusEpochBefore = 0,
                contentFocusEpochAfter = 0,
                focusedContentRoute = null,
                subRouteFocusEpochBefore = 3,
                subRouteFocusEpochAfter = 4,
            ),
        )
    }

    @Test
    fun alreadyFocusedPlaybackDestinationCompletesRestoreWithoutAnotherFocusEvent() {
        assertTrue(
            didPlaybackReturnFocusReachContent(
                focusRoute = TvRoutes.DETAILS,
                destinationHasFocus = true,
                contentFocusEpochBefore = 3,
                contentFocusEpochAfter = 3,
                focusedContentRoute = null,
                subRouteFocusEpochBefore = 9,
                subRouteFocusEpochAfter = 9,
            ),
        )
        assertFalse(
            didPlaybackReturnFocusReachContent(
                focusRoute = TvRoutes.DETAILS,
                destinationHasFocus = false,
                contentFocusEpochBefore = 3,
                contentFocusEpochAfter = 3,
                focusedContentRoute = null,
                subRouteFocusEpochBefore = 9,
                subRouteFocusEpochAfter = 9,
            ),
        )
    }

}
