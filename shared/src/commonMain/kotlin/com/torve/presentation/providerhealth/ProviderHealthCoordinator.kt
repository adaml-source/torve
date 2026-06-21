package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthRepository
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Runs registered [ProviderHealthChecker]s on demand and persists their
 * results through [ProviderHealthRepository].
 *
 * The coordinator is the only thing UI code needs to talk to:
 *   - [entries]            — observe last-known state.
 *   - [runCheck]           — re-test a single provider.
 *   - [runAll]             — re-test every registered provider.
 *
 * Checkers are registered at construction (typically in DI). Adding
 * checkers later via [register] is supported but not required for v1.
 */
class ProviderHealthCoordinator(
    private val repository: ProviderHealthRepository,
    initialCheckers: List<ProviderHealthChecker> = emptyList(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val checkers: MutableMap<String, ProviderHealthChecker> = initialCheckers
        .associateBy { it.providerKey }
        .toMutableMap()

    val entries: StateFlow<List<ProviderHealthEntry>> = repository.entries

    fun register(checker: ProviderHealthChecker) {
        checkers[checker.providerKey] = checker
    }

    /** Re-run a single provider. Returns the [Job] so callers can `join`. */
    fun runCheck(providerKey: String): Job? {
        val checker = checkers[providerKey] ?: return null
        return scope.launch {
            // Mark as UNKNOWN (in-flight) so the UI shows a spinner.
            val current = repository.entries.value.firstOrNull { it.providerKey == providerKey }
            if (current != null) {
                repository.upsert(current.copy(status = ProviderHealthStatus.UNKNOWN))
            }
            val result = runCatching { checker.check() }
                .getOrElse { t ->
                    ProviderHealthEntry(
                        category = current?.category
                            ?: error("Checker '$providerKey' threw before producing an entry: ${t.message}"),
                        providerKey = providerKey,
                        label = current.label,
                        status = ProviderHealthStatus.RED,
                        message = "Health check failed: ${t.message ?: t::class.simpleName}",
                    )
                }
            repository.upsert(result.copy(lastCheckedAt = nowMs()))
        }
    }

    /** Re-run every registered checker. */
    fun runAll(): Job = scope.launch {
        checkers.keys.forEach { key -> runCheck(key)?.join() }
    }
}
