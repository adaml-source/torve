package com.torve.domain.streams

import com.torve.data.addon.ParsedStream
import com.torve.data.usenet.UsenetCandidateMapping.toDto
import com.torve.data.usenet.UsenetRepository
import com.torve.data.usenet.model.UsenetCandidatePayload
import com.torve.data.usenet.model.UsenetWarmResponseDto
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.telemetry.NoOpTelemetryEmitter
import com.torve.domain.telemetry.TelemetryEmitter
import com.torve.domain.telemetry.UsenetTelemetryEvents
import com.torve.domain.telemetry.UsenetTelemetryKeys
import com.torve.domain.telemetry.UsenetWarmScope
import com.torve.domain.telemetry.contentCandidateAttrs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fires a one-shot prewarm against `POST /resolver/usenet/warm` when a
 * detail screen finishes resolving its top source candidates. Scope is
 * deliberately narrow:
 *
 *  - Caller (DetailViewModel) passes a stable `contentId` string +
 *    the top 1–2 backend candidate ids. The coordinator calls
 *    [UsenetRepository.warmUsenetCandidates] once per unique
 *    (contentId, candidateIds) pair per session.
 *  - No polling, no resolve, no state propagation — the warm response
 *    is intentionally discarded in Prompt 3. Prompt 4 will layer on the
 *    expanded warmup from source-sheet open; Prompts 5–6 layer on
 *    resolve + polling.
 *  - Lifecycle-safe: singleton instance, coordinator holds only a small
 *    dedup key; cleanup is explicit via [clearForContent].
 *
 * The dedup key includes the full candidate-id list rather than just
 * contentId because ranking may reveal a different top-N as the stream
 * list fills in (startup cache → full fetch). Callers can safely fire
 * [prewarm] on every stream-list update; only distinct top sets hit the
 * network.
 */
