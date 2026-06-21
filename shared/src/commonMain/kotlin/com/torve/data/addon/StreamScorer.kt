package com.torve.data.addon

import com.torve.domain.model.AutoSourceMode
import com.torve.domain.model.CodecPreference
import com.torve.domain.model.HdrMode
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import kotlin.math.absoluteValue

/**
 * Scores streams by "best playable" quality for TV:
 * compatibility + stability + throughput + quality.
 *
 * Resolution is intentionally a secondary factor to avoid selecting
 * unstable high-bitrate sources when a smooth 1080p option exists.
 */
class StreamScorer {

    fun score(stream: ParsedStream, preferences: StreamPreferences): Int {
        val compatibilityScore = scoreCompatibility(stream, preferences)
        val stabilityScore = scoreStability(stream)
        val throughputScore = scoreThroughputSuitability(stream, preferences)
        val containerScore = scoreContainerCompatibility(stream, preferences)
        val qualityScore = scoreQuality(stream, preferences)
        val hdrScore = scoreHdr(stream, preferences)
        val audioScore = scoreAudio(stream)
        val penalties = scoreRiskPenalties(stream, preferences)

        val total = compatibilityScore +
            stabilityScore +
            throughputScore +
            containerScore +
            qualityScore +
            hdrScore +
            audioScore -
            penalties

        return total.coerceIn(0, 100)
    }

    fun scoreAll(streams: List<ParsedStream>, preferences: StreamPreferences): List<ParsedStream> {
        return streams
            .map { it.copy(score = score(it, preferences)) }
            .sortedByDescending { it.score }
    }

    // --- Compatibility (0..28) ---

    private fun scoreCompatibility(stream: ParsedStream, preferences: StreamPreferences): Int {
        val codec = stream.codec?.uppercase().orEmpty()
        val mode = preferences.autoSourceMode

        val codecBase = when (preferences.codecPreference) {
            CodecPreference.H264_ONLY -> when {
                codec.contains("H.264") || codec.contains("H264") || codec.contains("X264") || codec.contains("AVC") -> 18
                codec.isBlank() -> 12
                else -> 2
            }
            CodecPreference.HEVC_PREFERRED -> when {
                codec.contains("HEVC") || codec.contains("H.265") || codec.contains("X265") -> 17
                codec.contains("H.264") || codec.contains("H264") || codec.contains("X264") || codec.contains("AVC") -> 14
                codec.contains("AV1") -> 10
                codec.isBlank() -> 11
                else -> 7
            }
            CodecPreference.ANY -> when {
                codec.contains("H.264") || codec.contains("H264") || codec.contains("X264") || codec.contains("AVC") -> 16
                codec.contains("HEVC") || codec.contains("H.265") || codec.contains("X265") -> 13
                codec.contains("AV1") -> 9
                codec.isBlank() -> 11
                else -> 8
            }
        }

        val compatibilityBias = when {
            !preferences.preferCompatibleCodecs -> 0
            codec.contains("H.264") || codec.contains("H264") || codec.contains("X264") || codec.contains("AVC") -> 8
            codec.isBlank() -> 4
            codec.contains("HEVC") || codec.contains("H.265") || codec.contains("X265") ->
                if (mode == AutoSourceMode.QUALITY_FIRST && preferences.allow4kAuto) 4 else 0
            codec.contains("AV1") -> -2
            else -> -1
        }
        return (codecBase + compatibilityBias).coerceIn(0, 28)
    }

    // --- Stability (0..28) ---

