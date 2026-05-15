package com.torve.data.account

import com.torve.data.contentpolicy.ContentChannelProvider
import com.torve.domain.model.MediaFavorite
import com.torve.domain.model.MediaType
import com.torve.domain.model.toMediaFavoriteWireValue
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class MediaFavoritesApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val channelProvider: ContentChannelProvider? = null,
) {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun listFavorites(accessToken: String): MediaFavoritesListDto {
        val response = httpClient.get("${baseUrl()}/me/media-favorites") {
            bearerAuth(accessToken)
            appendChannelHeader()
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Media favorites list failed: HTTP ${response.status.value}")
        }
        return response.body()
    }

    suspend fun upsertFavorite(
        accessToken: String,
        favorite: MediaFavorite,
        sourceDeviceId: String?,
    ): MediaFavoriteDto {
        val response = httpClient.put("${baseUrl()}/me/media-favorites/${favorite.mediaKey.encodeURLPathPart()}") {
            bearerAuth(accessToken)
            appendChannelHeader()
            contentType(ContentType.Application.Json)
            setBody(favorite.toUpsertDto(sourceDeviceId))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Media favorite save failed: HTTP ${response.status.value} ${response.bodyAsText().take(120)}")
        }
        return response.body()
    }

    suspend fun deleteFavorite(accessToken: String, mediaKey: String): MediaFavoriteDeleteDto {
        val response = httpClient.delete("${baseUrl()}/me/media-favorites/${mediaKey.encodeURLPathPart()}") {
            bearerAuth(accessToken)
            appendChannelHeader()
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Media favorite delete failed: HTTP ${response.status.value}")
        }
        return response.body()
    }

    suspend fun collectFavoriteInvalidations(
        accessToken: String,
        onInvalidated: suspend () -> Unit,
    ) {
        httpClient.prepareGet("${baseUrl()}/me/events") {
            bearerAuth(accessToken)
            header("Accept", "text/event-stream")
            appendChannelHeader()
        }.execute { response ->
            if (response.status == HttpStatusCode.Unauthorized || !response.status.isSuccess()) {
                return@execute
            }
            val channel = response.bodyAsChannel()
            var currentEvent = ""
            while (currentCoroutineContext().isActive && !channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                when {
                    line.startsWith("event:") -> {
                        currentEvent = line.removePrefix("event:").trim()
                    }

                    line.startsWith("data:") -> {
                        if (currentEvent == MEDIA_FAVORITES_UPDATED_EVENT) {
                            onInvalidated()
                        }
                        currentEvent = ""
                    }

                    line.isBlank() -> {
                        currentEvent = ""
                    }
                }
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appendChannelHeader() {
        channelProvider?.channel?.let { header("X-Torve-Channel", it) }
    }

    private companion object {
        const val MEDIA_FAVORITES_UPDATED_EVENT = "MEDIA_FAVORITES_UPDATED"
    }
}

@Serializable
data class MediaFavoritesListDto(
    val items: List<MediaFavoriteDto> = emptyList(),
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class MediaFavoriteDto(
    val id: String? = null,
    @SerialName("media_key") val mediaKey: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val title: String,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
    @SerialName("added_at") val addedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("source_device_id") val sourceDeviceId: String? = null,
)

@Serializable
data class MediaFavoriteUpsertDto(
    @SerialName("media_type") val mediaType: String,
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val title: String,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
    @SerialName("source_device_id") val sourceDeviceId: String? = null,
)

@Serializable
data class MediaFavoriteDeleteDto(
    val removed: Boolean = false,
)

fun MediaFavoriteDto.toDomain(): MediaFavorite {
    return MediaFavorite(
        mediaKey = mediaKey,
        mediaType = MediaType.fromString(mediaType),
        tmdbId = tmdbId,
        imdbId = imdbId,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = rating,
        year = year,
        addedAt = addedAt,
        updatedAt = updatedAt,
    )
}

private fun MediaFavorite.toUpsertDto(sourceDeviceId: String?): MediaFavoriteUpsertDto {
    return MediaFavoriteUpsertDto(
        mediaType = mediaType.toMediaFavoriteWireValue(),
        tmdbId = tmdbId,
        imdbId = imdbId,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = rating,
        year = year,
        sourceDeviceId = sourceDeviceId?.takeIf { it.isNotBlank() },
    )
}
