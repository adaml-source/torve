package com.torve.data.metadata

import com.torve.data.network.HttpClientFactory
import com.torve.data.network.sanitizeNetworkDiagnosticText
import com.torve.platform.torveVerboseLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import kotlin.time.TimeSource

data class TmdbRequestDiagnostic(
    val requestId: String,
    val category: String,
    val host: String,
    val resolvedHost: String? = null,
    val endpoint: String,
    val params: String,
    val requestTimeoutMs: Long,
    val connectTimeoutMs: Long,
    val socketTimeoutMs: Long,
    val queuedAtMs: Long,
    val startedAtMs: Long? = null,
    val completedAtMs: Long? = null,
    val queueDelayMs: Long? = null,
    val activeRequestMs: Long? = null,
    val outcome: String,
    val executed: Boolean,
    val statusCode: Int? = null,
    val failureKind: String? = null,
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
) {
    fun toLogLine(): String {
        return buildString {
            append("TMDB_REQUEST ")
            append("request_id=").append(requestId)
            append("outcome=").append(outcome)
            append(" category=").append(category)
            append(" host=").append(host)
            resolvedHost?.let { append(" resolved_host=").append(it) }
            append(" endpoint=").append(endpoint)
            append(" params=").append(params)
            append(" request_timeout_ms=").append(requestTimeoutMs)
            append(" connect_timeout_ms=").append(connectTimeoutMs)
            append(" socket_timeout_ms=").append(socketTimeoutMs)
            append(" queued_at_ms=").append(queuedAtMs)
            startedAtMs?.let { append(" started_at_ms=").append(it) }
            completedAtMs?.let { append(" completed_at_ms=").append(it) }
            queueDelayMs?.let { append(" queue_delay_ms=").append(it) }
            activeRequestMs?.let { append(" active_request_ms=").append(it) }
            append(" executed=").append(executed)
            statusCode?.let { append(" status=").append(it) }
            failureKind?.let { append(" failureKind=").append(it) }
            exceptionType?.let { append(" exceptionType=").append(it) }
            exceptionMessage?.let { append(" exceptionMessage=").append(it) }
        }
    }
}

