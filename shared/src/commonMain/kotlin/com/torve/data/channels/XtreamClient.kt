package com.torve.data.channels

import com.torve.data.network.HttpClientFactory
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.datetime.Clock

private const val XTREAM_MAX_JSON_BODY_BYTES = 4 * 1024 * 1024
private const val XTREAM_ALL_STREAM_MAX_JSON_BODY_BYTES = 16 * 1024 * 1024
// Streaming list requests retain only the current JSON item in the decoder.
// Keep this separate from the buffered-list limit above so a large valid
// catalog is not downloaded, discarded, and fetched again category by category.
internal const val XTREAM_STREAMING_ALL_STREAM_MAX_JSON_BODY_BYTES = 128 * 1024 * 1024
private const val XTREAM_SERIES_INFO_MAX_JSON_BODY_BYTES = 4 * 1024 * 1024
private const val XTREAM_READ_CHUNK_BYTES = 32 * 1024
private const val XTREAM_INITIAL_BODY_BUFFER_BYTES = 64 * 1024
private const val XTREAM_WRAPPED_LIST_MAX_JSON_BODY_BYTES = 2 * 1024 * 1024
private const val XTREAM_MAX_JSON_ITEM_BYTES = 512 * 1024

class XtreamResponseTooLargeException(
    val limitBytes: Int,
    val contentLength: Long? = null,
) : IllegalStateException(
    if (contentLength != null) {
        "Xtream response is too large ($contentLength bytes, limit $limitBytes bytes)."
    } else {
        "Xtream response exceeded $limitBytes bytes."
    },
)

/**
 * Xtream Codes API client.
 * Uses the player_api.php endpoint to fetch live, VOD, and series content.
 */
