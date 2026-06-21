package com.torve.desktop.playback

import androidx.compose.ui.input.key.Key
import com.torve.domain.player.DesktopPlaybackHotkeys
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlaybackHotkeyBindingsTest {
    @Test
    fun default_hotkeys_match_requested_desktop_playback_controls() {
        val hotkeys = DesktopPlaybackHotkeys()

        assertEquals(Key.DirectionUp, hotkeys.volumeUp.toComposePlaybackKey())
        assertEquals(Key.DirectionDown, hotkeys.volumeDown.toComposePlaybackKey())
        assertEquals(Key.DirectionLeft, hotkeys.seekBackward.toComposePlaybackKey())
        assertEquals(Key.DirectionRight, hotkeys.seekForward.toComposePlaybackKey())
        assertEquals(Key.Escape, hotkeys.exitPlayback.toComposePlaybackKey())
        assertEquals(Key.C, hotkeys.cycleSubtitles.toComposePlaybackKey())
        assertEquals(Key.V, hotkeys.cycleVideoMode.toComposePlaybackKey())
        assertEquals(Key.S, hotkeys.stopPlayback.toComposePlaybackKey())
    }

    @Test
    fun iptv_channel_defaults_do_not_steal_arrow_keys() {
        val hotkeys = DesktopPlaybackHotkeys()

        assertEquals(KeyEvent.VK_PAGE_UP, hotkeys.previousChannel.toAwtPlaybackKeyCode())
        assertEquals(KeyEvent.VK_PAGE_DOWN, hotkeys.nextChannel.toAwtPlaybackKeyCode())
    }

    @Test
    fun unsupported_custom_key_is_detected_for_settings_feedback() {
        assertFalse(isSupportedPlaybackHotkey("Shift+Left"))
        assertTrue(isSupportedPlaybackHotkey("PageDown"))
    }
}
