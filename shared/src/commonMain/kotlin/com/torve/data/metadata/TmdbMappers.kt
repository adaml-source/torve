package com.torve.data.metadata

import com.torve.domain.model.CastMember
import com.torve.domain.model.Genre
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.PersonSummary
import com.torve.domain.model.Season

object TmdbMappers {

    fun posterUrl(path: String?, size: String = "w500"): String? =
        path?.let { "${TmdbApiClient.IMAGE_BASE}/$size$it" }

    fun backdropUrl(path: String?, size: String = "w1280"): String? =
        path?.let { "${TmdbApiClient.IMAGE_BASE}/$size$it" }

    fun profileUrl(path: String?, size: String = "w185"): String? =
        path?.let { "${TmdbApiClient.IMAGE_BASE}/$size$it" }

    fun movieToMediaItem(m: TmdbMovie): MediaItem {
        val trailer = m.videos?.results?.firstOrNull { v ->
            v.site == "YouTube" && v.type == "Trailer"
        }
        val director = m.credits?.crew?.firstOrNull { it.job == "Director" }
        return MediaItem(
            id = m.id.toString(),
            tmdbId = m.id,
            imdbId = m.imdbId,
            type = MediaType.MOVIE,
            title = m.title,
            year = m.releaseDate?.take(4)?.toIntOrNull(),
            overview = m.overview,
            posterUrl = posterUrl(m.posterPath),
            backdropUrl = backdropUrl(m.backdropPath),
            rating = m.voteAverage,
            voteCount = m.voteCount,
            runtime = m.runtime,
            genres = m.genres?.map { Genre(it.id, it.name) } ?: emptyList(),
            genreIds = m.genreIds,
            cast = (m.credits?.cast ?: emptyList()).take(20).map { c ->
                CastMember(
                    id = c.id,
                    name = c.name,
                    character = c.character,
                    profileUrl = profileUrl(c.profilePath),
                )
            },
            director = director?.name,
            directorId = director?.id,
            releaseDate = m.releaseDate,
            status = m.status,
            trailerKey = trailer?.key,
            tagline = m.tagline,
            popularity = m.popularity,
            ratings = m.voteAverage?.let { MediaRatings(tmdbScore = it.toFloat()) },
        )
    }

    fun tvToMediaItem(t: TmdbTv): MediaItem {
        val trailer = t.videos?.results?.firstOrNull { v ->
            v.site == "YouTube" && v.type == "Trailer"
        }
        val director = t.credits?.crew?.firstOrNull { it.job == "Director" }
        return MediaItem(
            id = t.id.toString(),
            tmdbId = t.id,
            imdbId = t.externalIds?.imdbId,
            type = MediaType.SERIES,
            title = t.name,
            year = t.firstAirDate?.take(4)?.toIntOrNull(),
            overview = t.overview,
            posterUrl = posterUrl(t.posterPath),
            backdropUrl = backdropUrl(t.backdropPath),
            rating = t.voteAverage,
            voteCount = t.voteCount,
            genres = t.genres?.map { Genre(it.id, it.name) } ?: emptyList(),
            genreIds = t.genreIds,
            cast = (t.credits?.cast ?: emptyList()).take(20).map { c ->
                CastMember(
                    id = c.id,
                    name = c.name,
                    character = c.character,
                    profileUrl = profileUrl(c.profilePath),
                )
            },
            director = director?.name,
            directorId = director?.id,
            releaseDate = t.firstAirDate,
            status = t.status,
            trailerKey = trailer?.key,
            tagline = t.tagline,
            popularity = t.popularity,
            seasons = t.seasons?.map { s ->
                Season(
                    seasonNumber = s.seasonNumber,
                    episodeCount = s.episodeCount,
                    name = s.name,
                    posterUrl = posterUrl(s.posterPath),
                    overview = s.overview,
                    airDate = s.airDate,
                )
            } ?: emptyList(),
            ratings = t.voteAverage?.let { MediaRatings(tmdbScore = it.toFloat()) },
        )
    }

    fun personCreditToMediaItem(c: TmdbPersonCastCredit): MediaItem {
        val date = c.releaseDate ?: c.firstAirDate
        return MediaItem(
            id = c.id.toString(),
            tmdbId = c.id,
            type = if (c.mediaType == "tv") MediaType.SERIES else MediaType.MOVIE,
            title = c.title ?: c.name ?: "",
            year = date?.take(4)?.toIntOrNull(),
            posterUrl = posterUrl(c.posterPath),
            backdropUrl = backdropUrl(c.backdropPath),
            rating = c.voteAverage,
            releaseDate = date,
            popularity = c.popularity,
            ratings = c.voteAverage?.let { MediaRatings(tmdbScore = it.toFloat()) },
        )
    }

    fun personSummaryToDomain(p: TmdbPersonSummary): PersonSummary = PersonSummary(
        id = p.id,
        name = p.name,
        profileUrl = profileUrl(p.profilePath),
        knownForDepartment = p.knownForDepartment,
    )

    fun personCrewCreditToMediaItem(c: TmdbPersonCrewCredit): MediaItem {
        val date = c.releaseDate ?: c.firstAirDate
        return MediaItem(
            id = c.id.toString(),
            tmdbId = c.id,
            type = if (c.mediaType == "tv") MediaType.SERIES else MediaType.MOVIE,
            title = c.title ?: c.name ?: "",
            year = date?.take(4)?.toIntOrNull(),
            posterUrl = posterUrl(c.posterPath),
            backdropUrl = backdropUrl(c.backdropPath),
            rating = c.voteAverage,
            releaseDate = date,
            popularity = c.popularity,
            ratings = c.voteAverage?.let { MediaRatings(tmdbScore = it.toFloat()) },
        )
    }

    fun multiToMediaItem(r: TmdbMultiResult): MediaItem {
        val date = r.releaseDate ?: r.firstAirDate
        return MediaItem(
            id = r.id.toString(),
            tmdbId = r.id,
            type = if (r.mediaType == "tv") MediaType.SERIES else MediaType.MOVIE,
            title = r.title ?: r.name ?: "",
            year = date?.take(4)?.toIntOrNull(),
            overview = r.overview,
            posterUrl = posterUrl(r.posterPath),
            backdropUrl = backdropUrl(r.backdropPath),
            rating = r.voteAverage,
            voteCount = r.voteCount,
            genreIds = r.genreIds,
            releaseDate = date,
            popularity = r.popularity,
            ratings = r.voteAverage?.let { MediaRatings(tmdbScore = it.toFloat()) },
        )
    }
}
