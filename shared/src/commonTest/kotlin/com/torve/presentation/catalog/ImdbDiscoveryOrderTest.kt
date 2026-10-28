package com.torve.presentation.catalog

import com.torve.domain.discovery.DiscoveryRatingSource
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import kotlin.test.*

class ImdbDiscoveryOrderTest {
    private fun item(id: String, imdb: Float?, tmdb: Double = 9.9, votes: Int = 100) = MediaItem(
        id = id, title = id, type = MediaType.MOVIE, rating = tmdb,
        ratings = MediaRatings(imdbScore = imdb, imdbVotes = votes),
    )
    @Test fun imdbIsTheDefaultIncludingAfterReset() {
        assertEquals(SortOption.IMDB_SCORE_DESC, CatalogFilter().sortBy)
        assertEquals(DiscoveryRatingSource.IMDB, CatalogFilter().ratingSource)
        assertFalse(CatalogFilter().isActive)
        assertEquals(0, CatalogFilter().activeCount)
    }
    @Test fun actualImdbScoresWinOverTmdbAndUnknownsAreLast() {
        val result = CatalogFilter().applyRatingOrder(listOf(item("unknown", null), item("lower", 6f), item("highest", 9f, 4.0)))
        assertEquals(listOf("highest", "lower", "unknown"), result.map { it.id })
    }
    @Test fun minimumImdbDoesNotUseTmdbFallback() {
        val result = CatalogFilter(minRating = 7.5f).applyRatingOrder(listOf(item("unknown", null), item("lower", 7f), item("pass", 7.5f)))
        assertEquals(listOf("pass"), result.map { it.id })
    }
    @Test fun newPageIsSortedAcrossExistingPages() {
        assertEquals(listOf("new", "first", "second"), (listOf(item("first", 8f), item("second", 7f)) + item("new", 9f))
            .highestImdbFirst().map { it.id })
    }
    @Test fun explicitAlternativeSortStillUsesSelectedMinimumSource() {
        val values = listOf(item("first", 9f, 4.0), item("second", 6f, 8.0))
        assertEquals(listOf("second"), CatalogFilter(minRating = 7f, ratingSource = DiscoveryRatingSource.TMDB,
            sortBy = SortOption.POPULARITY_DESC).applyRatingOrder(values).map { it.id })
    }
}