class XtreamClient(
    @Suppress("unused")
    private val httpClient: HttpClient,
    private val json: Json,
) {
    private val xtreamHttpClient: HttpClient by lazy {
        HttpClientFactory.createEpgStreamingClient(forceIdentityEncoding = false)
    }

    /**
     * Authenticate and get server info.
     */
    suspend fun authenticate(server: String, username: String, password: String): XtreamAuthInfo {
        val response = getJson<XtreamAuthResponse>(server, username, password)
        return XtreamAuthInfo(
            isAuthenticated = response.userInfo?.auth == 1,
            status = response.userInfo?.status ?: "Unknown",
            expirationDate = response.userInfo?.expDate,
            activeCons = response.userInfo?.activeCons,
            maxConnections = response.userInfo?.maxConnections,
            serverUrl = server.trimEnd('/'),
        )
    }

    /**
     * Fetch live stream categories.
     */
    suspend fun getLiveCategories(
        server: String,
        username: String,
        password: String,
    ): List<XtreamCategory> {
        return getJsonList(server, username, password, action = "get_live_categories")
    }

    /**
     * Fetch live streams, optionally filtered by category.
     */
    suspend fun getLiveStreams(
        server: String,
        username: String,
        password: String,
        categoryId: String? = null,
    ): List<XtreamLiveStream> {
        return getJsonList(
            server = server,
            username = username,
            password = password,
            action = "get_live_streams",
            extraParams = categoryId?.let { mapOf("category_id" to it) }.orEmpty(),
            maxBytes = if (categoryId == null) XTREAM_ALL_STREAM_MAX_JSON_BODY_BYTES else XTREAM_MAX_JSON_BODY_BYTES,
        )
    }

    /**
     * Fetch VOD categories.
     */
    suspend fun getVodCategories(
        server: String,
        username: String,
        password: String,
    ): List<XtreamCategory> {
        return getJsonList(server, username, password, action = "get_vod_categories")
    }

    /**
     * Fetch VOD streams, optionally filtered by category.
     */
    suspend fun getVodStreams(
        server: String,
        username: String,
        password: String,
        categoryId: String? = null,
    ): List<XtreamVodStream> {
        return getJsonList(
            server = server,
            username = username,
            password = password,
            action = "get_vod_streams",
            extraParams = categoryId?.let { mapOf("category_id" to it) }.orEmpty(),
            maxBytes = if (categoryId == null) XTREAM_ALL_STREAM_MAX_JSON_BODY_BYTES else XTREAM_MAX_JSON_BODY_BYTES,
        )
    }

    /**
     * Fetch series categories.
     */
    suspend fun getSeriesCategories(
        server: String,
        username: String,
        password: String,
    ): List<XtreamCategory> {
        return getJsonList(server, username, password, action = "get_series_categories")
    }

    /**
     * Fetch series, optionally filtered by category.
     */
    suspend fun getSeries(
        server: String,
        username: String,
        password: String,
        categoryId: String? = null,
    ): List<XtreamSeries> {
        return getJsonList(
            server = server,
            username = username,
            password = password,
            action = "get_series",
            extraParams = categoryId?.let { mapOf("category_id" to it) }.orEmpty(),
            maxBytes = if (categoryId == null) XTREAM_ALL_STREAM_MAX_JSON_BODY_BYTES else XTREAM_MAX_JSON_BODY_BYTES,
        )
    }

    /**
     * Fetch full series details, including playable episode ids.
     *
     * Xtream list rows are show containers. Playback must use an episode id
     * from get_series_info, not the series_id from get_series.
     */
    suspend fun getSeriesInfo(
        server: String,
        username: String,
        password: String,
        seriesId: String,
    ): XtreamSeriesInfo {
        val raw = xtreamHttpClient.prepareGet("${server.trimEnd('/')}/player_api.php") {
            parameter("username", username)
            parameter("password", password)
            parameter("action", "get_series_info")
            parameter("series_id", seriesId)
            parameter("_t", Clock.System.now().toEpochMilliseconds().toString())
        }.execute { it.safeXtreamBodyAsText(maxBytes = XTREAM_SERIES_INFO_MAX_JSON_BODY_BYTES) }
        val root = json.parseToJsonElement(raw) as? JsonObject
            ?: return XtreamSeriesInfo(seriesId = seriesId)
        val info = (root["info"] as? JsonObject)?.toXtreamSeries()?.copy(seriesId = seriesId)
        return XtreamSeriesInfo(
            seriesId = seriesId,
            info = info,
            episodes = root["episodes"].toXtreamSeriesEpisodes(),
        )
    }

    private suspend inline fun <reified T> getJson(
        server: String,
        username: String,
        password: String,
        action: String? = null,
        extraParams: Map<String, String> = emptyMap(),
    ): T {
        val deserializer = serializer<T>()
        val raw = xtreamHttpClient.prepareGet("${server.trimEnd('/')}/player_api.php") {
            parameter("username", username)
            parameter("password", password)
            action?.let { parameter("action", it) }
            extraParams.forEach { (key, value) -> parameter(key, value) }
        }.execute { it.safeXtreamBodyAsText() }
        return json.decodeFromString(deserializer, raw)
    }

    private suspend inline fun <reified T> getJsonList(
        server: String,
        username: String,
        password: String,
        action: String,
        extraParams: Map<String, String> = emptyMap(),
        maxBytes: Int = XTREAM_MAX_JSON_BODY_BYTES,
    ): List<T> {
        val deserializer = serializer<T>()
        return xtreamHttpClient.prepareGet("${server.trimEnd('/')}/player_api.php") {
            parameter("username", username)
            parameter("password", password)
            parameter("action", action)
            parameter("_t", Clock.System.now().toEpochMilliseconds().toString())
            extraParams.forEach { (key, value) -> parameter(key, value) }
        }.execute { response ->
            response.decodeXtreamJsonList(
                json = json,
                deserializer = deserializer,
                maxBytes = maxBytes,
            )
        }
    }

    private suspend fun HttpResponse.safeXtreamBodyAsText(
        maxBytes: Int = XTREAM_MAX_JSON_BODY_BYTES,
    ): String {
        val declaredLength = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxBytes) {
            throw XtreamResponseTooLargeException(maxBytes, declaredLength)
        }

        val channel = bodyAsChannel()
        val chunk = ByteArray(minOf(XTREAM_READ_CHUNK_BYTES, maxBytes + 1))
        var bytes = ByteArray(minOf(XTREAM_INITIAL_BODY_BUFFER_BYTES, maxBytes))
        var total = 0
        while (true) {
            val remainingBeforeLimit = maxBytes + 1 - total
            if (remainingBeforeLimit <= 0) {
                throw XtreamResponseTooLargeException(maxBytes)
            }
            val read = channel.readAvailable(chunk, 0, minOf(chunk.size, remainingBeforeLimit))
            if (read <= 0) break
            val newTotal = total + read
            if (newTotal > maxBytes) {
                throw XtreamResponseTooLargeException(maxBytes)
            }
            if (newTotal > bytes.size) {
                var newSize = bytes.size
                while (newSize < newTotal && newSize < maxBytes) {
                    newSize = minOf(maxBytes, newSize * 2)
                }
                if (newSize < newTotal) {
                    throw XtreamResponseTooLargeException(maxBytes)
                }
                bytes = bytes.copyOf(newSize)
            }
            chunk.copyInto(bytes, destinationOffset = total, startIndex = 0, endIndex = read)
            total = newTotal
        }
        return bytes.decodeToString(0, total)
    }

    private suspend fun <T> HttpResponse.decodeXtreamJsonList(
        json: Json,
        deserializer: DeserializationStrategy<T>,
        maxBytes: Int,
    ): List<T> {
        val declaredLength = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxBytes) {
            throw XtreamResponseTooLargeException(maxBytes, declaredLength)
        }

        val decoder = XtreamJsonListStreamDecoder(
            json = json,
            deserializer = deserializer,
            maxBytes = maxBytes,
        )
        val channel = bodyAsChannel()
        val chunk = ByteArray(minOf(XTREAM_READ_CHUNK_BYTES, maxBytes + 1))
        while (true) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read < 0) break
            if (read == 0) continue
            decoder.consume(chunk, 0, read)
        }
        return decoder.finish()
    }

    private suspend fun <T> HttpResponse.streamXtreamJsonList(
        json: Json,
        deserializer: DeserializationStrategy<T>,
        maxBytes: Int,
        onItem: (T) -> Unit,
    ) {
        val declaredLength = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxBytes) {
            throw XtreamResponseTooLargeException(maxBytes, declaredLength)
        }
        val decoder = XtreamJsonListStreamDecoder(
            json = json,
            deserializer = deserializer,
            maxBytes = maxBytes,
            onItemDecoded = onItem,
        )
        val channel = bodyAsChannel()
        val chunk = ByteArray(minOf(XTREAM_READ_CHUNK_BYTES, maxBytes + 1))
        while (true) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read < 0) break
            if (read == 0) continue
            decoder.consume(chunk, 0, read)
        }
        decoder.finish()
    }

    private suspend fun <T> streamJsonList(
        server: String,
        username: String,
        password: String,
        action: String,
        extraParams: Map<String, String> = emptyMap(),
        maxBytes: Int = XTREAM_MAX_JSON_BODY_BYTES,
        deserializer: DeserializationStrategy<T>,
        onItem: (T) -> Unit,
    ) {
        xtreamHttpClient.prepareGet("${server.trimEnd('/')}/player_api.php") {
            parameter("username", username)
            parameter("password", password)
            parameter("action", action)
            parameter("_t", Clock.System.now().toEpochMilliseconds().toString())
            extraParams.forEach { (key, value) -> parameter(key, value) }
        }.execute { response ->
            response.streamXtreamJsonList(
                json = json,
                deserializer = deserializer,
                maxBytes = maxBytes,
                onItem = onItem,
            )
        }
    }

    suspend fun streamLiveStreams(
        server: String,
        username: String,
        password: String,
        categoryId: String? = null,
        onStream: (XtreamLiveStream) -> Unit,
    ) = streamJsonList(
        server = server,
        username = username,
        password = password,
        action = "get_live_streams",
        extraParams = categoryId?.let { mapOf("category_id" to it) }.orEmpty(),
        maxBytes = if (categoryId == null) {
            XTREAM_STREAMING_ALL_STREAM_MAX_JSON_BODY_BYTES
        } else {
            XTREAM_MAX_JSON_BODY_BYTES
        },
        deserializer = XtreamLiveStream.serializer(),
        onItem = onStream,
    )

    suspend fun streamVodStreams(
        server: String,
        username: String,
        password: String,
        categoryId: String? = null,
        onStream: (XtreamVodStream) -> Unit,
    ) = streamJsonList(
        server = server,
        username = username,
        password = password,
        action = "get_vod_streams",
        extraParams = categoryId?.let { mapOf("category_id" to it) }.orEmpty(),
        maxBytes = if (categoryId == null) {
            XTREAM_STREAMING_ALL_STREAM_MAX_JSON_BODY_BYTES
        } else {
            XTREAM_MAX_JSON_BODY_BYTES
        },
        deserializer = XtreamVodStream.serializer(),
        onItem = onStream,
    )

    suspend fun streamSeries(
        server: String,
        username: String,
        password: String,
        onSeries: (XtreamSeries) -> Unit,
    ) = streamJsonList(
        server = server,
        username = username,
        password = password,
        action = "get_series",
        maxBytes = XTREAM_STREAMING_ALL_STREAM_MAX_JSON_BODY_BYTES,
        deserializer = XtreamSeries.serializer(),
        onItem = onSeries,
    )

    fun mapLiveStreamToChannel(
        stream: XtreamLiveStream,
        categoryMap: Map<String, XtreamCategory>,
        server: String,
        username: String,
        password: String,
        playlistId: String,
    ): Channel {
        val categoryName = categoryMap[stream.categoryId]?.categoryName
        val streamUrl = "${server.trimEnd('/')}/live/$username/$password/${stream.streamId}.ts"
        val hasArchive = (stream.tvArchive ?: 0) > 0
        return Channel(
            name = stream.name ?: "Unknown",
            url = streamUrl,
            tvgId = stream.epgChannelId,
            tvgName = stream.name,
            tvgLogo = stream.streamIcon,
            groupTitle = categoryName,
            channelNumber = stream.num,
            catchupType = if (hasArchive) "xc" else null,
            catchupDays = if (hasArchive) stream.tvArchiveDuration?.takeIf { it > 0 } ?: 1 else null,
            playlistId = playlistId,
            contentType = ChannelContentType.LIVE,
        )
    }

    fun mapVodStreamToChannel(
        stream: XtreamVodStream,
        categoryMap: Map<String, XtreamCategory>,
        server: String,
        username: String,
        password: String,
        playlistId: String,
    ): Channel {
        val categoryName = categoryMap[stream.categoryId]?.categoryName
        val ext = stream.containerExtension ?: "mp4"
        val streamUrl = "${server.trimEnd('/')}/movie/$username/$password/${stream.streamId}.$ext"
        return Channel(
            name = stream.name ?: "Unknown",
            url = streamUrl,
            tvgName = stream.name,
            tvgLogo = stream.streamIcon,
            groupTitle = categoryName?.let { "VOD: $it" } ?: "VOD",
            channelNumber = stream.num,
            kodiProps = buildMap {
                put("vod_stream_id", stream.streamId)
                stream.categoryId?.takeIf { it.isNotBlank() }?.let { put("vod_category_id", it) }
                stream.containerExtension?.takeIf { it.isNotBlank() }?.let { put("vod_container_extension", it) }
                stream.rating?.takeIf { it.isNotBlank() }?.let { put("vod_rating", it) }
                stream.rating5Based?.takeIf { it > 0.0 }?.let { put("vod_rating_5based", it.toString()) }
                stream.youtubeTrailer?.takeIf { it.isNotBlank() }?.let { put("vod_youtube_trailer", it) }
                stream.genre?.takeIf { it.isNotBlank() }?.let { put("vod_genre", it) }
            },
            playlistId = playlistId,
            contentType = ChannelContentType.VOD_MOVIE,
        )
    }

    fun mapSeriesStreamToChannel(
        series: XtreamSeries,
        categoryMap: Map<String, XtreamCategory>,
        server: String,
        username: String,
        password: String,
        playlistId: String,
    ): Channel {
        val categoryName = categoryMap[series.categoryId]?.categoryName
        val seriesUrl = "${server.trimEnd('/')}/series/$username/$password/${series.seriesId}.mp4"
        return Channel(
            name = series.name ?: "Unknown",
            url = seriesUrl,
            tvgName = series.name,
            tvgLogo = series.cover,
            groupTitle = categoryName?.let { "VOD: $it" } ?: "VOD",
            channelNumber = series.num,
            kodiProps = buildMap {
                put("vod_series_id", series.seriesId)
                series.categoryId?.takeIf { it.isNotBlank() }?.let { put("vod_category_id", it) }
                series.rating?.takeIf { it.isNotBlank() }?.let { put("vod_rating", it) }
                series.rating5Based?.takeIf { it > 0.0 }?.let { put("vod_rating_5based", it.toString()) }
                series.lastModified?.takeIf { it.isNotBlank() }?.let { put("vod_last_modified", it) }
                series.plot?.takeIf { it.isNotBlank() }?.let { put("vod_plot", it) }
                series.cast?.takeIf { it.isNotBlank() }?.let { put("vod_cast", it) }
                series.director?.takeIf { it.isNotBlank() }?.let { put("vod_director", it) }
                series.genre?.takeIf { it.isNotBlank() }?.let { put("vod_genre", it) }
                series.releaseDate?.takeIf { it.isNotBlank() }?.let { put("vod_release_date", it) }
                series.backdropPath.firstOrNull()?.takeIf { it.isNotBlank() }?.let { put("vod_backdrop", it) }
                series.youtubeTrailer?.takeIf { it.isNotBlank() }?.let { put("vod_youtube_trailer", it) }
                series.episodeRunTime?.takeIf { it.isNotBlank() }?.let { put("vod_episode_run_time", it) }
            },
            playlistId = playlistId,
            contentType = ChannelContentType.VOD_SERIES,
        )
    }

    /**
     * Convert Xtream live streams to Channel models.
     */
    fun mapLiveToChannels(
        streams: List<XtreamLiveStream>,
        categories: List<XtreamCategory>,
        server: String,
        username: String,
        password: String,
        playlistId: String,
    ): List<Channel> {
        val categoryMap = categories.associateBy { it.categoryId }
        return streams.map { stream ->
            val categoryName = categoryMap[stream.categoryId]?.categoryName
            val streamUrl = "${server.trimEnd('/')}/live/$username/$password/${stream.streamId}.ts"
            val hasArchive = (stream.tvArchive ?: 0) > 0
            Channel(
                name = stream.name ?: "Unknown",
                url = streamUrl,
                tvgId = stream.epgChannelId,
                tvgName = stream.name,
                tvgLogo = stream.streamIcon,
                groupTitle = categoryName,
                channelNumber = stream.num,
                catchupType = if (hasArchive) "xc" else null,
                catchupDays = if (hasArchive) {
                    stream.tvArchiveDuration?.takeIf { it > 0 } ?: 1
                } else {
                    null
                },
                playlistId = playlistId,
                contentType = ChannelContentType.LIVE,
            )
        }
    }

    /**
     * Convert Xtream VOD streams to Channel models (for unified browsing).
     */
    fun mapVodToChannels(
        streams: List<XtreamVodStream>,
        categories: List<XtreamCategory>,
        server: String,
        username: String,
        password: String,
        playlistId: String,
    ): List<Channel> {
        val categoryMap = categories.associateBy { it.categoryId }
        return streams.map { stream ->
            val categoryName = categoryMap[stream.categoryId]?.categoryName
            val ext = stream.containerExtension ?: "mp4"
            val streamUrl = "${server.trimEnd('/')}/movie/$username/$password/${stream.streamId}.$ext"
            // Xtream exposes movies and series through separate endpoints. Treating
            // movie categories such as "Netflix" or "Anime" as series based on
            // labels makes Movies/Shows drift and duplicates provider data.
            val contentType = ChannelContentType.VOD_MOVIE
            Channel(
                name = stream.name ?: "Unknown",
                url = streamUrl,
                tvgName = stream.name,
                tvgLogo = stream.streamIcon,
                groupTitle = categoryName?.let { "VOD: $it" } ?: "VOD",
                channelNumber = stream.num,
                kodiProps = buildMap {
                    put("vod_stream_id", stream.streamId)
                    stream.categoryId?.takeIf { it.isNotBlank() }?.let { put("vod_category_id", it) }
                    stream.containerExtension?.takeIf { it.isNotBlank() }?.let { put("vod_container_extension", it) }
                    stream.rating?.takeIf { it.isNotBlank() }?.let { put("vod_rating", it) }
                    stream.rating5Based?.takeIf { it > 0.0 }?.let { put("vod_rating_5based", it.toString()) }
                    stream.youtubeTrailer?.takeIf { it.isNotBlank() }?.let { put("vod_youtube_trailer", it) }
                    stream.genre?.takeIf { it.isNotBlank() }?.let { put("vod_genre", it) }
                },
                playlistId = playlistId,
                contentType = contentType,
            )
        }
    }

    /**
     * Convert Xtream series catalog entries to Channel models so VOD Movies/Shows
     * can be separated by the provider's native movie vs series endpoints.
     */
    fun mapSeriesToChannels(
        series: List<XtreamSeries>,
        categories: List<XtreamCategory>,
        server: String,
        username: String,
        password: String,
        playlistId: String,
    ): List<Channel> {
        val categoryMap = categories.associateBy { it.categoryId }
        return series.map { show ->
            val categoryName = categoryMap[show.categoryId]?.categoryName
            val seriesUrl = "${server.trimEnd('/')}/series/$username/$password/${show.seriesId}.mp4"
            Channel(
                name = show.name ?: "Unknown",
                url = seriesUrl,
                tvgName = show.name,
                tvgLogo = show.cover,
                groupTitle = categoryName?.let { "VOD: $it" } ?: "VOD",
                channelNumber = show.num,
                kodiProps = buildMap {
                    put("vod_series_id", show.seriesId)
                    show.categoryId?.takeIf { it.isNotBlank() }?.let { put("vod_category_id", it) }
                    show.rating?.takeIf { it.isNotBlank() }?.let { put("vod_rating", it) }
                    show.rating5Based?.takeIf { it > 0.0 }?.let { put("vod_rating_5based", it.toString()) }
                    show.lastModified?.takeIf { it.isNotBlank() }?.let { put("vod_last_modified", it) }
                    show.plot?.takeIf { it.isNotBlank() }?.let { put("vod_plot", it) }
                    show.cast?.takeIf { it.isNotBlank() }?.let { put("vod_cast", it) }
                    show.director?.takeIf { it.isNotBlank() }?.let { put("vod_director", it) }
                    show.genre?.takeIf { it.isNotBlank() }?.let { put("vod_genre", it) }
                    show.releaseDate?.takeIf { it.isNotBlank() }?.let { put("vod_release_date", it) }
                    show.backdropPath.firstOrNull()?.takeIf { it.isNotBlank() }?.let { put("vod_backdrop", it) }
                    show.youtubeTrailer?.takeIf { it.isNotBlank() }?.let { put("vod_youtube_trailer", it) }
                    show.episodeRunTime?.takeIf { it.isNotBlank() }?.let { put("vod_episode_run_time", it) }
                },
                playlistId = playlistId,
                contentType = ChannelContentType.VOD_SERIES,
            )
        }
    }
}

