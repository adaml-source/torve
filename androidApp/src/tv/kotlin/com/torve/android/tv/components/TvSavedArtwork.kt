package com.torve.android.tv.components

import com.torve.domain.model.*

/** Reuse local library presentation even when an optional metadata provider is off. */
internal fun tvSavedArtwork(item: MediaItem, saved: List<WatchlistItem>, history: List<WatchHistoryEntry>): MediaItem {
    val id = item.tmdbId ?: item.id.extractTmdbIdOrNull()
    val imdb = item.imdbId ?: item.id.extractImdbIdOrNull()
    val library = saved.firstOrNull { it.mediaType == item.type &&
        ((id != null && it.tmdbId == id) || it.mediaId == item.id || (imdb != null && it.imdbId == imdb)) }
    val watched = history.firstOrNull { MediaType.fromString(it.mediaType) == item.type && !it.isContentPlaceholder &&
        (!it.posterUrl.isNullOrBlank() || !it.backdropUrl.isNullOrBlank()) }
    return item.copy(
        posterUrl = item.posterUrl?.takeIf { it.isNotBlank() } ?: library?.posterUrl?.takeIf { it.isNotBlank() } ?: watched?.posterUrl,
        backdropUrl = item.backdropUrl?.takeIf { it.isNotBlank() } ?: library?.backdropUrl?.takeIf { it.isNotBlank() } ?: watched?.backdropUrl,
        imdbId = item.imdbId ?: library?.imdbId,
        year = item.year ?: library?.year,
    )
}
