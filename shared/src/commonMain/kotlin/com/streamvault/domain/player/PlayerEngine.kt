package com.streamvault.domain.player

/**
 * Platform-agnostic player abstraction.
 * Implemented by MPVPlayerEngine (Android/iOS) or ExoPlayerEngine (Android fallback).
 */
interface PlayerEngine {
    val state: PlayerState

    fun play(url: String)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun seekRelative(deltaMs: Long)
    fun setSpeed(speed: Float)

    // Track selection
    fun getSubtitleTracks(): List<TrackDescription>
    fun getAudioTracks(): List<TrackDescription>
    fun selectSubtitleTrack(id: Int)
    fun selectAudioTrack(id: Int)
    fun disableSubtitles()

    // Lifecycle
    fun release()

    // State observation
    fun addListener(listener: PlayerListener)
    fun removeListener(listener: PlayerListener)
}

data class PlayerState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isBuffering: Boolean = false,
    val isIdle: Boolean = true,
)

data class TrackDescription(
    val id: Int,
    val label: String,
    val language: String? = null,
    val isSelected: Boolean = false,
)

interface PlayerListener {
    fun onStateChanged(state: PlayerState) {}
    fun onTracksChanged(audio: List<TrackDescription>, subtitles: List<TrackDescription>) {}
    fun onError(message: String) {}
}
