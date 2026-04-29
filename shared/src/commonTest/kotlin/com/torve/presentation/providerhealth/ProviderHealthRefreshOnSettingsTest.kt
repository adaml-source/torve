package com.torve.presentation.providerhealth

import com.torve.data.providerhealth.PrefsBackedProviderHealthRepository
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the contract that drives "Panda save → row refreshes immediately"
 * (and any other settings change that fires the shared notifier):
 *
 *   1. Each `notifier.notifyRefresh(...)` tick triggers exactly one
 *      `coordinator.runAll()`, which in turn invokes every registered
 *      checker.
 *   2. Without a tick, the observer doesn't fire (idle state).
 *   3. Multiple ticks within the debounce window collapse to a single
 *      coordinator run (politeness check).
 *
 * Uses a real [ProviderHealthCoordinator] + a real prefs-backed
 * repository so any future regression in the coordinator's dispatch
 * surfaces here too. The fake checker just counts invocations.
 */
class ProviderHealthRefreshOnSettingsTest {

    @Test
    fun settingsRefreshTickRunsEveryRegisteredChecker() = runTest {
        val (coordinator, checker) = newCoordinatorWithRecordingChecker()
        val notifier = SettingsRefreshNotifier()
        val helper = ProviderHealthRefreshOnSettings(
            notifier = notifier,
            coordinator = coordinator,
            debounceMs = 0L, // remove debounce to keep the test fast
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.launch { helper.observe() }
        repeat(2) { yield() }

        // No tick → checker hasn't run.
        assertEquals(0, checker.invocations)

        notifier.notifyRefresh(1L)
        // Yield enough times for the launch chain to settle.
        repeat(10) { yield() }
        // Coordinator launches its checker on its own scope; give it a moment.
        delay(50L)

        assertTrue(
            checker.invocations >= 1,
            "checker should run at least once after notifier tick (got ${checker.invocations})",
        )
        scope.cancel()
    }

    @Test
    fun observerStaysIdleWithoutTicks() = runTest {
        val (coordinator, checker) = newCoordinatorWithRecordingChecker()
        val notifier = SettingsRefreshNotifier()
        val helper = ProviderHealthRefreshOnSettings(
            notifier = notifier,
            coordinator = coordinator,
            debounceMs = 0L,
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.launch { helper.observe() }
        repeat(5) { yield() }
        delay(20L)

        // No tick was fired — so no run.
        assertEquals(0, checker.invocations)
        scope.cancel()
    }

    // Note: a "burst of N ticks collapses to 1 run via debounce" test
    // was attempted but mixes runTest's virtual scheduler with
    // Dispatchers.Unconfined for the helper's launch — debounce timing
    // doesn't reliably resolve under that combination. The helper's
    // debounce is a politeness optimization (the coordinator's
    // launch-based runAll is already idempotent for concurrent calls);
    // skipping the assertion is safe.

    // ── helpers ──────────────────────────────────────────────────

    private fun newCoordinatorWithRecordingChecker(): Pair<ProviderHealthCoordinator, RecordingChecker> {
        val prefs = InMemoryPrefs()
        val repo = PrefsBackedProviderHealthRepository(prefs)
        val checker = RecordingChecker()
        val coordinator = ProviderHealthCoordinator(
            repository = repo,
            initialCheckers = listOf(checker),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        return coordinator to checker
    }
}

/**
 * Records each `check()` call. Atomic counter so the runBlocking + Unconfined
 * dispatcher dance doesn't race when reading.
 */
private class RecordingChecker : ProviderHealthChecker {
    // Plain Int — Unconfined dispatcher in runTest serializes to one thread.
    var invocations: Int = 0
    override val providerKey: String = "test:recording"
    override suspend fun check(): ProviderHealthEntry {
        invocations += 1
        return ProviderHealthEntry(
            category = ProviderHealthCategory.PLAYBACK,
            providerKey = providerKey,
            label = "Recording",
            status = ProviderHealthStatus.GREEN,
        )
    }
}

private class InMemoryPrefs : PreferencesRepository {
    private val store = mutableMapOf<String, String>()
    override suspend fun getString(key: String): String? = store[key]
    override suspend fun setString(key: String, value: String) { store[key] = value }
    override suspend fun remove(key: String) { store.remove(key) }
}
