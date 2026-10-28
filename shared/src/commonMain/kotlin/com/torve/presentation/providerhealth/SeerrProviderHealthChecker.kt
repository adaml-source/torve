package com.torve.presentation.providerhealth

import com.torve.domain.integrations.MediaLifecycleService
import com.torve.domain.integrations.ConnectionProbeResult
import com.torve.domain.model.MediaType
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthFailure
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlinx.coroutines.CancellationException

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
                failure = ProviderHealthFailure.SETUP_INCOMPLETE,
            )
        }
        val result = runCatching { service.probeConnection(serverUrl, apiKey) }.getOrElse {
            if (it is CancellationException) throw it
            ConnectionProbeResult.UNKNOWN_FAILURE
        }
        return if (result == ConnectionProbeResult.CONNECTED) {
            val moviesReady = service.canAutomaticallyCheckStatus(MediaType.MOVIE)
            val seriesReady = service.canAutomaticallyCheckStatus(MediaType.SERIES)
            base().copy(
                status = if (moviesReady || seriesReady) ProviderHealthStatus.GREEN else ProviderHealthStatus.YELLOW,
                message = when {
                    moviesReady && seriesReady -> "Movie and season requests are ready"
                    moviesReady -> "Movie requests are ready; seasons need a default Sonarr route in Seerr"
                    seriesReady -> "Season requests are ready; movies need a default Radarr route in Seerr"
                    else -> "Seerr connected, but no default Radarr or Sonarr request route is ready"
                },
                nextAction = if (moviesReady || seriesReady) null else "Finish library setup",
                failure = if (moviesReady || seriesReady) null else ProviderHealthFailure.SETUP_INCOMPLETE,
            )
        } else {
            base().copy(
                status = ProviderHealthStatus.RED,
                message = result.failureMessage("Seerr"),
                nextAction = "Check connection",
                failure = result.healthFailure,
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
