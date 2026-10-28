package com.torve.android.tv.screens

import com.torve.android.tv.components.cacheTvBrowsePreviewEnrichedItem
import com.torve.android.tv.components.withCachedTvBrowsePreviewEnrichment
import com.torve.android.tv.components.tvSavedArtwork
import com.torve.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TvExtensionSearchModelTest {
    @Test fun sparseOutcomeCardsReuseSavedLibraryArtWithoutAProviderOrChangingIdentity() {
        val sparse = MediaItem("calendar:94997", tmdbId = 94997, type = MediaType.SERIES, title = "House of the Dragon - Episode")
        val saved = WatchlistItem("94997", MediaType.SERIES, 94997, title = "House of the Dragon",
            posterUrl = "https://example.invalid/poster.jpg", backdropUrl = "https://example.invalid/backdrop.jpg", addedAt = 0)
        val resolved = tvSavedArtwork(sparse, listOf(saved), emptyList())
        assertEquals(sparse.id, resolved.id); assertEquals(sparse.title, resolved.title)
        assertEquals(saved.posterUrl, resolved.posterUrl); assertEquals(saved.backdropUrl, resolved.backdropUrl)
        assertNull(tvSavedArtwork(sparse.copy(type = MediaType.MOVIE), listOf(saved), emptyList()).posterUrl)
    }
    @Test fun movieAndShowSearchesAreAggregatedWithoutDuplicateSelectorsOrAiByDefault() {
        val movie = AddonCatalog("movie", "search-movie", "Search", listOf(AddonExtra("search")))
        val series = AddonCatalog("series", "search-series", "Search", listOf(AddonExtra("search")))
        val ai = AddonCatalog("movie", "ai-movie-search", "AI Movie Search", listOf(AddonExtra("search")))
        val addon = InstalledAddon("https://example.invalid/manifest.json", AddonManifest("test", "Test", "1", catalogs = listOf(movie, series, ai, movie)))
        val sources = tvExtensionSearchSources(listOf(addon))
        assertEquals(3, sources.size)
        assertEquals(setOf("movie", "series"), sources.filterNot { it.isAi }.map { it.catalog.type }.toSet())
        assertEquals(1, sources.count { it.isAi })
    }
    @Test fun minimumRatingAndSortUseImdbRatherThanTmdb() {
        val tmdbOnly = MediaItem("a", type = MediaType.MOVIE, title = "TMDB", rating = 9.9)
        val low = tmdbOnly.copy(id = "b", title = "Low IMDb", ratings = MediaRatings(imdbScore = 6f, tmdbScore = 10f))
        val high = tmdbOnly.copy(id = "c", title = "High IMDb", ratings = MediaRatings(imdbScore = 8f))
        assertEquals(listOf(high), filterTvExtensionSearch(listOf(tmdbOnly, low, high), TvExtensionSearchFilters(minImdb = 7f)))
        assertEquals(listOf(high, low, tmdbOnly), filterTvExtensionSearch(listOf(tmdbOnly, low, high), TvExtensionSearchFilters(sort = "imdb")))
    }
    @Test fun genreYearsTypeAndRuntimeApplyTogetherAndNoticesNeverBecomeResults() {
        val expected = MediaItem("ok", type = MediaType.SERIES, title = "Show", year = 2023, runtime = 45, genres = listOf(Genre(0, "Drama")))
        val filters = TvExtensionSearchFilters(type = "tv", genre = "Drama", fromYear = 2020, throughYear = 2024, runtime = "short")
        assertEquals(listOf(expected), filterTvExtensionSearch(listOf(expected, expected.copy(id = "movie", type = MediaType.MOVIE),
            expected.copy(id = "old", year = 2019), expected.copy(id = "notice", catalogueNotice = "Invalid model")), filters))
        val combinedGenre = expected.copy(genres = listOf(Genre(0, "Action & Adventure")))
        assertEquals(listOf(combinedGenre), filterTvExtensionSearch(listOf(combinedGenre), TvExtensionSearchFilters(genre = "Action")))
    }
    @Test fun imdbAndTmdbAliasesShareArtworkWithoutChangingSavedEpisodeIdentity() {
        val detail = MediaItem("tmdb:9876501", tmdbId = 9876501, imdbId = "tt9876501", type = MediaType.SERIES,
            title = "Show", posterUrl = "https://example.invalid/poster.jpg", backdropUrl = "https://example.invalid/backdrop.jpg")
        cacheTvBrowsePreviewEnrichedItem(detail)
        val episode = MediaItem("trakt-calendar:9876501:2:3:2099-01-01", tmdbId = 9876501, type = MediaType.SERIES, title = "Show - S2E3 - Episode")
        val resolved = withCachedTvBrowsePreviewEnrichment(episode)
        assertEquals(episode.id, resolved.id); assertEquals(episode.title, resolved.title)
        assertEquals(detail.posterUrl, resolved.posterUrl)
        assertEquals(detail.posterUrl, withCachedTvBrowsePreviewEnrichment(detail.copy(id = "tt9876501", tmdbId = null, posterUrl = null)).posterUrl)
    }
    @Test fun lateRottenTomatoesScoresMergeWithExistingImdbScores() {
        val item = MediaItem("tt9876502", type = MediaType.MOVIE, title = "Merge fixture", ratings = MediaRatings(imdbScore = 8f))
        cacheTvBrowsePreviewEnrichedItem(item)
        cacheTvBrowsePreviewEnrichedItem(item.copy(ratings = MediaRatings(rottenTomatoesScore = 91)))
        val resolved = withCachedTvBrowsePreviewEnrichment(item)
        assertEquals(8f, resolved.ratings!!.imdbScore)
        assertEquals(91, resolved.ratings!!.rottenTomatoesScore)
    }
}
