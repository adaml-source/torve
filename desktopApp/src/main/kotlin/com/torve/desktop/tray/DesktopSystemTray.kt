package com.torve.desktop.tray

import java.awt.AWTException
import java.awt.Image
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Toolkit

/**
 * A Continue-Watching item surfaced into the tray submenu.
 *
 * [label] is shown in the menu (show title + episode marker if any).
 */
data class TrayContinueWatchingItem(
    val mediaId: String,
    val label: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

/**
 * AWT SystemTray wrapper with:
 *  - Show Torve, Play/Pause, Quit actions.
 *  - A live "Continue Watching" submenu populated via [setContinueWatching].
 * Silently no-ops on platforms where SystemTray isn't available.
 */
class DesktopSystemTray(
    private val onShowWindow: () -> Unit,
    private val onTogglePlayback: () -> Unit,
    private val onQuit: () -> Unit,
) {
    /** Optional - set by the shell once the runtime is available. */
    var onPlayContinueWatching: (TrayContinueWatchingItem) -> Unit = {}
    private var trayIcon: TrayIcon? = null
    private var continueWatching: List<TrayContinueWatchingItem> = emptyList()

    fun install() {
        if (!SystemTray.isSupported()) {
            println("TORVE TRAY | SystemTray not supported on this platform")
            return
        }
        val image = loadIcon() ?: run {
            println("TORVE TRAY | icon missing, skipping tray install")
            return
        }

        trayIcon = TrayIcon(image, "Torve", buildPopup()).apply {
            isImageAutoSize = true
            toolTip = "Torve"
            addActionListener { onShowWindow() }
        }
        try {
            SystemTray.getSystemTray().add(trayIcon)
        } catch (e: AWTException) {
            println("TORVE TRAY | failed to register tray icon: ${e.message}")
            trayIcon = null
        }
    }

    /**
     * Refresh the Continue-Watching submenu with fresh items. Top 5 shown.
     */
    fun setContinueWatching(items: List<TrayContinueWatchingItem>) {
        continueWatching = items.take(5)
        trayIcon?.popupMenu = buildPopup()
    }

    fun release() {
        trayIcon?.let { icon ->
            runCatching { SystemTray.getSystemTray().remove(icon) }
        }
        trayIcon = null
    }

    /**
     * Native OS notification through the tray icon. Used by the EPG reminder
     * scheduler when a programme is about to start. No-ops if the tray icon
     * was never installed (headless / unsupported platform).
     */
    fun notify(title: String, body: String) {
        trayIcon?.displayMessage(title, body, TrayIcon.MessageType.INFO)
    }

    private fun buildPopup(): PopupMenu = PopupMenu().apply {
        add(MenuItem("Show Torve").apply { addActionListener { onShowWindow() } })
        add(MenuItem("Play / Pause").apply { addActionListener { onTogglePlayback() } })
        if (continueWatching.isNotEmpty()) {
            val submenu = Menu("Continue Watching")
            continueWatching.forEach { item ->
                submenu.add(MenuItem(item.label).apply {
                    addActionListener { onPlayContinueWatching(item) }
                })
            }
            add(submenu)
        }
        addSeparator()
        add(MenuItem("Quit").apply { addActionListener { onQuit() } })
    }

    private fun loadIcon(): Image? {
        val stream = javaClass.getResourceAsStream("/torve_icon.png") ?: return null
        val bytes = stream.use { it.readAllBytes() }
        return Toolkit.getDefaultToolkit().createImage(bytes)
    }
}
