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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

interface MediaFavoritesRemoteDataSource {
    suspend fun listFavorites(accessToken: String): MediaFavoritesListDto

    suspend fun upsertFavorite(
        accessToken: String,
        favorite: MediaFavorite,
        sourceDeviceId: String?,
    ): MediaFavoriteMutationResultDto

    suspend fun deleteFavorite(accessToken: String, mediaKey: String): MediaFavoriteDeleteDto

    suspend fun collectFavoriteInvalidations(
        accessToken: String,
        onInvalidated: suspend () -> Unit,
    )
}

class MediaFavoritesApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val channelProvider: ContentChannelProvider? = null,
    private val installationIdProvider: () -> String? = { null },
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MediaFavoritesRemoteDataSource {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    override suspend fun listFavorites(accessToken: String): MediaFavoritesListDto {
        val response = httpClient.get("${baseUrl()}/me/media-favorites") {
            bearerAuth(accessToken)
            appendBackendHeaders()
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Media favorites list failed: HTTP ${response.status.value}")
        }
        return response.body()
    }

    override suspend fun upsertFavorite(
        accessToken: String,
        favorite: MediaFavorite,
        sourceDeviceId: String?,
    ): MediaFavoriteMutationResultDto {
        val response = httpClient.put("${baseUrl()}/me/media-favorites/${favorite.mediaKey.encodeURLPathPart()}") {
            bearerAuth(accessToken)
            appendBackendHeaders()
            contentType(ContentType.Application.Json)
            setBody(favorite.toUpsertDto(sourceDeviceId))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Media favorite save failed: HTTP ${response.status.value}")
        }
        return decodeMutationResult(response.bodyAsText(), favorite)
    }

    override suspend fun deleteFavorite(accessToken: String, mediaKey: String): MediaFavoriteDeleteDto {
        val response = httpClient.delete("${baseUrl()}/me/media-favorites/${mediaKey.encodeURLPathPart()}") {
            bearerAuth(accessToken)
            appendBackendHeaders()
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Media favorite delete failed: HTTP ${response.status.value}")
        }
        return response.body()
    }

    override suspend fun collectFavoriteInvalidations(
        accessToken: String,
        onInvalidated: suspend () -> Unit,
    ) {
        httpClient.prepareGet("${baseUrl()}/me/events") {
            bearerAuth(accessToken)
            header("Accept", "text/event-stream")
            appendBackendHeaders()
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

    private fun io.ktor.client.request.HttpRequestBuilder.appendBackendHeaders() {
        channelProvider?.channel?.let { header("X-Torve-Channel", it) }
        installationIdProvider()?.takeIf { it.isNotBlank() }?.let {
            header("X-Torve-Installation-Id", it)
        }
    }

    private fun decodeMutationResult(
        body: String,
        requestFavorite: MediaFavorite,
    ): MediaFavoriteMutationResultDto {
        if (body.isBlank()) {
            return MediaFavoriteMutationResultDto(favorite = requestFavorite.toDto())
        }
        val wrapper = runCatching {
            json.decodeFromString<MediaFavoriteMutationResponseDto>(body)
        }.getOrNull()
        if (wrapper != null) {
            val candidate = wrapper.favorite
                ?: wrapper.item
                ?: wrapper.mediaFavorite
                ?: wrapper.favorites.firstOrNull { it.mediaKey == requestFavorite.mediaKey }
                ?: wrapper.items.firstOrNull { it.mediaKey == requestFavorite.mediaKey }
                ?: requestFavorite.toDto(updatedAt = wrapper.updatedAt)
            return MediaFavoriteMutationResultDto(
                favorite = candidate,
                version = wrapper.version,
                updatedAt = wrapper.updatedAt ?: candidate.updatedAt,
            )
        }
        val direct = runCatching {
            json.decodeFromString<MediaFavoriteDto>(body)
        }.getOrNull()
        if (direct != null) {
            return MediaFavoriteMutationResultDto(
                favorite = direct,
                updatedAt = direct.updatedAt,
            )
        }
        return MediaFavoriteMutationResultDto(favorite = requestFavorite.toDto())
    }

    private companion object {
        const val MEDIA_FAVORITES_UPDATED_EVENT = "MEDIA_FAVORITES_UPDATED"
    }
}

@Serializable
data class MediaFavoritesListDto(
    val favorites: List<MediaFavoriteDto> = emptyList(),
    val items: List<MediaFavoriteDto> = emptyList(),
    @Serializable(with = NullableStringScalarSerializer::class)
    val version: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val favoriteRows: List<MediaFavoriteDto>
        get() = if (favorites.isNotEmpty()) favorites else items
}

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
data class AddMediaFavoriteRequest(
    @SerialName("media_key") val mediaKey: String,
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

typealias MediaFavoriteUpsertDto = AddMediaFavoriteRequest

data class MediaFavoriteMutationResultDto(
    val favorite: MediaFavoriteDto,
    val version: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class MediaFavoriteMutationResponseDto(
    val favorite: MediaFavoriteDto? = null,
    val item: MediaFavoriteDto? = null,
    @SerialName("media_favorite") val mediaFavorite: MediaFavoriteDto? = null,
    val favorites: List<MediaFavoriteDto> = emptyList(),
    val items: List<MediaFavoriteDto> = emptyList(),
    @Serializable(with = NullableStringScalarSerializer::class)
    val version: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
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
    return AddMediaFavoriteRequest(
        mediaKey = mediaKey,
        mediaType = mediaType.toMediaFavoriteWireValue(),
        tmdbId = tmdbId,
        imdbId = imdbId,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = rating,
        year = year,
        sourceDeviceId = sourceDeviceId?.takeIf { it.isUuidLike() },
    )
}

private fun MediaFavorite.toDto(updatedAt: String? = null): MediaFavoriteDto {
    return MediaFavoriteDto(
        mediaKey = mediaKey,
        mediaType = mediaType.toMediaFavoriteWireValue(),
        tmdbId = tmdbId,
        imdbId = imdbId,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = rating,
        year = year,
        addedAt = addedAt,
        updatedAt = updatedAt ?: this.updatedAt,
    )
}

private fun String.isUuidLike(): Boolean {
    if (length != 36) return false
    return indices.all { index ->
        val c = this[index]
        when (index) {
            8, 13, 18, 23 -> c == '-'
            else -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        }
    }
}

private object NullableStringScalarSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NullableStringScalar", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        return element.jsonPrimitive.contentOrNull
    }

    override fun serialize(encoder: Encoder, value: String?) {
        encoder.encodeString(value.orEmpty())
    }
}
