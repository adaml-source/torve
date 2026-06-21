package com.torve.desktop.vlc

import com.torve.desktop.playback.DesktopPlaybackEngineEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State

/**
 * Converts vlcj native callbacks into coroutine-safe state and event streams.
 *
 * All vlcj callbacks arrive on an internal VLC thread. This bridge normalizes
 * them into an immutable [VlcSessionState] exposed via [StateFlow], and emits
 * [DesktopPlaybackEngineEvent]s for the controller layer.
 *
 * No UI code should mutate state directly. All state changes flow through here.
 */
class VlcEventBridge {

    private val _state = MutableStateFlow(VlcSessionState())
    val state: StateFlow<VlcSessionState> = _state.asStateFlow()

    private val _engineEvents = MutableSharedFlow<DesktopPlaybackEngineEvent>(extraBufferCapacity = 64)
    val engineEvents: SharedFlow<DesktopPlaybackEngineEvent> = _engineEvents.asSharedFlow()

    /** Context set before opening media, used to enrich events. */
    var mediaPath: String? = null
    var headerCount: Int = 0
    var subtitleLabel: String? = null
    var bufferingModeLabel: String? = null

    fun createEventAdapter(): MediaPlayerEventAdapter = object : MediaPlayerEventAdapter() {

        override fun opening(mediaPlayer: MediaPlayer) {
            updateState { copy(playbackStatus = VlcPlaybackStatus.Opening, isBuffering = true, isEnded = false, errorState = null) }
            emit(DesktopPlaybackEngineEvent.Opening(
                mediaPath = mediaPath.orEmpty(),
                autoPlay = true,
                appliedHeaderCount = headerCount,
                selectedSubtitleLabel = subtitleLabel,
                bufferingModeLabel = bufferingModeLabel,
            ))
        }

        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            if (newCache in 0f..<100f) {
                updateState { copy(playbackStatus = VlcPlaybackStatus.Buffering, isBuffering = true, bufferedPercent = newCache) }
                emit(DesktopPlaybackEngineEvent.Buffering(mediaPath = mediaPath, cachePercent = newCache.coerceIn(0f, 100f)))
            } else {
                updateState { copy(isBuffering = false, bufferedPercent = 100f) }
            }
        }

        override fun playing(mediaPlayer: MediaPlayer) {
            refreshTracks(mediaPlayer)
            // Intentionally NOT calling refreshVolume here. VLC's
            // audio().volume()/isMute queries are racy when the device is
            // still initialising - they often return 0/true even though
            // playback is unmuted. Bootstrap and any explicit
            // setVolume/setMute publishes are authoritative; the playing
            // event must not overwrite them with stale reads.
            refreshRate(mediaPlayer.status().rate())
            refreshDelays(mediaPlayer.audio().delay(), mediaPlayer.subpictures().delay())
            updateState {
                copy(
                    playbackStatus = VlcPlaybackStatus.Playing,
                    isPlaying = true, isPaused = false, isBuffering = false, isEnded = false,
                    canSeek = mediaPlayer.status().isSeekable,
                    canPause = mediaPlayer.status().canPause(),
                )
            }
            emit(DesktopPlaybackEngineEvent.Playing(mediaPath))
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            updateState { copy(playbackStatus = VlcPlaybackStatus.Paused, isPlaying = false, isPaused = true) }
            emit(DesktopPlaybackEngineEvent.Paused(mediaPath))
        }

        override fun stopped(mediaPlayer: MediaPlayer) {
            updateState { copy(playbackStatus = VlcPlaybackStatus.Stopped, isPlaying = false, isPaused = false) }
            emit(DesktopPlaybackEngineEvent.Stopped("Playback stopped."))
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            updateState { copy(playbackStatus = VlcPlaybackStatus.Ended, isPlaying = false, isEnded = true) }
            emit(DesktopPlaybackEngineEvent.Stopped("Playback finished."))
        }

