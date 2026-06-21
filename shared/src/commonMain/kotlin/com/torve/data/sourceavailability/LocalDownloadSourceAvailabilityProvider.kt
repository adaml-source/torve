package com.torve.data.sourceavailability

import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaType
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.domain.sourceavailability.SourceAvailabilityProvider
import com.torve.domain.sourceavailability.SourceAvailabilityRankBoost
import com.torve.domain.sourceavailability.SourceAvailabilitySignal

/**
 * Reports `Downloaded` when at least one row in [DownloadRepository] for
 * this tmdbId has reached [DownloadStatus.COMPLETED].
 *
 * Match strategy: TMDB-driven downloads encode their tmdbId as the
 * `mediaId` string. We tolerate `mediaId == "<tmdbId>"` and
 * `mediaId == "tmdb:<tmdbId>"`. Anything else (e.g. NZB-prefixed ids
 * like `nzb_<surface>_<hash>`) is intentionally not matched here —
 * Slice A only surfaces "I can hand this exact tmdbId to the player."
 */
class LocalDownloadSourceAvailabilityProvider(
    private val downloadRepository: DownloadRepository,
) : SourceAvailabilityProvider {

    override val kind: SourceAvailabilityKind = SourceAvailabilityKind.LOCAL_DOWNLOAD

    override suspend fun probe(tmdbId: Int, mediaType: MediaType): SourceAvailabilitySignal? {
        val candidateIds = setOf(tmdbId.toString(), "tmdb:$tmdbId")
        val downloads = runCatching { downloadRepository.getCompletedDownloads() }
            .getOrDefault(emptyList())
        val match = downloads.firstOrNull { row ->
            row.status == DownloadStatus.COMPLETED &&
                row.mediaId in candidateIds &&
                row.mediaType == mediaType
        }
        return match?.let {
            SourceAvailabilitySignal(
                kind = SourceAvailabilityKind.LOCAL_DOWNLOAD,
                badge = "Downloaded",
                rankBoost = SourceAvailabilityRankBoost.LOCAL_DOWNLOAD,
            )
        }
    }
}
