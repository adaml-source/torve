package com.torve.presentation.watching

import com.torve.domain.model.*
import com.torve.presentation.detail.*
import com.torve.presentation.home.latestHomeResume
import kotlinx.datetime.LocalDate
import kotlin.test.*

class VerifiedViewingNavigationTest {
    private val show = MediaItem("42", type = MediaType.SERIES, title = "Fixture")
    private val sought = WatchProgress("42", MediaType.SERIES, "Fixture", positionMs = 59_000,
        durationMs = 60_000, seasonNumber = 1, episodeNumber = 1, verifiedWatched = false)

    @Test fun seekingDoesNotAdvanceUpNextOrLoseExactEpisodeResume() {
        val today = LocalDate(2026, 10, 18)
        val episodes = (1..2).map { WatchingEpisode(show, 1, Episode(it), today) }
        val next = assertNotNull(chooseUpNext(episodes, emptySet(), sought, today))
        assertEquals(1, next.episode.episodeNumber)
        assertTrue(next.resume)
        val detail = DetailUiState(mediaItem = show, watchProgress = sought, streamContextSeason = 1, streamContextEpisode = 1)
        assertEquals(59_000, detail.primaryPlaybackTarget.positionMs)
        assertTrue(detail.primaryPlaybackTarget.isResume)
        assertEquals(sought, latestHomeResume(listOf(sought)))
        assertFalse(shouldClearEpisodeProgressOnUnwatch(sought, 1, 1))
    }
    @Test fun verifiedCompletionOverridesTheCurrentPosition() {
        val completed = sought.copy(positionMs = 30_000, verifiedWatched = true)
        assertTrue(completed.isWatched())
        assertFalse(DetailUiState(mediaItem = show, watchProgress = completed,
            streamContextSeason = 1, streamContextEpisode = 1).primaryPlaybackTarget.isResume)
        assertNull(latestHomeResume(listOf(completed)))
        assertTrue(shouldClearEpisodeProgressOnUnwatch(completed, 1, 1))
    }
    @Test fun anUnknownImportedLegacyBookmarkKeepsItsExistingThreshold() {
        assertTrue(sought.copy(verifiedWatched = null).isWatched())
        assertFalse(sought.copy(verifiedWatched = null, positionMs = 20_000).isWatched())
    }
    @Test fun downloadedFilesUseVerifiedCompletionRatherThanTheSeekPosition() {
        val download = Download("offline", "42", MediaType.SERIES, "Fixture", streamUrl = "",
            seasonNumber = 1, episodeNumber = 1)
        assertFalse(download.toDownloadedItem(sought).isWatched)
        assertTrue(download.toDownloadedItem(sought.copy(positionMs = 30_000, verifiedWatched = true)).isWatched)
    }
}
