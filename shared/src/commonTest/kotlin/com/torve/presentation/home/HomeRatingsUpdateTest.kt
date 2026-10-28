package com.torve.presentation.home

import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HomeRatingsUpdateTest {
    private fun movie(id: String) = MediaItem(id, type = MediaType.MOVIE, title = "Movie $id", posterUrl = "old-$id.jpg")
    private fun rated(item: MediaItem) = item.copy(ratings = MediaRatings(imdbScore = 8f))

    @Test fun delayedResponsePreservesNewTrendingOrderArtworkAndMembership() {
        val before = listOf(movie("1"), movie("2"), movie("3"))
        val current = listOf(before[2].copy(title = "Localized", posterUrl = "new.jpg"), movie("4"), before[0])
        val merged = HomeRatingsUpdate(before, before.map(::rated)).apply(current)
        assertEquals(listOf("3", "4", "1"), merged.map { it.id })
        assertEquals("Localized", merged[0].title)
        assertEquals("new.jpg", merged[0].posterUrl)
        assertEquals(8f, merged[0].ratings?.imdbScore)
        assertSame(current[1], merged[1])
    }

    @Test fun clearedRailCannotBeRepopulatedByOldResponse() {
        val before = listOf(movie("1"))
        val empty = emptyList<MediaItem>()
        assertSame(empty, HomeRatingsUpdate(before, before.map(::rated)).apply(empty))
    }

    @Test fun identicalResponseDoesNotReplaceTheVisibleListOrShelf() {
        val item = rated(movie("1"))
        val shelf = CatalogShelf(id = "trending-movies", title = "Trending", items = listOf(item))
        val update = HomeRatingsUpdate(listOf(item), listOf(item))
        assertSame(shelf, update.apply(shelf))
        assertSame(shelf.items, update.apply(shelf.items))
    }

    @Test fun movieAndShowWithTheSameNumericIdStayIndependent() {
        val movie = movie("7")
        val show = movie.copy(type = MediaType.SERIES, title = "Show")
        val merged = HomeRatingsUpdate(listOf(movie), listOf(rated(movie))).apply(listOf(show, movie))
        assertSame(show, merged[0])
        assertEquals(8f, merged[1].ratings?.imdbScore)
    }

    @Test fun resolvedTmdbIdentityDoesNotChangeTheCardIdOrPoster() {
        val item = movie("tmdb:7")
        val result = HomeRatingsUpdate(listOf(item), listOf(rated(item).copy(tmdbId = 7, imdbId = "tt0000007"))).apply(item)
        assertEquals(item.id, result.id)
        assertEquals(item.posterUrl, result.posterUrl)
        assertEquals(7, result.tmdbId)
        assertEquals("tt0000007", result.imdbId)
    }

    @Test fun olderResponseCannotOverwriteNewerRatingsButCanFillMissingFields() {
        val item = movie("7")
        val current = item.copy(ratings = MediaRatings(imdbScore = 9f))
        val oldResponse = item.copy(ratings = MediaRatings(imdbScore = 8f, rottenTomatoesScore = 95))
        val result = HomeRatingsUpdate(listOf(item), listOf(oldResponse)).apply(current)
        assertEquals(9f, result.ratings?.imdbScore)
        assertEquals(95, result.ratings?.rottenTomatoesScore)
    }

    @Test fun failedProviderCannotClearExistingRatings() {
        val item = rated(movie("7"))
        assertSame(item, HomeRatingsUpdate(listOf(item), listOf(item.copy(ratings = null))).apply(item))
    }

    @Test fun reusedProviderIdCannotEnrichAnUnrelatedTitle() {
        val item = movie("provider:7").copy(tmdbId = 7)
        val replacement = item.copy(tmdbId = 8, title = "Other movie")
        assertSame(replacement, HomeRatingsUpdate(listOf(item), listOf(rated(item))).apply(replacement))
    }
}
