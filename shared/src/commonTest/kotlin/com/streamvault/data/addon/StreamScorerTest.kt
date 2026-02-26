package com.streamvault.data.addon

import com.streamvault.domain.model.CodecPreference
import com.streamvault.domain.model.HdrMode
import com.streamvault.domain.model.StreamPreferences
import com.streamvault.domain.model.StreamQuality
import kotlin.test.Test
import kotlin.test.assertTrue

class StreamScorerTest {

    private val scorer = StreamScorer()

    private fun stream(
        quality: String = "1080p",
        codec: String = "",
        isCached: Boolean = false,
        seeds: Int? = null,
        hdr: String? = null,
        audioCodec: String? = null,
    ) = ParsedStream(
        addonName = "TestAddon",
        quality = quality,
        title = "Test Stream",
        codec = codec,
        isCached = isCached,
        seeds = seeds,
        hdr = hdr,
        audioCodec = audioCodec,
    )

    @Test
    fun cached_hevc_1080p_scores_near_maximum() {
        val s = stream(quality = "1080p", codec = "HEVC", isCached = true, seeds = 100)
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val scored = scorer.score(s, prefs)
        assertTrue(scored >= 80, "Cached HEVC 1080p should score near 100, got $scored")
    }

    @Test
    fun uncached_h264_480p_scores_low() {
        val s = stream(quality = "480p", codec = "H.264", isCached = false, seeds = 0)
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val scored = scorer.score(s, prefs)
        assertTrue(scored <= 40, "Uncached 480p H.264 should score low, got $scored")
    }

    @Test
    fun cache_status_dominates_for_equal_quality() {
        val cached = stream(quality = "1080p", codec = "H.264", isCached = true)
        val uncached = stream(quality = "1080p", codec = "H.264", isCached = false)
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val cachedScore = scorer.score(cached, prefs)
        val uncachedScore = scorer.score(uncached, prefs)
        assertTrue(
            cachedScore > uncachedScore,
            "Cached ($cachedScore) should beat uncached ($uncachedScore)",
        )
    }

    @Test
    fun hdr_prefer_mode_boosts_hdr_streams() {
        val hdrStream = stream(quality = "1080p", hdr = "HDR10")
        val sdrStream = stream(quality = "1080p", hdr = null)
        val prefs = StreamPreferences(
            maxQuality = StreamQuality.FHD_1080P,
            hdrMode = HdrMode.PREFER_HDR,
        )
        val hdrScore = scorer.score(hdrStream, prefs)
        val sdrScore = scorer.score(sdrStream, prefs)
        assertTrue(
            hdrScore > sdrScore,
            "HDR stream ($hdrScore) should beat SDR ($sdrScore) in PREFER_HDR mode",
        )
    }

    @Test
    fun sdr_only_mode_penalizes_hdr() {
        val hdrStream = stream(quality = "1080p", hdr = "HDR10")
        val sdrStream = stream(quality = "1080p", hdr = null)
        val prefs = StreamPreferences(
            maxQuality = StreamQuality.FHD_1080P,
            hdrMode = HdrMode.SDR_ONLY,
        )
        val hdrScore = scorer.score(hdrStream, prefs)
        val sdrScore = scorer.score(sdrStream, prefs)
        assertTrue(
            sdrScore >= hdrScore,
            "SDR stream ($sdrScore) should beat or tie HDR ($hdrScore) in SDR_ONLY mode",
        )
    }

    @Test
    fun hevc_preferred_scores_hevc_higher() {
        val hevc = stream(quality = "1080p", codec = "HEVC")
        val h264 = stream(quality = "1080p", codec = "H.264")
        val prefs = StreamPreferences(
            maxQuality = StreamQuality.FHD_1080P,
            codecPreference = CodecPreference.HEVC_PREFERRED,
        )
        val hevcScore = scorer.score(hevc, prefs)
        val h264Score = scorer.score(h264, prefs)
        assertTrue(
            hevcScore > h264Score,
            "HEVC ($hevcScore) should beat H.264 ($h264Score) when HEVC_PREFERRED",
        )
    }

    @Test
    fun audio_codec_hierarchy() {
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val atmos = scorer.score(stream(audioCodec = "Atmos"), prefs)
        val truehd = scorer.score(stream(audioCodec = "TrueHD"), prefs)
        val dtsHdMa = scorer.score(stream(audioCodec = "DTS-HD MA"), prefs)
        val dts = scorer.score(stream(audioCodec = "DTS"), prefs)
        val eac3 = scorer.score(stream(audioCodec = "EAC3"), prefs)
        val aac = scorer.score(stream(audioCodec = "AAC"), prefs)

        assertTrue(atmos >= truehd, "Atmos ($atmos) >= TrueHD ($truehd)")
        assertTrue(truehd >= dtsHdMa, "TrueHD ($truehd) >= DTS-HD MA ($dtsHdMa)")
        assertTrue(dtsHdMa >= dts, "DTS-HD MA ($dtsHdMa) >= DTS ($dts)")
        assertTrue(dts >= eac3, "DTS ($dts) >= EAC3 ($eac3)")
        assertTrue(eac3 >= aac, "EAC3 ($eac3) >= AAC ($aac)")
    }

    @Test
    fun scoreAll_returns_sorted_descending() {
        val streams = listOf(
            stream(quality = "480p", seeds = 0),
            stream(quality = "1080p", isCached = true, seeds = 50),
            stream(quality = "720p", seeds = 20),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val scored = scorer.scoreAll(streams, prefs)
        for (i in 0 until scored.size - 1) {
            assertTrue(
                scored[i].score >= scored[i + 1].score,
                "Scores should be descending: ${scored[i].score} >= ${scored[i + 1].score}",
            )
        }
    }
}
