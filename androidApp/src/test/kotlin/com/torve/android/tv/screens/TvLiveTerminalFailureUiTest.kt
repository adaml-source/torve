package com.torve.android.tv.screens

import com.torve.android.player.LiveAudioTerminalFailureHint
import com.torve.android.player.LivePlayerEngineId
import com.torve.domain.player.LiveTuneState
import com.torve.domain.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLiveTerminalFailureUiTest {

    @Test
    fun `dts terminal failure maps to contextual tv banner`() {
        val presentation = buildTvLiveTerminalFailurePresentation(
            terminalFailureHint(selectedMime = "audio/vnd.dts"),
        )

        assertEquals(
            "This channel's DTS audio isn't supported on this device.",
            presentation.bannerMessage,
        )
        assertTrue(presentation.suppressTuneProgress)
    }

    @Test
    fun `generic terminal failure maps to fallback allowed tv banner`() {
        val presentation = buildTvLiveTerminalFailurePresentation(
            terminalFailureHint(selectedMime = null),
        )

        assertEquals(
            "Audio unavailable on this device for this channel.",
            presentation.bannerMessage,
        )
    }

    @Test
    fun `known terminal failure suppresses ambiguous exo tune spinner`() {
        val showSpinner = shouldShowTvLiveTuneProgress(
            playerState = PlayerState(
                isIdle = false,
                isBuffering = true,
                liveTuneState = LiveTuneState.BUFFERING_AV,
            ),
            engineId = LivePlayerEngineId.EXOPLAYER,
            terminalFailurePresentation = TvLiveTerminalFailurePresentation(
                bannerMessage = "Audio unavailable on this device for this channel.",
            ),
        )

        assertFalse(showSpinner)
    }

    @Test
    fun `recoverable exo tune still shows spinner`() {
        val showSpinner = shouldShowTvLiveTuneProgress(
            playerState = PlayerState(
                isIdle = false,
                isBuffering = true,
                liveTuneState = LiveTuneState.BUFFERING_AV,
            ),
            engineId = LivePlayerEngineId.EXOPLAYER,
            terminalFailurePresentation = null,
        )

        assertTrue(showSpinner)
    }

    private fun terminalFailureHint(selectedMime: String?): LiveAudioTerminalFailureHint {
        val now = System.currentTimeMillis()
        return LiveAudioTerminalFailureHint(
            deviceProfile = "amazon|aftgazl|sdk28",
            channelKey = "channel",
            streamKey = "stream",
            preferencesKey = "false|false|auto",
            selectedMime = selectedMime,
            audioSignature = "sig",
            terminalFailureKind = "EXOPLAYER_AUDIO_UNRECOVERABLE",
            finalRecoveryMode = "TRACK_RESELECT",
            finalTuneState = LiveTuneState.FALLBACK_ALLOWED.name,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            failureCount = 1,
            lastFailureReason = "fixture",
        )
    }
}
