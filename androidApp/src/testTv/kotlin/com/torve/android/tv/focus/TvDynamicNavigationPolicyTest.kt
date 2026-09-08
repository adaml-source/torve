package com.torve.android.tv.focus

import org.junit.Assert.assertEquals
import org.junit.Test

class TvDynamicNavigationPolicyTest {

    @Test
    fun lateOptionalDestinationChangesAvailabilityWithoutChangingRailSlots() {
        val stableSlots = listOf("home", "jellyfin", "library", "settings")

        assertEquals(
            linkedSetOf("home", "library", "settings"),
            availableNavigationRoutes(
                allRoutes = stableSlots,
                optionalRoute = "jellyfin",
                optionalRouteAvailable = false,
            ),
        )
        assertEquals(
            stableSlots.toSet(),
            availableNavigationRoutes(
                allRoutes = stableSlots,
                optionalRoute = "jellyfin",
                optionalRouteAvailable = true,
            ),
        )
    }

    @Test
    fun missingOptionalRouteDoesNotRemoveOrdinaryDestinations() {
        assertEquals(
            linkedSetOf("home", "library", "settings"),
            availableNavigationRoutes(
                allRoutes = listOf("home", "library", "settings"),
                optionalRoute = "jellyfin",
                optionalRouteAvailable = false,
            ),
        )
    }
}
