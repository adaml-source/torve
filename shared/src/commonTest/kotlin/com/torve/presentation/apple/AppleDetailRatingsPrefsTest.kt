package com.torve.presentation.apple

import com.torve.domain.model.*
import com.torve.presentation.settings.SettingsUiState
import kotlin.test.*

class AppleDetailRatingsPrefsTest {
    private val item = MediaItem(id = "42", tmdbId = 42, type = MediaType.MOVIE, title = "New release", rating = 9.1,
        ratings = MediaRatings(imdbScore = 9.3f, imdbVotes = 17097, tmdbScore = 9.1f, rottenTomatoesScore = 98))

    @Test fun detailUsesRatingsSettingsWhileCardsRetainTheirPreset() {
        val selected = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES),
            showTorveScoreOnDetailPage = false)
        val preset = CardStyle(ratingPrefs = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.TMDB)))
        val state = SettingsUiState(ratingPrefs = selected,
            cardStylePresets = listOf(CardStylePreset("preset", "Card preset", preset)), globalDefaultPresetId = "preset")
        assertEquals(listOf("IMDB", "ROTTEN_TOMATOES"), displayAppleRatings(item, appleRatingPreferences(state, true), true).map { it.provider })
        assertEquals(listOf("TMDB"), displayAppleRatings(item, appleRatingPreferences(state, false), false).map { it.provider })
    }

    @Test fun disabledTorveProviderStaysHiddenEvenWithDetailScoreSwitchOn() {
        val prefs = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES),
            showTorveScoreOnDetailPage = true)
        assertEquals(listOf("IMDB", "ROTTEN_TOMATOES"), displayAppleRatings(item, prefs, true).map { it.provider })
        assertTrue(displayAppleRatings(item, prefs.copy(enabledProviders = prefs.enabledProviders + RatingSource.TORVE), true).any { it.provider == "TORVE" })
        assertFalse(displayAppleRatings(item, prefs.copy(enabledProviders = prefs.enabledProviders + RatingSource.TORVE,
            showTorveScoreOnDetailPage = false), true).any { it.provider == "TORVE" })
    }

    @Test fun selectingNoProvidersDoesNotInventATmdbDetailRating() {
        assertTrue(displayAppleRatings(item, RatingDisplayPrefs(enabledProviders = emptyList()), true).isEmpty())
    }

    @Test fun detailFollowsSavedProviderOrderAndMasterVisibility() {
        val prefs = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES),
            providerOrder = listOf(RatingSource.ROTTEN_TOMATOES, RatingSource.IMDB), showTorveScoreOnDetailPage = false)
        assertEquals(listOf("ROTTEN_TOMATOES", "IMDB"), displayAppleRatings(item, prefs, true).map { it.provider })
        assertTrue(displayAppleRatings(item, prefs.copy(showRatingsOnDetailPage = false), true).isEmpty())
    }
}
