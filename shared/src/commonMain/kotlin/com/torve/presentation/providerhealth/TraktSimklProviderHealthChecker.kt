package com.torve.presentation.providerhealth

import com.torve.domain.integrations.ConnectionProbeResult
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlinx.coroutines.CancellationException

class TraktProviderHealthChecker(
    private val tokenSource: suspend () -> String?,
    private val probe: (suspend (String) -> ConnectionProbeResult)? = null,
) : ProviderHealthChecker {
    override val providerKey = "trakt:account"
    override suspend fun check(): ProviderHealthEntry = trackingHealth(
        ProviderHealthCategory.TRAKT, providerKey, "Trakt", tokenSource(), probe)
}

class SimklProviderHealthChecker(
    private val tokenSource: suspend () -> String?,
    private val probe: (suspend (String) -> ConnectionProbeResult)? = null,
) : ProviderHealthChecker {
    override val providerKey = "simkl:account"
    override suspend fun check(): ProviderHealthEntry = trackingHealth(
        ProviderHealthCategory.SIMKL, providerKey, "SIMKL", tokenSource(), probe)
}

private suspend fun trackingHealth(category: ProviderHealthCategory, key: String, label: String,
    token: String?, probe: (suspend (String) -> ConnectionProbeResult)?): ProviderHealthEntry {
    val credential = token?.takeIf { it.isNotBlank() }
    if (credential == null) return ProviderHealthEntry(category, key, label, ProviderHealthStatus.UNCONFIGURED,
        message = "Not connected.", nextAction = "Connect $label")
    if (probe == null) return ProviderHealthEntry(category, key, label, ProviderHealthStatus.YELLOW,
        message = "Credentials saved. Connection not verified yet.", nextAction = "Check connection")
    val result = try { probe(credential) } catch (error: Exception) {
        if (error is CancellationException) throw error
        ConnectionProbeResult.UNKNOWN_FAILURE
    }
    return ProviderHealthEntry(category, key, label,
        status = if (result == ConnectionProbeResult.CONNECTED) ProviderHealthStatus.GREEN else ProviderHealthStatus.RED,
        message = if (result == ConnectionProbeResult.CONNECTED) "Connected." else result.failureMessage(label),
        nextAction = if (result == ConnectionProbeResult.CONNECTED) null
            else if (result == ConnectionProbeResult.AUTH_REJECTED) "Reconnect $label" else "Retry connection",
        failure = result.healthFailure)
}
