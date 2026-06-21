package com.torve.desktop.providerhealth

import com.torve.desktop.playback.DesktopPlayerController
import com.torve.desktop.playback.DesktopPlayerPhase
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthRepository
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Subscribes to [DesktopPlayerController.state] and writes "last
 * successful playback" / "last failed playback" rows into the shared
 * [ProviderHealthRepository] every time the phase transitions in or out
 * of a relevant terminal state.
 *
 * Kept off the [DesktopPlayerController] constructor surface to keep the
 * controller free of provider-health concerns. Started once at app
 * startup; idempotent - calling [start] more than once is a no-op.
 */
class PlaybackHealthBridge(
    private val controller: DesktopPlayerController,
    private val repository: ProviderHealthRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    private var started: Boolean = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            controller.state
                .distinctUntilChanged { a, b -> a.phase == b.phase }
                .collect { state ->
                    when (state.phase) {
                        DesktopPlayerPhase.PLAYING -> recordSuccess(state.runtimeInfo.activeMediaPath)
                        DesktopPlayerPhase.RUNTIME_ERROR,
                        DesktopPlayerPhase.RESOLUTION_FAILED -> recordFailure(state.engineMessage, state.phase)
                        else -> { /* transient phases - no health update */ }
                    }
                }
        }
    }

    private suspend fun recordSuccess(mediaPath: String?) {
        repository.upsert(
            ProviderHealthEntry(
                category = ProviderHealthCategory.PLAYBACK,
                providerKey = "playback:last_success",
                label = "Last playback",
                status = ProviderHealthStatus.GREEN,
                lastCheckedAt = nowMs(),
                message = "Last successful start" + (mediaPath?.let { ": ${truncate(it)}" } ?: "."),
            ),
        )
    }

    private suspend fun recordFailure(message: String?, phase: DesktopPlayerPhase) {
        val phaseLabel = when (phase) {
            DesktopPlayerPhase.RUNTIME_ERROR -> "Runtime error"
            DesktopPlayerPhase.RESOLUTION_FAILED -> "Resolution failed"
            else -> "Failed"
        }
        repository.upsert(
            ProviderHealthEntry(
                category = ProviderHealthCategory.PLAYBACK,
                providerKey = "playback:last_failure",
                label = "Last playback failure",
                status = ProviderHealthStatus.RED,
                lastCheckedAt = nowMs(),
                message = "$phaseLabel: ${message ?: "no detail."}",
                nextAction = "Open diagnostics",
            ),
        )
    }

    /** Trim long URLs to keep the support row readable; never logs secrets. */
    private fun truncate(text: String): String =
        if (text.length <= 80) text else text.take(77) + "..."
}
