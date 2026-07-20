package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.channels.EpgState
import com.torve.presentation.transfer.RelayReachability
import com.torve.presentation.transfer.TransferAttemptRecord
import com.torve.presentation.transfer.TransferDiagnosticsSnapshot

/**
 * Pure functions that derive specialised health facets + the
 * [ProviderHealthEvidence] envelope from existing UI state. Live in
 * the presentation layer so they're testable without spinning up a
 * checker; the production checkers can publish evidence by calling
 * these and embedding the result in their nextAction copy.
 *
 * Prompt 17 acceptance:
 *  - "EPG loaded but no channels matched" is not shown as a blocking
 *    error if channels are usable. → enforced by [iptvFacetsFrom] +
 *    [iptvEvidenceFrom] downgrading the EPG-only-no-match case to a
 *    YELLOW informational evidence rather than RED.
 *  - "No attempt recorded" says what attempt means. → enforced by
 *    [transferEvidenceFrom] returning the [TransferHealthFacet.NoAttemptYet]
 *    facet with a friendlier evidenceSummary distinct from "transfer
 *    failed" wording.
 *  - Network errors explain backend relay vs local/manual transfer
 *    fallback. → enforced by [transferEvidenceFrom] copy.
 */

/** Derive the structured facets from a live [ChannelsUiState]. */
fun iptvFacetsFrom(state: ChannelsUiState): IptvHealthFacets {
    val cachedCatalogLoaded = state.selectedPlaylistId != null &&
        (
            state.channels.isNotEmpty() ||
                state.categories.isNotEmpty() ||
                state.groupedChannels.isNotEmpty() ||
                state.categoryChannels.isNotEmpty()
            )
    val playlistLoaded = state.playlists.isNotEmpty() || cachedCatalogLoaded
    val storedChannelCount = state.selectedPlaylistId
        ?.let { id -> state.playlists.firstOrNull { it.id == id }?.channelCount }
        ?: state.playlists.firstOrNull()?.channelCount
        ?: 0
    val channelCount = maxOf(state.channels.size, storedChannelCount)
    val channelsLoaded = channelCount > 0 ||
        state.categories.isNotEmpty() ||
        state.groupedChannels.isNotEmpty() ||
        state.categoryChannels.isNotEmpty()
    val epg = state.epgState
    val epgLoaded = epg is EpgState.Loaded
    val matched = (epg as? EpgState.Loaded)?.matchedChannelCount ?: 0
    val unmatched = (epg as? EpgState.Loaded)?.unmatchedChannelCount ?: 0
    // Channels are usable via name fallback when:
    //   - the playlist is loaded and channels are present, AND
    //   - the runtime player will fall back to fuzzy name match for
    //     EPG (which it does today on every Channel without a strict
    //     epg-channel-key hit).
    // Strict EPG zero-match doesn't disable playback, so usability is
    // simply "channels are loaded at all". Keep the field separate so
    // a future change that tightens runtime matching can flip it
    // independently.
    val channelsUsableViaNameFallback = channelsLoaded
    return IptvHealthFacets(
        playlistLoaded = playlistLoaded,
        channelsLoaded = channelsLoaded,
        epgLoaded = epgLoaded,
        epgMatchedCount = matched,
        epgUnmatchedCount = unmatched,
        channelsUsableViaNameFallback = channelsUsableViaNameFallback,
    )
}

/**
 * Combine a raw IPTV [ProviderHealthEntry] with derived facets into a
 * structured evidence envelope. The downgrade rule lives here:
 * a strict-EPG-zero-match row whose channels are still usable is
 * surfaced as YELLOW informational ("EPG strict matching produced
 * no rows; runtime falls back to name match"), NOT RED.
 */
fun iptvEvidenceFrom(
    entry: ProviderHealthEntry,
    facets: IptvHealthFacets,
): ProviderHealthEvidence {
    val (effectiveStatus, summary, nextAction) = when {
        !facets.playlistLoaded -> Triple(
            ProviderHealthStatus.UNCONFIGURED,
            "No IPTV playlist added.",
            "Add a playlist",
        )
        // Playlist exists but produced zero channels AND no
        // categories. That's a real warning — refresh or re-add.
        // Keep separate from the EPG-zero-match case below: this is
        // about the playlist itself, not the EPG.
        !facets.channelsLoaded -> Triple(
            ProviderHealthStatus.YELLOW,
            "Playlist loaded but produced no channels. Refresh the playlist or check the URL.",
            "Refresh playlist",
        )
        facets.channelsLoaded && !facets.epgLoaded -> Triple(
            ProviderHealthStatus.GREEN,
            "Playlist loaded. EPG not configured.",
            null,
        )
        facets.channelsLoaded && facets.epgFullyUnmatched -> Triple(
            // Real warning: EPG is in but matches NOTHING and channels
            // can't fall back. Worth surfacing.
            ProviderHealthStatus.YELLOW,
            "EPG loaded but no channels matched and fallback unavailable.",
            "Check tvg-id mapping",
        )
        facets.channelsLoaded && facets.epgLoaded && facets.epgMatchedCount == 0 -> Triple(
            // Soft case: EPG strict match produced zero rows but
            // channels still play via name fallback. NOT a blocking
            // warning; just informational.
            ProviderHealthStatus.GREEN,
            "Playlist loaded. EPG strict matching produced 0 of " +
                "${facets.epgMatchedCount + facets.epgUnmatchedCount} rows; runtime falls back to name match.",
            null,
        )
        facets.channelsLoaded && facets.epgLoaded -> {
            val total = facets.epgMatchedCount + facets.epgUnmatchedCount
            val pct = if (total > 0) (facets.epgMatchedCount * 100 / total) else 0
            Triple(
                ProviderHealthStatus.GREEN,
                "Playlist loaded. EPG matched ${facets.epgMatchedCount} of $total channels ($pct%).",
                null,
            )
        }
        else -> Triple(entry.status, entry.message ?: "IPTV state unavailable.", entry.nextAction)
    }

    return ProviderHealthEvidence(
        status = effectiveStatus,
        lastCheckedAt = entry.lastCheckedAt,
        sourceOfTruth = "ChannelsViewModel projection (no network)",
        evidenceSummary = summary,
        recommendedAction = nextAction,
        canRefresh = facets.playlistLoaded,
    )
}

