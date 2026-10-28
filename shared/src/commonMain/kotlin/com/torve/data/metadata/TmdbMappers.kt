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

    private fun imageUrl(path: String?, size: String): String? {
        val normalized = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            normalized
        } else {
            val rootedPath = if (normalized.startsWith('/')) normalized else "/$normalized"
            "${TmdbApiClient.IMAGE_BASE}/$size$rootedPath"
        }
    }

    fun posterUrl(path: String?, size: String = "w500"): String? =
        imageUrl(path, size)

    fun backdropUrl(path: String?, size: String = "w1280"): String? =
        imageUrl(path, size)

    fun profileUrl(path: String?, size: String = "w185"): String? =
        imageUrl(path, size)

    fun logoUrl(path: String?, size: String = "w500"): String? =
        imageUrl(path, size)

    /** Title logos contain language-specific text, even when their language tag is missing. */
    fun bestLogoPath(images: TmdbImages?, preferredLanguage: String = "en"): String? {
        val language = preferredLanguage.substringBefore('-').substringBefore('_').lowercase()
        // Unlike a scene backdrop, an untagged logo is not proof of neutral artwork.
        // TMDB can return a foreign title graphic tagged null (for example Korean
        // Spider-Man lettering). Keep only the requested language and English;
        // callers render the localized text title when neither is available.
        return bestImagePath(images?.logos.orEmpty().filter {
            it.iso6391 == language || it.iso6391 == "en"
        }, language)
    }

    /** Localized backdrops often contain the title; only neutral scenes can sit behind a logo. */
    private fun titlelessBackdropPath(images: TmdbImages?): String? =
        bestImagePath(images?.backdrops.orEmpty().filter { it.iso6391.isNullOrBlank() }, "en")

    /** Prefer localized artwork over a higher-rated image in another language. */
    internal fun bestImagePath(images: List<TmdbImageItem>, preferredLanguage: String): String? {
        val logos = images.filter { it.filePath.isNotBlank() }
        if (logos.isEmpty()) return null
        val ranked = logos.sortedWith(
            compareByDescending<TmdbImageItem> { it.voteAverage * it.voteCount }
                .thenByDescending { it.voteCount }
                .thenByDescending { it.width },
        )
        val language = preferredLanguage.substringBefore('-').substringBefore('_').lowercase()
        val best = ranked.firstOrNull { it.iso6391 == language }
            ?: ranked.firstOrNull { it.iso6391.isNullOrBlank() }
            ?: ranked.firstOrNull { it.iso6391 == "en" }
            ?: ranked.firstOrNull()
        return best?.filePath
    }

    fun movieToMediaItem(m: TmdbMovie, preferredLanguage: String = "en"): MediaItem {
        val trailer = pickBestTrailer(m.videos?.results)
        val director = m.credits?.crew?.firstOrNull { it.job == "Director" }
        return MediaItem(
            id = m.id.toString(),
            tmdbId = m.id,
            imdbId = m.imdbId,
            type = MediaType.MOVIE,
            title = m.title,
            collectionId = m.collection?.id,
            collectionName = m.collection?.name,
            adult = m.adult,
            ageCertifications = m.releaseDates?.results.orEmpty().flatMap { c -> c.releases.filter { it.certification.isNotBlank() }.map { com.torve.domain.model.AgeCertification(c.country, it.certification) } },
            year = m.releaseDate?.take(4)?.toIntOrNull(),
            overview = m.overview,
            posterUrl = posterUrl(bestImagePath(m.images?.posters.orEmpty(), preferredLanguage) ?: m.posterPath),
            backdropUrl = backdropUrl(titlelessBackdropPath(m.images) ?: bestImagePath(m.images?.backdrops.orEmpty(), preferredLanguage) ?: m.backdropPath),
            titlelessBackdropUrl = backdropUrl(titlelessBackdropPath(m.images)),
            logoUrl = logoUrl(bestLogoPath(m.images, preferredLanguage)),
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

    fun tvToMediaItem(t: TmdbTv, preferredLanguage: String = "en"): MediaItem {
        val trailer = pickBestTrailer(t.videos?.results)
        val director = t.credits?.crew?.firstOrNull { it.job == "Director" }
        return MediaItem(
            id = t.id.toString(),
            tmdbId = t.id,
            imdbId = t.externalIds?.imdbId,
            type = MediaType.SERIES,
            title = t.name,
            adult = t.adult,
            ageCertifications = t.contentRatings?.results.orEmpty().filter { it.rating.isNotBlank() }.map { com.torve.domain.model.AgeCertification(it.country, it.rating) },
            year = t.firstAirDate?.take(4)?.toIntOrNull(),
            overview = t.overview,
            posterUrl = posterUrl(bestImagePath(t.images?.posters.orEmpty(), preferredLanguage) ?: t.posterPath),
            backdropUrl = backdropUrl(titlelessBackdropPath(t.images) ?: bestImagePath(t.images?.backdrops.orEmpty(), preferredLanguage) ?: t.backdropPath),
            titlelessBackdropUrl = backdropUrl(titlelessBackdropPath(t.images)),
            logoUrl = logoUrl(bestLogoPath(t.images, preferredLanguage)),
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
            creditCharacter = c.character,
            year = date?.take(4)?.toIntOrNull(),
            posterUrl = posterUrl(c.posterPath),
            backdropUrl = backdropUrl(c.backdropPath),
            rating = c.voteAverage,
            releaseDate = date,
            popularity = c.popularity,
            genreIds = c.genreIds,
            voteCount = c.voteCount,
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
            creditJob = c.job,
            creditIsCrew = true,
            year = date?.take(4)?.toIntOrNull(),
            posterUrl = posterUrl(c.posterPath),
            backdropUrl = backdropUrl(c.backdropPath),
            rating = c.voteAverage,
            releaseDate = date,
            popularity = c.popularity,
            genreIds = c.genreIds,
            voteCount = c.voteCount,
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
