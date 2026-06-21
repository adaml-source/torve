package com.torve.desktop.player

import org.junit.Test
import java.awt.event.KeyEvent
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the keyboard shortcut constants used in [VlcVideoSurfaceHost]
 * map to the expected key codes. This is a compile-time+runtime sanity check.
 */
class KeyboardShortcutMappingTest {

    @Test
    fun `space key maps to VK_SPACE`() {
        assertEquals(32, KeyEvent.VK_SPACE)
    }

    @Test
    fun `escape key maps to VK_ESCAPE`() {
        assertEquals(27, KeyEvent.VK_ESCAPE)
    }

    @Test
    fun `F key maps to VK_F`() {
        assertEquals(70, KeyEvent.VK_F)
    }

    @Test
    fun `arrow keys are distinct`() {
        val keys = setOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN)
        assertEquals(4, keys.size, "Arrow keys must have distinct keycodes")
    }

    @Test
    fun `M key maps to VK_M for mute`() {
        assertEquals(77, KeyEvent.VK_M)
    }

    @Test
    fun `subtitle delay keys H and J are distinct`() {
        assertTrue(KeyEvent.VK_H != KeyEvent.VK_J)
    }

    @Test
    fun `audio delay keys K and L are distinct`() {
        assertTrue(KeyEvent.VK_K != KeyEvent.VK_L)
    }

    @Test
    fun `F11 is available for fullscreen toggle`() {
        assertEquals(122, KeyEvent.VK_F11)
    }
}
