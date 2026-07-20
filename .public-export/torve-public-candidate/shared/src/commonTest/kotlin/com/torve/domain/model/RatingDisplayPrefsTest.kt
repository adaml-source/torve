package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingDisplayPrefsTest {

    @Test
    fun deriveProvidersToRender_respectsOrderAndMax() {
        val enabled = listOf(
            RatingSource.IMDB,
            RatingSource.ROTTEN_TOMATOES,
            RatingSource.TMDB,
            RatingSource.METACRITIC,
        )
        val order = listOf(
            RatingSource.TMDB,
            RatingSource.IMDB,
            RatingSource.ROTTEN_TOMATOES,
            RatingSource.MAL,
        )

        val result = deriveProvidersToRender(
            enabledProviders = enabled,
            providerOrder = order,
            maxRatingsOnCard = 3,
        )

        assertEquals(
            listOf(RatingSource.TMDB, RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES),
            result,
        )
    }

    @Test
    fun isOutsidePosition_selectsOutsideBranch() {
        assertTrue(RatingPillPosition.OUTSIDE.isOutside())
        assertFalse(RatingPillPosition.INSIDE.isOutside())
    }

    @Test
    fun deriveProvidersToRender_returnsEmptyWhenMaxIsZero() {
        val result = deriveProvidersToRender(
            enabledProviders = listOf(RatingSource.IMDB, RatingSource.TMDB),
            providerOrder = listOf(RatingSource.TMDB, RatingSource.IMDB),
            maxRatingsOnCard = 0,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun deriveProvidersToRender_skipsDisabledProviders() {
        val result = deriveProvidersToRender(
            enabledProviders = listOf(RatingSource.IMDB),
            providerOrder = listOf(RatingSource.TMDB, RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES),
            maxRatingsOnCard = 3,
        )

        assertEquals(listOf(RatingSource.IMDB), result)
    }

    @Test
    fun deriveProvidersToRender_usesTmdbOnlyWhenNoProviderIsSelected() {
        val result = deriveProvidersToRender(
            enabledProviders = emptyList(),
            providerOrder = listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES),
            maxRatingsOnCard = 3,
        )

        assertEquals(listOf(RatingSource.TMDB), result)
    }

    @Test
    fun deriveProvidersToRender_canDisableEmptySelectionTmdbFallback() {
        val result = deriveProvidersToRender(
            enabledProviders = emptyList(),
            providerOrder = listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES),
            maxRatingsOnCard = 3,
            fallbackToTmdbWhenNoneSelected = false,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun withFallbackTmdbScore_onlyAddsTmdbWhenMissing() {
        val fromBaseline = null.withFallbackTmdbScore(7.4)
        assertEquals(7.4f, fromBaseline?.tmdbScore)

        val preserved = MediaRatings(imdbScore = 8.1f, tmdbScore = 6.9f).withFallbackTmdbScore(7.4)
        assertEquals(6.9f, preserved?.tmdbScore)
        assertEquals(8.1f, preserved?.imdbScore)

        assertNull(null.withFallbackTmdbScore(null))
    }

    @Test
    fun hasAnyEnabledDisplayValue_respectsEnabledProviders() {
        val ratings = MediaRatings(imdbScore = 8.0f, tmdbScore = 7.2f)

        assertFalse(
            ratings.hasAnyEnabledDisplayValue(
                RatingDisplayPrefs(enabledProviders = listOf(RatingSource.ROTTEN_TOMATOES)),
            ),
        )
        assertTrue(
            ratings.hasAnyEnabledDisplayValue(
                RatingDisplayPrefs(enabledProviders = listOf(RatingSource.IMDB)),
            ),
        )
        assertTrue(
            ratings.hasAnyEnabledDisplayValue(
                RatingDisplayPrefs(enabledProviders = emptyList()),
            ),
        )
        assertFalse(
            ratings.hasAnyEnabledDisplayValue(
                RatingDisplayPrefs(
                    enabledProviders = listOf(RatingSource.TORVE),
                    showTorveScoreOnCards = false,
                ),
                includeTorve = false,
            ),
        )
    }
}
