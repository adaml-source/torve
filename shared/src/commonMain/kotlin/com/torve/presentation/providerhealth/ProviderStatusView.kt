package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus

/**
 * Settings-IA shared source-of-truth view for a provider's status.
 *
 * Prompt 16 mandates that every provider surface (Trakt, SIMKL, Debrid,
 * IPTV, Plex/Jellyfin, Panda, …) renders status from one common
 * mapper so a provider can never read "connected" in one card and
 * "disconnected" in another. Cards on Settings → Status & Repair and
 * Settings → Configure Sources both consume this view; the mapper
 * collapses the raw [ProviderHealthEntry] traffic-light + nullable
 * message into the four user-facing kinds the prompt names.
 *
 * The view also carries one [primaryActionLabel] / [primaryActionKind]
 * pair so each card has exactly one CTA — the prompt's "one clear CTA"
 * acceptance criterion. Cards must not render a second button parsed
 * out of the message field.
 */
data class ProviderStatusView(
    val entry: ProviderHealthEntry,
    val kind: ProviderStatusKind,
    /** One-line headline label, e.g. "Connected" / "Configured but not verified". */
    val headline: String,
    /** Optional supporting line, usually [ProviderHealthEntry.message]. */
    val detail: String?,
    /** Label of the single CTA to render. Null means the card is read-only. */
    val primaryActionLabel: String?,
    /** Stable identifier for the action — UI maps to a navigation target. */
    val primaryActionKind: ProviderActionKind,
) {
    val canRefresh: Boolean get() = kind != ProviderStatusKind.NEEDS_CREDENTIALS
}

/**
 * Four user-facing status kinds Prompt 16 spells out. Each maps from
 * one or more [ProviderHealthStatus] values plus contextual signals:
 *
 *  - [CONNECTED]              ← GREEN
 *  - [CONFIGURED_NOT_VERIFIED]← YELLOW (or UNKNOWN with credentials present)
 *  - [NEEDS_CREDENTIALS]      ← UNCONFIGURED
 *  - [LAST_CHECK_FAILED]      ← RED, but only when a check has actually been
 *                               attempted (lastCheckedAt != null). A RED
 *                               with no prior check is reported as
 *                               NEEDS_CREDENTIALS — the user hasn't tried
 *                               anything yet, "last check failed" would
 *                               be a lie.
 */
enum class ProviderStatusKind {
    CONNECTED,
    CONFIGURED_NOT_VERIFIED,
    NEEDS_CREDENTIALS,
    LAST_CHECK_FAILED,
    /** Async check in flight; UI shows a spinner. Distinct from any
     *  "needs the user to do something" state. */
    CHECKING,
}

/**
 * Stable identifier for the single primary action a card can offer.
 * UI layers map these to platform-appropriate routes.
 */
enum class ProviderActionKind {
    /** "Configure" / "Set up" — open the provider's credential editor. */
    CONFIGURE,
    /** "Refresh now" — re-run the health check without changing config. */
    REFRESH,
    /** "Re-enter credentials" — same as CONFIGURE but the copy implies
     *  the existing credential is broken, not absent. */
    REENTER,
    /** "Show diagnostics" — for failures that don't have a single
     *  user-fixable knob. */
    DIAGNOSE,
    /** No CTA — the card is informational. */
    NONE,
}

object ProviderStatusMapper {

    /**
     * Convert a single [ProviderHealthEntry] into a [ProviderStatusView].
     * Pure function — no I/O, no DI — so it round-trips through tests
     * without setup.
     */
    fun map(entry: ProviderHealthEntry): ProviderStatusView {
        val kind = classify(entry)
        val (label, detail) = headlineAndDetail(entry, kind)
        val (actionLabel, actionKind) = primaryAction(kind)
        return ProviderStatusView(
            entry = entry,
            kind = kind,
            headline = label,
            detail = detail,
            primaryActionLabel = actionLabel,
            primaryActionKind = actionKind,
        )
    }

    fun mapAll(entries: List<ProviderHealthEntry>): List<ProviderStatusView> =
        entries.map(::map)

    private fun classify(entry: ProviderHealthEntry): ProviderStatusKind = when (entry.status) {
        ProviderHealthStatus.GREEN -> ProviderStatusKind.CONNECTED
        ProviderHealthStatus.YELLOW -> ProviderStatusKind.CONFIGURED_NOT_VERIFIED
        ProviderHealthStatus.RED -> {
            // RED before any check ever ran is really NEEDS_CREDENTIALS
            // — saying "last check failed" implies a check happened.
            // After a real check failed, surface as LAST_CHECK_FAILED.
            if (entry.lastCheckedAt != null) ProviderStatusKind.LAST_CHECK_FAILED
            else ProviderStatusKind.NEEDS_CREDENTIALS
        }
        ProviderHealthStatus.UNCONFIGURED -> ProviderStatusKind.NEEDS_CREDENTIALS
        ProviderHealthStatus.UNKNOWN -> ProviderStatusKind.CHECKING
    }

    private fun headlineAndDetail(
        entry: ProviderHealthEntry,
        kind: ProviderStatusKind,
    ): Pair<String, String?> {
        val headline = when (kind) {
            ProviderStatusKind.CONNECTED -> "Connected"
            ProviderStatusKind.CONFIGURED_NOT_VERIFIED -> "Configured but not verified"
            ProviderStatusKind.NEEDS_CREDENTIALS -> "Needs credentials"
            ProviderStatusKind.LAST_CHECK_FAILED -> "Last check failed"
            ProviderStatusKind.CHECKING -> "Checking…"
        }
        // Detail text is the entry's message verbatim — that's where
        // the actionable specifics live (e.g. "401 unauthorized — re-
        // enter API key"). Suppress the message in the CONNECTED case
        // unless it's saying something extra; a green "Connected" row
        // doesn't need a redundant "ready" line under it.
        val detail = when (kind) {
            ProviderStatusKind.CONNECTED -> entry.message?.takeIf { msg ->
                !msg.equals("ready.", ignoreCase = true) &&
                    !msg.equals("ready", ignoreCase = true) &&
                    !msg.endsWith(" ready.")
            }
            else -> entry.message
        }
        return headline to detail
    }

    private fun primaryAction(kind: ProviderStatusKind): Pair<String?, ProviderActionKind> = when (kind) {
        ProviderStatusKind.CONNECTED -> "Check again" to ProviderActionKind.REFRESH
        ProviderStatusKind.CONFIGURED_NOT_VERIFIED -> "Check again" to ProviderActionKind.REFRESH
        ProviderStatusKind.NEEDS_CREDENTIALS -> "Configure" to ProviderActionKind.CONFIGURE
        ProviderStatusKind.LAST_CHECK_FAILED -> "Re-enter credentials" to ProviderActionKind.REENTER
        ProviderStatusKind.CHECKING -> null to ProviderActionKind.NONE
    }
}
