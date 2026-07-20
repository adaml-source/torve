package com.torve.desktop.playback

import androidx.compose.ui.input.key.Key
import com.torve.domain.player.DesktopPlaybackHotkeys
import java.awt.event.KeyEvent

enum class DesktopPlaybackHotkeyAction(
    val label: String,
    val hint: String,
) {
    PLAY_PAUSE("Play / pause", "Toggle playback"),
    VOLUME_UP("Volume up", "Raise volume"),
    VOLUME_DOWN("Volume down", "Lower volume"),
    SEEK_BACKWARD("Seek back", "Jump backward by the configured seek step"),
    SEEK_FORWARD("Seek forward", "Jump forward by the configured seek step"),
    EXIT_PLAYBACK("Exit playback", "Leave fullscreen or close the player"),
    CYCLE_SUBTITLES("Change subtitles", "Cycle subtitle tracks, including Off"),
    CYCLE_VIDEO_MODE("Change video mode", "Cycle picture / aspect mode"),
    MUTE("Mute", "Toggle mute"),
    STOP_PLAYBACK("Stop", "Stop playback and return to the app"),
    TOGGLE_FULLSCREEN("Fullscreen", "Toggle fullscreen"),
    PREVIOUS_CHANNEL("Previous IPTV channel", "Only while Live TV is playing"),
    NEXT_CHANNEL("Next IPTV channel", "Only while Live TV is playing"),
}

fun DesktopPlaybackHotkeys.bindingFor(action: DesktopPlaybackHotkeyAction): String = when (action) {
    DesktopPlaybackHotkeyAction.PLAY_PAUSE -> playPause
    DesktopPlaybackHotkeyAction.VOLUME_UP -> volumeUp
    DesktopPlaybackHotkeyAction.VOLUME_DOWN -> volumeDown
    DesktopPlaybackHotkeyAction.SEEK_BACKWARD -> seekBackward
    DesktopPlaybackHotkeyAction.SEEK_FORWARD -> seekForward
    DesktopPlaybackHotkeyAction.EXIT_PLAYBACK -> exitPlayback
    DesktopPlaybackHotkeyAction.CYCLE_SUBTITLES -> cycleSubtitles
    DesktopPlaybackHotkeyAction.CYCLE_VIDEO_MODE -> cycleVideoMode
    DesktopPlaybackHotkeyAction.MUTE -> mute
    DesktopPlaybackHotkeyAction.STOP_PLAYBACK -> stopPlayback
    DesktopPlaybackHotkeyAction.TOGGLE_FULLSCREEN -> toggleFullscreen
    DesktopPlaybackHotkeyAction.PREVIOUS_CHANNEL -> previousChannel
    DesktopPlaybackHotkeyAction.NEXT_CHANNEL -> nextChannel
}

fun DesktopPlaybackHotkeys.withBinding(
    action: DesktopPlaybackHotkeyAction,
    binding: String,
): DesktopPlaybackHotkeys = when (action) {
    DesktopPlaybackHotkeyAction.PLAY_PAUSE -> copy(playPause = binding)
    DesktopPlaybackHotkeyAction.VOLUME_UP -> copy(volumeUp = binding)
    DesktopPlaybackHotkeyAction.VOLUME_DOWN -> copy(volumeDown = binding)
    DesktopPlaybackHotkeyAction.SEEK_BACKWARD -> copy(seekBackward = binding)
    DesktopPlaybackHotkeyAction.SEEK_FORWARD -> copy(seekForward = binding)
    DesktopPlaybackHotkeyAction.EXIT_PLAYBACK -> copy(exitPlayback = binding)
    DesktopPlaybackHotkeyAction.CYCLE_SUBTITLES -> copy(cycleSubtitles = binding)
    DesktopPlaybackHotkeyAction.CYCLE_VIDEO_MODE -> copy(cycleVideoMode = binding)
    DesktopPlaybackHotkeyAction.MUTE -> copy(mute = binding)
    DesktopPlaybackHotkeyAction.STOP_PLAYBACK -> copy(stopPlayback = binding)
    DesktopPlaybackHotkeyAction.TOGGLE_FULLSCREEN -> copy(toggleFullscreen = binding)
    DesktopPlaybackHotkeyAction.PREVIOUS_CHANNEL -> copy(previousChannel = binding)
    DesktopPlaybackHotkeyAction.NEXT_CHANNEL -> copy(nextChannel = binding)
}.sanitized()

fun String.toComposePlaybackKey(): Key? = when (normalizeHotkey()) {
    "space", "spacebar" -> Key.Spacebar
    "esc", "escape" -> Key.Escape
    "left", "arrowleft" -> Key.DirectionLeft
    "right", "arrowright" -> Key.DirectionRight
    "up", "arrowup" -> Key.DirectionUp
    "down", "arrowdown" -> Key.DirectionDown
    "pageup", "pgup" -> Key.PageUp
    "pagedown", "pgdown" -> Key.PageDown
    "enter", "return" -> Key.Enter
    "f" -> Key.F
    "c" -> Key.C
    "v" -> Key.V
    "m" -> Key.M
    "s" -> Key.S
    "n" -> Key.N
    "p" -> Key.P
    else -> null
}

fun String.toAwtPlaybackKeyCode(): Int? = when (normalizeHotkey()) {
    "space", "spacebar" -> KeyEvent.VK_SPACE
    "esc", "escape" -> KeyEvent.VK_ESCAPE
    "left", "arrowleft" -> KeyEvent.VK_LEFT
    "right", "arrowright" -> KeyEvent.VK_RIGHT
    "up", "arrowup" -> KeyEvent.VK_UP
    "down", "arrowdown" -> KeyEvent.VK_DOWN
    "pageup", "pgup" -> KeyEvent.VK_PAGE_UP
    "pagedown", "pgdown" -> KeyEvent.VK_PAGE_DOWN
    "enter", "return" -> KeyEvent.VK_ENTER
    "f" -> KeyEvent.VK_F
    "c" -> KeyEvent.VK_C
    "v" -> KeyEvent.VK_V
    "m" -> KeyEvent.VK_M
    "s" -> KeyEvent.VK_S
    "n" -> KeyEvent.VK_N
    "p" -> KeyEvent.VK_P
    else -> null
}

fun isSupportedPlaybackHotkey(raw: String): Boolean = raw.toComposePlaybackKey() != null

private fun String.normalizeHotkey(): String =
    trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")
