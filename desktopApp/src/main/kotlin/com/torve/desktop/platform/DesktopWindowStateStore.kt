package com.torve.desktop.platform

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.io.File
import java.util.Properties

/**
 * Persists and restores `WindowState` (size + position + maximized flag) across
 * launches via a local properties file so the app opens where the user left it.
 */
object DesktopWindowStateStore {
    private const val FILE_NAME = "window-state.properties"
    private const val DEFAULT_WIDTH = 1024
    private const val DEFAULT_HEIGHT = 720

    fun restore(): WindowState {
        val props = read() ?: return WindowState(
            size = DpSize(DEFAULT_WIDTH.dp, DEFAULT_HEIGHT.dp),
        )
        val width = props.getProperty("width")?.toFloatOrNull() ?: DEFAULT_WIDTH.toFloat()
        val height = props.getProperty("height")?.toFloatOrNull() ?: DEFAULT_HEIGHT.toFloat()
        val x = props.getProperty("x")?.toFloatOrNull()
        val y = props.getProperty("y")?.toFloatOrNull()
        val candidate: WindowPosition = if (x != null && y != null && x >= 0 && y >= 0) {
            WindowPosition(x.dp, y.dp)
        } else {
            WindowPosition.PlatformDefault
        }
        return WindowState(
            size = DpSize(width.dp, height.dp),
            position = clampToVisibleScreens(candidate),
        )
    }

    /**
     * Returns [position] if it falls inside ANY currently-connected screen's
     * visible bounds, otherwise [WindowPosition.PlatformDefault]. Used during
     * window restore so a saved position on a now-disconnected monitor falls
     * back gracefully instead of painting off-screen.
     *
     * Headless / no-screen environments (CI) return [WindowPosition.PlatformDefault]
     * because `any()` over an empty list is false.
     */
    internal fun clampToVisibleScreens(position: WindowPosition): WindowPosition {
        if (position !is WindowPosition.Absolute) return position
        val ge = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        }.getOrNull() ?: return WindowPosition.PlatformDefault
        val px = position.x.value.toInt()
        val py = position.y.value.toInt()
        val anyContains = ge.screenDevices.any { device ->
            device.defaultConfiguration.bounds.contains(px, py)
        }
        return if (anyContains) position else WindowPosition.PlatformDefault
    }

    fun save(state: WindowState) {
        runCatching {
            val props = Properties().apply {
                setProperty("width", state.size.width.value.toString())
                setProperty("height", state.size.height.value.toString())
                val pos = state.position
                if (pos is WindowPosition.Absolute) {
                    setProperty("x", pos.x.value.toString())
                    setProperty("y", pos.y.value.toString())
                }
            }
            file().outputStream().use { props.store(it, "Torve window state") }
        }
    }

    private fun read(): Properties? {
        val f = file()
        if (!f.exists()) return null
        return runCatching {
            Properties().apply { f.inputStream().use { load(it) } }
        }.getOrNull()
    }

    private fun file(): File {
        val dir = desktopDataDir()
        dir.mkdirs()
        return File(dir, FILE_NAME)
    }
}
