package com.torve.data.addon

import com.torve.domain.model.Genre
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.Season
import com.torve.domain.model.Episode
import com.torve.domain.model.CastMember

/**
 * Maps Stremio catalog meta objects to the app's MediaItem domain model.
 */
fun StremioMeta.toMediaItem(): MediaItem {
    val notice = catalogueNoticeOrNull()
    val mediaType = when (type.lowercase()) {
        "movie" -> MediaType.MOVIE
        "series", "tv" -> MediaType.SERIES
        else -> MediaType.MOVIE
    }

    val parsedYear = releaseInfo?.take(4)?.toIntOrNull()
        ?: year?.take(4)?.toIntOrNull()

    val parsedRating = imdbRating?.toDoubleOrNull()

    val imdbId = this.imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }
        ?: if (id.startsWith("tt")) id else null

    val credits = (appExtras?.cast.orEmpty() + cast.map { StremioAppCast(name = it) })
        .map { it.copy(name = it.name.trim()) }.filter { it.name.isNotEmpty() }.distinctBy { it.name.lowercase() }

    val genreObjects = genres.map { name ->
        Genre(id = name.hashCode(), name = name)
    }

    return MediaItem(
        id = id,
        imdbId = imdbId,
        type = mediaType,
        title = name,
        catalogueNotice = notice,
        isContentPlaceholder = notice != null,
        year = parsedYear,
        overview = description,
        posterUrl = poster,
        backdropUrl = background ?: poster,
        rating = parsedRating,
        ratings = parsedRating?.let { com.torve.domain.model.MediaRatings(imdbScore = it.toFloat()) },
        genres = genreObjects,
        releaseDate = releaseInfo,
        director = director.firstOrNull(),
        // Extensions supply names, not TMDB person IDs. Negative IDs keep these
        // display credits distinct from navigable TMDB people.
        cast = credits.mapIndexed { index, credit ->
            CastMember(id = -index - 1, name = credit.name, character = credit.character, profileUrl = credit.photo)
        },
        logoUrl = logo,
        runtime = runtime?.substringBefore(' ')?.toIntOrNull(),
        seasons = videos.filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
            .groupBy { it.season!! }.toList().sortedBy { it.first }.map { (number, entries) ->
                Season(seasonNumber = number, episodeCount = entries.maxOf { it.episode!! },
                    episodes = entries.distinctBy { it.episode }.sortedBy { it.episode }.map {
                        Episode(episodeNumber = it.episode!!, name = it.title, overview = it.overview.orEmpty(),
                            stillUrl = it.thumbnail, airDate = it.released)
                    })
            },
    )
}

/** Providers sometimes return a configuration error using the movie metadata shape. */
fun StremioMeta.catalogueNoticeOrNull(): String? {
    if (id.matches(Regex("tt[0-9]+")) || id.toIntOrNull() != null) return null
    val label = name.trim().lowercase()
    val errorLabel = label in setOf("ai error", "error", "configuration error", "api error", "invalid api key", "rate limit exceeded")
    if (!errorLabel || description.isNullOrBlank()) return null
    return description.trim().takeIf { text ->
        label == "ai error" || Regex("(?i)\\b(model|api|quota|invalid|configuration|key|failed|unavailable)\\b").containsMatchIn(text)
    }
}
