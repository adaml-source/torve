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
import com.torve.domain.model.dedupeByStableKey
import com.torve.domain.repository.MetadataRepository
import com.torve.platform.torveVerboseLog
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private fun todayIsoDate(): String =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

private fun List<MediaItem>.filterUpcomingMovieReleases(today: String = todayIsoDate()): List<MediaItem> =
    filter { item ->
        val releaseDate = item.releaseDate?.take(10)?.takeIf { it.length == 10 }
        releaseDate != null && releaseDate >= today
    }

private const val HOME_RAIL_PREFETCH_PAGES = 2
private const val HOME_UPCOMING_PREFETCH_PAGES = 5
private const val METADATA_LIST_TTL_MS = 30 * 60 * 1000L
private const val METADATA_DETAIL_TTL_MS = 12 * 60 * 60 * 1000L
private const val METADATA_CACHE_MAX_ENTRIES = 320

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
    private val metadataCacheMutex = Mutex()
    private val metadataCache = LinkedHashMap<String, MetadataCacheEntry>()
    private val metadataInFlight = mutableMapOf<String, Deferred<Any?>>()

    private data class MetadataCacheEntry(
        val value: Any?,
        val storedAtMs: Long,
    )

    private fun metadataCacheKey(endpoint: String, vararg parts: Any?): String =
        buildString {
            append(endpoint)
            parts.forEach { part ->
                append('|')
                append(part?.toString()?.replace("|", "%7C") ?: "-")
            }
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cachedMetadata(
        key: String,
        ttlMs: Long,
        fetch: suspend () -> T,
    ): T = coroutineScope {
        val now = Clock.System.now().toEpochMilliseconds()
        metadataCacheMutex.withLock {
            metadataCache[key]?.takeIf { now - it.storedAtMs <= ttlMs }?.let { entry ->
                torveVerboseLog { "metadata_cache_hit key=$key" }
                return@coroutineScope entry.value as T
            }
        }

        var created = false
        val deferred = metadataCacheMutex.withLock {
            metadataInFlight[key]?.also {
                torveVerboseLog { "metadata_inflight_join key=$key" }
            } ?: async {
                torveVerboseLog { "metadata_cache_miss key=$key" }
                torveVerboseLog { "metadata_network_fetch key=$key" }
                fetch() as Any?
            }.also {
                metadataInFlight[key] = it
                created = true
            }
        }

        try {
            val value = deferred.await()
            if (created) {
                metadataCacheMutex.withLock {
                    metadataCache[key] = MetadataCacheEntry(value, Clock.System.now().toEpochMilliseconds())
                    while (metadataCache.size > METADATA_CACHE_MAX_ENTRIES) {
                        val evictedKey = metadataCache.keys.firstOrNull() ?: break
                        metadataCache.remove(evictedKey)
                        torveVerboseLog { "metadata_cache_eviction key=$evictedKey" }
                    }
                    metadataInFlight.remove(key)
                }
            }
            value as T
        } catch (t: Throwable) {
            if (created) {
                metadataCacheMutex.withLock {
                    metadataInFlight.remove(key)
                }
            }
            throw t
        }
    }

    override suspend fun getTrending(type: String, page: Int): List<MediaItem> {
        val cacheKey = metadataCacheKey("getTrending", type, page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        torveVerboseLog { "CONTENT_REPO fetch_start source=trending type=$type page=$page" }
        try {
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
    }

    override suspend fun getPopular(type: String, page: Int): List<MediaItem> {
        val cacheKey = metadataCacheKey("getPopular", type, page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        torveVerboseLog { "CONTENT_REPO fetch_start source=popular type=$type page=$page" }
        try {
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
    }

    override suspend fun getTopRated(type: String, page: Int): List<MediaItem> {
        val cacheKey = metadataCacheKey("getTopRated", type, page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        torveVerboseLog { "CONTENT_REPO fetch_start source=top_rated type=$type page=$page" }
        try {
            val items = if (type == "tv") {
                api.discoverTv(
                    page = page,
                    sortBy = "vote_average.desc",
                    minRating = 7.0f,
                    requestCategory = "catalog.top_rated.$type.page_$page",
                ).results.map { TmdbMappers.tvToMediaItem(it) }
            } else {
                api.discoverMovies(
                    page = page,
                    sortBy = "vote_average.desc",
                    minRating = 7.0f,
                    requestCategory = "catalog.top_rated.$type.page_$page",
                ).results.map { TmdbMappers.movieToMediaItem(it) }
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
    }

    override suspend fun getUpcoming(page: Int): List<MediaItem> {
        val cacheKey = metadataCacheKey("getUpcoming", page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        api.getUpcoming(page, requestCategory = "catalog.upcoming.movie.page_$page")
            .results
            .map { TmdbMappers.movieToMediaItem(it) }
            .filterUpcomingMovieReleases()
        }
    }

    override suspend fun getNowPlaying(page: Int): List<MediaItem> {
        val cacheKey = metadataCacheKey("getNowPlaying", page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
            api.getNowPlaying(page, requestCategory = "catalog.now_playing.movie.page_$page").results.map { TmdbMappers.movieToMediaItem(it) }
        }
    }

    override suspend fun getAiringToday(page: Int): List<MediaItem> {
        val cacheKey = metadataCacheKey("getAiringToday", page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
            api.getAiringToday(page, requestCategory = "catalog.airing_today.tv.page_$page").results.map { TmdbMappers.tvToMediaItem(it) }
        }
    }

    override suspend fun searchMulti(query: String, page: Int): List<MediaItem> {
        val cacheKey = metadataCacheKey("searchMulti", query.trim().lowercase(), page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        api.searchMulti(query, page).results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .map { TmdbMappers.multiToMediaItem(it) }
        }
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
        val cacheKey = metadataCacheKey("getDetail", type, id)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
        if (type == "movie") {
            TmdbMappers.movieToMediaItem(api.getMovieDetail(id))
        } else {
            TmdbMappers.tvToMediaItem(api.getTvDetail(id))
        }
        }
    }

    override suspend fun getSimilar(type: String, id: Int, page: Int): List<MediaItem> {
        val cacheKey = metadataCacheKey("getSimilar", type, id, page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        if (type == "tv") {
            api.getSimilarTv(id, page).results.map { TmdbMappers.tvToMediaItem(it) }
        } else {
            api.getSimilar(type, id, page).results.map { TmdbMappers.movieToMediaItem(it) }
        }
        }
    }

    override suspend fun getRecommendations(type: String, id: Int, page: Int): List<MediaItem> {
        val cacheKey = metadataCacheKey("getRecommendations", type, id, page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        if (type == "tv") {
            api.getRecommendationsTv(id, page).results.map { TmdbMappers.tvToMediaItem(it) }
        } else {
            api.getRecommendations(type, id, page).results.map { TmdbMappers.movieToMediaItem(it) }
        }
        }
    }

    override suspend fun getPersonCredits(personId: Int): List<MediaItem> {
        val cacheKey = metadataCacheKey("getPersonCredits", personId)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
        val credits = api.getPersonCredits(personId)
        val castItems = credits.cast.map { TmdbMappers.personCreditToMediaItem(it) }
        val crewItems = credits.crew.map { TmdbMappers.personCrewCreditToMediaItem(it) }
        (castItems + crewItems)
            .distinctBy { it.tmdbId }
            .sortedByDescending { it.popularity ?: 0.0 }
        }
    }

    override suspend fun getPersonDetail(personId: Int): TmdbPerson {
        val cacheKey = metadataCacheKey("getPersonDetail", personId)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
            api.getPersonDetail(personId)
        }
    }

    override suspend fun getPersonImageUrls(personId: Int): List<String> {
        val cacheKey = metadataCacheKey("getPersonImageUrls", personId)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
        api.getImages("person", personId)
            .profiles
            .sortedWith(
                compareByDescending<TmdbImageItem> { it.voteAverage * (it.voteCount.coerceAtLeast(1)) }
                    .thenByDescending { it.width * it.height },
            )
            .mapNotNull { image -> TmdbMappers.profileUrl(image.filePath, size = "w342") }
            .distinct()
        }
    }

    override suspend fun getMediaImageUrls(type: String, id: Int): List<String> {
        val cacheKey = metadataCacheKey("getMediaImageUrls", type, id)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
        val images = api.getImages(type, id)
        val backdrops = images.backdrops
            .sortedWith(
                compareByDescending<TmdbImageItem> { it.voteAverage * (it.voteCount.coerceAtLeast(1)) }
                    .thenByDescending { it.width * it.height },
            )
            .mapNotNull { image -> TmdbMappers.backdropUrl(image.filePath, size = "w1280") }
        val posters = images.posters
            .sortedWith(
                compareByDescending<TmdbImageItem> { it.voteAverage * (it.voteCount.coerceAtLeast(1)) }
                    .thenByDescending { it.width * it.height },
            )
            .mapNotNull { image -> TmdbMappers.posterUrl(image.filePath, size = "w500") }
        (backdrops + posters).distinct()
        }
    }

    override suspend fun getSeasonDetail(tvId: Int, seasonNumber: Int): Season {
        val cacheKey = metadataCacheKey("getSeasonDetail", tvId, seasonNumber)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
        val detail = api.getTvSeasonDetail(tvId, seasonNumber)
        Season(
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
    }

    override suspend fun getTrendingPaged(type: String, page: Int): PagedResult {
        val cacheKey = metadataCacheKey("getTrendingPaged", type, page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        torveVerboseLog { "CONTENT_REPO fetch_start source=trending_paged type=$type page=$page" }
        try {
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
    }

    override suspend fun getPopularPaged(type: String, page: Int): PagedResult {
        val cacheKey = metadataCacheKey("getPopularPaged", type, page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        torveVerboseLog { "CONTENT_REPO fetch_start source=popular_paged type=$type page=$page" }
        try {
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
    }

    override suspend fun getTopRatedPaged(type: String, page: Int): PagedResult {
        val cacheKey = metadataCacheKey("getTopRatedPaged", type, page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        torveVerboseLog { "CONTENT_REPO fetch_start source=top_rated_paged type=$type page=$page" }
        try {
            val result = if (type == "tv") {
                val resp = api.discoverTv(
                    page = page,
                    sortBy = "vote_average.desc",
                    minRating = 7.0f,
                    requestCategory = "catalog.top_rated_paged.$type.page_$page",
                )
                PagedResult(
                    items = resp.results.map { TmdbMappers.tvToMediaItem(it) },
                    page = resp.page,
                    totalPages = resp.totalPages,
                    totalResults = resp.totalResults,
                )
            } else {
                val resp = api.discoverMovies(
                    page = page,
                    sortBy = "vote_average.desc",
                    minRating = 7.0f,
                    requestCategory = "catalog.top_rated_paged.$type.page_$page",
                )
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
        val cacheKey = metadataCacheKey(
            "discover",
            type,
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
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        if (type == "tv") {
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
    }

    override suspend fun searchKeywords(query: String): List<TmdbKeyword> {
        val cacheKey = metadataCacheKey("searchKeywords", query.trim().lowercase())
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
            api.searchKeywords(query).results
        }
    }

    override suspend fun searchMultiPaged(query: String, page: Int, type: String?): PagedResult {
        val cacheKey = metadataCacheKey("searchMultiPaged", query.trim().lowercase(), page, type)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
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
        PagedResult(
            items = items,
            page = resp.page,
            totalPages = resp.totalPages,
            totalResults = resp.totalResults,
        )
        }
    }

    override suspend fun getPopularPeople(page: Int): List<PersonSummary> {
        val cacheKey = metadataCacheKey("getPopularPeople", page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
            api.getPopularPeople(page).results.map { TmdbMappers.personSummaryToDomain(it) }
        }
    }

    override suspend fun searchPerson(query: String, page: Int): List<PersonSummary> {
        val cacheKey = metadataCacheKey("searchPerson", query.trim().lowercase(), page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
            api.searchPerson(query, page).results.map { TmdbMappers.personSummaryToDomain(it) }
        }
    }

    override suspend fun getWatchProviderLogos(type: String, region: String): Map<Int, String> {
        val cacheKey = metadataCacheKey("getWatchProviderLogos", type, region)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
        if (providerLogosBackendBaseUrl.isNotBlank()) {
            try {
                return@cachedMetadata api.getWatchProviderLogosFromBackend(providerLogosBackendBaseUrl, type, region)
            } catch (_: Exception) { /* fallback */ }
        }
        val response = api.getWatchProviders(type, region)
        response.results.mapNotNull { provider ->
            provider.logoPath?.let { path ->
                provider.providerId to "${TmdbApiClient.IMAGE_BASE}/original$path"
            }
        }.toMap()
        }
    }

    override suspend fun getLogoUrl(type: String, tmdbId: Int): String? {
        val cacheKey = metadataCacheKey("getLogoUrl", type, tmdbId)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
        try {
            val images = api.getImages(type, tmdbId)
            TmdbMappers.bestLogoPath(images)?.let { TmdbMappers.logoUrl(it) }
        } catch (_: Exception) {
            null
        }
        }
    }

    override suspend fun getHomeShelves(): List<CatalogShelf> = cachedMetadata(
        key = metadataCacheKey("getHomeShelves"),
        ttlMs = METADATA_LIST_TTL_MS,
    ) {
        supervisorScope {
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

        suspend fun <T> homePages(pageCount: Int, block: suspend (Int) -> List<T>): List<T> =
            (1..pageCount).flatMap { page -> block(page) }

        val trendingMovies = async {
            homeRequest("trending_movies") {
                homePages(HOME_RAIL_PREFETCH_PAGES) { page ->
                    api.getTrending("movie", page = page, requestCategory = "home.trending_movies.page_$page").results
                }.map { TmdbMappers.movieToMediaItem(it) }.dedupeByStableKey()
            }
        }
        val trendingTv = async {
            homeRequest("trending_tv") {
                homePages(HOME_RAIL_PREFETCH_PAGES) { page ->
                    api.getTrendingTv(page = page, requestCategory = "home.trending_tv.page_$page").results
                }.map { TmdbMappers.tvToMediaItem(it) }.dedupeByStableKey()
            }
        }
        val nowPlaying = async {
            homeRequest("now_playing") {
                homePages(HOME_RAIL_PREFETCH_PAGES) { page ->
                    api.getNowPlaying(page = page, requestCategory = "home.now_playing.page_$page").results
                }.map { TmdbMappers.movieToMediaItem(it) }.dedupeByStableKey()
            }
        }
        val popularMovies = async {
            homeRequest("popular_movies") {
                homePages(HOME_RAIL_PREFETCH_PAGES) { page ->
                    api.getPopular("movie", page = page, requestCategory = "home.popular_movies.page_$page").results
                }.map { TmdbMappers.movieToMediaItem(it) }.dedupeByStableKey()
            }
        }
        val upcoming = async {
            homeRequest("upcoming") {
                homePages(HOME_UPCOMING_PREFETCH_PAGES) { page ->
                    api.getUpcoming(page = page, requestCategory = "home.upcoming.page_$page").results
                }.map { TmdbMappers.movieToMediaItem(it) }
                    .filterUpcomingMovieReleases()
                    .dedupeByStableKey()
            }
        }
        val popularTv = async {
            homeRequest("popular_tv") {
                homePages(HOME_RAIL_PREFETCH_PAGES) { page ->
                    api.getPopularTv(page = page, requestCategory = "home.popular_tv.page_$page").results
                }.map { TmdbMappers.tvToMediaItem(it) }.dedupeByStableKey()
            }
        }
        val topRated = async {
            homeRequest("top_rated") {
                homePages(HOME_RAIL_PREFETCH_PAGES) { page ->
                    api.discoverMovies(
                        page = page,
                        sortBy = "vote_average.desc",
                        minRating = 7.0f,
                        requestCategory = "home.top_rated.page_$page",
                    ).results
                }.map { TmdbMappers.movieToMediaItem(it) }.dedupeByStableKey()
            }
        }
        val airingToday = async {
            homeRequest("airing_today") {
                homePages(HOME_RAIL_PREFETCH_PAGES) { page ->
                    api.getAiringToday(page = page, requestCategory = "home.airing_today.page_$page").results
                }.map { TmdbMappers.tvToMediaItem(it) }.dedupeByStableKey()
            }
        }
        val failedSegments = mutableListOf<String>()

        fun <T> consume(result: Result<T>): T? {
            result.exceptionOrNull()?.message?.let(failedSegments::add)
            return result.getOrNull()
        }

        val shelves = buildList {
            consume(trendingMovies.await())?.takeIf { it.isNotEmpty() }?.let { items ->
                add(
                    CatalogShelf(
                        id = "trending-movies",
                        title = "Trending Movies",
                        items = items,
                        type = ShelfType.POSTER,
                    ),
                )
            }
            consume(trendingTv.await())?.takeIf { it.isNotEmpty() }?.let { items ->
                add(
                    CatalogShelf(
                        id = "trending-tv",
                        title = "Trending TV Shows",
                        items = items,
                        type = ShelfType.POSTER,
                    ),
                )
            }
            consume(nowPlaying.await())?.takeIf { it.isNotEmpty() }?.let { items ->
                add(
                    CatalogShelf(
                        id = "now-playing",
                        title = "Now Playing",
                        items = items,
                        type = ShelfType.LANDSCAPE,
                    ),
                )
            }
            consume(popularMovies.await())?.takeIf { it.isNotEmpty() }?.let { items ->
                add(
                    CatalogShelf(
                        id = "popular-movies",
                        title = "Popular Movies",
                        items = items,
                        type = ShelfType.POSTER,
                    ),
                )
            }
            consume(upcoming.await())?.takeIf { it.isNotEmpty() }?.let { items ->
                add(
                    CatalogShelf(
                        id = "upcoming",
                        title = "Upcoming",
                        items = items,
                        type = ShelfType.POSTER,
                    ),
                )
            }
            consume(popularTv.await())?.takeIf { it.isNotEmpty() }?.let { items ->
                add(
                    CatalogShelf(
                        id = "popular-tv",
                        title = "Popular TV Shows",
                        items = items,
                        type = ShelfType.POSTER,
                    ),
                )
            }
            consume(topRated.await())?.takeIf { it.isNotEmpty() }?.let { items ->
                add(
                    CatalogShelf(
                        id = "top-rated",
                        title = "Top Rated",
                        items = items,
                        type = ShelfType.POSTER,
                    ),
                )
            }
            consume(airingToday.await())?.takeIf { it.isNotEmpty() }?.let { items ->
                add(
                    CatalogShelf(
                        id = "airing-today",
                        title = "Airing Today",
                        items = items,
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
}
