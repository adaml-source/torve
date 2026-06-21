package com.torve.desktop.player

import kotlinx.coroutines.runBlocking
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// ── Torve design tokens ─────────────────────────────────────────

private val CHROME_BG = Color(6, 8, 14, 238)
private val CHROME_SURFACE = Color(14, 18, 28, 236)
private val CHROME_PANEL = Color(21, 28, 42, 242)
private val CHROME_PANEL_ALT = Color(29, 36, 54, 242)
private val CHROME_ACCENT = Color(232, 168, 56)
private val CHROME_ACCENT_HOVER = Color(246, 191, 87)
private val CHROME_TEXT = Color(247, 249, 252)
private val CHROME_TEXT_SEC = Color(188, 196, 215)
private val CHROME_TEXT_MUTED = Color(123, 132, 152)
private val CHROME_BORDER = Color(74, 84, 107, 180)
private val CHROME_BORDER_STRONG = Color(123, 136, 166, 210)
private val CHROME_DANGER = Color(233, 92, 92)
private val CHROME_SLIDER_BG = Color(255, 255, 255, 36)
private val CHROME_SLIDER_BUFFER = Color(255, 255, 255, 70)
private val CHROME_DIALOG_BG = Color(12, 15, 24)

private val FONT_BODY = Font("Segoe UI Semibold", Font.PLAIN, 13)
private val FONT_BODY_SMALL = Font("Segoe UI", Font.PLAIN, 12)
private val FONT_LABEL = Font("Segoe UI", Font.PLAIN, 11)
private val FONT_META = Font("Consolas", Font.PLAIN, 11)
private val FONT_TITLE = Font("Segoe UI Semibold", Font.PLAIN, 16)
private val FONT_VALUE = Font("Segoe UI Semibold", Font.PLAIN, 24)

private enum class ChromeIcon {
    BACK, PLAY, PAUSE, STOP, REWIND, FORWARD, AUDIO, SUBTITLE,
    SETTINGS, FULLSCREEN_ENTER, FULLSCREEN_EXIT, VOLUME, VOLUME_MUTED,
}

private enum class ChromeButtonTone { SURFACE, PRIMARY, DANGER }

/**
 * Player chrome that lives as a transparent JPanel inside the video host's
 * [javax.swing.JLayeredPane]. No JWindow. No separate top-level windows.
 * No repositioning timers. The chrome panel is simply shown/hidden within
 * the same component hierarchy as the video surface.
 *
 * All dialog panels (delay, equalizer, diagnostics) use standard decorated
 * JDialogs with OS-native title bars and close buttons.
 */
class VlcPlayerChromeHost(
    private val engine: VlcDesktopPlaybackEngine,
    private var onClose: () -> Unit = {},
    private var onStop: () -> Unit = {},
) {
    private var disposed = false
    private var hideTimer: Timer? = null
    private var updateTimer: Timer? = null
    private var toastMessage: String? = null
    private var toastTimer: Timer? = null
    private val autoHideDelayMs = 2400

    private val topBar = TopInfoBar()
    private val bufferingPanel = BufferingPanel()
    private val controlBar = ControlBar(
        engine = engine,
        onBack = { onClose() },
        onStop = { onStop() },
        onShowToast = ::showToast,
        ownerProvider = { chromePanel.let(SwingUtilities::getWindowAncestor) },
    )

    /** The chrome overlay panel. Add this to a JLayeredPane above the video. */
    val chromePanel: JPanel = OverlayRootPanel().apply {
        layout = BorderLayout(0, 0)
        add(topBar, BorderLayout.NORTH)
        add(bufferingPanel, BorderLayout.CENTER)
        add(controlBar, BorderLayout.SOUTH)
        isVisible = false // start hidden, shown on first interaction
    }

    init {
        addHoverKeepAlive(topBar)
        addHoverKeepAlive(controlBar)
        addHoverKeepAlive(bufferingPanel)
        startTimers()
    }

    fun setTitle(title: String, subtitle: String? = null) {
        topBar.setMediaTitle(title, subtitle)
    }

    fun updateActions(onClose: () -> Unit, onStop: () -> Unit) {
        this.onClose = onClose
        this.onStop = onStop
        controlBar.updateActions(onBack = onClose, onStop = onStop)
    }

    fun showTemporarily() {
        if (disposed) return
        chromePanel.isVisible = true
        chromePanel.repaint()
        restartHideTimer()
    }

    fun isShowing(): Boolean = chromePanel.isVisible

    fun dispose() {
        if (disposed) return
        disposed = true
        stopTimers()
        chromePanel.isVisible = false
    }

    // ── Internal ────────────────────────────────────────────────

    private fun restartHideTimer() {
        if (disposed) return
        if (engine.isPlaying() && !engine.isBuffering()) {
            hideTimer?.restart()
        } else {
            hideTimer?.stop()
        }
    }

    private fun updateState() {
        if (disposed) return
        val diagnostics = engine.readDiagnostics()
        val bufferPercent = engine.getBufferingPercent()

        topBar.updateState(
            playbackState = diagnostics.playbackState,
            bufferingMode = diagnostics.bufferingMode,
            runtimeVersion = diagnostics.vlcVersion,
            toastMessage = toastMessage,
            audioTrack = diagnostics.audioTrack,
            subtitleTrack = diagnostics.subtitleTrack,
        )
        controlBar.updateState(bufferPercent)
        bufferingPanel.updateState(
            visible = bufferPercent != null,
            percent = bufferPercent ?: 0f,
            modeLabel = diagnostics.bufferingMode,
        )

        // Force-show during buffering or pause; otherwise let auto-hide work
        if (bufferPercent != null || engine.isPaused()) {
            if (!chromePanel.isVisible) {
                chromePanel.isVisible = true
            }
            hideTimer?.stop()
        }
    }

    private fun showToast(message: String) {
        toastMessage = message
        showTemporarily()
        toastTimer?.stop()
        toastTimer = Timer(2600) { toastMessage = null }.apply {
            isRepeats = false
            start()
        }
    }

    private fun startTimers() {
        if (disposed) return
        if (hideTimer == null) {
            hideTimer = Timer(autoHideDelayMs) {
                if (engine.isPlaying() && !engine.isBuffering()) {
                    chromePanel.isVisible = false
                }
            }.apply { isRepeats = false }
        }
        if (updateTimer == null) {
            updateTimer = Timer(200) { updateState() }
        }
        if (updateTimer?.isRunning != true) {
            updateTimer?.start()
        }
    }

    private fun stopTimers() {
        hideTimer?.stop()
        updateTimer?.stop()
        toastTimer?.stop()
        toastTimer = null
    }

    private fun addHoverKeepAlive(component: JComponent) {
        component.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) { showTemporarily() }
        })
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hideTimer?.stop() }
            override fun mouseExited(e: MouseEvent) { restartHideTimer() }
        })
    }
}

