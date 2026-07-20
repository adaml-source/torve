package com.torve.desktop.platform

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Window
import java.util.WeakHashMap
import javax.swing.JFrame
import javax.swing.JMenuBar

object DesktopBorderlessFullscreen {
    private data class Snapshot(
        val style: Int,
        val exStyle: Int,
        val bounds: Rectangle,
        val extendedState: Int,
        val awtMenuBar: java.awt.MenuBar?,
        val swingMenuBar: JMenuBar?,
    )

    private val snapshots = WeakHashMap<Frame, Snapshot>()

    fun apply(frame: Frame, fullscreen: Boolean) {
        if (!isWindows()) return
        if (fullscreen) enter(frame) else exit(frame)
    }

    fun apply(window: Window?, fullscreen: Boolean) {
        val frame = owningFrame(window) ?: return
        apply(frame, fullscreen)
    }

    private fun enter(frame: Frame) {
        if (!frame.isDisplayable) return
        val hwnd = hwnd(frame) ?: return
        if (!snapshots.containsKey(frame)) {
            snapshots[frame] = Snapshot(
                style = User32.INSTANCE.GetWindowLong(hwnd, GWL_STYLE),
                exStyle = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE),
                bounds = Rectangle(frame.bounds),
                extendedState = frame.extendedState,
                awtMenuBar = frame.menuBar,
                swingMenuBar = (frame as? JFrame)?.jMenuBar,
            )
        }

        frame.menuBar = null
        (frame as? JFrame)?.jMenuBar = null

        val style = User32.INSTANCE.GetWindowLong(hwnd, GWL_STYLE)
        val borderlessStyle = style and WS_CAPTION.inv() and WS_THICKFRAME.inv() and
            WS_MINIMIZEBOX.inv() and WS_MAXIMIZEBOX.inv() and WS_SYSMENU.inv()
        User32.INSTANCE.SetWindowLong(hwnd, GWL_STYLE, borderlessStyle)

        val bounds = frame.graphicsConfiguration?.bounds
            ?: frame.graphicsConfiguration?.device?.defaultConfiguration?.bounds
            ?: return
        User32.INSTANCE.SetWindowPos(
            hwnd,
            null,
            bounds.x,
            bounds.y,
            bounds.width,
            bounds.height,
            SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED,
        )
        frame.validate()
        frame.repaint()
    }

    private fun exit(frame: Frame) {
        val snapshot = snapshots.remove(frame) ?: return
        if (!frame.isDisplayable) return
        val hwnd = hwnd(frame) ?: return
        User32.INSTANCE.SetWindowLong(hwnd, GWL_STYLE, snapshot.style)
        User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, snapshot.exStyle)
        frame.menuBar = snapshot.awtMenuBar
        (frame as? JFrame)?.jMenuBar = snapshot.swingMenuBar
        frame.extendedState = snapshot.extendedState
        User32.INSTANCE.SetWindowPos(
            hwnd,
            null,
            snapshot.bounds.x,
            snapshot.bounds.y,
            snapshot.bounds.width,
            snapshot.bounds.height,
            SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED,
        )
        frame.validate()
        frame.repaint()
    }

    private fun hwnd(frame: Frame): WinDef.HWND? = runCatching {
        WinDef.HWND(Native.getComponentPointer(frame))
    }.getOrNull()

    private fun owningFrame(window: Window?): Frame? {
        var current = window
        while (current != null && current !is Frame) {
            current = current.owner
        }
        return current
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").contains("win", ignoreCase = true)
}

private const val GWL_STYLE = -16
private const val GWL_EXSTYLE = -20

private const val WS_CAPTION = 0x00C00000
private const val WS_THICKFRAME = 0x00040000
private const val WS_SYSMENU = 0x00080000
private const val WS_MINIMIZEBOX = 0x00020000
private const val WS_MAXIMIZEBOX = 0x00010000

private const val SWP_NOZORDER = 0x0004
private const val SWP_NOACTIVATE = 0x0010
private const val SWP_FRAMECHANGED = 0x0020
