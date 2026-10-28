package com.torve.presentation.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A single Trakt-only sync, shared by authorization and the manual sync action. */
internal class TraktSyncCoordinator(
    private val scope: CoroutineScope,
    private val prepareSession: suspend () -> Boolean,
    private val importTrakt: suspend () -> Boolean,
    private val onStarted: () -> Unit,
    private val onFinished: (Boolean) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private var job: Job? = null
    private var generation = 0L
    private var automaticSyncRequested = false

    /** Settings refreshes also follow a completed sync; they must not start another one. */
    fun syncAutomatically(): Job? {
        if (automaticSyncRequested) return job
        return sync()
    }

    fun sync(): Job {
        automaticSyncRequested = true
        job?.takeIf { it.isActive }?.let { return it }
        val syncGeneration = ++generation
        onStarted()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val success = prepareSession() && importTrakt()
                if (generation == syncGeneration) onFinished(success)
            } catch (cancelled: CancellationException) {
                if (generation == syncGeneration) onFinished(false)
                throw cancelled
            } catch (error: Exception) {
                if (generation == syncGeneration) {
                    onFailure(error)
                    onFinished(false)
                }
            }
        }
        job = next
        next.invokeOnCompletion { if (job === next) job = null }
        next.start()
        return next
    }

    fun cancel() {
        automaticSyncRequested = false
        ++generation
        job?.cancel()
        if (job != null) onFinished(false)
        job = null
    }
}
