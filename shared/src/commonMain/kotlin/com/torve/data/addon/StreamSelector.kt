package com.torve.data.addon

import com.torve.domain.model.AutoSourceMode
import com.torve.domain.model.DeviceCodecCaps
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality

/**
 * Single source of truth for playback stream selection.
 *
 * Auto mode means "best playable" stream on TV:
 * compatibility + stability + throughput + quality.
 */
class StreamSelector(
    private val scorer: StreamScorer,
) {

    fun selectBestPlayableVariant(
        streams: List<ParsedStream>,
        preferences: StreamPreferences,
        deviceCaps: DeviceCodecCaps,
        h264Only: Boolean = false,
    ): ParsedStream? {
        return rankPlayableVariants(
            streams = streams,
            preferences = preferences,
            deviceCaps = deviceCaps,
            h264Only = h264Only,
        ).firstOrNull()
    }

    fun rankPlayableVariants(
        streams: List<ParsedStream>,
        preferences: StreamPreferences,
        deviceCaps: DeviceCodecCaps,
        h264Only: Boolean = false,
    ): List<ParsedStream> {
        if (streams.isEmpty()) return emptyList()

        val maxHeight = effectiveMaxHeight(preferences)
        val eligible = streams.filter { stream ->
            passesQualityCap(stream, maxHeight) &&
                passesCodecFilter(stream, deviceCaps) &&
                passesH264OnlyFilter(stream, h264Only)
        }

        if (eligible.isEmpty()) return emptyList()

        val stablePreferred = eligible.filterNot { stream ->
            val hostKey = StreamRuntimeTelemetry.keyForStream(stream)
            StreamRuntimeTelemetry.isHostUnstable(hostKey)
        }
        val pool = if (stablePreferred.isNotEmpty()) stablePreferred else eligible

        return pool
            .map { stream ->
                val base = scorer.score(stream, preferences)
                val qualityBias = modeQualityBias(stream, preferences)
                val hostAdj = StreamRuntimeTelemetry.reliabilityAdjustment(StreamRuntimeTelemetry.keyForStream(stream)) / 2
                val directPlayableBonus = when {
                    stream.isAddonHostedUrl() -> 12
                    stream.directUrl != null -> 14
                    else -> 0
                }
                stream.copy(score = (base + qualityBias + hostAdj + directPlayableBonus).coerceIn(0, 100))
            }
            .sortedByDescending { it.score }
    }

    /**
     * Filters to currently playable streams while preserving rank order.
     * Used by manual picker and auto fallback controller.
     */
    fun filterPlayableStreams(
        streams: List<ParsedStream>,
        preferences: StreamPreferences,
        deviceCaps: DeviceCodecCaps,
    ): List<ParsedStream> {
        return rankPlayableVariants(
            streams = streams,
            preferences = preferences,
            deviceCaps = deviceCaps,
        )
    }

    fun selectFallbackAfterCodecError(
        failedStream: ParsedStream,
        allStreams: List<ParsedStream>,
        preferences: StreamPreferences,
        deviceCaps: DeviceCodecCaps,
        lowerCapTo: StreamQuality? = null,
    ): ParsedStream? {
        val failedHost = StreamRuntimeTelemetry.keyForStream(failedStream)
        StreamRuntimeTelemetry.recordFatalError(failedHost)

        val failedKey = streamKey(failedStream)
        val remaining = allStreams.filter { streamKey(it) != failedKey }
        if (remaining.isEmpty()) return null

        val effectivePrefs = if (lowerCapTo != null) {
            preferences.copy(maxQuality = lowerCapTo)
        } else {
            preferences
        }

        // First: force H.264-safe variant.
        val h264Fallback = selectBestPlayableVariant(
            streams = remaining,
            preferences = effectivePrefs,
            deviceCaps = deviceCaps,
            h264Only = true,
        )
        if (h264Fallback != null) return h264Fallback

        // Second: lower quality one tier when available.
        val failedQuality = StreamQuality.fromString(failedStream.quality)
        val lowerQuality = StreamQuality.entries
            .filter { it.rank > failedQuality.rank && it != StreamQuality.UNKNOWN }
            .minByOrNull { it.rank }

        return selectBestPlayableVariant(
            streams = remaining,
            preferences = if (lowerQuality != null) {
                effectivePrefs.copy(maxQuality = lowerQuality)
            } else {
                effectivePrefs
            },
            deviceCaps = deviceCaps,
        )
    }

    private fun streamKey(stream: ParsedStream): String {
        return stream.directUrl
            ?: stream.magnetUrl
            ?: stream.infoHash
            ?: listOf(
                stream.addonName,
                stream.title,
                stream.quality,
                stream.codec.orEmpty(),
                stream.source.orEmpty(),
                stream.size.orEmpty(),
                stream.fileIdx?.toString().orEmpty(),
            ).joinToString(":")
    }

    private fun effectiveMaxHeight(preferences: StreamPreferences): Int? {
        val cappedByMode = when (preferences.autoSourceMode) {
            AutoSourceMode.MAX_720P -> StreamQuality.HD_720P
            AutoSourceMode.MAX_1080P -> StreamQuality.FHD_1080P
            AutoSourceMode.BALANCED,
            AutoSourceMode.STABILITY_FIRST,
            AutoSourceMode.QUALITY_FIRST
            -> if (preferences.allow4kAuto) preferences.maxQuality else {
                minQuality(preferences.maxQuality, StreamQuality.FHD_1080P)
            }
        }
        return cappedByMode.heightPx
    }

    private fun minQuality(a: StreamQuality, b: StreamQuality): StreamQuality {
        return if (a.rank > b.rank) a else b
    }

    private fun passesQualityCap(stream: ParsedStream, maxHeight: Int?): Boolean {
        if (maxHeight == null) return true
        val quality = StreamQuality.fromString(stream.quality)
        val streamHeight = quality.heightPx ?: return true
        return streamHeight <= maxHeight
    }

    private fun passesCodecFilter(stream: ParsedStream, deviceCaps: DeviceCodecCaps): Boolean {
        val titleUpper = stream.title.uppercase()
        val bitDepth = if (
            titleUpper.contains("10BIT") ||
            titleUpper.contains("10-BIT") ||
            titleUpper.contains("10 BIT") ||
            titleUpper.contains("MAIN10") ||
            titleUpper.contains("MAIN 10")
        ) {
            "10"
        } else {
            null
        }
        return deviceCaps.canDecode(stream.codec, bitDepth, stream.title)
    }

    private fun passesH264OnlyFilter(stream: ParsedStream, h264Only: Boolean): Boolean {
        if (!h264Only) return true
        val codec = stream.codec?.uppercase().orEmpty()
        return codec.isBlank() ||
            codec.contains("H.264") || codec.contains("H264") ||
            codec.contains("X264") || codec.contains("AVC")
    }

    private fun modeQualityBias(stream: ParsedStream, preferences: StreamPreferences): Int {
        val quality = StreamQuality.fromString(stream.quality)
        return when (preferences.autoSourceMode) {
            AutoSourceMode.STABILITY_FIRST -> when (quality) {
                StreamQuality.HD_720P -> 8
                StreamQuality.FHD_1080P -> 5
                StreamQuality.SD_480P -> 2
                StreamQuality.UHD_4K, StreamQuality.REMUX_4K -> -10
                StreamQuality.UNKNOWN -> 0
            }
            AutoSourceMode.BALANCED -> when (quality) {
                StreamQuality.FHD_1080P -> 6
                StreamQuality.HD_720P -> 4
                StreamQuality.SD_480P -> 1
                StreamQuality.UHD_4K, StreamQuality.REMUX_4K -> if (preferences.allow4kAuto) 1 else -8
                StreamQuality.UNKNOWN -> 0
            }
            AutoSourceMode.QUALITY_FIRST -> when (quality) {
                StreamQuality.REMUX_4K, StreamQuality.UHD_4K -> if (preferences.allow4kAuto) 8 else -4
                StreamQuality.FHD_1080P -> 5
                StreamQuality.HD_720P -> 2
                StreamQuality.SD_480P -> -2
                StreamQuality.UNKNOWN -> 0
            }
            AutoSourceMode.MAX_1080P -> when (quality) {
                StreamQuality.FHD_1080P -> 8
                StreamQuality.HD_720P -> 4
                StreamQuality.SD_480P -> 2
                StreamQuality.UHD_4K, StreamQuality.REMUX_4K -> -10
                StreamQuality.UNKNOWN -> 0
            }
            AutoSourceMode.MAX_720P -> when (quality) {
                StreamQuality.HD_720P -> 8
                StreamQuality.SD_480P -> 4
                StreamQuality.FHD_1080P, StreamQuality.UHD_4K, StreamQuality.REMUX_4K -> -12
                StreamQuality.UNKNOWN -> 0
            }
        }
    }

    companion object {
        /** Resolution step-down sequence for error recovery. */
        val FALLBACK_QUALITY_STEPS = listOf(
            StreamQuality.FHD_1080P,
            StreamQuality.HD_720P,
            StreamQuality.SD_480P,
        )
    }
}
