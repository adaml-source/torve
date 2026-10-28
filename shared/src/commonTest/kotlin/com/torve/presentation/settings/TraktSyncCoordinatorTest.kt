package com.torve.presentation.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TraktSyncCoordinatorTest {
    @Test
    fun settingsRefreshAfterCompletionDoesNotStartAnotherAutomaticSync() = runTest {
        var imports = 0
        var starts = 0
        val sync = TraktSyncCoordinator(this, { true }, { imports++; true },
            { starts++ }, {}, { throw it })
        sync.syncAutomatically()!!.join()
        repeat(5) { sync.syncAutomatically()?.join() }
        assertEquals(1, imports)
        assertEquals(1, starts)
        // An explicit request still starts a fresh Trakt-only sync.
        sync.sync().join()
        assertEquals(2, imports)
        sync.syncAutomatically()?.join()
        assertEquals(2, imports)
    }

    @Test
    fun authorizationSyncAlsoSatisfiesAutomaticSettingsReloads() = runTest {
        var imports = 0
        val sync = TraktSyncCoordinator(this, { true }, { imports++; true },
            {}, {}, { throw it })
        sync.sync().join()
        sync.syncAutomatically()?.join()
        assertEquals(1, imports)
        sync.cancel()
        sync.syncAutomatically()!!.join()
        assertEquals(2, imports)
    }

    @Test
    fun failedAutomaticSyncDoesNotRetryOnEverySettingsRefresh() = runTest {
        var imports = 0
        var result: Boolean? = null
        val sync = TraktSyncCoordinator(this, { true }, { imports++; false },
            {}, { result = it }, { throw it })
        sync.syncAutomatically()!!.join()
        repeat(5) { sync.syncAutomatically()?.join() }
        assertEquals(false, result)
        assertEquals(1, imports)
    }

    @Test
    fun authorizationSyncRunsWhileAccountBackupIsStillPending() = runTest {
        val backup = CompletableDeferred<Unit>()
        val imported = mutableListOf<String>()
        var syncing = false
        var success = false
        val backupJob = launch { backup.await() }
        val sync = TraktSyncCoordinator(
            scope = this,
            prepareSession = { true },
            importTrakt = {
                imported += listOf("watchlist", "history", "progress", "ratings", "pending writes")
                true
            },
            onStarted = { syncing = true },
            onFinished = { syncing = false; success = it },
            onFailure = { throw it },
        )
        sync.sync().join()
        assertFalse(syncing)
        assertTrue(success)
        assertFalse(backupJob.isCompleted)
        assertEquals(5, imported.size)
        backup.complete(Unit)
    }

    @Test
    fun repeatedManualRequestsShareTheRunningAuthorizationSync() = runTest {
        var imports = 0
        var starts = 0
        val sync = TraktSyncCoordinator(this, { true }, { delay(100); imports++; true },
            { starts++ }, {}, { throw it })
        val authorizationJob = sync.sync()
        assertSame(authorizationJob, sync.sync())
        authorizationJob.join()
        assertEquals(1, imports)
        assertEquals(1, starts)
    }

    @Test
    fun sessionFailureDoesNotRunImportsOrReportSuccess() = runTest {
        var imported = false
        var result: Boolean? = null
        val sync = TraktSyncCoordinator(this, { false }, { imported = true; true },
            {}, { result = it }, { throw it })
        sync.sync().join()
        assertFalse(imported)
        assertEquals(false, result)
    }

    @Test
    fun partialImportDoesNotReportSuccess() = runTest {
        var result: Boolean? = null
        val sync = TraktSyncCoordinator(this, { true }, { false }, {}, { result = it }, { throw it })
        sync.sync().join()
        assertEquals(false, result)
    }

    @Test
    fun replacingAnAccountCannotLetOldCancellationClearNewSyncProgress() = runTest {
        var syncing = false
        var failed = false
        val sync = TraktSyncCoordinator(this, { true }, { delay(100); true },
            { syncing = true }, { syncing = false }, { failed = true })
        sync.sync()
        runCurrent()
        sync.cancel()
        val newAccountSync = sync.sync()
        runCurrent()
        assertTrue(syncing)
        assertFalse(failed)
        newAccountSync.join()
        assertFalse(syncing)
    }
}
