package com.streamvault.data.metadata

import com.streamvault.domain.model.CatalogShelf
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.PagedResult
import com.streamvault.domain.model.PersonSummary
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.ShelfType
import com.streamvault.domain.repository.MetadataRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class MetadataRepositoryImpl(
    private val api: TmdbApiClient,
) : MetadataRepository {
    private val providerLogosBackendBaseUrl: String = "" // Set your backend base URL when available.

    override suspend fun getTrending(type: String, page: Int): List<MediaItem> {
        return if (type == "tv") {
            api.getTrendingTv(page).results.map { TmdbMappers.tvToMediaItem(it) }
        } else {
            api.getTrending(type, page).results.map { TmdbMappers.movieToMediaItem(it) }
        }
    }

    override suspend fun getPopular(type: String, page: Int): List<MediaItem> {
        return if (type == "tv") {
            api.getPopularTv(page).results.map { TmdbMappers.tvToMediaItem(it) }
        } else {
            api.getPopular(type, page).results.map { TmdbMappers.movieToMediaItem(it) }
        }
    }

    override suspend fun getTopRated(type: String, page: Int): List<MediaItem> {
        return if (type == "tv") {
            api.getTopRatedTv(page).results.map { TmdbMappers.tvToMediaItem(it) }
        } else {
            api.getTopRated(type, page).results.map { TmdbMappers.movieToMediaItem(it) }
        }
    }

    override suspend fun getUpcoming(page: Int): List<MediaItem> {
        return api.getUpcoming(page).results.map { TmdbMappers.movieToMediaItem(it) }
    }

    override suspend fun getNowPlaying(page: Int): List<MediaItem> {
        return api.getNowPlaying(page).results.map { TmdbMappers.movieToMediaItem(it) }
    }

    override suspend fun getAiringToday(page: Int): List<MediaItem> {
        return api.getAiringToday(page).results.map { TmdbMappers.tvToMediaItem(it) }
    }

    override suspend fun searchMulti(query: String, page: Int): List<MediaItem> {
        return api.searchMulti(query, page).results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .map { TmdbMappers.multiToMediaItem(it) }
    }

    override suspend fun getDetail(type: String, id: Int): MediaItem {
        return if (type == "movie") {
            TmdbMappers.movieToMediaItem(api.getMovieDetail(id))
        } else {
            TmdbMappers.tvToMediaItem(api.getTvDetail(id))
        }
    }

    override suspend fun getSimilar(type: String, id: Int, page: Int): List<MediaItem> {
        return if (type == "tv") {
            api.getSimilarTv(id, page).results.map { TmdbMappers.tvToMediaItem(it) }
        } else {
            api.getSimilar(type, id, page).results.map { TmdbMappers.movieToMediaItem(it) }
        }
    }

    override suspend fun getRecommendations(type: String, id: Int, page: Int): List<MediaItem> {
        return if (type == "tv") {
            api.getRecommendationsTv(id, page).results.map { TmdbMappers.tvToMediaItem(it) }
        } else {
            api.getRecommendations(type, id, page).results.map { TmdbMappers.movieToMediaItem(it) }
        }
    }

    override suspend fun getPersonCredits(personId: Int): List<MediaItem> {
        val credits = api.getPersonCredits(personId)
        val castItems = credits.cast.map { TmdbMappers.personCreditToMediaItem(it) }
        val crewItems = credits.crew.map { TmdbMappers.personCrewCreditToMediaItem(it) }
        return (castItems + crewItems)
            .distinctBy { it.tmdbId }
            .sortedByDescending { it.popularity ?: 0.0 }
    }

    override suspend fun getPersonDetail(personId: Int): TmdbPerson {
        return api.getPersonDetail(personId)
    }

    override suspend fun getSeasonDetail(tvId: Int, seasonNumber: Int): Season {
        val detail = api.getTvSeasonDetail(tvId, seasonNumber)
        return Season(
            seasonNumber = detail.seasonNumber,
            episodeCount = detail.episodes.size,
            name = detail.name,
            posterUrl = TmdbMappers.posterUrl(detail.posterPath),
            overview = detail.overview,
            episodes = detail.episodes.map { ep ->
                Episode(
                    episodeNumber = ep.episodeNumber,
                    name = ep.name,
                    overview = ep.overview,
                    stillUrl = TmdbMappers.backdropUrl(ep.stillPath, "w300"),
                    airDate = ep.airDate,
                    runtime = ep.runtime,
                    rating = ep.voteAverage,
                )
            },
        )
    }

    override suspend fun getTrendingPaged(type: String, page: Int): PagedResult {
        return if (type == "tv") {
            val resp = api.getTrendingTv(page)
            PagedResult(
                items = resp.results.map { TmdbMappers.tvToMediaItem(it) },
                page = resp.page,
                totalPages = resp.totalPages,
                totalResults = resp.totalResults,
            )
        } else {
            val resp = api.getTrending(type, page)
            PagedResult(
                items = resp.results.map { TmdbMappers.movieToMediaItem(it) },
                page = resp.page,
                totalPages = resp.totalPages,
                totalResults = resp.totalResults,
            )
        }
    }

    override suspend fun getPopularPaged(type: String, page: Int): PagedResult {
        return if (type == "tv") {
            val resp = api.getPopularTv(page)
            PagedResult(
                items = resp.results.map { TmdbMappers.tvToMediaItem(it) },
                page = resp.page,
                totalPages = resp.totalPages,
                totalResults = resp.totalResults,
            )
        } else {
            val resp = api.getPopular(type, page)
            PagedResult(
                items = resp.results.map { TmdbMappers.movieToMediaItem(it) },
                page = resp.page,
                totalPages = resp.totalPages,
                totalResults = resp.totalResults,
            )
        }
    }

    override suspend fun getTopRatedPaged(type: String, page: Int): PagedResult {
        return if (type == "tv") {
            val resp = api.getTopRatedTv(page)
            PagedResult(
                items = resp.results.map { TmdbMappers.tvToMediaItem(it) },
                page = resp.page,
                totalPages = resp.totalPages,
                totalResults = resp.totalResults,
            )
        } else {
            val resp = api.getTopRated(type, page)
            PagedResult(
                items = resp.results.map { TmdbMappers.movieToMediaItem(it) },
                page = resp.page,
                totalPages = resp.totalPages,
                totalResults = resp.totalResults,
            )
        }
    }

    override suspend fun discover(
        type: String,
        page: Int,
        sortBy: String,
        withGenres: String?,
        minRating: Float?,
        year: Int?,
        yearTo: Int?,
        runtimeGte: Int?,
        runtimeLte: Int?,
        originCountries: String?,
        originalLanguage: String?,
        certification: String?,
        certificationGte: String?,
        certificationLte: String?,
        certificationCountry: String?,
        withCast: String?,
        withCrew: String?,
        withWatchProviders: String?,
        watchRegion: String?,
        withKeywords: String?,
    ): PagedResult {
        return if (type == "tv") {
            val resp = api.discoverTv(
                page,
                sortBy,
                withGenres,
                minRating,
                year,
                yearTo,
                runtimeGte,
                runtimeLte,
                originCountries,
                originalLanguage,
                withCast,
                withCrew,
                withWatchProviders,
                watchRegion,
                withKeywords,
            )
            PagedResult(
                items = resp.results.map { TmdbMappers.tvToMediaItem(it) },
                page = resp.page,
                totalPages = resp.totalPages,
                totalResults = resp.totalResults,
            )
        } else {
            val resp = api.discoverMovies(
                page,
                sortBy,
                withGenres,
                minRating,
                year,
                yearTo,
                runtimeGte,
                runtimeLte,
                originCountries,
                originalLanguage,
                certification,
                certificationGte,
                certificationLte,
                certificationCountry,
                withCast,
                withCrew,
                withWatchProviders,
                watchRegion,
                withKeywords,
            )
            PagedResult(
                items = resp.results.map { TmdbMappers.movieToMediaItem(it) },
                page = resp.page,
                totalPages = resp.totalPages,
                totalResults = resp.totalResults,
            )
        }
    }

    override suspend fun searchKeywords(query: String): List<TmdbKeyword> {
        return api.searchKeywords(query).results
    }

    override suspend fun searchMultiPaged(query: String, page: Int, type: String?): PagedResult {
        val resp = api.searchMulti(query, page)
        val items = resp.results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .let { results ->
                if (type != null) {
                    val mt = if (type == "tv") MediaType.SERIES else MediaType.MOVIE
                    results.filter {
                        val itemType = if (it.mediaType == "tv") MediaType.SERIES else MediaType.MOVIE
                        itemType == mt
                    }
                } else results
            }
            .map { TmdbMappers.multiToMediaItem(it) }
        return PagedResult(
            items = items,
            page = resp.page,
            totalPages = resp.totalPages,
            totalResults = resp.totalResults,
        )
    }

    override suspend fun getPopularPeople(page: Int): List<PersonSummary> {
        return api.getPopularPeople(page).results.map { TmdbMappers.personSummaryToDomain(it) }
    }

    override suspend fun searchPerson(query: String, page: Int): List<PersonSummary> {
        return api.searchPerson(query, page).results.map { TmdbMappers.personSummaryToDomain(it) }
    }

    override suspend fun getWatchProviderLogos(type: String, region: String): Map<Int, String> {
        if (providerLogosBackendBaseUrl.isNotBlank()) {
            try {
                return api.getWatchProviderLogosFromBackend(providerLogosBackendBaseUrl, type, region)
            } catch (_: Exception) { /* fallback */ }
        }
        val response = api.getWatchProviders(type, region)
        return response.results.mapNotNull { provider ->
            provider.logoPath?.let { path ->
                provider.providerId to "${TmdbApiClient.IMAGE_BASE}/w154$path"
            }
        }.toMap()
    }

    override suspend fun getHomeShelves(): List<CatalogShelf> = coroutineScope {
        val trendingMovies = async { api.getTrending("movie") }
        val trendingTv = async { api.getTrendingTv() }
        val nowPlaying = async { api.getNowPlaying() }
        val popularMovies = async { api.getPopular("movie") }
        val upcoming = async { api.getUpcoming() }
        val popularTv = async { api.getAiringToday() }
        val topRated = async { api.getTopRated("movie") }
        val airingToday = async { api.getAiringToday() }

        listOf(
            CatalogShelf(
                id = "trending-movies",
                title = "Trending Movies",
                items = trendingMovies.await().results.map { TmdbMappers.movieToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
            CatalogShelf(
                id = "trending-tv",
                title = "Trending TV Shows",
                items = trendingTv.await().results.map { TmdbMappers.tvToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
            CatalogShelf(
                id = "now-playing",
                title = "Now Playing",
                items = nowPlaying.await().results.map { TmdbMappers.movieToMediaItem(it) },
                type = ShelfType.LANDSCAPE,
            ),
            CatalogShelf(
                id = "popular-movies",
                title = "Popular Movies",
                items = popularMovies.await().results.map { TmdbMappers.movieToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
            CatalogShelf(
                id = "upcoming",
                title = "Upcoming",
                items = upcoming.await().results.map { TmdbMappers.movieToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
            CatalogShelf(
                id = "popular-tv",
                title = "Popular TV Shows",
                items = popularTv.await().results.map { TmdbMappers.tvToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
            CatalogShelf(
                id = "top-rated",
                title = "Top Rated",
                items = topRated.await().results.map { TmdbMappers.movieToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
            CatalogShelf(
                id = "airing-today",
                title = "Airing Today",
                items = airingToday.await().results.map { TmdbMappers.tvToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
        )
    }
}
