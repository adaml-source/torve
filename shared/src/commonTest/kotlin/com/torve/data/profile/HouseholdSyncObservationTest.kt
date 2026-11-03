package com.torve.data.profile

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HouseholdSyncObservationTest {
    @Test fun invalidationsNeverExecuteOrMaterializeTheHistoryQuery() = runTest {
        val listeners = mutableSetOf<Query.Listener>()
        val query = object : Query<String>({ error("Rows must not be mapped by a change watcher") }) {
            override fun addListener(listener: Listener) { listeners += listener }
            override fun removeListener(listener: Listener) { listeners -= listener }
            override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
                error("The original crash path must never execute a SELECT to detect changes")
        }
        val signals = async { query.householdChangeSignals().take(2).toList() }
        runCurrent()
        assertEquals(1, listeners.size)
        listeners.toList().forEach { it.queryResultsChanged() }
        assertEquals(listOf(Unit, Unit), signals.await())
        assertTrue(listeners.isEmpty(), "A stopped watcher must release its listener")
    }

    @Test fun failedDatabaseObservationRetriesAndRecoversWithoutEscapingTheScope() = runTest {
        var attempts = 0
        var failures = 0
        retryHouseholdObservation(onFailure = { failures++ }, retryDelayMs = 15_000) {
            attempts++
            if (attempts == 1) throw IllegalStateException("Simulated cursor allocation failure")
        }
        assertEquals(2, attempts)
        assertEquals(1, failures)
        assertEquals(15_000L, testScheduler.currentTime)
    }

    @Test fun cancellationIsNeverConvertedIntoARecoverableSyncError() = runTest {
        var failures = 0
        assertFailsWith<CancellationException> {
            retryHouseholdObservation(onFailure = { failures++ }) { throw CancellationException("Profile changed") }
        }
        assertEquals(0, failures)
    }
}
