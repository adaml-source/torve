package com.torve.android.tv

import com.torve.domain.model.MediaItem

/** Only presentation fields change; saved IDs and calendar episode labels remain authoritative. */
internal fun MediaItem.withTvHeroDetail(detail: MediaItem): MediaItem = copy(
    tmdbId = tmdbId ?: detail.tmdbId,
    imdbId = imdbId ?: detail.imdbId,
    posterUrl = posterUrl?.takeIf { it.isNotBlank() } ?: detail.posterUrl,
    backdropUrl = backdropUrl?.takeIf { it.isNotBlank() } ?: detail.backdropUrl,
    logoUrl = logoUrl?.takeIf { it.isNotBlank() } ?: detail.logoUrl,
    overview = overview?.takeIf { it.isNotBlank() } ?: detail.overview,
    rating = rating ?: detail.rating,
    ratings = ratings ?: detail.ratings,
    genres = genres.ifEmpty { detail.genres },
    runtime = runtime ?: detail.runtime,
    year = year ?: detail.year,
    cast = cast.ifEmpty { detail.cast },
    titlelessBackdropUrl = titlelessBackdropUrl ?: detail.titlelessBackdropUrl,
)