/**
 * Incrementally decodes the normal Xtream top-level JSON array one row at a
 * time. This intentionally avoids holding the complete response as both a
 * ByteArray and a UTF-16 String, which can exhaust the 192 MB Fire TV heap.
 * Small non-standard object-wrapped responses retain the compatibility path.
 *
 * When [onItemDecoded] is supplied, each decoded item is dispatched immediately
 * via the callback instead of being accumulated in [decoded]. This prevents the
 * large intermediate list that caused OOM on Fire TV with 95K-channel playlists.
 */
internal class XtreamJsonListStreamDecoder<T>(
    private val json: Json,
    private val deserializer: DeserializationStrategy<T>,
    private val maxBytes: Int,
    private val onItemDecoded: ((T) -> Unit)? = null,
) {
    private enum class Mode {
        BEFORE_ROOT,
        ARRAY_BETWEEN_ITEMS,
        ARRAY_ITEM,
        ARRAY_SCALAR,
        WRAPPED_OBJECT,
        DONE,
    }

    private var mode = Mode.BEFORE_ROOT
    private var totalBytes = 0
    private var itemDepth = 0
    private var inString = false
    private var escaped = false
    private var seenItems = 0
    private var failedItems = 0
    private val decoded = mutableListOf<T>()
    private val itemBuffer = GrowingXtreamByteBuffer(XTREAM_MAX_JSON_ITEM_BYTES)
    private val wrappedBuffer = GrowingXtreamByteBuffer(
        minOf(maxBytes, XTREAM_WRAPPED_LIST_MAX_JSON_BODY_BYTES),
    )

    fun consume(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        repeat(length) { index ->
            consumeByte(bytes[offset + index])
        }
    }

    fun finish(): List<T> {
        when (mode) {
            Mode.BEFORE_ROOT -> return emptyList()
            Mode.ARRAY_BETWEEN_ITEMS, Mode.ARRAY_SCALAR, Mode.ARRAY_ITEM -> {
                error("Incomplete Xtream JSON array response.")
            }
            Mode.WRAPPED_OBJECT -> {
                val raw = wrappedBuffer.decodeToString()
                val rows = json.parseToJsonElement(raw).flexRows()
                val items = rows.mapNotNull { row ->
                    runCatching { json.decodeFromJsonElement(deserializer, row) }.getOrNull()
                }
                if (onItemDecoded != null) {
                    items.forEach { onItemDecoded.invoke(it) }
                    return emptyList()
                }
                return items
            }
            Mode.DONE -> Unit
        }
        if (seenItems > 0 && decoded.isEmpty() && failedItems > 0 && onItemDecoded == null) {
            error("Xtream JSON response contained no decodable rows.")
        }
        return decoded
    }

    private fun consumeByte(byte: Byte) {
        totalBytes++
        if (totalBytes > maxBytes) {
            throw XtreamResponseTooLargeException(maxBytes)
        }

        when (mode) {
            Mode.BEFORE_ROOT -> consumeBeforeRoot(byte)
            Mode.ARRAY_BETWEEN_ITEMS -> consumeBetweenItems(byte)
            Mode.ARRAY_ITEM -> consumeArrayItem(byte)
            Mode.ARRAY_SCALAR -> consumeArrayScalar(byte)
            Mode.WRAPPED_OBJECT -> wrappedBuffer.append(byte)
            Mode.DONE -> if (!byte.isJsonWhitespace()) {
                error("Unexpected data after Xtream JSON array.")
            }
        }
    }

    private fun consumeBeforeRoot(byte: Byte) {
        if (byte.isJsonWhitespace() || byte.isUtf8BomByte()) return
        when (byte.toInt().toChar()) {
            '[' -> mode = Mode.ARRAY_BETWEEN_ITEMS
            '{' -> {
                mode = Mode.WRAPPED_OBJECT
                wrappedBuffer.append(byte)
            }
            else -> error("Xtream list response must be a JSON array or object.")
        }
    }

    private fun consumeBetweenItems(byte: Byte) {
        if (byte.isJsonWhitespace() || byte.toInt().toChar() == ',') return
        when (byte.toInt().toChar()) {
            ']' -> mode = Mode.DONE
            '{', '[' -> {
                mode = Mode.ARRAY_ITEM
                itemDepth = 1
                inString = false
                escaped = false
                itemBuffer.reset()
                itemBuffer.append(byte)
            }
            else -> {
                mode = Mode.ARRAY_SCALAR
                inString = byte.toInt().toChar() == '"'
                escaped = false
            }
        }
    }

    private fun consumeArrayItem(byte: Byte) {
        itemBuffer.append(byte)
        val char = byte.toInt().toChar()
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            return
        }
        when (char) {
            '"' -> inString = true
            '{', '[' -> itemDepth++
            '}', ']' -> {
                itemDepth--
                if (itemDepth == 0) {
                    decodeCurrentItem()
                    mode = Mode.ARRAY_BETWEEN_ITEMS
                }
            }
        }
    }

    private fun consumeArrayScalar(byte: Byte) {
        val char = byte.toInt().toChar()
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            return
        }
        when (char) {
            '"' -> inString = true
            ',' -> mode = Mode.ARRAY_BETWEEN_ITEMS
            ']' -> mode = Mode.DONE
        }
    }

    private fun decodeCurrentItem() {
        seenItems++
        val row = runCatching {
            json.decodeFromString(deserializer, itemBuffer.decodeToString())
        }.getOrNull()
        if (row == null) {
            failedItems++
        } else if (onItemDecoded != null) {
            onItemDecoded.invoke(row)
        } else {
            decoded += row
        }
        itemBuffer.reset()
    }
}

