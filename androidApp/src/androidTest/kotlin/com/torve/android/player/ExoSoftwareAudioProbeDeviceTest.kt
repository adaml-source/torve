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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExoSoftwareAudioProbeDeviceTest {
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
    fun probeDefaultVsForcedSoftwareAudioFixtures() {
        SOFTWARE_AUDIO_FIXTURES.forEach { fixture ->
            val defaultOutcome = runProbe(fixture, forceSoftwareProfile = false)
            val forcedSoftwareOutcome = runProbe(fixture, forceSoftwareProfile = true)
            Log.i(
                PROBE_TAG,
                buildString {
                    append("fixture=")
                    append(fixture.id)
                    append(" defaultConfirmed=")
                    append(defaultOutcome.confirmed)
                    append(" defaultMs=")
                    append(defaultOutcome.confirmedPlaybackMs)
                    append(" defaultAudioDecoder=")
                    append(defaultOutcome.audioDecoderName ?: "none")
                    append(" defaultAudioKind=")
                    append(defaultOutcome.audioDecoderKind)
                    append(" defaultCodec=")
                    append(defaultOutcome.audioCodec ?: "none")
                    append(" defaultStates=")
                    append(defaultOutcome.stateSummary())
                    append(" defaultMessages=")
                    append(defaultOutcome.messages.joinToString())
                    append(" forcedConfirmed=")
                    append(forcedSoftwareOutcome.confirmed)
                    append(" forcedMs=")
                    append(forcedSoftwareOutcome.confirmedPlaybackMs)
                    append(" forcedAudioDecoder=")
                    append(forcedSoftwareOutcome.audioDecoderName ?: "none")
                    append(" forcedAudioKind=")
                    append(forcedSoftwareOutcome.audioDecoderKind)
                    append(" forcedCodec=")
                    append(forcedSoftwareOutcome.audioCodec ?: "none")
                    append(" forcedStates=")
                    append(forcedSoftwareOutcome.stateSummary())
                    append(" forcedMessages=")
                    append(forcedSoftwareOutcome.messages.joinToString())
                    append(" forcedFallbackCallbacks=")
                    append(forcedSoftwareOutcome.fallbackCallbackCount)
                },
            )
        }
    }

    private fun runProbe(
        fixture: SoftwareAudioFixture,
        forceSoftwareProfile: Boolean,
        timeoutMs: Long = 7_000L,
    ): ProbeOutcome {
        val engine = createEngine()
        val channel = sampleChannel(
            name = "Software Probe ${fixture.id}",
            streamId = fixture.id,
            url = fixture.url,
        )
        val playbackContext = LiveAudioPlaybackContext.fromChannel(channel)
        if (forceSoftwareProfile) {
            LiveAudioCompatibilityStore.rememberSuccessfulRecovery(
                context = context,
                playbackContext = playbackContext,
                passthroughEnabled = false,
                preferSurround = false,
                outputMode = LiveAudioOutputMode.PREFER_COMPATIBLE,
                recoveryKind = LiveAudioRecoveryKind.SOFTWARE_AUDIO,
                engineId = LivePlayerEngineId.EXOPLAYER,
                preferredTrack = null,
                audioSignature = null,
                softwareAudioRequired = true,
            )
        }

        val states = CopyOnWriteArrayList<PlayerState>()
        val messages = CopyOnWriteArrayList<String>()
        val confirmed = CountDownLatch(1)
        val fallbackCallbackCount = AtomicInteger(0)
        var playStartedMs = 0L

        val listener = object : PlayerListener {
            override fun onStateChanged(state: PlayerState) {
                states += state
                if (
                    state.liveTuneState == LiveTuneState.PLAYING_CONFIRMED &&
                    (!state.isAudioExpected || state.isAudioReady)
                ) {
                    confirmed.countDown()
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
                engine.play(fixture.url)
            }

            val confirmedInTime = confirmed.await(timeoutMs, TimeUnit.MILLISECONDS)
            lateinit var runtimeInfo: PlaybackRuntimeInfo
            instrumentation.runOnMainSync {
                runtimeInfo = engine.getPlaybackRuntimeInfo()
                engine.removeListener(listener)
                engine.stop()
            }
            val elapsedMs = SystemClock.elapsedRealtime() - playStartedMs
            return ProbeOutcome(
                confirmed = confirmedInTime,
                confirmedPlaybackMs = elapsedMs,
                audioDecoderName = runtimeInfo.audioDecoderName,
                audioDecoderKind = runtimeInfo.audioDecoderKind,
                audioCodec = runtimeInfo.audioCodec,
                selectedAudioLabel = runtimeInfo.selectedAudioTrack?.label,
                liveTuneState = runtimeInfo.liveTuneState,
                audioRecoveryMode = runtimeInfo.audioRecoveryMode,
                messages = messages.toList(),
                fallbackCallbackCount = fallbackCallbackCount.get(),
                allStates = states.toList(),
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
        val assetPath = when (url) {
            AC3_FIXTURE_URL -> "asset:///fixtures/software_audio/ac3_probe.mkv"
            EAC3_FIXTURE_URL -> "asset:///fixtures/software_audio/eac3_probe.mkv"
            DTS_FIXTURE_URL -> "asset:///fixtures/software_audio/dts_probe.mkv"
            else -> error("Unknown fixture url: $url")
        }
        return ProgressiveMediaSource.Factory(DefaultDataSource.Factory(targetContext))
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(Uri.parse(assetPath))
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

    private data class ProbeOutcome(
        val confirmed: Boolean,
        val confirmedPlaybackMs: Long,
        val audioDecoderName: String?,
        val audioDecoderKind: DecoderKind,
        val audioCodec: String?,
        val selectedAudioLabel: String?,
        val liveTuneState: LiveTuneState,
        val audioRecoveryMode: LiveAudioRecoveryMode,
        val messages: List<String>,
        val fallbackCallbackCount: Int,
        val allStates: List<PlayerState>,
    ) {
        fun stateSummary(): String {
            return allStates.joinToString(separator = " -> ") {
                "${it.liveTuneState}:${it.audioRecoveryMode}:${it.isAudioReady}:${it.isVideoReady}"
            }
        }
    }

    private data class SoftwareAudioFixture(
        val id: String,
        val url: String,
    )

    private companion object {
        private const val PROBE_TAG = "SoftwareAudioProbe"
        private const val AC3_FIXTURE_URL = "fixture://software-audio/ac3"
        private const val EAC3_FIXTURE_URL = "fixture://software-audio/eac3"
        private const val DTS_FIXTURE_URL = "fixture://software-audio/dts"

        private val SOFTWARE_AUDIO_FIXTURES = listOf(
            SoftwareAudioFixture("ac3", AC3_FIXTURE_URL),
            SoftwareAudioFixture("eac3", EAC3_FIXTURE_URL),
            SoftwareAudioFixture("dts", DTS_FIXTURE_URL),
        )
    }
}
