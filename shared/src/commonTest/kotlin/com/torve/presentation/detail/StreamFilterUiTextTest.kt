package com.torve.presentation.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StreamFilterUiTextTest {

    @Test
    fun hiddenCountMessageShowsOnlyCount() {
        val message = StreamFilterUiText.hiddenCountMessage(3).orEmpty()

        assertEquals("3 streams hidden by filters", message)
        assertFalse(message.contains("https://"))
        assertFalse(message.contains("token", ignoreCase = true))
        assertFalse(message.contains("infoHash", ignoreCase = true))
        assertFalse(message.contains("memory", ignoreCase = true))
        assertFalse(message.contains("sourceKey", ignoreCase = true))
    }

    @Test
    fun hiddenCountMessageIsNotShownForZero() {
        assertNull(StreamFilterUiText.hiddenCountMessage(0))
    }

    @Test
    fun hiddenCountMessageIsNotShownWhenPremiumFeedbackIsDisabled() {
        assertNull(StreamFilterUiText.hiddenCountMessage(3, premiumFeedbackEnabled = false))
    }

    @Test
    fun allHiddenEmptyStateUsesSafeStaticCopy() {
        assertEquals(
            "All streams were hidden by your filters.",
            StreamFilterUiText.allHiddenMessage(visibleCount = 0, hiddenCount = 2),
        )
        assertEquals(
            "Adjust Regex Patterns in Settings to see more results.",
            StreamFilterUiText.allHiddenHint(visibleCount = 0, hiddenCount = 2),
        )
    }

    @Test
    fun tvManagementHintUsesSafeStaticCopy() {
        assertEquals(
            "Manage filters on mobile or desktop.",
            StreamFilterUiText.MANAGE_FILTERS_ON_MOBILE_OR_DESKTOP_HINT,
        )
        assertFalse(StreamFilterUiText.MANAGE_FILTERS_ON_MOBILE_OR_DESKTOP_HINT.contains("https://"))
        assertFalse(StreamFilterUiText.MANAGE_FILTERS_ON_MOBILE_OR_DESKTOP_HINT.contains("token", ignoreCase = true))
        assertFalse(StreamFilterUiText.MANAGE_FILTERS_ON_MOBILE_OR_DESKTOP_HINT.contains("regex", ignoreCase = true))
    }

    @Test
    fun allHiddenEmptyStateIsNotShownWhenNoFiltersHidStreams() {
        assertNull(StreamFilterUiText.allHiddenMessage(visibleCount = 0, hiddenCount = 0))
        assertNull(StreamFilterUiText.allHiddenHint(visibleCount = 0, hiddenCount = 0))
    }

    @Test
    fun allHiddenEmptyStateClearsWhenStreamsAreVisibleAgain() {
        assertNull(StreamFilterUiText.allHiddenMessage(visibleCount = 1, hiddenCount = 2))
        assertNull(StreamFilterUiText.allHiddenHint(visibleCount = 1, hiddenCount = 2))
    }

    @Test
    fun nonPremiumUsersDoNotSeeRegexEmptyStateCopy() {
        assertEquals(
            "No streams found",
            StreamFilterUiText.visibleErrorMessage(
                error = StreamFilterUiText.ALL_HIDDEN_MESSAGE,
                premiumFeedbackEnabled = false,
            ),
        )
        assertNull(
            StreamFilterUiText.visibleHint(
                hint = StreamFilterUiText.ADJUST_REGEX_HINT,
                premiumFeedbackEnabled = false,
            ),
        )
    }
}
