package com.torve.android.tv.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDynamicNavigationPolicyTest {

    @Test
    fun lateOptionalDestinationWaitsUntilTheRailOwnsFocus() {
        assertFalse(
            deferredOptionalDestinationVisibility(
                configured = true,
                currentlyVisible = false,
                railOwnsFocus = false,
            ),
        )
        assertTrue(
            deferredOptionalDestinationVisibility(
                configured = true,
                currentlyVisible = false,
                railOwnsFocus = true,
            ),
        )
    }

    @Test
    fun visibleDestinationStaysStableAndRemovalIsImmediate() {
        assertTrue(
            deferredOptionalDestinationVisibility(
                configured = true,
                currentlyVisible = true,
                railOwnsFocus = false,
            ),
        )
        assertFalse(
            deferredOptionalDestinationVisibility(
                configured = false,
                currentlyVisible = true,
                railOwnsFocus = false,
            ),
        )
    }
}
