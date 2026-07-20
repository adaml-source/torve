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
    fun duplicateMergeTreatsBlankArtworkAsMissing() {
        val merged = mergeTvSearchDuplicate(
            item(posterUrl = "", backdropUrl = " "),
            item(posterUrl = "poster", backdropUrl = "backdrop"),
        )

        assertEquals("poster", merged.posterUrl)
        assertEquals("backdrop", merged.backdropUrl)
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

    private fun item(
        posterUrl: String? = null,
        backdropUrl: String? = null,
        rating: Double? = null,
        voteCount: Int? = null,
        ratings: MediaRatings? = null,
    ) = MediaItem(
        id = "tmdb:1",
        tmdbId = 1,
        type = MediaType.MOVIE,
        title = "Movie",
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = rating,
        voteCount = voteCount,
        ratings = ratings,
    )
}
