package com.torve.data.usenet.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format DTOs for the Torve backend's NzbDAV + Usenet-resolver APIs.
 *
 * Aligned to the live contract verified in the contract-fix sprint:
 *  - Warm + Resolve consume FULL `UsenetCandidate` objects, not bare ids.
 *  - Cancel is candidate-scoped (single `candidate_id`) or content-scoped;
 *    `job_ids` is no longer accepted.
 *  - Responses use `state`, `failure_code`, `stream`, `results`. The app
 *    no longer models or consumes `backend_phase`, `failure_reason`,
 *    `resolved`, `warm_hit`, `headers`, `expires_at_ms`.
 *
 * Backend-owned invariants:
 *  - `ResolvedStreamDto.url` is always a self-authenticating Torve handoff
 *    URL. The app treats it byte-for-byte opaque.
 *  - `WarmCandidateOutDto.state` and `ResolveResponseDto.state` use the
 *    simplified vocabulary `ready | warming | failed`. The mapper layer
 *    maps these directly to the three UI states.
 */

// ─────────────────────────────────────────────────────────────────────────
// Integrations: /integrations/nzbdav/*
// (unchanged from prior contract — these DTOs were already correct)
// ─────────────────────────────────────────────────────────────────────────

@Serializable
data class NzbdavTestRequestDto(
    @SerialName("base_url") val baseUrl: String,
    @SerialName("api_key") val apiKey: String,
)

@Serializable
data class NzbdavTestResponseDto(
    /** True if the backend could reach the configured NzbDAV instance. */
    val ok: Boolean,
    val degraded: Boolean = false,
    /** Opaque backend reason token. Never shown to users. */
    val reason: String? = null,
)

@Serializable
data class NzbdavConfigRequestDto(
    @SerialName("base_url") val baseUrl: String,
    @SerialName("api_key") val apiKey: String,
    val enabled: Boolean = true,
)