// ── Overlay root panel (transparent, draws top/bottom gradients) ──

private class OverlayRootPanel : JPanel() {
    init { isOpaque = false }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.paint = GradientPaint(0f, 0f, Color(6, 8, 14, 180), 0f, 120f, Color(6, 8, 14, 0))
        g2.fillRect(0, 0, width, 120)
        g2.paint = GradientPaint(0f, height.toFloat(), Color(6, 8, 14, 220), 0f, (height - 160).toFloat(), Color(6, 8, 14, 0))
        g2.fillRect(0, height - 180, width, 180)
    }
}

// ── Top info bar ────────────────────────────────────────────────

private class TopInfoBar : JPanel() {
    private val titleLabel = JLabel()
    private val subtitleLabel = JLabel()
    private val stateChip = JLabel()
    private val detailChip = JLabel()
    private val toastChip = JLabel()

    init {
        isOpaque = false
        layout = BorderLayout(12, 0)
        border = BorderFactory.createEmptyBorder(18, 24, 8, 24)

        titleLabel.font = FONT_TITLE; titleLabel.foreground = CHROME_TEXT
        subtitleLabel.font = FONT_BODY_SMALL; subtitleLabel.foreground = CHROME_TEXT_SEC

        val left = JPanel().apply {
            isOpaque = false; layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(titleLabel); add(Box.createVerticalStrut(2)); add(subtitleLabel)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(stateChip); add(detailChip); add(toastChip)
        }
        add(left, BorderLayout.WEST)
        add(right, BorderLayout.EAST)

        styleChip(stateChip, CHROME_PANEL_ALT, CHROME_TEXT)
        styleChip(detailChip, CHROME_PANEL, CHROME_TEXT_SEC)
        styleChip(toastChip, Color(28, 42, 60, 236), CHROME_ACCENT)
        toastChip.isVisible = false
    }

    fun setMediaTitle(title: String, subtitle: String?) {
        titleLabel.text = title
        subtitleLabel.text = subtitle ?: ""
        subtitleLabel.isVisible = !subtitle.isNullOrBlank()
    }

    fun updateState(playbackState: String, bufferingMode: String?, runtimeVersion: String?,
                    toastMessage: String?, audioTrack: String?, subtitleTrack: String?) {
        stateChip.text = playbackState
        detailChip.text = listOfNotNull(bufferingMode, audioTrack, subtitleTrack, runtimeVersion).firstOrNull() ?: "Torve"
        toastChip.text = toastMessage ?: ""
        toastChip.isVisible = !toastMessage.isNullOrBlank()
    }

    private fun styleChip(label: JLabel, background: Color, foreground: Color) {
        label.font = FONT_LABEL; label.foreground = foreground
        label.isOpaque = true; label.background = background
        label.border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
    }
}

// ── Buffering panel ─────────────────────────────────────────────

private class BufferingPanel : JPanel() {
    private var visibleState = false
    private var percent = 0f
    private var modeLabel: String? = null

    init { isOpaque = false; preferredSize = Dimension(0, 120) }

    fun updateState(visible: Boolean, percent: Float, modeLabel: String?) {
        visibleState = visible; this.percent = percent; this.modeLabel = modeLabel; repaint()
    }

    override fun paintComponent(g: Graphics) {
        if (!visibleState) return
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val cw = 220; val ch = 74
        val x = (width - cw) / 2; val y = max(24, (height - ch) / 2)
        g2.color = Color(8, 12, 20, 228); g2.fillRoundRect(x, y, cw, ch, 28, 28)
        g2.color = CHROME_BORDER; g2.drawRoundRect(x, y, cw, ch, 28, 28)
        g2.color = CHROME_TEXT; g2.font = FONT_BODY
        g2.drawString("Buffering ${percent.roundToInt()}%", x + 18, y + 28)
        modeLabel?.let { g2.color = CHROME_TEXT_SEC; g2.font = FONT_LABEL; g2.drawString(it, x + 18, y + 46) }
        g2.color = CHROME_SLIDER_BG; g2.fillRoundRect(x + 18, y + 54, cw - 36, 6, 6, 6)
        g2.color = CHROME_ACCENT
        g2.fillRoundRect(x + 18, y + 54, ((cw - 36) * (percent.coerceIn(0f, 100f) / 100f)).roundToInt(), 6, 6, 6)
    }
}

