package com.torve.android.ui.components

import com.torve.domain.model.MediaRatings
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingSource
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferredRatingProviderTest {

    @Test
    fun selectedExternalValueWinsOverTmdbFallback() {
        val ratings = MediaRatings(imdbScore = 8.4f, tmdbScore = 7.2f)
        val prefs = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.IMDB))

        assertEquals(
            listOf(RatingSource.IMDB),
            preferredExternalRatingProviders(ratings, prefs),
        )
    }

    @Test
    fun tmdbIsExplicitFallbackWhenSelectedProvidersHaveNoValue() {
        val ratings = MediaRatings(tmdbScore = 7.2f, traktScore = 81f)
        val prefs = RatingDisplayPrefs(
            enabledProviders = listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES),
        )

        assertEquals(
            listOf(RatingSource.TMDB),
            preferredExternalRatingProviders(ratings, prefs),
        )
    }

    @Test
    fun torveOnlyPreferenceDoesNotEnableExternalFallback() {
        val ratings = MediaRatings(tmdbScore = 7.2f)
        val prefs = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.TORVE))

        assertEquals(emptyList<RatingSource>(), preferredExternalRatingProviders(ratings, prefs))
    }

    @Test
    fun zeroTmdbScoreIsTreatedAsNotYetRated() {
        val ratings = MediaRatings(tmdbScore = 0f)
        val prefs = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.IMDB))

        assertEquals(emptyList<RatingSource>(), preferredExternalRatingProviders(ratings, prefs))
    }
}
