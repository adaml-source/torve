package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayRatingTest {
    @Test
    fun imdbBeatsAllOtherSources() {
        val rating = MediaRatings(
            imdbScore = 8.6f,
            mdblistScore = 91f,
            traktScore = 88f,
            tmdbScore = 9.4f,
        ).bestDisplayRating()

        assertEquals(RatingSource.IMDB, rating?.source)
        assertEquals(8.6, rating?.value)
    }

    @Test
    fun mdblistBeatsTraktAndTmdbWhenImdbMissing() {
        val rating = MediaRatings(
            mdblistScore = 82f,
            traktScore = 91f,
            tmdbScore = 9.4f,
        ).bestDisplayRating()

        assertEquals(RatingSource.MDBLIST, rating?.source)
        assertEquals(8.2, rating?.value)
    }

    @Test
    fun traktBeatsTmdbWhenHigherSourcesMissing() {
        val rating = MediaRatings(
            traktScore = 79f,
            tmdbScore = 9.4f,
        ).bestDisplayRating()

        assertEquals(RatingSource.TRAKT, rating?.source)
        assertEquals(7.9, rating?.value)
    }

    @Test
    fun tmdbFallbackIsUsedOnlyWhenNoBetterSourceExists() {
        val rating = MediaRatings().bestDisplayRating(tmdbFallback = 7.44)

        assertEquals(RatingSource.TMDB, rating?.source)
        assertEquals(7.4, rating?.value)
    }

    @Test
    fun invalidZeroAndOutOfRangeRatingsReturnNull() {
        assertNull(MediaRatings(imdbScore = 0f, tmdbScore = 0f).bestDisplayRating())
        assertNull(MediaRatings(imdbScore = 101f).bestDisplayRating())
        assertNull(MediaRatings().bestDisplayRating(tmdbFallback = 0.0))
    }
}
