package com.torve.presentation.detail

import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeUnwatchProgressPolicyTest {
    private val partial = WatchProgress("1418", MediaType.SERIES, "The Big Bang Theory",
        positionMs = 300_000, durationMs = 1_200_000, seasonNumber = 3, episodeNumber = 11)

    @Test fun removingAnAccidentalWatchedMarkRetainsThePartialResume() {
        assertFalse(shouldClearEpisodeProgressOnUnwatch(partial, 3, 11))
    }

    @Test fun removingACompletedEpisodeClearsItsCompletionProgress() {
        assertTrue(shouldClearEpisodeProgressOnUnwatch(partial.copy(positionMs = 1_020_000), 3, 11))
    }

    @Test fun removingAnotherEpisodeDoesNotEraseTheCurrentResume() {
        assertFalse(shouldClearEpisodeProgressOnUnwatch(partial.copy(positionMs = 1_200_000), 3, 10))
        assertFalse(shouldClearEpisodeProgressOnUnwatch(partial.copy(positionMs = 1_200_000), 2, 11))
    }

    @Test fun MissingProgressAndUnknownEpisodeNeverTriggerADeletion() {
        assertFalse(shouldClearEpisodeProgressOnUnwatch(null, 3, 11))
        assertFalse(shouldClearEpisodeProgressOnUnwatch(partial.copy(episodeNumber = null), 3, 11))
    }
}
