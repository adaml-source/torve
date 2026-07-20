package com.torve.domain.streams

import com.torve.data.usenet.UsenetCandidateMapping.toDto
import com.torve.data.usenet.UsenetMapper
import com.torve.data.usenet.UsenetRepository
import com.torve.data.usenet.model.UsenetAvailability
import com.torve.data.usenet.model.UsenetCandidatePayload
import com.torve.data.usenet.model.UsenetCandidateUiModel
import com.torve.data.usenet.model.UsenetResolvedStream
import com.torve.domain.telemetry.NoOpTelemetryEmitter
import com.torve.domain.telemetry.StreamPathDiagnostics
import com.torve.domain.telemetry.StreamPathTelemetryContext
import com.torve.domain.telemetry.StreamPlaybackPath
import com.torve.domain.telemetry.TelemetryEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock

/**
 * Bounded polling loop for a single user-selected Usenet job.
 *
 * Design intent:
 *  - At most one active poll in the whole app at any time. Starting a
 *    new poll cancels the previous one + best-effort tells the backend
 *    to cancel the outgoing job (and, if the backend rejects targeted
 *    job-id cancels, falls back to a content-scoped cancel).
 *  - Cadence is VM-state-driven, not Compose-driven. Callers pass a
 *    scope and a tick callback; recomposition can't accidentally spawn
 *    pollers.
 *  - Bounded max duration so a backend that never transitions can't keep
 *    the loop alive indefinitely. On timeout we emit a terminal
 *    `TimedOut` outcome; the VM treats it as a failed row (same
 *    fallback chain Prompt 5 established).
 *  - Terminal states (`Ready`, `Failed`, `TimedOut`) stop the loop
 *    without calling backend cancel — the job has already reached a
 *    final state and cancel would be a wasted round-trip.
 *
 * Scope boundary:
 *  - The poller does NOT apply the outcome to the sidecar. The VM is
 *    the sole owner of sidecar writes; it consumes `PollOutcome` and
 *    calls `_state.update { }`. This keeps the poller fully testable
 *    without standing up a VM.
 *  - Backend state is derived via [UsenetMapper.applyJobStatus] so the
 *    same three-state collapse applies to warm / resolve / poll.
 */
