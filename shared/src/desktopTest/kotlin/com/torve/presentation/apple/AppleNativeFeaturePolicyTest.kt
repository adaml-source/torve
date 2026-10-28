package com.torve.presentation.apple

import com.torve.data.addon.ParsedStream
import com.torve.domain.model.*
import com.torve.presentation.settings.ThemeMode
import org.koin.core.Koin
import kotlin.test.*

class AppleNativeFeaturePolicyTest {
    private val features = AppleNativeFeatures(Koin())
    private fun stream(title: String, quality: String, size: String? = null, cached: Boolean = false) =
        ParsedStream("Provider", quality, title, size = size, isCached = cached)

    @Test fun signedOutAppearanceKeepsLocalChoiceAndSavedAccountSystemWins() {
        assertEquals(ThemeMode.LIGHT, resolveAppleAppearance(null, ThemeMode.LIGHT))
        assertEquals(ThemeMode.DARK, resolveAppleAppearance(null, ThemeMode.DARK))
        assertEquals(ThemeMode.SYSTEM, resolveAppleAppearance("SYSTEM", ThemeMode.LIGHT))
        assertEquals(ThemeMode.LIGHT, resolveAppleAppearance("LIGHT", ThemeMode.DARK))
    }

    @Test fun sizeSortingSpansCachedGroupsAndLeavesUnknownSizesLast() {
        val small = stream("Small cached", "1080p", "2 GB", true)
        val large = stream("Large", "4K", "12 GB")
        val unknown = stream("Unknown", "4K", null, true)
        assertEquals(listOf(large, small, unknown), features.sortedSources(listOf(unknown, small, large), "SIZE_DESC", "", false))
        assertEquals(listOf(small, large, unknown), features.sortedSources(listOf(large, unknown, small), "SIZE_ASC", "", false))
        assertEquals(listOf(unknown, small), features.sortedSources(listOf(unknown, small, large), "DEFAULT", "", true))
    }
    @Test fun qualityFiltersUnderstandEquivalentProviderLabels() {
        val items = listOf(stream("A", "2160p"), stream("B", "UHD"), stream("C", "4k"), stream("D", "1080p"))
        assertEquals(items.take(3), features.sortedSources(items, "DEFAULT", "4K", false))
        assertEquals(listOf(items.last()), features.sortedSources(items, "DEFAULT", "FHD", false))
    }
    @Test fun unavailableRatingDoesNotConsumeTheCardLimit() {
        val item = MediaItem(id = "1", type = MediaType.MOVIE, title = "Title", ratings = MediaRatings(imdbScore = 8.1f, rtAudienceScore = 92))
        val prefs = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.TMDB, RatingSource.IMDB, RatingSource.RT_AUDIENCE),
            providerOrder = listOf(RatingSource.TMDB, RatingSource.IMDB, RatingSource.RT_AUDIENCE), maxRatingsOnCard = 1)
        assertEquals(listOf("IMDB"), displayAppleRatings(item, prefs, false).map { it.provider })
        assertEquals(listOf("8.1"), displayAppleRatings(item, prefs, false).map { it.value })
    }
    @Test fun cardAndDetailVisibilityUseTheirOwnPreferences() {
        val item = MediaItem(id = "1", type = MediaType.MOVIE, title = "Title", rating = 7.8,
            ratings = MediaRatings(imdbScore = 8.1f, rtAudienceScore = 92))
        val prefs = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.RT_AUDIENCE, RatingSource.IMDB),
            providerOrder = listOf(RatingSource.RT_AUDIENCE, RatingSource.IMDB), maxRatingsOnCard = 1,
            showTorveScoreOnDetailPage = false)
        assertEquals(listOf("8.1"), displayAppleRatings(item, prefs, false).map { it.value })
        assertEquals(listOf("RT_AUDIENCE", "IMDB"), displayAppleRatings(item, prefs, true).map { it.provider })
        assertTrue(displayAppleRatings(item, prefs.copy(showRatingsOnDetailPage = false), true).isEmpty())
        assertTrue(displayAppleRatings(item, prefs.copy(maxRatingsOnCard = -1), false).isEmpty())
    }
    @Test fun cardsUseTmdbOnlyAsFallbackWhileDetailKeepsSavedOrdering() {
        val item = MediaItem(id = "1", type = MediaType.MOVIE, title = "Title", rating = 9.0,
            ratings = MediaRatings(imdbScore = 7.6f, imdbVotes = 1000, tmdbScore = 9.0f))
        val prefs = RatingDisplayPrefs()
        assertEquals(listOf("IMDB"), displayAppleRatings(item, prefs, false).map { it.provider })
        assertEquals(listOf("7.6"), displayAppleRatings(item, prefs, false).map { it.value })
        assertEquals("IMDB", displayAppleRatings(item, prefs, true).first().provider)
        assertEquals(listOf("IMDB"), displayAppleRatings(item,
            prefs.copy(providerOrder = listOf(RatingSource.TMDB, RatingSource.IMDB)), false).map { it.provider })
        assertEquals(listOf("TMDB", "IMDB"), displayAppleRatings(item,
            prefs.copy(providerOrder = listOf(RatingSource.TMDB, RatingSource.IMDB), showTorveScoreOnDetailPage = false), true).map { it.provider })
        val tmdbOnly = item.copy(ratings = MediaRatings(tmdbScore = 9.0f))
        assertEquals(listOf("TMDB"), displayAppleRatings(tmdbOnly, prefs, false).map { it.provider })
    }
    @Test fun compactCardsPrioritizeImdbAndRottenTomatoesBeforeOtherProviders() {
        val item = MediaItem(id = "1", type = MediaType.MOVIE, title = "Title", rating = 7.0,
            ratings = MediaRatings(imdbScore = 8.0f, rottenTomatoesScore = 95, rtAudienceScore = 90))
        val prefs = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.TMDB, RatingSource.RT_AUDIENCE, RatingSource.ROTTEN_TOMATOES, RatingSource.IMDB),
            providerOrder = listOf(RatingSource.TMDB, RatingSource.RT_AUDIENCE, RatingSource.ROTTEN_TOMATOES, RatingSource.IMDB), maxRatingsOnCard = 2)
        assertEquals(listOf("IMDB", "ROTTEN_TOMATOES"), displayAppleRatings(item, prefs, false).map { it.provider })
        assertEquals(listOf("IMDB"), displayAppleRatings(item, prefs.copy(maxRatingsOnCard = 1), false).map { it.provider })
        assertEquals(listOf("ROTTEN_TOMATOES"), displayAppleRatings(item.copy(ratings = MediaRatings(rottenTomatoesScore = 95)), prefs, false).map { it.provider })
    }
}