private class GrowingXtreamByteBuffer(private val maxBytes: Int) {
    private var bytes = ByteArray(minOf(1024, maxBytes.coerceAtLeast(1)))
    private var size = 0

    fun append(byte: Byte) {
        if (size >= maxBytes) error("Xtream JSON value exceeded $maxBytes bytes.")
        if (size == bytes.size) {
            bytes = bytes.copyOf(minOf(maxBytes, bytes.size * 2))
        }
        bytes[size++] = byte
    }

    fun reset() {
        size = 0
    }

    fun decodeToString(): String = bytes.decodeToString(0, size)
}

private fun Byte.isJsonWhitespace(): Boolean = when (toInt().toChar()) {
    ' ', '\t', '\r', '\n' -> true
    else -> false
}

private fun Byte.isUtf8BomByte(): Boolean {
    val value = toInt() and 0xff
    return value == 0xef || value == 0xbb || value == 0xbf
}

// --- API Response Models ---

@Serializable
data class XtreamAuthResponse(
    @SerialName("user_info") val userInfo: XtreamUserInfo? = null,
    @SerialName("server_info") val serverInfo: XtreamServerInfo? = null,
)

@Serializable
data class XtreamUserInfo(
    val auth: Int? = null,
    val status: String? = null,
    val username: String? = null,
    @SerialName("exp_date") val expDate: String? = null,
    @SerialName("active_cons") val activeCons: String? = null,
    @SerialName("max_connections") val maxConnections: String? = null,
)

