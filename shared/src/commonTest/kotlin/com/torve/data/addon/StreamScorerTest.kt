package com.torve.data.addon

import com.torve.domain.model.AutoSourceMode
import com.torve.domain.model.CodecPreference
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import kotlin.test.Test
import kotlin.test.assertTrue

class StreamScorerTest {

    private val scorer = StreamScorer()

    private fun stream(
        quality: String = "1080p",
        codec: String = "",
        isCached: Boolean = false,
        seeds: Int? = null,
        title: String = "Test Stream",
    ) = ParsedStream(
        addonName = "TestAddon",
        quality = quality,
        title = title,
        codec = codec,
        isCached = isCached,
        seeds = seeds,
    )

    @Test
    fun balanced_prefers_stable_1080_over_fragile_4k() {
        val stable1080 = stream(quality = "1080p", codec = "H.264", isCached = true, seeds = 80)
        val fragile4k = stream(quality = "4K", codec = "HEVC", isCached = false, seeds = 2, title = "Big REMUX")
        val prefs = StreamPreferences(
            maxQuality = StreamQuality.REMUX_4K,
            autoSourceMode = AutoSourceMode.BALANCED,
            allow4kAuto = false,
        )

        val stableScore = scorer.score(stable1080, prefs)
        val fragileScore = scorer.score(fragile4k, prefs)
        assertTrue(
            stableScore > fragileScore,
            "Stable 1080p ($stableScore) should beat fragile 4K ($fragileScore) in balanced mode",
        )
    }

    @Test
    fun cache_status_improves_score() {
        val cached = stream(quality = "1080p", codec = "H.264", isCached = true, seeds = 30)
        val uncached = stream(quality = "1080p", codec = "H.264", isCached = false, seeds = 30)
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)

        val cachedScore = scorer.score(cached, prefs)
        val uncachedScore = scorer.score(uncached, prefs)
        assertTrue(cachedScore > uncachedScore, "Cached ($cachedScore) should beat uncached ($uncachedScore)")
    }

    @Test
    fun quality_first_with_4k_enabled_prefers_4k() {
        val fourK = stream(quality = "4K", codec = "HEVC", isCached = true, seeds = 40)
        val fhd = stream(quality = "1080p", codec = "H.264", isCached = true, seeds = 40)
        val prefs = StreamPreferences(
            maxQuality = StreamQuality.REMUX_4K,
            autoSourceMode = AutoSourceMode.QUALITY_FIRST,
            allow4kAuto = true,
            preferCompatibleCodecs = false,
            codecPreference = CodecPreference.HEVC_PREFERRED,
        )

        val fourKScore = scorer.score(fourK, prefs)
        val fhdScore = scorer.score(fhd, prefs)
        assertTrue(fourKScore > fhdScore, "4K ($fourKScore) should beat 1080p ($fhdScore) in quality-first mode")
    }

    @Test
    fun max720_mode_penalizes_4k() {
        val fourK = stream(quality = "4K", codec = "HEVC", isCached = true, seeds = 80)
        val hd = stream(quality = "720p", codec = "H.264", isCached = false, seeds = 20)
        val prefs = StreamPreferences(
            maxQuality = StreamQuality.REMUX_4K,
            autoSourceMode = AutoSourceMode.MAX_720P,
            allow4kAuto = true,
        )

        val fourKScore = scorer.score(fourK, prefs)
        val hdScore = scorer.score(hd, prefs)
        assertTrue(hdScore > fourKScore, "720p ($hdScore) should beat 4K ($fourKScore) in MAX_720 mode")
    }

    @Test
    fun prefer_compatible_codecs_boosts_h264() {
        val hevc = stream(quality = "1080p", codec = "HEVC", isCached = true, seeds = 60)
        val h264 = stream(quality = "1080p", codec = "H.264", isCached = true, seeds = 60)
        val prefs = StreamPreferences(
            maxQuality = StreamQuality.FHD_1080P,
            autoSourceMode = AutoSourceMode.BALANCED,
            preferCompatibleCodecs = true,
            codecPreference = CodecPreference.ANY,
        )

        val hevcScore = scorer.score(hevc, prefs)
        val h264Score = scorer.score(h264, prefs)
        assertTrue(h264Score >= hevcScore, "H.264 ($h264Score) should be >= HEVC ($hevcScore) when compat is preferred")
    }

    @Test
    fun scoreAll_returns_sorted_descending() {
        val streams = listOf(
            stream(quality = "4K", codec = "HEVC", seeds = 2, title = "Large REMUX"),
            stream(quality = "1080p", codec = "H.264", isCached = true, seeds = 80),
            stream(quality = "720p", codec = "H.264", seeds = 30),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.REMUX_4K)
        val scored = scorer.scoreAll(streams, prefs)
        for (i in 0 until scored.size - 1) {
            assertTrue(
                scored[i].score >= scored[i + 1].score,
                "Scores should be descending: ${scored[i].score} >= ${scored[i + 1].score}",
            )
        }
    }
}
