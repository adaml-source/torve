package com.torve.desktop.player

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopPlayerContractsTest {

    @Test
    fun `DesktopAspectMode has all standard ratios`() {
        val modes = DesktopAspectMode.entries
        assertTrue(modes.any { it == DesktopAspectMode.RATIO_16_9 })
        assertTrue(modes.any { it == DesktopAspectMode.RATIO_4_3 })
        assertTrue(modes.any { it == DesktopAspectMode.DEFAULT })
        assertTrue(modes.any { it == DesktopAspectMode.FILL })
    }

    @Test
    fun `DesktopPlaybackSpeed entries cover standard range`() {
        val speeds = DesktopPlaybackSpeed.entries
        assertTrue(speeds.any { it.rate == 0.5f })
        assertTrue(speeds.any { it.rate == 1.0f })
        assertTrue(speeds.any { it.rate == 2.0f })
    }

    @Test
    fun `DesktopPlaybackSpeed labels are formatted`() {
        assertEquals("1x", DesktopPlaybackSpeed.SPEED_1_0.label)
        assertEquals("2x", DesktopPlaybackSpeed.SPEED_2_0.label)
    }

    @Test
    fun `DesktopTrackInfo holds track data`() {
        val track = DesktopTrackInfo(id = 1, label = "English", language = "en", isSelected = true)
        assertEquals(1, track.id)
        assertEquals("English", track.label)
        assertEquals("en", track.language)
        assertTrue(track.isSelected)
    }

    @Test
    fun `DesktopPlayerDiagnostics has engine name`() {
        val diag = DesktopPlayerDiagnostics(
            engineName = "LibVLC",
            vlcVersion = null, runtimePath = null, playbackState = "Idle", sourceType = null,
            videoCodec = null, audioCodec = null, resolution = null, fps = null, bitrate = null,
            sampleRate = null, audioChannels = null, cacheLevel = null, bufferingMode = null,
            playbackSpeed = 1f, volume = 100, isMuted = false,
            audioDelay = 0, subtitleDelay = 0, aspectMode = null,
            mediaUrl = null, durationMs = 0, currentTimeMs = 0, audioTrack = null, subtitleTrack = null,
        )
        assertEquals("LibVLC", diag.engineName)
        assertEquals(1f, diag.playbackSpeed)
        assertEquals(100, diag.volume)
    }

    @Test
    fun `DesktopBufferingPreset labels are user facing`() {
        assertEquals("Auto", DesktopBufferingPreset.AUTO.label)
        assertTrue(DesktopBufferingPreset.STABLE_STREAM.description.isNotBlank())
        assertTrue(DesktopBufferingPreset.AGGRESSIVE_BUFFER.networkCachingMs != null)
    }

    @Test
    fun `DesktopPlayerErrorCategory has user-facing titles`() {
        val categories = DesktopPlayerErrorCategory.entries
        assertTrue(categories.all { it.title.isNotBlank() })
        assertEquals("VLC runtime not found", DesktopPlayerErrorCategory.RUNTIME_MISSING.title)
    }
}
