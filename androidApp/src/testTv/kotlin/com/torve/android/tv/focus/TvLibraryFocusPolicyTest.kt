package com.torve.android.tv.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLibraryFocusPolicyTest {
    @Test fun primaryCollectionsStayAvailableWithoutConnections() {
        val tabs = TvLibraryFocusPolicy.tabsForFeatures(com.torve.presentation.settings.OptionalAppFeatures(channels = false, arrStack = false))
        assertEquals(listOf(TvLibraryTab.WATCHLIST, TvLibraryTab.FAVORITES, TvLibraryTab.CONTINUE, TvLibraryTab.HISTORY), tabs)
        assertEquals(TvLibraryTab.HISTORY, TvLibraryFocusPolicy.restoreCanonicalTab(3, tabs))
        assertEquals(TvLibraryTab.WATCHLIST, TvLibraryFocusPolicy.restoreTab(TvLibraryTab.REQUESTS.name, tabs))
    }
    @Test fun staleSavedIndexIsSafe() {
        assertEquals(TvLibraryTab.HISTORY, TvLibraryFocusPolicy.tabAt(99))
        assertEquals(TvLibraryTab.WATCHLIST, TvLibraryFocusPolicy.tabAt(-1))
    }
    @Test
    fun clickFocusRestoresOnlyToTheSelectedTab() {
        assertTrue(TvLibraryFocusPolicy.shouldRestoreClickedTab(2, 2))
        assertFalse(TvLibraryFocusPolicy.shouldRestoreClickedTab(1, 2))
        assertFalse(TvLibraryFocusPolicy.shouldRestoreClickedTab(null, 2))
    }
}
