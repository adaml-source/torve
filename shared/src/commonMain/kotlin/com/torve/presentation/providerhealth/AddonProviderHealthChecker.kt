package com.torve.presentation.providerhealth

import com.torve.data.addon.StremioAddonClient
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus

/**
 * Probes the user's installed Stremio-shaped addons by re-fetching each
 * `/manifest.json`. UNCONFIGURED when no addons are installed, GREEN when
 * every addon resolves, YELLOW when some fail, RED when all fail.
 *
 * The list of addons comes from a caller-supplied lambda so this checker
 * can live in shared code without taking a dependency on the desktop's
 * AddonRepository.
 */
class AddonProviderHealthChecker(
    private val addonsSource: suspend () -> List<AddonProbeTarget>,
    private val addonClient: StremioAddonClient,
) : ProviderHealthChecker {

    override val providerKey: String = "addon:stremio_manifest"

    override suspend fun check(): ProviderHealthEntry {
        val addons = runCatching { addonsSource() }.getOrDefault(emptyList())
        if (addons.isEmpty()) {
            return base().copy(
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "No addons installed yet.",
                nextAction = "Install an addon",
            )
        }
        var ok = 0
        var firstFailure: String? = null
        for (target in addons) {
            val result = runCatching { addonClient.getManifest(target.baseUrl) }
            if (result.isSuccess) {
                ok += 1
            } else if (firstFailure == null) {
                firstFailure = "${target.label}: ${result.exceptionOrNull()?.message ?: "unreachable"}"
            }
        }
        val total = addons.size
        return when {
            ok == total -> base().copy(
                status = ProviderHealthStatus.GREEN,
                message = "All $total addon manifests reachable.",
            )
            ok == 0 -> base().copy(
                status = ProviderHealthStatus.RED,
                message = "Couldn't reach any addon. ${firstFailure ?: ""}".trim(),
                nextAction = "Reinstall or remove the failing addon",
            )
            else -> base().copy(
                status = ProviderHealthStatus.YELLOW,
                message = "$ok of $total addons reachable. ${firstFailure ?: ""}".trim(),
                nextAction = "Inspect failing addons",
            )
        }
    }

    private fun base(): ProviderHealthEntry = ProviderHealthEntry(
        category = ProviderHealthCategory.ADDON,
        providerKey = providerKey,
        label = "Stremio addons",
        status = ProviderHealthStatus.UNKNOWN,
    )
}

/**
 * Minimal shape passed by the caller per installed addon.
 */
data class AddonProbeTarget(
    val label: String,
    /** The addon's base URL — `manifest.json` is appended by the client. */
    val baseUrl: String,
)
