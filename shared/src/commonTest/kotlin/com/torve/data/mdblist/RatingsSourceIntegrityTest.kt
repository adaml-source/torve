package com.torve.data.mdblist

import com.torve.domain.model.MediaRatings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RatingsSourceIntegrityTest {
    @Test
    fun `legacy trakt fallback is not displayed as imdb`() {
        val sanitized = MediaRatings(
            imdbScore = 6.4f,
            imdbVotes = null,
            traktScore = 64f,
            tmdbScore = 7.1f,
        ).withoutLegacyTraktImdbFallback()

        assertNull(sanitized.imdbScore)
        assertEquals(64f, sanitized.traktScore)
        assertEquals(7.1f, sanitized.tmdbScore)
    }

    @Test
    fun `real imdb value is preserved when votes identify its source`() {
        val sanitized = MediaRatings(
            imdbScore = 9.3f,
            imdbVotes = 125_000,
            traktScore = 93f,
        ).withoutLegacyTraktImdbFallback()

        assertEquals(9.3f, sanitized.imdbScore)
        assertEquals(125_000, sanitized.imdbVotes)
    }
}
