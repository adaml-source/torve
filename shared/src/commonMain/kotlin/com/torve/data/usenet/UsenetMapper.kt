package com.torve.data.usenet

import com.torve.data.usenet.model.ResolvedStreamDto
import com.torve.data.usenet.model.UsenetAvailability
import com.torve.data.usenet.model.UsenetCandidateStates
import com.torve.data.usenet.model.UsenetCandidateUiModel
import com.torve.data.usenet.model.UsenetJobStatusResponseDto
import com.torve.data.usenet.model.UsenetResolveResponseDto
import com.torve.data.usenet.model.UsenetResolvedStream
import com.torve.data.usenet.model.UsenetUserMessageKey
import com.torve.data.usenet.model.UsenetWarmResponseDto
import com.torve.data.usenet.model.WarmCandidateOutDto

/**
 * Collapses the live backend's `state` vocabulary into the three states
 * the UI is allowed to render, and merges response updates into the
 * stable sidecar map owned by [com.torve.presentation.detail.DetailViewModel].
 *
 * Post-contract-fix invariants:
 *  1. Only [UsenetAvailability.READY], [UsenetAvailability.PREPARING],
 *     [UsenetAvailability.UNAVAILABLE] are produced.
 *  2. `failureMessageKey` / `displayMessageKey` are ALWAYS resource keys
 *     from [UsenetUserMessageKey]. Backend `failure_code` tokens never
 *     leak through.
 *  3. State transitions do not reorder candidates — merge happens by
 *     `candidateId`; ordering is the caller's responsibility.
 *  4. Preparing → Ready does not clear the previously captured
 *     `resolvedStream`; Ready → Preparing or Ready → Unavailable clears
 *     it. `jobId` is cleared on every terminal state.
 *  5. JobOut never carries a stream under the live contract; an
 *     `applyJobStatus` ready transition therefore preserves
 *     `prior.resolvedStream` (caller will re-resolve to pick up the
 *     freshly-cached handoff — wired in P3).
 */
object UsenetMapper {

    private const val STATE_READY = "ready"
    private const val STATE_WARMING = "warming"
    private const val STATE_FAILED = "failed"
    private const val STATE_CANCELLED = "cancelled"
    private const val STATE_ERROR = "error"

    // ── Warm ────────────────────────────────────────────────────────────

    /**
     * Merge a warm response into the existing sidecar map. Candidates
     * not present in the response are left untouched (stable identity).
     */
    fun mergeWarmResponse(
        existing: UsenetCandidateStates,
        response: UsenetWarmResponseDto,
        now: Long,
    ): UsenetCandidateStates {
        if (response.results.isEmpty()) return existing
        val merged = existing.toMutableMap()
        for (result in response.results) {
            val prior = existing[result.candidateId]
            merged[result.candidateId] = warmResultToUi(result, prior, now)
        }
        return merged
    }

    private fun warmResultToUi(
        dto: WarmCandidateOutDto,
        prior: UsenetCandidateUiModel?,
        now: Long,
    ): UsenetCandidateUiModel {
        val rawAvailability = resolveAvailability(
            state = dto.state,
            hasFailureCode = dto.failureCode != null,
        )
        // Anti-regression: warm responses are passive backend refreshes.
        // A candidate that's already READY is backed by a resolved handoff
        // that the row can play right now — a stale warm that re-reports
        // the same candidate as still warming should not demote the row
        // to Preparing and flicker pills. Only an explicit UNAVAILABLE
        // wins over a prior READY state.
        val availability = if (
            prior?.availabilityState == UsenetAvailability.READY &&
            rawAvailability == UsenetAvailability.PREPARING
        ) {
            UsenetAvailability.READY
        } else {
            rawAvailability
        }
        val resolvedStream = when (availability) {
            // Warm responses never carry a stream; preserve a prior READY
            // row's handoff so the row stays playable across passive refresh.
            UsenetAvailability.READY -> prior?.resolvedStream
            UsenetAvailability.PREPARING, UsenetAvailability.UNAVAILABLE -> null
        }
        val jobId = when (availability) {
            UsenetAvailability.PREPARING -> dto.jobId ?: prior?.jobId
            UsenetAvailability.READY, UsenetAvailability.UNAVAILABLE -> null
        }
        return (prior ?: seedCandidate(dto.candidateId, now)).copy(
            availabilityState = availability,
            jobId = jobId,
            resolvedStream = resolvedStream,
            displayMessageKey = displayKeyFor(availability),
            failureMessageKey = failureKeyFor(availability),
            lastStateChangeAt = if (prior?.availabilityState != availability) now else prior.lastStateChangeAt,
        )
    }

    // ── Resolve ─────────────────────────────────────────────────────────

