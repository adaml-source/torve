package com.torve.desktop.player

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VlcMediaOptionsTest {

    @Test
    fun `factoryArgs includes hardware decoding`() {
        val args = VlcMediaOptions.factoryArgs()
        assertTrue(args.any { it.contains("avcodec-hw") }, "Should include hardware decoding option")
    }

    @Test
    fun `factoryArgs includes network caching`() {
        val args = VlcMediaOptions.factoryArgs()
        assertTrue(args.any { it.contains("network-caching") }, "Should include network caching")
    }

    @Test
    fun `factoryArgs disables video title`() {
        val args = VlcMediaOptions.factoryArgs()
        assertTrue(args.any { it.contains("no-video-title-show") }, "Should disable video title overlay")
    }

    @Test
    fun `factoryArgs does not contain empty strings`() {
        val args = VlcMediaOptions.factoryArgs()
        assertFalse(args.any { it.isBlank() }, "No blank args should be present")
    }

    @Test
    fun `mediaOptions applies fast start auto defaults for vod`() {
        val options = VlcMediaOptions.mediaOptions()
        assertTrue(options.any { it.contains("network-caching=650") })
        assertTrue(options.any { it.contains("live-caching=450") })
        assertTrue(options.any { it.contains("file-caching=220") })
    }

    @Test
    fun `mediaOptions includes referer header`() {
        val options = VlcMediaOptions.mediaOptions(
            requestHeaders = mapOf("Referer" to "https://example.com"),
        )
        assertTrue(options.any { it.contains("http-referrer") && it.contains("example.com") })
    }

    @Test
    fun `mediaOptions includes user-agent header`() {
        val options = VlcMediaOptions.mediaOptions(
            requestHeaders = mapOf("User-Agent" to "Torve/1.0"),
        )
        assertTrue(options.any { it.contains("http-user-agent") && it.contains("Torve/1.0") })
    }

    @Test
    fun `mediaOptions includes start time`() {
        val options = VlcMediaOptions.mediaOptions(startTimeMs = 30_000)
        assertTrue(options.any { it.contains("start-time=30") })
    }

    @Test
    fun `mediaOptions includes subtitle file`() {
        val options = VlcMediaOptions.mediaOptions(subtitleUrl = "https://example.com/subs.srt")
        assertTrue(options.any { it.contains("sub-file") && it.contains("subs.srt") })
    }

    @Test
    fun `mediaOptions includes buffering preset overrides`() {
        val options = VlcMediaOptions.mediaOptions(bufferingPreset = DesktopBufferingPreset.LOW_LATENCY)
        assertTrue(options.any { it.contains("network-caching=450") })
        assertTrue(options.any { it.contains("live-caching=180") })
        assertTrue(options.any { it.contains("file-caching=120") })
    }

    @Test
    fun `mediaOptions uses live auto profile for live urls`() {
        val options = VlcMediaOptions.mediaOptions(mediaPath = "https://example.com/channel/live/master.m3u8")
        assertTrue(options.any { it.contains("network-caching=550") })
        assertTrue(options.any { it.contains("live-caching=250") })
        assertTrue(options.any { it.contains("file-caching=180") })
    }

    @Test
    fun `mediaOptions uses local file auto profile for local files`() {
        val options = VlcMediaOptions.mediaOptions(mediaPath = "C:\\\\media\\\\movie.mkv")
        assertFalse(options.any { it.contains("network-caching=") })
        // file-caching was bumped 140 → 1000 to stop visible stutter when the
        // decoder briefly falls behind during sustained local playback.
        // See VlcMediaOptions.autoLocalFileProfile.
        assertTrue(options.any { it.contains("file-caching=1000") })
    }

    @Test
    fun `liveStreamOverrides has lower caching`() {
        val overrides = VlcMediaOptions.liveStreamOverrides()
        assertTrue(overrides.any { it.contains("network-caching=800") })
        assertTrue(overrides.any { it.contains("live-caching=400") })
    }
}
