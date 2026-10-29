package com.torve.android.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TvStartupPreparationTest {
    @Test fun profileReadsWaitForCachedAccountRestoration() = runTest {
        val restored = CompletableDeferred<Unit>()
        val reads = mutableListOf<String>()
        val preparation = TvStartupPreparation(this, StandardTestDispatcher(testScheduler),
            restoreAccount = { restored.await() },
            loadAppearance = { reads += "appearance"; "appearance" },
            loadHome = { reads += "home"; "home" })
        runCurrent()
        assertTrue(reads.isEmpty())
        restored.complete(Unit)
        runCurrent()
        assertEquals(setOf("appearance", "home"), reads.toSet())
        assertEquals("home", preparation.home.await())
    }

    @Test fun appearanceAndHomeDoNotSerializeTheirLoading() = runTest {
        val appearance = CompletableDeferred<String>()
        val home = CompletableDeferred<String>()
        var homeStarted = false
        val preparation = TvStartupPreparation(this, StandardTestDispatcher(testScheduler),
            restoreAccount = {}, loadAppearance = { appearance.await() },
            loadHome = { homeStarted = true; home.await() })
        runCurrent()
        assertTrue(homeStarted)
        home.complete("saved Home")
        runCurrent()
        assertEquals("saved Home", preparation.home.await())
        assertFalse(preparation.appearance.isCompleted)
        appearance.complete("saved theme")
    }

    @Test fun leavingTheActivityCancelsPendingPreparationWithoutReadingAnotherAccount() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val restored = CompletableDeferred<Unit>()
        var profileReads = 0
        val preparation = TvStartupPreparation(scope, StandardTestDispatcher(testScheduler),
            restoreAccount = { restored.await() },
            loadAppearance = { profileReads++; Unit }, loadHome = { profileReads++; Unit })
        runCurrent()
        scope.cancel()
        restored.complete(Unit)
        runCurrent()
        assertEquals(0, profileReads)
        assertTrue(preparation.appearance.isCancelled)
        assertTrue(preparation.home.isCancelled)
    }
}
