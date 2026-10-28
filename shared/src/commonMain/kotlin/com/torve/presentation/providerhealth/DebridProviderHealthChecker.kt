package com.torve.presentation.providerhealth

import com.torve.data.debrid.DebridClient
import com.torve.domain.model.DebridServiceType
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlinx.coroutines.CancellationException

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

    private suspend fun credential(): String? = apiKeySource()?.trim()?.takeIf {
        it.isNotBlank() && !it.contains("redact", ignoreCase = true) && !it.all { c -> c == '*' || c == '•' }
    }

    /** Cached success cannot attest to the current credentials after an app restart or restore. */
    suspend fun configurationState(): ProviderHealthEntry = if (credential() == null) {
        notConnected()
    } else base().copy(message = "Saved credentials. Check connection to verify.", nextAction = "Check connection")

    private fun notConnected() = base().copy(
        status = ProviderHealthStatus.UNCONFIGURED,
        message = "Not connected. Connect $label in Panda setup.",
        nextAction = "Set up $label via Panda",
    )

    override suspend fun check(): ProviderHealthEntry {
        val key = credential()
        if (key == null) {
            return notConnected()
        }
        val result = runCatching { debridClient.verifyApiKey(provider, key) }.getOrElse { t ->
            if (t is CancellationException) throw t
            return base().copy(
                status = ProviderHealthStatus.RED,
                message = "Could not check $label. Try again.",
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
            val refreshed = try { debridClient.rdTokenRefresher?.refresh() }
                catch (e: Exception) { if (e is CancellationException) throw e else null }
            if (refreshed != null) {
                val refreshResult = debridClient.verifyApiKey(provider, refreshed)
                if (refreshResult.success) {
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
            message = "$label could not be verified. Open Panda setup to check or reconnect this provider.",
            nextAction = "Open Panda setup",
        )
    }

    private fun base(): ProviderHealthEntry = ProviderHealthEntry(
        category = ProviderHealthCategory.DEBRID,
        providerKey = providerKey,
        label = label,
        status = ProviderHealthStatus.UNKNOWN,
    )
}
