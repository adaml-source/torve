package com.torve.presentation.detail

import com.torve.domain.model.Season
import kotlin.test.Test
import kotlin.test.assertEquals

class FirstUnwatchedEpisodeTest {
    @Test
    fun `completed seasons advance selector to first unwatched season and episode`() {
        val seasons = listOf(
            Season(seasonNumber = 1, episodeCount = 10),
            Season(seasonNumber = 2, episodeCount = 8),
            Season(seasonNumber = 3, episodeCount = 8),
        )
        val watched = buildSet {
            (1..10).forEach { add(episodeKey(1, it)) }
            (1..8).forEach { add(episodeKey(2, it)) }
            add(episodeKey(3, 1))
            add(episodeKey(3, 2))
        }

        assertEquals(3 to 3, firstUnwatchedEpisode(seasons, watched))
    }

    @Test
    fun `specials are skipped and first real episode is selected for a new show`() {
        val seasons = listOf(
            Season(seasonNumber = 0, episodeCount = 4),
            Season(seasonNumber = 1, episodeCount = 6),
        )

        assertEquals(1 to 1, firstUnwatchedEpisode(seasons, emptySet()))
    }
}
