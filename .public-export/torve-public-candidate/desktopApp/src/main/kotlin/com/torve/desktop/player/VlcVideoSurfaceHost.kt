package com.torve.desktop.player

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Callbacks from the video surface to the player host.
 */
class VlcSurfaceCallbacks {
    var onPlayPauseToggle: () -> Unit = {}
    var onStop: () -> Unit = {}
    var onSeekBack10: () -> Unit = {}
    var onSeekForward30: () -> Unit = {}
    var onVolumeUp: () -> Unit = {}
    var onVolumeDown: () -> Unit = {}
    var onMuteToggle: () -> Unit = {}
    var onFullscreenToggle: () -> Unit = {}
    var onEscPressed: () -> Unit = {}
    var onMouseMoved: () -> Unit = {}
    var onDoubleClick: () -> Unit = {}
    var onSubtitleDelayIncrease: () -> Unit = {}
    var onSubtitleDelayDecrease: () -> Unit = {}
    var onAudioDelayIncrease: () -> Unit = {}
    var onAudioDelayDecrease: () -> Unit = {}
}

/**
 * JPanel that hosts the VLC video surface and the chrome overlay controls
 * in the SAME component using a [JLayeredPane].
 *
 * Because the engine uses [uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent]
 * (lightweight Java2D rendering), standard Swing z-ordering works correctly.
 * No JWindow overlays. No separate top-level windows. No repositioning timers.
 * The chrome panel simply sits above the video panel in the layered pane.
 */
class VlcVideoSurfaceHost(
    private val engine: VlcDesktopPlaybackEngine,
    private var callbacks: VlcSurfaceCallbacks,
    private val chromeHost: VlcPlayerChromeHost,
) : JPanel(BorderLayout()) {

    private var disposed = false
    private val vlcComponent = engine.videoSurfaceComponent
    private val layeredPane = JLayeredPane()
    private val chromePanel: JPanel = chromeHost.chromePanel

    private val resizeListener = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            val size = layeredPane.size
            vlcComponent?.setBounds(0, 0, size.width, size.height)
            chromePanel.setBounds(0, 0, size.width, size.height)
        }
    }
    private val mouseMotionListener = object : MouseMotionAdapter() {
        override fun mouseMoved(e: MouseEvent) {
            callbacks.onMouseMoved()
            chromeHost.showTemporarily()
        }
    }
    private val mouseListener = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (disposed) return
            layeredPane.requestFocusInWindow()
            when {
                e.clickCount == 2 && e.button == MouseEvent.BUTTON1 -> {
                    engine.fullscreen.toggleFullscreen(this@VlcVideoSurfaceHost)
                    chromeHost.showTemporarily()
                }
                e.button == MouseEvent.BUTTON1 -> {
                    if (!chromeHost.isShowing()) chromeHost.showTemporarily()
                    else callbacks.onPlayPauseToggle()
                }
            }
        }
    }
    private val keyListener = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (disposed) return
            when (e.keyCode) {
                KeyEvent.VK_SPACE -> callbacks.onPlayPauseToggle()
                KeyEvent.VK_ESCAPE -> callbacks.onEscPressed()
                KeyEvent.VK_F, KeyEvent.VK_F11 -> engine.fullscreen.toggleFullscreen(this@VlcVideoSurfaceHost)
                KeyEvent.VK_LEFT -> callbacks.onSeekBack10()
                KeyEvent.VK_RIGHT -> callbacks.onSeekForward30()
                KeyEvent.VK_UP -> callbacks.onVolumeUp()
                KeyEvent.VK_DOWN -> callbacks.onVolumeDown()
                KeyEvent.VK_M -> callbacks.onMuteToggle()
                KeyEvent.VK_S -> callbacks.onStop()
                KeyEvent.VK_H -> callbacks.onSubtitleDelayDecrease()
                KeyEvent.VK_J -> callbacks.onSubtitleDelayIncrease()
                KeyEvent.VK_K -> callbacks.onAudioDelayDecrease()
                KeyEvent.VK_L -> callbacks.onAudioDelayIncrease()
            }
            chromeHost.showTemporarily()
        }
    }

    init {
        background = Color.BLACK
        isOpaque = true

        if (vlcComponent != null) {
            layeredPane.add(vlcComponent, JLayeredPane.DEFAULT_LAYER)
        }
        layeredPane.add(chromePanel, JLayeredPane.PALETTE_LAYER)

        add(layeredPane, BorderLayout.CENTER)

        // Keep both layers filling the entire area on resize
        addComponentListener(resizeListener)

        // Install input listeners on this host panel
        addMouseMotionListener(mouseMotionListener)
        addMouseListener(mouseListener)
        addKeyListener(keyListener)
        isFocusable = true

        log("created")
    }

    fun requestVideoFocus() {
        if (disposed) return
        SwingUtilities.invokeLater { if (!disposed) requestFocusInWindow() }
    }

    fun updateCallbacks(nextCallbacks: VlcSurfaceCallbacks) {
        callbacks = nextCallbacks
    }

    fun showChrome(reason: String = "manual") {
        if (disposed) return
        chromeHost.showTemporarily()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        log("dispose-start")
        removeComponentListener(resizeListener)
        removeMouseMotionListener(mouseMotionListener)
        removeMouseListener(mouseListener)
        removeKeyListener(keyListener)
        chromeHost.dispose()
        if (vlcComponent?.parent === layeredPane) layeredPane.remove(vlcComponent)
        if (chromePanel.parent === layeredPane) layeredPane.remove(chromePanel)
        revalidate(); repaint()
        log("dispose-complete")
    }

    private fun log(action: String, detail: String? = null) {
        val detailSuffix = detail?.let { " | detail=$it" } ?: ""
        println("TORVE VLC SURFACE | action=$action | disposed=$disposed$detailSuffix")
    }
}