// ── Control bar ─────────────────────────────────────────────────

private class ControlBar(
    private val engine: VlcDesktopPlaybackEngine,
    private var onBack: () -> Unit,
    private var onStop: () -> Unit,
    private val onShowToast: (String) -> Unit,
    private val ownerProvider: () -> Window?,
) : JPanel() {
    private val progressSlider = ChromeSlider()
    private val volumeSlider = ChromeSlider(0, 200)
    private val elapsedLabel = JLabel("0:00")
    private val totalLabel = JLabel("0:00")
    private val statusLabel = JLabel("Ready")
    private val playPauseButton = ChromeIconButton(ChromeIcon.PLAY, ChromeButtonTone.PRIMARY)
    private val stopButton = ChromeIconButton(ChromeIcon.STOP, ChromeButtonTone.SURFACE)
    private val backButton = ChromeIconButton(ChromeIcon.BACK, ChromeButtonTone.SURFACE)
    private val rewindButton = ChromeIconButton(ChromeIcon.REWIND, ChromeButtonTone.SURFACE)
    private val forwardButton = ChromeIconButton(ChromeIcon.FORWARD, ChromeButtonTone.SURFACE)
    private val audioButton = ChromeIconButton(ChromeIcon.AUDIO, ChromeButtonTone.SURFACE)
    private val subtitleButton = ChromeIconButton(ChromeIcon.SUBTITLE, ChromeButtonTone.SURFACE)
    private val settingsButton = ChromeIconButton(ChromeIcon.SETTINGS, ChromeButtonTone.SURFACE)
    private val fullscreenButton = ChromeIconButton(ChromeIcon.FULLSCREEN_ENTER, ChromeButtonTone.SURFACE)
    private val volumeButton = ChromeIconButton(ChromeIcon.VOLUME, ChromeButtonTone.SURFACE)
    private val speedButton = ChromeChipButton("1.00x")
    private var draggingTimeline = false

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, 20, 20, 20)
        layout = BorderLayout()

        val shell = RoundedChromePanel().apply {
            layout = BorderLayout(18, 14)
            border = BorderFactory.createEmptyBorder(16, 18, 16, 18)
        }
        val progressRow = JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            add(elapsedLabel, BorderLayout.WEST)
            add(progressSlider, BorderLayout.CENTER)
            add(totalLabel, BorderLayout.EAST)
        }
        val metaRow = JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false; border = BorderFactory.createEmptyBorder(0, 2, 0, 2)
            add(statusLabel, BorderLayout.WEST)
            add(buildCenterMeta(), BorderLayout.EAST)
        }
        val centerStack = JPanel().apply {
            isOpaque = false; layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(progressRow); add(Box.createVerticalStrut(8)); add(metaRow)
        }
        val bottomRow = JPanel(BorderLayout(16, 0)).apply {
            isOpaque = false
            add(buildLeftGroup(), BorderLayout.WEST)
            add(centerStack, BorderLayout.CENTER)
            add(buildRightGroup(), BorderLayout.EAST)
        }
        shell.add(bottomRow, BorderLayout.CENTER)
        add(shell, BorderLayout.SOUTH)

        listOf(elapsedLabel, totalLabel).forEach { it.font = FONT_META; it.foreground = CHROME_TEXT }
        statusLabel.font = FONT_LABEL; statusLabel.foreground = CHROME_TEXT_SEC

        progressSlider.addChangeListener {
            if (progressSlider.valueIsAdjusting) {
                draggingTimeline = true
                val dur = engine.getLength()
                if (dur > 0) elapsedLabel.text = formatTime((dur * (progressSlider.value / 1000.0)).toLong())
            }
        }
        progressSlider.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                draggingTimeline = false
                val dur = engine.getLength()
                if (dur > 0) engine.seek((dur * (progressSlider.value / 1000.0)).toLong())
            }
        })
        volumeSlider.value = engine.volume.getVolume()
        volumeSlider.addChangeListener { engine.volume.setVolume(volumeSlider.value) }

        backButton.addActionListener { onBack() }
        stopButton.addActionListener { onStop() }
        rewindButton.addActionListener { engine.seekRelative(-10_000) }
        forwardButton.addActionListener { engine.seekRelative(30_000) }
        playPauseButton.addActionListener { runBlocking { if (engine.isPlaying()) engine.pause() else engine.play() } }
        fullscreenButton.addActionListener { ownerProvider()?.let(engine.fullscreen::toggleFullscreen) }
        volumeButton.addActionListener { engine.volume.toggleMute() }
        speedButton.addActionListener { showSpeedMenu(speedButton) }
        audioButton.addActionListener { showAudioMenu(audioButton) }
        subtitleButton.addActionListener { showSubtitleMenu(subtitleButton) }
        settingsButton.addActionListener { showSettingsMenu(settingsButton) }
    }

    fun updateActions(onBack: () -> Unit, onStop: () -> Unit) { this.onBack = onBack; this.onStop = onStop }

    fun updateState(bufferPercent: Float?) {
        val dur = engine.getLength().coerceAtLeast(0)
        val pos = engine.getTime().coerceAtLeast(0)
        if (!draggingTimeline && dur > 0) progressSlider.value = ((pos.toDouble() / dur) * 1000).roundToInt().coerceIn(0, 1000)
        elapsedLabel.text = formatTime(pos); totalLabel.text = formatTime(dur)
        statusLabel.text = buildStatusLine(bufferPercent)
        playPauseButton.icon = if (engine.isPlaying()) ChromeIcon.PAUSE else ChromeIcon.PLAY
        fullscreenButton.icon = if (engine.fullscreen.isFullscreen) ChromeIcon.FULLSCREEN_EXIT else ChromeIcon.FULLSCREEN_ENTER
        volumeButton.icon = if (engine.volume.isMuted()) ChromeIcon.VOLUME_MUTED else ChromeIcon.VOLUME
        speedButton.text = String.format("%.2fx", engine.getPlaybackRate())
        volumeSlider.value = engine.volume.getVolume()
    }

    private fun buildLeftGroup(): JComponent = groupPanel().apply { add(backButton); add(playPauseButton); add(stopButton); add(rewindButton); add(forwardButton) }

    private fun buildRightGroup(): JComponent = JPanel(BorderLayout(10, 0)).apply {
        isOpaque = false
        add(groupPanel().apply { add(speedButton); add(audioButton); add(subtitleButton); add(settingsButton) }, BorderLayout.CENTER)
        add(groupPanel().apply { add(volumeButton); add(volumeSlider); add(fullscreenButton) }, BorderLayout.EAST)
    }

    private fun buildCenterMeta(): JComponent = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
        isOpaque = false
        add(metricLabel("Audio", engine.getSelectedAudioTrackLabel() ?: "Default"))
        add(metricLabel("Subs", engine.getSelectedSubtitleTrackLabel() ?: "Off"))
        add(metricLabel("Cache", engine.getBufferingPreset().label))
    }

    private fun metricLabel(title: String, value: String) = JLabel("$title: $value").apply { font = FONT_LABEL; foreground = CHROME_TEXT_MUTED }

    private fun groupPanel() = RoundedChromePanel(fill = CHROME_PANEL, stroke = CHROME_BORDER, arc = 22).apply { layout = FlowLayout(FlowLayout.LEFT, 8, 8) }

    private fun buildStatusLine(bufferPercent: Float?) = when {
        bufferPercent != null -> "Buffering ${bufferPercent.roundToInt()}%  •  ${engine.getBufferingPreset().label}"
        engine.isPaused() -> "Paused  •  ${engine.currentPlaybackStateLabel()}"
        else -> "${engine.currentPlaybackStateLabel()}  •  ${engine.getBufferingPreset().label}"
    }

    // ── Menus ───────────────────────────────────────────────────

    private fun showSpeedMenu(anchor: JComponent) {
        val popup = chromePopupMenu(); val group = ButtonGroup()
        val rate = engine.getPlaybackRate()
        DesktopPlaybackSpeed.entries.forEach { s ->
            popup.add(radioMenuItem(s.label, abs(s.rate - rate) < 0.01f) { engine.setPlaybackRate(s.rate) }.also(group::add))
        }
        popup.show(anchor, 0, -popup.preferredSize.height)
    }

    private fun showAudioMenu(anchor: JComponent) {
        val popup = chromePopupMenu()
        val tracks = engine.audio.getTracks()
        if (tracks.isEmpty()) popup.add(disabledItem("No audio tracks"))
        else tracks.forEach { t -> popup.add(radioMenuItem(t.label, t.isSelected) { engine.audio.selectTrack(t.id); onShowToast("Audio: ${t.label}") }) }
        popup.addSeparator()
        popup.add(actionItem("Audio Delay -50 ms") { engine.audio.decreaseDelay(); onShowToast("Audio delay ${engine.audio.getDelay()} ms") })
        popup.add(actionItem("Audio Delay +50 ms") { engine.audio.increaseDelay(); onShowToast("Audio delay ${engine.audio.getDelay()} ms") })
        popup.add(actionItem("Audio Sync...") { openDelayDialog(true) })
        popup.show(anchor, 0, -popup.preferredSize.height)
    }

    private fun showSubtitleMenu(anchor: JComponent) {
        val popup = chromePopupMenu()
        popup.add(radioMenuItem("Subtitles Off", !engine.subtitle.isEnabled()) { engine.subtitle.disable(); onShowToast("Subtitles off") })
        val tracks = engine.subtitle.getTracks()
        if (tracks.isNotEmpty()) { popup.addSeparator(); tracks.forEach { t -> popup.add(radioMenuItem(t.label, t.isSelected) { engine.subtitle.selectTrack(t.id); onShowToast("Subs: ${t.label}") }) } }
        popup.addSeparator()
        popup.add(actionItem("Subtitle Delay -50 ms") { engine.subtitle.decreaseDelay(); onShowToast("Sub delay ${engine.subtitle.getDelay()} ms") })
        popup.add(actionItem("Subtitle Delay +50 ms") { engine.subtitle.increaseDelay(); onShowToast("Sub delay ${engine.subtitle.getDelay()} ms") })
        popup.add(actionItem("Subtitle Sync...") { openDelayDialog(false) })
        popup.show(anchor, 0, -popup.preferredSize.height)
    }

    private fun showSettingsMenu(anchor: JComponent) {
        val popup = chromePopupMenu()
        val pictureMenu = JMenu("Picture Mode").apply { styleMenu(this) }
        DesktopAspectMode.entries.forEach { m -> pictureMenu.add(radioMenuItem(m.label, m == engine.getAspectMode()) { engine.setAspectRatio(m); onShowToast("Picture: ${m.label}") }) }
        popup.add(pictureMenu)
        val bufferMenu = JMenu("Buffering").apply { styleMenu(this) }
        DesktopBufferingPreset.entries.forEach { p -> bufferMenu.add(radioMenuItem(p.label, p == engine.getBufferingPreset()) { engine.setBufferingPreset(p); onShowToast("${p.label} selected") }) }
        bufferMenu.addSeparator(); bufferMenu.add(disabledItem("Applies when next stream opens"))
        popup.add(bufferMenu)
        popup.add(actionItem("Audio Sync...") { openDelayDialog(true) })
        popup.add(actionItem("Subtitle Sync...") { openDelayDialog(false) })
        popup.add(actionItem("Equalizer...") { openEqualizerDialog() })
        popup.add(actionItem("Media Info...") { openDiagnosticsDialog() })
        popup.add(actionItem("Take Snapshot") {
            val snap = runCatching { engine.captureSnapshotToLibrary() }.getOrNull()
            onShowToast(if (snap != null) "Snapshot saved" else "Snapshot failed")
        })
        popup.show(anchor, 0, -popup.preferredSize.height)
    }

    // ── Dialogs (standard decorated JDialogs) ───────────────────

    private fun createDialog(title: String): JDialog {
        val owner = ownerProvider()
        val dialog = if (owner is java.awt.Frame) {
            JDialog(owner, title, true)
        } else if (owner is java.awt.Dialog) {
            JDialog(owner, title, true)
        } else {
            JDialog(null as java.awt.Frame?, title, true)
        }
        dialog.background = CHROME_DIALOG_BG
        dialog.contentPane.background = CHROME_DIALOG_BG
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.rootPane.registerKeyboardAction(
            { dialog.dispose() },
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW,
        )
        return dialog
    }

    private fun openDelayDialog(audio: Boolean) {
        val title = if (audio) "Audio Delay" else "Subtitle Delay"
        val getter = if (audio) engine.audio::getDelay else engine.subtitle::getDelay
        val setter = if (audio) engine.audio::setDelay else engine.subtitle::setDelay
        val dialog = createDialog(title)
        dialog.contentPane = DelayPanel(
            title = title,
            description = if (audio) "Adjust audio sync against the current video." else "Adjust subtitle timing against the current dialog.",
            valueProvider = getter, onValueChanged = setter,
        )
        dialog.pack()
        dialog.setLocationRelativeTo(ownerProvider())
        dialog.isVisible = true
    }

    private fun openEqualizerDialog() {
        val dialog = createDialog("Equalizer")
        dialog.contentPane = EqualizerPanel(engine, onShowToast)
        dialog.setSize(640, 620)
        dialog.setLocationRelativeTo(ownerProvider())
        dialog.isVisible = true
    }

    private fun openDiagnosticsDialog() {
        val dialog = createDialog("Media Info")
        dialog.contentPane = DiagnosticsPanel(engine)
        dialog.setSize(620, 520)
        dialog.setLocationRelativeTo(ownerProvider())
        dialog.isVisible = true
    }

    // ── Menu helpers ────────────────────────────────────────────

    private fun chromePopupMenu(): JPopupMenu {
        UIManager.put("PopupMenu.consumeEventOnClose", true)
        return object : JPopupMenu() {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = CHROME_PANEL; g2.fillRoundRect(0, 0, width - 1, height - 1, 18, 18)
                g2.color = CHROME_BORDER_STRONG; g2.drawRoundRect(0, 0, width - 1, height - 1, 18, 18)
            }
        }.apply { isOpaque = false; border = BorderFactory.createEmptyBorder(8, 8, 8, 8) }
    }

    private fun styleMenu(menu: JMenu) { menu.font = FONT_BODY; menu.foreground = CHROME_TEXT; menu.isOpaque = true; menu.background = CHROME_PANEL }

    private fun radioMenuItem(text: String, selected: Boolean, action: () -> Unit) = object : JRadioButtonMenuItem(text, selected) {
        override fun paintComponent(g: Graphics) { paintMenuItem(this, g) }
    }.apply { font = FONT_BODY; foreground = if (selected) CHROME_ACCENT else CHROME_TEXT; background = CHROME_PANEL; border = BorderFactory.createEmptyBorder(8, 12, 8, 12); isOpaque = false; addActionListener { action() } }

    private fun actionItem(text: String, action: () -> Unit) = object : JMenuItem(text) {
        override fun paintComponent(g: Graphics) { paintMenuItem(this, g) }
    }.apply { font = FONT_BODY; foreground = CHROME_TEXT; background = CHROME_PANEL; border = BorderFactory.createEmptyBorder(8, 12, 8, 12); isOpaque = false; addActionListener { action() } }

    private fun disabledItem(text: String) = actionItem(text) {}.apply { isEnabled = false; foreground = CHROME_TEXT_MUTED }

    private fun paintMenuItem(item: JMenuItem, g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = when { !item.isEnabled -> CHROME_PANEL; item.model.isArmed || item.model.isSelected -> Color(40, 53, 77); item.model.isRollover -> Color(34, 45, 66); else -> CHROME_PANEL }
        g2.fillRoundRect(0, 0, item.width, item.height, 12, 12)
        g2.color = item.foreground; g2.font = item.font
        g2.drawString(item.text, 14, (item.height + g2.fontMetrics.ascent - g2.fontMetrics.descent) / 2)
    }
}

