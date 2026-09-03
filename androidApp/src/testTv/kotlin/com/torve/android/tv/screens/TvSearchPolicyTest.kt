package com.torve.android.tv.screens

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSearchPolicyTest {

    @Test
    fun stableKeyDoesNotChangeWhenMetadataIdsArrive() {
        val initial = item(
            id = "catalog:movie:eddington",
            tmdbId = null,
            imdbId = null,
        )
        val hydrated = initial.copy(tmdbId = 648878, imdbId = "tt11717042")

        assertEquals(initial.tvSearchStableKey(), hydrated.tvSearchStableKey())
    }

    @Test
    fun stableKeyCanonicalizesTmdbSourceIds() {
        assertEquals(
            item(id = "648878", tmdbId = null).tvSearchStableKey(),
            item(id = "tmdb:648878", tmdbId = 648878).tvSearchStableKey(),
        )
    }

    @Test
    fun stableProjectionRetainsPosterOrderWhileReplacingEnrichedData() {
        val first = item(id = "tmdb:1", tmdbId = 1, title = "First")
        val second = item(id = "tmdb:2", tmdbId = 2, title = "Second")
        val appended = item(id = "tmdb:3", tmdbId = 3, title = "Third")
        val enrichedFirst = first.copy(ratings = MediaRatings(imdbScore = 8.4f))

        val projected = stableTvSearchProjection(
            existing = listOf(second, first),
            available = listOf(enrichedFirst, second, appended),
            limit = 10,
        )

        assertEquals(listOf("Second", "First", "Third"), projected.map { it.title })
        assertEquals(8.4f, projected[1].ratings?.imdbScore)
    }

    @Test
    fun ratingSortUsesTheExplicitSelectedProvider() {
        val tmdbWinner = item(
            id = "tmdb:1",
            tmdbId = 1,
            title = "TMDB winner",
            rating = 9.4,
            ratings = MediaRatings(imdbScore = 6.1f, rottenTomatoesScore = 95),
        )
        val imdbWinner = item(
            id = "tmdb:2",
            tmdbId = 2,
            title = "IMDb winner",
            rating = 6.0,
            ratings = MediaRatings(imdbScore = 8.8f, rottenTomatoesScore = 70),
        )

        assertEquals(
            listOf("IMDb winner", "TMDB winner"),
            listOf(tmdbWinner, imdbWinner).sortedForTvSearch(TvSearchSort.IMDB_DESC).map { it.title },
        )
        assertEquals(
            listOf("TMDB winner", "IMDb winner"),
            listOf(tmdbWinner, imdbWinner).sortedForTvSearch(TvSearchSort.TMDB_DESC).map { it.title },
        )
        assertEquals(
            listOf("TMDB winner", "IMDb winner"),
            listOf(tmdbWinner, imdbWinner)
                .sortedForTvSearch(TvSearchSort.ROTTEN_TOMATOES_DESC)
                .map { it.title },
        )
    }

    @Test
    fun missingProviderScoresSortAfterKnownScoresInBothDirections() {
        val known = item(
            id = "tmdb:1",
            tmdbId = 1,
            title = "Known",
            ratings = MediaRatings(imdbScore = 7.2f),
        )
        val unknown = item(id = "tmdb:2", tmdbId = 2, title = "Unknown")

        assertEquals(
            listOf("Known", "Unknown"),
            listOf(unknown, known).sortedForTvSearch(TvSearchSort.IMDB_DESC).map { it.title },
        )
        assertEquals(
            listOf("Known", "Unknown"),
            listOf(unknown, known).sortedForTvSearch(TvSearchSort.IMDB_ASC).map { it.title },
        )
    }

    @Test
    fun primaryRatingEnrichmentRequiresBothImdbAndRottenTomatoes() {
        assertTrue(
            item(ratings = MediaRatings(tmdbScore = 7.5f))
                .needsTvSearchPrimaryRatingEnrichment(),
        )
        assertTrue(
            item(ratings = MediaRatings(imdbScore = 8.0f))
                .needsTvSearchPrimaryRatingEnrichment(),
        )
        assertFalse(
            item(ratings = MediaRatings(imdbScore = 8.0f, rottenTomatoesScore = 90))
                .needsTvSearchPrimaryRatingEnrichment(),
        )
        assertFalse(
            item(id = "catalog:unidentified", tmdbId = null, imdbId = null)
                .needsTvSearchPrimaryRatingEnrichment(),
        )
    }

    @Test
    fun duplicateMergeTreatsBlankArtworkAsMissing() {
        val merged = mergeTvSearchDuplicate(
            item(posterUrl = "", backdropUrl = " "),
            item(posterUrl = "poster", backdropUrl = "backdrop"),
        )

        assertEquals("poster", merged.posterUrl)
        assertEquals("backdrop", merged.backdropUrl)
    }

    @Test
    fun recentSearchesAreNewestFirstCaseInsensitiveAndBounded() {
        var recent = emptyList<String>()
        listOf("The Bear", "Severance", "the bear", "Slow Horses").forEach { query ->
            recent = addTvRecentSearch(recent, query, limit = 3)
        }

        assertEquals(listOf("Slow Horses", "the bear", "Severance"), recent)
    }

    @Test
    fun recentSearchSerializationPreservesOrderAndSpecialCharacters() {
        val searches = listOf("Two and a Half Men", "Star Trek: Picard", "Tom & Jerry")

        assertEquals(searches, decodeTvRecentSearches(encodeTvRecentSearches(searches)))
    }

    @Test
    fun malformedRecentSearchHistoryFailsClosed() {
        assertEquals(emptyList<String>(), decodeTvRecentSearches("not-json"))
    }

    @Test
    fun topRatedUsesAvailableScoreAndBoundedVoteFloor() {
        assertTrue(
            item(rating = 8.1, voteCount = 800)
                .matchesTvSearchMinRating(TV_SEARCH_TOP_RATED_FILTER, null),
        )
        assertTrue(
            item(ratings = MediaRatings(imdbScore = 8.3f, imdbVotes = 6_000))
                .matchesTvSearchMinRating(TV_SEARCH_TOP_RATED_FILTER, null),
        )
        assertFalse(
            item(rating = 6.9, voteCount = 50_000)
                .matchesTvSearchMinRating(TV_SEARCH_TOP_RATED_FILTER, null),
        )
        assertFalse(
            item(rating = 9.0, voteCount = 100)
                .matchesTvSearchMinRating(TV_SEARCH_TOP_RATED_FILTER, null),
        )
    }

    @Test
    fun aiDiscoveryRequestKeepsPageAndPushesUiFiltersToTmdb() {
        val plan = TvAiSearchPagingPlan(
            query = "smart space dramas",
            title = "Smart Space Dramas",
            mediaType = "movie",
            sortBy = "vote_average.desc",
            genreIds = setOf(878),
            keywordIds = setOf(123, 456),
            yearFrom = 1990,
            yearTo = 2020,
            minRating = 7.0f,
            personId = 42,
            isDirector = false,
        )

        val request = plan.pageRequest(
            page = 3,
            filterType = "tv",
            selectedGenreIds = setOf(18),
            selectedStudioIds = setOf(213, 49),
            selectedYearFrom = 2000,
            selectedYearTo = 2009,
            runtimeGte = 90,
            runtimeLte = 120,
            originalLanguage = "de",
            withWatchProviders = "8|9",
            regionCode = "DE",
        )

        assertEquals(3, request.page)
        assertEquals("tv", request.type)
        assertEquals("18,878", request.withGenres)
        assertEquals("123|456", request.withKeywords)
        assertEquals("49|213", request.withCompanies)
        assertEquals(2000, request.yearFrom)
        assertEquals(2009, request.yearTo)
        assertEquals(90, request.runtimeGte)
        assertEquals(120, request.runtimeLte)
        assertEquals("de", request.originalLanguage)
        assertEquals("8|9", request.withWatchProviders)
        assertEquals("DE", request.watchRegion)
        assertEquals("42", request.withCast)
        assertEquals(null, request.withCrew)
        assertFalse(request.hasEmptyYearRange)
    }

    @Test
    fun aiDiscoveryRequestRejectsUiYearOutsideInterpretedRange() {
        val plan = TvAiSearchPagingPlan(
            query = "recent thrillers",
            title = "Recent Thrillers",
            mediaType = "movie",
            sortBy = "popularity.desc",
            genreIds = emptySet(),
            keywordIds = emptySet(),
            yearFrom = 2020,
            yearTo = null,
            minRating = null,
            personId = 99,
            isDirector = true,
        )

        val request = plan.pageRequest(
            page = 0,
            filterType = null,
            selectedGenreIds = emptySet(),
            selectedStudioIds = emptySet(),
            selectedYearFrom = 1990,
            selectedYearTo = 1999,
            runtimeGte = null,
            runtimeLte = null,
            originalLanguage = null,
            withWatchProviders = null,
            regionCode = "US",
        )

        assertEquals(1, request.page)
        assertEquals("99", request.withCrew)
        assertEquals(null, request.withCast)
        assertTrue(request.hasEmptyYearRange)
    }

    private fun item(
        id: String = "tmdb:1",
        tmdbId: Int? = 1,
        imdbId: String? = null,
        title: String = "Movie",
        posterUrl: String? = null,
        backdropUrl: String? = null,
        rating: Double? = null,
        voteCount: Int? = null,
        ratings: MediaRatings? = null,
    ) = MediaItem(
        id = id,
        tmdbId = tmdbId,
        imdbId = imdbId,
        type = MediaType.MOVIE,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = rating,
        voteCount = voteCount,
        ratings = ratings,
    )
}
