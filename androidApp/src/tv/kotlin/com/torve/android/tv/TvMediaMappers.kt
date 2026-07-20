package com.torve.android.tv

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.domain.model.WatchlistItem
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull

fun WatchProgress.isTvCatalogProgress(): Boolean =
    !mediaId.startsWith("sports:", ignoreCase = true)

internal fun preferredTvPosterUrl(posterUrl: String?, backdropUrl: String?): String? =
    posterUrl?.takeIf { it.isNotBlank() }
        ?: backdropUrl?.takeIf { it.isNotBlank() }

fun WatchProgress.toMediaItemOrNull(): MediaItem? {
    if (!isTvCatalogProgress()) return null
    val tmdbId = mediaId.extractTmdbIdOrNull()
    return MediaItem(
        id = mediaId,
        tmdbId = tmdbId,
        imdbId = mediaId.extractImdbIdOrNull(),
        type = mediaType,
        title = if (mediaType == MediaType.SERIES) showTitle ?: title else title,
        year = null,
        overview = null,
        posterUrl = preferredTvPosterUrl(posterUrl, backdropUrl),
        backdropUrl = backdropUrl?.takeIf { it.isNotBlank() },
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
        posterUrl = preferredTvPosterUrl(posterUrl, backdropUrl),
        backdropUrl = backdropUrl?.takeIf { it.isNotBlank() },
        rating = rating,
    )
}
