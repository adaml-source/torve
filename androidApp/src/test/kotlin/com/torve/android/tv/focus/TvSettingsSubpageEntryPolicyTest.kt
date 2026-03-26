package com.torve.android.tv.focus

import com.torve.android.tv.nav.TvRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSettingsSubpageEntryPolicyTest {

    @Test
    fun resolveContentEntryRoute_prefersExplicitSettingsSubpageRoute() {
        val route = resolveContentEntryRoute(
            pendingContentEntryRoute = null,
            currentSubRoute = TvRoutes.HOME_LAYOUT,
            isSubRouteActive = true,
            isRailFocused = false,
            confirmedTopRoute = TvRoutes.SETTINGS,
            selectedTopRoute = TvRoutes.SETTINGS,
        )

        assertEquals(TvRoutes.HOME_LAYOUT, route)
    }

    @Test
    fun resolveContentEntryRoute_keepsGenericDetailsFallback_forNonSettingsSubroutes() {
        val route = resolveContentEntryRoute(
            pendingContentEntryRoute = null,
            currentSubRoute = TvRoutes.DETAILS,
            isSubRouteActive = true,
            isRailFocused = false,
            confirmedTopRoute = TvRoutes.MOVIES,
            selectedTopRoute = TvRoutes.MOVIES,
        )

        assertEquals(TvRoutes.DETAILS, route)
    }

    @Test
    fun shouldSuppressRailForSettingsSubpageEntry_onlyWhileOwnedHandoffIsPending() {
        assertTrue(
            shouldSuppressRailForSettingsSubpageEntry(
                pendingRoute = TvRoutes.RATINGS_SETTINGS,
                currentSubRoute = null,
            ),
        )
        assertTrue(
            shouldSuppressRailForSettingsSubpageEntry(
                pendingRoute = TvRoutes.RATINGS_SETTINGS,
                currentSubRoute = TvRoutes.RATINGS_SETTINGS,
            ),
        )
        assertFalse(
            shouldSuppressRailForSettingsSubpageEntry(
                pendingRoute = TvRoutes.DETAILS,
                currentSubRoute = TvRoutes.DETAILS,
            ),
        )
    }
}
