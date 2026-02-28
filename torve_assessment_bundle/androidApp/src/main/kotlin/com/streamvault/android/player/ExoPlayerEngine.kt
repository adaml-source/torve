package com.streamvault.android.player

import android.content.Context
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
import com.streamvault.domain.player.PlayerEngine
import com.streamvault.domain.player.PlayerListener
import com.streamvault.domain.player.PlayerState
import com.streamvault.domain.player.TrackDescription
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

    /** Set by the player screen to enable codec-error fallback at the stream level. */
    var onCodecError: ((errorCode: Int) -> Unit)? = null

    /** Prevents infinite loop if audio fallback also fails. */
    private var audioFallbackAttempted = false

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
            }
            notifyStateChanged()
        }

        override fun onPlayerError(error: PlaybackException) {
            val isCodecError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED

            if (isCodecError) {
                val msg = error.message.orEmpty()
                val isAudioCodecError = msg.contains("AudioRenderer", ignoreCase = true) ||
                    msg.contains("audio/eac3", ignoreCase = true) ||
                    msg.contains("audio/ac3", ignoreCase = true) ||
                    msg.contains("audio/dts", ignoreCase = true) ||
                    msg.contains("audio/truehd", ignoreCase = true) ||
                    msg.contains("audio/mlp", ignoreCase = true)

                if (isAudioCodecError) {
                    if (!audioFallbackAttempted) {
                        Log.w(TAG, "Audio codec error — attempting fallback")
                        audioFallbackAttempted = true
                        handleAudioCodecError()
                    } else {
                        // Already tried fallback — just disable audio silently and keep playing
                        Log.w(TAG, "Audio codec error after fallback — disabling audio entirely")
                        val player = exoPlayer ?: return
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            .build()
                        player.prepare()
                        player.playWhenReady = true
                        listeners.forEach { it.onError("Audio format not supported on this device — playing without sound") }
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
            listeners.forEach { it.onTracksChanged(audios, subs) }
        }
    }

    fun initialize() {
        val processor = delayProcessor
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): DefaultAudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor))
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
    }

    override fun play(url: String) {
        audioFallbackAttempted = false
        exoPlayer?.apply {
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

        // Safe audio MIME types that virtually all Android devices support
        val safeMimes = setOf(
            "audio/mp4a-latm", // AAC
            "audio/mpeg",      // MP3
            "audio/opus",
            "audio/vorbis",
            "audio/raw",
            "audio/flac",
        )

        // Search through all audio track groups for a compatible track
        val tracks = player.currentTracks
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val mime = format.sampleMimeType ?: continue
                if (mime in safeMimes) {
                    Log.d(TAG, "Audio fallback: selecting $mime (${format.language ?: "?"}) instead of unsupported codec")
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, i),
                        )
                        .build()
                    player.prepare()
                    player.playWhenReady = true
                    return
                }
            }
        }

        // No compatible audio track — disable audio, keep video playing silently
        Log.w(TAG, "No compatible audio track found — disabling audio")
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        player.prepare()
        player.playWhenReady = true
        listeners.forEach { it.onError("Audio format not supported on this device — playing without sound") }
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
    }
}
