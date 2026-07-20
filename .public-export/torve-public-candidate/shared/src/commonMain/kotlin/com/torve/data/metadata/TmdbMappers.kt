package com.torve.data.metadata

import com.torve.domain.model.CastMember
import com.torve.domain.model.Genre
import com.torve.domain.model.MediaCompany
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

    fun logoUrl(path: String?, size: String = "w500"): String? =
        path?.let { "${TmdbApiClient.IMAGE_BASE}/$size$it" }

    /** Pick the best title logo from a TMDB images response. */
    fun bestLogoPath(images: TmdbImages?): String? {
        if (images == null) return null
        val logos = images.logos
        if (logos.isEmpty()) return null
        val ranked = logos.sortedWith(
            compareByDescending<TmdbImageItem> { it.voteAverage * it.voteCount }
                .thenByDescending { it.voteCount }
                .thenByDescending { it.width },
        )
        val best = ranked.firstOrNull { it.iso6391 == "en" }
            ?: ranked.firstOrNull { it.iso6391 == null }
            ?: ranked.firstOrNull()
        return best?.filePath
    }

    fun movieToMediaItem(m: TmdbMovie): MediaItem {
        val trailer = pickBestTrailer(m.videos?.results)
        val director = m.credits?.crew?.firstOrNull { it.job == "Director" }
        return MediaItem(
            id = m.id.toString(),
            tmdbId = m.id,
            imdbId = m.imdbId,
            type = MediaType.MOVIE,
            title = m.title,
            adult = m.adult,
            year = m.releaseDate?.take(4)?.toIntOrNull(),
            overview = m.overview,
            posterUrl = posterUrl(m.posterPath),
            backdropUrl = backdropUrl(m.backdropPath),
            logoUrl = logoUrl(bestLogoPath(m.images)),
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
            directorProfileUrl = profileUrl(director?.profilePath),
            studios = m.productionCompanies.toMediaCompanies(),
            releaseDate = m.releaseDate,
            status = m.status,
            trailerKey = trailer?.key,
            tagline = m.tagline,
            popularity = m.popularity,
            ratings = m.voteAverage?.let { MediaRatings(tmdbScore = it.toFloat()) },
        )
    }

    fun tvToMediaItem(t: TmdbTv): MediaItem {
        val trailer = pickBestTrailer(t.videos?.results)
        val director = t.credits?.crew?.firstOrNull { it.job == "Director" }
        return MediaItem(
            id = t.id.toString(),
            tmdbId = t.id,
            imdbId = t.externalIds?.imdbId,
            type = MediaType.SERIES,
            title = t.name,
            adult = t.adult,
            year = t.firstAirDate?.take(4)?.toIntOrNull(),
            overview = t.overview,
            posterUrl = posterUrl(t.posterPath),
            backdropUrl = backdropUrl(t.backdropPath),
            logoUrl = logoUrl(bestLogoPath(t.images)),
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
            directorProfileUrl = profileUrl(director?.profilePath),
            studios = t.networks.toMediaCompanies(),
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

    private fun List<TmdbCompany>.toMediaCompanies(): List<MediaCompany> =
        filter { it.id > 0 && it.name.isNotBlank() }
            .distinctBy { it.id }
            .map { MediaCompany(it.id, it.name, logoUrl(it.logoPath, size = "w154")) }

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
            adult = r.adult,
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

    /**
     * Pick the best YouTube trailer for the user's locale.
     *
     * The detail request is already filtered to the user's language plus
     * English (see [TmdbApiClient.getMovieDetail]/`getTvDetail`'s
     * `include_video_language` param). With that pre-filter:
     *
     *  1. Prefer official trailers in a non-English language (= the user's
     *     language when it differs from English).
     *  2. Otherwise prefer official English trailers.
     *  3. Otherwise any trailer in the user's language.
     *  4. Otherwise any English trailer.
     *  5. Last resort: any YouTube trailer at all.
     *
     * Always restricted to `site == "YouTube"` and `type == "Trailer"` so
     * we don't accidentally surface a behind-the-scenes clip or a teaser
     * marked as `Featurette`.
     */
    fun pickBestTrailer(videos: List<TmdbVideo>?): TmdbVideo? {
        val candidates = videos?.filter { it.site == "YouTube" && it.type == "Trailer" }
            ?: return null
        if (candidates.isEmpty()) return null
        val officialNonEn = candidates.firstOrNull { it.official && !it.language.equals("en", true) }
        if (officialNonEn != null) return officialNonEn
        val officialEn = candidates.firstOrNull { it.official && it.language.equals("en", true) }
        if (officialEn != null) return officialEn
        val anyNonEn = candidates.firstOrNull { !it.language.equals("en", true) }
        if (anyNonEn != null) return anyNonEn
        val anyEn = candidates.firstOrNull { it.language.equals("en", true) }
        if (anyEn != null) return anyEn
        return candidates.firstOrNull()
    }
}