@Serializable
data class XtreamServerInfo(
    val url: String? = null,
    val port: String? = null,
    @SerialName("https_port") val httpsPort: String? = null,
    @SerialName("server_protocol") val serverProtocol: String? = null,
)

data class XtreamAuthInfo(
    val isAuthenticated: Boolean,
    val status: String,
    val expirationDate: String? = null,
    val activeCons: String? = null,
    val maxConnections: String? = null,
    val serverUrl: String,
)

@Serializable
data class XtreamCategory(
    @SerialName("category_id")
    @Serializable(with = FlexibleXtreamStringSerializer::class)
    val categoryId: String = "",
    @SerialName("category_name")
    @Serializable(with = FlexibleXtreamStringSerializer::class)
    val categoryName: String = "",
    @SerialName("parent_id")
    @Serializable(with = FlexibleXtreamIntSerializer::class)
    val parentId: Int = 0,
)

@Serializable
data class XtreamLiveStream(
    @Serializable(with = FlexibleXtreamNullableIntSerializer::class)
    val num: Int? = null,
    val name: String? = null,
    @SerialName("stream_type") val streamType: String? = null,
    @SerialName("stream_id")
    @Serializable(with = FlexibleXtreamStringSerializer::class)
    val streamId: String = "",
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id")
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val epgChannelId: String? = null,
    @SerialName("category_id")
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val categoryId: String? = null,
    @SerialName("tv_archive")
    @Serializable(with = FlexibleXtreamNullableIntSerializer::class)
    val tvArchive: Int? = null,
    @SerialName("tv_archive_duration")
    @Serializable(with = FlexibleXtreamNullableIntSerializer::class)
    val tvArchiveDuration: Int? = null,
)

