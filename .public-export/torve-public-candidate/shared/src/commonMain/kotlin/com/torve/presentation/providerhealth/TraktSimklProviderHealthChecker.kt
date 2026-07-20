package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus

/**
 * Token-presence check for Trakt. Connecting through TraktClient is a
 * bigger lift (refresh tokens, rate limits); for now we surface "have we
 * got a token at all?" — UNCONFIGURED when blank, GREEN when present.
 *
 * If the token is later revoked server-side, the SettingsViewModel's
 * `verifyTraktConnection` flow will already update its own state; this
 * row is meant to give the user a single place to *find* the answer
 * rather than to be the sole source of truth.
 */
class TraktProviderHealthChecker(
    private val tokenSource: suspend () -> String?,
) : ProviderHealthChecker {

    override val providerKey: String = "trakt:account"

    override suspend fun check(): ProviderHealthEntry {
        val token = tokenSource()?.takeIf { it.isNotBlank() }
        return ProviderHealthEntry(
            category = ProviderHealthCategory.TRAKT,
            providerKey = providerKey,
            label = "Trakt",
            status = if (token != null) ProviderHealthStatus.GREEN else ProviderHealthStatus.UNCONFIGURED,
            message = if (token != null) "Connected." else "Not connected.",
            nextAction = if (token != null) null else "Connect Trakt",
        )
    }
}

class SimklProviderHealthChecker(
    private val tokenSource: suspend () -> String?,
) : ProviderHealthChecker {

    override val providerKey: String = "simkl:account"

    override suspend fun check(): ProviderHealthEntry {
        val token = tokenSource()?.takeIf { it.isNotBlank() }
        return ProviderHealthEntry(
            category = ProviderHealthCategory.SIMKL,
            providerKey = providerKey,
            label = "SIMKL",
            status = if (token != null) ProviderHealthStatus.GREEN else ProviderHealthStatus.UNCONFIGURED,
            message = if (token != null) "Connected." else "Not connected.",
            nextAction = if (token != null) null else "Connect SIMKL",
        )
    }
}