class TmdbApiClient(
    private val httpClient: HttpClient,
    private val apiKeyProvider: () -> String = ::tmdbApiKey,
    private val resolvedHostProvider: (String) -> List<String>? = ::lookupHostAddresses,
    private val diagnostics: (TmdbRequestDiagnostic) -> Unit = { diagnostic ->
        torveVerboseLog { diagnostic.toLogLine() }
    },
    /** Returns a TMDB language tag (e.g. "de", "es", "fr") or null for the TMDB default (English). */
    private val languageProvider: () -> String? = { null },
) {

    companion object {
        const val BASE_URL = "https://api.themoviedb.org/3"
        const val BASE_HOST = "api.themoviedb.org"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p"
        internal const val MAX_CONCURRENT_REQUESTS = 3

        private val requestLimiter = Semaphore(MAX_CONCURRENT_REQUESTS)
    }

    private var resolvedHostCache: String? = null

    /** Mutable language override. Set by SettingsViewModel when the user changes language. */
    var contentLanguage: String? = null

    /** Base params included in every TMDB call: API key + language (if set). */
    private fun baseParams(): List<Pair<String, Any>> = buildList {
        add("api_key" to apiKeyProvider())
        val lang = contentLanguage ?: languageProvider()
        torveVerboseLog { "TMDB_BASE_PARAMS contentLanguage=$contentLanguage languageProvider=${languageProvider()} resolved=$lang" }
        lang?.takeIf { it.isNotBlank() && it != "en" }?.let {
            add("language" to it)
        }
    }

    private fun imageLanguageParam(): String {
        val lang = contentLanguage ?: languageProvider()
        return listOf(lang, "en", "null")
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(",")
    }

    private fun videoLanguageParam(): String {
        val lang = contentLanguage ?: languageProvider()
        return listOf(lang, "en")
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(",")
    }

    suspend fun getTrending(
        type: String = "all",
        page: Int = 1,
        requestCategory: String = "tmdb.trending.$type",
    ): TmdbResponse<TmdbMovie> {
        return get(
            endpoint = "/trending/$type/week",
            requestCategory = requestCategory,
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun getPopular(
        type: String,
        page: Int = 1,
        requestCategory: String = "tmdb.popular.$type",
    ): TmdbResponse<TmdbMovie> {
        return get(
            endpoint = "/$type/popular",
            requestCategory = requestCategory,
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun getTopRated(
        type: String,
        page: Int = 1,
        requestCategory: String = "tmdb.top_rated.$type",
    ): TmdbResponse<TmdbMovie> {
        return get(
            endpoint = "/$type/top_rated",
            requestCategory = requestCategory,
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun getUpcoming(
        page: Int = 1,
        requestCategory: String = "tmdb.upcoming.movie",
    ): TmdbResponse<TmdbMovie> {
        return get(
            endpoint = "/movie/upcoming",
            requestCategory = requestCategory,
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun getNowPlaying(
        page: Int = 1,
        requestCategory: String = "tmdb.now_playing.movie",
    ): TmdbResponse<TmdbMovie> {
        return get(
            endpoint = "/movie/now_playing",
            requestCategory = requestCategory,
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun getAiringToday(
        page: Int = 1,
        requestCategory: String = "tmdb.airing_today.tv",
    ): TmdbResponse<TmdbTv> {
        return get(
            endpoint = "/tv/airing_today",
            requestCategory = requestCategory,
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun searchMulti(query: String, page: Int = 1): TmdbResponse<TmdbMultiResult> {
        return get(
            endpoint = "/search/multi",
            requestCategory = "tmdb.search.multi",
            parameters = baseParams() + listOf(
                "query" to query,
                "page" to page,
            ),
        )
    }

    /**
     * Adult-permissive movie search. Used by the desktop "Adult" catalog;
     * gated behind a Settings toggle and intended for users who source
     * playback from Usenet indexers that handle adult content.
     */
    suspend fun searchMoviesAdult(query: String, page: Int = 1): TmdbResponse<TmdbMovie> {
        return get(
            endpoint = "/search/movie",
            requestCategory = "tmdb.search.movie.adult",
            parameters = baseParams() + listOf(
                "query" to query,
                "page" to page,
                "include_adult" to true,
            ),
        )
    }

    /**
     * Adult-permissive popular discover. Surfaces TMDB's adult catalog
     * for the dedicated desktop "Adult" page entry point. Sorted by
     * popularity descending.
     */
    suspend fun discoverAdultMovies(page: Int = 1): TmdbResponse<TmdbMovie> {
        return get(
            endpoint = "/discover/movie",
            requestCategory = "tmdb.discover.movie.adult",
            parameters = baseParams() + listOf(
                "page" to page,
                "include_adult" to true,
                "sort_by" to "popularity.desc",
            ),
        )
    }

    suspend fun findByImdbId(imdbId: String): TmdbFindResponse {
        return get(
            endpoint = "/find/$imdbId",
            requestCategory = "tmdb.find.imdb",
            parameters = baseParams() + listOf(
                "external_source" to "imdb_id",
            ),
        )
    }

    suspend fun getMovieDetail(id: Int): TmdbMovie {
        return get(
            endpoint = "/movie/$id",
            requestCategory = "tmdb.detail.movie",
            parameters = baseParams() + listOf(
                "append_to_response" to "credits,videos,similar,external_ids,images",
                "include_image_language" to imageLanguageParam(),
                // Fetch trailers in the user's language and English as a
                // fallback so the trailer mapper has options. TMDB returns
                // every video for the title regardless when no filter is
                // set; we constrain to two locales to keep payloads small.
                "include_video_language" to videoLanguageParam(),
            ),
        )
    }

    suspend fun getTvDetail(id: Int): TmdbTv {
        return get(
            endpoint = "/tv/$id",
            requestCategory = "tmdb.detail.tv",
            parameters = baseParams() + listOf(
                "append_to_response" to "credits,videos,similar,external_ids,images",
                "include_image_language" to imageLanguageParam(),
                "include_video_language" to videoLanguageParam(),
            ),
        )
    }

    suspend fun getImages(type: String, id: Int): TmdbImages {
        return get(
            endpoint = "/$type/$id/images",
            requestCategory = "tmdb.images.$type",
            parameters = baseParams() + listOf(
                "include_image_language" to imageLanguageParam(),
            ),
        )
    }

    suspend fun getMovieExternalIds(id: Int): TmdbExternalIds {
        return get(
            endpoint = "/movie/$id/external_ids",
            requestCategory = "tmdb.external_ids.movie",
            parameters = baseParams(),
        )
    }

    suspend fun getTvExternalIds(id: Int): TmdbExternalIds {
        return get(
            endpoint = "/tv/$id/external_ids",
            requestCategory = "tmdb.external_ids.tv",
            parameters = baseParams(),
        )
    }

    suspend fun getSimilar(type: String, id: Int, page: Int = 1): TmdbResponse<TmdbMovie> {
        return get(
            endpoint = "/$type/$id/similar",
            requestCategory = "tmdb.similar.$type",
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun getTrendingTv(
        page: Int = 1,
        requestCategory: String = "tmdb.trending.tv",
    ): TmdbResponse<TmdbTv> {
        return get(
            endpoint = "/trending/tv/week",
            requestCategory = requestCategory,
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun getPopularTv(
        page: Int = 1,
        requestCategory: String = "tmdb.popular.tv",
    ): TmdbResponse<TmdbTv> {
        return get(
            endpoint = "/tv/popular",
            requestCategory = requestCategory,
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun getTopRatedTv(
        page: Int = 1,
        requestCategory: String = "tmdb.top_rated.tv",
    ): TmdbResponse<TmdbTv> {
        return get(
            endpoint = "/tv/top_rated",
            requestCategory = requestCategory,
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun getSimilarTv(id: Int, page: Int = 1): TmdbResponse<TmdbTv> {
        return get(
            endpoint = "/tv/$id/similar",
            requestCategory = "tmdb.similar.tv",
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun getRecommendations(type: String, id: Int, page: Int = 1): TmdbResponse<TmdbMovie> {
        return get(
            endpoint = "/$type/$id/recommendations",
            requestCategory = "tmdb.recommendations.$type",
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun getRecommendationsTv(id: Int, page: Int = 1): TmdbResponse<TmdbTv> {
        return get(
            endpoint = "/tv/$id/recommendations",
            requestCategory = "tmdb.recommendations.tv",
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun getPopularPeople(page: Int = 1): TmdbResponse<TmdbPersonSummary> {
        return get(
            endpoint = "/person/popular",
            requestCategory = "tmdb.person.popular",
            parameters = baseParams() + listOf(
                "page" to page,
            ),
        )
    }

    suspend fun searchPerson(query: String, page: Int = 1): TmdbResponse<TmdbPersonSummary> {
        return get(
            endpoint = "/search/person",
            requestCategory = "tmdb.search.person",
            parameters = baseParams() + listOf(
                "query" to query,
                "page" to page,
            ),
        )
    }

    suspend fun getPersonCredits(personId: Int): TmdbPersonCredits {
        return get(
            endpoint = "/person/$personId/combined_credits",
            requestCategory = "tmdb.person.credits",
            parameters = baseParams(),
        )
    }

    suspend fun getPersonDetail(personId: Int): TmdbPerson {
        return get(
            endpoint = "/person/$personId",
            requestCategory = "tmdb.person.detail",
            parameters = baseParams(),
        )
    }

    suspend fun discoverByGenre(type: String, genreId: Int, page: Int = 1): TmdbResponse<TmdbMovie> {
        return get(
            endpoint = "/discover/$type",
            requestCategory = "tmdb.discover_genre.$type",
            parameters = baseParams() + listOf(
                "with_genres" to genreId,
                "sort_by" to "popularity.desc",
                "page" to page,
            ),
        )
    }

    suspend fun discoverMovies(
        page: Int = 1,
        sortBy: String = "popularity.desc",
        withGenres: String? = null,
        minRating: Float? = null,
        year: Int? = null,
        yearTo: Int? = null,
        runtimeGte: Int? = null,
        runtimeLte: Int? = null,
        originCountries: String? = null,
        originalLanguage: String? = null,
        certification: String? = null,
        certificationGte: String? = null,
        certificationLte: String? = null,
        certificationCountry: String? = null,
        withCast: String? = null,
        withCrew: String? = null,
        withWatchProviders: String? = null,
        watchRegion: String? = null,
        withKeywords: String? = null,
        requestCategory: String = "tmdb.discover.movie",
    ): TmdbResponse<TmdbMovie> {
        return get(
            endpoint = "/discover/movie",
            requestCategory = requestCategory,
            parameters = buildList {
                addAll(baseParams())
                add("page" to page)
                add("sort_by" to sortBy)
                withGenres?.let { add("with_genres" to it) }
                minRating?.let { add("vote_average.gte" to it) }
                year?.let { add("primary_release_date.gte" to "$it-01-01") }
                yearTo?.let { add("primary_release_date.lte" to "$it-12-31") }
                runtimeGte?.let { add("with_runtime.gte" to it) }
                runtimeLte?.let { add("with_runtime.lte" to it) }
                originCountries?.let { add("with_origin_country" to it) }
                originalLanguage?.let { add("with_original_language" to it) }
                certificationCountry?.let { add("certification_country" to it) }
                certification?.let { add("certification" to it) }
                certificationGte?.let { add("certification.gte" to it) }
                certificationLte?.let { add("certification.lte" to it) }
                withCast?.let { add("with_cast" to it) }
                withCrew?.let { add("with_crew" to it) }
                withWatchProviders?.let { add("with_watch_providers" to it) }
                watchRegion?.let { add("watch_region" to it) }
                withKeywords?.let { add("with_keywords" to it) }
                // Floor vote_count at 100 globally to exclude indie shorts
                // with 1-2 votes that pollute the top of any sort
                // (especially IMDB-rating sort, which previously showed a
                // wall of "10.0" titles by 1-vote items). 100 is permissive
                // enough to keep most legitimate small-release content
                // while filtering the long tail of fringe entries.
                add("vote_count.gte" to 100)
            },
        )
    }

    suspend fun discoverTv(
        page: Int = 1,
        sortBy: String = "popularity.desc",
        withGenres: String? = null,
        minRating: Float? = null,
        year: Int? = null,
        yearTo: Int? = null,
        runtimeGte: Int? = null,
        runtimeLte: Int? = null,
        originCountries: String? = null,
        originalLanguage: String? = null,
        withCast: String? = null,
        withCrew: String? = null,
        withWatchProviders: String? = null,
        watchRegion: String? = null,
        withKeywords: String? = null,
        requestCategory: String = "tmdb.discover.tv",
    ): TmdbResponse<TmdbTv> {
        return get(
            endpoint = "/discover/tv",
            requestCategory = requestCategory,
            parameters = buildList {
                addAll(baseParams())
                add("page" to page)
                add("sort_by" to sortBy)
                withGenres?.let { add("with_genres" to it) }
                minRating?.let { add("vote_average.gte" to it) }
                year?.let { add("first_air_date.gte" to "$it-01-01") }
                yearTo?.let { add("first_air_date.lte" to "$it-12-31") }
                runtimeGte?.let { add("with_runtime.gte" to it) }
                runtimeLte?.let { add("with_runtime.lte" to it) }
                originCountries?.let { add("with_origin_country" to it) }
                originalLanguage?.let { add("with_original_language" to it) }
                withCast?.let { add("with_cast" to it) }
                withCrew?.let { add("with_crew" to it) }
                withWatchProviders?.let { add("with_watch_providers" to it) }
                watchRegion?.let { add("watch_region" to it) }
                withKeywords?.let { add("with_keywords" to it) }
                // Floor vote_count at 100 globally (see discoverMovie comment).
                add("vote_count.gte" to 100)
            },
        )
    }

    suspend fun getTvSeasonDetail(tvId: Int, seasonNumber: Int): TmdbSeasonDetail {
        return get(
            endpoint = "/tv/$tvId/season/$seasonNumber",
            requestCategory = "tmdb.season.detail.tv",
            parameters = baseParams(),
        )
    }

    suspend fun searchKeywords(query: String): TmdbResponse<TmdbKeyword> {
        return get(
            endpoint = "/search/keyword",
            requestCategory = "tmdb.search.keyword",
            parameters = baseParams() + listOf(
                "query" to query,
            ),
        )
    }

    suspend fun getWatchProviders(type: String = "movie", region: String = "US"): TmdbWatchProvidersResponse {
        return get(
            endpoint = "/watch/providers/$type",
            requestCategory = "tmdb.watch_providers.$type",
            parameters = baseParams() + listOf(
                "watch_region" to region,
            ),
        )
    }

    suspend fun getTitleWatchProviders(type: String, id: Int): TmdbTitleWatchProvidersResponse {
        return get(
            endpoint = "/$type/$id/watch/providers",
            requestCategory = "tmdb.title_watch_providers.$type",
            parameters = baseParams(),
        )
    }

    suspend fun getWatchProviderLogosFromBackend(
        baseUrl: String,
        type: String,
        region: String,
    ): Map<Int, String> {
        val response: Map<String, String> = httpClient.get(baseUrl) {
            url { appendPathSegments("tmdb", "providers", "logos") }
            parameter("type", type)
            parameter("region", region)
        }.body()
        return response.mapNotNull { (key, value) ->
            key.toIntOrNull()?.let { it to value }
        }.toMap()
    }

    private suspend inline fun <reified T> get(
        endpoint: String,
        requestCategory: String,
        parameters: List<Pair<String, Any?>>,
    ): T {
        val queuedAtMs = Clock.System.now().toEpochMilliseconds()
        val queueMark = TimeSource.Monotonic.markNow()
        val requestId = "$requestCategory@$queuedAtMs"
        val resolvedHost = resolvedHostCache ?: resolvedHostProvider(BASE_HOST)
            ?.joinToString(",")
            ?.also { resolvedHostCache = it }
        diagnostics(
            TmdbRequestDiagnostic(
                requestId = requestId,
                category = requestCategory,
                host = BASE_HOST,
                resolvedHost = resolvedHost,
                endpoint = endpoint,
                params = sanitizeParameters(parameters),
                requestTimeoutMs = HttpClientFactory.DEFAULT_REQUEST_TIMEOUT_MS,
                connectTimeoutMs = HttpClientFactory.DEFAULT_CONNECT_TIMEOUT_MS,
                socketTimeoutMs = HttpClientFactory.DEFAULT_SOCKET_TIMEOUT_MS,
                queuedAtMs = queuedAtMs,
                outcome = "queued",
                executed = false,
            ),
        )

        var startedAtMs: Long? = null
        try {
            return requestLimiter.withPermit {
                val startedMs = Clock.System.now().toEpochMilliseconds()
                startedAtMs = startedMs
                val runMark = TimeSource.Monotonic.markNow()
                val queuedMs = queueMark.elapsedNow().inWholeMilliseconds
                diagnostics(
                    TmdbRequestDiagnostic(
                        requestId = requestId,
                        category = requestCategory,
                        host = BASE_HOST,
                        resolvedHost = resolvedHost,
                        endpoint = endpoint,
                        params = sanitizeParameters(parameters),
                        requestTimeoutMs = HttpClientFactory.DEFAULT_REQUEST_TIMEOUT_MS,
                        connectTimeoutMs = HttpClientFactory.DEFAULT_CONNECT_TIMEOUT_MS,
                        socketTimeoutMs = HttpClientFactory.DEFAULT_SOCKET_TIMEOUT_MS,
                        queuedAtMs = queuedAtMs,
                        startedAtMs = startedMs,
                        queueDelayMs = queuedMs,
                        outcome = "start",
                        executed = true,
                    ),
                )
                try {
                    val response = request(endpoint, parameters)
                    val statusCode = response.status.value
                    val body = response.body<T>()
                    diagnostics(
                        TmdbRequestDiagnostic(
                            requestId = requestId,
                            category = requestCategory,
                            host = BASE_HOST,
                            resolvedHost = resolvedHost,
                            endpoint = endpoint,
                            params = sanitizeParameters(parameters),
                            requestTimeoutMs = HttpClientFactory.DEFAULT_REQUEST_TIMEOUT_MS,
                            connectTimeoutMs = HttpClientFactory.DEFAULT_CONNECT_TIMEOUT_MS,
                            socketTimeoutMs = HttpClientFactory.DEFAULT_SOCKET_TIMEOUT_MS,
                            queuedAtMs = queuedAtMs,
                            startedAtMs = startedMs,
                            completedAtMs = Clock.System.now().toEpochMilliseconds(),
                            queueDelayMs = queuedMs,
                            activeRequestMs = runMark.elapsedNow().inWholeMilliseconds,
                            outcome = if (response.status.isSuccess()) "success" else "http_non_success",
                            executed = true,
                            statusCode = statusCode,
                        ),
                    )
                    body
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) {
                        diagnostics(
                            TmdbRequestDiagnostic(
                                requestId = requestId,
                                category = requestCategory,
                                host = BASE_HOST,
                                resolvedHost = resolvedHost,
                                endpoint = endpoint,
                                params = sanitizeParameters(parameters),
                                requestTimeoutMs = HttpClientFactory.DEFAULT_REQUEST_TIMEOUT_MS,
                                connectTimeoutMs = HttpClientFactory.DEFAULT_CONNECT_TIMEOUT_MS,
                                socketTimeoutMs = HttpClientFactory.DEFAULT_SOCKET_TIMEOUT_MS,
                                queuedAtMs = queuedAtMs,
                                completedAtMs = Clock.System.now().toEpochMilliseconds(),
                                queueDelayMs = queueMark.elapsedNow().inWholeMilliseconds,
                                outcome = "failure",
                                executed = false,
                                failureKind = classifyFailure(throwable),
                                exceptionType = throwable::class.simpleName ?: throwable::class.toString(),
                                exceptionMessage = sanitizeNetworkDiagnosticText(throwable.message),
                            ),
                        )
                        throw throwable
                    }
                    diagnostics(
                        TmdbRequestDiagnostic(
                            requestId = requestId,
                            category = requestCategory,
                            host = BASE_HOST,
                            resolvedHost = resolvedHost,
                            endpoint = endpoint,
                            params = sanitizeParameters(parameters),
                            requestTimeoutMs = HttpClientFactory.DEFAULT_REQUEST_TIMEOUT_MS,
                            connectTimeoutMs = HttpClientFactory.DEFAULT_CONNECT_TIMEOUT_MS,
                            socketTimeoutMs = HttpClientFactory.DEFAULT_SOCKET_TIMEOUT_MS,
                            queuedAtMs = queuedAtMs,
                            startedAtMs = startedMs,
                            completedAtMs = Clock.System.now().toEpochMilliseconds(),
                            queueDelayMs = queuedMs,
                            activeRequestMs = runMark.elapsedNow().inWholeMilliseconds,
                            outcome = "failure",
                            executed = true,
                            failureKind = classifyFailure(throwable),
                            exceptionType = throwable::class.simpleName ?: throwable::class.toString(),
                            exceptionMessage = sanitizeNetworkDiagnosticText(throwable.message),
                        ),
                    )
                    throw throwable
                }
            }
        } catch (throwable: Throwable) {
            if (startedAtMs == null) {
                diagnostics(
                    TmdbRequestDiagnostic(
                        requestId = requestId,
                        category = requestCategory,
                        host = BASE_HOST,
                        resolvedHost = resolvedHost,
                        endpoint = endpoint,
                        params = sanitizeParameters(parameters),
                        requestTimeoutMs = HttpClientFactory.DEFAULT_REQUEST_TIMEOUT_MS,
                        connectTimeoutMs = HttpClientFactory.DEFAULT_CONNECT_TIMEOUT_MS,
                        socketTimeoutMs = HttpClientFactory.DEFAULT_SOCKET_TIMEOUT_MS,
                        queuedAtMs = queuedAtMs,
                        completedAtMs = Clock.System.now().toEpochMilliseconds(),
                        queueDelayMs = queueMark.elapsedNow().inWholeMilliseconds,
                        outcome = "failure",
                        executed = false,
                        failureKind = classifyFailure(throwable),
                        exceptionType = throwable::class.simpleName ?: throwable::class.toString(),
                        exceptionMessage = sanitizeNetworkDiagnosticText(throwable.message),
                    ),
                )
            }
            throw throwable
        }
    }

    private suspend fun request(
        endpoint: String,
        parameters: List<Pair<String, Any?>>,
    ): HttpResponse {
        return httpClient.get("$BASE_URL$endpoint") {
            parameters.forEach { (name, value) ->
                if (value != null) {
                    parameter(name, value)
                }
            }
        }
    }

    private fun sanitizeParameters(parameters: List<Pair<String, Any?>>): String {
        return parameters
            .filter { (name, value) -> name != "api_key" && value != null }
            .joinToString("&") { (name, value) -> "$name=$value" }
            .ifBlank { "-" }
    }

    private fun classifyFailure(throwable: Throwable): String {
        return when (throwable) {
            is HttpRequestTimeoutException -> "timeout"
            is CancellationException -> "cancelled"
            else -> "exception"
        }
    }
}
