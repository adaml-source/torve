package com.torve.presentation.providerhealth

import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.domain.transfer.SecretCategory
import com.torve.presentation.transfer.TransferSecretCatalog

/**
 * "Restore setup from another device" card state.
 *
 * This is a fresh-device/lost-setup nudge, not a second configuration
 * catalog. If the device already has any transferable credential
 * category present, the user is doing a partial/manual setup and should
 * use the explicit Receive credentials entry instead of seeing a broad
 * recovery prompt.
 */
data class ProviderHealthRecoverySnapshot(
    /** Final decision: true when the UI should show the card. */
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
     * Build a snapshot. [healthEntries] is optional; when present, green
     * transferable rows count as present and unconfigured transferable
     * rows count as missing.
     *
     * The card shows only when two or more transferable categories are
     * missing and no transferable category is present. That keeps a user
     * with just SIMKL, Plex, or Debrid configured from being told they
     * still "need" to receive credentials.
     */
    suspend fun snapshot(
        healthEntries: List<ProviderHealthEntry> = emptyList(),
    ): ProviderHealthRecoverySnapshot {
        val missing = mutableSetOf<SecretCategory>()
        val present = mutableSetOf<SecretCategory>()

        for (spec in TransferSecretCatalog.specs) {
            if (spec.keys.isEmpty()) continue
            val anyPresent = spec.keys.any { key ->
                val value = runCatching { secretStore.get(key) }.getOrNull()
                !value.isNullOrBlank()
            }
            if (anyPresent) present += spec.category else missing += spec.category
        }

        for (entry in healthEntries) {
            val transferableCategory = entry.category.transferableSecretCategory() ?: continue
            when (entry.status) {
                ProviderHealthStatus.UNCONFIGURED -> {
                    missing += transferableCategory
                    present -= transferableCategory
                }
                ProviderHealthStatus.GREEN -> {
                    present += transferableCategory
                    missing -= transferableCategory
                }
                ProviderHealthStatus.YELLOW,
                ProviderHealthStatus.RED,
                ProviderHealthStatus.UNKNOWN -> Unit
            }
        }

        val orderedMissing = TransferSecretCatalog.specs
            .map { it.category }
            .filter { it in missing }

        return ProviderHealthRecoverySnapshot(
            shouldShowRecoveryCard = present.isEmpty() &&
                orderedMissing.size >= MIN_MISSING_FOR_CARD,
            missingTransferableCategoryCount = orderedMissing.size,
            missingCategories = orderedMissing,
        )
    }

    companion object {
        const val MIN_MISSING_FOR_CARD: Int = 2
    }
}
