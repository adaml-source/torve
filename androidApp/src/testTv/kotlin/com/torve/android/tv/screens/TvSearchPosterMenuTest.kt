package com.torve.android.tv.screens

import com.torve.android.tv.components.TvMediaContextMenuAction
import com.torve.domain.integrations.*
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TvSearchPosterMenuTest {
    private val movie = MediaItem("tmdb:42", tmdbId = 42, title = "Movie", type = MediaType.MOVIE)

    @Test fun shortcutsIncludeTrailerBeforeMetadataArrivesAndPreserveWatchlistState() {
        val watchlist = TvMediaContextMenuAction("toggle_watchlist", "Remove from watchlist", isLocked = true)
        val actions = tvSearchPosterActions(movie, listOf(
            TvMediaContextMenuAction("view_details", "Details"), watchlist,
            TvMediaContextMenuAction("trailer", "Trailer")), "Library", "Season", "Watch trailer")
        assertEquals(listOf("search_library", "toggle_watchlist", "trailer", "view_details"), actions.map { it.id })
        assertEquals(watchlist, actions[1])
        assertNull(movie.trailerKey)
        assertEquals("Watch trailer", actions[2].label)
    }

    @Test fun seriesAskForASeasonAndDoNotOfferFramedFocusMode() {
        val actions = tvSearchPosterActions(movie.copy(type = MediaType.SERIES), emptyList(), "Library", "Choose season", "Trailer")
        assertEquals("Choose season", actions.first().label)
        assertFalse(actions.any { it.id.contains("focus_preview") })
    }

    @Test fun explicitMovieRequestChecksStatusSubmitsOnceAndTracksTheResult() = runTest {
        val service = Service()
        val tracked = mutableListOf<MediaLifecycleStatus>()
        assertEquals("Added to your library queue", requestTvSearchLibraryCopy(movie, null, service) { status, title, seasons ->
            assertEquals(movie.title, title); assertTrue(seasons.isEmpty()); tracked += status
        })
        assertEquals(listOf(MediaLifecycleRequest(42, MediaType.MOVIE)), service.requests)
        assertEquals(MediaLifecycleState.PROCESSING, tracked.single().state)
    }

    @Test fun availableUnconfiguredUnknownAndPendingTitlesAreNeverSubmittedAgain() = runTest {
        for (state in listOf(MediaLifecycleState.AVAILABLE, MediaLifecycleState.UNCONFIGURED,
            MediaLifecycleState.UNKNOWN, MediaLifecycleState.PROCESSING, MediaLifecycleState.PENDING_APPROVAL)) {
            val service = Service(state)
            requestTvSearchLibraryCopy(movie, null, service) { _, _, _ -> }
            assertTrue("Unexpected request for $state", service.requests.isEmpty())
        }
    }

    @Test fun seriesRequestContainsOnlyTheExplicitlyChosenSeason() = runTest {
        val service = Service()
        requestTvSearchLibraryCopy(movie.copy(type = MediaType.SERIES), 3, service) { _, _, seasons -> assertEquals(listOf(3), seasons) }
        assertEquals(listOf(3), service.requests.single().seasons)
        assertEquals(listOf(3), service.checkedSeasons)
    }

    @Test fun failedStatusCheckSendsNoRequest() = runTest {
        val service = Service(failure = IllegalStateException("offline"))
        try {
            requestTvSearchLibraryCopy(movie, null, service) { _, _, _ -> fail("Must not track") }
            fail("Must surface failure")
        } catch (_: IllegalStateException) { }
        assertTrue(service.requests.isEmpty())
    }

    @Test fun cancellationPropagatesWithoutSubmitting() = runTest {
        val service = Service(failure = CancellationException("cancelled"))
        try {
            requestTvSearchLibraryCopy(movie, null, service) { _, _, _ -> fail("Must not track") }
            fail("Must cancel")
        } catch (_: CancellationException) { }
        assertTrue(service.requests.isEmpty())
    }

    @Test fun unidentifiedTitlesDoNotCallTheLibraryService() = runTest {
        val service = Service()
        requestTvSearchLibraryCopy(movie.copy(tmdbId = null), null, service) { _, _, _ -> fail("Must not track") }
        assertEquals(0, service.checks)
        assertTrue(service.requests.isEmpty())
    }

    private class Service(
        private val state: MediaLifecycleState = MediaLifecycleState.NOT_REQUESTED,
        private val failure: Exception? = null,
    ) : MediaLifecycleService {
        var checks = 0
        var checkedSeasons = emptyList<Int>()
        val requests = mutableListOf<MediaLifecycleRequest>()
        override suspend fun isConfigured() = true
        override suspend fun testConnection(serverUrl: String, apiKey: String) = true
        override suspend fun getStatus(tmdbId: Int, mediaType: MediaType, seasons: List<Int>, is4k: Boolean): MediaLifecycleStatus {
            checks++; checkedSeasons = seasons
            failure?.let { throw it }
            return MediaLifecycleStatus(tmdbId, mediaType, state)
        }
        override suspend fun request(request: MediaLifecycleRequest): MediaLifecycleStatus {
            requests += request
            return MediaLifecycleStatus(request.tmdbId, request.mediaType, MediaLifecycleState.PROCESSING)
        }
        override suspend fun retry(requestId: Int): MediaLifecycleStatus = error("Not used")
    }
}
