package com.torve.presentation.lanlibrary

import com.torve.data.lanlibrary.LanHubRegistryApi
import com.torve.domain.lanlibrary.LanHub
import com.torve.domain.lanlibrary.LanHubStreamUrlResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Consumer-side helper for TV / mobile to discover desktop hubs. Polls
 * the backend registry on a low-cadence loop while the screen is alive;
 * stops when the caller cancels [observe].
 *
 * Returns only metadata — no auth secret. Callers needing to play a
 * file must call [buildStreamUrl] which fetches a fresh secret per hub.
 */
class LanHubDiscovery(
    private val registry: LanHubRegistryApi,
    private val pollIntervalMs: Long = DEFAULT_POLL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _hubs = MutableStateFlow<List<LanHub>>(emptyList())
    val hubs: StateFlow<List<LanHub>> = _hubs.asStateFlow()

    /**
     * Start the polling loop. Returns the [Job] so the caller can
     * cancel it. Idempotent — multiple calls reuse the same flow.
     */
    fun observe(): Job = scope.launch {
        while (true) {
            val list = runCatching { registry.list() }.getOrDefault(emptyList())
            _hubs.value = list
            delay(pollIntervalMs)
        }
    }

    /**
     * Build a `/local/stream/{id}?token=...` URL for [entryId] on the
     * picked [hub]. The caller is responsible for picking which hub to
     * use (typically: the most-recently-published one).
     *
     * Auth secret is fetched per call so the secret never sits in
     * memory between launches; rotating publisher restarts immediately
     * invalidate stale clients.
     */
    suspend fun buildStreamUrl(
        hub: LanHub,
        entryId: String,
        accessTokenForEntry: suspend (LanHub) -> String?,
    ): LanHubStreamUrlResult {
        val secret = registry.fetchSecret(hub.publisherId)
            ?: return LanHubStreamUrlResult.RegistryUnavailable
        val perEntryToken = accessTokenForEntry(hub)
            ?: return LanHubStreamUrlResult.NoAccessToken
        val url = "http://${hub.lanHost}:${hub.lanPort}/local/stream/$entryId?token=$perEntryToken"
        return LanHubStreamUrlResult.Ready(url = url, authSecret = secret.authSecret)
    }

    companion object {
        const val DEFAULT_POLL_MS: Long = 60_000L
    }
}
