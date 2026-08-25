package com.torve.android.tv.focus

import com.torve.android.tv.nav.TvRoutes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TvPlaybackFocusRestorePolicyTest {

    @Test
    fun playbackReturnTargetsDetailsWhenDetailsOwnsTheDestination() {
        assertEquals(
            TvRoutes.DETAILS,
            playbackReturnFocusRoute("tv_details/tv/123", TvRoutes.SHOWS),
        )
        assertEquals(
            TvRoutes.IPTV,
            playbackReturnFocusRoute(TvRoutes.SUB_NAV_START, TvRoutes.IPTV),
        )
    }

    @Test
    fun requestFocusIsNotSuccessUntilTheExpectedSurfaceReportsFocus() {
        assertFalse(
            didPlaybackReturnFocusReachContent(
                focusRoute = TvRoutes.SHOWS,
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
                contentFocusEpochBefore = 0,
                contentFocusEpochAfter = 0,
                focusedContentRoute = null,
                subRouteFocusEpochBefore = 3,
                subRouteFocusEpochAfter = 4,
            ),
        )
    }

    @Test
    fun detailsNeverFallsBackToTheHiddenRail() {
        assertFalse(canPlaybackReturnFallbackToRail(TvRoutes.DETAILS, isSubRouteActive = true))
        assertTrue(canPlaybackReturnFallbackToRail(TvRoutes.SHOWS, isSubRouteActive = false))
    }
}

