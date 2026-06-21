package com.torve.android.tv.screens

import com.torve.android.player.LiveAudioTerminalFailureHint
import com.torve.android.player.LivePlayerEngineId
import com.torve.domain.player.LiveTuneState
import com.torve.domain.player.PlayerState
import java.util.Locale

internal data class TvLiveTerminalFailurePresentation(
    val bannerMessage: String,
    val suppressTuneProgress: Boolean = true,
)

internal fun buildTvLiveTerminalFailurePresentation(
    hint: LiveAudioTerminalFailureHint,
): TvLiveTerminalFailurePresentation {
    val bannerMessage = when (audioFormatLabel(hint.selectedMime)) {
        "DTS" -> "This channel's DTS audio isn't supported on this device."
        "Dolby Digital Plus" -> "This channel's Dolby Digital Plus audio isn't supported on this device."
        "Dolby Digital" -> "This channel's Dolby Digital audio isn't supported on this device."
        "AAC" -> "This channel's AAC audio isn't supported on this device."
        "MP3" -> "This channel's MP3 audio isn't supported on this device."
        "Opus" -> "This channel's Opus audio isn't supported on this device."
        "Vorbis" -> "This channel's Vorbis audio isn't supported on this device."
        else -> {
            when (hint.finalTuneState) {
                LiveTuneState.FALLBACK_ALLOWED.name -> "Audio unavailable on this device for this channel."
                else -> "This channel's audio isn't supported on this device."
            }
        }
    }
    return TvLiveTerminalFailurePresentation(bannerMessage = bannerMessage)
}

internal fun shouldShowTvLiveTuneProgress(
    playerState: PlayerState,
    engineId: LivePlayerEngineId?,
    terminalFailurePresentation: TvLiveTerminalFailurePresentation?,
): Boolean {
    if (
        terminalFailurePresentation?.suppressTuneProgress == true &&
        engineId == LivePlayerEngineId.EXOPLAYER &&
        !playerState.isIdle &&
        playerState.liveTuneState != LiveTuneState.PLAYING_CONFIRMED &&
        playerState.liveTuneState != LiveTuneState.FALLBACK_ALLOWED
    ) {
        return false
    }
    return playerState.isBuffering ||
        (
            engineId == LivePlayerEngineId.EXOPLAYER &&
                !playerState.isIdle &&
                playerState.liveTuneState != LiveTuneState.PLAYING_CONFIRMED &&
                playerState.liveTuneState != LiveTuneState.FALLBACK_ALLOWED
            )
}

private fun audioFormatLabel(selectedMime: String?): String? {
    val mime = selectedMime
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotBlank)
        ?: return null
    return when {
        "vnd.dts" in mime || mime.endsWith("dts") -> "DTS"
        "eac3" in mime || "ec-3" in mime -> "Dolby Digital Plus"
        mime.endsWith("ac3") || "ac-3" in mime -> "Dolby Digital"
        "aac" in mime -> "AAC"
        "mpeg" in mime || "mp3" in mime -> "MP3"
        "opus" in mime -> "Opus"
        "vorbis" in mime -> "Vorbis"
        else -> null
    }
}
