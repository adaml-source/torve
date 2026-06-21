package com.torve.presentation.providerhealth

import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus

/**
 * Probes the user's Plex or Jellyfin server via
 * [LibraryOverlayService.testConnection]. The composite implementation
 * tries both, so this checker is product-neutral.
 *
 * UNCONFIGURED when either server URL or token is blank. GREEN on a
 * successful ping, RED otherwise.
 */
class PlexJellyfinProviderHealthChecker(
    private val serverUrlSource: suspend () -> String?,
    private val tokenSource: suspend () -> String?,
    private val service: LibraryOverlayService,
    private val productLabel: String = "Plex / Jellyfin",
) : ProviderHealthChecker {

    override val providerKey: String = "library:plex_jellyfin"

    override suspend fun check(): ProviderHealthEntry {
        val url = serverUrlSource()?.trim().orEmpty()
        val token = tokenSource()?.trim().orEmpty()
        if (url.isBlank() || token.isBlank()) {
            return base().copy(
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "Not connected. Add your $productLabel server URL and token.",
                nextAction = "Connect $productLabel",
            )
        }
        val ok = runCatching { service.testConnection(url, token) }.getOrElse { t ->
            return base().copy(
                status = ProviderHealthStatus.RED,
                message = "Couldn't reach the server: ${t.message ?: t::class.simpleName}",
                nextAction = "Check server URL",
            )
        }
        return if (ok) {
            base().copy(
                status = ProviderHealthStatus.GREEN,
                message = "$productLabel is reachable.",
                nextAction = null,
            )
        } else {
            base().copy(
                status = ProviderHealthStatus.RED,
                message = "Server rejected the credentials.",
                nextAction = "Re-enter token",
            )
        }
    }

    private fun base(): ProviderHealthEntry = ProviderHealthEntry(
        category = ProviderHealthCategory.PLEX_JELLYFIN,
        providerKey = providerKey,
        label = productLabel,
        status = ProviderHealthStatus.UNKNOWN,
    )
}