// ── Delay panel ─────────────────────────────────────────────────

private class DelayPanel(
    title: String, description: String, valueProvider: () -> Long, onValueChanged: (Long) -> Unit,
) : RoundedChromePanel(fill = CHROME_DIALOG_BG, stroke = CHROME_BORDER_STRONG, arc = 24) {
    init {
        layout = BorderLayout(0, 18)
        border = BorderFactory.createEmptyBorder(20, 22, 20, 22)
        val descriptionLabel = JLabel(description).apply { font = FONT_BODY_SMALL; foreground = CHROME_TEXT_SEC }
        val valueLabel = JLabel("${valueProvider()} ms", SwingConstants.CENTER).apply { font = FONT_VALUE; foreground = CHROME_ACCENT }
        val slider = ChromeSlider(-10_000, 10_000).apply { value = valueProvider().toInt() }
        slider.addChangeListener { valueLabel.text = "${slider.value} ms"; onValueChanged(slider.value.toLong()) }
        add(JPanel().apply { isOpaque = false; layout = BoxLayout(this, BoxLayout.Y_AXIS); add(JLabel(title).apply { font = FONT_TITLE; foreground = CHROME_TEXT }); add(Box.createVerticalStrut(4)); add(descriptionLabel) }, BorderLayout.NORTH)
        add(valueLabel, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 14)).apply {
            isOpaque = false; add(slider, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
                isOpaque = false
                add(dialogButton("-250") { slider.value -= 250 }); add(dialogButton("-50") { slider.value -= 50 })
                add(dialogButton("Reset") { slider.value = 0 }); add(dialogButton("+50") { slider.value += 50 }); add(dialogButton("+250") { slider.value += 250 })
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }
}

// ── Equalizer panel ─────────────────────────────────────────────

private class EqualizerPanel(
    private val engine: VlcDesktopPlaybackEngine, private val onShowToast: (String) -> Unit,
) : RoundedChromePanel(fill = CHROME_DIALOG_BG, stroke = CHROME_BORDER_STRONG, arc = 24) {
    init {
        layout = BorderLayout(0, 12); border = BorderFactory.createEmptyBorder(20, 22, 20, 22)
        val snapshot = engine.equalizer.snapshot()
        val enableBox = JCheckBox("Enable equalizer").apply { isSelected = snapshot.enabled; font = FONT_BODY; foreground = CHROME_TEXT; isOpaque = false }
        val presetCombo = JComboBox(snapshot.presets.toTypedArray()).apply {
            selectedItem = snapshot.presetName; font = FONT_BODY_SMALL
            foreground = CHROME_TEXT; background = CHROME_PANEL
            renderer = javax.swing.DefaultListCellRenderer().apply { foreground = CHROME_TEXT; background = CHROME_PANEL }
        }
        val preampSlider = ChromeSlider(snapshot.minLevel.roundToInt(), snapshot.maxLevel.roundToInt()).apply { value = snapshot.preamp.roundToInt() }
        val content = JPanel().apply { isOpaque = false; layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        if (!snapshot.available) {
            content.add(JLabel("Equalizer unavailable until VLC is initialized.").apply { foreground = CHROME_TEXT_SEC; font = FONT_BODY })
        } else {
            content.add(enableBox); content.add(Box.createVerticalStrut(14)); content.add(sectionHeader("Preset")); content.add(presetCombo)
            content.add(Box.createVerticalStrut(16)); content.add(sectionHeader("Preamp")); content.add(preampSlider)
            content.add(Box.createVerticalStrut(16)); content.add(sectionHeader("Bands"))
            snapshot.bands.forEach { band ->
                val bs = ChromeSlider(snapshot.minLevel.roundToInt(), snapshot.maxLevel.roundToInt()).apply { value = band.level.roundToInt() }
                content.add(JPanel(BorderLayout(10, 0)).apply {
                    isOpaque = false; border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
                    add(JLabel(band.label).apply { font = FONT_LABEL; foreground = CHROME_TEXT_SEC; preferredSize = Dimension(72, 24) }, BorderLayout.WEST)
                    add(bs, BorderLayout.CENTER)
                })
                bs.addChangeListener { engine.equalizer.setBandLevel(band.index, bs.value.toFloat()) }
            }
        }
        enableBox.addActionListener { engine.equalizer.setEnabled(enableBox.isSelected); onShowToast(if (enableBox.isSelected) "Equalizer on" else "Equalizer off") }
        presetCombo.addActionListener { (presetCombo.selectedItem as? String)?.let { engine.equalizer.applyPreset(it); onShowToast("Preset: $it") } }
        preampSlider.addChangeListener { engine.equalizer.setPreamp(preampSlider.value.toFloat()) }
        add(JLabel("Equalizer").apply { font = FONT_TITLE; foreground = CHROME_TEXT }, BorderLayout.NORTH)
        add(JScrollPane(content).apply { border = BorderFactory.createEmptyBorder(); viewport.isOpaque = false; isOpaque = false; background = CHROME_DIALOG_BG; viewport.background = CHROME_DIALOG_BG }, BorderLayout.CENTER)
    }
}

// ── Diagnostics panel ───────────────────────────────────────────

private class DiagnosticsPanel(private val engine: VlcDesktopPlaybackEngine) : RoundedChromePanel(fill = CHROME_DIALOG_BG, stroke = CHROME_BORDER_STRONG, arc = 24) {
    private val grid = JPanel(GridBagLayout())
    init {
        layout = BorderLayout(0, 12); border = BorderFactory.createEmptyBorder(20, 22, 20, 22)
        grid.isOpaque = false
        add(JLabel("Media Diagnostics").apply { font = FONT_TITLE; foreground = CHROME_TEXT }, BorderLayout.NORTH)
        add(JScrollPane(grid).apply { border = BorderFactory.createEmptyBorder(); viewport.isOpaque = false; isOpaque = false; background = CHROME_DIALOG_BG; viewport.background = CHROME_DIALOG_BG }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false; add(dialogButton("Refresh") { refresh() }) }, BorderLayout.SOUTH)
        refresh()
    }
    private fun refresh() {
        grid.removeAll(); val d = engine.readDiagnostics()
        listOf("Source" to (d.mediaUrl ?: "N/A"), "Type" to (d.sourceType ?: "Unknown"), "State" to d.playbackState,
            "Duration" to formatTime(d.durationMs), "Position" to formatTime(d.currentTimeMs),
            "Audio" to (d.audioTrack ?: "Default"), "Subtitle" to (d.subtitleTrack ?: "Off"),
            "Speed" to String.format("%.2fx", d.playbackSpeed), "Buffer" to (d.bufferingMode ?: "Auto"),
            "Audio Delay" to "${d.audioDelay} ms", "Sub Delay" to "${d.subtitleDelay} ms",
            "Picture" to (d.aspectMode ?: engine.getAspectMode().label),
            "Runtime" to (d.runtimePath ?: "Unknown"), "Version" to (d.vlcVersion ?: "Unknown"),
        ).forEachIndexed { i, (l, v) -> addRow(i, l, v) }
        grid.revalidate(); grid.repaint()
    }
    private fun addRow(i: Int, label: String, value: String) {
        grid.add(JLabel(label).apply { font = FONT_LABEL; foreground = CHROME_TEXT_MUTED }, GridBagConstraints().apply { gridx = 0; gridy = i; anchor = GridBagConstraints.NORTHWEST; insets = Insets(0, 0, 10, 18) })
        grid.add(JLabel("<html>${value.replace("\n", "<br/>")}</html>").apply { font = FONT_BODY_SMALL; foreground = CHROME_TEXT }, GridBagConstraints().apply { gridx = 1; gridy = i; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0, 0, 10, 0) })
    }
}

