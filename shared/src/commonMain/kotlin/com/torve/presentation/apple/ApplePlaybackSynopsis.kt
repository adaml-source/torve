package com.torve.presentation.apple

import com.torve.domain.model.MediaType
import com.torve.presentation.detail.DetailUiState

data class ApplePlaybackSynopsis(val title: String, val overview: String, val source: String? = null)

object ApplePlaybackSynopsisPolicy {
    fun preferFull(base: ApplePlaybackSynopsis, candidate: String?, source: String): ApplePlaybackSynopsis {
        val text = candidate?.trim()?.takeUnless { it.equals("N/A", true) }.orEmpty()
        return if (text.length > base.overview.trim().length) base.copy(overview = text, source = source) else base
    }
    /** Never substitute a series or another episode's plot when exact episode metadata is absent. */
    fun from(state: DetailUiState, season: Int?, episode: Int?): ApplePlaybackSynopsis? {
        val item = state.mediaItem ?: return null
        if (item.type == MediaType.MOVIE) return ApplePlaybackSynopsis(item.title, item.overview.orEmpty())
        if (season == null || episode == null) return null
        val group = state.seasonDetail?.takeIf { it.seasonNumber == season }
            ?: item.seasons.firstOrNull { it.seasonNumber == season }
        val exact = group?.episodes?.firstOrNull { it.episodeNumber == episode }
        return ApplePlaybackSynopsis("${item.title} · S$season E$episode", exact?.overview.orEmpty())
    }
}
