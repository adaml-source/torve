package com.torve.presentation.providerhealth

import com.torve.domain.integrations.MediaLifecycleService
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus

/** Connectivity and credential health for the household Seerr gateway. */
class SeerrProviderHealthChecker(
    private val serverUrlSource: suspend () -> String?,
    private val apiKeySource: suspend () -> String?,
    private val service: MediaLifecycleService,
) : ProviderHealthChecker {
    override val providerKey: String = PROVIDER_KEY

    override suspend fun check(): ProviderHealthEntry {
        val serverUrl = serverUrlSource()?.trim().orEmpty()
        val apiKey = apiKeySource()?.trim().orEmpty()
        if (serverUrl.isBlank() && apiKey.isBlank()) {
            return base().copy(
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "Connect Seerr to request permanent library copies",
                nextAction = "Configure",
            )
        }
        if (serverUrl.isBlank() || apiKey.isBlank()) {
            return base().copy(
                status = ProviderHealthStatus.YELLOW,
                message = "Server URL and API key are both required",
                nextAction = "Finish setup",
            )
        }
        val connected = runCatching { service.testConnection(serverUrl, apiKey) }.getOrDefault(false)
        return if (connected) {
            base().copy(
                status = ProviderHealthStatus.GREEN,
                message = "Request manager connected",
            )
        } else {
            base().copy(
                status = ProviderHealthStatus.RED,
                message = "Seerr is unreachable or rejected the API key",
                nextAction = "Check connection",
            )
        }
    }

    private fun base() = ProviderHealthEntry(
        category = ProviderHealthCategory.REQUEST_MANAGER,
        providerKey = providerKey,
        label = "Seerr",
        status = ProviderHealthStatus.UNKNOWN,
    )

    companion object {
        const val PROVIDER_KEY = "seerr:request_manager"
    }
}
