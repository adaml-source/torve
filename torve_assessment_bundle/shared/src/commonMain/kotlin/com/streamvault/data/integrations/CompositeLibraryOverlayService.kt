package com.streamvault.data.integrations

import com.streamvault.domain.integrations.IntegrationSecretKey
import com.streamvault.domain.integrations.IntegrationSecretStore
import com.streamvault.domain.integrations.LibraryOverlayService
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.WatchProgress
import com.streamvault.domain.repository.PreferencesRepository

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
        // Route based on URL: Plex default port is 32400
        return if (serverUrl.contains(":32400") || serverUrl.lowercase().contains("plex")) {
            plex.testConnection(serverUrl, apiKey)
        } else {
            jellyfin.testConnection(serverUrl, apiKey)
        }
    }
}
