package com.torve.presentation.home

import com.torve.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRowLoadingTest {
    private fun row(id: String) = CatalogShelf(id, id, listOf(MediaItem(id, title = id, type = MediaType.MOVIE)))
    @Test fun readyRowPublishesBeforeItsSlowNeighbour() = runTest {
        val seen = mutableListOf<List<String>>()
        val job = async { loadHomeRows(listOf({ delay(1_000); row("slow") }, { delay(10); row("fast") })) {
            seen += it.map { row -> row.id }
        } }
        advanceTimeBy(11); runCurrent()
        assertEquals(listOf(listOf("fast")), seen)
        assertEquals(listOf("fast", "slow"), job.await().map { it.id })
        assertEquals(listOf("fast", "slow"), seen.last())
    }
    @Test fun timeoutsAndFailedSourcesDoNotRetryOrLoseReadyRows() = runTest {
        var requests = 0
        val result = loadHomeRows(listOf({ requests++; awaitCancellation() },
            { requests++; error("offline") }, { requests++; row("available") }), timeoutMs = 100) { }
        assertEquals(3, requests)
        assertEquals(listOf("available"), result.map { it.id })
    }
    @Test fun noMoreThanTwoRequestsRunTogetherAndParentCancellationStopsThem() = runTest {
        var active = 0; var maximum = 0
        val job = launch { loadHomeRows((1..10).map { { active++; maximum = maxOf(active, maximum)
            try { awaitCancellation() } finally { active-- } } }) { error("No row finished") } }
        runCurrent(); assertEquals(2, maximum)
        job.cancelAndJoin(); assertEquals(0, active)
    }
    @Test fun aResumeRowCannotCauseSavedDiscoveryAndExtensionsToBeDiscarded() {
        val progress = WatchProgress("42", MediaType.MOVIE, "Fresh progress", positionMs = 1200, durationMs = 9000)
        val cached = HomeUiState(shelves = listOf(row("trending")), addonShelves = listOf(row("extension")),
            watchlistItems = row("saved").items)
        val current = HomeUiState(continueWatching = listOf(progress), shelves = listOf(row("fresh")))
        val restored = current.withStartupFallback(cached)
        assertEquals(listOf(progress), restored.continueWatching)
        assertEquals(listOf("fresh", "trending"), restored.shelves.map { it.id })
        assertEquals(cached.addonShelves, restored.addonShelves)
        assertEquals(cached.watchlistItems, restored.watchlistItems)
        assertFalse(restored.isLoading)
    }
    @Test fun peoplePreviewStopsAfterTheConfiguredPages() = runTest {
        val actor = PersonSummary(1, "Actor", knownForDepartment = "Acting")
        val calls = mutableListOf<Int>()
        fillHomePeopleRows(listOf(actor), actors = true, directors = true, maxPages = 2) {
            calls += it; listOf(actor.copy(id = it))
        }
        assertEquals(listOf(2), calls)
    }
}
