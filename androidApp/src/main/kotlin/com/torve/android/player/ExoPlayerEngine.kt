package com.torve.android.player

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.torve.domain.model.Channel
import com.torve.domain.player.LiveAudioOutputMode
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

    private var currentSubtitleTracks = listOf<TrackDescription>()
    private var currentAudioTracks = listOf<TrackDescription>()
    private var trackGroups = listOf<Tracks.Group>()
    private val delayProcessor = DelayAudioProcessor()
    val equalizerProcessor = EqualizerAudioProcessor()

    /** Set by the player screen to enable codec-error fallback at the stream level. */
    var onCodecError: ((errorCode: Int) -> Unit)? = null

    /** Prevents infinite loop if audio fallback also fails. */
    private var audioFallbackAttempted = false
    private var audioPassthroughEnabled = false
    private var preferSurroundCodecs = true
    private var liveAudioOutputMode = LiveAudioOutputMode.PREFER_COMPATIBLE
    private var userAudioPassthroughEnabled = false
    private var userPreferSurroundCodecs = true
    private var userLiveAudioOutputMode = LiveAudioOutputMode.PREFER_COMPATIBLE
    private var ac3PassthroughFallbackAttempted = false
    private var ac3TrackFallbackAttempted = false
    private var ac3StereoFallbackAttempted = false
    private var liveAudioRecoveryAttempts = 0
    private var currentPlaybackContext: LiveAudioPlaybackContext? = null
    private var rememberedCompatibilityHint: LiveAudioCompatibilityHint? = null
    private var rememberedTrackHintApplied = false
    private var currentAudioSignature: String? = null
    private var pendingSuccessfulRecoveryKind: LiveAudioRecoveryKind? = null
    private var sessionIncompatibleRecoverySkipped = false

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
            notifyStateChanged()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state = _state.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                isIdle = playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED,
            )
            if (playbackState == Player.STATE_READY) {
                _state = _state.copy(durationMs = exoPlayer?.duration ?: 0)
                confirmSuccessfulRecoveryIfNeeded()
            }
            notifyStateChanged()
        }

        override fun onPlayerError(error: PlaybackException) {
            val isCodecError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED

            if (isCodecError) {
                val player = exoPlayer
                val msg = error.message.orEmpty()
                val isAudioCodecError = msg.contains("AudioRenderer", ignoreCase = true) ||
                    msg.contains("audio/eac3", ignoreCase = true) ||
                    msg.contains("audio/ac3", ignoreCase = true) ||
                    msg.contains("audio/dts", ignoreCase = true) ||
                    msg.contains("audio/truehd", ignoreCase = true) ||
                    msg.contains("audio/mlp", ignoreCase = true)

                if (isAudioCodecError) {
                    val selectedAudio = player?.selectedAudioTrackSnapshot()
                    if (player != null && isAc3RendererFailure(error, selectedAudio?.mime)) {
                        if (attemptAc3Recovery(player, error, selectedAudio)) {
                            return
                        }
                        disableAudioAfterCompatibilityFailure(player)
                        return
                    }

                    if (!audioFallbackAttempted) {
                        Log.w(TAG, "Audio codec error - attempting fallback")
                        audioFallbackAttempted = true
                        handleAudioCodecError()
                    } else {
                        val targetPlayer = player ?: return
                        disableAudioAfterCompatibilityFailure(targetPlayer)
                    }
                    return
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
                            ),
                        )
                        C.TRACK_TYPE_AUDIO -> audios.add(
                            TrackDescription(
                                id = audios.size,
                                label = label,
                                language = format.language,
                                isSelected = isSelected,
                            ),
                        )
                    }
                }
            }

            trackGroups = groups
            currentSubtitleTracks = subs
            currentAudioTracks = audios
            currentAudioSignature = tracks.buildAudioSignature()
            invalidateRememberedHintIfTrackMetadataChanged()
            applyRememberedCompatibleTrackIfNeeded()
            listeners.forEach { it.onTracksChanged(audios, subs) }
        }
    }

    fun initialize() {
        val processors = arrayOf(equalizerProcessor, delayProcessor)
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): DefaultAudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(processors)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }.setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .build()

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .also { it.addListener(exoListener) }
        setAudioOutputPreferences(audioPassthroughEnabled, preferSurroundCodecs, liveAudioOutputMode)
    }

    fun setLivePlaybackContext(channel: Channel?) {
        currentPlaybackContext = channel?.let(LiveAudioPlaybackContext::fromChannel)
        rememberedCompatibilityHint = currentPlaybackContext?.let {
            LiveAudioCompatibilityStore.resolveHint(context, it)
        }
        rememberedTrackHintApplied = false
        currentAudioSignature = null
    }

    private fun currentPreferenceKey(): String {
        return buildString {
            append(userAudioPassthroughEnabled)
            append('|')
            append(userPreferSurroundCodecs)
            append('|')
            append(userLiveAudioOutputMode.storageValue)
        }
    }

    private fun applyRememberedCompatibilityHintIfAvailable() {
        rememberedCompatibilityHint = currentPlaybackContext?.let {
            LiveAudioCompatibilityStore.resolveHint(context, it)
        }
        rememberedTrackHintApplied = false
        val hint = rememberedCompatibilityHint
        if (hint != null) {
            Log.d(
                TAG,
                "Applying remembered live audio hint kind=${hint.recoveryKind} mode=${hint.outputMode} channel=${currentPlaybackContext?.displayName.orEmpty()}",
            )
            applyEffectiveAudioOutputPreferences(
                passthroughEnabled = hint.passthroughEnabled,
                preferSurround = hint.preferSurround,
                outputMode = hint.liveAudioOutputMode(),
            )
        } else {
            applyEffectiveAudioOutputPreferences(
                passthroughEnabled = userAudioPassthroughEnabled,
                preferSurround = userPreferSurroundCodecs,
                outputMode = userLiveAudioOutputMode,
            )
        }
    }

    override fun play(url: String) {
        audioFallbackAttempted = false
        ac3PassthroughFallbackAttempted = false
        ac3TrackFallbackAttempted = false
        ac3StereoFallbackAttempted = false
        liveAudioRecoveryAttempts = 0
        currentAudioSignature = null
        pendingSuccessfulRecoveryKind = null
        sessionIncompatibleRecoverySkipped = currentPlaybackContext?.let {
            LiveAudioCompatibilityStore.isSessionIncompatible(it, currentPreferenceKey())
        } == true
        applyRememberedCompatibilityHintIfAvailable()
        exoPlayer?.apply {
            stop()
            clearMediaItems()
            setMediaItem(MediaItem.fromUri(url))
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
        exoPlayer?.stop()
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
        selectTrackInGroup(player, textGroups, id)
    }

    override fun selectAudioTrack(id: Int) {
        val player = exoPlayer ?: return
        val audioGroups = trackGroups.filter { it.type == C.TRACK_TYPE_AUDIO }
        selectTrackInGroup(player, audioGroups, id)
    }

    private fun selectTrackInGroup(player: ExoPlayer, groups: List<Tracks.Group>, trackIndex: Int) {
        var idx = 0
        for (group in groups) {
            for (i in 0 until group.length) {
                if (idx == trackIndex) {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, i),
                        )
                        .build()
                    return
                }
                idx++
            }
        }
    }

    override fun disableSubtitles() {
        val player = exoPlayer ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /**
     * Handles audio codec errors by trying to select a compatible audio track.
     * Falls back to disabling audio entirely if no compatible track exists.
     */
    private fun handleAudioCodecError() {
        val player = exoPlayer ?: return
        if (liveAudioRecoveryAttempts >= MAX_LIVE_AUDIO_RECOVERY_ATTEMPTS) {
            disableAudioAfterCompatibilityFailure(player)
            return
        }
        val candidate = player.findCompatibleAudioCandidate()
        if (candidate != null) {
            liveAudioRecoveryAttempts += 1
            Log.d(
                TAG,
                "Audio fallback: selecting ${candidate.mime ?: "unknown"} (${candidate.language ?: "?"})",
            )
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(
                            TrackSelectionOverride(candidate.group.mediaTrackGroup, candidate.trackIndex),
                        )
                        .build()
            pendingSuccessfulRecoveryKind = LiveAudioRecoveryKind.COMPATIBLE_TRACK
            restartPlaybackAfterAudioFallback(player, player.currentPosition)
            return
        }

        disableAudioAfterCompatibilityFailure(player)
    }

    private fun isAc3RendererFailure(
        error: PlaybackException,
        selectedAudioMime: String?,
    ): Boolean {
        val chain = error.buildCauseChainSummary()
        val rendererMentioned = chain.contains("MediaCodecAudioRenderer", ignoreCase = true) ||
            chain.contains("AudioRenderer", ignoreCase = true)
        val ac3Mentioned = chain.contains("audio/ac3", ignoreCase = true) ||
            chain.contains("audio/eac3", ignoreCase = true) ||
            isAc3FamilyMime(selectedAudioMime)
        return rendererMentioned && ac3Mentioned
    }

    private fun attemptAc3Recovery(
        player: ExoPlayer,
        error: PlaybackException,
        selectedAudio: SelectedAudioTrackSnapshot?,
    ): Boolean {
        if (sessionIncompatibleRecoverySkipped || liveAudioRecoveryAttempts >= MAX_LIVE_AUDIO_RECOVERY_ATTEMPTS) {
            return false
        }
        val compatibleCandidate = player.findCompatibleAudioCandidate()
        val hasAlternateCompatibleTrack = compatibleCandidate != null
        val resumePositionMs = player.currentPosition.coerceAtLeast(0L)

        if (audioPassthroughEnabled && !ac3PassthroughFallbackAttempted) {
            ac3PassthroughFallbackAttempted = true
            liveAudioRecoveryAttempts += 1
            applyEffectiveAudioOutputPreferences(
                passthroughEnabled = false,
                preferSurround = false,
                outputMode = LiveAudioOutputMode.PREFER_COMPATIBLE,
            )
            pendingSuccessfulRecoveryKind = LiveAudioRecoveryKind.COMPATIBLE_MODE
            restartPlaybackAfterAudioFallback(player, resumePositionMs)
            logAc3Diagnostics(
                error = error,
                selectedAudio = selectedAudio,
                fallbackPath = "disable_passthrough",
                alternateTrackExists = hasAlternateCompatibleTrack,
            )
            return true
        }

        if (!ac3TrackFallbackAttempted && compatibleCandidate != null) {
            ac3TrackFallbackAttempted = true
            liveAudioRecoveryAttempts += 1
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(
                    TrackSelectionOverride(
                        compatibleCandidate.group.mediaTrackGroup,
                        compatibleCandidate.trackIndex,
                    ),
                )
                .build()
            pendingSuccessfulRecoveryKind = LiveAudioRecoveryKind.COMPATIBLE_TRACK
            restartPlaybackAfterAudioFallback(player, resumePositionMs)
            logAc3Diagnostics(
                error = error,
                selectedAudio = selectedAudio,
                fallbackPath = "switch_track:${compatibleCandidate.mime ?: "unknown"}",
                alternateTrackExists = true,
            )
            return true
        }

        if (!ac3StereoFallbackAttempted && liveAudioOutputMode != LiveAudioOutputMode.FORCE_STEREO_PCM) {
            ac3StereoFallbackAttempted = true
            liveAudioRecoveryAttempts += 1
            applyEffectiveAudioOutputPreferences(
                passthroughEnabled = false,
                preferSurround = false,
                outputMode = LiveAudioOutputMode.FORCE_STEREO_PCM,
            )
            pendingSuccessfulRecoveryKind = LiveAudioRecoveryKind.STEREO_PCM
            restartPlaybackAfterAudioFallback(player, resumePositionMs)
            logAc3Diagnostics(
                error = error,
                selectedAudio = selectedAudio,
                fallbackPath = "force_stereo_pcm",
                alternateTrackExists = hasAlternateCompatibleTrack,
            )
            return true
        }

        logAc3Diagnostics(
            error = error,
            selectedAudio = selectedAudio,
            fallbackPath = "none",
            alternateTrackExists = hasAlternateCompatibleTrack,
        )
        return false
    }

    private fun confirmSuccessfulRecoveryIfNeeded() {
        val recoveryKind = pendingSuccessfulRecoveryKind ?: return
        val playbackContext = currentPlaybackContext
        if (playbackContext != null) {
            val selectedTrackHint = when (recoveryKind) {
                LiveAudioRecoveryKind.COMPATIBLE_TRACK -> {
                    exoPlayer?.selectedAudioTrackSnapshot()?.toTrackHint()
                }
                else -> null
            }
            rememberedCompatibilityHint = LiveAudioCompatibilityStore.rememberSuccessfulRecovery(
                context = context,
                playbackContext = playbackContext,
                passthroughEnabled = audioPassthroughEnabled,
                preferSurround = preferSurroundCodecs,
                outputMode = liveAudioOutputMode,
                recoveryKind = recoveryKind,
                preferredTrack = selectedTrackHint,
                audioSignature = currentAudioSignature,
            )
            LiveAudioCompatibilityStore.clearSessionIncompatible(playbackContext)
        }
        pendingSuccessfulRecoveryKind = null
        rememberedTrackHintApplied = recoveryKind == LiveAudioRecoveryKind.COMPATIBLE_TRACK
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

    private fun applyRememberedCompatibleTrackIfNeeded() {
        val player = exoPlayer ?: return
        val hint = rememberedCompatibilityHint ?: return
        val preferredTrack = hint.preferredTrack ?: return
        if (hint.recoveryKind != LiveAudioRecoveryKind.COMPATIBLE_TRACK || rememberedTrackHintApplied) {
            return
        }

        val currentlySelected = player.selectedAudioTrackSnapshot()
        if (currentlySelected != null && currentlySelected.matches(preferredTrack)) {
            rememberedTrackHintApplied = true
            return
        }

        val candidate = player.findTrackMatchingHint(preferredTrack) ?: return
        rememberedTrackHintApplied = true
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(
                TrackSelectionOverride(candidate.group.mediaTrackGroup, candidate.trackIndex),
            )
            .build()
        Log.d(
            TAG,
            "Applying remembered compatible audio track ${candidate.mime ?: "unknown"} for ${currentPlaybackContext?.displayName.orEmpty()}",
        )
    }

    private fun disableAudioAfterCompatibilityFailure(player: ExoPlayer) {
        pendingSuccessfulRecoveryKind = null
        val playbackContext = currentPlaybackContext
        if (playbackContext != null) {
            if (rememberedCompatibilityHint != null) {
                LiveAudioCompatibilityStore.invalidateHint(context, playbackContext)
                rememberedCompatibilityHint = null
                rememberedTrackHintApplied = false
            }
            LiveAudioCompatibilityStore.markSessionIncompatible(playbackContext, currentPreferenceKey())
        }
        Log.w(TAG, "Audio incompatible after recovery attempts - disabling audio for video continuity")
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        restartPlaybackAfterAudioFallback(player, player.currentPosition)
        listeners.forEach { it.onError(INCOMPATIBLE_LIVE_AUDIO_MESSAGE) }
    }

    private fun recoveryMessageFor(kind: LiveAudioRecoveryKind): String {
        return when (kind) {
            LiveAudioRecoveryKind.COMPATIBLE_MODE,
            LiveAudioRecoveryKind.STEREO_PCM -> COMPATIBLE_AUDIO_MODE_MESSAGE
            LiveAudioRecoveryKind.COMPATIBLE_TRACK -> COMPATIBLE_AUDIO_TRACK_MESSAGE
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

    private fun logAc3Diagnostics(
        error: PlaybackException,
        selectedAudio: SelectedAudioTrackSnapshot?,
        fallbackPath: String,
        alternateTrackExists: Boolean,
    ) {
        val androidVersion = "${Build.VERSION.RELEASE ?: "?"} (SDK ${Build.VERSION.SDK_INT})"
        Log.w(
            TAG,
            "AC3 fallback: channel=${currentPlaybackContext?.displayName ?: "unknown"} model=${Build.MODEL} android=$androidVersion " +
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
                "fallbackPath=$fallbackPath " +
                "causeChain=${error.buildCauseChainSummary()}",
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
        )
    }

    private fun SelectedAudioTrackSnapshot.matches(trackHint: LiveAudioTrackHint): Boolean {
        val normalizedMime = normalizeFormatKey(mime)
        return normalizedMime == trackHint.formatKey &&
            language.equals(trackHint.language, ignoreCase = true) &&
            label.equals(trackHint.label, ignoreCase = true) &&
            (trackHint.channelCount == null || trackHint.channelCount == channelCount)
    }

    private fun ExoPlayer.findCompatibleAudioCandidate(): SelectedAudioTrackSnapshot? {
        val selected = selectedAudioTrackSnapshot()
        var audioGroupIndex = 0
        currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val mime = format.sampleMimeType ?: continue
                if (!isCompatibleAudioMime(mime)) continue
                val isCurrentSelection = selected != null &&
                    selected.groupIndex == audioGroupIndex &&
                    selected.trackIndex == trackIndex
                if (isCurrentSelection) continue
                return SelectedAudioTrackSnapshot(
                    group = group,
                    groupIndex = audioGroupIndex,
                    trackIndex = trackIndex,
                    mime = mime,
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
        if (normalizeFormatKey(mime) == trackHint.formatKey) score += 6
        if (language.equals(trackHint.language, ignoreCase = true)) score += 3
        if (label.equals(trackHint.label, ignoreCase = true)) score += 2
        if (trackHint.channelCount != null && channelCount == trackHint.channelCount) score += 1
        return score
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
        exoPlayer?.removeListener(exoListener)
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        onCodecError = null
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
            "audio/mp4a-latm",
            "audio/opus",
            "audio/mpeg",
        )
        val stereoFirstMimes = arrayOf(
            "audio/mp4a-latm",
            "audio/opus",
            "audio/mpeg",
            "audio/eac3",
            "audio/ac3",
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            "audio/true-hd",
        )
        val compatibleFirstMimes = arrayOf(
            "audio/mp4a-latm",
            "audio/opus",
            "audio/mpeg",
            "audio/raw",
            "audio/flac",
            "audio/vorbis",
            "audio/eac3",
            "audio/ac3",
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            "audio/true-hd",
        )

        val forceStereoMimes = arrayOf(
            "audio/mp4a-latm",
            "audio/opus",
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
            .build()
    }

    fun getExoPlayer(): ExoPlayer? = exoPlayer

    fun updatePosition() {
        val player = exoPlayer ?: return
        _state = _state.copy(positionMs = player.currentPosition)
    }

    private fun notifyStateChanged() {
        listeners.forEach { it.onStateChanged(_state) }
    }

    companion object {
        private const val TAG = "ExoPlayerEngine"
        private const val MAX_LIVE_AUDIO_RECOVERY_ATTEMPTS = 3
        private const val COMPATIBLE_AUDIO_MODE_MESSAGE =
            "Audio recovered by changing the audio mode."
        private const val COMPATIBLE_AUDIO_TRACK_MESSAGE =
            "Switched to a compatible audio track."
        private const val INCOMPATIBLE_LIVE_AUDIO_MESSAGE =
            "Audio unavailable on this device for this channel."
        private val AC3_FAMILY_MIMES = setOf(
            "audio/ac3",
            "audio/eac3",
            "audio/eac3-joc",
        )
        private val COMPATIBLE_AUDIO_MIMES = setOf(
            "audio/mp4a-latm",
            "audio/mpeg",
            "audio/opus",
            "audio/vorbis",
            "audio/raw",
            "audio/flac",
            "audio/amr-nb",
            "audio/amr-wb",
        )
    }
}


