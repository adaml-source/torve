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

/**
 * Read-only adapter for IntroDB's community aggregate. Playback knows only the
 * [SegmentMarkerProvider] contract; provider failure therefore remains isolated.
 */
class IntroDbSegmentMarkerProvider(
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
        val imdbId = request.showImdbId?.trim()?.lowercase()
            ?.takeIf { IMDB_ID.matches(it) }
            ?: return emptyList()
        val season = request.seasonNumber?.takeIf { it in 0..MAX_SEASON } ?: return emptyList()
        val episode = request.episodeNumber?.takeIf { it in 1..MAX_EPISODE } ?: return emptyList()
        val currentRuntime = request.media.runtimeMs.takeIf { it > 0L } ?: return emptyList()

        val response = httpClient.get("${baseUrl.trimEnd('/')}/segments") {
            parameter("imdb_id", imdbId)
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
        val payload = runCatching { json.decodeFromString<IntroDbResponse>(bytes.decodeToString()) }.getOrNull()
            ?: return emptyList()
        if (payload.imdbId?.lowercase() != imdbId || payload.season != season || payload.episode != episode) {
            return emptyList()
        }

        // The legacy aggregate does not expose a separate reference runtime.
        // Its accepted outro end is the only usable release-runtime anchor.
        // The engine evaluates the delta and only permits manual interaction
        // for nearby variants; it never applies that delta to all timestamps.
        val remoteEnd = payload.outro?.endMs?.takeIf { it > 0L }
        val runtimeReference = remoteEnd ?: currentRuntime
        val runtimeReliable = remoteEnd != null

        return buildList {
            payload.intro?.toMarker(SegmentType.INTRO, runtimeReference, runtimeReliable, currentRuntime)?.let(::add)
            payload.recap?.toMarker(SegmentType.RECAP, runtimeReference, runtimeReliable, currentRuntime)?.let(::add)
            payload.outro?.toMarker(SegmentType.CREDITS, runtimeReference, runtimeReliable, currentRuntime)?.let(::add)
        }
    }

    private fun IntroDbSegment.toMarker(
        type: SegmentType,
        referenceRuntimeMs: Long,
        runtimeReliable: Boolean,
        currentRuntimeMs: Long,
    ): ProviderMarker? {
        val start = startMs ?: if (type in setOf(SegmentType.INTRO, SegmentType.RECAP)) 0L else return null
        val rawEnd = endMs ?: return null
        val end = if (
            type == SegmentType.CREDITS && rawEnd > currentRuntimeMs &&
            rawEnd - currentRuntimeMs <= MAX_RUNTIME_ANCHOR_VARIANCE_MS
        ) currentRuntimeMs else rawEnd
        if (start < 0L || start >= end || end > currentRuntimeMs + MAX_TIMESTAMP_TOLERANCE_MS) return null
        val apiConfidence = confidence?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: return null
        val count = submissionCount?.takeIf { it in 1..MAX_SUBMISSION_COUNT } ?: return null
        // A single accepted aggregate is enough for a manual prompt, but a
        // community marker can never reach the automatic threshold by itself.
        val normalizedConfidence = (
            BASE_CONFIDENCE + apiConfidence * CONFIDENCE_WEIGHT +
                count.coerceAtMost(CONFIDENCE_COUNT_CAP) * CONFIDENCE_PER_SUBMISSION
            ).coerceAtMost(MAX_PROVIDER_CONFIDENCE)
        return ProviderMarker(
            type = type,
            startMs = start,
            endMs = end,
            confidence = normalizedConfidence,
            providerId = PROVIDER_ID,
            providerVersion = PROVIDER_VERSION,
            referenceRuntimeMs = referenceRuntimeMs,
            referenceFingerprint = null,
            referenceRuntimeReliable = runtimeReliable,
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
        const val DEFAULT_BASE_URL = "https://api.introdb.app"
        const val PROVIDER_ID = "introdb-community"
        const val PROVIDER_VERSION = 1
        const val REQUEST_TIMEOUT_MS = 1_200L
        const val MAX_RESPONSE_BYTES = 64L * 1024L
        const val MAX_TIMESTAMP_TOLERANCE_MS = 1_500L
        const val MAX_RUNTIME_ANCHOR_VARIANCE_MS = 5_000L
        const val MAX_SEASON = 200
        const val MAX_EPISODE = 2_000
        const val MAX_SUBMISSION_COUNT = 1_000_000
        const val BASE_CONFIDENCE = 0.62
        const val CONFIDENCE_WEIGHT = 0.18
        const val CONFIDENCE_PER_SUBMISSION = 0.02
        const val CONFIDENCE_COUNT_CAP = 4
        const val MAX_PROVIDER_CONFIDENCE = 0.88
        val IMDB_ID = Regex("^tt[0-9]{5,12}$")
    }
}

@Serializable
private data class IntroDbResponse(
    @SerialName("imdb_id") val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val intro: IntroDbSegment? = null,
    val recap: IntroDbSegment? = null,
    val outro: IntroDbSegment? = null,
)

@Serializable
private data class IntroDbSegment(
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    val confidence: Double? = null,
    @SerialName("submission_count") val submissionCount: Int? = null,
)
