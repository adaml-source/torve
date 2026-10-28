package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

class ProviderHealthConcurrencyTest {
    private val ready = ProviderHealthEntry(ProviderHealthCategory.REQUEST_MANAGER, "seerr:test", "Seerr", ProviderHealthStatus.GREEN, 10L)
    private class Repository(initial: ProviderHealthEntry) : ProviderHealthRepository {
        override val entries = MutableStateFlow(listOf(initial))
        override suspend fun load() = Unit
        override suspend fun upsert(entry: ProviderHealthEntry) { entries.value = listOf(entry) }
        override suspend fun remove(category: ProviderHealthCategory, providerKey: String) { entries.value = entries.value.filterNot { it.providerKey == providerKey } }
        override suspend fun clear() = Unit
    }
    @Test fun overlappingChecksProbeOnlyOnceAndTheCompletedResultWins() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val repo = Repository(ready)
        val checker = object : ProviderHealthChecker {
            override val providerKey = ready.providerKey
            override suspend fun check(): ProviderHealthEntry { calls++; gate.await(); return ready }
        }
        val coordinator = ProviderHealthCoordinator(repo, listOf(checker), backgroundScope, { 20L })
        val first = coordinator.runCheck(ready.providerKey)!!; runCurrent()
        coordinator.runCheck(ready.providerKey)!!.join()
        assertEquals(1, calls); assertEquals(ProviderHealthStatus.UNKNOWN, repo.entries.value.single().status)
        gate.complete(Unit); first.join()
        assertEquals(ProviderHealthStatus.GREEN, repo.entries.value.single().status)
        assertEquals(20L, repo.entries.value.single().lastCheckedAt)
    }
    @Test fun cancelledProbeRestoresCachedStateAndAllowsANewCheck() = runTest {
        var hold = true
        val repo = Repository(ready)
        val checker = object : ProviderHealthChecker {
            override val providerKey = ready.providerKey
            override suspend fun check(): ProviderHealthEntry { if (hold) awaitCancellation(); return ready }
        }
        val coordinator = ProviderHealthCoordinator(repo, listOf(checker), backgroundScope, { 20L })
        val job = coordinator.runCheck(ready.providerKey)!!; runCurrent(); job.cancelAndJoin()
        assertEquals(ready, repo.entries.value.single())
        hold = false; coordinator.runCheck(ready.providerKey)!!.join()
        assertEquals(20L, repo.entries.value.single().lastCheckedAt)
    }
    @Test fun unexpectedFailuresNeverExposeExceptionPayloads() = runTest {
        val repo = Repository(ready)
        val checker = object : ProviderHealthChecker {
            override val providerKey = ready.providerKey
            override suspend fun check(): ProviderHealthEntry = error("URL=http://private-host key=private-secret")
        }
        ProviderHealthCoordinator(repo, listOf(checker), backgroundScope).runCheck(ready.providerKey)!!.join()
        val result = repo.entries.value.single()
        assertEquals(ProviderHealthStatus.RED, result.status)
        assertFalse(result.message.orEmpty().contains("private"))
    }
    @Test fun disabledProviderIsNeverProbedAndItsStaleWarningIsRemoved() = runTest {
        var calls = 0
        val repo = Repository(ready.copy(status = ProviderHealthStatus.RED))
        val checker = object : ProviderHealthChecker {
            override val providerKey = ready.providerKey
            override suspend fun check(): ProviderHealthEntry { calls++; return ready }
        }
        val coordinator = ProviderHealthCoordinator(repo, listOf(checker), backgroundScope, isProviderEnabled = { false })
        coordinator.runAll().join()
        assertEquals(0, calls)
        assertTrue(repo.entries.value.isEmpty())
        assertTrue(coordinator.checking.value.isEmpty())
    }
    @Test fun disablingCancelsAnInFlightProbeWithoutRestoringItsOldErrorAndCanBeReenabled() = runTest {
        var enabled = true
        var hold = true
        val repo = Repository(ready.copy(status = ProviderHealthStatus.RED))
        val checker = object : ProviderHealthChecker {
            override val providerKey = ready.providerKey
            override suspend fun check(): ProviderHealthEntry { if (hold) awaitCancellation(); return ready }
        }
        val coordinator = ProviderHealthCoordinator(repo, listOf(checker), backgroundScope, isProviderEnabled = { enabled })
        val job = coordinator.runCheck(ready.providerKey)!!
        runCurrent()
        enabled = false
        coordinator.reconcileDisabledProviders()
        job.join()
        assertTrue(repo.entries.value.isEmpty())
        assertTrue(coordinator.checking.value.isEmpty())
        enabled = true
        hold = false
        coordinator.runCheck(ready.providerKey)!!.join()
        assertEquals(ProviderHealthStatus.GREEN, repo.entries.value.single().status)
    }
}
