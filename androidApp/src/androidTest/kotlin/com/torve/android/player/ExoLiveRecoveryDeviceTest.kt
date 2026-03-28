package com.torve.android.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.common.TrackGroup
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.LoopingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.Allocator
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.R
import com.torve.domain.model.Channel
import com.torve.domain.player.LiveAudioRecoveryMode
import com.torve.domain.player.LiveTuneState
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExoLiveRecoveryDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val fixtureFactory = LiveRecoveryFixtureMediaSources(context)

    @Before
    fun clearStore() {
        LiveAudioCompatibilityStore.clearInMemoryState()
        context.getSharedPreferences("torve_live_audio_compatibility", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getFileStreamPath(REPORT_FILE_NAME)?.delete()
    }

    @Test
    fun goodChannel_tunesQuicklyWithAudioOnFirstTry() {
        val channel = sampleChannel(
            name = "Fixture Good",
            streamId = "fixture-good",
            url = GOOD_FIXTURE_URL,
        )
        val engine = createEngine()
        try {
            val outcome = tuneChannel(engine, channel, GOOD_FIXTURE_URL)

            assertEquals(0, outcome.fallbackCallbackCount)
            assertFalse(outcome.recoveryModes.contains("TRACK_RESELECT"))
            assertTrue(outcome.finalState.isAudioReady)
            assertTrue(outcome.finalState.isVideoReady)
            assertTrue(outcome.confirmedPlaybackMs in 1..4_000L)

            Log.i(
                VALIDATION_TAG,
                "Scenario=good_channel confirmedMs=${outcome.confirmedPlaybackMs} " +
                    "states=${outcome.stateSummary()} messages=${outcome.messages.joinToString()}",
            )
            appendReport(
                "scenario=good_channel confirmedMs=${outcome.confirmedPlaybackMs} " +
                    "fallbackCallbacks=${outcome.fallbackCallbackCount} " +
                    "tracks=${outcome.audioTrackSummary} selected=${outcome.selectedAudioLabel ?: "none"} " +
                    "states=${outcome.stateSummary()} messages=${outcome.messages.joinToString()}",
            )
        } finally {
            releaseEngine(engine)
        }
    }

    @Test
    fun stalledPrimaryAudio_recoversInsideExo_and_reusesLearnedProfile() {
        val channel = sampleChannel(
            name = "Fixture Stalled Primary",
            streamId = "fixture-stalled-primary",
            url = STALLED_PRIMARY_FIXTURE_URL,
        )
        val playbackContext = LiveAudioPlaybackContext.fromChannel(channel)
        LiveAudioCompatibilityStore.rememberSuccessfulRecovery(
            context = context,
            playbackContext = playbackContext,
            passthroughEnabled = true,
            preferSurround = true,
            outputMode = com.torve.domain.player.LiveAudioOutputMode.AUTO,
            recoveryKind = LiveAudioRecoveryKind.COMPATIBLE_TRACK,
            engineId = LivePlayerEngineId.EXOPLAYER,
            preferredTrack = LiveAudioTrackHint(
                label = "Broken Primary Audio",
                language = "eng",
                formatKey = "audio/mpeg",
                channelCount = 2,
                groupIndex = 0,
                trackIndex = 0,
            ),
            audioSignature = null,
        )

        val firstEngine = createEngine()
        val firstOutcome = try {
            tuneChannel(firstEngine, channel, STALLED_PRIMARY_FIXTURE_URL)
        } finally {
            releaseEngine(firstEngine)
        }
        appendReport(
            "scenario=stalled_primary_audio_first_tune confirmedMs=${firstOutcome.confirmedPlaybackMs} " +
                "fallbackCallbacks=${firstOutcome.fallbackCallbackCount} " +
                "recoveryModes=${firstOutcome.recoveryModes.joinToString()} " +
                "tracks=${firstOutcome.audioTrackSummary} selected=${firstOutcome.selectedAudioLabel ?: "none"} " +
                "states=${firstOutcome.stateSummary()} messages=${firstOutcome.messages.joinToString()}",
        )

        assertEquals(0, firstOutcome.fallbackCallbackCount)
        assertTrue(
            "Expected TRACK_RESELECT recovery but saw modes=${firstOutcome.recoveryModes} " +
                "selected=${firstOutcome.selectedAudioLabel} tracks=${firstOutcome.audioTrackSummary} " +
                "states=${firstOutcome.stateSummary()} messages=${firstOutcome.messages}",
            firstOutcome.recoveryModes.contains("TRACK_RESELECT"),
        )
        assertTrue(firstOutcome.finalState.isAudioReady)
        assertTrue(firstOutcome.finalState.isVideoReady)
        assertTrue(firstOutcome.confirmedPlaybackMs >= 900L)
        assertEquals("Valid Secondary Audio", firstOutcome.selectedAudioLabel)

        val storedHint = LiveAudioCompatibilityStore.resolveHint(
            context,
            playbackContext,
        )
        assertNotNull(storedHint)
        assertEquals(LiveAudioRecoveryKind.COMPATIBLE_TRACK, storedHint?.recoveryKind)

        val secondEngine = createEngine()
        val secondOutcome = try {
            tuneChannel(secondEngine, channel, STALLED_PRIMARY_FIXTURE_URL)
        } finally {
            releaseEngine(secondEngine)
        }

        assertEquals(0, secondOutcome.fallbackCallbackCount)
        assertFalse(secondOutcome.recoveryModes.contains("TRACK_RESELECT"))
        assertTrue(secondOutcome.finalState.isAudioReady)
        assertTrue(secondOutcome.confirmedPlaybackMs < firstOutcome.confirmedPlaybackMs)
        assertEquals("Valid Secondary Audio", secondOutcome.selectedAudioLabel)

        Log.i(
            VALIDATION_TAG,
            "Scenario=stalled_primary_audio firstConfirmedMs=${firstOutcome.confirmedPlaybackMs} " +
                "secondConfirmedMs=${secondOutcome.confirmedPlaybackMs} " +
                "firstTracks=${firstOutcome.audioTrackSummary} secondTracks=${secondOutcome.audioTrackSummary} " +
                "firstStates=${firstOutcome.stateSummary()} secondStates=${secondOutcome.stateSummary()} " +
                "storedRecovery=${storedHint?.recoveryKind} storedTrack=${storedHint?.preferredTrack}",
        )
        appendReport(
            "scenario=stalled_primary_audio firstConfirmedMs=${firstOutcome.confirmedPlaybackMs} " +
                "secondConfirmedMs=${secondOutcome.confirmedPlaybackMs} " +
                "fallbackCallbacks=${firstOutcome.fallbackCallbackCount + secondOutcome.fallbackCallbackCount} " +
                "firstTracks=${firstOutcome.audioTrackSummary} secondTracks=${secondOutcome.audioTrackSummary} " +
                "firstStates=${firstOutcome.stateSummary()} secondStates=${secondOutcome.stateSummary()} " +
                "storedRecovery=${storedHint?.recoveryKind} storedTrack=${storedHint?.preferredTrack}",
        )
    }

    private fun createEngine(): ExoPlayerEngine {
        lateinit var engine: ExoPlayerEngine
        instrumentation.runOnMainSync {
            engine = ExoPlayerEngine(context).also {
                it.testMediaSourceFactory = { ctx, url -> fixtureFactory.create(url, ctx) }
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

    private fun tuneChannel(
        engine: ExoPlayerEngine,
        channel: Channel,
        url: String,
        timeoutMs: Long = 12_000L,
    ): TuneOutcome {
        val states = CopyOnWriteArrayList<PlayerState>()
        val messages = CopyOnWriteArrayList<String>()
        val recoveryModes = CopyOnWriteArrayList<String>()
        val confirmed = CountDownLatch(1)
        val fallbackCallbackCount = AtomicInteger(0)
        var playStartedMs = 0L
        var selectedAudioLabel: String? = null
        var audioTrackSummary = ""

        val listener = object : PlayerListener {
            override fun onStateChanged(state: PlayerState) {
                states += state
                if (state.audioRecoveryMode != LiveAudioRecoveryMode.NONE) {
                    recoveryModes += state.audioRecoveryMode.name
                }
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

        instrumentation.runOnMainSync {
            engine.addListener(listener)
            engine.onLiveAudioCompatibilityFailure = {
                fallbackCallbackCount.incrementAndGet()
                false
            }
            engine.setAudioOutputPreferences(
                passthroughEnabled = true,
                preferSurround = true,
            )
            engine.setLivePlaybackContext(channel)
            playStartedMs = SystemClock.elapsedRealtime()
            engine.play(url)
        }

        val confirmedInTime = confirmed.await(timeoutMs, TimeUnit.MILLISECONDS)

        instrumentation.runOnMainSync {
            val runtimeInfo = engine.getPlaybackRuntimeInfo()
            selectedAudioLabel = runtimeInfo.selectedAudioTrack?.label
            audioTrackSummary = engine.getAudioTracks()
                .joinToString(separator = " | ") { track ->
                    val selectedMarker = if (track.isSelected) "*" else ""
                    "$selectedMarker${track.label}:${track.formatHint ?: "unknown"}:${track.channelCount ?: -1}"
                }
            engine.removeListener(listener)
            engine.stop()
        }

        assertTrue(
            buildString {
                append("Tune did not reach PLAYING_CONFIRMED. ")
                append("states=")
                append(states.joinToString { "${it.liveTuneState}/${it.audioRecoveryMode}/${it.isAudioReady}/${it.isVideoReady}" })
                append(" messages=")
                append(messages.joinToString())
            },
            confirmedInTime,
        )

        val finalState = states.last()
        val confirmedPlaybackMs = states
            .firstOrNull {
                it.liveTuneState == LiveTuneState.PLAYING_CONFIRMED &&
                    (!it.isAudioExpected || it.isAudioReady)
            }
            ?.let { SystemClock.elapsedRealtime() - playStartedMs }
            ?: (SystemClock.elapsedRealtime() - playStartedMs)

        return TuneOutcome(
            confirmedPlaybackMs = confirmedPlaybackMs,
            finalState = finalState,
            recoveryModes = recoveryModes.toList(),
            messages = messages.toList(),
            fallbackCallbackCount = fallbackCallbackCount.get(),
            allStates = states.toList(),
            selectedAudioLabel = selectedAudioLabel,
            audioTrackSummary = audioTrackSummary,
        )
    }

    private fun sampleChannel(name: String, streamId: String, url: String): Channel {
        return Channel(
            name = name,
            url = url,
            playlistId = "fixture-playlist",
            tvgId = streamId,
            groupTitle = "Fixture",
        )
    }

    private data class TuneOutcome(
        val confirmedPlaybackMs: Long,
        val finalState: PlayerState,
        val recoveryModes: List<String>,
        val messages: List<String>,
        val fallbackCallbackCount: Int,
        val allStates: List<PlayerState>,
        val selectedAudioLabel: String?,
        val audioTrackSummary: String,
    ) {
        fun stateSummary(): String {
            return allStates.joinToString(separator = " -> ") {
                "${it.liveTuneState}:${it.audioRecoveryMode}:${it.isAudioReady}:${it.isVideoReady}"
            }
        }
    }

    private class LiveRecoveryFixtureMediaSources(
        private val context: Context,
    ) {
        fun create(url: String, targetContext: Context): MediaSource {
            return when (url) {
                GOOD_FIXTURE_URL -> mergedSource(
                    targetContext = targetContext,
                    includeBrokenPrimaryAudio = false,
                )
                STALLED_PRIMARY_FIXTURE_URL -> mergedSource(
                    targetContext = targetContext,
                    includeBrokenPrimaryAudio = true,
                )
                else -> throw PlaybackException(
                    "Unknown fixture url: $url",
                    null,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                )
            }
        }

        private fun mergedSource(
            targetContext: Context,
            includeBrokenPrimaryAudio: Boolean,
        ): MediaSource {
            val sources = mutableListOf<MediaSource>()
            sources += progressiveSource(
                factory = DefaultDataSource.Factory(targetContext),
                uri = Uri.parse("asset:///fixtures/reset_position.mp4"),
            )
            if (includeBrokenPrimaryAudio) {
                sources += DualTrackAudioRecoveryMediaSource(
                    context = targetContext,
                    MediaItem.Builder()
                        .setUri(Uri.parse(STALLED_PRIMARY_FIXTURE_URL))
                        .build(),
                    durationUs = 5_000_000L,
                )
            } else {
                sources += progressiveSource(
                    factory = DefaultDataSource.Factory(targetContext),
                    uri = RawResourceDataSource.buildRawResourceUri(R.raw.torve_sting_dark),
                )
            }
            return LoopingMediaSource(MergingMediaSource(true, true, *sources.toTypedArray()))
        }

        private fun progressiveSource(
            factory: DataSource.Factory,
            uri: Uri,
        ): MediaSource {
            return ProgressiveMediaSource.Factory(factory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(uri)
                        .build(),
                )
        }
    }

    private class DualTrackAudioRecoveryMediaSource(
        context: Context,
        private val mediaItem: MediaItem,
        private val durationUs: Long,
    ) : androidx.media3.exoplayer.source.BaseMediaSource() {
        private val validTrackBytes = buildPcmSineChunk()
        private val trackGroup = TrackGroup(
            Format.Builder()
                .setId("broken-primary-audio")
                .setSampleMimeType(MimeTypes.AUDIO_MPEG)
                .setLanguage("eng")
                .setLabel("Broken Primary Audio")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setChannelCount(2)
                .setSampleRate(44_100)
                .setAverageBitrate(128_000)
                .build(),
            Format.Builder()
                .setId("valid-secondary-audio")
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setLanguage("eng")
                .setLabel("Valid Secondary Audio")
                .setChannelCount(2)
                .setSampleRate(44_100)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setAverageBitrate(44_100 * 2 * 2 * 8)
                .build(),
        )

        override fun getMediaItem(): MediaItem = mediaItem

        override fun prepareSourceInternal(transferListener: androidx.media3.datasource.TransferListener?) {
            refreshSourceInfo(
                SinglePeriodTimeline(
                    durationUs,
                    true,
                    false,
                    false,
                    null,
                    mediaItem,
                ),
            )
        }

        override fun maybeThrowSourceInfoRefreshError() = Unit

        override fun createPeriod(
            id: MediaSource.MediaPeriodId,
            allocator: Allocator,
            startPositionUs: Long,
        ): MediaPeriod {
            return DualTrackAudioRecoveryMediaPeriod(
                trackGroup = trackGroup,
                durationUs = durationUs,
                validTrackBytes = validTrackBytes,
            )
        }

        override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit

        override fun releaseSourceInternal() = Unit
    }

    private class DualTrackAudioRecoveryMediaPeriod(
        private val trackGroup: TrackGroup,
        private val durationUs: Long,
        private val validTrackBytes: ByteArray,
    ) : MediaPeriod {
        private val trackGroups = TrackGroupArray(trackGroup)

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            callback.onPrepared(this)
        }

        override fun maybeThrowPrepareError() = Unit

        override fun getTrackGroups(): TrackGroupArray = trackGroups

        override fun selectTracks(
            selections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long {
            for (index in selections.indices) {
                val selection = selections[index]
                if (selection == null) {
                    streams[index] = null
                    continue
                }
                val selectedTrackIndex = selection.getIndexInTrackGroup(max(0, selection.selectedIndex))
                streams[index] = when (selectedTrackIndex) {
                    0 -> BrokenPrimaryAudioSampleStream(trackGroup.getFormat(0))
                    else -> ValidPcmAudioSampleStream(trackGroup.getFormat(1), validTrackBytes)
                }
                streamResetFlags[index] = true
            }
            return positionUs
        }

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit

        override fun readDiscontinuity(): Long = C.TIME_UNSET

        override fun seekToUs(positionUs: Long): Long = positionUs

        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
            return positionUs
        }

        override fun getBufferedPositionUs(): Long = C.TIME_END_OF_SOURCE

        override fun getNextLoadPositionUs(): Long = C.TIME_END_OF_SOURCE

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false

        override fun isLoading(): Boolean = false

        override fun reevaluateBuffer(positionUs: Long) = Unit
    }

    private class BrokenPrimaryAudioSampleStream(
        private val format: Format,
    ) : SampleStream {
        private var sentFormat = false

        override fun isReady(): Boolean = false

        override fun maybeThrowError() = Unit

        override fun readData(
            formatHolder: FormatHolder,
            buffer: DecoderInputBuffer,
            readFlags: Int,
        ): Int {
            if (readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0) {
                formatHolder.format = format
                return C.RESULT_FORMAT_READ
            }
            if (!sentFormat) {
                formatHolder.format = format
                sentFormat = true
                return C.RESULT_FORMAT_READ
            }
            return C.RESULT_NOTHING_READ
        }

        override fun skipData(positionUs: Long): Int = 0
    }

    private class ValidPcmAudioSampleStream(
        private val format: Format,
        private val pcmChunkBytes: ByteArray,
    ) : SampleStream {
        private var sentFormat = false
        private var sampleIndex = 0
        private var sentEndOfStream = false

        override fun isReady(): Boolean = true

        override fun maybeThrowError() = Unit

        override fun readData(
            formatHolder: FormatHolder,
            buffer: DecoderInputBuffer,
            readFlags: Int,
        ): Int {
            val requireFormat = readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0
            val omitSampleData = readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA != 0
            val peekSample = readFlags and SampleStream.FLAG_PEEK != 0
            if (requireFormat) {
                formatHolder.format = format
                return C.RESULT_FORMAT_READ
            }
            if (!sentFormat) {
                formatHolder.format = format
                sentFormat = true
                return C.RESULT_FORMAT_READ
            }
            if (sampleIndex < MAX_SAMPLE_COUNT) {
                buffer.clear()
                buffer.timeUs = SAMPLE_DURATION_US * sampleIndex
                buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME)
                if (!omitSampleData) {
                    buffer.ensureSpaceForWrite(pcmChunkBytes.size)
                    buffer.data?.put(pcmChunkBytes)
                    buffer.data?.flip()
                }
                if (!peekSample) {
                    sampleIndex += 1
                }
                return C.RESULT_BUFFER_READ
            }
            if (!sentEndOfStream) {
                buffer.clear()
                buffer.timeUs = SAMPLE_DURATION_US * MAX_SAMPLE_COUNT
                buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                if (!peekSample) {
                    sentEndOfStream = true
                }
                return C.RESULT_BUFFER_READ
            }
            return C.RESULT_NOTHING_READ
        }

        override fun skipData(positionUs: Long): Int = 0

        private companion object {
            private const val SAMPLE_DURATION_US = 23_220L
            private const val MAX_SAMPLE_COUNT = 512
        }
    }

    private companion object {
        private const val VALIDATION_TAG = "LiveValidation"
        private const val GOOD_FIXTURE_URL = "fixture://good-channel"
        private const val STALLED_PRIMARY_FIXTURE_URL = "fixture://stalled-primary-audio"
        private const val REPORT_FILE_NAME = "live_validation_report.txt"
        private const val PCM_SAMPLE_RATE = 44_100
        private const val PCM_CHANNELS = 2
        private const val PCM_FRAMES_PER_CHUNK = 1_024
        private fun buildPcmSineChunk(): ByteArray {
            val bytes = ByteArray(PCM_FRAMES_PER_CHUNK * PCM_CHANNELS * 2)
            val frequencyHz = 440.0
            val amplitude = Short.MAX_VALUE * 0.2
            for (frame in 0 until PCM_FRAMES_PER_CHUNK) {
                val sampleValue = (sin(2.0 * Math.PI * frequencyHz * frame / PCM_SAMPLE_RATE) * amplitude)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                val frameOffset = frame * PCM_CHANNELS * 2
                for (channel in 0 until PCM_CHANNELS) {
                    val channelOffset = frameOffset + (channel * 2)
                    bytes[channelOffset] = (sampleValue.toInt() and 0xFF).toByte()
                    bytes[channelOffset + 1] = ((sampleValue.toInt() shr 8) and 0xFF).toByte()
                }
            }
            return bytes
        }
    }

    private fun appendReport(line: String) {
        context.openFileOutput(REPORT_FILE_NAME, Context.MODE_APPEND).bufferedWriter().use { writer ->
            writer.appendLine(line)
        }
    }
}