        override fun error(mediaPlayer: MediaPlayer) {
            val err = VlcError("VLC_PLAYBACK_ERROR", "VLC encountered a playback error.", recoverable = true)
            updateState { copy(playbackStatus = VlcPlaybackStatus.Error(err), errorState = err, isPlaying = false) }
            emit(DesktopPlaybackEngineEvent.Error(err.code, err.message, err.recoverable))
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
            updateState { copy(positionMs = newTime) }
            val duration = mediaPlayer.status().length()
            emit(DesktopPlaybackEngineEvent.Position(
                positionSeconds = newTime / 1000.0,
                durationSeconds = if (duration > 0) duration / 1000.0 else null,
            ))
        }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            updateState { copy(durationMs = newLength) }
            emit(DesktopPlaybackEngineEvent.Position(positionSeconds = null, durationSeconds = newLength / 1000.0))
        }

        override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) {
            if (newCount > 0) {
                updateState { copy(hasVideo = true) }
                emit(DesktopPlaybackEngineEvent.FirstFrame(mediaPath))
            }
        }

        override fun seekableChanged(mediaPlayer: MediaPlayer, newSeekable: Int) {
            updateState { copy(canSeek = newSeekable != 0) }
        }

        override fun pausableChanged(mediaPlayer: MediaPlayer, newPausable: Int) {
            updateState { copy(canPause = newPausable != 0) }
        }
    }

    /** Refresh track lists from the media player. Call after tracks become available. */
    fun refreshTracks(mediaPlayer: MediaPlayer) {
        val audioTracks = mediaPlayer.audio().trackDescriptions()
            .filter { it.id() != -1 }
            .map { VlcTrack(it.id(), it.description() ?: "Track ${it.id()}") }
        val canDisableAudioTrack = mediaPlayer.audio().trackDescriptions().any { it.id() == -1 }
        val subtitleTracks = mediaPlayer.subpictures().trackDescriptions()
            .filter { it.id() != -1 }
            .map { VlcTrack(it.id(), it.description() ?: "Track ${it.id()}") }
        val videoTracks = mediaPlayer.video().trackDescriptions()
            .filter { it.id() != -1 }
            .map { VlcTrack(it.id(), it.description() ?: "Track ${it.id()}") }
        val canDisableVideoTrack = mediaPlayer.video().trackDescriptions().any { it.id() == -1 }

        val selectedAudio = audioTracks.firstOrNull { it.id == mediaPlayer.audio().track() }
        val selectedSub = subtitleTracks.firstOrNull { it.id == mediaPlayer.subpictures().track() }
        val selectedVideo = videoTracks.firstOrNull { it.id == mediaPlayer.video().track() }

        updateState {
            copy(
                availableAudioTracks = audioTracks,
                availableSubtitleTracks = subtitleTracks,
                availableVideoTracks = videoTracks,
                selectedAudioTrack = selectedAudio,
                selectedSubtitleTrack = selectedSub,
                selectedVideoTrack = selectedVideo,
                canDisableAudioTrack = canDisableAudioTrack,
                canDisableVideoTrack = canDisableVideoTrack,
                hasAudio = audioTracks.isNotEmpty() || selectedAudio != null,
                hasVideo = videoTracks.isNotEmpty() || selectedVideo != null || hasVideo,
            )
        }
    }

    /** Update volume/mute state from the media player. */
    fun refreshVolume(volume: Int, muted: Boolean) {
        updateState { copy(volume = volume, isMuted = muted) }
    }

    /** Update playback rate from the media player. */
    fun refreshRate(rate: Float) {
        updateState { copy(playbackRate = rate) }
    }

    /** Update delay values from the media player. */
    fun refreshDelays(audioDelayUs: Long, subtitleDelayUs: Long) {
        updateState { copy(audioDelayUs = audioDelayUs, subtitleDelayUs = subtitleDelayUs) }
    }

    /** Update video dimensions when format is known. */
    fun setVideoDimensions(width: Int, height: Int) {
        updateState { copy(videoDimensions = VideoDimensions(width, height)) }
    }

    /** Update fullscreen state. */
    fun setFullscreen(fullscreen: Boolean) {
        updateState { copy(isFullscreen = fullscreen) }
    }

    /** Reset to idle state. */
    fun reset() {
        _state.value = VlcSessionState()
        mediaPath = null
        headerCount = 0
        subtitleLabel = null
        bufferingModeLabel = null
    }

    private inline fun updateState(transform: VlcSessionState.() -> VlcSessionState) {
        _state.value = _state.value.transform()
    }

    private fun emit(event: DesktopPlaybackEngineEvent) {
        _engineEvents.tryEmit(event)
    }
}
