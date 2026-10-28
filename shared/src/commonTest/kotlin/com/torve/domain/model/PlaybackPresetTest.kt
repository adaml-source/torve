package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackPresetTest {
    @Test fun fastStartCapsQualityAndUsesCompatibleVideo() {
        val result = PlaybackPreset.FAST_START.apply(StreamPreferences(allow4kAuto = true))
        assertEquals(StreamQuality.FHD_1080P, result.maxQuality)
        assertEquals(CodecPreference.H264_ONLY, result.codecPreference)
        assertEquals(HdrMode.SDR_ONLY, result.hdrMode)
        assertTrue(result.cachedOnly)
        assertFalse(result.allow4kAuto)
    }
    @Test fun qualityIntentDoesNotEnableUnsupportedDisplayFlags() {
        val result = PlaybackPreset.BEST_QUALITY.apply(StreamPreferences(dvEnabled = false, hdrEnabled = false))
        assertTrue(result.allow4kAuto)
        assertFalse(result.dvEnabled)
        assertFalse(result.hdrEnabled)
        assertEquals(SourceFallbackPolicy.ASK, result.sourceFallbackPolicy)
    }
    @Test fun languagePresetRequiresAnActualLanguage() {
        assertFalse(PlaybackPreset.PREFERRED_AUDIO.isAvailable(StreamPreferences()))
        val selected = PlaybackPreset.PREFERRED_AUDIO.apply(StreamPreferences(preferredAudioLanguages = listOf("de")))
        assertEquals(listOf("de"), selected.preferredAudioLanguages)
        assertEquals(SourceLanguageMatchMode.REQUIRE, selected.sourceLanguageMatchMode)
        assertEquals(UnknownSourceMetadataPolicy.REJECT, selected.unknownSourceLanguagePolicy)
    }
    @Test fun advancedEditsBecomeCustomWithoutChangingNextEpisodePreferences() {
        val original = StreamPreferences(nextEpisodeMode = NextEpisodeMode.OFF)
        val selected = PlaybackPreset.FAST_START.apply(original)
        assertEquals(NextEpisodeMode.OFF, selected.nextEpisodeMode)
        assertEquals(PlaybackPreset.FAST_START, PlaybackPreset.recognize(selected))
        assertEquals(PlaybackPreset.CUSTOM, PlaybackPreset.recognize(selected.copy(cachedOnly = false)))
    }
}
