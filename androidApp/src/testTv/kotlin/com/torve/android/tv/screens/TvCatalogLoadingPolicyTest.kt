package com.torve.android.tv.screens

import com.torve.android.tv.components.TvContentRail
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvCatalogLoadingPolicyTest {

    private val externalCardPrefs = RatingDisplayPrefs(
        enabledProviders = listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES),
    )

    @Test
    fun regularRailsUseSmallInitialRequestBudget() {
        assertEquals(2, catalogRailPageLimit("trending_movie"))
        assertEquals(2, catalogRailPageLimit("genre_tv_18"))
        assertEquals(40, catalogRailCandidateLimit("popular_movie"))
    }

    @Test
    fun topRatedRailRetainsLargerVerificationPool() {
        assertEquals(3, catalogRailPageLimit("top_rated_movie"))
        assertEquals(48, catalogRailCandidateLimit("top_rated_tv"))
    }

    @Test
    fun refreshRequestsOnlyMissingRailsInDisplayOrder() {
        val missing = missingCatalogRailKeys(
            expectedKeys = listOf(
                "trending_movie",
                "popular_movie",
                "top_rated_movie",
                "genre_movie_28",
            ),
            cachedKeys = listOf(
                "popular_movie",
                "unrelated",
                "popular_movie",
            ),
        )

        assertEquals(
            linkedSetOf("trending_movie", "top_rated_movie", "genre_movie_28"),
            missing,
        )
    }

    @Test
    fun persistedAndLocalRailsShareTheRefreshBudget() {
        val persisted = listOf("trending_movie", "popular_movie")
        val local = listOf("genre_movie_28", "genre_movie_35")

        val missing = missingCatalogRailKeys(
            expectedKeys = listOf(
                "trending_movie",
                "popular_movie",
                "top_rated_movie",
                "genre_movie_28",
                "genre_movie_35",
            ),
            cachedKeys = persisted + local,
        )

        assertEquals(linkedSetOf("top_rated_movie"), missing)
    }

    @Test
    fun initialRatingEnrichmentIsBoundedAndDeduplicated() {
        val duplicate = movie(id = "movie-1", tmdbId = 1)
        val rails = listOf(
            TvContentRail(
                key = "trending_movie",
                title = "Trending",
                items = listOf(duplicate, movie("movie-2", 2), movie("movie-3", 3)),
            ),
            TvContentRail(
                key = "popular_movie",
                title = "Popular",
                items = listOf(duplicate, movie("movie-4", 4)),
            ),
        )

        val candidates = initialCatalogRatingCandidates(rails, externalCardPrefs, limit = 3)

        assertEquals(listOf("movie-1", "movie-2", "movie-4"), candidates.map { it.id })
    }

    @Test
    fun traktOnlyRatingStillNeedsAnImdbOrRtCardValue() {
        val traktOnly = movie(id = "trakt-only", tmdbId = 9).copy(
            ratings = MediaRatings(traktScore = 84f),
        )

        val candidates = initialCatalogRatingCandidates(
            rails = listOf(TvContentRail("trending_movie", "Trending", listOf(traktOnly))),
            prefs = externalCardPrefs,
        )

        assertEquals(listOf("trakt-only"), candidates.map { it.id })
    }

    @Test
    fun enrichedRatingIsMergedIntoEveryDuplicatePoster() {
        val original = movie(id = "catalog-copy", tmdbId = 77)
        val rails = listOf(
            TvContentRail("trending_movie", "Trending", listOf(original)),
            TvContentRail("popular_movie", "Popular", listOf(original.copy(id = "other-copy"))),
        )
        val enriched = original.copy(
            ratings = MediaRatings(imdbScore = 8.7f, imdbVotes = 120_000),
        )

        val merged = mergeCatalogRatingItems(rails, listOf(enriched))

        assertEquals(8.7f, merged[0].items[0].ratings?.imdbScore)
        assertEquals(8.7f, merged[1].items[0].ratings?.imdbScore)
        assertNull(original.ratings)
    }

    private fun movie(id: String, tmdbId: Int): MediaItem = MediaItem(
        id = id,
        tmdbId = tmdbId,
        type = MediaType.MOVIE,
        title = id,
    )
}