@Serializable
data class NzbdavStatusResponseDto(
    val configured: Boolean,
    @SerialName("is_enabled") val isEnabled: Boolean? = null,
    @SerialName("last_tested_at") val lastTestedAt: String? = null,
    @SerialName("last_healthy_at") val lastHealthyAt: String? = null,
    val degraded: Boolean = false,
    val reason: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────
// Shared candidate shape — required body of warm + resolve
// ─────────────────────────────────────────────────────────────────────────

/**
 * Wire-side `UsenetCandidate`. Mirrors the live backend exactly:
 *  - `candidate_id` is mandatory and opaque.
 *  - `hash_key` is mandatory; the backend uses it for dedup + routing.
 *  - `nzb_url` is optional (backend re-fetches from indexer when null).
 *
 * Domain counterpart is [com.torve.data.usenet.model.UsenetCandidatePayload]
 * (carried on `ParsedStream`); the projection between the two is a
 * one-liner — see [com.torve.data.usenet.UsenetCandidateMapping].
 */
@Serializable
data class UsenetCandidateDto(
    @SerialName("candidate_id") val candidateId: String,
    @SerialName("hash_key") val hashKey: String,
    @SerialName("nzb_url") val nzbUrl: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────
// Resolver: /resolver/usenet/warm
// ─────────────────────────────────────────────────────────────────────────

@Serializable
data class UsenetWarmRequestDto(
    @SerialName("content_id") val contentId: String,
    /** Full candidate objects — NOT a list of ids. */
    val candidates: List<UsenetCandidateDto>,
    /** Backend hint: warm only the top N. Null lets the backend decide. */
    @SerialName("top_n") val topN: Int? = null,
)

/**
 * Per-candidate result inside the warm response. State is the simplified
 * three-value vocabulary; failure_code is opaque and must NOT be rendered
 * to the user. Mapper collapses both into the UI's three states +
 * resource-key copy.
 */
@Serializable
data class WarmCandidateOutDto(
    @SerialName("candidate_id") val candidateId: String,
    /** "ready" | "warming" | "failed" */
    val state: String,
    @SerialName("job_id") val jobId: String? = null,
    /** Opaque failure-reason token. Never shown to users. */
    @SerialName("failure_code") val failureCode: String? = null,
    /**
     * Backend-suggested fallback candidates if this one is failing.
     * Always present (may be empty). Provides the full payload so the
     * caller can feed straight into resolve / warm without a lookup step.
     */
    @SerialName("fallback_suggestions") val fallbackSuggestions: List<UsenetCandidateDto> = emptyList(),
)

@Serializable
data class UsenetWarmResponseDto(
    @SerialName("content_id") val contentId: String? = null,
    /** Renamed from `candidates` to match backend `results` field. */
    val results: List<WarmCandidateOutDto> = emptyList(),
)

// ─────────────────────────────────────────────────────────────────────────
// Resolver: /resolver/usenet/resolve-nzb  (bare-URL path for browse surfaces)
// ─────────────────────────────────────────────────────────────────────────

@Serializable
data class UsenetResolveNzbRequestDto(
    @SerialName("nzb_url") val nzbUrl: String,
    /** Human-readable title forwarded to the backend for logging/dedup. */
    val title: String = "",
)

// ─────────────────────────────────────────────────────────────────────────
// Resolver: /resolver/usenet/resolve + /jobs/{id}
// ─────────────────────────────────────────────────────────────────────────

@Serializable
data class UsenetResolveRequestDto(
    @SerialName("content_id") val contentId: String,
    /** Full candidate object — NOT a bare id string. */
    val candidate: UsenetCandidateDto,
)

/**
 * Self-authenticating Torve handoff stream descriptor. The app passes
 * [url] to the player byte-for-byte; the URL itself carries auth, so
 * no headers are needed for success and the field is no longer modeled.
 *
 *  - [isDirect] true → the URL serves the bytes directly.
 *  - [supportsRange] indicates HTTP Range requests are honoured.
 *  - [streamId] backend-internal id; not consumed by the app today.
 */
@Serializable
data class ResolvedStreamDto(
    /** Opaque Torve handoff URL. Always Torve-hosted. */
    val url: String,
    @SerialName("is_direct") val isDirect: Boolean = true,
    @SerialName("supports_range") val supportsRange: Boolean = true,
    @SerialName("stream_id") val streamId: String? = null,
)

/**
 * `ResolveResponse` per the live contract. `state` is the discriminator;
 * `stream` is present iff `state == "ready"`; `job_id` is present on
 * `state == "warming"`; `failure_code` is opaque on `state == "failed"`.
 */
@Serializable
data class UsenetResolveResponseDto(
    /** "ready" | "warming" | "failed" */
    val state: String,
    @SerialName("job_id") val jobId: String? = null,
    /** Opaque failure-reason token. Never shown to users. */
    @SerialName("failure_code") val failureCode: String? = null,
    @SerialName("fallback_suggestions") val fallbackSuggestions: List<UsenetCandidateDto> = emptyList(),
    /** Present iff `state == "ready"`. Renamed from `resolved`. */
    val stream: ResolvedStreamDto? = null,
)

/**
 * `JobOut` per the live contract. Notably does NOT include `stream` —
 * a caller that observes `state == "ready"` from a job poll must
 * re-call `/resolve` to pick up the freshly-cached handoff. Fallback
 * suggestions are always present so the caller can pivot if the job
 * fails.
 */
@Serializable
data class UsenetJobStatusResponseDto(
    @SerialName("job_id") val jobId: String,
    @SerialName("content_id") val contentId: String,
    /** "ready" | "warming" | "failed" */
    val state: String,
    @SerialName("failure_code") val failureCode: String? = null,
    @SerialName("fallback_suggestions") val fallbackSuggestions: List<UsenetCandidateDto> = emptyList(),
)

// ─────────────────────────────────────────────────────────────────────────
// Resolver: /resolver/usenet/cancel
// ─────────────────────────────────────────────────────────────────────────

/**
 * Cancel may scope by [candidateId] (single targeted candidate the user
 * just abandoned) or [contentId] (whole title — leaving the detail page).
 * `user_session` is a backend-defined session correlator; the app
 * forwards it when known. The previously-modeled `job_ids` array is
 * no longer accepted by the backend.
 */
@Serializable
data class UsenetCancelRequestDto(
    @SerialName("content_id") val contentId: String? = null,
    @SerialName("candidate_id") val candidateId: String? = null,
    @SerialName("user_session") val userSession: String? = null,
)

@Serializable
data class UsenetCancelResponseDto(
    val cancelled: Int = 0,
)
