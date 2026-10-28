package com.torve.android.tv.nav

import com.torve.presentation.settings.OptionalAppFeatures
import org.junit.Assert.*
import org.junit.Test

class TvOptionalFeatureNavigationTest {
    private val routes = listOf(TvRoutes.HOME, TvRoutes.MOVIES, TvRoutes.SHOWS, TvRoutes.SEARCH,
        TvRoutes.IPTV, TvRoutes.SPORTS, TvRoutes.JELLYFIN, TvRoutes.LIBRARY, TvRoutes.SETTINGS)
    @Test fun allServiceDestinationsStayReachableForSetup() {
        assertEquals(routes.filterNot { it == TvRoutes.SPORTS }, enabledTvNavigationRoutes(routes, OptionalAppFeatures(false, false, false, false), true, false))
    }
    @Test fun navigationOrderDoesNotChangeDuringHydration() {
        assertEquals(routes.filterNot { it == TvRoutes.SPORTS }, enabledTvNavigationRoutes(routes, OptionalAppFeatures(), false, false))
        assertEquals(routes.filterNot { it == TvRoutes.SPORTS }, enabledTvNavigationRoutes(routes, OptionalAppFeatures(), true, true))
    }
}
