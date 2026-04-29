package com.torve.domain.telemetry

/**
 * Stable event-name + attribute-key contract for the Usenet flow.
 *
 * Every value emitted from feature code must pass through one of the
 * builders below. The builders accept only safe types and produce
 * `Map<String, String>` — raw backend reason tokens, exception bodies,
 * and URLs are structurally unable to reach this layer because they
 * aren't parameters. Tests additionally assert this at runtime by
 * scanning emitted attribute values for known leak shapes.
 */
object UsenetTelemetryEvents {
    const val SOURCE_SHEET_OPENED: String = "usenet_source_sheet_opened"
    const val WARM_REQUESTED: String = "usenet_warm_requested"
    const val CANDIDATE_READY: String = "usenet_candidate_ready"
    const val SOURCE_SELECTED: String = "usenet_source_selected"
    const val RESOLVE_READY: String = "usenet_resolve_ready"
    const val RESOLVE_WARMING: String = "usenet_resolve_warming"
    const val RESOLVE_FAILED: String = "usenet_resolve_failed"
    const val PLAYBACK_STARTED: String = "usenet_playback_started"
    const val PLAYBACK_FAILED_EARLY: String = "usenet_playback_failed_early"
    const val FALLBACK_ATTEMPTED: String = "usenet_fallback_attempted"
    const val FALLBACK_SUCCEEDED: String = "usenet_fallback_succeeded"
    const val USER_ABANDONED_BEFORE_READY: String = "usenet_user_abandoned_before_ready"
}

object UsenetTelemetryKeys {
    const val CONTENT_ID: String = "content_id"
    const val CANDIDATE_ID: String = "candidate_id"
    const val CANDIDATE_COUNT: String = "candidate_count"
    const val WARM_SCOPE: String = "warm_scope"             // "detail" | "sheet"
    const val INITIAL_STATE: String = "initial_state"       // READY | PREPARING | UNAVAILABLE | UNKNOWN
    const val FINAL_STATE: String = "final_state"
    const val TIME_TO_READY_BUCKET: String = "time_to_ready_bucket"
    const val TIME_TO_PLAY_BUCKET: String = "time_to_play_bucket"
    const val FALLBACK_ATTEMPT_INDEX: String = "fallback_attempt_index"
    const val FALLBACK_REASON: String = "fallback_reason"   // neutral enum only: "resolve_failed" | "poll_failed" | "poll_timed_out"
    const val ABANDON_STATE: String = "abandon_state"       // coarse: PREPARING | UNAVAILABLE | NONE
}

/**
 * Coarse three-valued enum used for `initial_state` / `final_state` /
 * `abandon_state` so every emission uses the same vocabulary and never
 * reflects backend internal phases.
 */
enum class UsenetTelemetryState {
    READY, PREPARING, UNAVAILABLE, UNKNOWN, NONE;

    companion object {
        fun from(availability: com.torve.data.usenet.model.UsenetAvailability?): UsenetTelemetryState =
            when (availability) {
                com.torve.data.usenet.model.UsenetAvailability.READY -> READY
                com.torve.data.usenet.model.UsenetAvailability.PREPARING -> PREPARING
                com.torve.data.usenet.model.UsenetAvailability.UNAVAILABLE -> UNAVAILABLE
                null -> UNKNOWN
            }
    }
}

/**
 * Scope-indicator attribute for warm emissions.
 *  - `detail` — low-cost prewarm fired on detail-screen load (top 1–2).
 *  - `sheet` — expanded warmup on source-sheet open (top 3–5).
 */
enum class UsenetWarmScope(val value: String) { DETAIL("detail"), SHEET("sheet") }

/**
 * Coarse fallback-reason vocabulary. Keeps telemetry cardinality low
 * and guarantees no opaque backend token is ever surfaced.
 */
enum class UsenetFallbackReason(val value: String) {
    RESOLVE_FAILED("resolve_failed"),
    POLL_FAILED("poll_failed"),
    POLL_TIMED_OUT("poll_timed_out"),
}

/**
 * Safe attribute builder for emissions that reference a content + candidate
 * pair. Keys are constrained; no raw strings can be passed in as values.
 */
fun contentCandidateAttrs(
    contentId: String?,
    candidateId: String?,
): Map<String, String> = buildMap {
    if (!contentId.isNullOrBlank()) put(UsenetTelemetryKeys.CONTENT_ID, contentId)
    if (!candidateId.isNullOrBlank()) put(UsenetTelemetryKeys.CANDIDATE_ID, candidateId)
}
