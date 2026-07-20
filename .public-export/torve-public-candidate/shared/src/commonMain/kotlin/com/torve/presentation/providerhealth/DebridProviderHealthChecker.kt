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
                message = "Not connected. Connect $label in Panda setup.",
                nextAction = "Set up $label via Panda",
            )
        }
        val result = runCatching { debridClient.verifyApiKey(provider, key) }.getOrElse { t ->
            return base().copy(
                status = ProviderHealthStatus.RED,
                message = "Couldn't reach $label: ${t.message ?: t::class.simpleName}",
                nextAction = "Retry",
            )
        }
        if (result.success) {
            return base().copy(
                status = ProviderHealthStatus.GREEN,
                message = "$label is connected.",
                nextAction = null,
            )
        }
        // Verification failed — for Real-Debrid, attempt a silent token refresh
        // before reporting RED. OAuth access tokens expire; a successful refresh
        // means the session is still valid and should be surfaced as GREEN.
        if (provider == DebridServiceType.REAL_DEBRID) {
            val refreshed = runCatching { debridClient.rdTokenRefresher?.refresh() }.getOrNull()
            if (refreshed != null) {
                val refreshResult = runCatching { debridClient.verifyApiKey(provider, refreshed) }.getOrNull()
                if (refreshResult?.success == true) {
                    return base().copy(
                        status = ProviderHealthStatus.GREEN,
                        message = "$label is connected.",
                        nextAction = null,
                    )
                }
            }
        }
        return base().copy(
            status = ProviderHealthStatus.RED,
            message = "session expired — reconnect in Settings → Integrations",
            nextAction = "Re-enter API key",
        )
    }

    private fun base(): ProviderHealthEntry = ProviderHealthEntry(
        category = ProviderHealthCategory.DEBRID,
        providerKey = providerKey,
        label = label,
        status = ProviderHealthStatus.UNKNOWN,
    )
}
