package com.torve.desktop.platform

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import org.junit.Test
import java.awt.GraphicsEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins behaviour of [DesktopWindowStateStore.clampToVisibleScreens] (Fix
 * E-1 — saved coordinates on a now-disconnected monitor must fall back to
 * platform default rather than painting off-screen).
 *
 * Headless test environments (incl. this CI runner) report zero screen
 * devices, so any absolute position is "outside any screen" and the
 * function returns PlatformDefault. We exploit that to cover the
 * disconnected-monitor scenario without needing real multi-monitor
 * hardware. The single-screen case is covered by reading the actual
 * `GraphicsEnvironment` when one is available.
 */
class WindowPositionClampTest {

    @Test
    fun `PlatformDefault input is returned unchanged`() {
        val result = DesktopWindowStateStore.clampToVisibleScreens(WindowPosition.PlatformDefault)
        assertSame(WindowPosition.PlatformDefault, result)
    }

    @Test
    fun `Aligned input is returned unchanged regardless of screen check`() {
        // Aligned is the other non-Absolute variant; clamp must pass it through.
        val aligned = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center)
        val result = DesktopWindowStateStore.clampToVisibleScreens(aligned)
        assertSame(aligned, result)
    }

    @Test
    fun `position outside every connected screen falls back to PlatformDefault`() {
        // (-99999, -99999) is outside any sane monitor's bounds; in headless
        // mode the screen list is empty so the same branch fires. Either way
        // the function must return PlatformDefault.
        val saved = WindowPosition(x = (-99_999).dp, y = (-99_999).dp)
        val result = DesktopWindowStateStore.clampToVisibleScreens(saved)
        assertEquals(WindowPosition.PlatformDefault, result)
    }

    @Test
    fun `position on the primary screen is returned as-is`() {
        // Skip when headless — without a screen, any absolute position is
        // outside-everything by definition. The headless path is already
        // covered by the test above.
        if (GraphicsEnvironment.isHeadless()) return
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        if (ge.screenDevices.isEmpty()) return
        val bounds = ge.defaultScreenDevice.defaultConfiguration.bounds
        val saved = WindowPosition(
            x = (bounds.x + bounds.width / 2).dp,
            y = (bounds.y + bounds.height / 2).dp,
        )
        val result = DesktopWindowStateStore.clampToVisibleScreens(saved)
        assertSame(saved, result)
    }

    @Test
    fun `top-left corner of primary screen is contained (inclusive bounds)`() {
        // Edge case: java.awt.Rectangle.contains(x, y) treats min-edge as
        // inclusive, max-edge as exclusive. (bounds.x, bounds.y) must count
        // as inside.
        if (GraphicsEnvironment.isHeadless()) return
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        if (ge.screenDevices.isEmpty()) return
        val bounds = ge.defaultScreenDevice.defaultConfiguration.bounds
        val cornerSaved = WindowPosition(x = bounds.x.dp, y = bounds.y.dp)
        val result = DesktopWindowStateStore.clampToVisibleScreens(cornerSaved)
        assertSame(cornerSaved, result, "min-edge of screen rect must be contained")
    }

    @Test
    fun `headless environment never accepts an absolute position`() {
        // Mirrors the disconnected-monitor scenario from the bug report:
        // every absolute coord is outside every (non-existent) screen.
        if (!GraphicsEnvironment.isHeadless()) return
        // If we are headless, both extreme and origin coords clamp away.
        val anyPos = WindowPosition(x = 100.dp, y = 100.dp)
        assertEquals(WindowPosition.PlatformDefault, DesktopWindowStateStore.clampToVisibleScreens(anyPos))
        assertTrue(true, "headless branch exercised")
    }
}