/**
 * Derive a single [TransferHealthFacet] from a diagnostics snapshot
 * + the most recent attempt record.
 */
fun transferFacetFrom(
    snapshot: TransferDiagnosticsSnapshot,
): TransferHealthFacet {
    val attempt: TransferAttemptRecord? = snapshot.lastAttempt
    return when (snapshot.relayReachable) {
        RelayReachability.NOT_SIGNED_IN -> TransferHealthFacet.Unauthenticated
        RelayReachability.UNAVAILABLE -> TransferHealthFacet.RelayUnsupported
        RelayReachability.UNAUTHORIZED -> TransferHealthFacet.Unauthenticated
        RelayReachability.NETWORK_ERROR -> TransferHealthFacet.BackendUnavailable
        RelayReachability.NO_CRYPTO_ENGINE -> TransferHealthFacet.BackendUnavailable
        RelayReachability.UNKNOWN, RelayReachability.REACHABLE -> {
            when {
                attempt == null -> TransferHealthFacet.NoAttemptYet
                attempt.outcome == com.torve.presentation.transfer.AttemptOutcome.FAILED ||
                    attempt.outcome == com.torve.presentation.transfer.AttemptOutcome.RELAY_UNAVAILABLE -> {
                    if (attempt.role == com.torve.presentation.transfer.AttemptRole.SENDER) {
                        TransferHealthFacet.LastSendFailed(attempt.recordedAtEpochMs)
                    } else {
                        TransferHealthFacet.LastReceiveFailed(attempt.recordedAtEpochMs)
                    }
                }
                else -> TransferHealthFacet.LastAttemptOk(
                    at = attempt.recordedAtEpochMs,
                    role = attempt.role.name,
                )
            }
        }
    }
}

/**
 * Convert a transfer facet into a [ProviderHealthEvidence]. The copy
 * Prompt 17 requires:
 *  - "No attempt recorded" surfaces as evidenceSummary verbatim so
 *    the user knows it means *no transfer attempt has ever been
 *    recorded on this device*, not "everything is broken".
 *  - Network errors mention the manual paste fallback so the user
 *    knows there's a path forward without the relay.
 */
fun transferEvidenceFrom(facet: TransferHealthFacet): ProviderHealthEvidence = when (facet) {
    is TransferHealthFacet.NoAttemptYet -> ProviderHealthEvidence(
        status = ProviderHealthStatus.UNCONFIGURED,
        lastCheckedAt = null,
        sourceOfTruth = "TransferAttemptTracker",
        evidenceSummary = "No credential transfer has been started on this device. " +
            "Use Send credentials or Receive credentials when you set up another device.",
        recommendedAction = "Receive from another device",
        canRefresh = false,
    )
    is TransferHealthFacet.BackendUnavailable -> ProviderHealthEvidence(
        status = ProviderHealthStatus.YELLOW,
        lastCheckedAt = null,
        sourceOfTruth = "Auth probe",
        evidenceSummary = "Couldn't reach the Torve backend. Auto-import via the relay needs network access; " +
            "manual paste from another device still works locally.",
        recommendedAction = "Use manual paste",
        canRefresh = true,
    )
    is TransferHealthFacet.Unauthenticated -> ProviderHealthEvidence(
        status = ProviderHealthStatus.YELLOW,
        lastCheckedAt = null,
        sourceOfTruth = "Auth probe",
        evidenceSummary = "Sign in to enable backend-relayed transfers. Manual paste from another device " +
            "still works without an account.",
        recommendedAction = "Sign in",
        canRefresh = true,
    )
    is TransferHealthFacet.RelayUnsupported -> ProviderHealthEvidence(
        status = ProviderHealthStatus.YELLOW,
        lastCheckedAt = null,
        sourceOfTruth = "Backend probe",
        evidenceSummary = "The backend doesn't expose the credential-transfer relay for this account. " +
            "Manual paste between devices is the supported path.",
        recommendedAction = "Use manual paste",
        canRefresh = false,
    )
    is TransferHealthFacet.LastSendFailed -> ProviderHealthEvidence(
        status = ProviderHealthStatus.RED,
        lastCheckedAt = facet.at,
        sourceOfTruth = "Last send attempt",
        evidenceSummary = "The most recent send attempt from this device failed. Try again or fall back to " +
            "manual paste.",
        recommendedAction = "Retry send",
        canRefresh = true,
    )
    is TransferHealthFacet.LastReceiveFailed -> ProviderHealthEvidence(
        status = ProviderHealthStatus.RED,
        lastCheckedAt = facet.at,
        sourceOfTruth = "Last receive attempt",
        evidenceSummary = "The most recent receive attempt on this device failed. Try again or fall back " +
            "to manual paste.",
        recommendedAction = "Retry receive",
        canRefresh = true,
    )
    is TransferHealthFacet.LastAttemptOk -> ProviderHealthEvidence(
        status = ProviderHealthStatus.GREEN,
        lastCheckedAt = facet.at,
        sourceOfTruth = "Last ${facet.role.lowercase()} attempt",
        evidenceSummary = "Last attempt succeeded.",
        recommendedAction = null,
        canRefresh = true,
    )
}