    private fun scoreStability(stream: ParsedStream): Int {
        var score = 0

        if (stream.isCached) score += 12
        if (stream.isAddonHostedUrl()) score += 16
        if (stream.directUrl != null) score += 12
        if (stream.recentSuccessCount > 0) {
            score += minOf(8, 3 + (stream.recentSuccessCount * 2))
        }
        stream.lastSuccessfulResolveAt?.let { lastSuccessAt ->
            val ageMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - lastSuccessAt
            score += when {
                ageMs <= 6 * 60 * 60 * 1000L -> 5
                ageMs <= 24 * 60 * 60 * 1000L -> 4
                ageMs <= 3 * 24 * 60 * 60 * 1000L -> 3
                ageMs <= 7 * 24 * 60 * 60 * 1000L -> 2
                else -> 1
            }
        }

        score += when (val seeds = stream.seeds ?: -1) {
            in 120..Int.MAX_VALUE -> 8
            in 60..119 -> 6
            in 20..59 -> 4
            in 5..19 -> 2
            in 1..4 -> 1
            0 -> -1
            else -> 1
        }

        val hostKey = StreamRuntimeTelemetry.keyForStream(stream)
        score += StreamRuntimeTelemetry.reliabilityAdjustment(hostKey)
        return score.coerceIn(0, 28)
    }

    // --- Throughput suitability (0..16) ---

    private fun scoreThroughputSuitability(stream: ParsedStream, preferences: StreamPreferences): Int {
        val quality = StreamQuality.fromString(stream.quality)
        val sizeBytes = stream.size?.let(::parseSizeToBytes)
        val mode = preferences.autoSourceMode

        var score = when (quality) {
            StreamQuality.REMUX_4K -> if (preferences.allow4kAuto) 5 else 0
            StreamQuality.UHD_4K -> if (preferences.allow4kAuto) 7 else 1
            StreamQuality.FHD_1080P -> 14
            StreamQuality.HD_720P -> 15
            StreamQuality.SD_480P -> 11
            StreamQuality.UNKNOWN -> 10
        }

        if (mode == AutoSourceMode.QUALITY_FIRST && preferences.allow4kAuto) {
            score += when (quality) {
                StreamQuality.REMUX_4K, StreamQuality.UHD_4K -> 4
                StreamQuality.FHD_1080P -> 2
                else -> 0
            }
        }

        if (sizeBytes != null) {
            val gb = sizeBytes / (1024.0 * 1024.0 * 1024.0)
            score += when {
                gb > 55 -> -8
                gb > 35 -> -6
                gb > 22 -> -4
                gb > 12 -> -2
                gb < 1.2 -> -2
                else -> 2
            }
        }
        return score.coerceIn(0, 16)
    }

    // --- Container compatibility (0..8) ---

    private fun scoreContainerCompatibility(stream: ParsedStream, preferences: StreamPreferences): Int {
        val container = inferContainer(stream)
        return when (container) {
            "m3u8" -> 8
            "mp4" -> 7
            "ts" -> 6
            "webm" -> 5
            "mkv" -> if (preferences.autoSourceMode == AutoSourceMode.QUALITY_FIRST) 4 else 2
            "" -> 4
            else -> 3
        }
    }

    // --- Quality preference (0..16) ---

    private fun scoreQuality(stream: ParsedStream, preferences: StreamPreferences): Int {
        val quality = StreamQuality.fromString(stream.quality)
        val mode = preferences.autoSourceMode
        val target = targetQualityForMode(preferences)

        if (quality == StreamQuality.UNKNOWN) return 8

        if (mode == AutoSourceMode.QUALITY_FIRST) {
            return when (quality) {
                StreamQuality.REMUX_4K -> if (preferences.allow4kAuto) 16 else 4
                StreamQuality.UHD_4K -> if (preferences.allow4kAuto) 16 else 6
                StreamQuality.FHD_1080P -> 12
                StreamQuality.HD_720P -> 9
                StreamQuality.SD_480P -> 6
                StreamQuality.UNKNOWN -> 8
            }
        }

        val diff = (quality.rank - target.rank).absoluteValue
        return when (diff) {
            0 -> 16
            1 -> 12
            2 -> 8
            else -> 5
        }
    }

    // --- HDR and Audio ---

