package com.torve.android.ui.watchlist

import com.torve.presentation.settings.OptionalAppFeatures
import org.junit.Assert.*
import org.junit.Test

class MobileLibraryFeaturePolicyTest {
    @Test fun unloadedPreferencesDoNotExposeOptionalTabs() {
        assertEquals(listOf(MobileLibraryTab.WATCHLIST, MobileLibraryTab.FAVORITES, MobileLibraryTab.DOWNLOADS),
            mobileLibraryTabs(OptionalAppFeatures(), false, true))
    }
    @Test fun disablingFeaturesHidesOnlyTheirTabs() {
        assertEquals(listOf(MobileLibraryTab.WATCHLIST, MobileLibraryTab.FAVORITES, MobileLibraryTab.DOWNLOADS),
            mobileLibraryTabs(OptionalAppFeatures(jellyfin = false, arrStack = false), true, true))
    }
    @Test fun disconnectedJellyfinIsHiddenWithRequestsStillAvailable() {
        assertEquals(listOf(MobileLibraryTab.WATCHLIST, MobileLibraryTab.FAVORITES, MobileLibraryTab.REQUESTS, MobileLibraryTab.DOWNLOADS),
            mobileLibraryTabs(OptionalAppFeatures(), true, false))
    }
    @Test fun savedJellyfinIndexStillSelectsJellyfinAfterRequestsAreDisabled() {
        val available = mobileLibraryTabs(OptionalAppFeatures(arrStack = false), true, true)
        assertEquals(MobileLibraryTab.JELLYFIN, restoreMobileLibraryTab(4, available))
    }
    @Test fun disabledOrInvalidSavedTabFallsBackToWatchlist() {
        val available = mobileLibraryTabs(OptionalAppFeatures(arrStack = false), true, false)
        assertEquals(MobileLibraryTab.WATCHLIST, restoreMobileLibraryTab(2, available))
        assertEquals(MobileLibraryTab.WATCHLIST, restoreMobileLibraryTab(99, available))
    }
}
