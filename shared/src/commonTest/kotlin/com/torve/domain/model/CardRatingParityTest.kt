package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CardRatingParityTest {
    @Test fun imdbAndTomatoesLeadAndTmdbDoesNotSubstituteForAnExternalScore() {
        val preferences = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.TMDB, RatingSource.ROTTEN_TOMATOES, RatingSource.IMDB),
            providerOrder = listOf(RatingSource.TMDB, RatingSource.ROTTEN_TOMATOES, RatingSource.IMDB), maxRatingsOnCard = 2)
        val resolved = preferences.forAvailableCardRatings(MediaRatings(imdbScore = 8.3f, rottenTomatoesScore = 91, tmdbScore = 7.5f))
        assertEquals(listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES), resolved.providerOrder)
        assertEquals(2, resolved.maxRatingsOnCard)
    }
    @Test fun unavailableRequestedScoresStayUnavailable() {
        val resolved = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.ROTTEN_TOMATOES))
            .forAvailableCardRatings(MediaRatings(imdbScore = 8f, tmdbScore = 7f))
        assertEquals(emptyList(), resolved.enabledProviders)
        assertEquals(0, resolved.maxRatingsOnCard)
    }
    @Test fun tmdbIsUsedWhenItIsTheOnlyAvailableEnabledScore() {
        val resolved = RatingDisplayPrefs().forAvailableCardRatings(MediaRatings(tmdbScore = 7.4f))
        assertEquals(listOf(RatingSource.TMDB), resolved.providerOrder)
    }
}
