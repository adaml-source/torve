package com.torve.android.tv.screens

import com.torve.android.player.LiveAudioCompatibilityFailureReport
import com.torve.android.player.LiveAudioPlaybackContext
import com.torve.android.player.LiveAudioTerminalFailureHint
import com.torve.android.player.LivePlayerEngineId
import com.torve.domain.model.Channel
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.LiveAudioRecoveryMode
import com.torve.domain.player.LiveTuneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLivePlaybackPolicyTest {

    @Test
    fun tvLivePlayback_prefersExoplayerAsPrimaryEngine() {
        val channel = Channel(
            name = "News One",
            url = "https://example.com/live/news-one.m3u8",
            playlistId = "playlist-1",
            groupTitle = "News",
        )

        assertEquals(
            LivePlayerEngineId.EXOPLAYER,
            TvLivePlaybackPolicy.primaryEngineForChannel(channel, mpvAvailable = true),
        )
    }

    @Test
    fun tvLivePlayback_doesNotAutomaticallySwitchToMpvOnRecoverableAudioFailure() {
        val report = LiveAudioCompatibilityFailureReport(
            selectedEngine = LivePlayerEngineId.EXOPLAYER,
            playbackContext = LiveAudioPlaybackContext.fromChannel(
                Channel(
                    name = "Silent News",
                    url = "https://example.com/live/silent-news.m3u8",
                    playlistId = "playlist-1",
                    groupTitle = "News",
                ),
            ),
            selectedMime = "audio/ac3",
            trackCount = 2,
            selectedTrack = null,
            passthroughEnabled = true,
            outputMode = LiveAudioOutputMode.AUTO,
            preferSurround = true,
            recoveryAttempts = 4,
            diagnostics = "bounded_readiness_timeout",
            fallbackAllowed = true,
            recoveryMode = LiveAudioRecoveryMode.SOFTWARE_AUDIO,
            tuneState = LiveTuneState.FALLBACK_ALLOWED,
        )

        assertFalse(TvLivePlaybackPolicy.allowAutomaticMpvFallback(report))
    }

    @Test
    fun tvLivePlayback_allowsAutomaticMpvFallbackAfterTerminalDecoderFailure() {
        val report = LiveAudioCompatibilityFailureReport(
            selectedEngine = LivePlayerEngineId.EXOPLAYER,
            playbackContext = LiveAudioPlaybackContext.fromChannel(
                Channel(
                    name = "Unsupported News",
                    url = "https://example.com/live/unsupported-news.m3u8",
                    playlistId = "playlist-1",
                    groupTitle = "News",
                ),
            ),
            selectedMime = "audio/mpeg-L2",
            trackCount = 1,
            selectedTrack = null,
            passthroughEnabled = false,
            outputMode = LiveAudioOutputMode.PREFER_COMPATIBLE,
            preferSurround = true,
            recoveryAttempts = 4,
            diagnostics = "Decoder init failed: [-49999], format_supported=NO_UNS",
            fallbackAllowed = true,
            recoveryMode = LiveAudioRecoveryMode.STEREO_PCM,
            tuneState = LiveTuneState.FALLBACK_ALLOWED,
        )

        assertTrue(TvLivePlaybackPolicy.allowAutomaticMpvFallback(report))
    }

    @Test
    fun tvLivePlayback_prefersMpvForKnownTerminalExoFailure() {
        val now = System.currentTimeMillis()
        val hint = LiveAudioTerminalFailureHint(
            deviceProfile = "amazon|aftgazl|sdk28",
            channelKey = "channel",
            streamKey = "stream",
            preferencesKey = "false|true|prefer_compatible",
            selectedMime = "audio/mpeg-L2",
            audioSignature = "sig",
            terminalFailureKind = "EXOPLAYER_AUDIO_UNRECOVERABLE",
            finalRecoveryMode = "STEREO_PCM",
            finalTuneState = LiveTuneState.FALLBACK_ALLOWED.name,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            failureCount = 2,
            lastFailureReason = "Decoder init failed",
        )

        assertEquals(
            LivePlayerEngineId.MPV,
            TvLivePlaybackPolicy.primaryEngineForKnownTerminalFailure(
                hint = hint,
                mpvAvailable = true,
            ),
        )
    }
}
