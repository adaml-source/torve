package com.torve.data.sourceavailability

import com.torve.domain.model.MediaType
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.domain.sourceavailability.SourceAvailabilityProvider
import com.torve.domain.sourceavailability.SourceAvailabilityRankBoost
import com.torve.domain.sourceavailability.SourceAvailabilitySignal

/**
 * Reports "Watched" when the user has at least one history entry for
 * this title. Lowest rank-boost in the family — never beats a real
 * playback path. Surfaces only when no other provider fires, so the
 * row gets a "Watched" badge instead of pretending nothing is known.
 *
 * Match rule: `WatchHistoryEntry.mediaId` is stored as the user-facing
 * tmdbId string; mediaType is the lowercase string form. We accept a
 * couple of variants so legacy rows from before the canonicalization
 * pass still match.
 */
class WatchHistorySourceAvailabilityProvider(
    private val repository: WatchHistoryRepository,
) : SourceAvailabilityProvider {

    override val kind: SourceAvailabilityKind = SourceAvailabilityKind.WATCH_HISTORY

    override suspend fun probe(tmdbId: Int, mediaType: MediaType): SourceAvailabilitySignal? {
        val candidateIds = setOf(tmdbId.toString(), "tmdb:$tmdbId")
        val rows = runCatching { repository.getForMedia(tmdbId.toString()) }.getOrDefault(emptyList())
        val match = rows.any { entry ->
            entry.mediaId in candidateIds &&
                entry.mediaType.equals(mediaType.shortName(), ignoreCase = true)
        }
        return if (match) {
            SourceAvailabilitySignal(
                kind = SourceAvailabilityKind.WATCH_HISTORY,
                badge = "Watched",
                rankBoost = SourceAvailabilityRankBoost.WATCH_HISTORY,
            )
        } else null
    }

    private fun MediaType.shortName(): String = when (this) {
        MediaType.MOVIE -> "movie"
        MediaType.SERIES -> "tv"
    }
}
