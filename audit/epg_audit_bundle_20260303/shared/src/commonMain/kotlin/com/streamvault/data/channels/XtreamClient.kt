package com.streamvault.data.channels

import com.streamvault.domain.model.Channel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Xtream Codes API client.
 * Uses the player_api.php endpoint to fetch live, VOD, and series content.
 */
class XtreamClient(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    /**
     * Authenticate and get server info.
     */
    suspend fun authenticate(server: String, username: String, password: String): XtreamAuthInfo {
        val response: XtreamAuthResponse = httpClient.get("${server.trimEnd('/')}/player_api.php") {
            parameter("username", username)
            parameter("password", password)
        }.body()
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
        return httpClient.get("${server.trimEnd('/')}/player_api.php") {
            parameter("username", username)
            parameter("password", password)
            parameter("action", "get_live_categories")
        }.body()
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
        return httpClient.get("${server.trimEnd('/')}/player_api.php") {
            parameter("username", username)
            parameter("password", password)
            parameter("action", "get_live_streams")
            categoryId?.let { parameter("category_id", it) }
        }.body()
    }

    /**
     * Fetch VOD categories.
     */
    suspend fun getVodCategories(
        server: String,
        username: String,
        password: String,
    ): List<XtreamCategory> {
        return httpClient.get("${server.trimEnd('/')}/player_api.php") {
            parameter("username", username)
            parameter("password", password)
            parameter("action", "get_vod_categories")
        }.body()
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
        return httpClient.get("${server.trimEnd('/')}/player_api.php") {
            parameter("username", username)
            parameter("password", password)
            parameter("action", "get_vod_streams")
            categoryId?.let { parameter("category_id", it) }
        }.body()
    }

    /**
     * Fetch series categories.
     */
    suspend fun getSeriesCategories(
        server: String,
        username: String,
        password: String,
    ): List<XtreamCategory> {
        return httpClient.get("${server.trimEnd('/')}/player_api.php") {
            parameter("username", username)
            parameter("password", password)
            parameter("action", "get_series_categories")
        }.body()
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
        return httpClient.get("${server.trimEnd('/')}/player_api.php") {
            parameter("username", username)
            parameter("password", password)
            parameter("action", "get_series")
            categoryId?.let { parameter("category_id", it) }
        }.body()
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
            Channel(
                name = stream.name ?: "Unknown",
                url = streamUrl,
                tvgId = stream.epgChannelId,
                tvgName = stream.name,
                tvgLogo = stream.streamIcon,
                groupTitle = categoryName,
                channelNumber = stream.num,
                playlistId = playlistId,
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
            Channel(
                name = stream.name ?: "Unknown",
                url = streamUrl,
                tvgLogo = stream.streamIcon,
                groupTitle = categoryName?.let { "VOD: $it" } ?: "VOD",
                playlistId = playlistId,
            )
        }
    }
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
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("category_name") val categoryName: String = "",
    @SerialName("parent_id") val parentId: Int = 0,
)

@Serializable
data class XtreamLiveStream(
    val num: Int? = null,
    val name: String? = null,
    @SerialName("stream_type") val streamType: String? = null,
    @SerialName("stream_id") val streamId: Int = 0,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("tv_archive") val tvArchive: Int? = null,
    @SerialName("tv_archive_duration") val tvArchiveDuration: Int? = null,
)

@Serializable
data class XtreamVodStream(
    val num: Int? = null,
    val name: String? = null,
    @SerialName("stream_type") val streamType: String? = null,
    @SerialName("stream_id") val streamId: Int = 0,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    val rating: String? = null,
)

@Serializable
data class XtreamSeries(
    val num: Int? = null,
    val name: String? = null,
    @SerialName("series_id") val seriesId: Int = 0,
    val cover: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val rating: String? = null,
    @SerialName("last_modified") val lastModified: String? = null,
)
