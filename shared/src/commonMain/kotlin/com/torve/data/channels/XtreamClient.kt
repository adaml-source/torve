package com.torve.data.channels

import com.torve.domain.model.Channel
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
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
        return getJson(server, username, password, action = "get_live_categories")
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
        return getJson(
            server = server,
            username = username,
            password = password,
            action = "get_live_streams",
            extraParams = categoryId?.let { mapOf("category_id" to it) }.orEmpty(),
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
        return getJson(server, username, password, action = "get_vod_categories")
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
        return getJson(
            server = server,
            username = username,
            password = password,
            action = "get_vod_streams",
            extraParams = categoryId?.let { mapOf("category_id" to it) }.orEmpty(),
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
        return getJson(server, username, password, action = "get_series_categories")
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
        return getJson(
            server = server,
            username = username,
            password = password,
            action = "get_series",
            extraParams = categoryId?.let { mapOf("category_id" to it) }.orEmpty(),
        )
    }

    private suspend inline fun <reified T> getJson(
        server: String,
        username: String,
        password: String,
        action: String? = null,
        extraParams: Map<String, String> = emptyMap(),
    ): T {
        val raw = httpClient.get("${server.trimEnd('/')}/player_api.php") {
            parameter("username", username)
            parameter("password", password)
            action?.let { parameter("action", it) }
            extraParams.forEach { (key, value) -> parameter(key, value) }
        }.bodyAsText()
        return json.decodeFromString(raw)
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