@Serializable
data class XtreamVodStream(
    @Serializable(with = FlexibleXtreamNullableIntSerializer::class)
    val num: Int? = null,
    val name: String? = null,
    @SerialName("stream_type") val streamType: String? = null,
    @SerialName("stream_id")
    @Serializable(with = FlexibleXtreamStringSerializer::class)
    val streamId: String = "",
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("category_id")
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val categoryId: String? = null,
    @SerialName("container_extension")
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val containerExtension: String? = null,
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val rating: String? = null,
    @SerialName("rating_5based")
    @Serializable(with = FlexibleXtreamNullableDoubleSerializer::class)
    val rating5Based: Double? = null,
    @SerialName("youtube_trailer")
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val youtubeTrailer: String? = null,
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val genre: String? = null,
)

@Serializable
data class XtreamSeries(
    @Serializable(with = FlexibleXtreamNullableIntSerializer::class)
    val num: Int? = null,
    val name: String? = null,
    @SerialName("series_id")
    @Serializable(with = FlexibleXtreamStringSerializer::class)
    val seriesId: String = "",
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val cover: String? = null,
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val plot: String? = null,
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val cast: String? = null,
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val director: String? = null,
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val genre: String? = null,
    @SerialName("releaseDate")
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val releaseDate: String? = null,
    @SerialName("category_id")
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val categoryId: String? = null,
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val rating: String? = null,
    @SerialName("rating_5based")
    @Serializable(with = FlexibleXtreamNullableDoubleSerializer::class)
    val rating5Based: Double? = null,
    @SerialName("last_modified")
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val lastModified: String? = null,
    @SerialName("backdrop_path")
    @Serializable(with = FlexibleXtreamStringListSerializer::class)
    val backdropPath: List<String> = emptyList(),
    @SerialName("youtube_trailer")
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val youtubeTrailer: String? = null,
    @SerialName("episode_run_time")
    @Serializable(with = FlexibleXtreamNullableStringSerializer::class)
    val episodeRunTime: String? = null,
)

