package com.torve.desktop.launch

import java.awt.Color
import java.awt.Dimension
import java.awt.Toolkit
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Process-wide handle to the launch-time splash window. Set in `main()`
 * before `application { ... }` starts, disposed by the launch guard
 * after the iconify dance completes. Volatile because it's read by
 * the guard coroutine on the EDT and written from main thread / EDT.
 */
@Volatile
internal var globalLaunchSplash: JWindow? = null

/**
 * Creates and shows a centered, always-on-top splash JWindow for the
 * duration of the Compose Desktop launch race. The splash is shown
 * BEFORE the Compose Window becomes visible and disposed AFTER the
 * iconify dance completes, so the user never sees the black window /
 * minimize-restore cycle that the launch guard requires.
 *
 * Returns the JWindow handle so the caller can dispose it later. If
 * the splash image can't be loaded, returns a text-only splash so we
 * still cover the launch race.
 */
internal fun showTorveLaunchSplash(): JWindow? {
    return runCatching {
        val splash = JWindow()
        splash.isAlwaysOnTop = true
        splash.focusableWindowState = false

        // Try transparent background. On Windows 11 this prevents DWM
        // from painting an accent-colored window border around the
        // splash. Falls back to opaque dark if the GraphicsConfiguration
        // refuses translucency (RDP / VMs / old drivers).
        val transparencyApplied = runCatching {
            splash.background = Color(0, 0, 0, 0)
        }.isSuccess
        if (!transparencyApplied) {
            splash.background = Color(0x0E, 0x14, 0x21)
        }

        val image = loadSplashImage()
        val label: JLabel = if (image != null) {
            JLabel(image)
        } else {
            JLabel("Torve", SwingConstants.CENTER).apply {
                foreground = Color(0xE6, 0xC7, 0x6B)
                font = font.deriveFont(java.awt.Font.BOLD, 64f)
                preferredSize = Dimension(480, 480)
                isOpaque = !transparencyApplied
                background = Color(0x0E, 0x14, 0x21)
            }
        }
        label.isOpaque = !transparencyApplied
        if (label.isOpaque) label.background = Color(0x0E, 0x14, 0x21)
        label.border = BorderFactory.createEmptyBorder()

        splash.contentPane = label
        splash.pack() // size = label preferred = image native size

        // Center on the primary screen.
        val screen = Toolkit.getDefaultToolkit().screenSize
        splash.setLocation(
            (screen.width - splash.width) / 2,
            (screen.height - splash.height) / 2,
        )

        splash.isVisible = true
        splash.toFront()
        splash
    }.onFailure {
        // Splash is decorative; never let its failure block launch.
        launchGuardLog(
            "splash_create_failed",
            "error" to (it.message ?: it::class.simpleName ?: "unknown"),
        )
    }.getOrNull()
}

internal fun dismissTorveLaunchSplash() {
    val splash = globalLaunchSplash ?: return
    globalLaunchSplash = null
    SwingUtilities.invokeLater {
        runCatching {
            splash.isVisible = false
            splash.dispose()
        }
    }
    launchGuardLog("splash_dismissed")
}

/**
 * Loads torve-splash.png from the appResources directory. Compose
 * Desktop's jpackage layout puts these files at $APPDIR/resources/.
 * The same image used by the JDK -splash arg, just rendered into a
 * Swing label here.
 */
private fun loadSplashImage(): ImageIcon? {
    return runCatching {
        val candidates = listOf(
            // Installed jpackage layout: ...\Torve\app\resources\torve-splash.png
            System.getProperty("compose.application.resources.dir")
                ?.let { File(it, "torve-splash.png") },
            // Dev: runtime/common/torve-splash.png
            File("runtime/common/torve-splash.png"),
            File("desktopApp/runtime/common/torve-splash.png"),
        )
        val file = candidates.filterNotNull().firstOrNull { it.isFile } ?: return null
        val raw = ImageIO.read(file) ?: return null
        // Display at the file's native dimensions. Earlier versions
        // scaled to 480 px, which produced a visible jump because the
        // JDK -splash: arg shows the same image at native size first;
        // when our JWindow took over with a smaller version the user
        // saw the splash shrink. Native size matches the handoff.
        ImageIcon(raw)
    }.getOrNull()
}
