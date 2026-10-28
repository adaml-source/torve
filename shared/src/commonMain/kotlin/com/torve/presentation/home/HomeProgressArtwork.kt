package com.torve.presentation.home

import com.torve.domain.model.WatchProgress
import com.torve.domain.model.MediaItem
import com.torve.domain.model.extractTmdbIdOrNull

/** Empty posters take precedence over optional synopsis/backdrop repairs in a bounded batch. */
internal fun List<MediaItem>.prioritizeHomeArtwork(): List<MediaItem> =
    sortedBy { if (it.posterUrl.isNullOrBlank()) 0 else 1 }

/** A progress sync must not erase artwork already resolved on this device. */
internal fun List<WatchProgress>.withHomeArtworkFrom(previous: List<WatchProgress>): List<WatchProgress> {
    fun WatchProgress.key() = mediaType to (mediaId.extractTmdbIdOrNull()?.toString() ?: mediaId)
    val known = previous.associateBy { it.key() }
    return map { current ->
        val cached = known[current.key()]
        if (cached == null) current else current.copy(
            posterUrl = current.posterUrl.takeUnless { it.isNullOrBlank() } ?: cached.posterUrl,
            backdropUrl = current.backdropUrl.takeUnless { it.isNullOrBlank() } ?: cached.backdropUrl,
        )
    }
}
