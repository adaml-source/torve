package com.torve.android.player

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torve.domain.model.Channel
import com.torve.domain.player.LiveAudioOutputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveAudioCompatibilityStoreInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearStore() {
        LiveAudioCompatibilityStore.clearInMemoryState()
        context.getSharedPreferences("torve_live_audio_compatibility", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun playbackProfile_persistsAndResolvesAcrossSessions() {
        val playbackContext = samplePlaybackContext("News One", "news.one")
        val stored = LiveAudioCompatibilityStore.rememberSuccessfulRecovery(
            context = context,
            playbackContext = playbackContext,
            passthroughEnabled = false,
            preferSurround = false,
            outputMode = LiveAudioOutputMode.PREFER_COMPATIBLE,
            recoveryKind = LiveAudioRecoveryKind.COMPATIBLE_TRACK,
            engineId = LivePlayerEngineId.EXOPLAYER,
            preferredTrack = LiveAudioTrackHint(
                label = "English AAC",
                language = "eng",
                formatKey = "audio/aac",
                channelCount = 2,
                groupIndex = 1,
                trackIndex = 0,
            ),
            audioSignature = "aac:eng:2:english aac",
        )

        val resolved = LiveAudioCompatibilityStore.resolveHint(context, playbackContext)
        assertNotNull(resolved)
        assertEquals(stored.preferredTrack?.formatKey, resolved?.preferredTrack?.formatKey)
        assertEquals(LivePlayerEngineId.EXOPLAYER, resolved?.preferredEngineId())
        assertEquals(LiveAudioRecoveryKind.COMPATIBLE_TRACK, resolved?.recoveryKind)
    }

    @Test
    fun playbackProfile_failureCountersAndInvalidationPersist() {
        val playbackContext = samplePlaybackContext("News Two", "news.two")
        LiveAudioCompatibilityStore.rememberSuccessfulRecovery(
            context = context,
            playbackContext = playbackContext,
            passthroughEnabled = true,
            preferSurround = true,
            outputMode = LiveAudioOutputMode.AUTO,
            recoveryKind = LiveAudioRecoveryKind.SOFTWARE_AUDIO,
            engineId = LivePlayerEngineId.EXOPLAYER,
            preferredTrack = LiveAudioTrackHint(formatKey = "audio/eac3", channelCount = 6),
            audioSignature = "eac3:eng:6",
            softwareAudioRequired = true,
        )

        LiveAudioCompatibilityStore.recordFailure(
            context = context,
            playbackContext = playbackContext,
            reason = "bounded_readiness_timeout",
        )

        val resolvedAfterFailure = LiveAudioCompatibilityStore.resolveHint(context, playbackContext)
        assertNotNull(resolvedAfterFailure)
        assertTrue((resolvedAfterFailure?.failureCount ?: 0) >= 1)
        assertEquals(true, resolvedAfterFailure?.softwareAudioRequired)

        LiveAudioCompatibilityStore.invalidateHint(context, playbackContext)
        assertNull(LiveAudioCompatibilityStore.resolveHint(context, playbackContext))
    }

    @Test
    fun terminalFailure_persistsAndClearsAfterSuccessfulRecovery() {
        val playbackContext = samplePlaybackContext("News Three", "news.three")

        LiveAudioCompatibilityStore.rememberTerminalFailure(
            context = context,
            playbackContext = playbackContext,
            preferencesKey = "false|false|prefer_compatible",
            selectedMime = "audio/vnd.dts",
            audioSignature = "audio/vnd.dts:und:1",
            finalRecoveryMode = "TRACK_RESELECT",
            finalTuneState = "FALLBACK_ALLOWED",
            reason = "decoder init failed",
        )

        val storedFailure = LiveAudioCompatibilityStore.resolveTerminalFailure(
            context = context,
            playbackContext = playbackContext,
            preferencesKey = "false|false|prefer_compatible",
        )
        assertNotNull(storedFailure)
        assertEquals("audio/vnd.dts", storedFailure?.selectedMime)
        assertEquals("TRACK_RESELECT", storedFailure?.finalRecoveryMode)
        assertTrue((storedFailure?.failureCount ?: 0) >= 1)

        LiveAudioCompatibilityStore.rememberSuccessfulRecovery(
            context = context,
            playbackContext = playbackContext,
            passthroughEnabled = false,
            preferSurround = false,
            outputMode = LiveAudioOutputMode.PREFER_COMPATIBLE,
            recoveryKind = LiveAudioRecoveryKind.COMPATIBLE_TRACK,
            engineId = LivePlayerEngineId.EXOPLAYER,
            preferredTrack = LiveAudioTrackHint(formatKey = "audio/aac", channelCount = 2),
            audioSignature = "audio/aac:eng:2",
        )

        assertNull(
            LiveAudioCompatibilityStore.resolveTerminalFailure(
                context = context,
                playbackContext = playbackContext,
                preferencesKey = "false|false|prefer_compatible",
            ),
        )
    }

    private fun samplePlaybackContext(name: String, tvgId: String): LiveAudioPlaybackContext {
        return LiveAudioPlaybackContext.fromChannel(
            Channel(
                name = name,
                url = "https://example.com/live/$tvgId.m3u8",
                playlistId = "playlist-1",
                tvgId = tvgId,
                groupTitle = "News",
            ),
        )
    }
}
