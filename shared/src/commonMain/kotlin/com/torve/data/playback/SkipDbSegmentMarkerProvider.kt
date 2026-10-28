package com.torve.data.playback

import com.torve.domain.player.PlaybackSegmentRequest
import com.torve.domain.player.ProviderMarker
import com.torve.domain.player.ProviderTimelineMatch
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
import kotlin.math.abs

/** Read-only adapter for SkipDB's duration-aware community segment API. */
class SkipDbSegmentMarkerProvider(
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
        val imdbId = request.showImdbId?.trim()?.lowercase()?.takeIf(IMDB_ID::matches)
            ?: return emptyList()
        val season = request.seasonNumber?.takeIf { it in 0..MAX_SEASON } ?: return emptyList()
        val episode = request.episodeNumber?.takeIf { it in 1..MAX_EPISODE } ?: return emptyList()
        val currentRuntime = request.media.runtimeMs.takeIf { it > 0L } ?: return emptyList()

        val response = httpClient.get("${baseUrl.trimEnd('/')}/api/segments") {
            parameter("imdb_id", imdbId)
            parameter("season", season)
            parameter("episode", episode)
            parameter("duration", formatDurationSeconds(currentRuntime))
            parameter("adjust", "conservative")
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
        val payload = runCatching { json.decodeFromString<SkipDbResponse>(bytes.decodeToString()) }.getOrNull()
            ?: return emptyList()
        if (payload.imdbId?.lowercase() != imdbId || payload.season != season || payload.episode != episode) {
            return emptyList()
        }

        return buildList {
            payload.segments.intro?.toMarker(SegmentType.INTRO, currentRuntime)?.let(::add)
            payload.segments.recap?.toMarker(SegmentType.RECAP, currentRuntime)?.let(::add)
            payload.segments.outro?.toMarker(SegmentType.CREDITS, currentRuntime)?.let(::add)
            payload.segments.preview?.toMarker(SegmentType.NEXT_EPISODE_PREVIEW, currentRuntime)?.let(::add)
        }
    }

    private fun SkipDbSegment.toMarker(type: SegmentType, currentRuntimeMs: Long): ProviderMarker? {
        val start = startMs ?: return null
        val rawEnd = endMs ?: if (type == SegmentType.CREDITS) currentRuntimeMs else return null
        // SkipDB uses 0/0 to mean a confirmed absence. It is useful provider
        // knowledge, but it is not a timeline segment and must not reach fusion.
        if (start == 0L && rawEnd == 0L) return null
        if (start < 0L || rawEnd <= start) return null
        val end = if (rawEnd > currentRuntimeMs && rawEnd - currentRuntimeMs <= TIMESTAMP_TOLERANCE_MS) {
            currentRuntimeMs
        } else {
            rawEnd
        }
        if (end > currentRuntimeMs) return null
        val maximumDuration = when (type) {
            SegmentType.INTRO, SegmentType.RECAP -> MAX_OPENING_SEGMENT_MS
            else -> MAX_END_SEGMENT_MS
        }
        if (end - start !in MIN_SEGMENT_MS..maximumDuration) return null

        val rawConfidence = confidence?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
        val providerMatch = timelineMatch() ?: return null
        val isAdjusted = adjusted ?: return null
        val offset = offsetMs ?: return null
        when (providerMatch) {
            ProviderTimelineMatch.EXACT_RUNTIME -> if (isAdjusted || offset != 0L) return null
            ProviderTimelineMatch.CONSERVATIVE_RUNTIME_MATCH -> {
                if (offset == 0L || abs(offset) > MAX_PROVIDER_SHIFT_MS) return null
                // Conservative mode may shift an earlier/shorter current stream,
                // but deliberately leaves a longer stream unchanged. Both are
                // reported by SkipDB as a nearby "shifted" runtime match.
                if (isAdjusted && offset >= 0L) return null
            }
            ProviderTimelineMatch.AGNOSTIC -> if (isAdjusted || offset != 0L) return null
            ProviderTimelineMatch.OUT_OF_RANGE -> {
                if (isAdjusted || offset !in -MAX_DURATION_OFFSET_MS..MAX_DURATION_OFFSET_MS) return null
            }
            ProviderTimelineMatch.UNSPECIFIED -> return null
        }

        return ProviderMarker(
            type = type,
            startMs = start,
            endMs = end,
            confidence = rawConfidence,
            providerId = PROVIDER_ID,
            providerVersion = PROVIDER_VERSION,
            // SkipDB returned timestamps on the requested duration timeline.
            // timelineMatch preserves how it reached that result so the engine
            // does not mistake a duration match for an exact media fingerprint.
            referenceRuntimeMs = currentRuntimeMs,
            referenceRuntimeReliable = providerMatch in setOf(
                ProviderTimelineMatch.EXACT_RUNTIME,
                ProviderTimelineMatch.CONSERVATIVE_RUNTIME_MATCH,
            ),
            endsAtMediaEnd = type == SegmentType.CREDITS && currentRuntimeMs - end <= EOF_SNAP_TOLERANCE_MS,
            timelineMatch = providerMatch,
        )
    }

    private fun SkipDbSegment.timelineMatch(): ProviderTimelineMatch? = when (match?.lowercase()) {
        "exact" -> ProviderTimelineMatch.EXACT_RUNTIME
        "shifted" -> ProviderTimelineMatch.CONSERVATIVE_RUNTIME_MATCH
        "agnostic" -> ProviderTimelineMatch.AGNOSTIC
        "out-of-range" -> ProviderTimelineMatch.OUT_OF_RANGE
        else -> null
    }

    private fun formatDurationSeconds(runtimeMs: Long): String =
        if (runtimeMs % 1_000L == 0L) {
            (runtimeMs / 1_000L).toString()
        } else {
            "${runtimeMs / 1_000L}.${(runtimeMs % 1_000L).toString().padStart(3, '0')}"
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
        const val DEFAULT_BASE_URL = "https://api.skipdb.tv"
        const val PROVIDER_ID = "skipdb-v1"
        const val PROVIDER_VERSION = 1
        const val REQUEST_TIMEOUT_MS = 1_200L
        const val MAX_RESPONSE_BYTES = 64L * 1024L
        const val TIMESTAMP_TOLERANCE_MS = 1_500L
        const val EOF_SNAP_TOLERANCE_MS = 10_000L
        const val MIN_SEGMENT_MS = 5_000L
        const val MAX_OPENING_SEGMENT_MS = 5L * 60_000L
        const val MAX_END_SEGMENT_MS = 15L * 60_000L
        const val MAX_PROVIDER_SHIFT_MS = 15_000L
        const val MAX_DURATION_OFFSET_MS = 24L * 60L * 60_000L
        const val MAX_SEASON = 200
        const val MAX_EPISODE = 2_000
        val IMDB_ID = Regex("^tt[0-9]{5,12}$")
    }
}

@Serializable
private data class SkipDbResponse(
    @SerialName("imdb_id") val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val segments: SkipDbSegments = SkipDbSegments(),
)

@Serializable
private data class SkipDbSegments(
    val intro: SkipDbSegment? = null,
    val recap: SkipDbSegment? = null,
    val outro: SkipDbSegment? = null,
    val preview: SkipDbSegment? = null,
)

@Serializable
private data class SkipDbSegment(
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    val match: String? = null,
    val adjusted: Boolean? = null,
    @SerialName("offset_ms") val offsetMs: Long? = null,
    val confidence: Double? = null,
)
