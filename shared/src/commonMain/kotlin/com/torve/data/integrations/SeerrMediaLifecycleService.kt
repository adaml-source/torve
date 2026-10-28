package com.torve.data.integrations

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.MediaLifecycleRequest
import com.torve.domain.integrations.MediaLifecycleService
import com.torve.domain.integrations.ConnectionProbeResult
import com.torve.domain.integrations.MediaLifecycleState
import com.torve.domain.integrations.MediaLifecycleStatus
import com.torve.domain.integrations.MediaLifecycleEntry
import com.torve.domain.model.MediaType
import com.torve.domain.repository.PreferencesRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.util.hex
import io.ktor.util.sha1
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.torve.presentation.settings.OptionalAppFeature
import com.torve.presentation.settings.isAppFeatureEnabled

/** Household-safe request gateway to Sonarr/Radarr through Seerr. */
class SeerrMediaLifecycleService(
    private val httpClient: HttpClient,
    private val prefs: PreferencesRepository,
    private val secretStore: IntegrationSecretStore,
) : MediaLifecycleService {
    private data class VerifiedRoutes(val connection: Connection, val mediaTypes: Set<MediaType>)
    private val verifiedRoutes = MutableStateFlow<VerifiedRoutes?>(null)
    private val readinessJson = Json { ignoreUnknownKeys = true }

    override suspend fun isConfigured(): Boolean = configuredConnection() != null

    override suspend fun canAutomaticallyCheckStatus(mediaType: MediaType): Boolean {
        val connection = configuredConnection() ?: return false
        val verified = verifiedRoutes.value ?: run {
            val saved = prefs.getString(KEY_VERIFIED_REQUEST_ROUTES)?.let { raw ->
                runCatching { readinessJson.decodeFromString<SeerrVerifiedRoutesDto>(raw) }.getOrNull()
            } ?: return false
            if (saved.connectionFingerprint != connection.fingerprint()) return false
            VerifiedRoutes(connection, MediaType.entries.filter { it.name in saved.mediaTypes }.toSet())
                .also { verifiedRoutes.value = it }
        }
        return verified.connection == connection && mediaType in verified.mediaTypes
    }

    override suspend fun testConnection(serverUrl: String, apiKey: String): Boolean =
        probeConnection(serverUrl, apiKey) == ConnectionProbeResult.CONNECTED

    override suspend fun probeConnection(serverUrl: String, apiKey: String): ConnectionProbeResult {
        if (!prefs.isAppFeatureEnabled(OptionalAppFeature.ARR_STACK)) return ConnectionProbeResult.SETUP_INCOMPLETE
        verifiedRoutes.value = null
        prefs.remove(KEY_VERIFIED_REQUEST_ROUTES)
        val base = normalizeBaseUrl(serverUrl) ?: return ConnectionProbeResult.SETUP_INCOMPLETE
        if (apiKey.isBlank()) return ConnectionProbeResult.SETUP_INCOMPLETE
        val connection = Connection(base, apiKey.trim())
        val authenticated = authenticatedProbe {
            // /status is public and would accept any key. /auth/me proves the
            // supplied API key can actually perform authenticated requests.
            httpClient.get("$base/api/v1/auth/me") {
                header(API_KEY_HEADER, apiKey.trim())
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }.status
        }
        if (authenticated != ConnectionProbeResult.CONNECTED) return authenticated
        // Seerr owns these ARR connections. A Radarr route does not make TV
        // requests ready, and a Sonarr route does not make movie requests ready.
        // Inspect them during setup/health checks, never on detail-page entry.
        val ready = coroutineScope {
            listOf(MediaType.MOVIE to "radarr", MediaType.SERIES to "sonarr").map { (type, route) ->
                async {
                    val response = runCatching {
                        val servers = httpClient.get("$base/api/v1/settings/$route") {
                            authorized(connection.apiKey)
                        }
                        if (!servers.status.isSuccess()) return@runCatching false
                        servers.body<List<SeerrRequestServerDto>>().any {
                            it.isDefault && !it.is4k && it.activeProfileId > 0 && it.activeDirectory.isNotBlank()
                        }
                    }.getOrElse {
                        if (it is CancellationException) throw it
                        false
                    }
                    type.takeIf { response }
                }
            }.awaitAll().filterNotNull().toSet()
        }
        verifiedRoutes.value = VerifiedRoutes(connection, ready)
        prefs.setString(KEY_VERIFIED_REQUEST_ROUTES, readinessJson.encodeToString(
            SeerrVerifiedRoutesDto.serializer(),
            SeerrVerifiedRoutesDto(connection.fingerprint(), ready.map { it.name }.sorted()),
        ))
        return ConnectionProbeResult.CONNECTED
    }

    override suspend fun getStatus(
        tmdbId: Int,
        mediaType: MediaType,
        seasons: List<Int>,
        is4k: Boolean,
    ): MediaLifecycleStatus {
        val connection = configuredConnection()
            ?: return MediaLifecycleStatus(tmdbId, mediaType, MediaLifecycleState.UNCONFIGURED)
        val route = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        try {
            val response = httpClient.get("${connection.baseUrl}/api/v1/$route/$tmdbId") {
                authorized(connection.apiKey)
            }
            if (response.status == HttpStatusCode.NotFound) {
                return MediaLifecycleStatus(tmdbId, mediaType, MediaLifecycleState.NOT_REQUESTED)
            }
            check(response.status.isSuccess()) { "Seerr status request failed" }
            val details: SeerrDetailsDto = response.body()
            return deriveSeerrLifecycleStatus(tmdbId, mediaType, details.mediaInfo, seasons = seasons, is4k = is4k)
        } catch (error: Exception) {
            if (error !is CancellationException) {
                verifiedRoutes.value = null
                prefs.remove(KEY_VERIFIED_REQUEST_ROUTES)
            }
            throw error
        }
    }

    override suspend fun request(request: MediaLifecycleRequest): MediaLifecycleStatus {
        val connection = configuredConnection()
            ?: return MediaLifecycleStatus(request.tmdbId, request.mediaType, MediaLifecycleState.UNCONFIGURED)
        val httpResponse = httpClient.post("${connection.baseUrl}/api/v1/request") {
            authorized(connection.apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                SeerrCreateRequestDto(
                    mediaType = if (request.mediaType == MediaType.MOVIE) "movie" else "tv",
                    mediaId = request.tmdbId,
                    seasons = request.seasons.takeIf { it.isNotEmpty() },
                    is4k = request.is4k,
                ),
            )
        }
        if (httpResponse.status == HttpStatusCode.Conflict) {
            return getStatus(request.tmdbId, request.mediaType, request.seasons, request.is4k)
        }
        check(httpResponse.status.isSuccess()) { "Seerr library request failed" }
        val response: SeerrRequestDto = httpResponse.body()
        return deriveSeerrLifecycleStatus(
            request.tmdbId,
            request.mediaType,
            response.media,
            response,
            request.seasons,
            request.is4k,
        )
    }

    override suspend fun retry(requestId: Int): MediaLifecycleStatus {
        val connection = configuredConnection()
            ?: return MediaLifecycleStatus(0, MediaType.MOVIE, MediaLifecycleState.UNCONFIGURED)
        val httpResponse = httpClient.post("${connection.baseUrl}/api/v1/request/$requestId/retry") {
            authorized(connection.apiKey)
            contentType(ContentType.Application.Json)
        }
        check(httpResponse.status.isSuccess()) { "Seerr library retry failed" }
        val response: SeerrRequestDto = httpResponse.body()
        val type = if (response.type.equals("tv", ignoreCase = true)) MediaType.SERIES else MediaType.MOVIE
        return deriveSeerrLifecycleStatus(response.media?.tmdbId ?: 0, type, response.media, response)
    }

    override suspend fun deleteRequest(requestId: Int): Boolean {
        val connection = configuredConnection() ?: return false
        val response = httpClient.delete("${connection.baseUrl}/api/v1/request/$requestId") {
            authorized(connection.apiKey)
        }
        return response.status.isSuccess() || response.status == HttpStatusCode.NotFound
    }

    override suspend fun listRecent(limit: Int): List<MediaLifecycleEntry> {
        val connection = configuredConnection() ?: return emptyList()
        val response = httpClient.get("${connection.baseUrl}/api/v1/request") {
            authorized(connection.apiKey)
            parameter("take", limit.coerceIn(1, 100))
            parameter("skip", 0)
            parameter("filter", "all")
            parameter("sort", "modified")
            parameter("sortDirection", "desc")
            parameter("mediaType", "all")
        }
        check(response.status.isSuccess()) { "Seerr request list failed" }
        val page: SeerrRequestPageDto = response.body()
        return coroutineScope {
            page.results.mapNotNull { request ->
                val media = request.media ?: return@mapNotNull null
                val tmdbId = media.tmdbId.takeIf { it > 0 } ?: return@mapNotNull null
                val mediaType = when {
                    request.type.equals("tv", ignoreCase = true) ||
                        media.mediaType.equals("tv", ignoreCase = true) -> MediaType.SERIES
                    else -> MediaType.MOVIE
                }
                async {
                    val route = if (mediaType == MediaType.MOVIE) "movie" else "tv"
                    val details = runCatching {
                        httpClient.get("${connection.baseUrl}/api/v1/$route/$tmdbId") {
                            authorized(connection.apiKey)
                        }.body<SeerrMediaDetailsDto>()
                    }.getOrNull()
                    MediaLifecycleEntry(
                        status = deriveSeerrLifecycleStatus(
                            tmdbId = tmdbId,
                            mediaType = mediaType,
                            media = details?.mediaInfo ?: media,
                            fallbackRequest = request,
                            is4k = request.is4k,
                        ),
                        title = details?.displayTitle
                            ?: if (mediaType == MediaType.MOVIE) "Movie $tmdbId" else "Series $tmdbId",
                        year = details?.displayDate?.take(4)?.toIntOrNull(),
                        overview = details?.overview,
                        posterUrl = details?.posterPath.toTmdbImageUrl("w500"),
                        backdropUrl = details?.backdropPath.toTmdbImageUrl("w1280"),
                        rating = details?.voteAverage?.takeIf { it > 0.0 },
                        tvdbId = media.tvdbId,
                    )
                }
            }.awaitAll()
        }
    }

    private suspend fun configuredConnection(): Connection? {
        if (!prefs.isAppFeatureEnabled(OptionalAppFeature.ARR_STACK)) return null
        val base = normalizeBaseUrl(prefs.getString(KEY_SERVER_URL)) ?: return null
        val apiKey = secretStore.get(IntegrationSecretKey.SEERR_API_KEY)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Connection(base, apiKey)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorized(apiKey: String) {
        header(API_KEY_HEADER, apiKey)
        header(HttpHeaders.Accept, ContentType.Application.Json)
    }

    private data class Connection(val baseUrl: String, val apiKey: String) {
        // Change detection for a local UI/network gate, not authentication.
        // Persist no credentials in the readiness record.
        fun fingerprint(): String = hex(sha1("$baseUrl\n$apiKey".encodeToByteArray()))
    }

    companion object {
        const val KEY_SERVER_URL = "seerr_server_url"
        private const val KEY_VERIFIED_REQUEST_ROUTES = "seerr_verified_request_routes_v1"
        private const val API_KEY_HEADER = "X-Api-Key"

        fun normalizeBaseUrl(value: String?): String? {
            val normalized = value?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return null
            return normalized
        }
    }
}

internal fun deriveSeerrLifecycleStatus(
    tmdbId: Int,
    mediaType: MediaType,
    media: SeerrMediaInfoDto?,
    fallbackRequest: SeerrRequestDto? = null,
    seasons: List<Int> = emptyList(),
    is4k: Boolean = false,
): MediaLifecycleStatus {
    if (media == null && fallbackRequest == null) {
        return MediaLifecycleStatus(tmdbId, mediaType, MediaLifecycleState.NOT_REQUESTED)
    }
    val requestedSeasons = seasons.toSet()
    val mediaRequests = media?.requests.orEmpty()
    val matchingRequests = mediaRequests.filter { request ->
        request.is4k == is4k && (
            requestedSeasons.isEmpty() ||
                request.seasons.isEmpty() ||
                request.seasons.any { it.seasonNumber in requestedSeasons }
            )
    }
    val request = matchingRequests.maxByOrNull { it.id }
        ?: fallbackRequest?.takeIf { it.is4k == is4k }
    if (media != null && requestedSeasons.isNotEmpty() && mediaRequests.isNotEmpty() && request == null) {
        return MediaLifecycleStatus(tmdbId, mediaType, MediaLifecycleState.NOT_REQUESTED, is4k = is4k)
    }
    val mediaStatus = if (is4k) media?.status4k ?: media?.status else media?.status
    val state = when {
        mediaStatus == 5 -> MediaLifecycleState.AVAILABLE
        mediaStatus == 4 -> MediaLifecycleState.PARTIALLY_AVAILABLE
        request?.status == 3 -> MediaLifecycleState.DECLINED
        request?.status == 1 -> MediaLifecycleState.PENDING_APPROVAL
        mediaStatus == 3 -> MediaLifecycleState.PROCESSING
        mediaStatus == 2 && request?.status == 2 -> MediaLifecycleState.PROCESSING
        request?.status == 2 -> MediaLifecycleState.APPROVED
        mediaStatus == 6 -> MediaLifecycleState.DELETED
        media == null -> MediaLifecycleState.NOT_REQUESTED
        else -> MediaLifecycleState.UNKNOWN
    }
    return MediaLifecycleStatus(
        tmdbId = media?.tmdbId?.takeIf { it > 0 } ?: tmdbId,
        mediaType = mediaType,
        state = state,
        requestId = request?.id,
        is4k = is4k,
        updatedAt = request?.updatedAt ?: media?.updatedAt,
    )
}

@Serializable
internal data class SeerrDetailsDto(val mediaInfo: SeerrMediaInfoDto? = null)

/** Ignore server credentials in the response; only request-route defaults are needed. */
@Serializable
internal data class SeerrRequestServerDto(
    val isDefault: Boolean = false,
    val is4k: Boolean = false,
    val activeProfileId: Int = 0,
    val activeDirectory: String = "",
)

@Serializable
private data class SeerrVerifiedRoutesDto(
    val connectionFingerprint: String,
    val mediaTypes: List<String>,
)

@Serializable
internal data class SeerrMediaInfoDto(
    val id: Int = 0,
    val tmdbId: Int = 0,
    val tvdbId: Int? = null,
    val mediaType: String? = null,
    val status: Int = 1,
    val status4k: Int? = null,
    val requests: List<SeerrRequestDto> = emptyList(),
    val updatedAt: String? = null,
)

@Serializable
internal data class SeerrRequestDto(
    val id: Int = 0,
    val status: Int = 0,
    val media: SeerrMediaInfoDto? = null,
    val is4k: Boolean = false,
    val seasons: List<SeerrRequestSeasonDto> = emptyList(),
    val updatedAt: String? = null,
    @SerialName("type") val type: String? = null,
)

@Serializable
private data class SeerrRequestPageDto(
    val results: List<SeerrRequestDto> = emptyList(),
)

@Serializable
private data class SeerrMediaDetailsDto(
    val mediaInfo: SeerrMediaInfoDto? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val voteAverage: Double? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
) {
    val displayTitle: String?
        get() = title?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
    val displayDate: String?
        get() = releaseDate?.takeIf { it.isNotBlank() } ?: firstAirDate?.takeIf { it.isNotBlank() }
}

private fun String?.toTmdbImageUrl(size: String): String? {
    val path = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith('/') -> "https://image.tmdb.org/t/p/$size$path"
        else -> "https://image.tmdb.org/t/p/$size/$path"
    }
}

@Serializable
internal data class SeerrRequestSeasonDto(
    val seasonNumber: Int = 0,
    val status: Int = 0,
)

@Serializable
private data class SeerrCreateRequestDto(
    val mediaType: String,
    val mediaId: Int,
    val seasons: List<Int>? = null,
    val is4k: Boolean = false,
)
