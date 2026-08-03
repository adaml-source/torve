package com.torve.android.tv.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLibraryFocusPolicyTest {
    @Test
    fun visibleTabsMatchTheLibrarySubsections() {
        assertEquals(
            listOf(
                TvLibraryTab.WATCHLIST,
                TvLibraryTab.FAVORITES,
                TvLibraryTab.REQUESTS,
                TvLibraryTab.VOD,
            ),
            TvLibraryFocusPolicy.visibleTabs,
        )
    }

    @Test
    fun staleSavedIndexClampsToLastVisibleTab() {
        assertEquals(TvLibraryTab.VOD, TvLibraryFocusPolicy.tabAt(99))
        assertEquals(TvLibraryTab.WATCHLIST, TvLibraryFocusPolicy.tabAt(-1))
    }

    @Test
    fun clickFocusRestoresOnlyToTheSelectedTab() {
        assertTrue(TvLibraryFocusPolicy.shouldRestoreClickedTab(2, 2))
        assertFalse(TvLibraryFocusPolicy.shouldRestoreClickedTab(1, 2))
        assertFalse(TvLibraryFocusPolicy.shouldRestoreClickedTab(null, 2))
    }
}
