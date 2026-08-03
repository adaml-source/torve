package com.torve.data.integrations

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.MediaLifecycleRequest
import com.torve.domain.integrations.MediaLifecycleService
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Household-safe request gateway to Sonarr/Radarr through Seerr. */
class SeerrMediaLifecycleService(
    private val httpClient: HttpClient,
    private val prefs: PreferencesRepository,
    private val secretStore: IntegrationSecretStore,
) : MediaLifecycleService {
    override suspend fun isConfigured(): Boolean = configuredConnection() != null

    override suspend fun testConnection(serverUrl: String, apiKey: String): Boolean {
        val base = normalizeBaseUrl(serverUrl) ?: return false
        if (apiKey.isBlank()) return false
        return runCatching {
            // /status is public and would accept any key. /auth/me proves the
            // supplied API key can actually perform authenticated requests.
            httpClient.get("$base/api/v1/auth/me") {
                header(API_KEY_HEADER, apiKey.trim())
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }.status.isSuccess()
        }.getOrDefault(false)
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
        val response = httpClient.get("${connection.baseUrl}/api/v1/$route/$tmdbId") {
            authorized(connection.apiKey)
        }
        if (response.status == HttpStatusCode.NotFound) {
            return MediaLifecycleStatus(tmdbId, mediaType, MediaLifecycleState.NOT_REQUESTED)
        }
        check(response.status.isSuccess()) { "Seerr status request failed" }
        val details: SeerrDetailsDto = response.body()
        return deriveSeerrLifecycleStatus(tmdbId, mediaType, details.mediaInfo, seasons = seasons, is4k = is4k)
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
                            media = media,
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
        val base = normalizeBaseUrl(prefs.getString(KEY_SERVER_URL)) ?: return null
        val apiKey = secretStore.get(IntegrationSecretKey.SEERR_API_KEY)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Connection(base, apiKey)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorized(apiKey: String) {
        header(API_KEY_HEADER, apiKey)
        header(HttpHeaders.Accept, ContentType.Application.Json)
    }

    private data class Connection(val baseUrl: String, val apiKey: String)

    companion object {
        const val KEY_SERVER_URL = "seerr_server_url"
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