data class XtreamSeriesInfo(
    val seriesId: String,
    val info: XtreamSeries? = null,
    val episodes: List<XtreamSeriesEpisode> = emptyList(),
)

data class XtreamSeriesEpisode(
    val id: String,
    val episodeNum: Int? = null,
    val title: String? = null,
    val containerExtension: String? = null,
    val season: Int? = null,
    val directSource: String? = null,
    val movieImage: String? = null,
    val plot: String? = null,
    val durationSecs: Int? = null,
    val rating: Double? = null,
)

private object FlexibleXtreamStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleXtreamString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return jsonDecoder.decodeJsonElement().flexStringOrNull().orEmpty()
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

private object FlexibleXtreamNullableStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleXtreamNullableString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString().ifBlank { null }
        return jsonDecoder.decodeJsonElement().flexStringOrNull()
    }

    override fun serialize(encoder: Encoder, value: String?) {
        encoder.encodeString(value.orEmpty())
    }
}

private object FlexibleXtreamIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleXtreamInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeInt()
        return jsonDecoder.decodeJsonElement().flexIntOrNull() ?: 0
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

private object FlexibleXtreamNullableIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleXtreamNullableInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? {
        val jsonDecoder = decoder as? JsonDecoder ?: return runCatching { decoder.decodeInt() }.getOrNull()
        return jsonDecoder.decodeJsonElement().flexIntOrNull()
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        encoder.encodeString(value?.toString().orEmpty())
    }
}

private object FlexibleXtreamNullableDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleXtreamNullableDouble", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double? {
        val jsonDecoder = decoder as? JsonDecoder ?: return runCatching { decoder.decodeDouble() }.getOrNull()
        return jsonDecoder.decodeJsonElement().flexDoubleOrNull()
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        encoder.encodeString(value?.toString().orEmpty())
    }
}

private object FlexibleXtreamStringListSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleXtreamStringList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
            .takeIf { it.isNotBlank() }
            ?.let(::listOf)
            .orEmpty()
        return jsonDecoder.decodeJsonElement().flexStringList()
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeString(value.joinToString(","))
    }
}

private fun JsonObject.toXtreamCategory(): XtreamCategory = XtreamCategory(
    categoryId = get("category_id").flexStringOrNull().orEmpty(),
    categoryName = get("category_name").flexStringOrNull().orEmpty(),
    parentId = get("parent_id").flexIntOrNull() ?: 0,
)

