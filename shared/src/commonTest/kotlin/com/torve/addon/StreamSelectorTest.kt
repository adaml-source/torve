package com.torve.addon

import com.torve.data.addon.ParsedStream
import com.torve.data.addon.StreamScorer
import com.torve.data.addon.StreamSelector
import com.torve.domain.model.AutoSourceMode
import com.torve.domain.model.CodecPreference
import com.torve.domain.model.DeviceCodecCaps
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamSelectorTest {

    private val scorer = StreamScorer()
    private val selector = StreamSelector(scorer)

    private fun stream(
        quality: String,
        codec: String = "",
        title: String = "Test Stream",
        score: Int = 50,
        seeds: Int? = 20,
        isCached: Boolean = false,
    ) = ParsedStream(
        addonName = "TestAddon",
        quality = quality,
        title = title,
        codec = codec,
        score = score,
        seeds = seeds,
        isCached = isCached,
    )

    private val fullDevice = DeviceCodecCaps(
        supportsH264 = true,
        supportsHevc = true,
        supportsHevcMain10 = true,
        supportsVp9 = true,
        supportsAv1 = true,
    )

    private val h264OnlyDevice = DeviceCodecCaps(
        supportsH264 = true,
        supportsHevc = false,
        supportsHevcMain10 = false,
        supportsVp9 = false,
        supportsAv1 = false,
    )

    private val midDevice = DeviceCodecCaps(
        supportsH264 = true,
        supportsHevc = true,
        supportsHevcMain10 = false,
        supportsVp9 = true,
        supportsAv1 = false,
    )

    @Test
    fun maxQualityCap_neverSelectsAbove1080() {
        val streams = listOf(
            stream("4K", "HEVC", seeds = 80),
            stream("1080p", "H.264", seeds = 50),
            stream("720p", "H.264", seeds = 30),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, fullDevice)
        assertNotNull(result)
        val selectedQuality = StreamQuality.fromString(result.quality)
        assertTrue(
            (selectedQuality.heightPx ?: Int.MAX_VALUE) <= 1080,
            "Selected quality ${result.quality} must not exceed 1080p",
        )
    }

    @Test
    fun allStreamsAboveCap_returnsNull() {
        val streams = listOf(
            stream("4K", "HEVC"),
            stream("4K", "H.264"),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, fullDevice)
        assertNull(result)
    }

    @Test
    fun h264OnlyDevice_neverSelectsHevc() {
        val streams = listOf(
            stream("1080p", "HEVC", seeds = 90),
            stream("1080p", "H.264", seeds = 40),
            stream("720p", "H.264", seeds = 30),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, h264OnlyDevice)
        assertNotNull(result)
        assertTrue(result.codec?.contains("H.264") == true || result.codec?.contains("H264") == true)
    }

    @Test
    fun hevcMain10_rejectedOnDeviceWithoutMain10Support() {
        val streams = listOf(
            stream("1080p", "HEVC", title = "Movie.2024.1080p.10bit.HEVC", seeds = 80),
            stream("1080p", "H.264", seeds = 40),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectBestPlayableVariant(streams, prefs, midDevice)
        assertNotNull(result)
        assertTrue(result.codec?.contains("H.264") == true || result.codec?.contains("H264") == true)
    }

    @Test
    fun qualityFirst_canSelect4k_whenAllowed() {
        val streams = listOf(
            stream("4K", "HEVC", seeds = 30, isCached = true),
            stream("1080p", "H.264", seeds = 80, isCached = true),
        )
        val prefs = StreamPreferences(
            maxQuality = StreamQuality.REMUX_4K,
            autoSourceMode = AutoSourceMode.QUALITY_FIRST,
            allow4kAuto = true,
            preferCompatibleCodecs = false,
            codecPreference = CodecPreference.HEVC_PREFERRED,
        )
        val result = selector.selectBestPlayableVariant(streams, prefs, fullDevice)
        assertNotNull(result)
        assertEquals("4K", result.quality)
    }

    @Test
    fun stabilityFirst_avoidsFragile4k_whenStable1080Exists() {
        val streams = listOf(
            stream("4K", "HEVC", title = "Huge REMUX", seeds = 1),
            stream("1080p", "H.264", seeds = 60, isCached = true),
            stream("720p", "H.264", seeds = 50),
        )
        val prefs = StreamPreferences(
            maxQuality = StreamQuality.REMUX_4K,
            autoSourceMode = AutoSourceMode.STABILITY_FIRST,
            allow4kAuto = false,
        )
        val result = selector.selectBestPlayableVariant(streams, prefs, fullDevice)
        assertNotNull(result)
        assertTrue(result.quality == "1080p" || result.quality == "720p")
    }

    @Test
    fun fallbackAfterCodecError_selectsDifferentStream() {
        val failedStream = stream("1080p", "HEVC", seeds = 80)
        val streams = listOf(
            failedStream,
            stream("1080p", "H.264", seeds = 50),
            stream("720p", "H.264", seeds = 40),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val result = selector.selectFallbackAfterCodecError(failedStream, streams, prefs, fullDevice)
        assertNotNull(result)
        assertTrue(result != failedStream)
    }

    @Test
    fun filterPlayableStreams_removesIncompatible() {
        val streams = listOf(
            stream("4K", "HEVC", seeds = 80),
            stream("1080p", "AV1", seeds = 50),
            stream("1080p", "H.264", seeds = 40),
            stream("720p", "H.264", seeds = 40),
        )
        val prefs = StreamPreferences(maxQuality = StreamQuality.FHD_1080P)
        val filtered = selector.filterPlayableStreams(streams, prefs, h264OnlyDevice)
        assertEquals(2, filtered.size)
        filtered.forEach { s ->
            val q = StreamQuality.fromString(s.quality)
            assertNotNull(q.heightPx)
            assertTrue(q.heightPx!! <= 1080)
            assertTrue(s.codec?.contains("H.264") == true || s.codec?.contains("H264") == true)
        }
    }
}
