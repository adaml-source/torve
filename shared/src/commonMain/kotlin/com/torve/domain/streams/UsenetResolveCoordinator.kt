package com.torve.domain.streams

import com.torve.data.usenet.UsenetCandidateMapping.toDto
import com.torve.data.usenet.UsenetMapper
import com.torve.data.usenet.UsenetRepository
import com.torve.data.usenet.model.UsenetAvailability
import com.torve.data.usenet.model.UsenetCandidatePayload
import com.torve.data.usenet.model.UsenetCandidateStates
import com.torve.data.usenet.model.UsenetCandidateUiModel
import com.torve.data.usenet.model.UsenetResolvedStream
import com.torve.domain.telemetry.NoOpTelemetryEmitter
import com.torve.domain.telemetry.StreamPathDiagnostics
import com.torve.domain.telemetry.StreamPathTelemetryContext
import com.torve.domain.telemetry.StreamPlaybackPath
import com.torve.domain.telemetry.TelemetryEmitter
import com.torve.domain.telemetry.UsenetTelemetryEvents
import com.torve.domain.telemetry.UsenetTelemetryKeys
import com.torve.domain.telemetry.UsenetTelemetryState
import com.torve.domain.telemetry.contentCandidateAttrs
import com.torve.domain.telemetry.timeBucket
import kotlinx.datetime.Clock

/**
 * Executes a single `/resolver/usenet/resolve` call and projects the
 * response onto the sidecar UI model via [UsenetMapper]. Intentionally
 * stateless across calls — the caller (DetailViewModel) owns the sidecar
 * map and decides what to do with the outcome.
 *
 * Scope boundary:
 *  - Prompt 5: this coordinator is strictly "call resolve, classify
 *    outcome, return the merged row". No polling, no fallback loop —
 *    the VM owns chain fallback for `Failed`.
 *  - Prompt 6: adds a companion polling path. The resolve coordinator's
 *    signature here is designed to accept a polling-coordinator hand-off
 *    without a shape change (outcomes are already discriminated).
 */
