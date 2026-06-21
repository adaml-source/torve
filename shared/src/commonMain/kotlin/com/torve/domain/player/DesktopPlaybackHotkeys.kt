package com.torve.domain.player

import kotlinx.serialization.Serializable

@Serializable
data class DesktopPlaybackHotkeys(
    val playPause: String = "Space",
    val volumeUp: String = "Up",
    val volumeDown: String = "Down",
    val seekBackward: String = "Left",
    val seekForward: String = "Right",
    val exitPlayback: String = "Esc",
    val cycleSubtitles: String = "C",
    val cycleVideoMode: String = "V",
    val mute: String = "M",
    val stopPlayback: String = "S",
    val toggleFullscreen: String = "F",
    val previousChannel: String = "PageUp",
    val nextChannel: String = "PageDown",
) {
    fun sanitized(): DesktopPlaybackHotkeys = copy(
        playPause = playPause.cleanKey("Space"),
        volumeUp = volumeUp.cleanKey("Up"),
        volumeDown = volumeDown.cleanKey("Down"),
        seekBackward = seekBackward.cleanKey("Left"),
        seekForward = seekForward.cleanKey("Right"),
        exitPlayback = exitPlayback.cleanKey("Esc"),
        cycleSubtitles = cycleSubtitles.cleanKey("C"),
        cycleVideoMode = cycleVideoMode.cleanKey("V"),
        mute = mute.cleanKey("M"),
        stopPlayback = stopPlayback.cleanKey("S"),
        toggleFullscreen = toggleFullscreen.cleanKey("F"),
        previousChannel = previousChannel.cleanKey("PageUp"),
        nextChannel = nextChannel.cleanKey("PageDown"),
    )

    private fun String.cleanKey(fallback: String): String =
        trim().takeIf { it.isNotBlank() } ?: fallback
}
