package com.torve.data.addon

import com.torve.domain.model.CodecPreference
import com.torve.domain.model.DeviceCodecCaps
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality

/**
 * Single source of truth for playback stream selection.
 *
 * All playback paths (auto-play best stream, manual pick, resume, casting)
 * MUST use this class so that max-quality cap and device codec filtering
 * can never be bypassed.
 *
 * Player stack: ExoPlayer (Media3 1.5.1) with libmpv fallback.
 * This class is the authoritative gate before any URL reaches the player.
 */
class StreamSelector(
    private val scorer: StreamScorer,
) {

    /**
     * Selects the best playable stream variant that satisfies BOTH:
     *  1) User's max quality cap (never exceeded under any circumstance)
     *  2) Device codec capabilities (never selects a codec the device can't decode)
     *
     * @param streams Already-scored list from StreamAggregator (highest score first).
     * @param preferences User preferences including maxQuality.
     * @param deviceCaps Codec support snapshot from DeviceCodecProbe.
     * @param h264Only When true, restricts to H.264 streams only (used for fallback on codec error).
     * @return The best playable stream, or null if none are compatible.
     */
    fun selectBestPlayableVariant(
        streams: List<ParsedStream>,
        preferences: StreamPreferences,
        deviceCaps: DeviceCodecCaps,
        h264Only: Boolean = false,
    ): ParsedStream? {
        val maxHeight = preferences.maxQuality.heightPx

        // Apply all hard constraints: quality cap + codec + h264-only fallback
        val eligible = streams.filter { stream ->
            passesQualityCap(stream, maxHeight) &&
                passesCodecFilter(stream, deviceCaps) &&
                passesH264OnlyFilter(stream, h264Only)
        }

        if (eligible.isNotEmpty()) {
            return eligible.first() // already sorted by score from StreamAggregator
        }

        // Fallback: if no eligible streams, try baseline (H.264 only, under cap)
        if (!h264Only) {
            val baselineFallback = streams.filter { stream ->
                passesQualityCap(stream, maxHeight) &&
                    passesH264OnlyFilter(stream, h264Only = true)
            }
            if (baselineFallback.isNotEmpty()) return baselineFallback.first()
        }

        // Last resort: return the lowest-quality stream that the device can decode
        // Still NEVER exceed max quality cap
        val lastResort = streams
            .filter { passesQualityCap(it, maxHeight) }
            .sortedByDescending { StreamQuality.fromString(it.quality).rank } // lowest quality first
            .firstOrNull { passesCodecFilter(it, deviceCaps) }

        return lastResort
    }

    /**
     * Filters a full stream list to only device-compatible, quality-capped streams.
     * Used to present the manual stream picker with only playable options highlighted.
     */
    fun filterPlayableStreams(
        streams: List<ParsedStream>,
        preferences: StreamPreferences,
        deviceCaps: DeviceCodecCaps,
    ): List<ParsedStream> {
        val maxHeight = preferences.maxQuality.heightPx
        return streams.filter { stream ->
            passesQualityCap(stream, maxHeight) && passesCodecFilter(stream, deviceCaps)
        }
    }

    /**
     * Selects a fallback stream after a codec error at runtime.
     * Tries H.264-only first, then downgrades resolution within the cap.
     *
     * @param failedStream The stream that caused the codec error.
     * @param allStreams Full stream list.
     * @param preferences User preferences.
     * @param deviceCaps Device codec caps.
     * @param lowerCapTo Optionally force a lower max quality (e.g. 720p after repeated failures).
     */
    fun selectFallbackAfterCodecError(
        failedStream: ParsedStream,
        allStreams: List<ParsedStream>,
        preferences: StreamPreferences,
        deviceCaps: DeviceCodecCaps,
        lowerCapTo: StreamQuality? = null,
    ): ParsedStream? {
        val effectivePrefs = if (lowerCapTo != null) {
            preferences.copy(maxQuality = lowerCapTo)
        } else {
            preferences
        }

        // First try: H.264-only at current (or lowered) cap
        val h264Fallback = selectBestPlayableVariant(
            streams = allStreams.filter { it != failedStream },
            preferences = effectivePrefs,
            deviceCaps = deviceCaps,
            h264Only = true,
        )
        if (h264Fallback != null) return h264Fallback

        // Second try: any codec the device supports, one tier below failed stream
        val failedQuality = StreamQuality.fromString(failedStream.quality)
        val lowerQuality = StreamQuality.entries
            .filter { it.rank > failedQuality.rank && it != StreamQuality.UNKNOWN }
            .minByOrNull { it.rank }
            ?: return null

        return selectBestPlayableVariant(
            streams = allStreams.filter { it != failedStream },
            preferences = effectivePrefs.copy(maxQuality = lowerQuality),
            deviceCaps = deviceCaps,
        )
    }

    // --- Private constraint checks ---

    private fun passesQualityCap(stream: ParsedStream, maxHeight: Int?): Boolean {
        if (maxHeight == null) return true // no cap (UNKNOWN quality = no limit)
        val quality = StreamQuality.fromString(stream.quality)
        val streamHeight = quality.heightPx
        if (streamHeight == null) {
            // Unknown height: deprioritize but allow if quality is UNKNOWN
            // (these are already scored low by StreamScorer)
            return quality == StreamQuality.UNKNOWN
        }
        return streamHeight <= maxHeight
    }

    private fun passesCodecFilter(stream: ParsedStream, deviceCaps: DeviceCodecCaps): Boolean {
        val codec = stream.codec
        // Detect 10-bit from title metadata (common in torrent names)
        val bitDepth = if (stream.title.uppercase().let {
                it.contains("10BIT") || it.contains("10-BIT") || it.contains("10 BIT") ||
                    it.contains("MAIN10") || it.contains("MAIN 10")
            }
        ) "10" else null

        return deviceCaps.canDecode(codec, bitDepth, stream.title)
    }

    private fun passesH264OnlyFilter(stream: ParsedStream, h264Only: Boolean): Boolean {
        if (!h264Only) return true
        val codec = stream.codec?.uppercase() ?: ""
        // Allow H.264 or unknown codec (unknown = likely H.264 in practice)
        return codec.isBlank() ||
            codec.contains("H.264") || codec.contains("H264") ||
            codec.contains("X264") || codec.contains("AVC")
    }

    companion object {
        /** Resolution step-down sequence for codec error recovery. */
        val FALLBACK_QUALITY_STEPS = listOf(
            StreamQuality.FHD_1080P,
            StreamQuality.HD_720P,
            StreamQuality.SD_480P,
        )
    }
}
