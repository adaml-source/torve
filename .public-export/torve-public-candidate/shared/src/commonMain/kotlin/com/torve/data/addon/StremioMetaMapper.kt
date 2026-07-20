package com.torve.data.addon

import com.torve.domain.model.Genre
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType

/**
 * Maps Stremio catalog meta objects to the app's MediaItem domain model.
 */
fun StremioMeta.toMediaItem(): MediaItem {
    val mediaType = when (type.lowercase()) {
        "movie" -> MediaType.MOVIE
        "series", "tv" -> MediaType.SERIES
        else -> MediaType.MOVIE
    }

    val parsedYear = releaseInfo?.take(4)?.toIntOrNull()
        ?: year?.take(4)?.toIntOrNull()

    val parsedRating = imdbRating?.toDoubleOrNull()

    val imdbId = if (id.startsWith("tt")) id else null

    val genreObjects = genres.map { name ->
        Genre(id = name.hashCode(), name = name)
    }

    return MediaItem(
        id = id,
        imdbId = imdbId,
        type = mediaType,
        title = name,
        year = parsedYear,
        overview = description,
        posterUrl = poster,
        backdropUrl = background ?: poster,
        rating = parsedRating,
        ratings = parsedRating?.let { com.torve.domain.model.MediaRatings(imdbScore = it.toFloat()) },
        genres = genreObjects,
        releaseDate = releaseInfo,
        director = director.firstOrNull(),
    )
}
