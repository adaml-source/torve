package com.torve.domain.providerhealth

import kotlinx.serialization.Serializable

/**
 * Categories of providers tracked by the credential-first setup flow.
 *
 * One category may have multiple entries — e.g. DEBRID can have one entry
 * per service the user has linked (Real-Debrid, AllDebrid, …). The
 * [ProviderHealthEntry.providerKey] disambiguates within a category.
 */
@Serializable
enum class ProviderHealthCategory {
    DEBRID,
    IPTV,
    PLEX_JELLYFIN,
    USENET_INDEXER,
    USENET_PROVIDER,
    DOWNLOAD_CLIENT,
    ADDON,
    EPG,
    TRAKT,
    SIMKL,
    PLAYBACK,
}

/**
 * Traffic-light status for a provider entry.
 *
 *   - [GREEN]:        last check succeeded, ready to use.
 *   - [YELLOW]:       partial / setup incomplete / degraded; usable but
 *                     wants attention.
 *   - [RED]:          credential invalid, provider unreachable, or
 *                     unsupported config; user must fix something.
 *   - [UNCONFIGURED]: user hasn't supplied this credential yet (no
 *                     check has ever been attempted).
 *   - [UNKNOWN]:      check pending — UI shows a spinner.
 */
@Serializable
enum class ProviderHealthStatus {
    GREEN,
    YELLOW,
    RED,
    UNCONFIGURED,
    UNKNOWN,
}

/**
 * One row in the provider health table. Designed to round-trip through
 * [com.torve.domain.repository.PreferencesRepository] as JSON so a fresh
 * launch can render the last-known state without re-running checks.
 */
@Serializable
data class ProviderHealthEntry(
    val category: ProviderHealthCategory,
    /** Stable id within a category. e.g. "real-debrid", "scenenzbs|https://…", "plex". */
    val providerKey: String,
    /** Display label shown in the UI. */
    val label: String,
    val status: ProviderHealthStatus,
    /** Epoch millis of the most recent check; null if never checked. */
    val lastCheckedAt: Long? = null,
    /**
     * Short human-readable line shown under the row. For RED, this should
     * be the actionable error (e.g. "401 unauthorized — re-enter API key").
     * MUST NOT contain secret values.
     */
    val message: String? = null,
    /**
     * What the user should do next. Optional — when present the UI renders
     * an inline action button with this label.
     */
    val nextAction: String? = null,
)
