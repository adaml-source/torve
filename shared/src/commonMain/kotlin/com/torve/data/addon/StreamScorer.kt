package com.torve.data.addon

import com.torve.domain.model.CodecPreference
import com.torve.domain.model.HdrMode
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import kotlin.math.abs
import kotlin.math.min

/**
 * Scores streams 0–100 based on user preferences.
 *
 * Weight breakdown:
 *   Resolution match:  30
 *   Cache status:      25
 *   Codec preference:  15
 *   HDR match:         10
 *   Audio quality:     10
 *   Seed health:       10
 */
class StreamScorer {

    fun score(stream: ParsedStream, preferences: StreamPreferences): Int {
        val resolutionScore = scoreResolution(stream, preferences)
        val cacheScore = scoreCache(stream)
        val codecScore = scoreCodec(stream, preferences)
        val hdrScore = scoreHdr(stream, preferences)
        val audioScore = scoreAudio(stream)
        val seedScore = scoreSeeds(stream)
        return min(100, resolutionScore + cacheScore + codecScore + hdrScore + audioScore + seedScore)
    }

    fun scoreAll(streams: List<ParsedStream>, preferences: StreamPreferences): List<ParsedStream> {
        return streams
            .map { it.copy(score = score(it, preferences)) }
            .sortedByDescending { it.score }
    }

    // --- Individual scoring components ---

    /** 30 points max. Exact preferred quality match = 30, ±1 rank = 20, ±2 = 10 */
    private fun scoreResolution(stream: ParsedStream, preferences: StreamPreferences): Int {
        val streamQuality = StreamQuality.fromString(stream.quality)
        val preferred = preferences.preferredQuality
        if (streamQuality == StreamQuality.UNKNOWN) return 15 // neutral
        val rankDiff = abs(streamQuality.rank - preferred.rank)
        return when (rankDiff) {
            0 -> 30
            1 -> 20
            2 -> 10
            else -> 5
        }
    }

    /** 25 points max. Cached = 25, not cached = 0 */
    private fun scoreCache(stream: ParsedStream): Int {
        return if (stream.isCached) 25 else 0
    }

    /** 15 points max. Based on codec preference setting */
    private fun scoreCodec(stream: ParsedStream, preferences: StreamPreferences): Int {
        val codec = stream.codec?.uppercase() ?: ""
        return when (preferences.codecPreference) {
            CodecPreference.HEVC_PREFERRED -> when {
                codec.contains("HEVC") || codec.contains("H.265") || codec.contains("X265") -> 15
                codec.contains("AV1") -> 13
                codec.contains("H.264") || codec.contains("X264") || codec.contains("AVC") -> 8
                else -> 5
            }
            CodecPreference.H264_ONLY -> when {
                codec.contains("H.264") || codec.contains("X264") || codec.contains("AVC") -> 15
                else -> 3
            }
            CodecPreference.ANY -> 10
        }
    }

    /** 10 points max. Based on HDR mode preference */
    private fun scoreHdr(stream: ParsedStream, preferences: StreamPreferences): Int {
        val hasHdr = stream.hdr != null
        return when (preferences.hdrMode) {
            HdrMode.PREFER_HDR -> when {
                stream.hdr?.contains("DV") == true -> 10
                stream.hdr == "HDR10+" -> 9
                stream.hdr == "HDR10" -> 8
                stream.hdr == "HDR" -> 7
                else -> 3 // SDR when preferring HDR
            }
            HdrMode.SDR_ONLY -> if (hasHdr) 2 else 10
            HdrMode.AUTO -> if (hasHdr) 7 else 5
        }
    }

    /** 10 points max. Higher quality audio formats score higher */
    private fun scoreAudio(stream: ParsedStream): Int {
        return when (stream.audioCodec?.uppercase()) {
            "ATMOS" -> 10
            "TRUEHD" -> 8
            "DTS-HD MA" -> 7
            "DTS" -> 5
            "EAC3" -> 4
            "AAC" -> 2
            else -> 3 // unknown
        }
    }

    /** 10 points max. More seeds = better availability */
    private fun scoreSeeds(stream: ParsedStream): Int {
        // Direct URLs (non-torrent) get full seed score since availability isn't seed-dependent
        if (stream.infoHash == null && stream.directUrl != null) return 8
        val seeds = stream.seeds ?: return 3
        return when {
            seeds >= 100 -> 10
            seeds >= 50 -> 7
            seeds >= 10 -> 5
            seeds > 0 -> 2
            else -> 0
        }
    }
}
