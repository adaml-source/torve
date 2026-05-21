package com.torve.android.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.torve.domain.model.Channel
import com.torve.domain.player.ExternalSubtitle
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.LiveAudioRecoveryMode
import com.torve.domain.player.LiveTuneState
import com.torve.domain.player.PlayerEngine
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState
import com.torve.domain.player.TrackDescription
import java.util.Locale

/**
 * PlayerEngine backed by ExoPlayer (Media3 1.5.1).
 * Used as fallback when libmpv .so files are not available.
 *
 * Resilience:
 * - On audio codec error, silently falls back to a compatible track or disables audio
 * - On video codec error, notifies via onCodecError callback so the stream selection
 *   layer can retry with a safer variant
 * - Video quality filtering is handled upstream by StreamSelector (not at TrackSelector level)
 */
@UnstableApi
class ExoPlayerEngine(
    private val context: Context,
) : PlayerEngine {

    private var _state = PlayerState()
    override val state: PlayerState get() = _state

    private val listeners = mutableListOf<PlayerListener>()
    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    private var currentSubtitleTracks = listOf<TrackDescription>()
    private var currentAudioTracks = listOf<TrackDescription>()
    private var trackGroups = listOf<Tracks.Group>()

    /**
     * Side-loaded subtitle tracks supplied by the caller for the next
     * [play] invocation. Consumed by [setPlaybackSource] when it builds
     * the MediaItem. Cleared on each play() call so the list never
     * carries across unrelated streams.
     */
    private var pendingExternalSubtitles: List<ExternalSubtitle> = emptyList()

    /**
     * One-shot HTTP request headers staged via
     * [setNextRequestHeaders]. Consumed by [setPlaybackSource] when it
     * builds the next MediaSource and cleared immediately after, so the
     * headers never leak to an unrelated stream the next time the user
     * picks a provider URL. Used by the LAN-library route to attach
     * `X-Torve-Lan-Auth` to the desktop hub's stream URL.
     */
    private var pendingRequestHeaders: Map<String, String> = emptyMap()
    /** Test-only accessor — the Android instrumentation test reads this. */
    @androidx.annotation.VisibleForTesting
    internal fun pendingRequestHeadersForTest(): Map<String, String> = pendingRequestHeaders
    private val delayProcessor = DelayAudioProcessor()
    val equalizerProcessor = EqualizerAudioProcessor()

    /** Set by the player screen to enable codec-error fallback at the stream level. */
    var onCodecError: ((errorCode: Int) -> Unit)? = null

    /**
     * Gives the live TV screen one last chance to move playback to the alternate engine before
     * ExoPlayer declares the channel audio-incompatible and disables audio.
     */
    internal var onLiveAudioCompatibilityFailure: ((LiveAudioCompatibilityFailureReport) -> Boolean)? = null
    internal var testMediaSourceFactory: ((Context, String) -> MediaSource)? = null

    private var audioPassthroughEnabled = false
    private var preferSurroundCodecs = true
    private var liveAudioOutputMode = LiveAudioOutputMode.PREFER_COMPATIBLE
    private var userAudioPassthroughEnabled = false
    private var userPreferSurroundCodecs = true
    private var userLiveAudioOutputMode = LiveAudioOutputMode.PREFER_COMPATIBLE
    private var compatibleModeFallbackAttempted = false
    private var alternateTrackFallbackAttempted = false
    private var softwareAudioFallbackAttempted = false
    private var stereoPcmFallbackAttempted = false
    private var liveAudioRecoveryAttempts = 0
    private var currentPlaybackContext: LiveAudioPlaybackContext? = null
    private var rememberedCompatibilityHint: LiveAudioCompatibilityHint? = null
    private var rememberedTrackHintApplied = false
    private var currentAudioSignature: String? = null
    private var pendingSuccessfulRecoveryKind: LiveAudioRecoveryKind? = null
    private var sessionIncompatibleRecoverySkipped = false
    private var persistedTerminalFailureHint: LiveAudioTerminalFailureHint? = null
    private var pendingTrackRecoveryAfterReprepare: PendingTrackRecoveryAfterReprepare? = null
    private val attemptedAudioRecoveryKeys = mutableSetOf<String>()
    private var honorRememberedCompatibilityHint = true
    private var videoDecoderName: String? = null
    private var audioDecoderName: String? = null
    private var currentUrl: String? = null
    internal var liveBufferDurationMs: Int = DEFAULT_LIVE_BUFFER_MS
    private var preferSoftwareAudioDecoding = false
    private var currentRendererPrefersSoftwareAudio = false
    private val liveTuneStateMachine = LiveTuneStateMachine(AUDIO_READINESS_TIMEOUT_MS)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioReadinessTimeoutRunnable = Runnable {
        handleAudioReadinessTimeout("bounded_readiness_timeout")
    }

    private enum class PendingTrackRecoveryAfterReprepare {
        COMPATIBLE_MODE,
        SOFTWARE_AUDIO,
        STEREO_PCM,
    }

    private data class SelectedAudioTrackSnapshot(
        val group: Tracks.Group,
        val groupIndex: Int,
        val trackIndex: Int,
        val mime: String?,
        val language: String?,
        val channelCount: Int,
        val bitrate: Int,
        val label: String?,
    )

    private val exoListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _state = _state.copy(isPlaying = playing)
            maybeConfirmTune("is_playing_changed")
            notifyStateChanged()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state = _state.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                isIdle = playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED,
            )
            when (playbackState) {
                Player.STATE_BUFFERING -> applyTuneSnapshot(
                    liveTuneStateMachine.onPlaybackBuffering(SystemClock.elapsedRealtime()),
                )
                Player.STATE_READY -> {
                    _state = _state.copy(durationMs = exoPlayer?.duration ?: 0)
                    if (!_state.isEngineFallbackAllowed) {
                        applyTuneSnapshot(
                            liveTuneStateMachine.onPlaybackReady(SystemClock.elapsedRealtime()),
                        )
                    }
                    maybeConfirmTune("playback_ready")
                }
                Player.STATE_IDLE,
                Player.STATE_ENDED -> {
                    cancelAudioReadinessTimeout()
                    applyTuneSnapshot(liveTuneStateMachine.onFailure(fallbackAllowed = _state.isEngineFallbackAllowed))
                }
            }
            notifyStateChanged()
        }

        override fun onPlayerError(error: PlaybackException) {
            // When no compatibility failure handler is set (TV live playback),
            // skip all custom audio recovery — let ExoPlayer + FFmpeg handle
            // codec fallback automatically via setEnableDecoderFallback(true),
            // exactly like TiviMate does.
            if (onLiveAudioCompatibilityFailure == null) {
                Log.w(TAG, "Player error (${error.errorCode}): ${error.message}")
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                ) {
                    // Re-sync to live edge on live window errors and audio
                    // timestamp discontinuities (common in IPTV PTS resets).
                    Log.i(TAG, "Re-syncing to live edge after error ${error.errorCode}")
                    exoPlayer?.let { player ->
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    return
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) {
                    // AC3/EAC3 passthrough unsupported on this device.
                    // Force software decoding to PCM — this avoids the
                    // bypass render path that sends raw AC3 to AudioTrack.
                    Log.w(TAG, "AudioTrack init failed — forcing software audio + stereo PCM")
                    liveAudioOutputMode = LiveAudioOutputMode.FORCE_STEREO_PCM
                    audioPassthroughEnabled = false
                    preferSoftwareAudioDecoding = true
                    rebuildPlayerForAudioProfile("audio_track_init_failed")
                    // After rebuild, ensure the track selector respects the
                    // channel cap even when no stereo track exists.
                    trackSelector?.setParameters(
                        trackSelector!!.parameters.buildUpon()
                            .setExceedAudioConstraintsIfNecessary(false)
                            .setMaxAudioChannelCount(2)
                            .build(),
                    )
                    return
                }
                listeners.forEach { it.onError(error.message ?: "Playback error (${error.errorCode})") }
                return
            }

            val isCodecError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED

            if (isCodecError) {
                val player = exoPlayer
                val msg = error.message.orEmpty()
                val causeChain = error.buildCauseChainSummary()
                val selectedAudio = player?.selectedAudioTrackSnapshot()
                val isAudioCodecError = msg.contains("AudioRenderer", ignoreCase = true) ||
                    msg.contains("AudioSink", ignoreCase = true) ||
                    causeChain.contains("AudioRenderer", ignoreCase = true) ||
                    causeChain.contains("AudioSink", ignoreCase = true) ||
                    causeChain.contains("AudioTrack", ignoreCase = true) ||
                    causeChain.contains("audio/eac3", ignoreCase = true) ||
                    causeChain.contains("audio/ac3", ignoreCase = true) ||
                    causeChain.contains("audio/aac", ignoreCase = true) ||
                    causeChain.contains("audio/mp4a-latm", ignoreCase = true) ||
                    causeChain.contains("audio/dts", ignoreCase = true) ||
                    causeChain.contains("audio/truehd", ignoreCase = true) ||
                    causeChain.contains("audio/mlp", ignoreCase = true) ||
                    isAc3FamilyMime(selectedAudio?.mime)

                if (isAudioCodecError) {
                    if (player != null && attemptLiveAudioRecovery(player, error, selectedAudio, causeChain)) {
                        return
                    }
                    if (player != null) {
                        disableAudioAfterCompatibilityFailure(
                            player = player,
                            error = error,
                            selectedAudio = selectedAudio,
                            diagnostics = causeChain,
                        )
                        return
                    }
                }

                Log.w(TAG, "Video codec error (${error.errorCode}): $msg")
                val callback = onCodecError
                if (callback != null) {
                    callback(error.errorCode)
                    return
                }
            }

            val errMsg = when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED ->
                    "Codec error: This format is not supported on this device"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "Network error: Could not connect to stream"
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                    "Live stream error: Fell behind live window"
                else -> error.message ?: "Playback error (${error.errorCode})"
            }
            listeners.forEach { it.onError(errMsg) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val subs = mutableListOf<TrackDescription>()
            val audios = mutableListOf<TrackDescription>()
            val groups = mutableListOf<Tracks.Group>()

            for (group in tracks.groups) {
                groups.add(group)
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = group.isTrackSelected(i)
                    val label = format.label
                        ?: format.language?.let { Locale(it).displayLanguage }
                        ?: "Track ${i + 1}"

                    when (group.type) {
                        C.TRACK_TYPE_TEXT -> subs.add(
                            TrackDescription(
                                id = subs.size,
                                label = label,
                                language = format.language,
                                isSelected = isSelected,
                                formatHint = format.sampleMimeType,
                            ),
                        )
                        C.TRACK_TYPE_AUDIO -> audios.add(
                            TrackDescription(
                                id = audios.size,
                                label = label,
                                language = format.language,
                                isSelected = isSelected,
                                formatHint = format.sampleMimeType,
                                channelCount = format.channelCount.takeIf { it > 0 },
                            ),
                        )
                    }
                }
            }

            trackGroups = groups
            currentSubtitleTracks = subs
            currentAudioTracks = audios
            currentAudioSignature = tracks.buildAudioSignature()
            applyTuneSnapshot(
                liveTuneStateMachine.onTracksDiscovered(
                    audioTrackCount = audios.size,
                    selectedAudioTrack = audios.any { it.isSelected },
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                ),
            )
            logAudioInventory("tracks_changed", tracks)
            invalidateRememberedHintIfTrackMetadataChanged()
            invalidateTerminalFailureIfTrackMetadataChanged()
            applyRememberedCompatibleTrackIfNeeded()
            applyPendingTrackRecoveryIfNeeded()
            ensureAudioTrackSelectedIfNeeded()
            maybeConfirmTune("tracks_changed")
            listeners.forEach { it.onTracksChanged(audios, subs) }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMsMs: Long,
        ) {
            videoDecoderName = decoderName
            applyTuneSnapshot(liveTuneStateMachine.onVideoReady(SystemClock.elapsedRealtime()))
            maybeConfirmTune("video_decoder_initialized")
            notifyStateChanged()
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMsMs: Long,
        ) {
            audioDecoderName = decoderName
            maybeConfirmTune("audio_decoder_initialized")
            notifyStateChanged()
        }

        override fun onAudioPositionAdvancing(
            eventTime: AnalyticsListener.EventTime,
            playoutStartSystemTimeMs: Long,
        ) {
            applyTuneSnapshot(liveTuneStateMachine.onAudioReady())
            Log.d("AudioReady", "Audio playout advancing for ${currentPlaybackContext?.displayName.orEmpty()}")
            maybeConfirmTune("audio_position_advancing")
            notifyStateChanged()
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
        ) {
            applyTuneSnapshot(liveTuneStateMachine.onAudioReady())
            Log.d("AudioReady", "AudioTrack initialized for ${currentPlaybackContext?.displayName.orEmpty()}")
            maybeConfirmTune("audio_track_initialized")
            notifyStateChanged()
        }

        override fun onAudioSessionIdChanged(
            eventTime: AnalyticsListener.EventTime,
            audioSessionId: Int,
        ) {
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
            applyTuneSnapshot(liveTuneStateMachine.onAudioReady())
            Log.d(
                "AudioReady",
                "Audio session ready for ${currentPlaybackContext?.displayName.orEmpty()} id=$audioSessionId",
            )
            maybeConfirmTune("audio_session_id")
            notifyStateChanged()
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            applyTuneSnapshot(liveTuneStateMachine.onVideoReady(SystemClock.elapsedRealtime()))
            maybeConfirmTune("rendered_first_frame")
            notifyStateChanged()
        }
    }

    fun initialize() {
        createPlayer(preferSoftwareAudioDecoding = false)
        setAudioOutputPreferences(audioPassthroughEnabled, preferSurroundCodecs, liveAudioOutputMode)
    }

    private fun createPlayer(preferSoftwareAudioDecoding: Boolean) {
        // TiviMate-aligned: no custom audio processors for live TV — they add
        // variable latency that causes A/V sync drift and microstutter.
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildImageRenderers(out: java.util.ArrayList<Renderer>) {
                // Torve playback does not use Media3 image tracks. Leaving the
                // renderer out avoids a Media3 duplicate ImageOutputBuffer
                // release crash seen during rapid TV focus/navigation churn.
            }
        }
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if (preferSoftwareAudioDecoding) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                },
            )

        val selector = DefaultTrackSelector(context).also { defaultTrackSelector ->
            defaultTrackSelector.setParameters(
                defaultTrackSelector.parameters
                    .buildUpon()
                    .setExceedAudioConstraintsIfNecessary(true)
                    .build(),
            )
        }
        trackSelector = selector

        // Buffer size is user-configurable via the live playback menu.
        // Default is 50s (TiviMate baseline); lower values reduce live-edge
        // drift at the cost of less network-jitter absorption.
        // Floor at 2500ms — video decoders need at least one full GOP to render.
        val bufMs = liveBufferDurationMs.coerceAtLeast(2_500)
        val playbackStart = if (bufMs <= 5_000) bufMs else 1_000
        val playbackAfterRebuffer = if (bufMs <= 5_000) bufMs else 2_000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufMs, bufMs, playbackStart, playbackAfterRebuffer)
            .build()
        // TiviMate uses default extractor flags — no FLAG_ALLOW_NON_IDR_KEYFRAMES
        // which can cause decoding artifacts and frame timing instability.
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
            )
        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(selector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .also {
                it.addListener(exoListener)
                it.addAnalyticsListener(analyticsListener)
            }
        trackSelector = selector
        currentRendererPrefersSoftwareAudio = preferSoftwareAudioDecoding
    }

    private fun rebuildPlayerForAudioProfile(reason: String) {
        val previousPlayer = exoPlayer
        val mediaUri = currentUrl ?: previousPlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
        val resumePositionMs = previousPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val playWhenReady = previousPlayer?.playWhenReady ?: true

        runCatching {
            previousPlayer?.removeListener(exoListener)
            previousPlayer?.removeAnalyticsListener(analyticsListener)
            previousPlayer?.stop()
            previousPlayer?.release()
        }.onFailure { error ->
            Log.w(TAG, "Ignoring late ExoPlayer rebuild release during $reason", error)
        }

        createPlayer(preferSoftwareAudioDecoding = preferSoftwareAudioDecoding)
        applyEffectiveAudioOutputPreferences(
            passthroughEnabled = audioPassthroughEnabled,
            preferSurround = preferSurroundCodecs,
            outputMode = liveAudioOutputMode,
        )
        Log.i(
            TAG,
            "LiveTune rebuild: channel=${currentPlaybackContext?.displayName.orEmpty()} " +
                "reason=$reason softwareAudio=$preferSoftwareAudioDecoding",
        )
        mediaUri?.let { uri ->
            exoPlayer?.apply {
                setPlaybackSource(uri)
                prepare()
                if (resumePositionMs > 0L) {
                    runCatching {
                        if (isCurrentMediaItemSeekable || !isCurrentMediaItemLive) {
                            seekTo(resumePositionMs)
                        } else {
                            seekToDefaultPosition()
                        }
                    }
                }
                this.playWhenReady = playWhenReady
            }
        }
    }

    fun setLivePlaybackContext(channel: Channel?, honorRememberedHint: Boolean = true) {
        honorRememberedCompatibilityHint = honorRememberedHint
        currentPlaybackContext = channel?.let(LiveAudioPlaybackContext::fromChannel)
        rememberedCompatibilityHint = if (honorRememberedHint) {
            currentPlaybackContext?.let {
                LiveAudioCompatibilityStore.resolveHint(context, it)
            }
        } else {
            null
        }
        rememberedTrackHintApplied = false
        currentAudioSignature = null
        persistedTerminalFailureHint = null
    }

    private fun currentPreferenceKey(): String {
        return buildLiveAudioPreferencesKey(
            passthroughEnabled = userAudioPassthroughEnabled,
            preferSurround = userPreferSurroundCodecs,
            outputMode = userLiveAudioOutputMode,
        )
    }

    private fun applyRememberedCompatibilityHintIfAvailable() {
        rememberedCompatibilityHint = if (honorRememberedCompatibilityHint) {
            currentPlaybackContext?.let {
                LiveAudioCompatibilityStore.resolveHint(context, it)
            }
        } else {
            null
        }
        rememberedTrackHintApplied = false
        val hint = rememberedCompatibilityHint
        if (hint != null) {
            preferSoftwareAudioDecoding = hint.softwareAudioRequired
            Log.d(
                TAG,
                "Applying remembered live audio hint kind=${hint.recoveryKind} mode=${hint.outputMode} " +
                    "softwareAudio=${hint.softwareAudioRequired} channel=${currentPlaybackContext?.displayName.orEmpty()}",
            )
            applyEffectiveAudioOutputPreferences(
                passthroughEnabled = hint.passthroughEnabled,
                preferSurround = hint.preferSurround,
                outputMode = hint.liveAudioOutputMode(),
            )
        } else {
            preferSoftwareAudioDecoding = false
            applyEffectiveAudioOutputPreferences(
                passthroughEnabled = userAudioPassthroughEnabled,
                preferSurround = userPreferSurroundCodecs,
                outputMode = userLiveAudioOutputMode,
            )
        }
    }

    override fun play(url: String, externalSubtitles: List<ExternalSubtitle>) {
        // Stage subs for the next MediaItem build, then delegate to the
        // single-arg form so all the existing setup stays in one place.
        pendingExternalSubtitles = externalSubtitles
        play(url)
    }

    override fun setNextRequestHeaders(headers: Map<String, String>) {
        // Defensive copy — caller can reuse / mutate their map after
        // staging without affecting the captured value.
        pendingRequestHeaders = if (headers.isEmpty()) emptyMap() else headers.toMap()
    }

    // ExoPlayer attaches the staged headers via DefaultHttpDataSource —
    // see line ~1257 where they're consumed before MediaSource build.
    override val supportsRequestHeaders: Boolean = true

    override fun play(url: String) {
        currentUrl = url
        compatibleModeFallbackAttempted = false
        alternateTrackFallbackAttempted = false
        softwareAudioFallbackAttempted = false
        stereoPcmFallbackAttempted = false
        liveAudioRecoveryAttempts = 0
        currentAudioSignature = null
        pendingSuccessfulRecoveryKind = null
        pendingTrackRecoveryAfterReprepare = null
        attemptedAudioRecoveryKeys.clear()
        sessionIncompatibleRecoverySkipped = currentPlaybackContext?.let {
            LiveAudioCompatibilityStore.isSessionIncompatible(it, currentPreferenceKey())
        } == true
        persistedTerminalFailureHint = currentPlaybackContext?.let {
            LiveAudioCompatibilityStore.resolveTerminalFailure(
                context = context,
                playbackContext = it,
                preferencesKey = currentPreferenceKey(),
            )
        }
        if (persistedTerminalFailureHint != null) {
            sessionIncompatibleRecoverySkipped = true
            Log.i(
                TAG,
                "Short-circuiting Exo live audio recovery for known terminal failure " +
                    "channel=${currentPlaybackContext?.displayName.orEmpty()} " +
                    "mime=${persistedTerminalFailureHint?.selectedMime ?: "unknown"} " +
                    "recovery=${persistedTerminalFailureHint?.finalRecoveryMode ?: "unknown"}",
            )
        }
        applyRememberedCompatibilityHintIfAvailable()
        if (currentRendererPrefersSoftwareAudio != preferSoftwareAudioDecoding) {
            rebuildPlayerForAudioProfile("apply_playback_profile")
        }
        cancelAudioReadinessTimeout()
        applyTuneSnapshot(liveTuneStateMachine.begin(SystemClock.elapsedRealtime()))
        Log.i(
            "LiveTune",
            "Starting live tune engine=${LivePlayerEngineId.EXOPLAYER.storageValue} " +
                "channel=${currentPlaybackContext?.displayName ?: "unknown"} " +
                "streamKey=${currentPlaybackContext?.streamKey ?: "none"} " +
                "passthrough=$audioPassthroughEnabled " +
                "audioMode=${liveAudioOutputMode.storageValue} " +
                "preferSurround=$preferSurroundCodecs " +
                "softwareAudio=$preferSoftwareAudioDecoding " +
                "sessionRecoverySkipped=$sessionIncompatibleRecoverySkipped",
        )
        exoPlayer?.apply {
            stop()
            clearMediaItems()
            setPlaybackSource(url)
            prepare()
            playWhenReady = true
        }
        _state = _state.copy(isIdle = false, isBuffering = true)
        notifyStateChanged()
    }

    override fun pause() {
        exoPlayer?.playWhenReady = false
    }

    override fun resume() {
        exoPlayer?.playWhenReady = true
    }

    override fun stop() {
        cancelAudioReadinessTimeout()
        exoPlayer?.stop()
        persistedTerminalFailureHint = null
        _state = PlayerState()
        notifyStateChanged()
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    override fun seekRelative(deltaMs: Long) {
        val player = exoPlayer ?: return
        player.seekTo(player.currentPosition + deltaMs)
    }

    override fun setSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
    }

    override fun getSubtitleTracks(): List<TrackDescription> = currentSubtitleTracks
    override fun getAudioTracks(): List<TrackDescription> = currentAudioTracks

    override fun selectSubtitleTrack(id: Int) {
        val player = exoPlayer ?: return
        val textGroups = trackGroups.filter { it.type == C.TRACK_TYPE_TEXT }
        selectTrackInGroup(player, textGroups, id, C.TRACK_TYPE_TEXT)
    }

    override fun selectAudioTrack(id: Int) {
        val player = exoPlayer ?: return
        val audioGroups = trackGroups.filter { it.type == C.TRACK_TYPE_AUDIO }
        selectTrackInGroup(
            player = player,
            groups = audioGroups,
            trackIndex = id,
            trackType = C.TRACK_TYPE_AUDIO,
            restartPlayback = true,
            exceedRendererCapabilitiesIfNecessary = true,
        )
    }

    private fun selectTrackInGroup(
        player: ExoPlayer,
        groups: List<Tracks.Group>,
        trackIndex: Int,
        trackType: Int,
        restartPlayback: Boolean = false,
        exceedRendererCapabilitiesIfNecessary: Boolean = false,
    ) {
        var idx = 0
        for (group in groups) {
            for (i in 0 until group.length) {
                if (idx == trackIndex) {
                    applyTrackSelectionOverride(
                        player = player,
                        trackType = trackType,
                        group = group,
                        trackIndex = i,
                        exceedRendererCapabilitiesIfNecessary = exceedRendererCapabilitiesIfNecessary,
                    )
                    if (restartPlayback) {
                        restartPlaybackAfterAudioFallback(player, player.currentPosition)
                    }
                    return
                }
                idx++
            }
        }
    }

    override fun disableSubtitles() {
        val player = exoPlayer ?: return
        val selector = trackSelector
        if (selector != null) {
            selector.setParameters(
                selector.parameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build(),
            )
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
    }

    private fun attemptLiveAudioRecovery(
        player: ExoPlayer,
        error: PlaybackException,
        selectedAudio: SelectedAudioTrackSnapshot?,
        diagnostics: String,
    ): Boolean {
        val bestCandidate = player.findBestAudioRecoveryCandidate()
        if (sessionIncompatibleRecoverySkipped || liveAudioRecoveryAttempts >= MAX_LIVE_AUDIO_RECOVERY_ATTEMPTS) {
            logAudioFailureDiagnostics(
                player = player,
                error = error,
                selectedAudio = selectedAudio,
                diagnostics = diagnostics,
                fallbackPath = "recovery_skipped",
                alternateTrackExists = bestCandidate != null,
                alternateTrack = bestCandidate,
            )
            return false
        }

        val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
        when (
            AudioTrackRecoveryPlanner.nextAction(
                AudioRecoveryPlanInput(
                    alternateTrackAvailable = bestCandidate != null,
                    alternateTrackAttempted = alternateTrackFallbackAttempted,
                    compatibleModeAttempted = compatibleModeFallbackAttempted,
                    softwareAudioAttempted = softwareAudioFallbackAttempted,
                    stereoPcmAttempted = stereoPcmFallbackAttempted,
                    passthroughEnabled = audioPassthroughEnabled,
                    preferSurround = preferSurroundCodecs,
                    outputMode = liveAudioOutputMode,
                ),
            )
        ) {
            ExoAudioRecoveryAction.SWITCH_TRACK -> {
                val candidate = bestCandidate ?: return false
                alternateTrackFallbackAttempted = true
                liveAudioRecoveryAttempts += 1
                pendingSuccessfulRecoveryKind = LiveAudioRecoveryKind.COMPATIBLE_TRACK
                applyTuneSnapshot(
                    liveTuneStateMachine.onRecoveryStarted(
                        LiveAudioRecoveryMode.TRACK_RESELECT,
                        SystemClock.elapsedRealtime(),
                    ),
                )
                selectAudioRecoveryCandidate(player, candidate)
                restartPlaybackAfterAudioFallback(player, resumePositionMs)
                logAudioFailureDiagnostics(
                    player = player,
                    error = error,
                    selectedAudio = selectedAudio,
                    diagnostics = diagnostics,
                    fallbackPath = "switch_track:${candidate.mime ?: "unknown"}",
                    alternateTrackExists = true,
                    alternateTrack = candidate,
                )
                return true
            }
            ExoAudioRecoveryAction.DISABLE_PASSTHROUGH -> {
                compatibleModeFallbackAttempted = true
                liveAudioRecoveryAttempts += 1
                pendingSuccessfulRecoveryKind = LiveAudioRecoveryKind.COMPATIBLE_MODE
                pendingTrackRecoveryAfterReprepare = PendingTrackRecoveryAfterReprepare.COMPATIBLE_MODE
                applyTuneSnapshot(
                    liveTuneStateMachine.onRecoveryStarted(
                        LiveAudioRecoveryMode.PASSTHROUGH_OFF,
                        SystemClock.elapsedRealtime(),
                    ),
                )
                applyEffectiveAudioOutputPreferences(
                    passthroughEnabled = false,
                    preferSurround = false,
                    outputMode = LiveAudioOutputMode.PREFER_COMPATIBLE,
                )
                restartPlaybackAfterAudioFallback(player, resumePositionMs)
                logAudioFailureDiagnostics(
                    player = player,
                    error = error,
                    selectedAudio = selectedAudio,
                    diagnostics = diagnostics,
                    fallbackPath = "disable_passthrough_and_prefer_compatible",
                    alternateTrackExists = bestCandidate != null,
                    alternateTrack = bestCandidate,
                )
                return true
            }
            ExoAudioRecoveryAction.PREFER_SOFTWARE_AUDIO -> {
                softwareAudioFallbackAttempted = true
                liveAudioRecoveryAttempts += 1
                pendingSuccessfulRecoveryKind = LiveAudioRecoveryKind.SOFTWARE_AUDIO
                pendingTrackRecoveryAfterReprepare = PendingTrackRecoveryAfterReprepare.SOFTWARE_AUDIO
                preferSoftwareAudioDecoding = true
                applyTuneSnapshot(
                    liveTuneStateMachine.onRecoveryStarted(
                        LiveAudioRecoveryMode.SOFTWARE_AUDIO,
                        SystemClock.elapsedRealtime(),
                    ),
                )
                rebuildPlayerForAudioProfile("software_audio_recovery")
                logAudioFailureDiagnostics(
                    player = player,
                    error = error,
                    selectedAudio = selectedAudio,
                    diagnostics = diagnostics,
                    fallbackPath = "prefer_software_audio",
                    alternateTrackExists = bestCandidate != null,
                    alternateTrack = bestCandidate,
                )
                return true
            }
            ExoAudioRecoveryAction.FORCE_STEREO_PCM -> {
                stereoPcmFallbackAttempted = true
                liveAudioRecoveryAttempts += 1
                pendingSuccessfulRecoveryKind = LiveAudioRecoveryKind.STEREO_PCM
                pendingTrackRecoveryAfterReprepare = PendingTrackRecoveryAfterReprepare.STEREO_PCM
                applyTuneSnapshot(
                    liveTuneStateMachine.onRecoveryStarted(
                        LiveAudioRecoveryMode.STEREO_PCM,
                        SystemClock.elapsedRealtime(),
                    ),
                )
                applyEffectiveAudioOutputPreferences(
                    passthroughEnabled = false,
                    preferSurround = false,
                    outputMode = LiveAudioOutputMode.FORCE_STEREO_PCM,
                )
                restartPlaybackAfterAudioFallback(player, resumePositionMs)
                logAudioFailureDiagnostics(
                    player = player,
                    error = error,
                    selectedAudio = selectedAudio,
                    diagnostics = diagnostics,
                    fallbackPath = "force_stereo_pcm",
                    alternateTrackExists = bestCandidate != null,
                    alternateTrack = bestCandidate,
                )
                return true
            }
            ExoAudioRecoveryAction.MARK_FAILED -> Unit
        }

        logAudioFailureDiagnostics(
            player = player,
            error = error,
            selectedAudio = selectedAudio,
            diagnostics = diagnostics,
            fallbackPath = "exhausted",
            alternateTrackExists = bestCandidate != null,
            alternateTrack = bestCandidate,
        )
        return false
    }

    private fun shouldAttemptCompatibleModeFallback(): Boolean {
        return audioPassthroughEnabled ||
            preferSurroundCodecs ||
            liveAudioOutputMode == LiveAudioOutputMode.AUTO
    }

    private fun applyPendingTrackRecoveryIfNeeded() {
        val player = exoPlayer ?: return
        val pending = pendingTrackRecoveryAfterReprepare ?: return
        val candidate = player.findBestAudioRecoveryCandidate(includeCurrent = true)
        pendingTrackRecoveryAfterReprepare = null
        if (candidate == null) {
            Log.d(
                TAG,
                "No post-reprepare audio candidate found for ${currentPlaybackContext?.displayName.orEmpty()} after $pending",
            )
            return
        }

        val selected = player.selectedAudioTrackSnapshot()
        val candidateKey = candidate.recoveryKey()
        if (selected != null && selected.recoveryKey() == candidateKey) {
            return
        }

        selectAudioRecoveryCandidate(player, candidate)
        Log.i(
            TAG,
            "Post-reprepare audio reselection picked ${candidate.mime ?: "unknown"} " +
            "for ${currentPlaybackContext?.displayName.orEmpty()} after $pending",
        )
    }

    private fun ensureAudioTrackSelectedIfNeeded() {
        val player = exoPlayer ?: return
        if (currentAudioTracks.isEmpty() || currentAudioTracks.any { it.isSelected }) {
            return
        }
        val selectedSnapshot = player.selectedAudioTrackSnapshot()
        val candidate = player.findBestAudioRecoveryCandidate(includeCurrent = true)
            ?: player.findSoleAudioTrackFallback()
            ?: return
        if (candidate.recoveryKey() in attemptedAudioRecoveryKeys) {
            return
        }
        pendingSuccessfulRecoveryKind = LiveAudioRecoveryKind.COMPATIBLE_TRACK
        applyTuneSnapshot(
            liveTuneStateMachine.onSelectingAudio(SystemClock.elapsedRealtime()),
        )
        selectAudioRecoveryCandidate(player, candidate)
        restartPlaybackAfterAudioFallback(player, player.currentPosition)
        Log.i(
            "AudioSelect",
            "Auto-selecting live audio track ${candidate.mime ?: "unknown"} for " +
                "${currentPlaybackContext?.displayName.orEmpty()} current=${selectedSnapshot?.debugSummary() ?: "none"}",
        )
    }

    /**
     * When [findBestAudioRecoveryCandidate] rejects the only audio track (e.g. MPEG-L2
     * on Fire TV where isTrackSupported returns false), force-select it anyway.
     * ExoPlayer's decoder fallback (setEnableDecoderFallback=true) and its built-in
     * software MP2 decoder can still play the track.
     */
    private fun ExoPlayer.findSoleAudioTrackFallback(): SelectedAudioTrackSnapshot? {
        val snapshots = currentTracks.collectAudioSnapshots()
        if (snapshots.size != 1) return null
        val sole = snapshots.first()
        val knownMime = normalizeFormatKey(sole.mime) in KNOWN_LIVE_AUDIO_MIMES
        if (!knownMime) return null
        Log.i(
            TAG,
            "Sole audio track fallback: ${sole.mime ?: "unknown"} " +
                "for ${currentPlaybackContext?.displayName.orEmpty()} " +
                "(track unsupported by platform but known mime)",
        )
        return sole
    }

    private fun confirmSuccessfulRecoveryIfNeeded() {
        if (_state.liveTuneState != LiveTuneState.PLAYING_CONFIRMED) {
            return
        }
        if (_state.isAudioExpected && !_state.isAudioReady) {
            return
        }
        val recoveryKind = pendingSuccessfulRecoveryKind ?: return
        val playbackContext = currentPlaybackContext
        val selectedTrackHint = exoPlayer?.selectedAudioTrackSnapshot()?.toTrackHint()
        if (playbackContext != null) {
            rememberedCompatibilityHint = LiveAudioCompatibilityStore.rememberSuccessfulRecovery(
                context = context,
                playbackContext = playbackContext,
                passthroughEnabled = audioPassthroughEnabled,
                preferSurround = preferSurroundCodecs,
                outputMode = liveAudioOutputMode,
                recoveryKind = recoveryKind,
                engineId = LivePlayerEngineId.EXOPLAYER,
                preferredTrack = selectedTrackHint,
                audioSignature = currentAudioSignature,
                softwareAudioRequired = preferSoftwareAudioDecoding,
            )
            LiveAudioCompatibilityStore.clearSessionIncompatible(playbackContext)
            persistedTerminalFailureHint = null
        }
        pendingSuccessfulRecoveryKind = null
        pendingTrackRecoveryAfterReprepare = null
        rememberedTrackHintApplied = selectedTrackHint != null
        listeners.forEach { it.onError(recoveryMessageFor(recoveryKind)) }
    }

    private fun invalidateRememberedHintIfTrackMetadataChanged() {
        val playbackContext = currentPlaybackContext ?: return
        val hint = rememberedCompatibilityHint ?: return
        val signature = currentAudioSignature ?: return
        val storedSignature = hint.audioSignature ?: return
        if (storedSignature == signature) return

        Log.i(TAG, "Invalidating live audio hint for ${playbackContext.displayName} because track metadata changed")
        LiveAudioCompatibilityStore.invalidateHint(context, playbackContext)
        rememberedCompatibilityHint = null
        rememberedTrackHintApplied = false
        applyEffectiveAudioOutputPreferences(
            passthroughEnabled = userAudioPassthroughEnabled,
            preferSurround = userPreferSurroundCodecs,
            outputMode = userLiveAudioOutputMode,
        )
    }

    private fun invalidateTerminalFailureIfTrackMetadataChanged() {
        val playbackContext = currentPlaybackContext ?: return
        val terminalFailure = persistedTerminalFailureHint ?: return
        if (_state.liveTuneState == LiveTuneState.FALLBACK_ALLOWED) {
            return
        }
        val signature = currentAudioSignature ?: return
        val storedSignature = terminalFailure.audioSignature ?: return
        if (storedSignature == signature) return

        Log.i(
            TAG,
            "Clearing terminal live audio failure for ${playbackContext.displayName} because track metadata changed",
        )
        LiveAudioCompatibilityStore.clearTerminalFailure(context, playbackContext)
        persistedTerminalFailureHint = null
        sessionIncompatibleRecoverySkipped = LiveAudioCompatibilityStore.isSessionIncompatible(
            playbackContext,
            currentPreferenceKey(),
        )
    }

    private fun applyRememberedCompatibleTrackIfNeeded() {
        val player = exoPlayer ?: return
        val hint = rememberedCompatibilityHint ?: return
        val preferredTrack = hint.preferredTrack ?: return
        if (rememberedTrackHintApplied) {
            return
        }

        val currentlySelected = player.selectedAudioTrackSnapshot()
        if (currentlySelected != null && currentlySelected.matches(preferredTrack)) {
            rememberedTrackHintApplied = true
            return
        }

        val candidate = player.findTrackMatchingHint(preferredTrack) ?: return
        rememberedTrackHintApplied = true
        applyTuneSnapshot(liveTuneStateMachine.onSelectingAudio(SystemClock.elapsedRealtime()))
        applyTrackSelectionOverride(
            player = player,
            trackType = C.TRACK_TYPE_AUDIO,
            group = candidate.group,
            trackIndex = candidate.trackIndex,
            exceedRendererCapabilitiesIfNecessary = true,
        )
        Log.d(
            TAG,
            "Applying remembered compatible audio track ${candidate.mime ?: "unknown"} for ${currentPlaybackContext?.displayName.orEmpty()}",
        )
    }

    private fun disableAudioAfterCompatibilityFailure(
        player: ExoPlayer,
        error: PlaybackException,
        selectedAudio: SelectedAudioTrackSnapshot?,
        diagnostics: String,
    ) {
        cancelAudioReadinessTimeout()
        pendingSuccessfulRecoveryKind = null
        pendingTrackRecoveryAfterReprepare = null
        val playbackContext = currentPlaybackContext
        val failureReport = LiveAudioCompatibilityFailureReport(
            selectedEngine = LivePlayerEngineId.EXOPLAYER,
            playbackContext = playbackContext,
            selectedMime = selectedAudio?.mime,
            trackCount = currentAudioTracks.size,
            selectedTrack = selectedAudio?.toTrackHint(),
            passthroughEnabled = audioPassthroughEnabled,
            outputMode = liveAudioOutputMode,
            preferSurround = preferSurroundCodecs,
            recoveryAttempts = liveAudioRecoveryAttempts,
            diagnostics = diagnostics,
            fallbackAllowed = true,
            recoveryMode = _state.audioRecoveryMode,
            tuneState = _state.liveTuneState,
        )
        if (playbackContext != null) {
            if (rememberedCompatibilityHint != null) {
                LiveAudioCompatibilityStore.invalidateHint(context, playbackContext)
                rememberedCompatibilityHint = null
                rememberedTrackHintApplied = false
            }
            LiveAudioCompatibilityStore.markSessionIncompatible(playbackContext, currentPreferenceKey())
            LiveAudioCompatibilityStore.recordFailure(
                context = context,
                playbackContext = playbackContext,
                reason = diagnostics,
            )
            persistedTerminalFailureHint = LiveAudioCompatibilityStore.rememberTerminalFailure(
                context = context,
                playbackContext = playbackContext,
                preferencesKey = currentPreferenceKey(),
                selectedMime = selectedAudio?.mime,
                audioSignature = currentAudioSignature,
                finalRecoveryMode = _state.audioRecoveryMode.name,
                finalTuneState = _state.liveTuneState.name,
                reason = diagnostics,
            )
            sessionIncompatibleRecoverySkipped = true
        }
        applyTuneSnapshot(liveTuneStateMachine.onFailure(fallbackAllowed = true))
        logAudioFailureDiagnostics(
            player = player,
            error = error,
            selectedAudio = selectedAudio,
            diagnostics = diagnostics,
            fallbackPath = "disable_audio",
            alternateTrackExists = player.findBestAudioRecoveryCandidate() != null,
            alternateTrack = player.findBestAudioRecoveryCandidate(),
        )
        Log.w(TAG, "Audio incompatible after recovery attempts - disabling audio for video continuity")
        val selector = trackSelector
        if (selector != null) {
            selector.setParameters(
                selector.parameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build(),
            )
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        restartPlaybackAfterAudioFallback(player, player.currentPosition)
        onLiveAudioCompatibilityFailure?.invoke(failureReport)
        listeners.forEach { it.onError(INCOMPATIBLE_LIVE_AUDIO_MESSAGE) }
    }

    private fun recoveryMessageFor(kind: LiveAudioRecoveryKind): String {
        return when (kind) {
            LiveAudioRecoveryKind.MOBILE_REFERENCE_FIRST_PASS -> "Audio confirmed with the mobile-reference playback path."
            LiveAudioRecoveryKind.COMPATIBLE_MODE,
            LiveAudioRecoveryKind.STEREO_PCM -> COMPATIBLE_AUDIO_MODE_MESSAGE
            LiveAudioRecoveryKind.COMPATIBLE_TRACK -> COMPATIBLE_AUDIO_TRACK_MESSAGE
            LiveAudioRecoveryKind.SOFTWARE_AUDIO -> "Audio recovered with software decoding."
            LiveAudioRecoveryKind.ENGINE_FALLBACK -> COMPATIBLE_AUDIO_ENGINE_MESSAGE
        }
    }

    private fun restartPlaybackAfterAudioFallback(player: ExoPlayer, requestedPositionMs: Long) {
        player.prepare()
        val positionMs = requestedPositionMs.coerceAtLeast(0L)
        if (positionMs > 0L) {
            runCatching {
                if (player.isCurrentMediaItemSeekable || !player.isCurrentMediaItemLive) {
                    player.seekTo(positionMs)
                } else {
                    player.seekToDefaultPosition()
                }
            }
        }
        player.playWhenReady = true
    }

    private fun logAudioFailureDiagnostics(
        player: ExoPlayer,
        error: PlaybackException,
        selectedAudio: SelectedAudioTrackSnapshot?,
        diagnostics: String,
        fallbackPath: String,
        alternateTrackExists: Boolean,
        alternateTrack: SelectedAudioTrackSnapshot?,
    ) {
        val androidVersion = "${Build.VERSION.RELEASE ?: "?"} (SDK ${Build.VERSION.SDK_INT})"
        Log.w(
            TAG,
            "Live audio recovery: engine=${LivePlayerEngineId.EXOPLAYER.storageValue} " +
                "channel=${currentPlaybackContext?.displayName ?: "unknown"} " +
                "model=${Build.MODEL} android=$androidVersion " +
                "trackCount=${currentAudioTracks.size} " +
                "selectedMime=${selectedAudio?.mime ?: "unknown"} " +
                "selectedLanguage=${selectedAudio?.language ?: "und"} " +
                "selectedChannels=${selectedAudio?.channelCount ?: -1} " +
                "selectedBitrate=${selectedAudio?.bitrate ?: -1} " +
                "selectedLabel=${selectedAudio?.label ?: "n/a"} " +
                "passthrough=$audioPassthroughEnabled " +
                "audioMode=${liveAudioOutputMode.storageValue} " +
                "preferSurround=$preferSurroundCodecs " +
                "recoveryAttempts=$liveAudioRecoveryAttempts " +
                "alternateTrackExists=$alternateTrackExists " +
                "alternateTrack=${alternateTrack?.debugSummary() ?: "none"} " +
                "inventory=${player.audioInventorySummary()} " +
                "fallbackPath=$fallbackPath " +
                "causeChain=$diagnostics " +
                "errorCode=${error.errorCode}",
        )
    }

    private fun logAudioInventory(event: String, tracks: Tracks) {
        if (currentPlaybackContext == null) return
        Log.d(
            TAG,
            "Live audio inventory: engine=${LivePlayerEngineId.EXOPLAYER.storageValue} " +
                "event=$event channel=${currentPlaybackContext?.displayName.orEmpty()} " +
                "passthrough=$audioPassthroughEnabled mode=${liveAudioOutputMode.storageValue} " +
                "preferSurround=$preferSurroundCodecs inventory=${tracks.audioInventorySummary()}",
        )
    }

    private fun PlaybackException.buildCauseChainSummary(maxDepth: Int = 8): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < maxDepth) {
            val type = current::class.simpleName ?: current::class.java.simpleName
            val msg = current.message?.replace('\n', ' ')?.take(160).orEmpty()
            parts += "$type:$msg"
            current = current.cause
            depth++
        }
        return parts.joinToString(" <- ")
    }

    private fun ExoPlayer.setPlaybackSource(url: String) {
        val overriddenMediaSource = testMediaSourceFactory?.invoke(context, url)
        if (overriddenMediaSource != null) {
            // Test path — don't attach external subs so the fixture stays
            // reproducible. Tests targeting side-load behavior should build
            // the fixture MediaSource themselves.
            setMediaSource(overriddenMediaSource)
            // Consume so the next real play() starts clean.
            pendingExternalSubtitles = emptyList()
            pendingRequestHeaders = emptyMap()
            return
        }
        val subtitleConfigs = buildExternalSubtitleConfigurations(pendingExternalSubtitles)
        // Consume regardless of outcome so a failed build doesn't leak
        // subs into the next play() on an unrelated stream.
        pendingExternalSubtitles = emptyList()
        // Capture and consume headers before the MediaSource build so a
        // build failure doesn't carry them across.
        val headers = pendingRequestHeaders
        pendingRequestHeaders = emptyMap()
        val builder = MediaItem.Builder().setUri(url)
        if (subtitleConfigs.isNotEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigs)
        }
        if (currentPlaybackContext != null) {
            // Live IPTV: configure speed control so the player stays at the
            // live edge instead of drifting behind and triggering periodic
            // BEHIND_LIVE_WINDOW resyncs (visible as 5-second repeats).
            val liveConfig = MediaItem.LiveConfiguration.Builder()
                .setMaxPlaybackSpeed(1.04f)
                .setMinPlaybackSpeed(0.96f)
                .setTargetOffsetMs(5_000)
                .setMinOffsetMs(2_000)
                .setMaxOffsetMs(12_000)
                .build()
            builder.setLiveConfiguration(liveConfig)
        }
        if (headers.isNotEmpty()) {
            // Custom HTTP data source so `X-Torve-Lan-Auth` (and any
            // other staged header) is attached to every request the
            // player issues for this URL.
            val httpFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(false)
            val mediaSourceFactory = DefaultMediaSourceFactory(context)
                .setDataSourceFactory(httpFactory)
            setMediaSource(mediaSourceFactory.createMediaSource(builder.build()))
        } else {
            setMediaItem(builder.build())
        }
    }

    /**
     * Convert side-loaded [ExternalSubtitle] tracks into Media3's
     * [MediaItem.SubtitleConfiguration] format. Invalid/unparseable entries
     * are skipped rather than failing the whole play() call.
     */
    private fun buildExternalSubtitleConfigurations(
        subs: List<ExternalSubtitle>,
    ): List<MediaItem.SubtitleConfiguration> {
        if (subs.isEmpty()) return emptyList()
        return subs.mapNotNull { sub ->
            val parsedUri = runCatching { Uri.parse(sub.url) }.getOrNull() ?: return@mapNotNull null
            val mime = sub.mimeType ?: inferSubtitleMimeType(sub.url)
            MediaItem.SubtitleConfiguration.Builder(parsedUri)
                .setMimeType(mime)
                .apply {
                    sub.languageCode?.takeIf { it.isNotBlank() }?.let { setLanguage(it) }
                    sub.label?.takeIf { it.isNotBlank() }?.let { setLabel(it) }
                    setSelectionFlags(C.SELECTION_FLAG_DEFAULT.inv().and(0))
                }
                .build()
        }
    }

    private fun inferSubtitleMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".vtt") -> MimeTypes.TEXT_VTT
            lower.contains(".srt") -> MimeTypes.APPLICATION_SUBRIP
            lower.contains(".ttml") || lower.contains(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP  // default for extensionless Stremio/OS URLs
        }
    }

    private fun ExoPlayer.selectedAudioTrackSnapshot(): SelectedAudioTrackSnapshot? {
        var audioGroupIndex = 0
        currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSelected(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                return SelectedAudioTrackSnapshot(
                    group = group,
                    groupIndex = audioGroupIndex,
                    trackIndex = trackIndex,
                    mime = format.sampleMimeType,
                    language = format.language,
                    channelCount = format.channelCount,
                    bitrate = format.bitrate,
                    label = format.label,
                )
            }
            audioGroupIndex++
        }
        return null
    }

    private fun SelectedAudioTrackSnapshot.toTrackHint(): LiveAudioTrackHint {
        return LiveAudioTrackHint(
            label = label,
            language = language,
            formatKey = normalizeFormatKey(mime),
            channelCount = channelCount.takeIf { it > 0 },
            groupIndex = groupIndex,
            trackIndex = trackIndex,
        )
    }

    private fun SelectedAudioTrackSnapshot.matches(trackHint: LiveAudioTrackHint): Boolean {
        if (trackHint.groupIndex != null && trackHint.trackIndex != null) {
            return trackHint.groupIndex == groupIndex && trackHint.trackIndex == trackIndex
        }
        val normalizedMime = normalizeFormatKey(mime)
        return normalizedMime == trackHint.formatKey &&
            language.equals(trackHint.language, ignoreCase = true) &&
            label.equals(trackHint.label, ignoreCase = true) &&
            (trackHint.channelCount == null || trackHint.channelCount == channelCount)
    }

    private fun SelectedAudioTrackSnapshot.recoveryKey(): String {
        return listOf(
            groupIndex.toString(),
            trackIndex.toString(),
            normalizeFormatKey(mime).orEmpty(),
            audioPassthroughEnabled.toString(),
            liveAudioOutputMode.storageValue,
        ).joinToString(separator = ":")
    }

    private fun SelectedAudioTrackSnapshot.debugSummary(): String {
        return listOf(
            "mime=${mime ?: "unknown"}",
            "lang=${language ?: "und"}",
            "channels=$channelCount",
            "bitrate=$bitrate",
            "label=${label ?: "n/a"}",
            "group=$groupIndex",
            "track=$trackIndex",
        ).joinToString(separator = ",")
    }

    private fun selectAudioRecoveryCandidate(
        player: ExoPlayer,
        candidate: SelectedAudioTrackSnapshot,
    ) {
        attemptedAudioRecoveryKeys += candidate.recoveryKey()
        applyTrackSelectionOverride(
            player = player,
            trackType = C.TRACK_TYPE_AUDIO,
            group = candidate.group,
            trackIndex = candidate.trackIndex,
            exceedRendererCapabilitiesIfNecessary = true,
            preferredAudioMime = candidate.mime,
            maxAudioChannelCount = candidate.channelCount.takeIf { it > 0 },
        )
    }

    private fun applyTrackSelectionOverride(
        player: ExoPlayer,
        trackType: Int,
        group: Tracks.Group,
        trackIndex: Int,
        exceedRendererCapabilitiesIfNecessary: Boolean = false,
        preferredAudioMime: String? = null,
        maxAudioChannelCount: Int? = null,
    ) {
        val selector = trackSelector
        if (selector != null) {
            val builder = selector.parameters
                .buildUpon()
                .setTrackTypeDisabled(trackType, false)
                .setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, trackIndex),
                )
            if (exceedRendererCapabilitiesIfNecessary) {
                builder.setExceedRendererCapabilitiesIfNecessary(true)
            }
            if (trackType == C.TRACK_TYPE_AUDIO) {
                builder.setExceedAudioConstraintsIfNecessary(true)
                preferredAudioMime?.let { mime -> builder.setPreferredAudioMimeTypes(mime) }
                maxAudioChannelCount?.let { channelCount -> builder.setMaxAudioChannelCount(channelCount) }
            }
            selector.setParameters(builder.build())
            return
        }

        val builder = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, trackIndex),
            )
        preferredAudioMime?.let { mime -> builder.setPreferredAudioMimeTypes(mime) }
        maxAudioChannelCount?.let { channelCount -> builder.setMaxAudioChannelCount(channelCount) }
        player.trackSelectionParameters = builder.build()
    }

    private fun ExoPlayer.findBestAudioRecoveryCandidate(
        includeCurrent: Boolean = false,
    ): SelectedAudioTrackSnapshot? {
        val selected = selectedAudioTrackSnapshot()
        val snapshots = currentTracks.collectAudioSnapshots()
        val candidates = snapshots.map { snapshot -> snapshot.toSelectionCandidate() }
        val excludedIds = if (includeCurrent) {
            emptySet()
        } else {
            attemptedAudioRecoveryKeys.toSet()
        }
        val bestCandidate = LiveTrackSelectionPolicy.selectBestCandidate(
            candidates = candidates,
            cachedTrack = rememberedCompatibilityHint?.preferredTrack,
            currentSelectionId = selected?.recoveryKey(),
            passthroughEnabled = audioPassthroughEnabled,
            preferSurround = preferSurroundCodecs,
            outputMode = liveAudioOutputMode,
            excludeIds = excludedIds,
        ) ?: return null
        return snapshots.firstOrNull { it.recoveryKey() == bestCandidate.id }
            ?.takeIf { includeCurrent || it.recoveryKey() !in attemptedAudioRecoveryKeys }
    }

    private fun ExoPlayer.findTrackMatchingHint(trackHint: LiveAudioTrackHint): SelectedAudioTrackSnapshot? {
        var bestCandidate: SelectedAudioTrackSnapshot? = null
        var bestScore = 0
        var audioGroupIndex = 0
        currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val candidate = SelectedAudioTrackSnapshot(
                    group = group,
                    groupIndex = audioGroupIndex,
                    trackIndex = trackIndex,
                    mime = format.sampleMimeType,
                    language = format.language,
                    channelCount = format.channelCount,
                    bitrate = format.bitrate,
                    label = format.label,
                )
                val score = candidate.matchScore(trackHint)
                if (score > bestScore) {
                    bestCandidate = candidate
                    bestScore = score
                }
            }
            audioGroupIndex++
        }
        return bestCandidate.takeIf { bestScore >= 3 }
    }

    private fun SelectedAudioTrackSnapshot.matchScore(trackHint: LiveAudioTrackHint): Int {
        var score = 0
        if (trackHint.groupIndex == groupIndex && trackHint.trackIndex == trackIndex) score += 12
        if (normalizeFormatKey(mime) == trackHint.formatKey) score += 6
        if (language.equals(trackHint.language, ignoreCase = true)) score += 3
        if (label.equals(trackHint.label, ignoreCase = true)) score += 2
        if (trackHint.channelCount != null && channelCount == trackHint.channelCount) score += 1
        return score
    }

    private fun SelectedAudioTrackSnapshot.recoveryScore(
        currentSelection: SelectedAudioTrackSnapshot?,
    ): Int {
        val normalizedMime = normalizeFormatKey(mime)
        var score = 0
        when {
            group.isTrackSupported(trackIndex) -> score += 500
            group.isTrackSupported(trackIndex, true) -> score += 320
            else -> score -= 280
        }
        score += mimePreferenceScore(normalizedMime)

        if (channelCount in 1..2) {
            score += when (liveAudioOutputMode) {
                LiveAudioOutputMode.FORCE_STEREO_PCM -> 90
                LiveAudioOutputMode.PREFER_COMPATIBLE -> 45
                LiveAudioOutputMode.AUTO -> if (audioPassthroughEnabled || preferSurroundCodecs) 6 else 28
            }
        } else if (channelCount > 2) {
            score += when (liveAudioOutputMode) {
                LiveAudioOutputMode.FORCE_STEREO_PCM -> -140
                LiveAudioOutputMode.PREFER_COMPATIBLE -> -50
                LiveAudioOutputMode.AUTO -> if (audioPassthroughEnabled || preferSurroundCodecs) 40 else -18
            }
        }

        if (bitrate in 1..320_000) score += 8
        if (bitrate > 640_000) score -= 8
        if (label.isNullOrBlank()) score -= 2
        if (language.isNullOrBlank()) score -= 1

        val isCurrentSelection = currentSelection != null &&
            currentSelection.groupIndex == groupIndex &&
            currentSelection.trackIndex == trackIndex
        if (isCurrentSelection) score -= 60

        return score
    }

    private fun mimePreferenceScore(normalizedMime: String?): Int {
        return when (normalizedMime) {
            "audio/mp4a-latm",
            "audio/aac" -> 180
            "audio/opus" -> 170
            "audio/mpeg-l2",
            "audio/mp2",
            "mp2",
            "mpeg-l2" -> 167
            "audio/mpeg" -> 165
            "audio/raw" -> 160
            "audio/flac" -> 150
            "audio/vorbis" -> 145
            "audio/ac4" -> if (audioPassthroughEnabled) 138 else 120
            "audio/eac3",
            "audio/eac3-joc" -> when (liveAudioOutputMode) {
                LiveAudioOutputMode.FORCE_STEREO_PCM -> 26
                LiveAudioOutputMode.PREFER_COMPATIBLE -> 72
                LiveAudioOutputMode.AUTO -> if (audioPassthroughEnabled || preferSurroundCodecs) 156 else 92
            }
            "audio/ac3" -> when (liveAudioOutputMode) {
                LiveAudioOutputMode.FORCE_STEREO_PCM -> 22
                LiveAudioOutputMode.PREFER_COMPATIBLE -> 68
                LiveAudioOutputMode.AUTO -> if (audioPassthroughEnabled || preferSurroundCodecs) 150 else 84
            }
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            "audio/true-hd",
            "audio/mlp" -> when (liveAudioOutputMode) {
                LiveAudioOutputMode.FORCE_STEREO_PCM -> -40
                LiveAudioOutputMode.PREFER_COMPATIBLE -> 18
                LiveAudioOutputMode.AUTO -> if (audioPassthroughEnabled || preferSurroundCodecs) 170 else 24
            }
            null -> -12
            else -> if (isCompatibleAudioMime(normalizedMime)) 96 else 8
        }
    }

    private fun Tracks.buildAudioSignature(): String {
        val signatures = mutableListOf<String>()
        groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                signatures += listOf(
                    normalizeFormatKey(format.sampleMimeType).orEmpty(),
                    format.language?.trim()?.lowercase(Locale.ROOT).orEmpty(),
                    format.channelCount.toString(),
                    format.label?.trim()?.lowercase(Locale.ROOT).orEmpty(),
                ).joinToString(separator = ":")
            }
        }
        return signatures.sorted().joinToString(separator = "|").take(512)
    }

    private fun Tracks.audioInventorySummary(): String {
        return collectAudioSnapshots()
            .joinToString(separator = " | ") { snapshot ->
                val selectedMarker = if (snapshot.group.isTrackSelected(snapshot.trackIndex)) "*" else ""
                "$selectedMarker${snapshot.debugSummary()}"
            }
            .take(1024)
    }

    private fun ExoPlayer.audioInventorySummary(): String = currentTracks.audioInventorySummary()

    private fun Tracks.collectAudioSnapshots(): List<SelectedAudioTrackSnapshot> {
        val snapshots = mutableListOf<SelectedAudioTrackSnapshot>()
        var audioGroupIndex = 0
        groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                snapshots += SelectedAudioTrackSnapshot(
                    group = group,
                    groupIndex = audioGroupIndex,
                    trackIndex = trackIndex,
                    mime = format.sampleMimeType,
                    language = format.language,
                    channelCount = format.channelCount,
                    bitrate = format.bitrate,
                    label = format.label,
                )
            }
            audioGroupIndex++
        }
        return snapshots
    }

    private fun SelectedAudioTrackSnapshot.toSelectionCandidate(): LiveAudioTrackCandidate {
        return LiveAudioTrackCandidate(
            id = recoveryKey(),
            language = language,
            label = label,
            formatKey = normalizeFormatKey(mime),
            channelCount = channelCount.takeIf { it > 0 },
            bitrate = bitrate.takeIf { it > 0 },
            supported = group.isTrackSupported(trackIndex, true) ||
                normalizeFormatKey(mime) in KNOWN_LIVE_AUDIO_MIMES,
            selected = group.isTrackSelected(trackIndex),
            isDefault = group.getTrackFormat(trackIndex).selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
            groupIndex = groupIndex,
            trackIndex = trackIndex,
        )
    }

    private fun normalizeFormatKey(value: String?): String? {
        return value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    }

    private fun isCompatibleAudioMime(mime: String): Boolean {
        val normalized = mime.lowercase(Locale.ROOT)
        return normalized in COMPATIBLE_AUDIO_MIMES
    }

    private fun isAc3FamilyMime(mime: String?): Boolean {
        val normalized = mime?.lowercase(Locale.ROOT) ?: return false
        return normalized in AC3_FAMILY_MIMES
    }

    override fun release() {
        cancelAudioReadinessTimeout()
        val player = exoPlayer ?: return
        exoPlayer = null
        runCatching {
            player.removeListener(exoListener)
            player.removeAnalyticsListener(analyticsListener)
            player.stop()
            player.release()
        }.onFailure { error ->
            Log.w(TAG, "Ignoring duplicate or late ExoPlayer release", error)
        }
        trackSelector = null
        onCodecError = null
        onLiveAudioCompatibilityFailure = null
        persistedTerminalFailureHint = null
    }

    override fun addListener(listener: PlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: PlayerListener) {
        listeners.remove(listener)
    }

    override fun setAudioDelay(delayMs: Int) {
        delayProcessor.setDelayMs(delayMs)
    }

    override fun getAudioDelay(): Int = delayProcessor.getDelayMs()

    override fun getAudioSessionId(): Int = exoPlayer?.audioSessionId ?: 0

    fun setAudioOutputPreferences(
        passthroughEnabled: Boolean,
        preferSurround: Boolean,
        outputMode: LiveAudioOutputMode = userLiveAudioOutputMode,
    ) {
        userAudioPassthroughEnabled = passthroughEnabled
        userPreferSurroundCodecs = preferSurround
        userLiveAudioOutputMode = outputMode
        rememberedCompatibilityHint = null
        rememberedTrackHintApplied = false
        pendingSuccessfulRecoveryKind = null
        pendingTrackRecoveryAfterReprepare = null
        preferSoftwareAudioDecoding = false
        persistedTerminalFailureHint = null
        currentPlaybackContext?.let { LiveAudioCompatibilityStore.clearSessionIncompatible(it) }
        applyEffectiveAudioOutputPreferences(
            passthroughEnabled = passthroughEnabled,
            preferSurround = preferSurround,
            outputMode = outputMode,
        )
    }

    private fun applyEffectiveAudioOutputPreferences(
        passthroughEnabled: Boolean,
        preferSurround: Boolean,
        outputMode: LiveAudioOutputMode = liveAudioOutputMode,
    ) {
        audioPassthroughEnabled = passthroughEnabled
        preferSurroundCodecs = preferSurround
        liveAudioOutputMode = outputMode

        val player = exoPlayer ?: return
        val surroundPreferredMimes = arrayOf(
            "audio/true-hd",
            "audio/vnd.dts.hd",
            "audio/vnd.dts",
            "audio/eac3-joc",
            "audio/eac3",
            "audio/ac3",
            "audio/ac4",
            "audio/mp4a-latm",
            "audio/aac",
            "audio/opus",
            "audio/mpeg-L2",
            "audio/mpeg",
        )
        val stereoFirstMimes = arrayOf(
            "audio/mp4a-latm",
            "audio/aac",
            "audio/opus",
            "audio/mpeg-L2",
            "audio/mpeg",
            "audio/ac4",
            "audio/eac3",
            "audio/ac3",
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            "audio/true-hd",
        )
        val compatibleFirstMimes = arrayOf(
            "audio/mp4a-latm",
            "audio/aac",
            "audio/opus",
            "audio/mpeg-L2",
            "audio/mpeg",
            "audio/raw",
            "audio/flac",
            "audio/vorbis",
            "audio/ac4",
            "audio/eac3",
            "audio/ac3",
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            "audio/true-hd",
        )

        val forceStereoMimes = arrayOf(
            "audio/mp4a-latm",
            "audio/aac",
            "audio/opus",
            "audio/mpeg-L2",
            "audio/mpeg",
            "audio/raw",
            "audio/flac",
            "audio/vorbis",
        )

        val preferredMimes = when (outputMode) {
            LiveAudioOutputMode.AUTO -> {
                if (passthroughEnabled || preferSurround) surroundPreferredMimes else stereoFirstMimes
            }
            LiveAudioOutputMode.PREFER_COMPATIBLE -> compatibleFirstMimes
            LiveAudioOutputMode.FORCE_STEREO_PCM -> forceStereoMimes
        }

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setPreferredAudioMimeTypes(
                *preferredMimes,
            )
            .setMaxAudioChannelCount(
                when (outputMode) {
                    LiveAudioOutputMode.AUTO -> if (passthroughEnabled || preferSurround) Int.MAX_VALUE else 2
                    LiveAudioOutputMode.PREFER_COMPATIBLE -> 2
                    LiveAudioOutputMode.FORCE_STEREO_PCM -> 2
                },
            )
            .build()
    }

    fun getExoPlayer(): ExoPlayer? = exoPlayer

    internal fun getPlaybackRuntimeInfo(): PlaybackRuntimeInfo {
        val player = exoPlayer
        val videoFormat = player?.videoFormat
        val audioFormat = player?.audioFormat
        val selectedAudioTrack = currentAudioTracks.firstOrNull { it.isSelected }
        val selectedSubtitleTrack = currentSubtitleTracks.firstOrNull { it.isSelected }
        return PlaybackRuntimeInfo(
            engineId = LivePlayerEngineId.EXOPLAYER,
            videoCodec = normalizeFormatKey(videoFormat?.sampleMimeType) ?: videoFormat?.codecs,
            audioCodec = normalizeFormatKey(audioFormat?.sampleMimeType) ?: audioFormat?.codecs,
            videoDecoderName = videoDecoderName,
            audioDecoderName = audioDecoderName,
            videoDecoderKind = inferDecoderKind(videoDecoderName),
            audioDecoderKind = inferDecoderKind(audioDecoderName),
            resolutionLabel = videoFormat?.let { format ->
                val width = format.width.takeIf { it > 0 } ?: return@let null
                val height = format.height.takeIf { it > 0 } ?: return@let null
                "${width}x${height}"
            },
            frameRate = videoFormat?.frameRate?.takeIf { it > 0f },
            selectedAudioTrack = selectedAudioTrack,
            selectedSubtitleTrack = selectedSubtitleTrack,
            outputMode = liveAudioOutputMode,
            passthroughEnabled = audioPassthroughEnabled,
            preferSurround = preferSurroundCodecs,
            liveTuneState = _state.liveTuneState,
            audioRecoveryMode = _state.audioRecoveryMode,
            engineFallbackAllowed = _state.isEngineFallbackAllowed,
            fallbackFromHardwareDefault = liveAudioRecoveryAttempts > 0 ||
                inferDecoderKind(videoDecoderName) == DecoderKind.SOFTWARE ||
                inferDecoderKind(audioDecoderName) == DecoderKind.SOFTWARE,
        )
    }

    fun updatePosition() {
        val player = exoPlayer ?: return
        _state = _state.copy(positionMs = player.currentPosition)
    }

    private fun applyTuneSnapshot(snapshot: LiveTuneSnapshot) {
        _state = _state.copy(
            liveTuneState = snapshot.state,
            audioRecoveryMode = snapshot.recoveryMode,
            isAudioExpected = snapshot.audioExpected,
            isAudioReady = snapshot.audioReady,
            isVideoReady = snapshot.videoReady,
            isEngineFallbackAllowed = snapshot.fallbackAllowed,
        )
    }

    private fun maybeConfirmTune(trigger: String) {
        val player = exoPlayer ?: return
        if (_state.isEngineFallbackAllowed) return
        if (player.playbackState != Player.STATE_READY) return
        val snapshot = liveTuneStateMachine.snapshot()
        if (snapshot.videoReady && (!snapshot.audioExpected || snapshot.audioReady)) {
            cancelAudioReadinessTimeout()
            applyTuneSnapshot(liveTuneStateMachine.onPlaybackReady(SystemClock.elapsedRealtime()))
            Log.d(
                "LiveTune",
                "Confirmed live tune channel=${currentPlaybackContext?.displayName.orEmpty()} " +
                    "streamKey=${currentPlaybackContext?.streamKey ?: "none"} trigger=$trigger " +
                    "audioExpected=${snapshot.audioExpected} audioReady=${snapshot.audioReady}",
            )
            confirmSuccessfulRecoveryIfNeeded()
            return
        }
        if (snapshot.videoReady && snapshot.audioExpected && !snapshot.audioReady) {
            scheduleAudioReadinessTimeout()
        }
    }

    private fun scheduleAudioReadinessTimeout() {
        cancelAudioReadinessTimeout()
        mainHandler.postDelayed(audioReadinessTimeoutRunnable, AUDIO_READINESS_TIMEOUT_MS)
    }

    private fun cancelAudioReadinessTimeout() {
        mainHandler.removeCallbacks(audioReadinessTimeoutRunnable)
    }

    private fun handleAudioReadinessTimeout(reason: String) {
        val player = exoPlayer ?: return
        val now = SystemClock.elapsedRealtime()
        if (!liveTuneStateMachine.isAudioReadinessTimedOut(now)) return
        val selectedAudio = player.selectedAudioTrackSnapshot()
        val diagnostics = buildString {
            append(reason)
            append("|streamKey=")
            append(currentPlaybackContext?.streamKey ?: "none")
            append("|selected=")
            append(selectedAudio?.debugSummary() ?: "none")
        }
        Log.w(
            "AudioRecover",
            "Bounded live audio readiness expired for ${currentPlaybackContext?.displayName.orEmpty()} diagnostics=$diagnostics",
        )
        val timeoutError = PlaybackException(
            "Bounded live audio readiness timeout",
            null,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
        if (!attemptLiveAudioRecovery(player, timeoutError, selectedAudio, diagnostics)) {
            disableAudioAfterCompatibilityFailure(
                player = player,
                error = timeoutError,
                selectedAudio = selectedAudio,
                diagnostics = diagnostics,
            )
        }
    }

    private fun notifyStateChanged() {
        listeners.forEach { it.onStateChanged(_state) }
    }

    /**
     * Change the live buffer size.  The new value takes effect on the next
     * channel tune — we intentionally do NOT rebuild the player mid-stream
     * because that tears down the video surface and causes a visible glitch.
     */
    fun setLiveBufferSize(durationMs: Int) {
        liveBufferDurationMs = durationMs
        Log.i(TAG, "Buffer size updated to ${durationMs}ms — will apply on next tune")
    }

    companion object {
        internal const val DEFAULT_LIVE_BUFFER_MS = 50_000
        private const val TAG = "ExoPlayerEngine"
        private const val MAX_LIVE_AUDIO_RECOVERY_ATTEMPTS = 4
        private const val AUDIO_READINESS_TIMEOUT_MS = 900L
        private const val MIN_AUDIO_RECOVERY_SCORE = 40
        private const val COMPATIBLE_AUDIO_MODE_MESSAGE =
            "Audio recovered by changing the audio mode."
        private const val COMPATIBLE_AUDIO_TRACK_MESSAGE =
            "Switched to a compatible audio track."
        private const val COMPATIBLE_AUDIO_ENGINE_MESSAGE =
            "Audio recovered by changing the playback engine."
        private const val INCOMPATIBLE_LIVE_AUDIO_MESSAGE =
            "Audio unavailable on this device for this channel."
        private val AC3_FAMILY_MIMES = setOf(
            "audio/ac3",
            "audio/eac3",
            "audio/eac3-joc",
        )
        private val COMPATIBLE_AUDIO_MIMES = setOf(
            "audio/mp4a-latm",
            "audio/aac",
            "audio/mpeg-l2",
            "audio/mp2",
            "audio/mpeg",
            "audio/opus",
            "audio/vorbis",
            "audio/raw",
            "audio/flac",
            "audio/ac4",
            "audio/amr-nb",
            "audio/amr-wb",
        )
        private val KNOWN_LIVE_AUDIO_MIMES = COMPATIBLE_AUDIO_MIMES + AC3_FAMILY_MIMES + setOf(
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            "audio/true-hd",
            "audio/mlp",
        )
    }

    private fun inferDecoderKind(decoderName: String?): DecoderKind {
        val normalized = decoderName?.trim()?.lowercase(Locale.ROOT) ?: return DecoderKind.UNKNOWN
        return when {
            normalized.contains("omx.google") ||
                normalized.contains("c2.android") ||
                normalized.contains("ffmpeg") ||
                normalized.contains("sw") ||
                normalized.contains("software") -> DecoderKind.SOFTWARE
            else -> DecoderKind.HARDWARE
        }
    }
}