// ── Shared components ───────────────────────────────────────────

private open class RoundedChromePanel(private val fill: Color = CHROME_SURFACE, private val stroke: Color = CHROME_BORDER, private val arc: Int = 28) : JPanel() {
    init { isOpaque = false }
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D; g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = fill; g2.fill(RoundRectangle2D.Float(0f, 0f, (width - 1).toFloat(), (height - 1).toFloat(), arc.toFloat(), arc.toFloat()))
        g2.color = stroke; g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, (width - 2).toFloat(), (height - 2).toFloat(), arc.toFloat(), arc.toFloat()))
        super.paintComponent(g)
    }
}

private class ChromeIconButton(var icon: ChromeIcon, private val tone: ChromeButtonTone) : JButton() {
    init {
        isOpaque = false; isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
        border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        preferredSize = Dimension(if (tone == ChromeButtonTone.PRIMARY) 50 else 42, if (tone == ChromeButtonTone.PRIMARY) 50 else 42)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D; g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = when (tone) {
            ChromeButtonTone.PRIMARY -> when { model.isPressed -> CHROME_ACCENT_HOVER.darker(); model.isRollover -> CHROME_ACCENT_HOVER; else -> CHROME_ACCENT }
            ChromeButtonTone.DANGER -> when { model.isPressed -> Color(186, 66, 66); model.isRollover -> Color(214, 82, 82); else -> CHROME_DANGER }
            ChromeButtonTone.SURFACE -> when { model.isPressed -> Color(44, 58, 82); model.isRollover -> Color(56, 71, 98); else -> CHROME_PANEL_ALT }
        }
        g2.fillRoundRect(0, 0, width, height, 18, 18)
        g2.color = if (tone == ChromeButtonTone.PRIMARY) CHROME_BG else CHROME_TEXT
        paintIcon(g2, icon, width, height)
    }
}

