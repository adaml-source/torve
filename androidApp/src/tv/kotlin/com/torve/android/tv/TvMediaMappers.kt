package com.torve.android.tv

import com.torve.domain.model.MediaItem
import com.torve.domain.model.WatchProgress
import com.torve.domain.model.WatchlistItem
import com.torve.domain.model.extractTmdbIdOrNull

fun WatchProgress.toMediaItemOrNull(): MediaItem? {
    val tmdbId = mediaId.extractTmdbIdOrNull()
    return MediaItem(
        id = mediaId,
        tmdbId = tmdbId,
        imdbId = null,
        type = mediaType,
        title = title,
        year = null,
        overview = null,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = null,
    )
}

fun WatchlistItem.toMediaItem(): MediaItem {
    return MediaItem(
        id = mediaId,
        tmdbId = tmdbId,
        imdbId = imdbId,
        type = mediaType,
        title = title,
        year = year,
        overview = null,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = rating,
    )
}
