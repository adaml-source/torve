package com.torve.android.player

import com.torve.domain.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvPlaybackProfileStoreTest {

    @Test
    fun defaultProfile_transportStream_biasesTsBufferingAndSync() {
        val channel = Channel(
            name = "3sat HD",
            url = "http://example.test/live/3sat.ts?mime=video/mp2t",
            tvgCountry = "DE",
        )
        val playbackContext = LiveAudioPlaybackContext(
            deviceProfile = "amazon|aftgazl|sdk30",
            channelKey = "channel",
            streamKey = "stream",
            displayName = "3sat HD",
        )

        val profile = LiveTvPlaybackHeuristics.defaultProfile(channel, playbackContext, nowEpochMs = 1L)

        assertEquals(LiveTvVideoSyncMode.DISPLAY_RESAMPLE.storageValue, profile.videoSyncMode)
        assertTrue(profile.likelyTransportStream)
        assertEquals(24, profile.steadyCacheSecs)
        assertEquals(96, profile.steadyMaxBytesMiB)
        assertEquals(LiveTvDeinterlaceMode.AUTO.storageValue, profile.deinterlaceMode)
    }

    @Test
    fun successObservation_promotesForcedDeinterlaceWhenInterlaceDetected() {
        val base = LiveTvPlaybackProfile(
            deviceProfile = "device",
            channelKey = "channel",
            streamKey = "stream",
            videoSyncMode = LiveTvVideoSyncMode.DISPLAY_RESAMPLE.storageValue,
            deinterlaceMode = LiveTvDeinterlaceMode.AUTO.storageValue,
            interpolationEnabled = false,
            tscaleMode = "oversample",
            hardwareDecodeMode = "auto-safe",
            zapCacheSecs = 4,
            steadyCacheSecs = 24,
            zapReadaheadSecs = 2,
            steadyReadaheadSecs = 10,
            zapMaxBytesMiB = 24,
            steadyMaxBytesMiB = 96,
            backBufferMiB = 32,
            streamBufferKiB = 1024,
            demuxerProbeBytes = 2_097_152,
            demuxerAnalyzeDurationUs = 2_500_000L,
            linearizeTimestamps = true,
            forceSeekable = false,
            diskCacheEnabled = false,
            likelyTransportStream = true,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )

        val merged = LiveTvPlaybackHeuristics.mergeSuccessfulObservation(
            base = base,
            observation = LiveTvRuntimeObservation(interlacedDetected = true, estimatedFrameRate = 25f),
            nowEpochMs = 2L,
        )

        assertEquals(LiveTvDeinterlaceMode.FORCE.storageValue, merged.deinterlaceMode)
        assertTrue(merged.interlacedSeen)
        assertEquals(25f, merged.frameRateHint)
    }

    @Test
    fun playbackIssue_expandsBuffersAndCanSwitchToVdrop() {
        val base = LiveTvPlaybackProfile(
            deviceProfile = "device",
            channelKey = "channel",
            streamKey = "stream",
            videoSyncMode = LiveTvVideoSyncMode.DISPLAY_RESAMPLE.storageValue,
            deinterlaceMode = LiveTvDeinterlaceMode.OFF.storageValue,
            interpolationEnabled = false,
            tscaleMode = "oversample",
            hardwareDecodeMode = "auto-safe",
            zapCacheSecs = 3,
            steadyCacheSecs = 18,
            zapReadaheadSecs = 1,
            steadyReadaheadSecs = 8,
            zapMaxBytesMiB = 16,
            steadyMaxBytesMiB = 64,
            backBufferMiB = 16,
            streamBufferKiB = 512,
            demuxerProbeBytes = 1_048_576,
            demuxerAnalyzeDurationUs = 1_500_000L,
            linearizeTimestamps = false,
            forceSeekable = false,
            diskCacheEnabled = false,
            likelyTransportStream = false,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )

        val merged = LiveTvPlaybackHeuristics.mergePlaybackIssue(
            base = base,
            reason = "judder_and_buffer_spike",
            observation = LiveTvRuntimeObservation(
                interlacedDetected = false,
                estimatedFrameRate = 50f,
                mistimedFrameCount = 12,
                droppedFrameCount = 7,
                delayedFrameCount = 5,
            ),
            nowEpochMs = 2L,
        )

        assertEquals(LiveTvVideoSyncMode.DISPLAY_VDROP.storageValue, merged.videoSyncMode)
        assertTrue(merged.steadyCacheSecs > base.steadyCacheSecs)
        assertTrue(merged.steadyMaxBytesMiB > base.steadyMaxBytesMiB)
        assertTrue(merged.backBufferMiB > base.backBufferMiB)
    }
}
