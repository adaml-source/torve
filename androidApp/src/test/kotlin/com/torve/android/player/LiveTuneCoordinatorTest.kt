package com.torve.android.player

import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.LiveTuneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTuneCoordinatorTest {

    @Test
    fun tuneSuccess_requiresAudioReadiness_whenAudioTracksExist() {
        val machine = LiveTuneStateMachine(readinessTimeoutMs = 900L)

        machine.begin(nowElapsedMs = 0L)
        machine.onTracksDiscovered(audioTrackCount = 2, selectedAudioTrack = true, nowElapsedMs = 10L)
        machine.onVideoReady(nowElapsedMs = 20L)

        val beforeAudioReady = machine.onPlaybackReady(nowElapsedMs = 30L)
        assertEquals(LiveTuneState.BUFFERING_AV, beforeAudioReady.state)
        assertTrue(beforeAudioReady.audioExpected)
        assertFalse(beforeAudioReady.audioReady)

        machine.onAudioReady()
        val confirmed = machine.onPlaybackReady(nowElapsedMs = 40L)
        assertEquals(LiveTuneState.PLAYING_CONFIRMED, confirmed.state)
        assertTrue(confirmed.audioReady)
        assertTrue(confirmed.videoReady)
    }

    @Test
    fun noAudioTracks_confirmImmediatelyOnceVideoIsReady() {
        val machine = LiveTuneStateMachine(readinessTimeoutMs = 900L)

        machine.begin(nowElapsedMs = 0L)
        machine.onTracksDiscovered(audioTrackCount = 0, selectedAudioTrack = false, nowElapsedMs = 10L)
        machine.onVideoReady(nowElapsedMs = 20L)

        val confirmed = machine.onPlaybackReady(nowElapsedMs = 30L)
        assertEquals(LiveTuneState.PLAYING_CONFIRMED, confirmed.state)
        assertFalse(confirmed.audioExpected)
        assertTrue(confirmed.audioReady)
    }

    @Test
    fun boundedReadinessTimeout_onlyStartsAfterVideoIsReady() {
        val machine = LiveTuneStateMachine(readinessTimeoutMs = 900L)

        machine.begin(nowElapsedMs = 0L)
        machine.onTracksDiscovered(audioTrackCount = 1, selectedAudioTrack = true, nowElapsedMs = 10L)
        assertFalse(machine.isAudioReadinessTimedOut(nowElapsedMs = 2_000L))

        machine.onVideoReady(nowElapsedMs = 100L)
        assertFalse(machine.isAudioReadinessTimedOut(nowElapsedMs = 999L))
        assertTrue(machine.isAudioReadinessTimedOut(nowElapsedMs = 1_000L))
    }

    @Test
    fun cachedTrackMatch_winsOverDefaultTrack() {
        val cachedTrack = LiveAudioTrackHint(
            label = "English AAC",
            language = "eng",
            formatKey = "audio/aac",
            channelCount = 2,
            groupIndex = 1,
            trackIndex = 0,
        )
        val candidates = listOf(
            LiveAudioTrackCandidate(
                id = "0:0:audio/ac3:false:prefer_compatible",
                label = "Default AC3",
                language = "eng",
                formatKey = "audio/ac3",
                channelCount = 6,
                supported = true,
                selected = true,
                isDefault = true,
                groupIndex = 0,
                trackIndex = 0,
            ),
            LiveAudioTrackCandidate(
                id = "1:0:audio/aac:false:prefer_compatible",
                label = "English AAC",
                language = "eng",
                formatKey = "audio/aac",
                channelCount = 2,
                supported = true,
                groupIndex = 1,
                trackIndex = 0,
            ),
        )

        val selected = LiveTrackSelectionPolicy.selectBestCandidate(
            candidates = candidates,
            cachedTrack = cachedTrack,
            currentSelectionId = candidates.first().id,
            passthroughEnabled = false,
            preferSurround = false,
            outputMode = LiveAudioOutputMode.PREFER_COMPATIBLE,
        )

        assertEquals(candidates[1].id, selected?.id)
    }

    @Test
    fun compatibleStereoTrack_beatsDefaultSurroundTrack_whenPreferringCompatibleAudio() {
        val candidates = listOf(
            LiveAudioTrackCandidate(
                id = "0:0:audio/ac3:false:prefer_compatible",
                label = "Default AC3",
                language = "eng",
                formatKey = "audio/ac3",
                channelCount = 6,
                supported = true,
                selected = true,
                isDefault = true,
                groupIndex = 0,
                trackIndex = 0,
            ),
            LiveAudioTrackCandidate(
                id = "1:0:audio/aac:false:prefer_compatible",
                label = "English AAC",
                language = "eng",
                formatKey = "audio/aac",
                channelCount = 2,
                supported = true,
                selected = false,
                isDefault = false,
                groupIndex = 1,
                trackIndex = 0,
            ),
        )

        val selected = LiveTrackSelectionPolicy.selectBestCandidate(
            candidates = candidates,
            cachedTrack = null,
            currentSelectionId = candidates.first().id,
            passthroughEnabled = false,
            preferSurround = false,
            outputMode = LiveAudioOutputMode.PREFER_COMPATIBLE,
        )

        assertEquals(candidates[1].id, selected?.id)
    }

    @Test
    fun recoveryPlanner_staysInsideExoplayer_beforeAllowingFailure() {
        val baseInput = AudioRecoveryPlanInput(
            alternateTrackAvailable = true,
            alternateTrackAttempted = false,
            compatibleModeAttempted = false,
            softwareAudioAttempted = false,
            stereoPcmAttempted = false,
            passthroughEnabled = true,
            preferSurround = true,
            outputMode = LiveAudioOutputMode.AUTO,
        )

        assertEquals(ExoAudioRecoveryAction.SWITCH_TRACK, AudioTrackRecoveryPlanner.nextAction(baseInput))
        assertEquals(
            ExoAudioRecoveryAction.DISABLE_PASSTHROUGH,
            AudioTrackRecoveryPlanner.nextAction(baseInput.copy(alternateTrackAttempted = true)),
        )
        assertEquals(
            ExoAudioRecoveryAction.PREFER_SOFTWARE_AUDIO,
            AudioTrackRecoveryPlanner.nextAction(
                baseInput.copy(
                    alternateTrackAttempted = true,
                    compatibleModeAttempted = true,
                    passthroughEnabled = false,
                    preferSurround = false,
                    outputMode = LiveAudioOutputMode.PREFER_COMPATIBLE,
                ),
            ),
        )
        assertEquals(
            ExoAudioRecoveryAction.FORCE_STEREO_PCM,
            AudioTrackRecoveryPlanner.nextAction(
                baseInput.copy(
                    alternateTrackAttempted = true,
                    compatibleModeAttempted = true,
                    softwareAudioAttempted = true,
                    alternateTrackAvailable = false,
                    passthroughEnabled = false,
                    preferSurround = false,
                    outputMode = LiveAudioOutputMode.PREFER_COMPATIBLE,
                ),
            ),
        )
        assertEquals(
            ExoAudioRecoveryAction.MARK_FAILED,
            AudioTrackRecoveryPlanner.nextAction(
                baseInput.copy(
                    alternateTrackAvailable = false,
                    alternateTrackAttempted = true,
                    compatibleModeAttempted = true,
                    softwareAudioAttempted = true,
                    stereoPcmAttempted = true,
                    passthroughEnabled = false,
                    preferSurround = false,
                    outputMode = LiveAudioOutputMode.FORCE_STEREO_PCM,
                ),
            ),
        )
    }

    @Test
    fun fallbackAllowed_isOnlyEnteredAfterExplicitFailure() {
        val machine = LiveTuneStateMachine(readinessTimeoutMs = 900L)

        machine.begin(nowElapsedMs = 0L)
        machine.onTracksDiscovered(audioTrackCount = 1, selectedAudioTrack = true, nowElapsedMs = 10L)
        machine.onVideoReady(nowElapsedMs = 20L)
        machine.onRecoveryStarted(
            recoveryMode = com.torve.domain.player.LiveAudioRecoveryMode.SOFTWARE_AUDIO,
            nowElapsedMs = 30L,
        )

        val failed = machine.onFailure(fallbackAllowed = true)
        assertEquals(LiveTuneState.FALLBACK_ALLOWED, failed.state)
        assertTrue(failed.fallbackAllowed)
    }
}
