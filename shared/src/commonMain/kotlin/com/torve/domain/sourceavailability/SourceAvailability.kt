package com.torve.domain.sourceavailability

import com.torve.domain.model.MediaType

/**
 * Where the user *already* has access to a piece of content.
 *
 * Distinct from `domain.integrations.AvailabilityProvider` which models
 * external streaming-service availability (Netflix, Hulu, …) — the
 * concept here is *owned* availability: things Torve can play directly
 * because the user has them in their own download folder, Plex library,
 * or Jellyfin server.
 *
 * Phase 3 Slice A intentionally ships only the kinds where Torve can
 * actually launch playback. Debrid cache / Usenet warm / IPTV live are
 * deliberately omitted until they can return real signals.
 */
enum class SourceAvailabilityKind {
    LOCAL_DOWNLOAD,
    PLEX,
    JELLYFIN,
}

/**
 * One signal from one source. The badge is the user-facing label; the
 * rankBoost is what [SourceAvailabilityRanker] uses to re-order results.
 */
data class SourceAvailabilitySignal(
    val kind: SourceAvailabilityKind,
    val badge: String,
    val rankBoost: Int,
)

/**
 * The aggregate of every source's signal for one media item. An empty
 * [signals] list means "the user can't play this from any owned source
 * yet" — the UI surfaces those at the bottom of the list (or hides them).
 */
data class SourceAvailabilityRecord(
    val tmdbId: Int,
    val mediaType: MediaType,
    val signals: List<SourceAvailabilitySignal>,
) {
    val isAvailable: Boolean get() = signals.isNotEmpty()
    /** Highest rankBoost across all signals — used as the row's score. */
    val score: Int get() = signals.maxOfOrNull { it.rankBoost } ?: 0
    /** First signal sorted by rankBoost — primary badge to show. */
    val primaryBadge: SourceAvailabilitySignal?
        get() = signals.maxByOrNull { it.rankBoost }
}

/**
 * Default rank boosts. Higher is better — the ranker sorts descending.
 *
 *   - LOCAL_DOWNLOAD beats everything: zero network, fastest playback,
 *     guaranteed-offline.
 *   - PLEX and JELLYFIN tie for second: LAN-fast, transcoded if needed,
 *     shape parity from the user's perspective.
 *
 * Slice A leaves these as bare integer constants rather than a config
 * surface; tuning is a follow-up if real product feedback warrants it.
 */
object SourceAvailabilityRankBoost {
    const val LOCAL_DOWNLOAD = 300
    const val PLEX = 200
    const val JELLYFIN = 200
}
