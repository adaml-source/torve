package com.torve.presentation.detail

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailPlaybackTargetTest {
    private val show = MediaItem(id = "tmdb:1418", type = MediaType.SERIES, title = "The Big Bang Theory")
    private val progress = WatchProgress(mediaId = show.id, mediaType = show.type, title = show.title,
        seasonNumber = 3, episodeNumber = 11, positionMs = 300_000, durationMs = 1_200_000)

    @Test fun `selected partial episode resumes at the known position`() {
        val target = DetailUiState(mediaItem = show, watchProgress = progress,
            streamContextSeason = 3, streamContextEpisode = 11).primaryPlaybackTarget
        assertTrue(target.isResume)
        assertEquals(300_000L, target.positionMs)
        assertEquals(3, target.season)
        assertEquals(11, target.episode)
    }
    @Test fun `selecting another episode does not borrow resume from the partial episode`() {
        val target = DetailUiState(mediaItem = show, watchProgress = progress,
            streamContextSeason = 3, streamContextEpisode = 12,
            nextEpisode = NextEpisodeInfo(3, 11, mode = NextEpisodeMode.RESUME_IN_PROGRESS)).primaryPlaybackTarget
        assertFalse(target.isResume)
        assertEquals(12, target.episode)
        assertEquals(null, target.positionMs)
    }
    @Test fun `completed progress is not offered as resume`() {
        val target = DetailUiState(mediaItem = show, watchProgress = progress.copy(positionMs = 1_199_000),
            streamContextSeason = 3, streamContextEpisode = 11).primaryPlaybackTarget
        assertFalse(target.isResume)
    }
    @Test fun `default action follows the resolved next episode`() {
        val target = DetailUiState(mediaItem = show, watchProgress = progress,
            nextEpisode = NextEpisodeInfo(3, 11, mode = NextEpisodeMode.RESUME_IN_PROGRESS)).primaryPlaybackTarget
        assertTrue(target.isResume)
        assertEquals(11, target.episode)
    }
    @Test fun `first unwatched episode is a new playback action`() {
        val target = DetailUiState(mediaItem = show,
            nextEpisode = NextEpisodeInfo(3, 12, mode = NextEpisodeMode.PLAY_FIRST_UNWATCHED)).primaryPlaybackTarget
        assertFalse(target.isResume)
        assertEquals(12, target.episode)
    }
    @Test fun `movie resume includes its timestamp`() {
        val movie = MediaItem(id = "tmdb:42", type = MediaType.MOVIE, title = "Movie")
        val target = DetailUiState(mediaItem = movie, watchProgress = progress.copy(mediaId = movie.id,
            mediaType = movie.type, seasonNumber = null, episodeNumber = null)).primaryPlaybackTarget
        assertTrue(target.isResume)
        assertEquals("5:00", playbackPositionLabel(target.positionMs!!))
    }
    @Test fun `timestamp formats hours without losing minutes or seconds`() {
        assertEquals("1:04:09", playbackPositionLabel(3_849_000))
        assertEquals("0:00", playbackPositionLabel(-1))
    }

    @Test fun `85 to 90 percent completed episodes never remain the preferred resume`() {
        for (position in listOf(1_020_000L, 1_074_000L, 1_080_000L)) {
            val completed = progress.copy(positionMs = position)
            assertEquals(null, preferredInProgressEpisode(show, listOf(completed)))
            assertFalse(DetailUiState(mediaItem = show, watchProgress = completed,
                streamContextSeason = 3, streamContextEpisode = 11).primaryPlaybackTarget.isResume)
        }
    }
    @Test fun `an episode below completion stays resumable`() {
        val partial = progress.copy(positionMs = 1_019_000)
        assertEquals(partial, preferredInProgressEpisode(show, listOf(partial)))
        assertTrue(DetailUiState(mediaItem = show, watchProgress = partial,
            streamContextSeason = 3, streamContextEpisode = 11).primaryPlaybackTarget.isResume)
    }
    @Test fun `seeking near the end cannot override verified viewing coverage`() {
        val seekOnly = progress.copy(positionMs = 1_190_000, verifiedWatched = false)
        assertEquals(seekOnly, preferredInProgressEpisode(show, listOf(seekOnly)))
        assertTrue(DetailUiState(mediaItem = show, watchProgress = seekOnly,
            streamContextSeason = 3, streamContextEpisode = 11).primaryPlaybackTarget.isResume)
    }
}
