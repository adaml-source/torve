package com.torve.data.sourceavailability

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.MediaType
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.repository.StreamRepository
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.domain.sourceavailability.SourceAvailabilityProvider
import com.torve.domain.sourceavailability.SourceAvailabilityRankBoost
import com.torve.domain.sourceavailability.SourceAvailabilitySignal

/**
 * Reports a debrid-cache hit when at least one acceleration candidate
 * for this title comes back as cached on a debrid provider.
 *
 * UNCONFIGURED-silent: returns null when no debrid API key is on file —
 * we never burn an acceleration request announcing "user has no debrid".
 *
 * Privacy: the call goes through Torve's own backend
 * (`/me/acceleration/startup`); the user's debrid API key never leaves
 * the device on this path.
 */
class DebridCacheSourceAvailabilityProvider(
    private val streamRepository: StreamRepository,
    private val secretStore: IntegrationSecretStore,
    private val tmdbToImdbResolver: suspend (Int, MediaType) -> String?,
) : SourceAvailabilityProvider {

    override val kind: SourceAvailabilityKind = SourceAvailabilityKind.DEBRID_CACHE

    override suspend fun probe(tmdbId: Int, mediaType: MediaType): SourceAvailabilitySignal? {
        if (!hasAnyDebridKey()) return null
        val imdb = runCatching { tmdbToImdbResolver(tmdbId, mediaType) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val request = SourceAccelerationRequest(mediaType = mediaType, imdbId = imdb)
        val snapshot = runCatching { streamRepository.getStartupCandidates(request) }.getOrNull()
            ?: return null
        if (snapshot.isEmpty) return null
        val cachedDebrid = snapshot.candidates.any { candidate ->
            val isCached = candidate.isKnownCached || candidate.readinessState == ReadinessState.READY_NOW
            val isDebrid = candidate.provenance.kind != CandidateProvenanceKind.USENET_NZBDAV
            isCached && isDebrid
        }
        return if (cachedDebrid) {
            SourceAvailabilitySignal(
                kind = SourceAvailabilityKind.DEBRID_CACHE,
                badge = "Cached",
                rankBoost = SourceAvailabilityRankBoost.DEBRID_CACHE,
            )
        } else null
    }

    private suspend fun hasAnyDebridKey(): Boolean {
        return DEBRID_KEYS.any { key ->
            !runCatching { secretStore.get(key) }.getOrNull().isNullOrBlank()
        }
    }

    companion object {
        private val DEBRID_KEYS = listOf(
            IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID,
            IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID,
            IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE,
            IntegrationSecretKey.DEBRID_API_KEY_TORBOX,
        )
    }
}
