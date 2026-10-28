package com.torve.presentation.apple

import com.torve.domain.model.*
import com.torve.presentation.home.HomeUiState
import kotlin.test.*

class AppleTvLibraryLayoutTest {
    private val pair = MediaRatings(imdbScore = 8.6f, rottenTomatoesScore = 94)

    @Test fun importedWatchlistRetainsEnrichmentWithoutChangingSavedIdentity() {
        val saved = WatchlistItem("tmdb:movie:123", MediaType.MOVIE, 123, title = "Saved title", posterUrl = "saved.jpg", rating = 7.0, year = 2024, addedAt = 1)
        val enriched = MediaItem(id = "123", type = MediaType.MOVIE, title = "Remote title", tmdbId = 123, ratings = pair)
        val result = AppleTvLibraryLayout.watchlist(saved, HomeUiState(watchlistItems = listOf(enriched)))
        assertEquals("tmdb:movie:123", result.id)
        assertEquals("Saved title", result.title)
        assertEquals("saved.jpg", result.posterUrl)
        assertEquals(7.0, result.rating)
        assertEquals(pair, result.ratings)
        val pills = displayAppleRatings(result, RatingDisplayPrefs(), false)
        assertTrue(pills.any { it.provider == "IMDB" })
        assertTrue(pills.any { it.provider == "ROTTEN_TOMATOES" })
    }

    @Test fun lateRatingLookupUpdatesSavedRowsAndDoesNotCrossMediaTypes() {
        val item = MediaItem(id = "123", type = MediaType.SERIES, title = "A show", tmdbId = 123)
        val home = HomeUiState(continueWatchingRatings = mapOf("tmdb:SERIES:123" to pair, "tmdb:MOVIE:123" to MediaRatings(imdbScore = 1f)))
        assertEquals(pair, AppleTvLibraryLayout.rated(item, home).ratings)
        assertNull(AppleTvLibraryLayout.rated(item, HomeUiState(watchlistItems = listOf(item.copy(type = MediaType.MOVIE, ratings = pair)))).ratings)
    }

    @Test fun aSavedBaselineRemainsAvailableBeforeExternalScoresArrive() {
        val saved = WatchlistItem("123", MediaType.MOVIE, 123, title = "Movie", rating = 7.2, addedAt = 1)
        val result = AppleTvLibraryLayout.watchlist(saved, HomeUiState())
        assertEquals(7.2, result.rating)
        assertTrue(displayAppleRatings(result, RatingDisplayPrefs(), false).any { it.provider == "TMDB" })
    }
}
