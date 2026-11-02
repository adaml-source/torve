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
fun StremioMeta.toCatalogueMediaItem(manifestId: String): MediaItem {
    val item = toMediaItem()
    // TMDB Addon 3.x parseMedia puts TMDB vote_average in imdbRating.
    // Its /meta response instead performs an IMDb lookup. Scope the workaround
    // to catalogue records from that manifest; explicit provider ratings win.
    return if (manifestId == "tmdb-addon" && nativeRatings == null) item.copy(
        ratings = item.rating?.takeIf { it > 0 && it <= 10 }?.let {
            com.torve.domain.model.MediaRatings(tmdbScore = it.toFloat())
        }) else item
}

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
        tmdbId = id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.toIntOrNull()?.takeIf { it > 0 },
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
        ratings = nativeRatings ?: parsedRating?.let { com.torve.domain.model.MediaRatings(imdbScore = it.toFloat()) },
        presentationPending = presentationPending,
        genres = genreObjects,
        releaseDate = releaseInfo,
        director = director.firstOrNull(),
        // Extensions supply names, not TMDB person IDs. Negative IDs keep these
        // display credits distinct from navigable TMDB people.
        cast = credits.mapIndexed { index, credit ->
            CastMember(id = -index - 1, name = credit.name, character = credit.character, profileUrl = credit.photo)
        },
        logoUrl = logo,
        trailerKey = (trailerStreams + trailers).firstNotNullOfOrNull { trailer ->
            val value = trailer.ytId ?: trailer.source
            val key = when {
                value.startsWith("https://youtu.be/") -> value.substringAfter("youtu.be/").substringBefore('?')
                value.startsWith("https://www.youtube.com/watch?") || value.startsWith("https://youtube.com/watch?") ->
                    value.substringAfter("v=", "").substringBefore('&')
                else -> value
            }
            key.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,64}")) }
        },
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