private fun JsonObject.toXtreamLiveStream(): XtreamLiveStream = XtreamLiveStream(
    num = get("num").flexIntOrNull(),
    name = get("name").flexStringOrNull(),
    streamType = get("stream_type").flexStringOrNull(),
    streamId = get("stream_id").flexStringOrNull().orEmpty(),
    streamIcon = get("stream_icon").flexStringOrNull(),
    epgChannelId = get("epg_channel_id").flexStringOrNull(),
    categoryId = get("category_id").flexStringOrNull(),
    tvArchive = get("tv_archive").flexIntOrNull(),
    tvArchiveDuration = get("tv_archive_duration").flexIntOrNull(),
)

private fun JsonObject.toXtreamVodStream(): XtreamVodStream = XtreamVodStream(
    num = get("num").flexIntOrNull(),
    name = get("name").flexStringOrNull(),
    streamType = get("stream_type").flexStringOrNull(),
    streamId = get("stream_id").flexStringOrNull().orEmpty(),
    streamIcon = get("stream_icon").flexStringOrNull(),
    categoryId = get("category_id").flexStringOrNull(),
    containerExtension = get("container_extension").flexStringOrNull(),
    rating = get("rating").flexStringOrNull(),
    rating5Based = get("rating_5based").flexDoubleOrNull(),
    youtubeTrailer = get("youtube_trailer").flexStringOrNull(),
    genre = get("genre").flexStringOrNull(),
)

private fun JsonObject.toXtreamSeries(): XtreamSeries = XtreamSeries(
    num = get("num").flexIntOrNull(),
    name = get("name").flexStringOrNull(),
    seriesId = get("series_id").flexStringOrNull().orEmpty(),
    cover = get("cover").flexStringOrNull(),
    plot = get("plot").flexStringOrNull(),
    cast = get("cast").flexStringOrNull(),
    director = get("director").flexStringOrNull(),
    genre = get("genre").flexStringOrNull(),
    releaseDate = get("releaseDate").flexStringOrNull(),
    categoryId = get("category_id").flexStringOrNull(),
    rating = get("rating").flexStringOrNull(),
    rating5Based = get("rating_5based").flexDoubleOrNull(),
    lastModified = get("last_modified").flexStringOrNull(),
    backdropPath = get("backdrop_path").flexStringList(),
    youtubeTrailer = get("youtube_trailer").flexStringOrNull(),
    episodeRunTime = get("episode_run_time").flexStringOrNull(),
)

private fun JsonElement?.toXtreamSeriesEpisodes(): List<XtreamSeriesEpisode> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonObject)?.toXtreamSeriesEpisode() }
    is JsonObject -> flatMap { (seasonKey, value) ->
        val fallbackSeason = seasonKey.toIntOrNull()
        when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonObject)?.toXtreamSeriesEpisode(fallbackSeason) }
            is JsonObject -> value.values.mapNotNull { (it as? JsonObject)?.toXtreamSeriesEpisode(fallbackSeason) }
            else -> emptyList()
        }
    }
    else -> emptyList()
}

private fun JsonObject.toXtreamSeriesEpisode(fallbackSeason: Int? = null): XtreamSeriesEpisode {
    val info = get("info") as? JsonObject
    return XtreamSeriesEpisode(
        id = get("id").flexStringOrNull().orEmpty(),
        episodeNum = get("episode_num").flexIntOrNull(),
        title = get("title").flexStringOrNull(),
        containerExtension = get("container_extension").flexStringOrNull(),
        season = get("season").flexIntOrNull() ?: fallbackSeason,
        directSource = get("direct_source").flexStringOrNull(),
        movieImage = info?.get("movie_image").flexStringOrNull(),
        plot = info?.get("plot").flexStringOrNull(),
        durationSecs = info?.get("duration_secs").flexIntOrNull(),
        rating = info?.get("rating").flexDoubleOrNull(),
    )
}

private fun JsonElement.flexRows(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> {
        val nestedArray = listOf("data", "results", "items", "streams", "series", "movies", "vod")
            .firstNotNullOfOrNull { key -> get(key) as? JsonArray }
        nestedArray?.mapNotNull { it as? JsonObject }
            ?: values.mapNotNull { it as? JsonObject }
    }
    else -> emptyList()
}

private fun JsonElement?.flexStringOrNull(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }
    is JsonArray -> firstOrNull().flexStringOrNull()
    else -> toString().takeIf { it.isNotBlank() && it != "null" }
}

private fun JsonElement?.flexIntOrNull(): Int? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> intOrNull ?: contentOrNull?.toDoubleOrNull()?.toInt()
    is JsonArray -> firstOrNull().flexIntOrNull()
    else -> toString().toDoubleOrNull()?.toInt()
}

private fun JsonElement?.flexDoubleOrNull(): Double? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> contentOrNull?.toDoubleOrNull()
    is JsonArray -> firstOrNull().flexDoubleOrNull()
    else -> toString().toDoubleOrNull()
}

private fun JsonElement?.flexStringList(): List<String> = when (this) {
    null, is JsonNull -> emptyList()
    is JsonArray -> mapNotNull { it.flexStringOrNull() }.filter { it.isNotBlank() }
    else -> flexStringOrNull()?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
}
