package com.torve.android.tv.screens

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressiveCatalogRowsTest {
    @Test fun readyRowDoesNotWaitForSlowNeighbour() = runTest {
        val gate = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val job = launch { loadProgressiveCatalogRows(listOf("slow", "ready"), fetch = {
            if (it == "slow") gate.await()
            it
        }) { _, value -> published += value } }
        runCurrent()
        assertEquals(listOf("ready"), published)
        gate.complete(Unit); job.join()
        assertEquals(listOf("ready", "slow"), published)
    }

    @Test fun onlyTwoRequestsRunAtOnceAndDuplicatesAreSkipped() = runTest {
        var active = 0; var maximum = 0; var count = 0
        loadProgressiveCatalogRows(listOf(1, 2, 3, 4, 1), fetch = {
            active++; maximum = maxOf(maximum, active); count++
            delay(100); active--; it
        }) { _, _ -> }
        assertEquals(2, maximum); assertEquals(4, count)
    }

    @Test fun failedAndTimedOutRowsDoNotEraseSuccessesOrRetry() = runTest {
        val published = mutableListOf<Int>(); val calls = mutableListOf<Int>()
        loadProgressiveCatalogRows(listOf(1, 2, 3), rowTimeoutMs = 200, fetch = {
            calls += it
            when (it) { 1 -> error("offline"); 2 -> delay(1_000) }
            it
        }) { _, value -> published += value }
        assertEquals(listOf(3), published); assertEquals(listOf(1, 2, 3), calls)
        assertEquals(200L, currentTime)
    }

    @Test fun leavingPageCancelsRunningAndQueuedWork() = runTest {
        var calls = 0; var publications = 0
        val job = launch { loadProgressiveCatalogRows((1..20).toList(), fetch = {
            calls++; awaitCancellation()
        }) { _, _ -> publications++ } }
        runCurrent(); job.cancelAndJoin(); advanceUntilIdle()
        assertEquals(2, calls); assertEquals(0, publications)
    }

    @Test fun backgroundResultsUpdateInPlaceWithoutMovingExistingFocus() {
        val merged = mergeProgressiveCatalogRows(listOf("popular:old", "drama:old"),
            listOf("trending:new", "popular:new")) { it.substringBefore(':') }
        assertEquals(listOf("popular:new", "drama:old", "trending:new"), merged)
    }
}
