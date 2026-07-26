package com.torve.presentation.providerhealth

import com.torve.data.integrations.AutomationAdminClient
import com.torve.data.integrations.AutomationConnectionResult
import com.torve.domain.integrations.AutomationInstanceRepository
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus

/** Aggregate, read-only health check for direct Sonarr/Radarr/Prowlarr/Bazarr/Tdarr connections. */
class AutomationStackProviderHealthChecker(
    private val repository: AutomationInstanceRepository,
    private val adminClient: AutomationAdminClient,
) : ProviderHealthChecker {
    override val providerKey: String = PROVIDER_KEY

    override suspend fun check(): ProviderHealthEntry {
        val instances = runCatching { repository.list().filter { it.enabled } }.getOrElse {
            return base().copy(
                status = ProviderHealthStatus.RED,
                message = "Saved automation connections could not be read",
                nextAction = "Review setup",
            )
        }
        if (instances.isEmpty()) {
            return base().copy(
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "Add Sonarr, Radarr, Prowlarr, Bazarr, or Tdarr",
                nextAction = "Configure",
            )
        }

        val results = instances.map { instance ->
            val apiKey = if (instance.serviceType == AutomationServiceType.TDARR) {
                ""
            } else {
                runCatching { repository.apiKey(instance) }.getOrNull().orEmpty()
            }
            if (instance.serviceType != AutomationServiceType.TDARR && apiKey.isBlank()) {
                InstanceHealth.MISSING_CREDENTIAL
            } else {
                when (runCatching { adminClient.testConnection(instance, apiKey) }
                    .getOrDefault(AutomationConnectionResult.Unreachable)) {
                    is AutomationConnectionResult.Connected -> InstanceHealth.CONNECTED
                    AutomationConnectionResult.Unauthorized -> InstanceHealth.UNAUTHORIZED
                    AutomationConnectionResult.Unreachable -> InstanceHealth.UNREACHABLE
                    AutomationConnectionResult.Unsupported -> InstanceHealth.UNSUPPORTED
                }
            }
        }
        val connected = results.count { it == InstanceHealth.CONNECTED }
        val total = results.size
        val incomplete = results.count {
            it == InstanceHealth.MISSING_CREDENTIAL || it == InstanceHealth.UNSUPPORTED
        }
        val failed = total - connected - incomplete

        return when {
            connected == total -> base().copy(
                status = ProviderHealthStatus.GREEN,
                message = "$connected automation ${if (connected == 1) "service" else "services"} connected",
            )
            connected > 0 -> base().copy(
                status = ProviderHealthStatus.YELLOW,
                message = "$connected of $total automation services connected; ${total - connected} need attention",
                nextAction = "Review setup",
            )
            failed > 0 -> base().copy(
                status = ProviderHealthStatus.RED,
                message = "No configured automation service is reachable",
                nextAction = "Check connections",
            )
            else -> base().copy(
                status = ProviderHealthStatus.YELLOW,
                message = "$incomplete automation ${if (incomplete == 1) "connection needs" else "connections need"} setup",
                nextAction = "Finish setup",
            )
        }
    }

    private fun base() = ProviderHealthEntry(
        category = ProviderHealthCategory.REQUEST_MANAGER,
        providerKey = providerKey,
        label = "Automation stack",
        status = ProviderHealthStatus.UNKNOWN,
    )

    private enum class InstanceHealth {
        CONNECTED,
        MISSING_CREDENTIAL,
        UNAUTHORIZED,
        UNREACHABLE,
        UNSUPPORTED,
    }

    companion object {
        const val PROVIDER_KEY = "automation:admin"
    }
}
