package com.torve.addon

import com.torve.data.addon.ParsedStream
import com.torve.data.addon.StreamScorer
import com.torve.data.addon.StreamSelector
import com.torve.domain.model.CodecPreference
import com.torve.domain.model.DeviceCodecCaps
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for StreamSelector — the single source of truth for stream selection.
 * Validates max quality cap enforcement, codec capability filtering, and fallback behavior.
 */
class StreamSelectorTest {

    private val scorer = StreamScorer()
    private val selector = StreamSelector(scorer)

    // Helper to create a ParsedStream with minimal fields
    private fun stream(
        quality: String,
        codec: String = "",
        title: String = "Test Stream",
        score: Int = 50,
        hdr: String? = null,
    ) = ParsedStream(
        addonName = "TestAddon",
        quality = quality,
        title = title,
        codec = codec,
        score = score,
        hdr = hdr,
    )

    // Full device that supports everything
    private val fullDevice = DeviceCodecCaps(
        supportsH264 = true,
        supportsHevc = true,
        supportsHevcMain10 = true,
        supportsVp9 = true,
        supportsAv1 = true,
    )

    // Budget device: only H.264
    private val h264OnlyDevice = DeviceCodecCaps(
        supportsH264 = true,
        supportsHevc = false,
        supportsHevcMain10 = false,
        supportsVp9 = false,
        supportsAv1 = false,
    )

    // Mid-range: H.264 + HEVC (8-bit only) + VP9
    private val midDevice = DeviceCodecCaps(
        supportsH264 = true,
        supportsHevc = true,
        supportsHevcMain10 = false,
        supportsVp9 = true,
        supportsAv1 = false,
    )

    // --- MAX QUALITY CAP TESTS ---

    @Test
    fun maxQuality1080p_selectsExactly1080p() {
        val streams = listOf(
            stream("4K", "HEVC", score = 90),
            stream("4K", "H.264", score = 80),
            stream("1080p", "H.264", score = 70),
            stream("720p", "H.264", score = 60),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, fullDevice)
        assertNotNull(result)
        assertEquals("1080p", result.quality)
    }

    @Test
    fun maxQuality1080p_neverSelects4K() {
        val streams = listOf(
            stream("4K", "HEVC", score = 100),
            stream("4K", "H.264", score = 95),
            stream("1080p", "H.264", score = 50),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, fullDevice)
        assertNotNull(result)
        assertEquals("1080p", result.quality)
    }

    @Test
    fun allStreamsAboveCap_selectsNone() {
        val streams = listOf(
            stream("4K", "HEVC", score = 100),
            stream("4K", "H.264", score = 90),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, fullDevice)
        assertNull(result)
    }

    @Test
    fun mixedQualities_2160_1440_1080_720_withCap1080_selects1080() {
        val streams = listOf(
            stream("4K", "HEVC", score = 95),     // 2160p — blocked
            stream("1080p", "HEVC", score = 85),   // 1080p — allowed, best score
            stream("720p", "H.264", score = 70),    // 720p — allowed
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, fullDevice)
        assertNotNull(result)
        assertEquals("1080p", result.quality)
    }

    @Test
    fun unknownHeightDoesNotOverrideCap() {
        // Unknown quality streams should not bypass the quality cap
        val streams = listOf(
            stream("4K", "HEVC", score = 100),
            stream("Unknown", "H.264", title = "Some Stream", score = 80), // UNKNOWN quality
            stream("720p", "H.264", score = 60),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, fullDevice)
        assertNotNull(result)
        // Should pick 720p since it's under cap; "Unknown" maps to 1080p via fromString's else branch
        // Actually "Unknown" -> fromString -> else -> FHD_1080P which is exactly 1080p, so it passes
        // Both 720p and the "Unknown"(=1080p) are eligible. Unknown has higher score.
        // Let's verify the one selected
        val selectedQuality = StreamQuality.fromString(result.quality)
        assert(selectedQuality.rank >= StreamQuality.FHD_1080P.rank) {
            "Selected quality ${result.quality} should be at or below 1080p"
        }
    }

    // --- CODEC CAPABILITY TESTS ---

    @Test
    fun h264OnlyDevice_neverSelectsHevc() {
        val streams = listOf(
            stream("1080p", "HEVC", score = 90),
            stream("1080p", "H.264", score = 70),
            stream("720p", "H.264", score = 60),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, h264OnlyDevice)
        assertNotNull(result)
        assertEquals("H.264", result.codec)
    }

