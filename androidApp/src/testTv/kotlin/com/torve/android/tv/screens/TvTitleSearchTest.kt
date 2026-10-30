package com.torve.android.tv.screens

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.PagedResult
import com.torve.domain.repository.MetadataRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvTitleSearchTest {
    private val candice = MediaItem("2819924", tmdbId = 100, type = MediaType.SERIES, title = "Candice Renoir", voteCount = 6000)
    private val candi = MediaItem("2", type = MediaType.MOVIE, title = "Candi", voteCount = 2)
    private fun repository(search: () -> PagedResult, suggestions: Flow<List<MediaItem>>) =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MetadataRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "searchMultiPaged" -> search()
                "titleSuggestions" -> suggestions
                else -> error("Unexpected call: ${method.name}")
            }
        } as MetadataRepository
    private fun page() = PagedResult(listOf(candi), 1, 3, 41)

    @Test fun partialTitlesRankAboveObscureWholeWordMatchesAndDeduplicate() {
        assertEquals(listOf(candice, candi), mergeTvTitleSearch("Candi", listOf(candi, candice), listOf(candice)))
        assertTrue(tvTitlePrefixMatches("candi ren", candice))
        assertFalse(tvTitlePrefixMatches("reno candi missing", candice))
    }
    @Test fun normalResultsPublishBeforeDelayedCompletions() = runTest {
        val updates = mutableListOf<List<MediaItem>>()
        val repo = repository(::page, flow { delay(500); emit(listOf(candice)) })
        val job = launch { repo.searchTvTitles("Candi", null) { items, _ -> updates += items } }
        runCurrent()
        assertEquals(listOf(candi), updates.single())
        advanceUntilIdle(); job.join()
        assertEquals(listOf(candice, candi), updates.last())
    }
    @Test fun unavailableCompletionsKeepNormalResultsAndPaging() = runTest {
        var lastPage: PagedResult? = null
        var last = emptyList<MediaItem>()
        repository(::page, flow { throw IllegalStateException("offline") }).searchTvTitles("Candi", null) { items, page -> last = items; lastPage = page }
        assertEquals(listOf(candi), last); assertEquals(3, lastPage?.totalPages)
    }
    @Test fun completionsStillWorkWhenTheNormalSearchFails() = runTest {
        var last = emptyList<MediaItem>()
        repository({ error("offline") }, flowOf(listOf(candice))).searchTvTitles("Candi", "tv") { items, _ -> last = items }
        assertEquals(listOf(candice), last)
    }
    @Test fun cancellingAnOldQueryCancelsItsCompletions() = runTest {
        var cancelled = false
        val repo = repository(::page, flow { try { awaitCancellation() } finally { cancelled = true } })
        val job = launch { repo.searchTvTitles("Candi", null) { _, _ -> } }
        runCurrent(); job.cancelAndJoin()
        assertTrue(cancelled)
    }
    @Test fun aStalledSuggestionSourceHasABoundedWait() = runTest {
        val repo = repository(::page, flow { awaitCancellation() })
        repo.searchTvTitles("Candi", null) { _, _ -> }
        assertEquals(2_500L, testScheduler.currentTime)
    }
}
