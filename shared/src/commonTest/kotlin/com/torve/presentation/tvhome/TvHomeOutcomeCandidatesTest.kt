package com.torve.presentation.tvhome

import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.presentation.home.HomeUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class TvHomeOutcomeCandidatesTest {
    private fun progress(id: String = "94997") = WatchProgress(id, MediaType.SERIES, "Episode", showTitle = "House of the Dragon")
    private fun detail(id: Int = 94997, type: MediaType = MediaType.SERIES) =
        MediaItem(id.toString(), tmdbId = id, type = type, title = "House of the Dragon", posterUrl = "https://example.test/poster.jpg", backdropUrl = "https://example.test/backdrop.jpg")

    @Test fun richerWatchlistEntryFillsArtworkEvenWhenProgressHasTheSameLocalId() {
        val result = tvHomeOutcomeCandidates(HomeUiState(continueWatching = listOf(progress()), watchlistItems = listOf(detail())))
        assertEquals(1, result.size)
        assertEquals("94997", result.single().id)
        assertEquals(detail().posterUrl, result.single().posterUrl)
        assertEquals(detail().backdropUrl, result.single().backdropUrl)
        assertEquals("House of the Dragon", result.single().title)
    }

    @Test fun prefixedProgressIdsAreRecognizedAndMergedByMediaTypeAndTmdbId() {
        val result = tvHomeOutcomeCandidates(HomeUiState(continueWatching = listOf(progress("series:94997")), recentlyWatched = listOf(detail())))
        assertEquals(1, result.size)
        assertEquals(94997, result.single().tmdbId)
        assertEquals("series:94997", result.single().id)
        assertEquals(detail().posterUrl, result.single().posterUrl)
    }

    @Test fun loadedCatalogueRepairsPersonalArtworkWithoutAddingUnrelatedCandidates() {
        val result = tvHomeOutcomeCandidates(HomeUiState(
            continueWatching = listOf(progress()),
            shelves = listOf(CatalogShelf("shows", "Shows", listOf(detail(), detail(123)))),
        ))
        assertEquals(listOf(94997), result.map { it.tmdbId })
        assertEquals(detail().posterUrl, result.single().posterUrl)
    }

    @Test fun movieAndSeriesWithTheSameNumberNeverShareArtwork() {
        val movie = detail(type = MediaType.MOVIE).copy(posterUrl = "movie-poster.jpg")
        val result = tvHomeOutcomeCandidates(HomeUiState(continueWatching = listOf(progress()), watchlistItems = listOf(movie)))
        assertEquals(listOf(MediaType.SERIES, MediaType.MOVIE), result.map { it.type })
        assertEquals(null, result.first().posterUrl)
        assertEquals("movie-poster.jpg", result.last().posterUrl)
    }

    @Test fun savedBackdropAndCandidateOrderAreRetained() {
        val result = tvHomeOutcomeCandidates(HomeUiState(
            continueWatching = listOf(progress().copy(backdropUrl = "saved-backdrop.jpg")),
            watchlistItems = listOf(detail(2), detail()),
        ))
        assertEquals(listOf(94997, 2), result.map { it.tmdbId })
        assertEquals("saved-backdrop.jpg", result.first().backdropUrl)
    }
}
