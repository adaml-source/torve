package com.torve.data.sourceavailability

import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.MediaType
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.repository.StreamRepository
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.domain.sourceavailability.SourceAvailabilityProvider
import com.torve.domain.sourceavailability.SourceAvailabilityRankBoost
import com.torve.domain.sourceavailability.SourceAvailabilitySignal
import com.torve.presentation.panda.PandaSetupUiState

/**
 * Reports a Usenet-ready hit when the acceleration backend advertises a
 * NzbDAV-provenance candidate for this title in any non-`UNAVAILABLE`
 * readiness state.
 *
 * UNCONFIGURED-silent: returns null when the Panda config isn't in edit
 * mode (no usenet stack on file). State source is the same singleton
 * [PandaSetupUiState] mirror that drives `PandaUsenetProviderHealthChecker`,
 * so this provider's verdict can never claim Usenet-readiness on a
 * device that hasn't completed Panda setup.
 */
class UsenetReadySourceAvailabilityProvider(
    private val streamRepository: StreamRepository,
    private val pandaStateSource: () -> PandaSetupUiState,
    private val tmdbToImdbResolver: suspend (Int, MediaType) -> String?,
) : SourceAvailabilityProvider {

    override val kind: SourceAvailabilityKind = SourceAvailabilityKind.USENET_READY

    override suspend fun probe(tmdbId: Int, mediaType: MediaType): SourceAvailabilitySignal? {
        val pandaState = pandaStateSource()
        if (!pandaState.isEditMode) return null
        val imdb = runCatching { tmdbToImdbResolver(tmdbId, mediaType) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val request = SourceAccelerationRequest(mediaType = mediaType, imdbId = imdb)
        val snapshot = runCatching { streamRepository.getStartupCandidates(request) }.getOrNull()
            ?: return null
        if (snapshot.isEmpty) return null
        val usenetReady = snapshot.candidates.any { candidate ->
            candidate.provenance.kind == CandidateProvenanceKind.USENET_NZBDAV &&
                candidate.readinessState != ReadinessState.UNAVAILABLE
        }
        return if (usenetReady) {
            SourceAvailabilitySignal(
                kind = SourceAvailabilityKind.USENET_READY,
                badge = "Usenet ready",
                rankBoost = SourceAvailabilityRankBoost.USENET_READY,
            )
        } else null
    }
}
