package com.torve.data.usenet.model

/**
 * Simplified, user-facing availability for a Usenet source row.
 *
 * The backend exposes a richer internal phase vocabulary — `submitting`,
 * `accepted`, `checking`, `repairing`, `extracting`, etc. — but the UI
 * must only ever show these three states. All mapping from backend phases
 * and failure tokens happens in [com.torve.data.usenet.UsenetMapper]; any
 * code path that lets a raw phase or opaque reason token reach a
 * user-facing surface is a bug.
 */
enum class UsenetAvailability {
    /** Handoff is cached/warmed — pressing play starts immediately. */
    READY,

    /** Backend is still working — UI waits briefly and intelligently. */
    PREPARING,

    /** Backend cannot deliver this candidate — UI should move on. */
    UNAVAILABLE,
}

/**
 * Resource-key contract for the small set of user-facing strings the spec
 * allows. Kept as string IDs so platform UIs can localize; Android resolves
 * these against `values/strings.xml`, iOS against its `Localizable.strings`.
 *
 * Only these five strings are permitted — anything else risks leaking
 * backend semantics into the UI.
 */
object UsenetUserMessageKey {
    /** "Ready now" — shown on Ready rows when an explicit status line is useful. */
    const val READY_NOW: String = "usenet_row_ready_now"

    /** "Preparing" — shown on Preparing rows as a compact inline status. */
    const val PREPARING: String = "usenet_row_preparing"

    /** "Unavailable" — shown on Unavailable rows. */
    const val UNAVAILABLE: String = "usenet_row_unavailable"

    /** "Trying next source" — transient copy during fallback (Prompt 5). */
    const val TRYING_NEXT_SOURCE: String = "usenet_trying_next_source"

    /** "Playback link expired, refreshing" — transient copy on expiry (Prompt 5). */
    const val PLAYBACK_LINK_EXPIRED: String = "usenet_playback_link_expired"
}

/**
 * Domain-flavored unwrapped [com.torve.data.usenet.model.ResolvedStreamDto].
 *
 * Intentionally distinct from [com.torve.domain.model.ResolvedStream]
 * (which models debrid/addon-hosted streams without headers) because the
 * Usenet handoff carries headers the player must include and an opaque
 * URL the app must never parse.
 *
 * Naming the class [UsenetResolvedStream] rather than overloading the
 * existing [ResolvedStream] avoids import ambiguity in any call site that
 * already touches debrid resolution.
 */
data class UsenetResolvedStream(
    /**
     * Opaque, self-authenticating Torve handoff URL. Passed to the player
     * byte-for-byte. Never parsed, never unwrapped.
     */
    val url: String,
    /** True when the URL serves bytes directly. */
    val isDirect: Boolean = true,
    /** True when HTTP Range requests are honoured by the handoff. */
    val supportsRange: Boolean = true,
    /** Backend-internal stream id; not consumed by the app today. */
    val streamId: String? = null,
)

/**
 * Stable, UI-facing snapshot of one Usenet candidate row.
 *
 * This is the sidecar model that the Source Sheet reads *alongside* the
 * existing `ParsedStream` list (keyed by `candidateId`). The ParsedStream
 * continues to drive quality/size/badge/ranking in the shared source list;
 * this sidecar provides the 3-state availability + handoff + job id that
 * the backend resolver owns.
 *
 * Stability contract (enforced by [com.torve.data.usenet.UsenetMapper]):
 *  - [candidateId] is the stable identity for diffing; never change it.
 *  - Updating one candidate must never require rebuilding neighbors.
 *  - [availabilityState] transitions must never reorder rows on their own —
 *    Prompt 4/5 pairs with existing ranking, which is the sole re-ranker.
 *  - [displayMessageKey] is a resource-key string, not a backend token.
 *  - [failureMessageKey] is set only when [availabilityState] is
 *    [UsenetAvailability.UNAVAILABLE]; it is always a resource key, never
 *    a raw backend failure-reason string.
 */
data class UsenetCandidateUiModel(
    /** Stable identity from the backend. Used for list diffs and VM lookups. */
    val candidateId: String,
    /** Optional short title. Usually mirrors the ParsedStream display title. */
    val title: String? = null,
    /** e.g. "1080p", "2160p". Null if backend didn't supply quality. */
    val qualityLabel: String? = null,
    /** Human-readable size, e.g. "5.0 GB". Null if unknown. */
    val sizeLabel: String? = null,
    /** Short source badge, e.g. "Usenet". */
    val badge: String? = null,
    /** The only three values ever rendered. See [UsenetAvailability]. */
    val availabilityState: UsenetAvailability,
    /** Sticky preference flag from ranking (preserved across updates). */
    val isPreferred: Boolean = false,
    /** True while this row is the user-selected candidate. */
    val isSelected: Boolean = false,
    /** Backend job id during PREPARING. Cleared on terminal states. */
    val jobId: String? = null,
    /** Populated only on READY. Cleared whenever state drifts away. */
    val resolvedStream: UsenetResolvedStream? = null,
    /**
     * Resource key for the status line shown under the row, e.g.
     * [UsenetUserMessageKey.PREPARING]. Null when the row needs no copy.
     */
    val displayMessageKey: String? = null,
    /**
     * Resource key for a persistent UNAVAILABLE explanation. Null for
     * READY/PREPARING. MUST remain a resource key, never a backend token.
     */
    val failureMessageKey: String? = null,
    /** Epoch ms of the most recent availabilityState change. */
    val lastStateChangeAt: Long,
) {
    /** Convenience: true if pressing play would start playback immediately. */
    val isImmediatelyPlayable: Boolean
        get() = availabilityState == UsenetAvailability.READY && resolvedStream != null
}

/**
 * The sidecar map exposed from `DetailUiState`. Keyed by [candidateId] so
 * the UI can look up a row's Usenet state cheaply without scanning.
 *
 * An empty map is the "no Usenet candidates" state — existing addon/debrid
 * rows render exactly as before. That's the default in Prompt 2; the VM
 * only populates this once warm/resolve coordinators land in later prompts.
 */
typealias UsenetCandidateStates = Map<String, UsenetCandidateUiModel>

/**
 * App-side carrier for the full backend `UsenetCandidate` payload.
 *
 * The live Torve backend's `/resolver/usenet/warm` and `/resolver/usenet/resolve`
 * endpoints both require a full candidate object — `candidate_id` alone is
 * not enough. Every USENET_NZBDAV `ParsedStream` row therefore needs to
 * preserve the [hashKey] (mandatory) and optional [nzbUrl] alongside the
 * id so the coordinator can reconstruct the request body.
 *
 * Kept as a plain domain `data class` (not `@Serializable`) so it can sit
 * on the (non-serializable) `ParsedStream` without forcing wire-format
 * coupling. The DTO mapper in P2 will project this to the wire-side
 * `UsenetCandidate`.
 */
data class UsenetCandidatePayload(
    /** Backend-issued, opaque to the app. Same string the row exposes via `usenetCandidateIdOrNull()`. */
    val candidateId: String,
    /**
     * Mandatory deterministic hash the backend uses to dedup and route
     * the candidate. Without this, warm/resolve cannot proceed — rows
     * missing it should not be sent to the backend (filter at the call
     * site, not at construction).
     */
    val hashKey: String,
    /**
     * Optional NZB URL when the originating addon already has it. The
     * backend can re-fetch from its own indexer when null.
     */
    val nzbUrl: String? = null,
)