class UsenetJobPoller(
    private val repository: UsenetRepository,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val telemetry: TelemetryEmitter = NoOpTelemetryEmitter(),
) {
    private val mutex = Mutex()
    private var active: ActivePoll? = null

    /**
     * Begin polling `jobId` for [candidateId] under [contentId]. Cancels
     * any prior active poll. Returns the [Job] so the caller can also
     * cancel via structured concurrency (VM teardown).
     *
     * [onOutcome] is invoked on the calling [scope] for each tick. The
     * callback receives both a non-terminal `StillWarming` (so the VM
     * can refresh the sidecar row) and terminal outcomes; after any
     * terminal outcome the poller has already stopped itself.
     */
    suspend fun startPolling(
        contentId: String,
        candidate: UsenetCandidatePayload,
        jobId: String,
        scope: CoroutineScope,
        seedRow: UsenetCandidateUiModel,
        onOutcome: suspend (PollOutcome) -> Unit,
    ): Job {
        val candidateId = candidate.candidateId
        if (contentId.isBlank() || candidateId.isBlank() || candidate.hashKey.isBlank() || jobId.isBlank()) {
            return scope.launch { /* no-op placeholder; cancellation-safe */ }
        }
        // Cancel any prior active poll before we start a new one. Backend
        // cancel is best-effort — a failure there does not block the new
        // poller from starting.
        mutex.withLock {
            val prior = active
            if (prior != null && prior.jobId != jobId) {
                prior.job.cancel()
                bestEffortCancel(prior.contentId, prior.candidateId)
            } else if (prior != null && prior.jobId == jobId) {
                // Same job resumed — keep the existing loop running.
                return prior.job
            }
        }

        val job = scope.launch {
            val startedAt = nowMs()
            var lastRow: UsenetCandidateUiModel = seedRow
            try {
                while (true) {
                    if (nowMs() - startedAt > maxDurationMs) {
                        onOutcome(PollOutcome.TimedOut(row = lastRow.asUnavailable(nowMs())))
                        break
                    }
                    yield()
                    delay(intervalMs)

                    val response = runCatching {
                        repository.getUsenetJobStatus(jobId)
                    }.getOrNull()

                    if (response == null) {
                        // Transient network failure — continue the loop. A
                        // single bad read shouldn't kick the user out of
                        // Preparing. The bounded cap still applies.
                        continue
                    }

                    val updated = UsenetMapper.applyJobStatus(
                        prior = lastRow,
                        response = response,
                        now = nowMs(),
                    )
                    lastRow = updated

                    when (updated.availabilityState) {
                        UsenetAvailability.READY -> {
                            // JobOut carries no stream under the live contract —
                            // a ready job means "the handoff is cached; re-call
                            // /resolve to pick it up." Single targeted re-resolve
                            // for the same candidate; the backend's hot cache
                            // returns state=ready + stream immediately.
                            val hotStream = fetchHotCacheStream(contentId, candidate)
                            if (hotStream != null) {
                                StreamPathDiagnostics.record(
                                    path = StreamPlaybackPath.USENET_HANDOFF,
                                    telemetry = telemetry,
                                    context = StreamPathTelemetryContext(
                                        contentType = contentTypeFromContentId(contentId),
                                        providerCategory = "usenet",
                                    ),
                                )
                                val withStream = updated.copy(resolvedStream = hotStream)
                                onOutcome(PollOutcome.Ready(row = withStream, stream = hotStream))
                            } else {
                                // Re-resolve didn't yield a stream. Treat as
                                // failed so the VM's fallback chain advances
                                // to the next ranked candidate rather than
                                // trying to play null.
                                onOutcome(PollOutcome.Failed(row = updated.asUnavailable(nowMs())))
                            }
                            break
                        }
                        UsenetAvailability.UNAVAILABLE -> {
                            onOutcome(PollOutcome.Failed(row = updated))
                            break
                        }
                        UsenetAvailability.PREPARING -> {
                            onOutcome(PollOutcome.StillWarming(row = updated))
                            // fall through — loop continues
                        }
                    }
                }
            } finally {
                mutex.withLock {
                    if (active?.jobId == jobId) active = null
                }
            }
        }

        mutex.withLock {
            active = ActivePoll(
                contentId = contentId,
                candidateId = candidateId,
                jobId = jobId,
                job = job,
            )
        }
        return job
    }

    /**
     * Cancel any active poll. Does not touch sidecar state — the caller
     * decides whether to also update the UI (usually: no, because this
     * is called on sheet dismiss / content switch / VM clear where the
     * row just drops out of view).
     *
     * Best-effort backend cancel: when [cancelOnBackend] is true, the
     * poller asks the backend to cancel the job. Failure is swallowed.
     */
    suspend fun cancelActive(cancelOnBackend: Boolean = true) {
        val snapshot = mutex.withLock {
            val a = active
            active = null
            a
        } ?: return
        snapshot.job.cancel()
        if (cancelOnBackend) bestEffortCancel(snapshot.contentId, snapshot.candidateId)
    }

    /** Current active job id, or null. Intended for tests + the VM's "is this tick stale?" check. */
    suspend fun activeJobId(): String? = mutex.withLock { active?.jobId }

    /**
     * Single targeted re-resolve triggered the moment a poll reaches
     * `state == "ready"`. `JobOut` never carries a stream under the live
     * contract; the backend expects the caller to re-call `/resolve` to
     * pick up the handoff from the hot cache. This method keeps that
     * logic tightly inside the poller so the outer `PollOutcome.Ready`
     * shape (row + stream) is unchanged — the VM's playback path needs
     * no awareness of the extra hop.
     *
     * Returns null when:
     *  - the re-resolve network call fails,
     *  - the backend responds with something other than `state=ready`
     *    (a rare race where the hot cache was evicted between tick and
     *    resolve), or
     *  - the response is ready but carries no stream.
     * In all three cases the caller treats this as a transient Failed
     * outcome so the VM's fallback chain advances to the next candidate.
     */
    private suspend fun fetchHotCacheStream(
        contentId: String,
        candidate: UsenetCandidatePayload,
    ): UsenetResolvedStream? {
        val response = runCatching {
            repository.resolveUsenetCandidate(
                contentId = contentId,
                candidate = candidate.toDto(),
            )
        }.getOrNull() ?: return null
        if (!response.state.equals("ready", ignoreCase = true)) return null
        val dto = response.stream ?: return null
        return UsenetResolvedStream(
            url = dto.url,
            isDirect = dto.isDirect,
            supportsRange = dto.supportsRange,
            streamId = dto.streamId,
        )
    }

    private fun contentTypeFromContentId(contentId: String): String {
        val lower = contentId.lowercase()
        return when {
            "movie" in lower -> "movie"
            "series" in lower || "show" in lower || "tv" in lower -> "series"
            else -> "unknown"
        }
    }

    private suspend fun bestEffortCancel(contentId: String, candidateId: String) {
        // Candidate-scoped — the live backend's `/cancel` takes
        // `candidate_id` for targeted cancel. `content_id` is included
        // so the backend can scope its bookkeeping; `job_ids` is no
        // longer accepted by the contract.
        runCatching {
            repository.cancelUsenetWarmJobs(
                contentId = contentId,
                candidateId = candidateId,
            )
        }
    }

    private data class ActivePoll(
        val contentId: String,
        val candidateId: String,
        val jobId: String,
        val job: Job,
    )

    companion object {
        /**
         * Default cadence — 1000ms sits in the middle of the spec's
         * 750–1500ms window and matches typical network-load conventions
         * for user-initiated wait loops.
         */
        const val DEFAULT_INTERVAL_MS: Long = 1_000L

        /**
         * 5-minute bounded cap. Matches the existing PREPARING budget
         * used by Panda's 15s × 20 = 300s probe window in DetailViewModel
         * (`PREPARING_PROBE_INTERVAL_MS * PREPARING_MAX_ATTEMPTS`) so the
         * two usenet paths share the same upper bound — users hit one
         * unified "this is taking too long, pick another source" moment.
         */
        const val DEFAULT_MAX_DURATION_MS: Long = 300_000L
    }
}

/**
 * Outcome emitted by [UsenetJobPoller] per tick. Terminal variants
 * (`Ready`, `Failed`, `TimedOut`) indicate the poller has stopped;
 * `StillWarming` is a non-terminal progress update.
 */
sealed interface PollOutcome {
    val row: UsenetCandidateUiModel

    data class Ready(
        override val row: UsenetCandidateUiModel,
        val stream: UsenetResolvedStream,
    ) : PollOutcome

    data class StillWarming(override val row: UsenetCandidateUiModel) : PollOutcome

    data class Failed(override val row: UsenetCandidateUiModel) : PollOutcome

    data class TimedOut(override val row: UsenetCandidateUiModel) : PollOutcome
}

private fun UsenetCandidateUiModel.asUnavailable(now: Long): UsenetCandidateUiModel = copy(
    availabilityState = UsenetAvailability.UNAVAILABLE,
    jobId = null,
    resolvedStream = null,
    displayMessageKey = com.torve.data.usenet.model.UsenetUserMessageKey.UNAVAILABLE,
    failureMessageKey = com.torve.data.usenet.model.UsenetUserMessageKey.UNAVAILABLE,
    lastStateChangeAt = now,
)
