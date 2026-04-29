package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.panda.PandaSetupUiState

/**
 * Derives Usenet-stack health from the live Panda config state. Three
 * separate rows so the user can see exactly which leg is missing:
 *
 *   - Indexer (scenenzbs / nzbgeek / etc.)
 *   - Usenet provider (Newshosting / Easynews / etc.)
 *   - Download client (TorBox / SABnzbd / etc.)
 *
 * The checker is purely state-derived. It never hits the network — Panda
 * already validates credentials at save time, and the `getConfigSecrets`
 * call from PandaSetupViewModel hydrates plaintext keys via the owner
 * JWT. So presence of a non-redacted, non-blank credential is the right
 * proxy for "ready to use on this device."
 */
class PandaUsenetProviderHealthChecker(
    private val stateSource: () -> PandaSetupUiState,
) : ProviderHealthChecker {

    override val providerKey: String = "panda:usenet_indexer"

    override suspend fun check(): ProviderHealthEntry {
        val state = stateSource()
        if (!state.isEditMode) {
            return base(ProviderHealthCategory.USENET_INDEXER, "Usenet indexer").copy(
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "Panda not set up yet.",
                nextAction = "Run Panda setup",
            )
        }
        val configured = state.nzbIndexers.filter { it.type != "none" }
        if (configured.isEmpty()) {
            return base(ProviderHealthCategory.USENET_INDEXER, "Usenet indexer").copy(
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "No NZB indexer configured in Panda.",
                nextAction = "Add an indexer",
            )
        }
        val withKey = configured.filter { it.apiKey.isNotBlank() && !isRedacted(it.apiKey) }
        return when {
            withKey.size == configured.size -> base(
                ProviderHealthCategory.USENET_INDEXER,
                "Usenet indexer",
            ).copy(
                status = ProviderHealthStatus.GREEN,
                message = "${configured.size} indexer(s) ready: ${configured.joinToString(", ") { it.type }}.",
            )
            withKey.isEmpty() -> base(
                ProviderHealthCategory.USENET_INDEXER,
                "Usenet indexer",
            ).copy(
                status = ProviderHealthStatus.RED,
                message = "Indexer API keys are missing on this device.",
                nextAction = "Re-enter indexer API keys",
            )
            else -> base(
                ProviderHealthCategory.USENET_INDEXER,
                "Usenet indexer",
            ).copy(
                status = ProviderHealthStatus.YELLOW,
                message = "${withKey.size} of ${configured.size} indexers ready; some keys missing.",
                nextAction = "Re-enter missing keys",
            )
        }
    }
}

/**
 * Newshosting / Easynews / etc. usenet provider — checks `enableUsenet`
 * + a non-blank password.
 */
class PandaUsenetProviderProviderHealthChecker(
    private val stateSource: () -> PandaSetupUiState,
) : ProviderHealthChecker {

    override val providerKey: String = "panda:usenet_provider"

    override suspend fun check(): ProviderHealthEntry {
        val state = stateSource()
        if (!state.isEditMode || !state.enableUsenet || state.usenetProvider == "none") {
            return base(ProviderHealthCategory.USENET_PROVIDER, "Usenet provider").copy(
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "Usenet provider not selected.",
                nextAction = "Pick a usenet provider",
            )
        }
        val pwOk = state.usenetPassword.isNotBlank() && !isRedacted(state.usenetPassword)
        return base(ProviderHealthCategory.USENET_PROVIDER, "Usenet provider").copy(
            status = if (pwOk) ProviderHealthStatus.GREEN else ProviderHealthStatus.RED,
            message = if (pwOk) {
                "${state.usenetProvider.replaceFirstChar { it.uppercase() }} ready."
            } else {
                "Password missing for ${state.usenetProvider}."
            },
            nextAction = if (pwOk) null else "Re-enter password",
        )
    }
}

/**
 * Download client (TorBox / SABnzbd / etc.) — needs an API key (or
 * password) on file.
 */
class PandaDownloadClientProviderHealthChecker(
    private val stateSource: () -> PandaSetupUiState,
) : ProviderHealthChecker {

    override val providerKey: String = "panda:download_client"

    override suspend fun check(): ProviderHealthEntry {
        val state = stateSource()
        if (!state.isEditMode || state.downloadClient == "none") {
            return base(ProviderHealthCategory.DOWNLOAD_CLIENT, "Download client").copy(
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "No download client selected.",
                nextAction = "Pick a download client",
            )
        }
        val keyOk = state.downloadClientApiKey.isNotBlank() && !isRedacted(state.downloadClientApiKey)
        val pwOk = state.downloadClientPassword.isNotBlank() && !isRedacted(state.downloadClientPassword)
        val anyOk = keyOk || pwOk
        return base(ProviderHealthCategory.DOWNLOAD_CLIENT, "Download client").copy(
            status = if (anyOk) ProviderHealthStatus.GREEN else ProviderHealthStatus.RED,
            message = if (anyOk) {
                "${state.downloadClient.replaceFirstChar { it.uppercase() }} ready."
            } else {
                "Credentials missing for ${state.downloadClient}."
            },
            nextAction = if (anyOk) null else "Re-enter credentials",
        )
    }
}

private fun isRedacted(value: String): Boolean =
    value.contains("redact", ignoreCase = true)

private fun base(
    category: ProviderHealthCategory,
    label: String,
): ProviderHealthEntry = ProviderHealthEntry(
    category = category,
    providerKey = "_temp",
    label = label,
    status = ProviderHealthStatus.UNKNOWN,
).let { template ->
    when (category) {
        ProviderHealthCategory.USENET_INDEXER -> template.copy(providerKey = "panda:usenet_indexer")
        ProviderHealthCategory.USENET_PROVIDER -> template.copy(providerKey = "panda:usenet_provider")
        ProviderHealthCategory.DOWNLOAD_CLIENT -> template.copy(providerKey = "panda:download_client")
        else -> template
    }
}
