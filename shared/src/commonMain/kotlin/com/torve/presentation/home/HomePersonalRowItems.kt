package com.torve.presentation.home

import com.torve.domain.model.MediaItem
import com.torve.domain.model.WatchlistItem

/** Keep both provider identities when constructing the lightweight saved row. */
internal fun WatchlistItem.toHomeWatchlistItem() = MediaItem(
    id = mediaId, tmdbId = tmdbId.takeIf { it > 0 }, imdbId = imdbId,
    title = title, posterUrl = posterUrl, backdropUrl = backdropUrl,
    rating = rating, year = year, type = mediaType,
)
