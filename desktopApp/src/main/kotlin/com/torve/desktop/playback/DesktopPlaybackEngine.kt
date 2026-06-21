package com.torve.desktop.playback

import kotlinx.coroutines.flow.Flow

interface DesktopPlaybackEngine {
    val backendLabel: String
    val runtimeRequirement: String
    val isEmbedded: Boolean get() = false
    val events: Flow<DesktopPlaybackEngineEvent>

    suspend fun probeRuntime(startupTrace: PlaybackStartupTrace? = null)

    suspend fun open(
        session: DesktopPlaybackSession,
        autoPlay: Boolean,
        startupTrace: PlaybackStartupTrace? = null,
        resumePositionMs: Long? = null,
    )

    suspend fun play()

    suspend fun pause()

    suspend fun stop()

    suspend fun close()

    fun dispose()
}

sealed interface DesktopPlaybackEngineEvent {
    data class RuntimeProbe(
        val found: Boolean,
        val executablePath: String? = null,
        val discoverySource: String? = null,
        val attemptedPaths: List<String> = emptyList(),
        val message: String,
    ) : DesktopPlaybackEngineEvent

    data class RuntimeReady(
        val executablePath: String,
        val discoverySource: String,
        val processId: Long?,
    ) : DesktopPlaybackEngineEvent

    data class Opening(
        val mediaPath: String,
        val autoPlay: Boolean,
        val appliedHeaderCount: Int,
        val selectedSubtitleLabel: String? = null,
        val bufferingModeLabel: String? = null,
    ) : DesktopPlaybackEngineEvent

    data class Buffering(
        val mediaPath: String? = null,
        val cachePercent: Float,
    ) : DesktopPlaybackEngineEvent

    data class Playing(
        val mediaPath: String? = null,
    ) : DesktopPlaybackEngineEvent

    data class FirstFrame(
        val mediaPath: String? = null,
    ) : DesktopPlaybackEngineEvent

    data class Paused(
        val mediaPath: String? = null,
    ) : DesktopPlaybackEngineEvent

    data class Stopped(
        val reason: String,
    ) : DesktopPlaybackEngineEvent

    data class Position(
        val positionSeconds: Double?,
        val durationSeconds: Double?,
    ) : DesktopPlaybackEngineEvent

    data class Closed(
        val reason: String,
    ) : DesktopPlaybackEngineEvent

    data class Error(
        val code: String,
        val message: String,
        val recoverable: Boolean = true,
    ) : DesktopPlaybackEngineEvent
}
