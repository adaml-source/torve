package com.torve.presentation.home

import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomePlaybackPriorityTest {
    private val partial = WatchProgress("tmdb:1", MediaType.SERIES, "Show", positionMs = 120_000,
        durationMs = 1_200_000, seasonNumber = 2, episodeNumber = 5, updatedAt = 100)
    @Test fun `newest unfinished playback wins without changing list order`() {
        val newer = partial.copy(mediaId = "tmdb:2", updatedAt = 200)
        val input = listOf(partial, newer)
        assertEquals(newer, latestHomeResume(input))
        assertEquals(partial, input.first())
    }
    @Test fun `completed item does not displace unfinished playback`() {
        assertEquals(partial, latestHomeResume(listOf(partial, partial.copy(updatedAt = 300, positionMs = 1_190_000))))
    }
    @Test fun `saved placeholders and unstarted episodes cannot look like resume`() {
        assertNull(latestHomeResume(listOf(partial.copy(isContentPlaceholder = true), partial.copy(positionMs = 0))))
    }
    @Test fun `resume keeps exact series episode`() {
        val selected = latestHomeResume(listOf(partial))!!
        assertEquals(2, selected.seasonNumber)
        assertEquals(5, selected.episodeNumber)
    }
    @Test fun `briefly opened playback uses the same resume threshold as detail`() {
        assertNull(latestHomeResume(listOf(partial.copy(positionMs = 1_000))))
    }
}
