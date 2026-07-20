package com.torve.presentation.tvhome

import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.MediaItem
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.domain.sourceavailability.SourceAvailabilityRecord

/**
 * Pure helpers for the TV Home outcome-driven rails (Prompt 11).
 *
 * Lives in `presentation/tvhome` so the platform Compose code stays a
 * thin renderer. Every function here is deterministic — given the same
 * inputs it returns the same output, no IO, no platform deps. Keeps
 * the rail-building logic testable without spinning up Compose.
 *
 * **Outcome rails this module computes:**
 *   - **Available Now**  — items the user can play right now from any
 *     owned source (LOCAL_DOWNLOAD / PLEX / JELLYFIN / DEBRID_CACHE /
 *     STREMIO_ADDON / USENET_READY / IPTV_LIVE). Watch-history-only
 *     signals are excluded because they don't represent a playable
 *     path, only a "you've seen this" trail.
 *   - **Downloads on Desktop** — items whose title matches the LAN
 *     library consumer's manifest cache. Doesn't itself open the LAN
 *     stream; the existing PlaybackRoutePreference + tile click handles
 *     that.
 *   - **Recently Added From My Sources** — addon-shelf or recently-
 *     released items already enriched with availability — i.e. the
 *     intersection of "what's new" and "what I can actually play."
 *
 * Provider Health and Live TV are not MediaItem rails — they're
 * surfaced as separate banners / tiles by the TV layer.
 */
object TvHomeRailsBuilder {

    /**
     * Filter [candidates] down to items with at least one playable
     * source-availability signal. Watch-history matches are excluded
     * (they aren't playable on their own).
     *
     * Preserves input order — the caller already sorted by recency or
     * popularity.
     */
    fun availableNow(
        candidates: List<MediaItem>,
        availability: Map<Int, SourceAvailabilityRecord>,
    ): List<MediaItem> {
        if (candidates.isEmpty() || availability.isEmpty()) return emptyList()
        return candidates.filter { item ->
            val tmdbId = item.tmdbId ?: return@filter false
            val record = availability[tmdbId] ?: return@filter false
            record.signals.any { it.isPlaybackPath() }
        }
    }

    /**
     * Filter [candidates] to items whose title matches a LAN-library
     * manifest entry. The presence check is synchronous because the
     * caller passes in the already-aggregated set of titles — we don't
     * touch the network here.
     *
     * Title matching is canonicalized (lower-cased, trimmed) so
     * differences between TMDB and the publisher don't break matches.
     */
    fun downloadsOnDesktop(
        candidates: List<MediaItem>,
        lanTitlesLowercase: Set<String>,
    ): List<MediaItem> {
        if (candidates.isEmpty() || lanTitlesLowercase.isEmpty()) return emptyList()
        return candidates.filter { item ->
            item.title.trim().lowercase() in lanTitlesLowercase
        }
    }

    /**
     * Compute the worst non-green provider-health status across [rows].
     * Drives the non-blocking TV banner: RED → "1 provider needs
     * attention. Open Settings", YELLOW → "1 provider degraded".
     * Returns null when everything is GREEN / UNCONFIGURED — the
     * banner is hidden.
     */
    fun providerBanner(rows: List<ProviderHealthEntry>): TvProviderBanner? {
        if (rows.isEmpty()) return null
        val red = rows.count { it.status == ProviderHealthStatus.RED }
        val yellow = rows.count { it.status == ProviderHealthStatus.YELLOW }
        return when {
            red > 0 -> TvProviderBanner(
                tone = TvProviderBannerTone.ERROR,
                title = if (red == 1) "1 provider needs attention" else "$red providers need attention",
                description = "Browsing keeps working. Open Settings to fix or transfer credentials.",
                redCount = red,
                yellowCount = yellow,
            )
            yellow > 0 -> TvProviderBanner(
                tone = TvProviderBannerTone.WARNING,
                title = if (yellow == 1) "1 provider degraded" else "$yellow providers degraded",
                description = "Some sources may be slower or partially configured.",
                redCount = 0,
                yellowCount = yellow,
            )
            else -> null
        }
    }

    /**
     * On-Now / Live-TV rail: every [EnrichedChannel] that has a
     * `currentProgramme` set. Channels with no current EPG entry are
     * dropped — the user is sold "On Now" and would lose trust if the
     * tile was empty. Caller takes the limit.
     *
     * Order is preserved from the input — the channels VM already
     * sorts by user pinning + recency.
     */
    fun onNow(channels: List<EnrichedChannel>): List<EnrichedChannel> {
        if (channels.isEmpty()) return emptyList()
        return channels.filter { it.currentProgramme != null }
    }

    private fun com.torve.domain.sourceavailability.SourceAvailabilitySignal.isPlaybackPath(): Boolean {
        // Watch-history is informational only. Every other kind models
        // a real playback path the user can launch.
        return this.kind != com.torve.domain.sourceavailability.SourceAvailabilityKind.WATCH_HISTORY
    }
}

/**
 * Non-blocking provider-health banner state. Rendered above the rails
 * so the user sees it without losing focus on a content tile.
 */
data class TvProviderBanner(
    val tone: TvProviderBannerTone,
    val title: String,
    val description: String,
    val redCount: Int,
    val yellowCount: Int,
)

enum class TvProviderBannerTone { ERROR, WARNING }