class UsenetResolveCoordinator(
    private val repository: UsenetRepository,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val telemetry: TelemetryEmitter = NoOpTelemetryEmitter(),
) {

    /**
     * Resolve [candidateId] for [contentId] and project the response onto
     * the sidecar row. Returns a typed outcome plus the updated sidecar
     * entry, so the caller can write the new state in a single atomic
     * `_state.update { }`.
     *
     * Resolve responses reflect user-initiated action (a row tap) — the
     * mapper is NOT subject to the warm anti-regression guard here, i.e.
     * a resolve coming back warming after a prior READY will transition
     * the row back to PREPARING. That's correct: the user asked for a
     * fresh resolve.
     */
    suspend fun resolve(
        contentId: String,
        candidate: UsenetCandidatePayload,
        existingSidecar: UsenetCandidateStates,
    ): ResolveOutcome {
        val candidateId = candidate.candidateId
        val prior = existingSidecar[candidateId] ?: UsenetCandidateUiModel(
            candidateId = candidateId,
            availabilityState = UsenetAvailability.PREPARING,
            lastStateChangeAt = nowMs(),
        )
        val initialState = UsenetTelemetryState.from(prior.availabilityState)
        val resolveStartedAt = nowMs()

        val response = runCatching {
            repository.resolveUsenetCandidate(
                contentId = contentId,
                candidate = candidate.toDto(),
            )
        }.getOrNull()

        if (response == null) {
            // Network failure is modeled as a failed outcome so the VM's
            // fallback chain still advances to the next candidate. No
            // opaque backend token is synthesized — the mapper sets a
            // resource-key failureMessageKey drawn from the allowed set.
            val failed = prior.copy(
                availabilityState = UsenetAvailability.UNAVAILABLE,
                jobId = null,
                resolvedStream = null,
                displayMessageKey = com.torve.data.usenet.model.UsenetUserMessageKey.UNAVAILABLE,
                failureMessageKey = com.torve.data.usenet.model.UsenetUserMessageKey.UNAVAILABLE,
                lastStateChangeAt = nowMs(),
            )
            emitResolveEvent(
                event = UsenetTelemetryEvents.RESOLVE_FAILED,
                contentId = contentId,
                candidateId = candidateId,
                initialState = initialState,
                finalState = UsenetTelemetryState.UNAVAILABLE,
                resolveStartedAt = resolveStartedAt,
            )
            return ResolveOutcome.Failed(row = failed)
        }

        val updated = UsenetMapper.applyResolveResponse(
            prior = prior,
            response = response,
            now = nowMs(),
        )

        return when (updated.availabilityState) {
            UsenetAvailability.READY -> {
                val stream = updated.resolvedStream
                if (stream != null) {
                    StreamPathDiagnostics.record(
                        path = StreamPlaybackPath.USENET_HANDOFF,
                        telemetry = telemetry,
                        context = StreamPathTelemetryContext(
                            contentType = contentTypeFromContentId(contentId),
                            providerCategory = "usenet",
                        ),
                    )
                    emitResolveEvent(
                        event = UsenetTelemetryEvents.RESOLVE_READY,
                        contentId = contentId,
                        candidateId = candidateId,
                        initialState = initialState,
                        finalState = UsenetTelemetryState.READY,
                        resolveStartedAt = resolveStartedAt,
                    )
                    ResolveOutcome.Ready(row = updated, stream = stream)
                } else {
                    // Defensive: mapper says READY but no stream survived.
                    // Treat as Failed so the VM moves on rather than trying
                    // to play null.
                    emitResolveEvent(
                        event = UsenetTelemetryEvents.RESOLVE_FAILED,
                        contentId = contentId,
                        candidateId = candidateId,
                        initialState = initialState,
                        finalState = UsenetTelemetryState.UNAVAILABLE,
                        resolveStartedAt = resolveStartedAt,
                    )
                    ResolveOutcome.Failed(row = updated.copy(
                        availabilityState = UsenetAvailability.UNAVAILABLE,
                        displayMessageKey = com.torve.data.usenet.model.UsenetUserMessageKey.UNAVAILABLE,
                        failureMessageKey = com.torve.data.usenet.model.UsenetUserMessageKey.UNAVAILABLE,
                    ))
                }
            }
            UsenetAvailability.PREPARING -> {
                emitResolveEvent(
                    event = UsenetTelemetryEvents.RESOLVE_WARMING,
                    contentId = contentId,
                    candidateId = candidateId,
                    initialState = initialState,
                    finalState = UsenetTelemetryState.PREPARING,
                    resolveStartedAt = resolveStartedAt,
                )
                ResolveOutcome.Warming(row = updated, jobId = updated.jobId)
            }
            UsenetAvailability.UNAVAILABLE -> {
                emitResolveEvent(
                    event = UsenetTelemetryEvents.RESOLVE_FAILED,
                    contentId = contentId,
                    candidateId = candidateId,
                    initialState = initialState,
                    finalState = UsenetTelemetryState.UNAVAILABLE,
                    resolveStartedAt = resolveStartedAt,
                )
                ResolveOutcome.Failed(row = updated)
            }
        }
    }

    private fun emitResolveEvent(
        event: String,
        contentId: String,
        candidateId: String,
        initialState: UsenetTelemetryState,
        finalState: UsenetTelemetryState,
        resolveStartedAt: Long,
    ) {
        runCatching {
            telemetry.emit(
                event = event,
                attributes = contentCandidateAttrs(contentId, candidateId) + mapOf(
                    UsenetTelemetryKeys.INITIAL_STATE to initialState.name,
                    UsenetTelemetryKeys.FINAL_STATE to finalState.name,
                    UsenetTelemetryKeys.TIME_TO_READY_BUCKET to timeBucket(nowMs() - resolveStartedAt),
                ),
            )
        }
    }

    private fun contentTypeFromContentId(contentId: String): String {
        val lower = contentId.lowercase()
        return when {
            "movie" in lower -> "movie"
            "series" in lower || "show" in lower || "tv" in lower -> "series"
            else -> "unknown"
        }
    }
}

/**
 * Narrow outcome discriminator for a single resolve call. The VM pattern-
 * matches on this to decide: launch player / wait / fall back. Exposes
 * only the minimum the VM needs — the full DTO never leaks out.
 */
sealed interface ResolveOutcome {
    val row: UsenetCandidateUiModel

    /** Backend returned a handoff; player can launch right now. */
    data class Ready(
        override val row: UsenetCandidateUiModel,
        val stream: UsenetResolvedStream,
    ) : ResolveOutcome

    /**
     * Backend is still working. [jobId] may be null (unusual — the
     * caller should not synthesize one) but is forwarded when present so
     * Prompt 6's poller can pick it up.
     */
    data class Warming(
        override val row: UsenetCandidateUiModel,
        val jobId: String?,
    ) : ResolveOutcome

    /** Backend explicitly can't deliver — or the call itself failed. */
    data class Failed(
        override val row: UsenetCandidateUiModel,
    ) : ResolveOutcome
}
