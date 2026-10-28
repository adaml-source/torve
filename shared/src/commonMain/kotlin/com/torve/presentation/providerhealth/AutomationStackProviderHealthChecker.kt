package com.torve.presentation.providerhealth

import com.torve.data.integrations.AutomationAdminClient
import com.torve.data.integrations.AutomationConnectionResult
import com.torve.domain.integrations.AutomationInstanceRepository
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthFailure
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlinx.coroutines.CancellationException

/** Aggregate, read-only health check for direct Sonarr/Radarr/Prowlarr/Bazarr/Tdarr connections. */
class AutomationStackProviderHealthChecker(
    private val repository: AutomationInstanceRepository,
    private val adminClient: AutomationAdminClient,
) : ProviderHealthChecker {
    override val providerKey: String = PROVIDER_KEY

    override suspend fun check(): ProviderHealthEntry {
        val instances = runCatching { repository.list().filter { it.enabled } }.getOrElse {
            if (it is CancellationException) throw it
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
                runCatching { repository.apiKey(instance) }.getOrElse {
                    if (it is CancellationException) throw it
                    null
                }.orEmpty()
            }
            val health = if (instance.serviceType != AutomationServiceType.TDARR && apiKey.isBlank()) {
                InstanceHealth.MISSING_CREDENTIAL
            } else {
                when (runCatching { adminClient.testConnection(instance, apiKey) }
                    .getOrElse {
                        if (it is CancellationException) throw it
                        AutomationConnectionResult.Unreachable
                    }) {
                    is AutomationConnectionResult.Connected -> InstanceHealth.CONNECTED
                    AutomationConnectionResult.Unauthorized -> InstanceHealth.UNAUTHORIZED
                    AutomationConnectionResult.Unreachable -> InstanceHealth.UNREACHABLE
                    AutomationConnectionResult.Unsupported -> InstanceHealth.UNSUPPORTED
                }
            }
            instance.serviceType to health
        }
        val connected = results.count { (_, health) -> health == InstanceHealth.CONNECTED }
        val total = results.size
        val incomplete = results.count { (_, health) ->
            health == InstanceHealth.MISSING_CREDENTIAL || health == InstanceHealth.UNSUPPORTED
        }
        val failed = total - connected - incomplete
        val problems = results.filter { (_, health) -> health != InstanceHealth.CONNECTED }
        val reasons = problems.map { (_, health) -> when (health) {
            InstanceHealth.UNAUTHORIZED -> ProviderHealthFailure.AUTH_REJECTED
            InstanceHealth.UNREACHABLE -> ProviderHealthFailure.UNREACHABLE
            else -> ProviderHealthFailure.SETUP_INCOMPLETE
        } }.distinct()
        val failure = reasons.singleOrNull() ?: ProviderHealthFailure.MIXED
        val details = problems.groupBy { it.second }.entries.joinToString(" ") { (health, services) ->
            val names = services.map { it.first.displayLabel() }.distinct().joinToString(", ")
            when (health) {
                InstanceHealth.UNAUTHORIZED -> "$names rejected credentials. Check the API key."
                InstanceHealth.UNREACHABLE -> "Could not reach $names. Check the network or server address."
                InstanceHealth.MISSING_CREDENTIAL -> "$names needs an API key."
                InstanceHealth.UNSUPPORTED -> "$names needs a supported connection setup."
                InstanceHealth.CONNECTED -> ""
            }
        }
        val action = when (failure) {
            ProviderHealthFailure.AUTH_REJECTED -> "Update credentials"
            ProviderHealthFailure.UNREACHABLE -> "Try again"
            ProviderHealthFailure.SETUP_INCOMPLETE -> "Finish setup"
            ProviderHealthFailure.MIXED -> "Review connections"
        }

        return when {
            connected == total -> base().copy(
                status = ProviderHealthStatus.GREEN,
                message = "$connected automation ${if (connected == 1) "service" else "services"} connected",
            )
            connected > 0 -> base().copy(
                status = ProviderHealthStatus.YELLOW,
                message = "$connected of $total ARR services connected. $details",
                nextAction = action,
                failure = failure,
            )
            failed > 0 -> base().copy(
                status = ProviderHealthStatus.RED,
                message = details,
                nextAction = action,
                failure = failure,
            )
            else -> base().copy(
                status = ProviderHealthStatus.YELLOW,
                message = details,
                nextAction = action,
                failure = failure,
            )
        }
    }

    private fun base() = ProviderHealthEntry(
        category = ProviderHealthCategory.REQUEST_MANAGER,
        providerKey = providerKey,
        label = "ARR automation stack",
        status = ProviderHealthStatus.UNKNOWN,
    )

    private fun AutomationServiceType.displayLabel(): String = when (this) {
        AutomationServiceType.SONARR -> "Sonarr"
        AutomationServiceType.RADARR -> "Radarr"
        AutomationServiceType.PROWLARR -> "Prowlarr"
        AutomationServiceType.BAZARR -> "Bazarr"
        AutomationServiceType.TDARR -> "Tdarr"
    }

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
