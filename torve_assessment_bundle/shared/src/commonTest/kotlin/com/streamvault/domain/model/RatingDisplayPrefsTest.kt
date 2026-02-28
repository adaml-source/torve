package com.streamvault.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
