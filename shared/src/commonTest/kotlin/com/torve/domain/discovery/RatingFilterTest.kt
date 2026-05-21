package com.torve.domain.discovery

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RatingFilterTest {
    @Test
    fun tmdbThresholdUsesTmdbRatingAndFallback() {
        val item = media(tmdb = 7.3f)

        assertTrue(item.matchesRatingFilter(7f, DiscoveryRatingSource.TMDB))
        assertFalse(item.matchesRatingFilter(8f, DiscoveryRatingSource.TMDB))
    }

    @Test
    fun strictSourceExcludesMissingRatingWhenThresholdIsActive() {
        val item = media(tmdb = 8.2f, imdb = null)

        assertFalse(item.matchesRatingFilter(7f, DiscoveryRatingSource.IMDB))
    }

    @Test
    fun imdbThresholdUsesImdbRating() {
        val item = media(tmdb = 6.2f, imdb = 8.1f)

        assertTrue(item.matchesRatingFilter(8f, DiscoveryRatingSource.IMDB))
        assertFalse(item.matchesRatingFilter(8f, DiscoveryRatingSource.TMDB))
    }

    @Test
    fun traktThresholdNormalizesPercentScale() {
        val item = media(trakt = 82f)

        assertTrue(item.matchesRatingFilter(8f, DiscoveryRatingSource.TRAKT))
        assertFalse(item.matchesRatingFilter(9f, DiscoveryRatingSource.TRAKT))
    }

    @Test
    fun anyRatingPassesWhenAnySupportedSourceMeetsThreshold() {
        val item = media(tmdb = 6.1f, imdb = 8.4f)

        assertTrue(item.matchesRatingFilter(8f, DiscoveryRatingSource.AnyRating))
    }

    @Test
    fun tmdbDiscoverRatingOnlyAppliesForTmdbSource() {
        assertEquals(7f, tmdbDiscoverRating(7f, DiscoveryRatingSource.TMDB))
        assertEquals(null, tmdbDiscoverRating(7f, DiscoveryRatingSource.IMDB))
        assertEquals(null, tmdbDiscoverRating(7f, DiscoveryRatingSource.AnyRating))
    }

    private fun media(
        tmdb: Float? = null,
        imdb: Float? = null,
        trakt: Float? = null,
    ): MediaItem = MediaItem(
        id = "movie",
        tmdbId = 1,
        type = MediaType.MOVIE,
        title = "Movie",
        rating = tmdb?.toDouble(),
        ratings = MediaRatings(
            tmdbScore = tmdb,
            imdbScore = imdb,
            traktScore = trakt,
        ),
    )
}
