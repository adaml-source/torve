package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.ProviderHealthStatus

/**
 * Settings-IA truth model for a provider's status (Prompt 17).
 *
 * Wraps the raw [ProviderHealthStatus] traffic-light with the
 * supporting context every health card needs to *explain itself* —
 * what was checked, when, what evidence drove the verdict, what action
 * the user can take, and whether refresh is meaningful right now.
 *
 * The presentation layer's [ProviderStatusMapper] from Prompt 16 still
 * derives the user-facing kind from this; what changes is that
 * checkers now publish the structured evidence rather than smuggling
 * details through a free-form `message` string.
 */
data class ProviderHealthEvidence(
    /** Raw traffic-light status from the underlying check. */
    val status: ProviderHealthStatus,
    /**
     * Epoch millis of the most recent successful or failing check.
     * Null means *no check has ever been attempted* — distinct from
     * "we checked recently and got nothing". The UI renders that
     * distinction as "No attempt recorded" instead of an old
     * timestamp, so the user can tell when a row's silence means
     * "never tried" vs "tried and failed".
     */
    val lastCheckedAt: Long? = null,
    /**
     * Where the verdict came from — e.g. "Newznab indexer probe",
     * "Local cache (not yet validated)", "TorBox /usenet/list".
     * One short noun phrase, never a URL or a credential.
     */
    val sourceOfTruth: String,
    /**
     * One line summarising what the check actually saw. Examples:
     *  - "Indexer responded; 1042 items in last response."
     *  - "EPG file parsed (3120 channels, 2710 matched, 410 unmatched)."
     *  - "TorBox returned BAD_TOKEN."
     * Used as the card detail when the user hasn't expanded; the UI
     * may also offer a "Show evidence" expansion to render this
     * verbatim.
     */
    val evidenceSummary: String,
    /**
     * What the user should do next. Optional — null means the row is
     * informational and the mapper will pick a sensible default
     * primary action for the kind (e.g. "Refresh now" for
     * CONNECTED).
     */
    val recommendedAction: String? = null,
    /**
     * False when re-running the check would not give new information
     * (e.g. NEEDS_CREDENTIALS — there's nothing to check). True for
     * everything else, including LAST_CHECK_FAILED so the user can
     * retry a transient failure.
     */
    val canRefresh: Boolean,
)

/**
 * Specialised facets for IPTV — Prompt 17 mandates that the IPTV
 * health row distinguishes these five things instead of collapsing
 * them into one ambiguous warning. The mapper uses this to suppress
 * the false-positive "EPG loaded but no channels matched" when
 * channels are usable from name fallback.
 */
data class IptvHealthFacets(
    val playlistLoaded: Boolean,
    val channelsLoaded: Boolean,
    val epgLoaded: Boolean,
    val epgMatchedCount: Int,
    val epgUnmatchedCount: Int,
    /**
     * True when channels are playable even though strict EPG matching
     * came up empty — the runtime player matches by fuzzy name. This
     * is the signal that turns a false-positive "EPG loaded but no
     * channels matched" warning into a YELLOW informational row.
     */
    val channelsUsableViaNameFallback: Boolean,
) {
    /** True when EPG strict matching produced zero rows AND the
     *  fallback path also can't find channels. That's the only
     *  scenario where an EPG warning is real. */
    val epgFullyUnmatched: Boolean
        get() = epgLoaded && epgMatchedCount == 0 && !channelsUsableViaNameFallback
}

/**
 * Specialised facets for the credential-transfer surface — Prompt 17
 * mandates the diagnostics card distinguish these six conditions so
 * "no attempt recorded" doesn't read like "transfer failed", a
 * network error explains it's about backend relay vs manual paste,
 * etc.
 */
sealed interface TransferHealthFacet {
    /** No send/receive has been attempted yet on this device. */
    data object NoAttemptYet : TransferHealthFacet

    /** Backend is unreachable (DNS, no network, 5xx). Manual paste
     *  still works locally. */
    data object BackendUnavailable : TransferHealthFacet

    /** Auth probe failed — the user needs to sign in again before
     *  the relay path can resume. Manual paste is unaffected. */
    data object Unauthenticated : TransferHealthFacet

    /** Backend returned 404 on the `/transfer/...` family — relay
     *  isn't deployed for this account / build. Manual paste is the
     *  supported path. */
    data object RelayUnsupported : TransferHealthFacet

    /** The most recent send attempt failed. */
    data class LastSendFailed(val at: Long) : TransferHealthFacet

    /** The most recent receive attempt failed. */
    data class LastReceiveFailed(val at: Long) : TransferHealthFacet

    /** Most recent attempt succeeded. */
    data class LastAttemptOk(val at: Long, val role: String) : TransferHealthFacet
}
