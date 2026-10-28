package com.torve.android.ui.player

import com.torve.domain.model.MediaType
import com.torve.presentation.apple.ApplePlaybackSynopsis
import com.torve.presentation.apple.ApplePlaybackSynopsisPolicy
import com.torve.presentation.detail.DetailUiState

/** Small, owner-scoped metadata snapshots for the retained native player. No source URLs are stored. */
internal object PlaybackMetadataStore {
    private var owner: String? = null
    private val snapshots = linkedMapOf<Pair<MediaType, String>, DetailUiState>()

    @Synchronized fun stage(dataOwner: String, state: DetailUiState) {
        val item = state.mediaItem ?: return
        if (owner != dataOwner) { snapshots.clear(); owner = dataOwner }
        val key = item.type to item.id
        snapshots.remove(key)
        snapshots[key] = DetailUiState(mediaItem = item, seasonDetail = state.seasonDetail)
        while (snapshots.size > 16) snapshots.remove(snapshots.keys.first())
    }

    @Synchronized fun snapshot(dataOwner: String, mediaId: String, type: MediaType): DetailUiState? =
        if (owner == dataOwner) snapshots[type to mediaId] else null

    fun synopsis(dataOwner: String, mediaId: String, type: MediaType, season: Int?, episode: Int?): ApplePlaybackSynopsis? =
        snapshot(dataOwner, mediaId, type)?.let { ApplePlaybackSynopsisPolicy.from(it, season, episode) }
}
