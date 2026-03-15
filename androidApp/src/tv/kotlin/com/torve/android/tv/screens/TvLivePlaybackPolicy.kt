package com.torve.android.tv.screens

import com.torve.android.player.LiveAudioCompatibilityFailureReport
import com.torve.android.player.LiveAudioTerminalFailureHint
import com.torve.android.player.LivePlayerEngineId
import com.torve.domain.model.Channel
import com.torve.domain.player.LiveTuneState

internal object TvLivePlaybackPolicy {
    // TiviMate uses ExoPlayer exclusively for live TV — no MPV.
    // This eliminates the vo_mediacodec_embed SIGABRT crash and gives
    // TiviMate-identical playback with our matched ExoPlayer config.
    fun primaryEngineForChannel(
        channel: Channel,
        mpvAvailable: Boolean,
    ): LivePlayerEngineId = LivePlayerEngineId.EXOPLAYER

    fun primaryEngineForKnownTerminalFailure(
        hint: LiveAudioTerminalFailureHint?,
        mpvAvailable: Boolean,
    ): LivePlayerEngineId = LivePlayerEngineId.EXOPLAYER

    fun allowAutomaticMpvFallback(
        report: LiveAudioCompatibilityFailureReport,
    ): Boolean = false
}
