package com.torve.android.tv

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TvRootSettingsEntryOrderTest {

    @Test
    fun orderedSettingsEntryCandidates_prefersSettingsLocalRequestersBeforeFirstContentFallback() {
        val settingsEntryRequester = FocusRequester()
        val settingsMainEntryFocusRequester = FocusRequester()
        val firstContentFocusRequester = FocusRequester()

        val candidates = orderedSettingsEntryCandidates(
            settingsEntryRequester = settingsEntryRequester,
            settingsMainEntryFocusRequester = settingsMainEntryFocusRequester,
            firstContentFocusRequester = firstContentFocusRequester,
        )

        assertEquals(3, candidates.size)
        assertSame(settingsEntryRequester, candidates[0])
        assertSame(settingsMainEntryFocusRequester, candidates[1])
        assertSame(firstContentFocusRequester, candidates[2])
    }

    @Test
    fun orderedSettingsEntryCandidates_deduplicatesSharedRequestersWithoutChangingPriority() {
        val sharedRequester = FocusRequester()
        val firstContentFocusRequester = FocusRequester()

        val candidates = orderedSettingsEntryCandidates(
            settingsEntryRequester = sharedRequester,
            settingsMainEntryFocusRequester = sharedRequester,
            firstContentFocusRequester = firstContentFocusRequester,
        )

        assertEquals(2, candidates.size)
        assertSame(sharedRequester, candidates[0])
        assertSame(firstContentFocusRequester, candidates[1])
    }
}
