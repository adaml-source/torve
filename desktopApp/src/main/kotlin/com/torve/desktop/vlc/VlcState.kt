package com.torve.desktop.vlc

/**
 * Immutable state snapshot of the VLC playback session, exposed to Compose.
 * All fields are read-only. State is updated atomically by [VlcEventBridge].
 */
data class VlcSessionState(
    val playbackStatus: VlcPlaybackStatus = VlcPlaybackStatus.Idle,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val isBuffering: Boolean = false,
    val isSeeking: Boolean = false,
    val isEnded: Boolean = false,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val bufferedPercent: Float = 0f,
    val volume: Int = 100,
    val isMuted: Boolean = false,
    val playbackRate: Float = 1f,
    val audioDelayUs: Long = 0,
    val subtitleDelayUs: Long = 0,
    val selectedAudioTrack: VlcTrack? = null,
    val selectedSubtitleTrack: VlcTrack? = null,
    val selectedVideoTrack: VlcTrack? = null,
    val availableAudioTracks: List<VlcTrack> = emptyList(),
    val availableSubtitleTracks: List<VlcTrack> = emptyList(),
    val availableVideoTracks: List<VlcTrack> = emptyList(),
    val canDisableAudioTrack: Boolean = false,
    val canDisableVideoTrack: Boolean = false,
    val aspectRatioMode: String? = null,
    val videoDimensions: VideoDimensions? = null,
    val errorState: VlcError? = null,
    val isFullscreen: Boolean = false,
    val canSeek: Boolean = false,
    val canPause: Boolean = false,
    val hasVideo: Boolean = false,
    val hasAudio: Boolean = false,
)

sealed class VlcPlaybackStatus {
    data object Idle : VlcPlaybackStatus()
    data object Opening : VlcPlaybackStatus()
    data object Buffering : VlcPlaybackStatus()
    data object Playing : VlcPlaybackStatus()
    data object Paused : VlcPlaybackStatus()
    data object Stopped : VlcPlaybackStatus()
    data object Ended : VlcPlaybackStatus()
    data class Error(val error: VlcError) : VlcPlaybackStatus()
}

data class VlcTrack(
    val id: Int,
    val name: String,
    val language: String? = null,
    val codec: String? = null,
    val channels: String? = null,
)

data class VlcAudioDevice(
    val outputName: String,
    val outputLabel: String,
    val deviceId: String,
    val deviceLabel: String,
)

data class VideoDimensions(
    val width: Int,
    val height: Int,
)

data class VlcError(
    val code: String,
    val message: String,
    val recoverable: Boolean = true,
)
