package com.torve.desktop.player

import java.awt.Component
import java.awt.Frame
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import javax.swing.SwingUtilities

/**
 * Manages fullscreen transitions for the embedded desktop player.
 *
 * Uses [GraphicsDevice.setFullScreenWindow] when exclusive fullscreen is
 * supported, and a manual maximised-undecorated fallback otherwise.
 * Neither path calls [Frame.dispose], so the Compose Desktop hierarchy
 * is never torn down.
 *
 * The controller always resolves the owning [Frame] so fullscreen operations
 * are never attempted against transient overlay windows such as the chrome
 * [javax.swing.JWindow].
 */
class VlcFullscreenController {

    private data class FullscreenSnapshot(
        val frame: Frame,
        val bounds: Rectangle,
        val extendedState: Int,
        val device: GraphicsDevice,
    )

    private var snapshot: FullscreenSnapshot? = null

    @Volatile
    var isFullscreen: Boolean = false
        private set

    fun toggleFullscreen(component: Component) {
        val frame = owningFrame(component) ?: return
        toggleFullscreen(frame)
    }

    fun toggleFullscreen(window: Window) {
        val frame = owningFrame(window) ?: return
        runOnEdt {
            if (isFullscreen && snapshot?.frame === frame) {
                exitFullscreenInternal(frame)
            } else {
                enterFullscreen(frame)
            }
        }
    }

    fun exitFullscreen() {
        val frame = snapshot?.frame ?: return
        runOnEdt { exitFullscreenInternal(frame) }
    }

    fun exitFullscreen(component: Component) {
        val frame = owningFrame(component) ?: return
        runOnEdt { exitFullscreenInternal(frame) }
    }

    private fun enterFullscreen(frame: Frame) {
        if (isFullscreen && snapshot?.frame === frame) return
        if (isFullscreen) {
            exitFullscreenInternal(snapshot?.frame ?: return)
        }

        val device = frame.graphicsConfiguration?.device
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice

        snapshot = FullscreenSnapshot(
            frame = frame,
            bounds = Rectangle(frame.bounds),
            extendedState = frame.extendedState,
            device = device,
        )

        if (device.isFullScreenSupported) {
            // Exclusive fullscreen - no dispose required
            device.fullScreenWindow = frame
        } else {
            // Fallback: maximise the frame to cover the screen.
            // We do NOT call frame.dispose() here because that destroys
            // the Compose Desktop component hierarchy and causes a crash.
            val screenBounds = device.defaultConfiguration.bounds
            frame.extendedState = Frame.MAXIMIZED_BOTH
            frame.bounds = screenBounds
            frame.toFront()
        }

        isFullscreen = true
        println("TORVE VLC ┃ Entered fullscreen on ${device.iDstring} (exclusive=${device.isFullScreenSupported})")
    }

    private fun exitFullscreenInternal(frame: Frame) {
        val currentSnapshot = snapshot ?: return
        if (currentSnapshot.frame !== frame) return

        val device = currentSnapshot.device

        if (device.fullScreenWindow === frame) {
            device.fullScreenWindow = null
        }

        frame.extendedState = currentSnapshot.extendedState
        frame.bounds = currentSnapshot.bounds
        frame.validate()
        frame.repaint()
        frame.toFront()

        snapshot = null
        isFullscreen = false
        println("TORVE VLC ┃ Exited fullscreen, restored bounds=${currentSnapshot.bounds}")
    }

    private fun owningFrame(component: Component?): Frame? {
        val window = component?.let { SwingUtilities.getWindowAncestor(it) }
        return owningFrame(window)
    }

    private fun owningFrame(window: Window?): Frame? {
        var current = window
        while (current != null && current !is Frame) {
            current = current.owner
        }
        return current as? Frame
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait(block)
        }
    }
}