    private fun scoreHdr(stream: ParsedStream, preferences: StreamPreferences): Int {
        val titleUpper = stream.title.uppercase()
        val hasHdr = stream.hdr != null || titleUpper.contains("HDR") ||
            titleUpper.contains("DOLBY VISION") || titleUpper.contains(" DV ")

        return when (preferences.hdrMode) {
            HdrMode.PREFER_HDR -> if (hasHdr) 8 else 3
            HdrMode.SDR_ONLY -> if (hasHdr) 1 else 7
            HdrMode.AUTO -> if (hasHdr) 5 else 6
        }
    }

    private fun scoreAudio(stream: ParsedStream): Int {
        return when (stream.audioCodec?.uppercase()) {
            "ATMOS" -> 6
            "TRUEHD" -> 5
            "DTS-HD MA" -> 5
            "DTS" -> 4
            "EAC3" -> 4
            "AAC" -> 3
            else -> 3
        }
    }

    // --- Risk penalties ---

    private fun scoreRiskPenalties(stream: ParsedStream, preferences: StreamPreferences): Int {
        val titleUpper = stream.title.uppercase()
        val quality = StreamQuality.fromString(stream.quality)
        val mode = preferences.autoSourceMode
        var penalty = 0

        if (!preferences.allow4kAuto && (quality == StreamQuality.UHD_4K || quality == StreamQuality.REMUX_4K)) {
            penalty += 14
        }

        if (mode != AutoSourceMode.QUALITY_FIRST) {
            if (titleUpper.contains("REMUX")) penalty += 7
            if (titleUpper.contains("DOLBY VISION") || titleUpper.contains(" DV ")) penalty += 6
            if (titleUpper.contains("HDR10+") || titleUpper.contains("HDR10")) penalty += 4
        }

        if (mode == AutoSourceMode.STABILITY_FIRST) {
            if (titleUpper.contains("HEVC") || titleUpper.contains("H265") || titleUpper.contains("X265")) {
                penalty += 3
            }
        }

        if (mode == AutoSourceMode.MAX_720P && quality.rank < StreamQuality.HD_720P.rank) {
            penalty += 9
        }

        return penalty
    }

    private fun targetQualityForMode(preferences: StreamPreferences): StreamQuality {
        return when (preferences.autoSourceMode) {
            AutoSourceMode.MAX_720P -> StreamQuality.HD_720P
            AutoSourceMode.MAX_1080P -> StreamQuality.FHD_1080P
            AutoSourceMode.STABILITY_FIRST -> StreamQuality.HD_720P
            AutoSourceMode.BALANCED -> StreamQuality.FHD_1080P
            AutoSourceMode.QUALITY_FIRST -> {
                if (preferences.allow4kAuto) StreamQuality.UHD_4K else StreamQuality.FHD_1080P
            }
        }
    }

    private fun inferContainer(stream: ParsedStream): String {
        val url = stream.directUrl.orEmpty().substringBefore('?').lowercase()
        val title = stream.title.lowercase()
        return when {
            url.endsWith(".m3u8") || title.contains("m3u8") || title.contains("hls") -> "m3u8"
            url.endsWith(".mp4") || title.contains(".mp4") -> "mp4"
            url.endsWith(".mkv") || title.contains(".mkv") -> "mkv"
            url.endsWith(".ts") || title.contains(".ts") -> "ts"
            url.endsWith(".webm") || title.contains(".webm") -> "webm"
            else -> ""
        }
    }

    private fun parseSizeToBytes(sizeStr: String): Long? {
        val text = sizeStr.trim().uppercase()
        val regex = Regex("""([\\d.]+)\\s*(TB|GB|MB|KB)""")
        val match = regex.find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2]
        return when (unit) {
            "TB" -> (value * 1024.0 * 1024.0 * 1024.0 * 1024.0).toLong()
            "GB" -> (value * 1024.0 * 1024.0 * 1024.0).toLong()
            "MB" -> (value * 1024.0 * 1024.0).toLong()
            "KB" -> (value * 1024.0).toLong()
            else -> null
        }
    }
}