class UsenetWarmCoordinator(
    private val repository: UsenetRepository,
    private val telemetry: TelemetryEmitter = NoOpTelemetryEmitter(),
) {
    private val mutex = Mutex()

    // Two independent dedup slots: the detail-open prewarm (top 1–2) and
    // the source-sheet-open warmup (top 3–5). Separate keys / responses
    // are intentional — they have different semantics and different
    // retry boundaries. Overlap at the backend is fine: the backend has
    // its own warm-success cache.
    private var lastKey: WarmKey? = null
    private var lastResponse: UsenetWarmResponseDto? = null
    private var lastSheetKey: WarmKey? = null
    private var lastSheetResponse: UsenetWarmResponseDto? = null

    /**
     * Issue a prewarm for [contentId] using [candidateIds] (callers pass
     * the ranked top N, the coordinator trims to [MAX_PREWARM_CANDIDATES]).
     *
     * Empty [candidateIds] is an intentional no-op — it means "no Usenet
     * rows in the current stream list," which is the common case today.
     *
     * Response is retained internally (via [lastWarmResponse]) so the
     * caller can optionally consume it when Prompt 4's sidecar population
     * lands. Prompt 3 does not read it.
     */
    suspend fun prewarm(
        contentId: String,
        candidates: List<UsenetCandidatePayload>,
    ): UsenetWarmResponseDto? {
        if (contentId.isBlank()) return null
        val trimmed = candidates
            .asSequence()
            .filter { it.candidateId.isNotBlank() && it.hashKey.isNotBlank() }
            .distinctBy { it.candidateId }
            .take(MAX_PREWARM_CANDIDATES)
            .toList()
        if (trimmed.isEmpty()) return null

        val key = WarmKey(contentId = contentId, candidateIds = trimmed.map { it.candidateId })
        mutex.withLock {
            if (key == lastKey) return lastResponse
            lastKey = key
        }

        emitSafely(
            event = UsenetTelemetryEvents.WARM_REQUESTED,
            attributes = contentCandidateAttrs(contentId = contentId, candidateId = null) + mapOf(
                UsenetTelemetryKeys.WARM_SCOPE to UsenetWarmScope.DETAIL.value,
                UsenetTelemetryKeys.CANDIDATE_COUNT to trimmed.size.toString(),
            ),
        )

        val response = runCatching {
            repository.warmUsenetCandidates(
                contentId = contentId,
                candidates = trimmed.map { it.toDto() },
            )
        }.getOrNull()

        mutex.withLock {
            if (lastKey == key) lastResponse = response
        }
        if (response != null) emitCandidateReadyEvents(contentId, response)
        return response
    }

    /**
     * Expanded warmup for the source sheet — user is actively browsing
     * sources, so we warm a wider top-N (up to [MAX_SHEET_WARM_CANDIDATES]).
     * Separate dedup slot from [prewarm] so the two paths don't collide
     * when their top-N sets overlap.
     *
     * Returns the warm response so the caller (DetailViewModel) can merge
     * it into the sidecar map via [com.torve.data.usenet.UsenetMapper].
     */
    suspend fun prewarmForSheet(
        contentId: String,
        candidates: List<UsenetCandidatePayload>,
    ): UsenetWarmResponseDto? {
        if (contentId.isBlank()) return null
        val trimmed = candidates
            .asSequence()
            .filter { it.candidateId.isNotBlank() && it.hashKey.isNotBlank() }
            .distinctBy { it.candidateId }
            .take(MAX_SHEET_WARM_CANDIDATES)
            .toList()
        if (trimmed.isEmpty()) return null

        val key = WarmKey(contentId = contentId, candidateIds = trimmed.map { it.candidateId })
        mutex.withLock {
            if (key == lastSheetKey) return lastSheetResponse
            lastSheetKey = key
        }

        emitSafely(
            event = UsenetTelemetryEvents.WARM_REQUESTED,
            attributes = contentCandidateAttrs(contentId = contentId, candidateId = null) + mapOf(
                UsenetTelemetryKeys.WARM_SCOPE to UsenetWarmScope.SHEET.value,
                UsenetTelemetryKeys.CANDIDATE_COUNT to trimmed.size.toString(),
            ),
        )

        val response = runCatching {
            repository.warmUsenetCandidates(
                contentId = contentId,
                candidates = trimmed.map { it.toDto() },
            )
        }.getOrNull()

        mutex.withLock {
            if (lastSheetKey == key) lastSheetResponse = response
        }
        if (response != null) emitCandidateReadyEvents(contentId, response)
        return response
    }

    /**
     * Called when the user navigates away from (or to a different) detail
     * page. Drops both dedup entries (detail prewarm + sheet warmup) so a
     * later revisit can re-warm, and best-effort asks the backend to
     * cancel any in-flight warms for this content. Network failure is
     * swallowed — this is cleanup, not work.
     */
    suspend fun clearForContent(contentId: String) {
        if (contentId.isBlank()) return
        mutex.withLock {
            if (lastKey?.contentId == contentId) {
                lastKey = null
                lastResponse = null
            }
            if (lastSheetKey?.contentId == contentId) {
                lastSheetKey = null
                lastSheetResponse = null
            }
        }
        // Content-scoped cancel — the live backend's `/cancel` accepts
        // content_id alone for "wipe all outstanding work for this title."
        runCatching { repository.cancelUsenetWarmJobs(contentId = contentId) }
    }

    /** Most recent warm response for the currently-deduped key, if any. */
    suspend fun lastWarmResponse(): UsenetWarmResponseDto? = mutex.withLock { lastResponse }

    /** Most recent sheet-warm response for the currently-deduped key, if any. */
    suspend fun lastSheetWarmResponse(): UsenetWarmResponseDto? = mutex.withLock { lastSheetResponse }

    /** Internal — exposed only to unit tests. */
    internal suspend fun currentKeyForTest(): WarmKey? = mutex.withLock { lastKey }

    /** Internal — exposed only to unit tests. */
    internal suspend fun currentSheetKeyForTest(): WarmKey? = mutex.withLock { lastSheetKey }

    /**
     * Scan a warm response for candidates whose `state == "ready"` and
     * emit one [UsenetTelemetryEvents.CANDIDATE_READY] per row. Only
     * `content_id` and `candidate_id` flow into the emission — the
     * live backend no longer surfaces a `warm_hit` signal so that
     * attribute is omitted.
     */
    private fun emitCandidateReadyEvents(
        contentId: String,
        response: UsenetWarmResponseDto,
    ) {
        for (result in response.results) {
            if (!result.state.equals("ready", ignoreCase = true)) continue
            emitSafely(
                event = UsenetTelemetryEvents.CANDIDATE_READY,
                attributes = contentCandidateAttrs(
                    contentId = contentId,
                    candidateId = result.candidateId,
                ),
            )
        }
    }

    private fun emitSafely(event: String, attributes: Map<String, String>) {
        runCatching { telemetry.emit(event, attributes) }
    }

    internal data class WarmKey(
        val contentId: String,
        val candidateIds: List<String>,
    )

    companion object {
        /** Top N to warm on detail open. Per spec: 1–2. */
        const val MAX_PREWARM_CANDIDATES = 2

        /** Top N to warm on source-sheet open. Per spec: 3–5. */
        const val MAX_SHEET_WARM_CANDIDATES = 5
    }
}

