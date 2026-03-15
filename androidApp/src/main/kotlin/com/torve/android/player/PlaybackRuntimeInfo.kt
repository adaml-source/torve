package com.torve.android.player

import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.LiveAudioRecoveryMode
import com.torve.domain.player.LiveTuneState
import com.torve.domain.player.TrackDescription

internal enum class DecoderKind {
    HARDWARE,
    SOFTWARE,
    UNKNOWN,
}

internal data class PlaybackRuntimeInfo(
    val engineId: LivePlayerEngineId,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val videoDecoderName: String? = null,
    val audioDecoderName: String? = null,
    val videoDecoderKind: DecoderKind = DecoderKind.UNKNOWN,
    val audioDecoderKind: DecoderKind = DecoderKind.UNKNOWN,
    val resolutionLabel: String? = null,
    val frameRate: Float? = null,
    val selectedAudioTrack: TrackDescription? = null,
    val selectedSubtitleTrack: TrackDescription? = null,
    val outputMode: LiveAudioOutputMode? = null,
    val passthroughEnabled: Boolean = false,
    val preferSurround: Boolean = false,
    val liveTuneState: LiveTuneState = LiveTuneState.IDLE,
    val audioRecoveryMode: LiveAudioRecoveryMode = LiveAudioRecoveryMode.NONE,
    val engineFallbackAllowed: Boolean = false,
    val fallbackFromHardwareDefault: Boolean = false,
)
