package com.torve.desktop.player

import org.junit.Assume.assumeFalse
import org.junit.Test
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.JWindow
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VlcFullscreenControllerTest {

    @Test
    fun `initial state is not fullscreen`() {
        val controller = VlcFullscreenController()
        assertFalse(controller.isFullscreen)
    }

    @Test
    fun `exitFullscreen does nothing when not fullscreen`() {
        val controller = VlcFullscreenController()
        controller.exitFullscreen()
        assertFalse(controller.isFullscreen)
    }

    @Test
    fun `toggle cycles restore frame state`() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val controller = VlcFullscreenController()
        val frame = JFrame("Torve Test").apply {
            setSize(960, 540)
            setLocation(120, 140)
            isVisible = true
        }
        val originalBounds = frame.bounds
        try {
            controller.toggleFullscreen(frame)
            assertTrue(controller.isFullscreen)

            controller.exitFullscreen()
            assertFalse(controller.isFullscreen)
            assertTrue(frame.bounds == originalBounds)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `overlay window resolves to owning frame`() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val controller = VlcFullscreenController()
        val frame = JFrame("Torve Owner").apply {
            setSize(800, 600)
            isVisible = true
        }
        val overlay = JWindow(frame).apply {
            setSize(100, 100)
            isVisible = true
        }
        try {
            controller.toggleFullscreen(overlay)
            assertTrue(controller.isFullscreen)
            controller.exitFullscreen()
            assertFalse(controller.isFullscreen)
        } finally {
            overlay.dispose()
            frame.dispose()
        }
    }
}
