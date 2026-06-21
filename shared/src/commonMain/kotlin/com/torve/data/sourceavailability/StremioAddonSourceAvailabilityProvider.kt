package com.torve.data.sourceavailability

import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.MediaType
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.StreamRepository
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.domain.sourceavailability.SourceAvailabilityProvider
import com.torve.domain.sourceavailability.SourceAvailabilityRankBoost
import com.torve.domain.sourceavailability.SourceAvailabilitySignal

/**
 * Reports an addon-source hit when at least one Stremio addon advertises
 * a stream for this title — including direct (addon-hosted) playable
 * URLs and uncached debrid-attached candidates.
 *
 * UNCONFIGURED-silent: returns null when the user has no enabled addons.
 *
 * Distinct from [DebridCacheSourceAvailabilityProvider]:
 *   - this fires when there's an addon source even if not yet cached;
 *   - the debrid provider fires when at least one cached candidate exists.
 *
 * Both can fire on the same title — the ranker picks the highest boost
 * (DEBRID_CACHE wins over STREMIO_ADDON when both apply).
 */
class StremioAddonSourceAvailabilityProvider(
    private val addonRepository: AddonRepository,
    private val streamRepository: StreamRepository,
    private val tmdbToImdbResolver: suspend (Int, MediaType) -> String?,
) : SourceAvailabilityProvider {

    override val kind: SourceAvailabilityKind = SourceAvailabilityKind.STREMIO_ADDON

    override suspend fun probe(tmdbId: Int, mediaType: MediaType): SourceAvailabilitySignal? {
        val enabledAddons = runCatching { addonRepository.getEnabledAddons() }.getOrDefault(emptyList())
        if (enabledAddons.isEmpty()) return null
        val imdb = runCatching { tmdbToImdbResolver(tmdbId, mediaType) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val request = SourceAccelerationRequest(mediaType = mediaType, imdbId = imdb)
        val snapshot = runCatching { streamRepository.getStartupCandidates(request) }.getOrNull()
            ?: return null
        if (snapshot.isEmpty) return null
        // Any candidate that isn't a Usenet candidate counts as addon-source —
        // the candidate set is built from the user's enabled addons + their
        // debrid attachments. We deliberately include uncached debrid here:
        // the addon advertised a source, even if it'll need a quick unrestrict.
        val addonHit = snapshot.candidates.any { candidate ->
            candidate.provenance.kind != CandidateProvenanceKind.USENET_NZBDAV &&
                candidate.readinessState != ReadinessState.UNAVAILABLE
        }
        return if (addonHit) {
            SourceAvailabilitySignal(
                kind = SourceAvailabilityKind.STREMIO_ADDON,
                badge = "Addon source",
                rankBoost = SourceAvailabilityRankBoost.STREMIO_ADDON,
            )
        } else null
    }
}
