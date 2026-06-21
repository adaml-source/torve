package com.torve.android.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.domain.model.Channel
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.LiveAudioRecoveryMode
import com.torve.domain.player.LiveTuneState
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExoTerminalFailureDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun clearStore() {
        LiveAudioCompatibilityStore.clearInMemoryState()
        context.getSharedPreferences("torve_live_audio_compatibility", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun repeatedDtsFailure_shortCircuitsAfterTerminalClassification() {
        val channel = sampleChannel(
            name = "Software Probe dts",
            streamId = "dts-terminal",
            url = DTS_FIXTURE_URL,
        )
        val playbackContext = LiveAudioPlaybackContext.fromChannel(channel)

        val firstOutcome = runFailureTune(channel)
        val storedFailure = LiveAudioCompatibilityStore.resolveTerminalFailure(
            context = context,
            playbackContext = playbackContext,
            preferencesKey = DEFAULT_PREFERENCES_KEY,
        )

        assertNotNull(storedFailure)
        assertTrue(firstOutcome.recoveryModes.contains(LiveAudioRecoveryMode.SOFTWARE_AUDIO.name))
        assertTrue(firstOutcome.stateSummary().contains(LiveTuneState.FALLBACK_ALLOWED.name))
        assertFalse(firstOutcome.confirmed)
        assertTrue(firstOutcome.fallbackCallbackCount >= 1)

        val secondOutcome = runFailureTune(channel)
        val updatedFailure = LiveAudioCompatibilityStore.resolveTerminalFailure(
            context = context,
            playbackContext = playbackContext,
            preferencesKey = DEFAULT_PREFERENCES_KEY,
        )

        assertNotNull(updatedFailure)
        assertFalse(secondOutcome.confirmed)
        assertTrue(secondOutcome.fallbackCallbackCount >= 1)
        assertTrue(
            "Expected repeated non-recoverable tune to fail faster. first=${firstOutcome.elapsedMs} second=${secondOutcome.elapsedMs}",
            secondOutcome.elapsedMs < firstOutcome.elapsedMs,
        )
        assertFalse(secondOutcome.recoveryModes.contains(LiveAudioRecoveryMode.SOFTWARE_AUDIO.name))
        assertFalse(secondOutcome.recoveryModes.contains(LiveAudioRecoveryMode.STEREO_PCM.name))
        assertFalse(secondOutcome.recoveryModes.contains(LiveAudioRecoveryMode.TRACK_RESELECT.name))
        assertTrue(secondOutcome.stateSummary().contains(LiveTuneState.FALLBACK_ALLOWED.name))
        assertTrue((updatedFailure?.failureCount ?: 0) > (storedFailure?.failureCount ?: 0))

        Log.i(
            VALIDATION_TAG,
            "Scenario=repeated_dts_terminal_failure " +
                "firstMs=${firstOutcome.elapsedMs} secondMs=${secondOutcome.elapsedMs} " +
                "firstStates=${firstOutcome.stateSummary()} secondStates=${secondOutcome.stateSummary()} " +
                "firstRecovery=${firstOutcome.recoveryModes.joinToString()} " +
                "secondRecovery=${secondOutcome.recoveryModes.joinToString()} " +
                "failureCount=${updatedFailure?.failureCount}",
        )
    }

    private fun runFailureTune(
        channel: Channel,
        timeoutMs: Long = 8_000L,
    ): FailureTuneOutcome {
        val engine = createEngine()
        val states = CopyOnWriteArrayList<PlayerState>()
        val messages = CopyOnWriteArrayList<String>()
        val recoveryModes = linkedSetOf<String>()
        val terminal = CountDownLatch(1)
        val fallbackCallbackCount = AtomicInteger(0)
        var playStartedMs = 0L

        val listener = object : PlayerListener {
            override fun onStateChanged(state: PlayerState) {
                states += state
                if (state.audioRecoveryMode != LiveAudioRecoveryMode.NONE) {
                    recoveryModes += state.audioRecoveryMode.name
                }
                if (
                    state.liveTuneState == LiveTuneState.FALLBACK_ALLOWED ||
                    (state.liveTuneState == LiveTuneState.PLAYING_CONFIRMED &&
                        (!state.isAudioExpected || state.isAudioReady))
                ) {
                    terminal.countDown()
                }
            }

            override fun onError(message: String) {
                messages += message
            }
        }

        try {
            instrumentation.runOnMainSync {
                engine.addListener(listener)
                engine.onLiveAudioCompatibilityFailure = {
                    fallbackCallbackCount.incrementAndGet()
                    false
                }
                engine.setAudioOutputPreferences(
                    passthroughEnabled = false,
                    preferSurround = false,
                    outputMode = LiveAudioOutputMode.PREFER_COMPATIBLE,
                )
                engine.setLivePlaybackContext(channel)
                playStartedMs = SystemClock.elapsedRealtime()
                engine.play(channel.url)
            }

            terminal.await(timeoutMs, TimeUnit.MILLISECONDS)
            lateinit var runtimeInfo: PlaybackRuntimeInfo
            instrumentation.runOnMainSync {
                runtimeInfo = engine.getPlaybackRuntimeInfo()
                engine.removeListener(listener)
                engine.stop()
            }
            return FailureTuneOutcome(
                confirmed = runtimeInfo.liveTuneState == LiveTuneState.PLAYING_CONFIRMED,
                elapsedMs = SystemClock.elapsedRealtime() - playStartedMs,
                fallbackCallbackCount = fallbackCallbackCount.get(),
                finalState = runtimeInfo.liveTuneState,
                finalRecoveryMode = runtimeInfo.audioRecoveryMode,
                recoveryModes = recoveryModes.toList(),
                stateHistory = states.toList(),
                messages = messages.toList(),
            )
        } finally {
            releaseEngine(engine)
        }
    }

    private fun createEngine(): ExoPlayerEngine {
        lateinit var engine: ExoPlayerEngine
        instrumentation.runOnMainSync {
            engine = ExoPlayerEngine(context).also {
                it.testMediaSourceFactory = { ctx, url -> createFixtureSource(ctx, url) }
                it.initialize()
            }
        }
        return engine
    }

    private fun releaseEngine(engine: ExoPlayerEngine) {
        instrumentation.runOnMainSync {
            engine.release()
        }
    }

    private fun createFixtureSource(targetContext: Context, url: String): MediaSource {
        require(url == DTS_FIXTURE_URL) { "Unexpected fixture url: $url" }
        return ProgressiveMediaSource.Factory(DefaultDataSource.Factory(targetContext))
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(Uri.parse("asset:///fixtures/software_audio/dts_probe.mkv"))
                    .build(),
            )
    }

    private fun sampleChannel(name: String, streamId: String, url: String): Channel {
        return Channel(
            name = name,
            url = url,
            playlistId = "software-audio-fixtures",
            tvgId = streamId,
            groupTitle = "Fixture",
        )
    }

    private data class FailureTuneOutcome(
        val confirmed: Boolean,
        val elapsedMs: Long,
        val fallbackCallbackCount: Int,
        val finalState: LiveTuneState,
        val finalRecoveryMode: LiveAudioRecoveryMode,
        val recoveryModes: List<String>,
        val stateHistory: List<PlayerState>,
        val messages: List<String>,
    ) {
        fun stateSummary(): String {
            return stateHistory.joinToString(separator = " -> ") {
                "${it.liveTuneState}:${it.audioRecoveryMode}:${it.isAudioReady}:${it.isVideoReady}"
            }
        }
    }

    private companion object {
        private const val VALIDATION_TAG = "TerminalFailureValidation"
        private const val DTS_FIXTURE_URL = "fixture://software-audio/dts"
        private const val DEFAULT_PREFERENCES_KEY = "false|false|prefer_compatible"
    }
}
