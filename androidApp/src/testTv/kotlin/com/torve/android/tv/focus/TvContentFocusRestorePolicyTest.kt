package com.torve.android.tv.focus

import com.torve.android.tv.nav.TvRoutes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvContentFocusRestorePolicyTest {

    @Test
    fun activeDetailsAlreadyFocused_stopsImplicitRestoreRetries() {
        assertTrue(
            shouldStopContentFocusRestore(
                activeRoute = TvRoutes.DETAILS,
                focusedContentRoute = TvRoutes.DETAILS,
            ),
        )
    }

    @Test
    fun matchingRouteWithoutPhysicalContentFocus_keepsBackRestoreEligible() {
        assertFalse(
            shouldStopContentFocusRestore(
                activeRoute = TvRoutes.HOME,
                focusedContentRoute = TvRoutes.HOME,
                contentOwnsFocus = false,
            ),
        )
    }

    @Test
    fun differentOrMissingFocus_keepsRestoreEligible() {
        assertFalse(
            shouldStopContentFocusRestore(
                activeRoute = TvRoutes.DETAILS,
                focusedContentRoute = TvRoutes.MOVIES,
            ),
        )
        assertFalse(
            shouldStopContentFocusRestore(
                activeRoute = TvRoutes.DETAILS,
                focusedContentRoute = null,
            ),
        )
    }

    @Test
    fun detailAndOtherRegularSubRoutes_ownTheirEntryFocus() {
        assertFalse(
            shouldRunRootContentFocusRestore(
                currentSubRoute = TvRoutes.DETAILS,
                isSubRouteActive = true,
            ),
        )
        assertFalse(
            shouldRunRootContentFocusRestore(
                currentSubRoute = TvRoutes.SEE_ALL,
                isSubRouteActive = true,
            ),
        )
    }

    @Test
    fun topLevelAndSettingsSubpages_keepRootRestoreSupport() {
        assertTrue(
            shouldRunRootContentFocusRestore(
                currentSubRoute = null,
                isSubRouteActive = false,
            ),
        )
        assertTrue(
            shouldRunRootContentFocusRestore(
                currentSubRoute = TvRoutes.HOME_LAYOUT,
                isSubRouteActive = true,
            ),
        )
    }
}