/**
 * Extract the backend-issued candidate id from a source row, or null if
 * the row is not a Usenet candidate. Prefers the structured payload
 * (the canonical post-contract source of truth) and falls back to
 * `accelerationSourceKey` for rows that pre-date the payload field.
 */
fun ParsedStream.usenetCandidateIdOrNull(): String? {
    if (accelerationProvenanceKind != CandidateProvenanceKind.USENET_NZBDAV) return null
    val fromPayload = usenetCandidate?.candidateId?.takeIf { it.isNotBlank() }
    if (fromPayload != null) return fromPayload
    return accelerationSourceKey?.takeIf { it.isNotBlank() }
}

/**
 * Returns the full payload required by the live backend's
 * `/resolver/usenet/warm` and `/resolver/usenet/resolve` endpoints.
 *
 * Returns null when:
 *  - the row is not USENET_NZBDAV provenance, or
 *  - the row carries no payload (legacy / pre-contract-fix mappers
 *    that only set `accelerationSourceKey`), or
 *  - the payload is missing the mandatory `hash_key`.
 *
 * Callers MUST treat null as "this row cannot be sent to the backend"
 * and fall through to the next ranked candidate. Synthesizing a payload
 * from incomplete data would produce silently broken warm/resolve calls.
 */
fun ParsedStream.usenetCandidatePayloadOrNull(): com.torve.data.usenet.model.UsenetCandidatePayload? {
    if (accelerationProvenanceKind != CandidateProvenanceKind.USENET_NZBDAV) return null
    val payload = usenetCandidate ?: return null
    if (payload.candidateId.isBlank() || payload.hashKey.isBlank()) return null
    return payload
}

/**
 * Canonical content-id string consumed by [UsenetWarmCoordinator.prewarm].
 *
 * Format is app-owned; the backend echoes it back on warm/resolve
 * responses. Chosen to be stable across app sessions for a given title +
 * episode so the backend's warm-success cache can survive a process
 * restart.
 */
fun formatUsenetContentId(
    type: String,
    tmdbId: Int?,
    season: Int? = null,
    episode: Int? = null,
): String? {
    if (tmdbId == null) return null
    val normalizedType = type.lowercase().ifBlank { return null }
    return buildString {
        append(normalizedType)
        append(':')
        append(tmdbId)
        if (season != null && episode != null) {
            append(":s")
            append(season)
            append(":e")
            append(episode)
        }
    }
}
