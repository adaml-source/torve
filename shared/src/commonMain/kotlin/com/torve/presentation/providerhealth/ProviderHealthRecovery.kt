package com.torve.presentation.providerhealth

import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.domain.transfer.SecretCategory
import com.torve.presentation.transfer.TransferSecretCatalog

/**
 * "Restore setup from another device" card state. Shown on Settings
 * surfaces when the device is missing local credentials for two or
 * more transferable provider categories — the unambiguous "fresh
 * device" or "lost setup" signal.
 *
 * Two detection paths feed the same decision:
 *   1. Direct: scan the [IntegrationSecretStore] for any non-blank
 *      value under each [TransferSecretCatalog] category. Works on
 *      every platform, including ones (Android, iOS) where no
 *      provider-health checker has run yet.
 *   2. Provider-health: count [ProviderHealthEntry] rows whose
 *      [ProviderHealthStatus] is [ProviderHealthStatus.UNCONFIGURED]
 *      and whose category is transferable. Adds confidence on desktop
 *      where checkers have run.
 */
data class ProviderHealthRecoverySnapshot(
    /** Final decision — true when the UI should show the card. */
    val shouldShowRecoveryCard: Boolean,
    /** Coarse count of transferable categories with no local credentials. */
    val missingTransferableCategoryCount: Int,
    /** Categories the card lists as candidates for restoration. */
    val missingCategories: List<SecretCategory>,
)

class ProviderHealthRecoveryStateProvider(
    private val secretStore: IntegrationSecretStore,
) {
    /**
     * Build a snapshot. [healthEntries] is optional; when present, an
     * UNCONFIGURED row for a transferable category counts as missing
     * even if the secret-store scan said otherwise (defense in depth).
     *
     * Threshold: card shows when **two or more** transferable categories
     * are missing. One missing category is just normal "I added Plex
     * but not Trakt" — not the recovery story.
     */
    suspend fun snapshot(
        healthEntries: List<ProviderHealthEntry> = emptyList(),
    ): ProviderHealthRecoverySnapshot {
        val missing = mutableSetOf<SecretCategory>()

        // Direct detection: any catalog category whose entire key list
        // has only blanks/nulls in the store is "missing."
        for (spec in TransferSecretCatalog.specs) {
            if (spec.keys.isEmpty()) continue
            val anyPresent = spec.keys.any { key ->
                val value = runCatching { secretStore.get(key) }.getOrNull()
                !value.isNullOrBlank()
            }
            if (!anyPresent) missing += spec.category
        }

        // Health-row signal: an UNCONFIGURED row for a transferable
        // category bumps the same set.
        for (entry in healthEntries) {
            if (entry.status != ProviderHealthStatus.UNCONFIGURED) continue
            val transferableCategory = entry.category.transferableSecretCategory()
            if (transferableCategory != null) missing += transferableCategory
        }

        val orderedMissing = TransferSecretCatalog.specs
            .map { it.category }
            .filter { it in missing }

        return ProviderHealthRecoverySnapshot(
            shouldShowRecoveryCard = orderedMissing.size >= MIN_MISSING_FOR_CARD,
            missingTransferableCategoryCount = orderedMissing.size,
            missingCategories = orderedMissing,
        )
    }

    companion object {
        /**
         * Two missing transferable categories is the minimum signal
         * we need before nudging the user. One missing category is
         * normal partial setup; two strongly suggests fresh-install
         * or lost-setup.
         */
        const val MIN_MISSING_FOR_CARD: Int = 2
    }
}