private class ChromeChipButton(text: String) : JButton(text) {
    init {
        font = FONT_BODY; foreground = CHROME_TEXT; isOpaque = false; isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
        border = BorderFactory.createEmptyBorder(10, 14, 10, 14); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D; g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = if (model.isRollover) Color(56, 71, 98) else CHROME_PANEL_ALT
        g2.fillRoundRect(0, 0, width, height, 20, 20); g2.color = CHROME_TEXT; g2.font = font
        g2.drawString(text, 14, (height + g2.fontMetrics.ascent - g2.fontMetrics.descent) / 2)
    }
}

private class ChromeSlider(minimum: Int = 0, maximum: Int = 1000) : JSlider(minimum, maximum) {
    init { isOpaque = false }
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D; g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val th = 6; val knob = 12; val y = height / 2 - th / 2
        g2.color = CHROME_SLIDER_BG; g2.fillRoundRect(0, y, width, th, 6, 6)
        val filled = ((value - minimum).toDouble() / (maximum - minimum).coerceAtLeast(1) * width).roundToInt()
        g2.color = CHROME_ACCENT; g2.fillRoundRect(0, y, filled, th, 6, 6)
        g2.color = CHROME_TEXT; g2.fillOval((filled - knob / 2).coerceIn(0, width - knob), height / 2 - knob / 2, knob, knob)
    }
}

