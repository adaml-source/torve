package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class TorveScoreTest {

    @Test
    fun calculateTorveScore_normalizesWeightsAcrossAvailableSources() {
        val ratings = MediaRatings(
            imdbScore = 8.0f, // 80
            tmdbScore = 7.0f, // 70
        )
        val weights = mapOf(
            RatingSource.IMDB to 80,
            RatingSource.TMDB to 20,
            RatingSource.ROTTEN_TOMATOES to 100, // missing source should be ignored
        )

        val score = calculateTorveScore(ratings, weights)
        assertNotNull(score)
        assertEquals(78f, score, 0.01f)
    }

    @Test
    fun calculateTorveScore_returnsNullWhenNoWeightedSourcesAvailable() {
        val ratings = MediaRatings()
        val weights = mapOf(
            RatingSource.IMDB to 50,
            RatingSource.TMDB to 50,
        )

        val score = calculateTorveScore(ratings, weights)

        assertNull(score)
    }

    @Test
    fun calculateTorveScore_ignoresNonPositiveWeights() {
        val ratings = MediaRatings(
            imdbScore = 9.0f, // 90
            tmdbScore = 6.0f, // 60
        )
        val weights = mapOf(
            RatingSource.IMDB to 100,
            RatingSource.TMDB to 0,
            RatingSource.ROTTEN_TOMATOES to -10,
        )

        val score = calculateTorveScore(ratings, weights)
        assertNotNull(score)
        assertEquals(90f, score, 0.01f)
    }

    @Test
    fun calculateTorveScore_clampsOutOfRangeValues() {
        val ratings = MediaRatings(
            traktScore = 130f,
            mdblistScore = -20f,
        )
        val weights = mapOf(
            RatingSource.TRAKT to 50,
            RatingSource.MDBLIST to 50,
        )

        val score = calculateTorveScore(ratings, weights)
        assertNotNull(score)
        assertEquals(50f, score, 0.01f)
    }
}
