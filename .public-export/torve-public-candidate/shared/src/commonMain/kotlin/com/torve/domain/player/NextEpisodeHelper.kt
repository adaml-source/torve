package com.torve.domain.player

import com.torve.domain.model.Season

data class NextEpisodeInfo(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeName: String,
    val isNewSeason: Boolean,
)

object NextEpisodeHelper {

    /**
     * Given the current season/episode and the show's season list (with episodes populated),
     * returns the next episode info, or null if this is the last episode of the series.
     */
    fun getNextEpisode(
        currentSeason: Int,
        currentEpisode: Int,
        seasons: List<Season>,
    ): NextEpisodeInfo? {
        val validSeasons = seasons
            .filter { it.seasonNumber > 0 }
            .sortedBy { it.seasonNumber }

        val currentSeasonObj = validSeasons.find { it.seasonNumber == currentSeason }
            ?: return null

        // Check next episode in current season
        val nextEpNum = currentEpisode + 1
        val nextEpInSeason = currentSeasonObj.episodes.find { it.episodeNumber == nextEpNum }
        if (nextEpInSeason != null) {
            return NextEpisodeInfo(
                seasonNumber = currentSeason,
                episodeNumber = nextEpNum,
                episodeName = nextEpInSeason.name,
                isNewSeason = false,
            )
        }

        // Current season exhausted — try first episode of next season
        val nextSeasonObj = validSeasons.firstOrNull { it.seasonNumber > currentSeason }
            ?: return null

        val firstEp = nextSeasonObj.episodes.firstOrNull()
            ?: return null

        return NextEpisodeInfo(
            seasonNumber = nextSeasonObj.seasonNumber,
            episodeNumber = firstEp.episodeNumber,
            episodeName = firstEp.name,
            isNewSeason = true,
        )
    }
}
