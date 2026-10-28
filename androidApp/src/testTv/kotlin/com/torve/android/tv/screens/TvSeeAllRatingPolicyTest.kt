package com.torve.android.tv.screens

import com.torve.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TvSeeAllRatingPolicyTest {
    @Test fun imdbOrderingNeverSubstitutesTmdbScores() {
        val tmdbOnly = MediaItem("a", title = "A", type = MediaType.MOVIE, rating = 9.9)
        val imdb = MediaItem("b", title = "B", type = MediaType.MOVIE, rating = 6.0, ratings = MediaRatings(imdbScore = 8.0f))
        assertEquals(listOf("b", "a"), sortSeeAllItems(listOf(tmdbOnly, imdb), TvSeeAllSortKey.RATING_DESC).map { it.id })
        assertEquals(listOf(imdb), listOf(tmdbOnly, imdb).filterSeeAllItems(emptySet(), emptySet(), emptyList(), 7.0))
    }
}
