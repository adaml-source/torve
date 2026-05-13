package com.torve.data.metadata

import com.torve.data.network.sanitizeNetworkDiagnosticText
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.Episode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.PagedResult
import com.torve.domain.model.PersonSummary
import com.torve.domain.model.Season
import com.torve.domain.model.ShelfType
import com.torve.domain.repository.MetadataRepository
import com.torve.platform.torveVerboseLog
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

class HomeShelvesUnavailableException(
    val failedSegments: List<String>,
) : IllegalStateException(
    buildString {
        append("Home content is unavailable")
        if (failedSegments.isNotEmpty()) {
            append(". Upstream failures: ")
            append(failedSegments.joinToString())
        }
    },
)

class MetadataRepositoryImpl(
    private val api: TmdbApiClient,
) : MetadataRepository {
    private val providerLogosBackendBaseUrl: String = "" // Set your backend base URL when available.

    override suspend fun getTrending(type: String, page: Int): List<MediaItem> {
        torveVerboseLog { "CONTENT_REPO fetch_start source=trending type=$type page=$page" }
        return try {
            val items = if (type == "tv") {
                api.getTrendingTv(page, requestCategory = "catalog.trending.$type.page_$page").results.map { TmdbMappers.tvToMediaItem(it) }
            } else {
                api.getTrending(type, page, requestCategory = "catalog.trending.$type.page_$page").results.map { TmdbMappers.movieToMediaItem(it) }
            }
            torveVerboseLog {
                "CONTENT_REPO fetch_success source=trending type=$type page=$page items=${items.size}"
            }
            items
        } catch (e: Exception) {
            torveVerboseLog {
                "CONTENT_REPO fetch_failure source=trending type=$type page=$page ${e::class.simpleName}: ${sanitizeNetworkDiagnosticText(e.message)}"
            }
            throw e
        }
    }

    override suspend fun getPopular(type: String, page: Int): List<MediaItem> {
        torveVerboseLog { "CONTENT_REPO fetch_start source=popular type=$type page=$page" }
        return try {
            val items = if (type == "tv") {
                api.getPopularTv(page, requestCategory = "catalog.popular.$type.page_$page").results.map { TmdbMappers.tvToMediaItem(it) }
            } else {
                api.getPopular(type, page, requestCategory = "catalog.popular.$type.page_$page").results.map { TmdbMappers.movieToMediaItem(it) }
            }
            torveVerboseLog {
                "CONTENT_REPO fetch_success source=popular type=$type page=$page items=${items.size}"
            }
            items
        } catch (e: Exception) {
            torveVerboseLog {
                "CONTENT_REPO fetch_failure source=popular type=$type page=$page ${e::class.simpleName}: ${sanitizeNetworkDiagnosticText(e.message)}"
            }
            throw e
        }
    }

    override suspend fun getTopRated(type: String, page: Int): List<MediaItem> {
        torveVerboseLog { "CONTENT_REPO fetch_start source=top_rated type=$type page=$page" }
        return try {
            val items = if (type == "tv") {
                api.getTopRatedTv(page, requestCategory = "catalog.top_rated.$type.page_$page").results.map { TmdbMappers.tvToMediaItem(it) }
            } else {
                api.getTopRated(type, page, requestCategory = "catalog.top_rated.$type.page_$page").results.map { TmdbMappers.movieToMediaItem(it) }
            }
            torveVerboseLog {
                "CONTENT_REPO fetch_success source=top_rated type=$type page=$page items=${items.size}"
            }
            items
        } catch (e: Exception) {
            torveVerboseLog {
                "CONTENT_REPO fetch_failure source=top_rated type=$type page=$page ${e::class.simpleName}: ${sanitizeNetworkDiagnosticText(e.message)}"
            }
            throw e
        }
    }

    override suspend fun getUpcoming(page: Int): List<MediaItem> {
        return api.getUpcoming(page, requestCategory = "catalog.upcoming.movie.page_$page").results.map { TmdbMappers.movieToMediaItem(it) }
    }

    override suspend fun getNowPlaying(page: Int): List<MediaItem> {
        return api.getNowPlaying(page, requestCategory = "catalog.now_playing.movie.page_$page").results.map { TmdbMappers.movieToMediaItem(it) }
    }

    override suspend fun getAiringToday(page: Int): List<MediaItem> {
        return api.getAiringToday(page, requestCategory = "catalog.airing_today.tv.page_$page").results.map { TmdbMappers.tvToMediaItem(it) }
    }

    override suspend fun searchMulti(query: String, page: Int): List<MediaItem> {
        return api.searchMulti(query, page).results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .map { TmdbMappers.multiToMediaItem(it) }
    }

    override suspend fun findByImdbId(imdbId: String, preferredType: String?): MediaItem? {
        if (!imdbId.startsWith("tt")) return null
        val result = api.findByImdbId(imdbId)
        val normalizedType = preferredType?.lowercase()
        return when (normalizedType) {
            "tv", "series" -> {
                result.tvResults.firstOrNull()?.let { TmdbMappers.tvToMediaItem(it) }
                    ?: result.movieResults.firstOrNull()?.let { TmdbMappers.movieToMediaItem(it) }
            }
            "movie" -> {
                result.movieResults.firstOrNull()?.let { TmdbMappers.movieToMediaItem(it) }
                    ?: result.tvResults.firstOrNull()?.let { TmdbMappers.tvToMediaItem(it) }
            }
            else -> {
                result.movieResults.firstOrNull()?.let { TmdbMappers.movieToMediaItem(it) }
                    ?: result.tvResults.firstOrNull()?.let { TmdbMappers.tvToMediaItem(it) }
            }
        }
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

    override suspend fun getPersonImageUrls(personId: Int): List<String> {
        return api.getImages("person", personId)
            .profiles
            .sortedWith(
                compareByDescending<TmdbImageItem> { it.voteAverage * (it.voteCount.coerceAtLeast(1)) }
                    .thenByDescending { it.width * it.height },
            )
            .mapNotNull { image -> TmdbMappers.profileUrl(image.filePath, size = "w342") }
            .distinct()
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
        torveVerboseLog { "CONTENT_REPO fetch_start source=trending_paged type=$type page=$page" }
        return try {
            val result = if (type == "tv") {
                val resp = api.getTrendingTv(page, requestCategory = "catalog.trending_paged.$type.page_$page")
                PagedResult(
                    items = resp.results.map { TmdbMappers.tvToMediaItem(it) },
                    page = resp.page,
                    totalPages = resp.totalPages,
                    totalResults = resp.totalResults,
                )
            } else {
                val resp = api.getTrending(type, page, requestCategory = "catalog.trending_paged.$type.page_$page")
                PagedResult(
                    items = resp.results.map { TmdbMappers.movieToMediaItem(it) },
                    page = resp.page,
                    totalPages = resp.totalPages,
                    totalResults = resp.totalResults,
                )
            }
            torveVerboseLog {
                "CONTENT_REPO fetch_success source=trending_paged type=$type page=$page items=${result.items.size}"
            }
            result
        } catch (e: Exception) {
            torveVerboseLog {
                "CONTENT_REPO fetch_failure source=trending_paged type=$type page=$page ${e::class.simpleName}: ${sanitizeNetworkDiagnosticText(e.message)}"
            }
            throw e
        }
    }

    override suspend fun getPopularPaged(type: String, page: Int): PagedResult {
        torveVerboseLog { "CONTENT_REPO fetch_start source=popular_paged type=$type page=$page" }
        return try {
            val result = if (type == "tv") {
                val resp = api.getPopularTv(page, requestCategory = "catalog.popular_paged.$type.page_$page")
                PagedResult(
                    items = resp.results.map { TmdbMappers.tvToMediaItem(it) },
                    page = resp.page,
                    totalPages = resp.totalPages,
                    totalResults = resp.totalResults,
                )
            } else {
                val resp = api.getPopular(type, page, requestCategory = "catalog.popular_paged.$type.page_$page")
                PagedResult(
                    items = resp.results.map { TmdbMappers.movieToMediaItem(it) },
                    page = resp.page,
                    totalPages = resp.totalPages,
                    totalResults = resp.totalResults,
                )
            }
            torveVerboseLog {
                "CONTENT_REPO fetch_success source=popular_paged type=$type page=$page items=${result.items.size}"
            }
            result
        } catch (e: Exception) {
            torveVerboseLog {
                "CONTENT_REPO fetch_failure source=popular_paged type=$type page=$page ${e::class.simpleName}: ${sanitizeNetworkDiagnosticText(e.message)}"
            }
            throw e
        }
    }

    override suspend fun getTopRatedPaged(type: String, page: Int): PagedResult {
        torveVerboseLog { "CONTENT_REPO fetch_start source=top_rated_paged type=$type page=$page" }
        return try {
            val result = if (type == "tv") {
                val resp = api.getTopRatedTv(page, requestCategory = "catalog.top_rated_paged.$type.page_$page")
                PagedResult(
                    items = resp.results.map { TmdbMappers.tvToMediaItem(it) },
                    page = resp.page,
                    totalPages = resp.totalPages,
                    totalResults = resp.totalResults,
                )
            } else {
                val resp = api.getTopRated(type, page, requestCategory = "catalog.top_rated_paged.$type.page_$page")
                PagedResult(
                    items = resp.results.map { TmdbMappers.movieToMediaItem(it) },
                    page = resp.page,
                    totalPages = resp.totalPages,
                    totalResults = resp.totalResults,
                )
            }
            torveVerboseLog {
                "CONTENT_REPO fetch_success source=top_rated_paged type=$type page=$page items=${result.items.size}"
            }
            result
        } catch (e: Exception) {
            torveVerboseLog {
                "CONTENT_REPO fetch_failure source=top_rated_paged type=$type page=$page ${e::class.simpleName}: ${sanitizeNetworkDiagnosticText(e.message)}"
            }
            throw e
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
                requestCategory = "catalog.discover.$type.page_$page",
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
                requestCategory = "catalog.discover.$type.page_$page",
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

    override suspend fun getLogoUrl(type: String, tmdbId: Int): String? {
        return try {
            val images = api.getImages(type, tmdbId)
            TmdbMappers.bestLogoPath(images)?.let { TmdbMappers.logoUrl(it) }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getHomeShelves(): List<CatalogShelf> = supervisorScope {
        torveVerboseLog { "CONTENT_REPO fetch_start source=home_shelves" }
        suspend fun <T> homeRequest(label: String, block: suspend () -> T): Result<T> {
            return try {
                Result.success(block())
            } catch (e: Exception) {
                val sanitized = sanitizeNetworkDiagnosticText(e.message)
                torveVerboseLog {
                    "CONTENT_REPO fetch_failure source=home_shelves segment=$label ${e::class.simpleName}: $sanitized"
                }
                Result.failure(IllegalStateException("$label=${sanitized ?: e::class.simpleName}"))
            }
        }

        val trendingMovies = async {
            homeRequest("trending_movies") {
                api.getTrending("movie", requestCategory = "home.trending_movies")
            }
        }
        val trendingTv = async {
            homeRequest("trending_tv") {
                api.getTrendingTv(requestCategory = "home.trending_tv")
            }
        }
        val nowPlaying = async {
            homeRequest("now_playing") {
                api.getNowPlaying(requestCategory = "home.now_playing")
            }
        }
        val popularMovies = async {
            homeRequest("popular_movies") {
                api.getPopular("movie", requestCategory = "home.popular_movies")
            }
        }
        val upcoming = async {
            homeRequest("upcoming") {
                api.getUpcoming(requestCategory = "home.upcoming")
            }
        }
        val popularTv = async {
            homeRequest("popular_tv") {
                api.getPopularTv(requestCategory = "home.popular_tv")
            }
        }
        val topRated = async {
            homeRequest("top_rated") {
                api.getTopRated("movie", requestCategory = "home.top_rated")
            }
        }
        val airingToday = async {
            homeRequest("airing_today") {
                api.getAiringToday(requestCategory = "home.airing_today")
            }
        }
        val failedSegments = mutableListOf<String>()

        fun <T> consume(result: Result<T>): T? {
            result.exceptionOrNull()?.message?.let(failedSegments::add)
            return result.getOrNull()
        }

        val shelves = buildList {
            consume(trendingMovies.await())?.results?.takeIf { it.isNotEmpty() }?.let { results ->
                add(
                    CatalogShelf(
                        id = "trending-movies",
                        title = "Trending Movies",
                        items = results.map { TmdbMappers.movieToMediaItem(it) },
                        type = ShelfType.POSTER,
                    ),
                )
            }
            consume(trendingTv.await())?.results?.takeIf { it.isNotEmpty() }?.let { results ->
                add(
                    CatalogShelf(
                        id = "trending-tv",
                        title = "Trending TV Shows",
                        items = results.map { TmdbMappers.tvToMediaItem(it) },
                        type = ShelfType.POSTER,
                    ),
                )
            }
            consume(nowPlaying.await())?.results?.takeIf { it.isNotEmpty() }?.let { results ->
                add(
                    CatalogShelf(
                        id = "now-playing",
                        title = "Now Playing",
                        items = results.map { TmdbMappers.movieToMediaItem(it) },
                        type = ShelfType.LANDSCAPE,
                    ),
                )
            }
            consume(popularMovies.await())?.results?.takeIf { it.isNotEmpty() }?.let { results ->
                add(
                    CatalogShelf(
                        id = "popular-movies",
                        title = "Popular Movies",
                        items = results.map { TmdbMappers.movieToMediaItem(it) },
                        type = ShelfType.POSTER,
                    ),
                )
            }
            consume(upcoming.await())?.results?.takeIf { it.isNotEmpty() }?.let { results ->
                add(
                    CatalogShelf(
                        id = "upcoming",
                        title = "Upcoming",
                        items = results.map { TmdbMappers.movieToMediaItem(it) },
                        type = ShelfType.POSTER,
                    ),
                )
            }
            consume(popularTv.await())?.results?.takeIf { it.isNotEmpty() }?.let { results ->
                add(
                    CatalogShelf(
                        id = "popular-tv",
                        title = "Popular TV Shows",
                        items = results.map { TmdbMappers.tvToMediaItem(it) },
                        type = ShelfType.POSTER,
                    ),
                )
            }
            consume(topRated.await())?.results?.takeIf { it.isNotEmpty() }?.let { results ->
                add(
                    CatalogShelf(
                        id = "top-rated",
                        title = "Top Rated",
                        items = results.map { TmdbMappers.movieToMediaItem(it) },
                        type = ShelfType.POSTER,
                    ),
                )
            }
            consume(airingToday.await())?.results?.takeIf { it.isNotEmpty() }?.let { results ->
                add(
                    CatalogShelf(
                        id = "airing-today",
                        title = "Airing Today",
                        items = results.map { TmdbMappers.tvToMediaItem(it) },
                        type = ShelfType.POSTER,
                    ),
                )
            }
        }
        if (shelves.isEmpty()) {
            torveVerboseLog {
                "CONTENT_REPO fetch_failure source=home_shelves segment=all message=all_home_segments_failed failures=${failedSegments.joinToString()}"
            }
            throw HomeShelvesUnavailableException(failedSegments)
        }
        torveVerboseLog {
            "CONTENT_REPO fetch_success source=home_shelves shelves=${shelves.size}"
        }
        shelves
    }
}
