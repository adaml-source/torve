package com.torve.data.addon

import com.torve.domain.model.*
import kotlin.test.*

class PlaybackPresetFallbackTest {
    private val selector = StreamSelector(StreamScorer())
    private fun stream(quality: String, codec: String = "H.264", cached: Boolean = true) =
        ParsedStream(addonName = "Test", title = "$quality $codec", quality = quality, codec = codec, isCached = cached, seeds = 10,
            infoHash = "0123456789abcdef0123456789abcdef01234567")

    @Test fun askingBeforeRelaxingRulesDoesNotSilentlyChooseALowerQuality() {
        val prefs = StreamPreferences(minQuality = StreamQuality.FHD_1080P, sourceFallbackPolicy = SourceFallbackPolicy.ASK)
        assertTrue(selector.rankWithPresetFallback(listOf(stream("720p")), prefs, DeviceCodecCaps()).isEmpty())
    }
    @Test fun explicitBestAvailableCanRelaxTheMinimumQualityButPreservesTheMaximumAndDeviceDecoder() {
        val prefs = StreamPreferences(minQuality = StreamQuality.FHD_1080P, maxQuality = StreamQuality.FHD_1080P,
            sourceFallbackPolicy = SourceFallbackPolicy.BEST_AVAILABLE)
        val available = stream("720p")
        val chosen = selector.rankWithPresetFallback(listOf(stream("4K"), stream("720p", "HEVC"), available), prefs, DeviceCodecCaps())
        assertEquals(listOf(available.title), chosen.map { it.title })
    }
    @Test fun aFallbackCannotOverrideCachedOnlyOrCodecPreferences() {
        val prefs = StreamPreferences(minQuality = StreamQuality.FHD_1080P, cachedOnly = true,
            codecPreference = CodecPreference.H264_ONLY, sourceFallbackPolicy = SourceFallbackPolicy.BEST_AVAILABLE)
        assertTrue(selector.rankWithPresetFallback(listOf(stream("720p", cached = false)), prefs, DeviceCodecCaps()).isEmpty())
        assertTrue(selector.rankWithPresetFallback(listOf(stream("720p", "HEVC")), prefs,
            DeviceCodecCaps(supportsHevc = true)).isEmpty())
    }
}
