package com.torve.data.playback

import com.torve.domain.player.PlaybackSegmentRequest
import com.torve.domain.player.ProviderMarker
import com.torve.domain.player.SegmentMarkerProvider
import com.torve.domain.player.SegmentType
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Read-only adapter for TheIntroDB v3's multi-segment community feed. */
class TheIntroDbSegmentMarkerProvider(
    private val httpClient: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    },
) : SegmentMarkerProvider {
    override val id: String = PROVIDER_ID

    override suspend fun getSegments(request: PlaybackSegmentRequest): List<ProviderMarker> {
        val tmdbId = request.showTmdbId?.takeIf { it in 1..MAX_TMDB_ID }
        val imdbId = request.showImdbId?.trim()?.lowercase()?.takeIf(IMDB_ID::matches)
        if (tmdbId == null && imdbId == null) return emptyList()
        val season = request.seasonNumber?.takeIf { it in 0..MAX_SEASON } ?: return emptyList()
        val episode = request.episodeNumber?.takeIf { it in 1..MAX_EPISODE } ?: return emptyList()
        val currentRuntime = request.media.runtimeMs.takeIf { it > 0L } ?: return emptyList()

        val response = httpClient.get("${baseUrl.trimEnd('/')}/media") {
            if (tmdbId != null) parameter("tmdb_id", tmdbId) else parameter("imdb_id", imdbId)
            parameter("type", "tv")
            parameter("season", season)
            parameter("episode", episode)
            timeout {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
        if (!response.status.isSuccess()) return emptyList()
        val declaredLength = response.contentLength()
        if (declaredLength != null && declaredLength > MAX_RESPONSE_BYTES) return emptyList()
        val bytes = readBounded(response.bodyAsChannel(), MAX_RESPONSE_BYTES.toInt())
        if (bytes.isEmpty()) return emptyList()
        val payload = runCatching { json.decodeFromString<TheIntroDbResponse>(bytes.decodeToString()) }.getOrNull()
            ?: return emptyList()
        if (payload.type != "tv" || payload.season != season || payload.episode != episode) return emptyList()
        if (tmdbId != null && payload.tmdbId != tmdbId) return emptyList()
        if (tmdbId == null && payload.imdbId?.lowercase() != imdbId) return emptyList()

        val referenceRuntime = payload.videoDurationMs?.takeIf { it > 0L }
        return buildList {
            payload.intro.take(MAX_SEGMENTS_PER_TYPE).mapNotNullTo(this) {
                it.toMarker(SegmentType.INTRO, referenceRuntime, currentRuntime)
            }
            payload.recap.take(MAX_SEGMENTS_PER_TYPE).mapNotNullTo(this) {
                it.toMarker(SegmentType.RECAP, referenceRuntime, currentRuntime)
            }
            payload.credits.take(MAX_SEGMENTS_PER_TYPE).mapNotNullTo(this) {
                it.toMarker(SegmentType.CREDITS, referenceRuntime, currentRuntime)
            }
            payload.preview.take(MAX_SEGMENTS_PER_TYPE).mapNotNullTo(this) {
                it.toMarker(SegmentType.NEXT_EPISODE_PREVIEW, referenceRuntime, currentRuntime)
            }
        }
    }

    private fun TheIntroDbSegment.toMarker(
        type: SegmentType,
        reliableRuntimeMs: Long?,
        currentRuntimeMs: Long,
    ): ProviderMarker? {
        val start = startMs ?: if (type in setOf(SegmentType.INTRO, SegmentType.RECAP)) 0L else return null
        val endsAtMediaEnd = endMs == null && type in setOf(
            SegmentType.CREDITS,
            SegmentType.FINAL_CREDITS,
            SegmentType.NEXT_EPISODE_PREVIEW,
        )
        val referenceRuntime = reliableRuntimeMs ?: currentRuntimeMs
        val end = endMs ?: if (endsAtMediaEnd) referenceRuntime else return null
        if (start < 0L || start >= end) return null
        if (reliableRuntimeMs != null) {
            if (end > reliableRuntimeMs + MAX_TIMESTAMP_TOLERANCE_MS) return null
        } else if (end > currentRuntimeMs + MAX_TIMESTAMP_TOLERANCE_MS) {
            return null
        }
        return ProviderMarker(
            type = type,
            startMs = start,
            endMs = end,
            confidence = PROVIDER_CONFIDENCE,
            providerId = PROVIDER_ID,
            providerVersion = PROVIDER_VERSION,
            referenceRuntimeMs = referenceRuntime,
            referenceRuntimeReliable = reliableRuntimeMs != null,
            endsAtMediaEnd = endsAtMediaEnd,
        )
    }

    private suspend fun readBounded(channel: ByteReadChannel, maximumBytes: Int): ByteArray {
        val chunk = ByteArray(4 * 1024)
        var result = ByteArray(minOf(maximumBytes, 8 * 1024))
        var total = 0
        while (true) {
            val remaining = maximumBytes + 1 - total
            if (remaining <= 0) return ByteArray(0)
            val read = channel.readAvailable(chunk, 0, minOf(chunk.size, remaining))
            if (read < 0) break
            if (read == 0) {
                yield()
                continue
            }
            val nextTotal = total + read
            if (nextTotal > maximumBytes) return ByteArray(0)
            if (nextTotal > result.size) {
                result = result.copyOf(minOf(maximumBytes, maxOf(result.size * 2, nextTotal)))
            }
            chunk.copyInto(result, destinationOffset = total, endIndex = read)
            total = nextTotal
        }
        return result.copyOf(total)
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.theintrodb.org/v3"
        const val PROVIDER_ID = "theintrodb-v3"
        const val PROVIDER_VERSION = 1
        const val REQUEST_TIMEOUT_MS = 1_200L
        const val MAX_RESPONSE_BYTES = 64L * 1024L
        const val MAX_TIMESTAMP_TOLERANCE_MS = 1_500L
        const val MAX_SEGMENTS_PER_TYPE = 16
        const val MAX_TMDB_ID = 10_000_000
        const val MAX_SEASON = 200
        const val MAX_EPISODE = 2_000
        const val PROVIDER_CONFIDENCE = 0.79
        val IMDB_ID = Regex("^tt[0-9]{5,12}$")
    }
}

@Serializable
private data class TheIntroDbResponse(
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val type: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("video_duration_ms") val videoDurationMs: Long? = null,
    val intro: List<TheIntroDbSegment> = emptyList(),
    val recap: List<TheIntroDbSegment> = emptyList(),
    val credits: List<TheIntroDbSegment> = emptyList(),
    val preview: List<TheIntroDbSegment> = emptyList(),
)

@Serializable
private data class TheIntroDbSegment(
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
)
