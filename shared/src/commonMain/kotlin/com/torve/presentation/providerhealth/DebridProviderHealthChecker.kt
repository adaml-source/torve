package com.torve.presentation.providerhealth

import com.torve.data.debrid.DebridClient
import com.torve.domain.model.DebridServiceType
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus

/**
 * Wraps [DebridClient.verifyApiKey] for one debrid provider.
 *
 * UNCONFIGURED when no API key is on file. Otherwise GREEN on a successful
 * verify, RED on rejection. Never logs or returns the API key value.
 */
class DebridProviderHealthChecker(
    private val provider: DebridServiceType,
    private val apiKeySource: suspend () -> String?,
    private val debridClient: DebridClient,
) : ProviderHealthChecker {

    override val providerKey: String = "debrid:${provider.name.lowercase()}"

    private val label: String = when (provider) {
        DebridServiceType.REAL_DEBRID -> "Real-Debrid"
        DebridServiceType.ALL_DEBRID -> "AllDebrid"
        DebridServiceType.PREMIUMIZE -> "Premiumize"
        DebridServiceType.TORBOX -> "TorBox"
    }

    override suspend fun check(): ProviderHealthEntry {
        val key = apiKeySource()?.takeIf { it.isNotBlank() }
        if (key == null) {
            return base().copy(
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "Not connected. Add your $label API key.",
                nextAction = "Connect $label",
            )
        }
        val result = runCatching { debridClient.verifyApiKey(provider, key) }.getOrElse { t ->
            return base().copy(
                status = ProviderHealthStatus.RED,
                message = "Couldn't reach $label: ${t.message ?: t::class.simpleName}",
                nextAction = "Retry",
            )
        }
        return if (result.success) {
            base().copy(
                status = ProviderHealthStatus.GREEN,
                message = "$label is connected.",
                nextAction = null,
            )
        } else {
            base().copy(
                status = ProviderHealthStatus.RED,
                message = result.error?.takeIf { it.isNotBlank() }
                    ?: "$label rejected the API key.",
                nextAction = "Re-enter API key",
            )
        }
    }

    private fun base(): ProviderHealthEntry = ProviderHealthEntry(
        category = ProviderHealthCategory.DEBRID,
        providerKey = providerKey,
        label = label,
        status = ProviderHealthStatus.UNKNOWN,
    )
}
