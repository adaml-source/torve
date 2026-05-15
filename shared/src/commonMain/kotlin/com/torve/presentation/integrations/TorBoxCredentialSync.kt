package com.torve.presentation.integrations

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode

internal const val TORBOX_DOWNLOAD_CLIENT_SUBKEY = "torbox"

internal fun usableTorBoxCredential(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    if (trimmed.contains("redact", ignoreCase = true)) return null
    return trimmed
}

internal suspend fun IntegrationSecretStore.findTorBoxCredential(
    preferredApiKey: String? = null,
): String? {
    return usableTorBoxCredential(preferredApiKey)
        ?: usableTorBoxCredential(get(IntegrationSecretKey.DEBRID_API_KEY_TORBOX))
        ?: usableTorBoxCredential(
            get(
                IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY,
                subKey = TORBOX_DOWNLOAD_CLIENT_SUBKEY,
            ),
        )
}

/**
 * TorBox is used in two places:
 * - as a normal Torve debrid provider
 * - as Panda's cloud download client for NZB/torrent processing
 *
 * Panda still has only one selected `debridService`; this helper only mirrors
 * the API key into both local secret slots so either consumer can read it.
 */
internal suspend fun IntegrationSecretStore.syncTorBoxCredentialPair(
    preferredApiKey: String? = null,
): Boolean {
    val apiKey = findTorBoxCredential(preferredApiKey) ?: return false
    put(IntegrationSecretKey.DEBRID_API_KEY_TORBOX, apiKey)
    put(
        key = IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY,
        value = apiKey,
        subKey = TORBOX_DOWNLOAD_CLIENT_SUBKEY,
    )
    return true
}

internal suspend fun IntegrationSecretStore.setTorBoxCredentialStorageMode(
    mode: IntegrationStorageMode,
) {
    setStorageMode(IntegrationSecretKey.DEBRID_API_KEY_TORBOX, mode)
    setStorageMode(IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY, mode)
}