private fun sectionHeader(text: String) = JLabel(text).apply { font = FONT_BODY; foreground = CHROME_TEXT; border = BorderFactory.createEmptyBorder(0, 0, 8, 0) }
private fun dialogButton(text: String, action: () -> Unit) = ChromeChipButton(text).apply { addActionListener { action() } }
private fun formatTime(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60; return if (h > 0) String.format("%d:%02d:%02d", h, m, ss) else String.format("%d:%02d", m, ss) }

// ── Icon painting ───────────────────────────────────────────────

private fun paintIcon(g2: Graphics2D, icon: ChromeIcon, w: Int, h: Int) {
    g2.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    when (icon) {
        ChromeIcon.BACK -> { g2.drawLine(w/2+6, h/2-10, w/2-6, h/2); g2.drawLine(w/2-6, h/2, w/2+6, h/2+10) }
        ChromeIcon.PLAY -> { val p = Path2D.Float(); p.moveTo((w*0.38).toDouble(), (h*0.28).toDouble()); p.lineTo((w*0.72).toDouble(), (h*0.5).toDouble()); p.lineTo((w*0.38).toDouble(), (h*0.72).toDouble()); p.closePath(); g2.fill(p) }
        ChromeIcon.PAUSE -> { g2.fillRoundRect(w/2-8, h/2-10, 5, 20, 4, 4); g2.fillRoundRect(w/2+3, h/2-10, 5, 20, 4, 4) }
        ChromeIcon.STOP -> g2.fillRoundRect(w/2-8, h/2-8, 16, 16, 5, 5)
        ChromeIcon.REWIND -> { tri(g2, w/2+2, h/2, -1); tri(g2, w/2+11, h/2, -1) }
        ChromeIcon.FORWARD -> { tri(g2, w/2-2, h/2, 1); tri(g2, w/2-11, h/2, 1) }
        ChromeIcon.AUDIO -> { g2.drawLine(w/2-10,h/2+6,w/2-4,h/2+6); g2.drawLine(w/2-4,h/2+6,w/2+3,h/2+12); g2.drawLine(w/2+3,h/2+12,w/2+3,h/2-12); g2.drawLine(w/2+3,h/2-12,w/2-4,h/2-6); g2.drawLine(w/2-4,h/2-6,w/2-10,h/2-6); g2.drawArc(w/2+1,h/2-10,12,20,-45,90) }
        ChromeIcon.SUBTITLE -> { g2.drawRoundRect(w/2-12,h/2-8,24,16,6,6); g2.drawLine(w/2-6,h/2+3,w/2-1,h/2+3); g2.drawLine(w/2+2,h/2+3,w/2+7,h/2+3) }
        ChromeIcon.SETTINGS -> { repeat(3) { i -> val y=h/2-8+i*8; g2.drawLine(w/2-10,y,w/2+10,y); g2.fillOval(w/2-2+if(i%2==0)-5 else 5,y-3,6,6) } }
        ChromeIcon.FULLSCREEN_ENTER -> { for(dx in listOf(-1,1)) for(dy in listOf(-1,1)) { val cx=w/2+dx*9; val cy=h/2+dy*9; g2.drawLine(cx,h/2+dy*2,cx,cy); g2.drawLine(cx,cy,w/2+dx*2,cy) } }
        ChromeIcon.FULLSCREEN_EXIT -> { for(dx in listOf(-1,1)) for(dy in listOf(-1,1)) { val cx=w/2+dx*2; val cy=h/2+dy*2; g2.drawLine(cx,h/2+dy*9,cx,cy); g2.drawLine(cx,cy,w/2+dx*9,cy) } }
        ChromeIcon.VOLUME -> { paintIcon(g2, ChromeIcon.AUDIO, w, h); g2.drawArc(w/2+2,h/2-12,16,24,-45,90) }
        ChromeIcon.VOLUME_MUTED -> { paintIcon(g2, ChromeIcon.AUDIO, w, h); g2.drawLine(w/2+6,h/2-8,w/2+16,h/2+8); g2.drawLine(w/2+16,h/2-8,w/2+6,h/2+8) }
    }
}

private fun tri(g2: Graphics2D, cx: Int, cy: Int, dir: Int) {
    val p = Path2D.Float(); p.moveTo((cx-5*dir).toDouble(), (cy-7).toDouble()); p.lineTo((cx+5*dir).toDouble(), cy.toDouble()); p.lineTo((cx-5*dir).toDouble(), (cy+7).toDouble()); p.closePath(); g2.fill(p)
}