    @Test
    fun h264OnlyDevice_neverSelectsAv1() {
        val streams = listOf(
            stream("1080p", "AV1", score = 95),
            stream("1080p", "HEVC", score = 85),
            stream("720p", "H.264", score = 60),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, h264OnlyDevice)
        assertNotNull(result)
        assertEquals("H.264", result.codec)
        assertEquals("720p", result.quality)
    }

    @Test
    fun hevcMain10_rejectedOnDeviceWithoutMain10Support() {
        val streams = listOf(
            stream("1080p", "HEVC", title = "Movie.2024.1080p.10bit.HEVC", score = 90),
            stream("1080p", "H.264", score = 70),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, midDevice)
        assertNotNull(result)
        assertEquals("H.264", result.codec) // HEVC 10-bit rejected, falls back to H.264
    }

    @Test
    fun hevcMain10_allowedOnDeviceWithMain10Support() {
        val streams = listOf(
            stream("1080p", "HEVC", title = "Movie.2024.1080p.10bit.HEVC", score = 90),
            stream("1080p", "H.264", score = 70),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, fullDevice)
        assertNotNull(result)
        assertEquals("HEVC", result.codec) // Full device supports Main10
    }

    @Test
    fun deviceLacksAv1_neverSelectsAv1EvenIfBest() {
        val streams = listOf(
            stream("1080p", "AV1", score = 100),
            stream("1080p", "HEVC", score = 80),
            stream("1080p", "H.264", score = 60),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, midDevice)
        assertNotNull(result)
        assertEquals("HEVC", result.codec) // mid device has HEVC but not AV1
    }

    // --- COMBINED QUALITY + CODEC TESTS ---

    @Test
    fun qualityCapAndCodecFilterAppliedTogether() {
        val streams = listOf(
            stream("4K", "HEVC", score = 100),     // blocked by quality cap
            stream("1080p", "AV1", score = 90),     // blocked by codec (h264-only device)
            stream("1080p", "HEVC", score = 80),     // blocked by codec
            stream("720p", "H.264", score = 60),      // passes both!
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, h264OnlyDevice)
        assertNotNull(result)
        assertEquals("720p", result.quality)
        assertEquals("H.264", result.codec)
    }

    // --- H.264-ONLY FALLBACK ---

    @Test
    fun h264OnlyFallbackSelectsH264() {
        val streams = listOf(
            stream("1080p", "HEVC", score = 90),
            stream("1080p", "H.264", score = 70),
            stream("720p", "H.264", score = 60),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, fullDevice, h264Only = true)
        assertNotNull(result)
        assertEquals("H.264", result.codec)
    }

    // --- FALLBACK AFTER CODEC ERROR ---

    @Test
    fun fallbackAfterCodecError_selectsDifferentStream() {
        val failedStream = stream("1080p", "HEVC", score = 90)
        val streams = listOf(
            failedStream,
            stream("1080p", "H.264", score = 70),
            stream("720p", "H.264", score = 60),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectFallbackAfterCodecError(failedStream, streams, prefs, fullDevice)
        assertNotNull(result)
        assertEquals("H.264", result.codec) // H.264-only fallback first
    }

    @Test
    fun fallbackWithLowerCap() {
        val failedStream = stream("1080p", "H.264", score = 90)
        val streams = listOf(
            failedStream,
            stream("720p", "H.264", score = 60),
            stream("480p", "H.264", score = 40),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectFallbackAfterCodecError(
            failedStream, streams, prefs, fullDevice,
            lowerCapTo = StreamQuality.HD_720P,
        )
        assertNotNull(result)
        assertEquals("720p", result.quality)
    }

    // --- FILTER PLAYABLE STREAMS ---

    @Test
    fun filterPlayableStreams_removesIncompatible() {
        val streams = listOf(
            stream("4K", "HEVC", score = 100),
            stream("1080p", "AV1", score = 90),
            stream("1080p", "H.264", score = 70),
            stream("720p", "H.264", score = 60),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val filtered = selector.filterPlayableStreams(streams, prefs, h264OnlyDevice)
        assertEquals(2, filtered.size) // only the two H.264 streams under 1080p cap
        filtered.forEach { s ->
            val q = StreamQuality.fromString(s.quality)
            assertNotNull(q.heightPx)
            assert(q.heightPx!! <= 1080) { "Quality ${s.quality} exceeds 1080p cap" }
            assert(s.codec == "H.264") { "Codec ${s.codec} should be H.264 on h264-only device" }
        }
    }
}
