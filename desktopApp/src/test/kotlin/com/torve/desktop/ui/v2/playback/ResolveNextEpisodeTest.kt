package com.torve.desktop.ui.v2.playback

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.Season
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins behaviour of [resolveNextEpisode] (Sprint 5 #1 — next-episode autoplay).
 * Pure function, no Compose / coroutines / clocks involved.
 */
class ResolveNextEpisodeTest {

    private fun series(seasons: List<Season>): MediaItem = MediaItem(
        id = "tv:1",
        type = MediaType.SERIES,
        title = "Test Series",
        seasons = seasons,
    )

    @Test
    fun `empty seasons returns null`() {
        val item = series(emptyList())
        assertNull(resolveNextEpisode(item, currentSeason = 1, currentEpisode = 1))
    }

    @Test
    fun `mid-season returns next episode in same season`() {
        val item = series(listOf(Season(1, episodeCount = 10)))
        assertEquals(NextEpisode(1, 6), resolveNextEpisode(item, 1, 5))
    }

    @Test
    fun `single-episode season then no next`() {
        val item = series(listOf(Season(1, episodeCount = 1)))
        assertNull(resolveNextEpisode(item, 1, 1))
    }

    @Test
    fun `last episode of season jumps to first episode of next season`() {
        val item = series(listOf(Season(1, episodeCount = 10), Season(2, episodeCount = 8)))
        assertEquals(NextEpisode(2, 1), resolveNextEpisode(item, 1, 10))
    }

    @Test
    fun `last episode of last season returns null`() {
        val item = series(listOf(Season(1, episodeCount = 10), Season(2, episodeCount = 8)))
        assertNull(resolveNextEpisode(item, 2, 8))
    }

    @Test
    fun `season zero is excluded from progression`() {
        // Season 0 typically holds specials — should not be visited.
        val item = series(listOf(Season(0, episodeCount = 5), Season(1, episodeCount = 4)))
        assertEquals(NextEpisode(1, 2), resolveNextEpisode(item, 1, 1))
    }

    @Test
    fun `seasons returned in unsorted order are still navigated correctly`() {
        val item = series(listOf(Season(3, 6), Season(1, 10), Season(2, 8)))
        assertEquals(NextEpisode(2, 1), resolveNextEpisode(item, 1, 10))
        assertEquals(NextEpisode(3, 1), resolveNextEpisode(item, 2, 8))
    }

    @Test
    fun `next season with zero episodes is skipped — but current code stops there (documented)`() {
        // resolveNextEpisode returns null when the *immediately* next season has 0 episodes
        // even if a later season has episodes. This documents the existing behaviour rather
        // than asserting an ideal — flagged in Section E if that's deemed a bug.
        val item = series(listOf(Season(1, 5), Season(2, episodeCount = 0), Season(3, 4)))
        assertNull(resolveNextEpisode(item, 1, 5))
    }

    @Test
    fun `episode count mismatch — current episode beyond season cap still advances to next season`() {
        // If we somehow reach episode 12 of a 10-episode season (data inconsistency),
        // the function still rolls forward to season 2.
        val item = series(listOf(Season(1, 10), Season(2, 8)))
        assertEquals(NextEpisode(2, 1), resolveNextEpisode(item, 1, 12))
    }

    @Test
    fun `current season not present in metadata still falls through to a later season`() {
        val item = series(listOf(Season(2, 8), Season(3, 6)))
        // Caller passes current = season 1 (which isn't in seasons list).
        // currentSeasonInfo is null, so the in-season branch is skipped; firstOrNull { > 1 }
        // returns season 2 → episode 1.
        assertEquals(NextEpisode(2, 1), resolveNextEpisode(item, 1, 5))
    }
}
