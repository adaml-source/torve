package com.torve.domain.sourceavailability

import com.torve.domain.model.MediaType

/**
 * Where the user *already* has access to a piece of content.
 *
 * Distinct from `domain.integrations.AvailabilityProvider` which models
 * external streaming-service availability (Netflix, Hulu, …) — the
 * concept here is *owned* availability: things Torve can play directly
 * because the user has them in their own download folder, Plex library,
 * Jellyfin server, debrid cache, addon source, Usenet warm queue, or as
 * a currently-on-air IPTV channel.
 *
 * Prompt 8 extends the original three with five new kinds. Each new
 * kind is backed by a real provider — UNCONFIGURED-silent when the
 * user hasn't set up the matching service.
 */
enum class SourceAvailabilityKind {
    LOCAL_DOWNLOAD,
    PLEX,
    JELLYFIN,
    /** Debrid (RD/AD/PM/TB) reports the title cached and instantly playable. */
    DEBRID_CACHE,
    /** A Stremio addon advertises a stream for this title. */
    STREMIO_ADDON,
    /** Usenet stack (Panda + NzbDAV) reports a warm/ready candidate. */
    USENET_READY,
    /** IPTV: a configured channel is currently airing this title. */
    IPTV_LIVE,
    /** User has watched this before — informational, not a playback path. */
    WATCH_HISTORY,
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
 * Ordering (Prompt 8):
 *   LOCAL_DOWNLOAD > PLEX/JELLYFIN > DEBRID_CACHE > STREMIO_ADDON >
 *   USENET_READY > IPTV_LIVE > WATCH_HISTORY > (no signal — generic catalog).
 *
 * Reasoning per tier:
 *   - LOCAL_DOWNLOAD: zero network, fastest, guaranteed-offline.
 *   - PLEX / JELLYFIN: LAN-fast, transcoded if needed, owner-controlled.
 *   - DEBRID_CACHE: instant cloud playback, no torrent wait, costs only
 *     bandwidth from the debrid CDN.
 *   - STREMIO_ADDON: addon-published direct URLs (debrid-attached or
 *     addon-hosted) — usually instant once unrestrict completes.
 *   - USENET_READY: requires NzbDAV mounting / SAB / TorBox so the
 *     start-up isn't quite zero-latency, but no "wait for swarm" risk.
 *   - IPTV_LIVE: only useful when intent matches the on-air slot —
 *     scoped by the provider so it doesn't sneak into VOD-only queries.
 *   - WATCH_HISTORY: a *trail* not a *path*. Lowest boost so it never
 *     beats anything actually playable; surfaces only when the item has
 *     no other signal.
 */
object SourceAvailabilityRankBoost {
    const val LOCAL_DOWNLOAD = 300
    const val PLEX = 200
    const val JELLYFIN = 200
    const val DEBRID_CACHE = 180
    const val STREMIO_ADDON = 170
    const val USENET_READY = 150
    const val IPTV_LIVE = 130
    const val WATCH_HISTORY = 50
}
