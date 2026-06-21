package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.domain.transfer.SecretCategory

/**
 * Presentation-level repair actions a provider-health row can offer.
 * Mapped from a [ProviderHealthEntry] by [ProviderRepairMapper].
 *
 * **Not** a backend concept — this layer just decides which buttons the
 * UI renders. The actions themselves are handled by existing platform
 * routes (transfer receive screen, provider settings page, diagnostics
 * screen, etc.).
 */
sealed interface ProviderRepairAction {
    /** "Re-enter manually" — universal fallback for credential-backed categories. */
    data object ReenterCredentials : ProviderRepairAction

    /**
     * "Transfer from another device" — open the credential-transfer
     * receive surface so a working device can ship sealed credentials
     * over the relay (or via manual paste). Only offered for categories
     * the [com.torve.presentation.transfer.TransferSecretCatalog] covers.
     */
    data object TransferFromAnotherDevice : ProviderRepairAction

    /**
     * "Show diagnostics" — surfaced when the failure is not a missing
     * local credential (e.g. provider unreachable, server 5xx, network
     * blackhole). Transfer would be misleading here because the other
     * device's credentials wouldn't help.
     */
    data object OpenDiagnostics : ProviderRepairAction

    /** "Open provider settings" — direct deep-link to the existing settings page. */
    data object OpenProviderSettings : ProviderRepairAction
}

/**
 * Which provider-health categories have a corresponding entry in the
 * shared [com.torve.presentation.transfer.TransferSecretCatalog].
 * Only these categories should ever offer
 * [ProviderRepairAction.TransferFromAnotherDevice].
 *
 * Single source of truth — the mapper consults this set instead of
 * hard-coding category names so a new transferable provider is one
 * line away.
 */
internal fun ProviderHealthCategory.transferableSecretCategory(): SecretCategory? =
    when (this) {
        ProviderHealthCategory.DEBRID -> SecretCategory.DEBRID
        ProviderHealthCategory.PLEX_JELLYFIN -> SecretCategory.PLEX_JELLYFIN
        ProviderHealthCategory.TRAKT -> SecretCategory.TRAKT_SIMKL
        ProviderHealthCategory.SIMKL -> SecretCategory.TRAKT_SIMKL
        ProviderHealthCategory.USENET_PROVIDER,
        ProviderHealthCategory.USENET_INDEXER,
        ProviderHealthCategory.DOWNLOAD_CLIENT -> SecretCategory.PANDA
        ProviderHealthCategory.IPTV,
        ProviderHealthCategory.ADDON,
        ProviderHealthCategory.EPG,
        ProviderHealthCategory.PLAYBACK -> null
    }

/**
 * Decides which repair actions a [ProviderHealthEntry] offers.
 *
 * Rules:
 *   - GREEN / UNKNOWN: empty list. Nothing to repair.
 *   - UNCONFIGURED on a transferable category:
 *     `[Transfer, Reenter]` — transfer is the fast path.
 *   - UNCONFIGURED on a non-transferable category: `[Reenter]`.
 *   - RED with a message that smells like "provider unreachable":
 *     `[OpenDiagnostics, OpenProviderSettings]` — never offer transfer
 *     because the other device probably can't reach the provider either.
 *   - RED with auth-style message ("unauthorized", "401", "invalid",
 *     "expired", "rejected") on a transferable category: `[Reenter, Transfer]`
 *     (lead with reenter — token is most likely stale and the matching
 *     token on the other device may also have rotated). On a non-transferable
 *     category: `[Reenter]`.
 *   - YELLOW (degraded / setup incomplete): `[OpenProviderSettings]`,
 *     plus `[Transfer]` first when category is transferable AND the
 *     row's message indicates missing companion config.
 *   - Any other RED: `[Reenter, OpenProviderSettings]` (with `Transfer`
 *     appended for transferable categories — best-effort, never primary).
 */
object ProviderRepairMapper {

    fun actionsFor(entry: ProviderHealthEntry): List<ProviderRepairAction> {
        val transferable = entry.category.transferableSecretCategory() != null
        val msgLower = entry.message?.lowercase().orEmpty()

        return when (entry.status) {
            ProviderHealthStatus.GREEN, ProviderHealthStatus.UNKNOWN -> emptyList()

            ProviderHealthStatus.UNCONFIGURED -> {
                if (transferable) listOf(
                    ProviderRepairAction.TransferFromAnotherDevice,
                    ProviderRepairAction.ReenterCredentials,
                ) else listOf(ProviderRepairAction.ReenterCredentials)
            }

            ProviderHealthStatus.RED -> when {
                msgLower.containsUnreachable() -> listOf(
                    ProviderRepairAction.OpenDiagnostics,
                    ProviderRepairAction.OpenProviderSettings,
                )
                msgLower.containsAuthFailure() -> if (transferable) listOf(
                    ProviderRepairAction.ReenterCredentials,
                    ProviderRepairAction.TransferFromAnotherDevice,
                ) else listOf(ProviderRepairAction.ReenterCredentials)
                else -> if (transferable) listOf(
                    ProviderRepairAction.ReenterCredentials,
                    ProviderRepairAction.OpenProviderSettings,
                    ProviderRepairAction.TransferFromAnotherDevice,
                ) else listOf(
                    ProviderRepairAction.ReenterCredentials,
                    ProviderRepairAction.OpenProviderSettings,
                )
            }

            ProviderHealthStatus.YELLOW -> {
                if (transferable && msgLower.containsCompanionConfigGap()) listOf(
                    ProviderRepairAction.TransferFromAnotherDevice,
                    ProviderRepairAction.OpenProviderSettings,
                ) else listOf(ProviderRepairAction.OpenProviderSettings)
            }
        }
    }

    private fun String.containsUnreachable(): Boolean {
        val needles = listOf(
            "unreachable",
            "couldn't reach",
            "couldn t reach",
            "network",
            "timeout",
            "timed out",
            "connection refused",
            "host",
            "dns",
            "5xx",
            "502",
            "503",
            "504",
        )
        return needles.any { this.contains(it) }
    }

    private fun String.containsAuthFailure(): Boolean {
        val needles = listOf(
            "unauthorized",
            "unauthorised",
            "401",
            "403",
            "forbidden",
            "invalid",
            "expired",
            "rejected",
            "revoked",
        )
        return needles.any { this.contains(it) }
    }

    private fun String.containsCompanionConfigGap(): Boolean {
        val needles = listOf(
            "server url",
            "missing url",
            "needs url",
            "companion config",
            "no server",
        )
        return needles.any { this.contains(it) }
    }
}
