package com.torve.data.sourceavailability

import com.torve.data.integrations.JellyfinLibraryOverlayService
import com.torve.data.integrations.PlexLibraryOverlayService
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaType
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.domain.sourceavailability.SourceAvailabilityProvider
import com.torve.domain.sourceavailability.SourceAvailabilityRankBoost
import com.torve.domain.sourceavailability.SourceAvailabilitySignal

/**
 * Wraps the existing [PlexLibraryOverlayService.isInLibrary] probe.
 * UNCONFIGURED (returns null) when no Plex server URL or token is set —
 * we never call the service unless we have credentials, so we don't burn
 * a network request announcing failure.
 */
class PlexSourceAvailabilityProvider(
    private val service: PlexLibraryOverlayService,
    private val prefs: PreferencesRepository,
    private val secretStore: IntegrationSecretStore,
) : SourceAvailabilityProvider {

    override val kind: SourceAvailabilityKind = SourceAvailabilityKind.PLEX

    override suspend fun probe(tmdbId: Int, mediaType: MediaType): SourceAvailabilitySignal? {
        val url = runCatching { prefs.getString("plex_server_url") }.getOrNull()
        val token = runCatching { secretStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN) }.getOrNull()
        if (url.isNullOrBlank() || token.isNullOrBlank()) return null
        val present = runCatching { service.isInLibrary(tmdbId, mediaType) }.getOrDefault(false)
        return if (present) {
            SourceAvailabilitySignal(
                kind = SourceAvailabilityKind.PLEX,
                badge = "In Plex",
                rankBoost = SourceAvailabilityRankBoost.PLEX,
            )
        } else null
    }
}

/**
 * Wraps [JellyfinLibraryOverlayService.isInLibrary]. Same UNCONFIGURED-
 * silent rule as Plex.
 */
class JellyfinSourceAvailabilityProvider(
    private val service: JellyfinLibraryOverlayService,
    private val prefs: PreferencesRepository,
    private val secretStore: IntegrationSecretStore,
) : SourceAvailabilityProvider {

    override val kind: SourceAvailabilityKind = SourceAvailabilityKind.JELLYFIN

    override suspend fun probe(tmdbId: Int, mediaType: MediaType): SourceAvailabilitySignal? {
        val url = runCatching { prefs.getString("jellyfin_server_url") }.getOrNull()
        val key = runCatching { secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY) }.getOrNull()
        if (url.isNullOrBlank() || key.isNullOrBlank()) return null
        val present = runCatching { service.isInLibrary(tmdbId, mediaType) }.getOrDefault(false)
        return if (present) {
            SourceAvailabilitySignal(
                kind = SourceAvailabilityKind.JELLYFIN,
                badge = "In Jellyfin",
                rankBoost = SourceAvailabilityRankBoost.JELLYFIN,
            )
        } else null
    }
}