    fun applyResolveResponse(
        prior: UsenetCandidateUiModel,
        response: UsenetResolveResponseDto,
        now: Long,
    ): UsenetCandidateUiModel {
        val availability = resolveAvailability(
            state = response.state,
            hasFailureCode = response.failureCode != null,
        )
        val resolvedStream = when (availability) {
            UsenetAvailability.READY -> response.stream?.toDomain() ?: prior.resolvedStream
            UsenetAvailability.PREPARING, UsenetAvailability.UNAVAILABLE -> null
        }
        val jobId = when (availability) {
            UsenetAvailability.PREPARING -> response.jobId ?: prior.jobId
            UsenetAvailability.READY, UsenetAvailability.UNAVAILABLE -> null
        }
        return prior.copy(
            availabilityState = availability,
            jobId = jobId,
            resolvedStream = resolvedStream,
            displayMessageKey = displayKeyFor(availability),
            failureMessageKey = failureKeyFor(availability),
            lastStateChangeAt = if (prior.availabilityState != availability) now else prior.lastStateChangeAt,
        )
    }

    // ── Job poll ────────────────────────────────────────────────────────

    /**
     * Apply a single job-status poll tick to the matching candidate.
     *
     * `JobOut` carries no `stream` field under the live contract — a
     * job that transitions to ready signals "the handoff is ready in
     * the backend's hot cache; re-call /resolve to pick it up."
     * The mapper preserves `prior.resolvedStream` on a ready transition
     * (which is null in the warming-then-poll path); the caller is
     * responsible for the re-resolve step (wired in P3).
     */
    fun applyJobStatus(
        prior: UsenetCandidateUiModel,
        response: UsenetJobStatusResponseDto,
        now: Long,
    ): UsenetCandidateUiModel {
        val availability = resolveAvailability(
            state = response.state,
            hasFailureCode = response.failureCode != null,
        )
        val resolvedStream = when (availability) {
            UsenetAvailability.READY -> prior.resolvedStream
            UsenetAvailability.PREPARING, UsenetAvailability.UNAVAILABLE -> null
        }
        val jobId = when (availability) {
            UsenetAvailability.PREPARING -> response.jobId
            UsenetAvailability.READY, UsenetAvailability.UNAVAILABLE -> null
        }
        return prior.copy(
            availabilityState = availability,
            jobId = jobId,
            resolvedStream = resolvedStream,
            displayMessageKey = displayKeyFor(availability),
            failureMessageKey = failureKeyFor(availability),
            lastStateChangeAt = if (prior.availabilityState != availability) now else prior.lastStateChangeAt,
        )
    }

    // ── Shared state collapse ───────────────────────────────────────────

    /**
     * Collapse the backend's three-value `state` vocabulary, with an
     * extra failure_code override. `failure_code` wins over the state
     * string so a partial "warming with failure_code" is treated as
     * Unavailable rather than leaving the user spinning forever.
     *
     * Unknown state strings are treated as PREPARING — the safer
     * default ("still working") so a backend rev that adds a new
     * intermediate state doesn't eject users prematurely.
     */
    private fun resolveAvailability(
        state: String?,
        hasFailureCode: Boolean,
    ): UsenetAvailability {
        if (hasFailureCode) return UsenetAvailability.UNAVAILABLE
        return when (state?.lowercase()) {
            STATE_READY -> UsenetAvailability.READY
            STATE_FAILED, STATE_CANCELLED, STATE_ERROR -> UsenetAvailability.UNAVAILABLE
            STATE_WARMING -> UsenetAvailability.PREPARING
            null -> UsenetAvailability.PREPARING
            else -> UsenetAvailability.PREPARING
        }
    }

    private fun displayKeyFor(state: UsenetAvailability): String = when (state) {
        UsenetAvailability.READY -> UsenetUserMessageKey.READY_NOW
        UsenetAvailability.PREPARING -> UsenetUserMessageKey.PREPARING
        UsenetAvailability.UNAVAILABLE -> UsenetUserMessageKey.UNAVAILABLE
    }

    private fun failureKeyFor(state: UsenetAvailability): String? = when (state) {
        UsenetAvailability.UNAVAILABLE -> UsenetUserMessageKey.UNAVAILABLE
        UsenetAvailability.READY, UsenetAvailability.PREPARING -> null
    }

    private fun seedCandidate(candidateId: String, now: Long) = UsenetCandidateUiModel(
        candidateId = candidateId,
        availabilityState = UsenetAvailability.PREPARING,
        lastStateChangeAt = now,
    )

    private fun ResolvedStreamDto.toDomain(): UsenetResolvedStream = UsenetResolvedStream(
        url = url,
        isDirect = isDirect,
        supportsRange = supportsRange,
        streamId = streamId,
    )
}
