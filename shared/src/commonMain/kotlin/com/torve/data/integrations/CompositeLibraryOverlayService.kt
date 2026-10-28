package com.torve.data.integrations

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.integrations.ConnectionProbeResult
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.OptionalAppFeature
import com.torve.presentation.settings.isAppFeatureEnabled

class CompositeLibraryOverlayService(
    val jellyfin: JellyfinLibraryOverlayService,
    val plex: PlexLibraryOverlayService,
    private val prefsRepo: PreferencesRepository,
    private val secretStore: IntegrationSecretStore,
) : LibraryOverlayService {

    private suspend fun activeService(): LibraryOverlayService? {
        val plexUrl = prefsRepo.getString("plex_server_url")
        val plexToken = secretStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN)
        if (!plexUrl.isNullOrBlank() && !plexToken.isNullOrBlank()) return plex

        if (!prefsRepo.isAppFeatureEnabled(OptionalAppFeature.JELLYFIN)) return null
        val jellyUrl = prefsRepo.getString("jellyfin_server_url")
        val jellyKey = secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY)
        if (!jellyUrl.isNullOrBlank() && !jellyKey.isNullOrBlank()) return jellyfin

        return null
    }

    override suspend fun isInLibrary(tmdbId: Int, mediaType: MediaType): Boolean {
        return activeService()?.isInLibrary(tmdbId, mediaType) ?: false
    }

    override suspend fun getContinueWatching(limit: Int): List<WatchProgress> {
        return activeService()?.getContinueWatching(limit) ?: emptyList()
    }

    override suspend fun testConnection(serverUrl: String, apiKey: String): Boolean {
        return probeConnection(serverUrl, apiKey) == ConnectionProbeResult.CONNECTED
    }

    override suspend fun probeConnection(serverUrl: String, apiKey: String): ConnectionProbeResult {
        // A saved server and its matching credential must be checked together.
        if (serverUrl.trimEnd('/') == prefsRepo.getString("plex_server_url")?.trimEnd('/') &&
            apiKey == secretStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN)) return plex.probeConnection(serverUrl, apiKey)
        if (serverUrl.trimEnd('/') == prefsRepo.getString("jellyfin_server_url")?.trimEnd('/') &&
            apiKey == secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY)) return jellyfin.probeConnection(serverUrl, apiKey)
        val first = plex.probeConnection(serverUrl, apiKey)
        if (first == ConnectionProbeResult.CONNECTED) return first
        val second = jellyfin.probeConnection(serverUrl, apiKey)
        if (second == ConnectionProbeResult.CONNECTED) return second
        return if (first == second) first else ConnectionProbeResult.UNKNOWN_FAILURE
    }
}
