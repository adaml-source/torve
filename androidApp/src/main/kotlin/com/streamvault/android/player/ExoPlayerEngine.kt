package com.streamvault.android.player

import android.content.Context
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
import com.streamvault.domain.player.PlayerEngine
import com.streamvault.domain.player.PlayerListener
import com.streamvault.domain.player.PlayerState
import com.streamvault.domain.player.TrackDescription
import java.util.Locale

/**
 * PlayerEngine backed by ExoPlayer (Media3).
 * Used as fallback when libmpv .so files are not available.
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
            val msg = when (error.errorCode) {
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
            listeners.forEach { it.onError(msg) }
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
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .build()

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .also { it.addListener(exoListener) }
    }

    override fun play(url: String) {
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

    override fun release() {
        exoPlayer?.removeListener(exoListener)
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun addListener(listener: PlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: PlayerListener) {
        listeners.remove(listener)
    }

    fun getExoPlayer(): ExoPlayer? = exoPlayer

    fun updatePosition() {
        val player = exoPlayer ?: return
        _state = _state.copy(positionMs = player.currentPosition)
    }

    private fun notifyStateChanged() {
        listeners.forEach { it.onStateChanged(_state) }
    }
}
