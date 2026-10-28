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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
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

// First paint only needs the first page. Destination screens load deeper
// pages as the user browses instead of making Home wait for them at startup.
private const val HOME_RAIL_PREFETCH_PAGES = 1
private const val HOME_UPCOMING_PREFETCH_PAGES = 1
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
    private val family: com.torve.data.profile.FamilyControls? = null,
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
            append('|').append(api.resolvedContentLanguage().lowercase())
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
        val cached = metadataCacheMutex.withLock {
            metadataCache[key]?.takeIf { now - it.storedAtMs <= if (it.value == null) minOf(ttlMs, 60_000L) else ttlMs }?.let { entry ->
                torveVerboseLog { "metadata_cache_hit key=$key" }
                entry
            }
        }
        // Certification performs I/O. Never hold the shared metadata lock while awaiting it.
        if (cached != null) return@coroutineScope prepareFamilyValue(cached.value) as T

        var created = false
        val deferred = metadataCacheMutex.withLock {
            metadataInFlight[key]?.takeUnless { it.isCancelled }?.also {
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
                    if (metadataInFlight[key] === deferred) metadataInFlight.remove(key)
                }
            }
            prepareFamilyValue(value) as T
        } catch (t: Throwable) {
            if (created) {
                withContext(NonCancellable) {
                    metadataCacheMutex.withLock {
                        if (metadataInFlight[key] === deferred) metadataInFlight.remove(key)
                    }
                }
            }
            // The request owner may have left its screen while another consumer
            // still needs this result. Retry only that active consumer's lookup.
            if (!created && t is CancellationException && currentCoroutineContext().isActive) {
                return@coroutineScope cachedMetadata(key, ttlMs, fetch)
            }
            throw t
        }
    }

    private suspend fun prepareFamilyValue(value: Any?): Any? {
        val gate = family ?: return value
        gate.initialize()
        if (!gate.state.value.contentRestricted) return value
        return when (value) {
            is MediaItem -> gate.prepare(listOf(value)).firstOrNull() ?: error("This title is not available in this profile.")
            is PagedResult -> value.copy(items = gate.prepare(value.items))
            is List<*> -> when {
                value.all { it is MediaItem } -> gate.prepare(value.filterIsInstance<MediaItem>())
                value.all { it is CatalogShelf } -> value.filterIsInstance<CatalogShelf>().mapNotNull { shelf ->
                    val items = gate.prepare(shelf.items)
                    if (items.isEmpty()) null else shelf.copy(items = items)
                }
                else -> value
            }
            else -> value
        }
    }

    private suspend fun kidsRules(): com.torve.domain.model.KidsProfileRules? {
        family?.initialize()
        return family?.state?.value?.effectiveKidsRules
    }

    // Discovery is a candidate search, never an age-verification shortcut. Every
    // returned title still passes prepareFamilyValue and its official certificates.
    private fun movieCandidateRating(rules: com.torve.domain.model.KidsProfileRules): String? {
        val ratings = when (rules.ratingCountry.uppercase()) {
            "US" -> listOf("G", "PG", "PG-13", "R", "NC-17")
            "DE" -> listOf("0", "6", "12", "16", "18")
            "GB" -> listOf("U", "PG", "12A", "15", "18")
            "FR" -> listOf("U", "10", "12", "16", "18")
            "AU" -> listOf("G", "PG", "M", "MA15+", "R18+")
            else -> return null
        }
        return ratings.lastOrNull { rating ->
            com.torve.domain.model.FamilyPolicy.minimumAge(com.torve.domain.model.AgeCertification(rules.ratingCountry, rating))
                ?.let { it <= rules.maximumAge } == true
        }
    }

    override suspend fun getTrending(type: String, page: Int): List<MediaItem> {
        if (kidsRules() != null) return discover(type, page = page).items
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
        if (kidsRules() != null) return discover(type, page = page, sortBy = "popularity.desc").items
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

    override suspend fun getTopRated(type: String, page: Int): List<MediaItem> = getTopRatedPaged(type, page).items

    override suspend fun getUpcoming(page: Int): List<MediaItem> {
        val cacheKey = metadataCacheKey("getUpcoming", page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        api.getUpcoming(page, requestCategory = "catalog.upcoming.movie.page_$page")
            .results
            .map { TmdbMappers.movieToMediaItem(it) }
            .filterUpcomingMovieReleases()
        }
    }

    override suspend fun getUpcomingPaged(page: Int): PagedResult = cachedMetadata(
        metadataCacheKey("getUpcomingPaged", page), METADATA_LIST_TTL_MS,
    ) {
        val response = api.getUpcoming(page, requestCategory = "catalog.upcoming_paged.movie.page_$page")
        PagedResult(response.results.map { TmdbMappers.movieToMediaItem(it) }.filterUpcomingMovieReleases(),
            response.page, response.totalPages, response.totalResults)
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
        val cacheKey = metadataCacheKey("getDetail:v2", type, id)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
        if (type == "movie") {
            TmdbMappers.movieToMediaItem(api.getMovieDetail(id), api.resolvedContentLanguage())
        } else {
            TmdbMappers.tvToMediaItem(api.getTvDetail(id), api.resolvedContentLanguage())
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
        val cacheKey = metadataCacheKey("getPersonCredits:v2", personId)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
        val credits = api.getPersonCredits(personId)
        val castItems = credits.cast.map { TmdbMappers.personCreditToMediaItem(it) }
        val crewItems = credits.crew.map { TmdbMappers.personCrewCreditToMediaItem(it) }
        (castItems + crewItems)
            .distinctBy { "${it.type}:${it.tmdbId}:${it.creditIsCrew}" }
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
        if (kidsRules() != null) return discover(type, page = page, sortBy = "popularity.desc")
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
        if (kidsRules() != null) return discover(type, page = page, sortBy = "popularity.desc")
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
        if (kidsRules() != null) return discover(type, page = page, sortBy = "vote_average.desc")
        val cacheKey = metadataCacheKey("getTopRatedPaged", type, page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
        torveVerboseLog { "CONTENT_REPO fetch_start source=top_rated_paged type=$type page=$page" }
        try {
            val result = if (type == "tv") {
                val resp = api.getTopRatedTv(page = page, requestCategory = "catalog.top_rated_paged.$type.page_$page")
                PagedResult(
                    items = resp.results.map { TmdbMappers.tvToMediaItem(it) },
                    page = resp.page,
                    totalPages = resp.totalPages,
                    totalResults = resp.totalResults,
                )
            } else {
                val resp = api.getTopRated("movie", page = page, requestCategory = "catalog.top_rated_paged.$type.page_$page")
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
        withCompanies: String?,
    ): PagedResult {
        val rules = kidsRules()
        // TMDB supports movie certification queries; TV discovery has no equivalent.
        // Start unfiltered young profiles in Kids/Family TV, then verify each title.
        // Explicit genre choices remain intact (e.g. a G-rated documentary search).
        val candidateGenres = withGenres ?: if (type == "tv" && rules != null && rules.maximumAge < 13) "10762|10751" else null
        val candidateRating = rules?.takeIf { certification == null && certificationGte == null && certificationLte == null }
            ?.let(::movieCandidateRating)
        val candidateCountry = if (candidateRating != null) rules?.ratingCountry else certificationCountry
        val candidateMaximum = candidateRating ?: certificationLte
        suspend fun sourcePage(sourcePage: Int): PagedResult {
            val cacheKey = metadataCacheKey(
                if (rules != null) "family-discover-v3" else "discover",
                if (withWatchProviders.isNullOrBlank()) "all" else "streaming-only-v1",
                type,
                sourcePage,
                sortBy,
                candidateGenres,
                minRating,
                year,
                yearTo,
                runtimeGte,
                runtimeLte,
                originCountries,
                originalLanguage,
                certification,
                certificationGte,
                candidateMaximum,
                candidateCountry,
                withCast,
                withCrew,
                withWatchProviders,
                watchRegion,
                withKeywords,
                withCompanies,
            )
            return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
            if (type == "tv") {
                val resp = api.discoverTv(
                    sourcePage,
                    sortBy,
                    candidateGenres,
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
                    withCompanies,
                    requestCategory = "catalog.discover.$type.page_$sourcePage",
                    minimumVoteCount = if (rules != null) { if (sortBy == "vote_average.desc") 20 else 0 } else null,
                )
                PagedResult(
                    items = resp.results.map { TmdbMappers.tvToMediaItem(it) },
                    page = resp.page,
                    totalPages = resp.totalPages,
                    totalResults = resp.totalResults,
                )
            } else {
                val resp = api.discoverMovies(
                    sourcePage,
                    sortBy,
                    candidateGenres,
                    minRating,
                    year,
                    yearTo,
                    runtimeGte,
                    runtimeLte,
                    originCountries,
                    originalLanguage,
                    certification,
                    certificationGte,
                    candidateMaximum,
                    candidateCountry,
                    withCast,
                    withCrew,
                    withWatchProviders,
                    watchRegion,
                    withKeywords,
                    withCompanies,
                    requestCategory = "catalog.discover.$type.page_$sourcePage",
                    minimumVoteCount = if (rules != null) { if (sortBy == "vote_average.desc") 20 else 0 } else null,
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

        if (rules == null) return sourcePage(page)
        val verified = LinkedHashMap<String, MediaItem>()
        var cursor = page
        var last: PagedResult
        var scanned = 0
        do {
            last = sourcePage(cursor)
            last.items.forEach { item -> val key = "${item.type}:${item.tmdbId ?: item.id}"; if (key !in verified) verified[key] = item }
            cursor = last.page + 1
            scanned++
        } while (verified.isEmpty() && last.page < last.totalPages && scanned < 4)
        return last.copy(items = verified.values.toList())
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

    override fun titleSuggestions(query: String, type: String?): Flow<List<MediaItem>> = channelFlow {
        val key = metadataCacheKey("titleSuggestions:v1", query.trim().lowercase(), type)
        val items = cachedMetadata(key, METADATA_LIST_TTL_MS) {
            kotlinx.coroutines.withTimeout(2_000) {
                val completions = api.titleCompletions(query).filter { type == null || it.mediaType == type }
                val resolved = mutableMapOf<String, MediaItem>()
                val resultMutex = Mutex()
                supervisorScope {
                    completions.map { completion -> async {
                        try {
                            val item = cachedMetadata(metadataCacheKey("completionTitle", completion.id), METADATA_DETAIL_TTL_MS) {
                                findByImdbId(completion.id, completion.mediaType)
                            }?.takeIf { type == null || (it.type == MediaType.SERIES) == (type == "tv") }
                            if (item != null) resultMutex.withLock {
                                resolved[completion.id] = item
                                send(completions.mapNotNull { resolved[it.id] })
                            }
                            item
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    } }.mapNotNull { it.await() }
                }
            }
        }
        send(items)
    }

    override suspend fun searchPerson(query: String, page: Int): List<PersonSummary> {
        val cacheKey = metadataCacheKey("searchPerson", query.trim().lowercase(), page)
        return cachedMetadata(cacheKey, METADATA_LIST_TTL_MS) {
            api.searchPerson(query, page).results.map { TmdbMappers.personSummaryToDomain(it) }
        }
    }

    override suspend fun getWatchProviderNames(type: String, region: String): Map<Int, String> {
        val cacheKey = metadataCacheKey("getWatchProviderNames", type, region)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
            api.getWatchProviders(type, region).results.associate { provider ->
                provider.providerId to provider.providerName
            }
        }
    }

    override suspend fun getWatchProviderCandidates(type: String): List<com.torve.domain.model.StreamingProviderCandidate> {
        val cacheKey = metadataCacheKey("getWatchProviderCandidates", type)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
            api.getWatchProviders(type, region = null).results.map { provider ->
                com.torve.domain.model.StreamingProviderCandidate(
                    id = provider.providerId,
                    name = provider.providerName,
                    regions = provider.displayPriorities.keys
                        .asSequence()
                        .map { it.trim().uppercase() }
                        .filter { it.length == 2 }
                        .toSet(),
                )
            }
        }
    }

    override suspend fun getTitleWatchProviderCandidates(
        type: String,
        tmdbId: Int,
    ): List<com.torve.domain.model.StreamingProviderCandidate> {
        val cacheKey = metadataCacheKey("getTitleWatchProviderCandidates", type, tmdbId)
        return cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
            api.getTitleWatchProviders(type, tmdbId).results
                .flatMap { (region, providers) ->
                    (providers.flatrate + providers.free + providers.ads)
                        .distinctBy { it.providerId }
                        .map { provider ->
                            Triple(provider.providerId, provider.providerName, region.trim().uppercase())
                        }
                }
                .groupBy { it.first to it.second }
                .map { (provider, entries) ->
                    com.torve.domain.model.StreamingProviderCandidate(
                        id = provider.first,
                        name = provider.second,
                        regions = entries.map { it.third }.filter { it.length == 2 }.toSet(),
                    )
                }
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
        return try {
            cachedMetadata(cacheKey, METADATA_DETAIL_TTL_MS) {
                val images = api.getImages(type, tmdbId)
                TmdbMappers.bestLogoPath(images, api.resolvedContentLanguage())?.let { TmdbMappers.logoUrl(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the nullable contract, without caching a failed connection as no artwork.
            null
        }
    }

    override suspend fun getHomeShelves(): List<CatalogShelf> = homeShelves().last()

    override fun homeShelves(): Flow<List<CatalogShelf>> = channelFlow {
        if (kidsRules() == null) { send(generalHomeShelves { send(it) }); return@channelFlow }
        val rows = listOf(
            Triple("trending-movies", "Trending Movies", "movie"),
            Triple("trending-tv", "Trending TV Shows", "tv"),
            Triple("popular-movies", "Popular Movies", "movie"),
            Triple("popular-tv", "Popular TV Shows", "tv"),
            Triple("top-rated", "Top Rated", "movie"),
            Triple("now-playing", "Now Playing", "movie"),
            Triple("upcoming", "Upcoming", "movie"),
        )
        val available = linkedMapOf<String, CatalogShelf>()
        val cursors = mutableMapOf<String, Int>()
        val exhausted = mutableSetOf<String>()
        suspend fun page(id: String, type: String, cursor: Int): PagedResult = when (id) {
            "now-playing" -> getNowPlaying(cursor).let { PagedResult(it, cursor, if (cursor < 20) 20 else cursor, it.size) }
            "upcoming" -> getUpcomingPaged(cursor)
            else -> discover(type, page = cursor, sortBy = if (id == "top-rated") "vote_average.desc" else "popularity.desc")
        }
        // Keep first-paint verification ahead of optional rows. A slow upcoming
        // title must not occupy the certification queue before the first rail.
        for ((id, title, type) in rows) {
            val items = try {
                val first = page(id, type, 1)
                cursors[id] = first.page + 1
                if (first.page >= first.totalPages) exhausted += id
                first.items
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            if (items.isNotEmpty()) {
                available[id] = CatalogShelf(id = id, title = title, items = items, type = ShelfType.POSTER)
                send(available.values.toList())
            }
        }
        if (available.isEmpty()) send(emptyList())
        // First paint above remains small and fast. Fill each rail afterwards,
        // carrying the raw source cursor across empty age-filtered pages.
        for ((id, title, type) in rows) {
            var cursor = cursors[id] ?: 1
            while ((available[id]?.items?.size ?: 0) < 20 && cursor <= 20 && id !in exhausted) {
                val more = try { page(id, type, cursor) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { break }
                cursor = maxOf(cursor + 1, more.page + 1)
                if (more.page >= more.totalPages) exhausted += id
                val existing = available[id]?.items.orEmpty()
                val merged = (existing + more.items).dedupeByStableKey().take(20)
                if (merged.size > existing.size) {
                    available[id] = CatalogShelf(id, title, merged, ShelfType.POSTER)
                    send(available.values.toList())
                }
            }
        }
    }

    private suspend fun generalHomeShelves(publish: suspend (List<CatalogShelf>) -> Unit): List<CatalogShelf> = cachedMetadata(
        key = metadataCacheKey("getHomeShelves"), ttlMs = METADATA_LIST_TTL_MS,
    ) {
        fun request(id: String, title: String, type: ShelfType = ShelfType.POSTER,
                    fetch: suspend () -> List<MediaItem>): suspend () -> CatalogShelf? = {
            CatalogShelf(id, title, fetch().dedupeByStableKey().take(20), type)
        }
        val shelves = com.torve.presentation.home.loadHomeRows(listOf(
            request("trending-movies", "Trending Movies") { api.getTrending("movie", page = 1, requestCategory = "home.trending_movies.page_1").results.map { TmdbMappers.movieToMediaItem(it) } },
            request("trending-tv", "Trending TV Shows") { api.getTrendingTv(page = 1, requestCategory = "home.trending_tv.page_1").results.map { TmdbMappers.tvToMediaItem(it) } },
            request("now-playing", "Now Playing", ShelfType.LANDSCAPE) { api.getNowPlaying(page = 1, requestCategory = "home.now_playing.page_1").results.map { TmdbMappers.movieToMediaItem(it) } },
            request("popular-movies", "Popular Movies") { api.getPopular("movie", page = 1, requestCategory = "home.popular_movies.page_1").results.map { TmdbMappers.movieToMediaItem(it) } },
            request("upcoming", "Upcoming") { api.getUpcoming(page = 1, requestCategory = "home.upcoming.page_1").results.map { TmdbMappers.movieToMediaItem(it) }.filterUpcomingMovieReleases() },
            request("popular-tv", "Popular TV Shows") { api.getPopularTv(page = 1, requestCategory = "home.popular_tv.page_1").results.map { TmdbMappers.tvToMediaItem(it) } },
            request("top-rated", "Top Rated") { api.getTopRated("movie", page = 1, requestCategory = "home.top_rated.page_1").results.map { TmdbMappers.movieToMediaItem(it) } },
            request("airing-today", "Airing Today") { api.getAiringToday(page = 1, requestCategory = "home.airing_today.page_1").results.map { TmdbMappers.tvToMediaItem(it) } },
        ), publish = publish)
        if (shelves.isEmpty()) throw HomeShelvesUnavailableException(listOf("No Home source completed within its startup budget"))
        shelves
    }
}
