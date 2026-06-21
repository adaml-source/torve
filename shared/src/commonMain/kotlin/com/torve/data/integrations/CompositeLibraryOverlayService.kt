package com.torve.data.integrations

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.PreferencesRepository

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
        // Try both services — return true if either connects
        val plexOk = runCatching { plex.testConnection(serverUrl, apiKey) }.getOrDefault(false)
        if (plexOk) return true
        return runCatching { jellyfin.testConnection(serverUrl, apiKey) }.getOrDefault(false)
    }
}
