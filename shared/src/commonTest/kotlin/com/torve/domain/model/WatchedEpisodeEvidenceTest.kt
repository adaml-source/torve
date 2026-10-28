package com.torve.domain.model

import kotlin.test.*

class WatchedEpisodeEvidenceTest {
    private val started = WatchHistoryEntry("local:series_s1e1_1792502000000", "local:series", "SERIES", "Series",
        null, null, 1792502000000, 0, 1, 1, "Series")
    @Test fun playbackStartIsNotACompletedEpisode() { assertFalse(started.countsAsWatchedEpisode()) }
    @Test fun realCompletionKeepsTheEpisodeWatched() { assertTrue(started.copy(durationWatchedMs = 180000).countsAsWatchedEpisode()) }
    @Test fun explicitAndImportedMarksRemainValidWithoutRuntime() {
        assertTrue(started.copy(id = "local:series_s1e1").countsAsWatchedEpisode())
        assertTrue(started.copy(id = "trakt-history-123").countsAsWatchedEpisode())
    }
    @Test fun aWholeShowMarkIsNotASpecificEpisode() { assertFalse(started.copy(seasonNumber = null, episodeNumber = null).countsAsWatchedEpisode()) }
}
