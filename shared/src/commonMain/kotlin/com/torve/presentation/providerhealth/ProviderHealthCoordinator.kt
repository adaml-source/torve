package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthRepository
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val isProviderEnabled: suspend (String) -> Boolean = { true },
) {
    private val checkers: MutableMap<String, ProviderHealthChecker> = initialCheckers
        .associateBy { it.providerKey }
        .toMutableMap()

    val entries: StateFlow<List<ProviderHealthEntry>> = repository.entries
    private val checkLock = Mutex()
    private val inFlight = mutableSetOf<String>()
    private val activeJobs = mutableMapOf<String, Job>()
    private val _checking = MutableStateFlow<Set<String>>(emptySet())
    val checking: StateFlow<Set<String>> = _checking.asStateFlow()

    fun register(checker: ProviderHealthChecker) {
        checkers[checker.providerKey] = checker
    }

    /** Re-run a single provider. Returns the [Job] so callers can `join`. */
    fun runCheck(providerKey: String): Job? {
        val checker = checkers[providerKey] ?: return null
        return scope.launch {
            if (!isProviderEnabled(providerKey)) {
                removeCachedProvider(providerKey)
                return@launch
            }
            if (!checkLock.withLock {
                inFlight.add(providerKey).also { added ->
                    if (added) activeJobs[providerKey] = kotlinx.coroutines.currentCoroutineContext()[Job]!!
                    _checking.value = inFlight.toSet()
                }
            }) return@launch
            // Mark as UNKNOWN (in-flight) so the UI shows a spinner.
            val current = repository.entries.value.firstOrNull { it.providerKey == providerKey }
            try {
            if (current != null) {
                repository.upsert(current.copy(status = ProviderHealthStatus.UNKNOWN, message = "Checking connection…"))
            }
            val result = runCatching { checker.check() }
                .getOrElse { t ->
                    if (t is CancellationException) throw t
                    ProviderHealthEntry(
                        category = current?.category
                            ?: error("Connection check failed before identifying its provider"),
                        providerKey = providerKey,
                        label = current.label,
                        status = ProviderHealthStatus.RED,
                        message = "Could not check this connection. Try again.",
                    )
                }
            if (isProviderEnabled(providerKey)) repository.upsert(result.copy(lastCheckedAt = nowMs()))
            else removeCachedProvider(providerKey)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (current != null && isProviderEnabled(providerKey)) repository.upsert(current)
                    else removeCachedProvider(providerKey)
                }
                throw cancelled
            } finally {
                withContext(NonCancellable) { checkLock.withLock {
                    inFlight.remove(providerKey)
                    activeJobs.remove(providerKey)
                    _checking.value = inFlight.toSet()
                } }
            }
        }
    }

    /** Re-run every registered checker. */
    fun runAll(): Job = scope.launch {
        reconcileDisabledProviders()
        checkers.keys.forEach { key -> runCheck(key)?.join() }
    }

    suspend fun reconcileDisabledProviders() {
        for (key in (checkers.keys + repository.entries.value.map { it.providerKey }).toSet()) {
            if (!isProviderEnabled(key)) {
                checkLock.withLock { activeJobs[key]?.cancel() }
                removeCachedProvider(key)
            }
        }
    }

    private suspend fun removeCachedProvider(key: String) {
        repository.entries.value.filter { it.providerKey == key }.forEach {
            repository.remove(it.category, key)
        }
    }
}
