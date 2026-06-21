package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RatingEnrichmentPredicateTest {
    @Test
    fun tmdbFallbackDoesNotSatisfyRichExternalRatingCompleteness() {
        val item = mediaItem(
            rating = 7.4,
            ratings = null.withFallbackTmdbScore(7.4),
        )

        assertFalse(item.ratings.hasRichExternalRating())
        assertTrue(item.needsExternalRatingEnrichment())
    }

    @Test
    fun tmdbScoreAloneDoesNotSatisfyRichExternalRatingCompleteness() {
        val item = mediaItem(ratings = MediaRatings(tmdbScore = 7.4f))

        assertFalse(item.ratings.hasRichExternalRating())
        assertTrue(item.needsExternalRatingEnrichment())
    }

    @Test
    fun imdbRatingSatisfiesRichExternalRatingCompleteness() {
        val item = mediaItem(ratings = MediaRatings(imdbScore = 8.1f, tmdbScore = 7.4f))

        assertTrue(item.ratings.hasRichExternalRating())
        assertFalse(item.needsExternalRatingEnrichment())
    }

    @Test
    fun rottenTomatoesCriticsSatisfiesRichExternalRatingCompleteness() {
        val item = mediaItem(ratings = MediaRatings(rottenTomatoesScore = 91, tmdbScore = 7.4f))

        assertTrue(item.ratings.hasRichExternalRating())
        assertFalse(item.needsExternalRatingEnrichment())
    }

    @Test
    fun rottenTomatoesAudienceSatisfiesRichExternalRatingCompleteness() {
        val item = mediaItem(ratings = MediaRatings(rtAudienceScore = 86, tmdbScore = 7.4f))

        assertTrue(item.ratings.hasRichExternalRating())
        assertFalse(item.needsExternalRatingEnrichment())
    }

    @Test
    fun traktAndMdblistSatisfyRichExternalRatingCompleteness() {
        assertTrue(MediaRatings(traktScore = 82f).hasRichExternalRating())
        assertTrue(MediaRatings(mdblistScore = 78f).hasRichExternalRating())
    }

    @Test
    fun itemWithoutLookupIdentityDoesNotNeedNetworkEnrichment() {
        val item = mediaItem(
            id = "playlist-only-row",
            tmdbId = null,
            imdbId = null,
            rating = 7.0,
            ratings = MediaRatings(tmdbScore = 7.0f),
        )

        assertFalse(item.hasExternalRatingLookupIdentity())
        assertFalse(item.needsExternalRatingEnrichment())
    }

    @Test
    fun ratingMergeUsesTmdbIdBeforeFallbackKeys() {
        val item = mediaItem(
            id = "local:unmatched",
            tmdbId = 12345,
            ratings = MediaRatings(tmdbScore = 6.4f),
        )
        val merged = item.withEnrichedRatingsFrom(
            mapOf(
                "tmdb:MOVIE:12345" to MediaRatings(imdbScore = 8.2f, rottenTomatoesScore = 91),
                "title:MOVIE:example:2020" to MediaRatings(imdbScore = 7.1f),
            ),
        )

        assertTrue(merged.ratings?.imdbScore == 8.2f)
        assertTrue(merged.ratings?.rottenTomatoesScore == 91)
        assertTrue(merged.ratings?.tmdbScore == 6.4f)
    }

    @Test
    fun ratingMergeUsesImdbIdWhenTmdbIdIsMissing() {
        val item = mediaItem(id = "provider:row", tmdbId = null, imdbId = "tt1234567")
        val merged = item.withEnrichedRatingsFrom(
            mapOf("imdb:MOVIE:tt1234567" to MediaRatings(imdbScore = 8.4f)),
        )

        assertTrue(merged.ratings?.imdbScore == 8.4f)
    }

    @Test
    fun ratingMergeUsesTitleYearTypeOnlyWhenIdsAreMissing() {
        val item = mediaItem(id = "provider:row", tmdbId = null, title = "Same Title", year = 2020)
        val merged = item.withEnrichedRatingsFrom(
            mapOf("title:MOVIE:sametitle:2020" to MediaRatings(imdbScore = 8.0f)),
        )

        assertTrue(merged.ratings?.imdbScore == 8.0f)
    }

    @Test
    fun ratingMergeDoesNotCrossMergeSameTitleDifferentYear() {
        val item = mediaItem(id = "provider:row", tmdbId = null, title = "Same Title", year = 2021)
        val merged = item.withEnrichedRatingsFrom(
            mapOf("title:MOVIE:sametitle:2020" to MediaRatings(imdbScore = 8.0f)),
        )

        assertFalse(merged.ratings.hasRichExternalRating())
    }

    @Test
    fun ratingMergeDoesNotOverwriteExistingRichRatingsWithFallbackOnlyRatings() {
        val item = mediaItem(
            ratings = MediaRatings(imdbScore = 8.7f, rottenTomatoesScore = 93),
        )
        val merged = item.withEnrichedRatingsFrom(
            mapOf("tmdb:MOVIE:12345" to MediaRatings(tmdbScore = 6.0f)),
        )

        assertTrue(merged.ratings?.imdbScore == 8.7f)
        assertTrue(merged.ratings?.rottenTomatoesScore == 93)
        assertTrue(merged.ratings?.tmdbScore == 6.0f)
    }

    private fun mediaItem(
        id: String = "tmdb:12345",
        tmdbId: Int? = 12345,
        imdbId: String? = null,
        title: String = "Example",
        year: Int? = null,
        rating: Double? = null,
        ratings: MediaRatings? = null,
    ): MediaItem = MediaItem(
        id = id,
        tmdbId = tmdbId,
        imdbId = imdbId,
        type = MediaType.MOVIE,
        title = title,
        year = year,
        rating = rating,
        ratings = ratings,
    )
}
