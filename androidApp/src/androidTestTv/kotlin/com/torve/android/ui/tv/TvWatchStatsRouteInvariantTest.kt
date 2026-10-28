package com.torve.android.ui.tv

import com.torve.android.tv.nav.TvRoutes
import com.torve.android.tv.nav.tvTopDestinations
import org.junit.Assert.assertFalse
import org.junit.Test

class TvWatchStatsRouteInvariantTest {
    @Test
    fun watchStatsRemainsSubRouteOnly() {
        assertFalse(tvTopDestinations.any { it.route == TvRoutes.WATCH_STATS })
    }
}
