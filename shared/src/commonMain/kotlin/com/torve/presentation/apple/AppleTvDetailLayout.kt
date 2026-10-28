package com.torve.presentation.apple

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.presentation.detail.DetailUiState
import com.torve.presentation.detail.episodeKey

/** Keep the native detail rail connected to the profile-filtered shared recommendations. */
internal object AppleTvDetailLayout {
    fun episodes(state: DetailUiState): List<AppleTvRow> = state.seasonDetail?.episodes.orEmpty().map { episode ->
        val watched = episodeKey(state.selectedSeason, episode.episodeNumber) in state.watchedEpisodes
        val hidden = state.hideUnwatchedSpoilers && !watched
        AppleTvRow("s${state.selectedSeason}e${episode.episodeNumber}",
            if (hidden) "Episode ${episode.episodeNumber}" else episode.name,
            "S${state.selectedSeason} E${episode.episodeNumber}" + (episode.airDate?.let { " · $it" } ?: ""),
            episode.stillUrl.takeUnless { hidden }, "episode", state.selectedSeason, episode.episodeNumber,
            targetId = state.mediaItem?.id.orEmpty(), overview = episode.overview.takeUnless { hidden }, watched = watched)
    }

    fun related(state: DetailUiState, row: (MediaItem) -> AppleTvRow): AppleTvShelf {
        val current = state.mediaItem
        val items = state.similar
            .filter { it.id.isNotBlank() && (it.id != current?.id || it.type != current.type) }
            .distinctBy { it.type to it.id }
        return AppleTvShelf("related", if (current?.type == MediaType.SERIES) "Similar TV shows" else "Similar movies",
            items.map(row), emptyMessage = "No related titles are available yet.")
    }
}
