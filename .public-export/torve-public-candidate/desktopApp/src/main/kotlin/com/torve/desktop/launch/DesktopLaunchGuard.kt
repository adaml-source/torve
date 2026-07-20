package com.torve.desktop.launch

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.delay
import java.awt.Frame
import java.io.File
import java.time.Instant
import javax.swing.JWindow

/**
 * Minimal Dwmapi.dll binding. jna-platform 5.14 doesn't ship a
 * pre-built Dwmapi interface, so we declare just the one function we
 * need.
 */
private interface Dwmapi : Library {
    fun DwmSetWindowAttribute(
        hwnd: WinDef.HWND,
        dwAttribute: Int,
        pvAttribute: IntByReference,
        cbAttribute: Int,
    ): WinNT.HRESULT

    companion object {
        val INSTANCE: Dwmapi = Native.load("dwmapi", Dwmapi::class.java)
    }
}

// DWMWA_TRANSITIONS_FORCEDISABLED. Disables all DWM-driven window
// transitions for the specified window: minimize, restore, maximize.
// Pass 1 to disable, 0 to re-enable.
private const val DWMWA_TRANSITIONS_FORCEDISABLED = 3

/**
 * Windows Compose Desktop launch-guard strategies. The default Skiko
 * Direct3D backend has a first-surface init race on cold launches: the
 * JFrame becomes visible before DWM has attached its composition surface
 * to Skiko's swapchain, leaving the user staring at a black window until
 * something forces a full repaint. The historical workaround is to
 * minimize-restore the main JFrame, which works but blinks the taskbar.
 *
 * Strategies, lightest to heaviest:
 *   off      - no guard at all (for verifying the bug still reproduces)
 *   iconify  - the original minimize-restore (taskbar blink, but reliable)
 *   focus    - pure-AWT focus-cycle through a 4x4 transparent JWindow
 *   native   - JNA SetForegroundWindow + RedrawWindow on the main HWND
 *
 * Override at runtime with TORVE_LAUNCH_GUARD={off|iconify|focus|native}
 * so support can disable or downgrade without a rebuild.
 */
internal enum class LaunchGuardStrategy(val token: String) {
    OFF("off"),
    ICONIFY("iconify"),
    FOCUS("focus"),
    NATIVE("native"),
    SHOWHIDE("showhide"),
    SWPFRAME("swpframe"),
    /**
     * Iconify dance, but with the JFrame's taskbar entry suppressed via
     * WS_EX_TOOLWINDOW for the duration of the blink. The window can't
     * blink a taskbar entry it doesn't have. After the iconify cycle
     * completes, the original extended style is restored and the
     * taskbar entry comes back.
     */
    ICONIFY_NOTASKBAR("iconify_notaskbar"),
    /**
     * Iconify dance with DWM transitions disabled for the duration via
     * DwmSetWindowAttribute(DWMWA_TRANSITIONS_FORCEDISABLED). The
     * minimize and restore happen instantly -- no animation plays --
     * so the taskbar entry briefly toggles but doesn't slide. Visually
     * much less noticeable than the default 250ms slide animation.
     */
    ICONIFY_NOTRANSITION("iconify_notransition"),
    ;

    companion object {
        fun parse(raw: String?): LaunchGuardStrategy? =
            raw?.lowercase()?.trim()?.let { value ->
                values().firstOrNull { it.token == value }
            }
    }
}

private const val LOG_TAG = "TorveLaunchGuard"

// File log target. jpackage Windows builds eat stdout, so println alone
// is invisible to the user. Mirror every TorveLaunchGuard line to this
// file so post-launch diagnosis is possible.
private val launchGuardLogFile: File by lazy {
    val base = System.getenv("LOCALAPPDATA")
        ?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".torve")
    val dir = File(base, "Torve")
    runCatching { dir.mkdirs() }
    File(dir, "launch-guard.log")
}

// RedrawWindow flags. Defined inline because jna-platform's WinUser
// doesn't expose them as constants on User32.
private const val RDW_INVALIDATE = 0x0001
private const val RDW_ERASE = 0x0004
private const val RDW_ALLCHILDREN = 0x0080
private const val RDW_UPDATENOW = 0x0100
private const val RDW_FRAME = 0x0400

// ShowWindow nCmdShow values.
private const val SW_HIDE = 0
private const val SW_SHOWNORMAL = 1
private const val SW_SHOW = 5
private const val SW_MINIMIZE = 6
private const val SW_RESTORE = 9

// SetWindowPos flags.
private const val SWP_NOSIZE = 0x0001
private const val SWP_NOMOVE = 0x0002
private const val SWP_NOZORDER = 0x0004
private const val SWP_NOACTIVATE = 0x0010
private const val SWP_FRAMECHANGED = 0x0020

// GetWindowLong/SetWindowLong indices.
private const val GWL_EXSTYLE = -20

// Extended window styles.
private const val WS_EX_TOOLWINDOW = 0x00000080
private const val WS_EX_APPWINDOW = 0x00040000

internal fun isWindows(): Boolean =
    System.getProperty("os.name", "").lowercase().contains("win")

internal fun launchGuardLog(event: String, vararg pairs: Pair<String, Any?>) {
    val joined = pairs.joinToString(" ") { (k, v) -> "$k=$v" }
    val ts = runCatching { Instant.now().toString() }.getOrDefault("?")
    val line = if (joined.isEmpty()) "$ts $LOG_TAG event=$event"
    else "$ts $LOG_TAG event=$event $joined"
    println(line)
    runCatching {
        launchGuardLogFile.appendText(line + System.lineSeparator())
    }
}

/**
 * Resolves the active strategy from TORVE_LAUNCH_GUARD env var, falling
 * back to NATIVE on Windows (jna-platform is already on the classpath via
 * vlcj). Non-Windows platforms always resolve to OFF.
 */
internal fun resolveLaunchGuardStrategy(): LaunchGuardStrategy {
    if (!isWindows()) return LaunchGuardStrategy.OFF
    val override = LaunchGuardStrategy.parse(System.getenv("TORVE_LAUNCH_GUARD"))
    // Default = ICONIFY. As of 2026-05-05 it is the only strategy
    // confirmed to recover from the DWM-composition-attachment race
    // on this user's hardware. Lighter strategies (showhide,
    // swpframe, native, focus) all failed cold-launch testing.
    // ICONIFY blinks the taskbar but is reliable.
    return override ?: LaunchGuardStrategy.ICONIFY
}

/**
 * Runs the resolved guard strategy against the main JFrame. Must be
 * called on the AWT EDT (Compose Desktop's Dispatchers.Main is EDT-bound,
 * so a LaunchedEffect block satisfies this).
 */
internal suspend fun runLaunchGuard(
    mainFrame: Frame,
    strategy: LaunchGuardStrategy,
) {
    launchGuardLog("guard_start", "strategy" to strategy.token)

    if (strategy == LaunchGuardStrategy.OFF) {
        launchGuardLog("guard_skipped", "reason" to "disabled")
        return
    }
    if (!isWindows()) {
        launchGuardLog("guard_skipped", "reason" to "non_windows")
        return
    }
    if (!mainFrame.isShowing) {
        launchGuardLog("guard_skipped", "reason" to "main_not_showing")
        return
    }
    // Removed earlier foreground gate (2026-05-05): GetForegroundWindow
    // does not reliably match our HWND 200ms after setVisible(true) on
    // cold launch -- the OS hasn't fully promoted us yet. The gate was
    // silently bailing for ALL strategies including iconify, which was
    // the regression that broke the previously-working iconify path.
    // The original iconify dance never had this gate; we don't need it.

    val result = runCatching {
        when (strategy) {
            LaunchGuardStrategy.ICONIFY -> runIconifyGuard(mainFrame)
            LaunchGuardStrategy.FOCUS -> runFocusGuard(mainFrame)
            LaunchGuardStrategy.NATIVE -> runNativeGuard(mainFrame)
            LaunchGuardStrategy.SHOWHIDE -> runShowHideGuard(mainFrame)
            LaunchGuardStrategy.SWPFRAME -> runSwpFrameGuard(mainFrame)
            LaunchGuardStrategy.ICONIFY_NOTASKBAR -> runIconifyNoTaskbarGuard(mainFrame)
            LaunchGuardStrategy.ICONIFY_NOTRANSITION -> runIconifyNoTransitionGuard(mainFrame)
            LaunchGuardStrategy.OFF -> Unit
        }
    }
    launchGuardLog(
        "guard_complete",
        "strategy" to strategy.token,
        "result" to if (result.isSuccess) "success" else "failure",
        "error" to (result.exceptionOrNull()?.message ?: "none"),
    )
}

/**
 * Iconify dance via AWT's setExtendedState. This is the same code that
 * worked in Build 3 (commit pre-launch-guard). The Win32 ShowWindow
 * variant tested in Build 7 made the launch noticeably worse: Windows
 * 11's ShowWindow(SW_MINIMIZE/SW_RESTORE) runs the minimize animation
 * synchronously and blocks the calling thread until the animation
 * completes (~700ms each direction), producing a long visible
 * minimize/restore that the user reported as "horrible".
 *
 * AWT's setExtendedState is asynchronous: it posts the state change
 * to the AWT event queue and returns immediately. The animation still
 * happens but doesn't block our coroutine, so the dance feels brief.
 */
private suspend fun runIconifyGuard(mainFrame: Frame) {
    val prev = mainFrame.extendedState
    mainFrame.extendedState = Frame.ICONIFIED
    delay(80)
    mainFrame.extendedState = prev
    mainFrame.toFront()
    mainFrame.requestFocus()
}

/**
 * Iconify dance with taskbar entry suppressed for the duration. The
 * taskbar lives at the OS shell layer, but its decision of whether to
 * show an entry for a window is driven by WS_EX_TOOLWINDOW /
 * WS_EX_APPWINDOW. Toggle TOOLWINDOW on, run the iconify dance (no
 * taskbar entry to blink), toggle it back off. SetWindowPos with
 * SWP_FRAMECHANGED makes the style change take effect without
 * hiding the window.
 *
 * The iconify itself still rebuilds DWM's composition surface
 * attachment -- that's the whole point of using SW_MINIMIZE/SW_RESTORE
 * over SW_HIDE/SW_SHOW. We just hide the visible side-effect.
 */
private suspend fun runIconifyNoTaskbarGuard(mainFrame: Frame) {
    val mainHwnd = WinDef.HWND(Native.getComponentPointer(mainFrame))
    val origStyle = User32.INSTANCE.GetWindowLong(mainHwnd, GWL_EXSTYLE)

    // Add TOOLWINDOW (suppress taskbar) and force-clear APPWINDOW.
    val hiddenStyle = (origStyle or WS_EX_TOOLWINDOW) and WS_EX_APPWINDOW.inv()
    User32.INSTANCE.SetWindowLong(mainHwnd, GWL_EXSTYLE, hiddenStyle)
    val applyFlags = SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or
        SWP_NOACTIVATE or SWP_FRAMECHANGED
    User32.INSTANCE.SetWindowPos(mainHwnd, null, 0, 0, 0, 0, applyFlags)

    // Iconify via AWT (asynchronous - doesn't block on the minimize
    // animation, unlike Win32 ShowWindow). With WS_EX_TOOLWINDOW
    // applied above, the JFrame has no taskbar entry, so the
    // minimize-restore should produce no visible taskbar animation.
    val prev = mainFrame.extendedState
    mainFrame.extendedState = Frame.ICONIFIED
    delay(80)
    mainFrame.extendedState = prev

    // Restore the original extended style. SWP_FRAMECHANGED makes
    // the change take effect without hiding the window.
    User32.INSTANCE.SetWindowLong(mainHwnd, GWL_EXSTYLE, origStyle)
    User32.INSTANCE.SetWindowPos(mainHwnd, null, 0, 0, 0, 0, applyFlags)

    User32.INSTANCE.SetForegroundWindow(mainHwnd)
}

private suspend fun runFocusGuard(mainFrame: Frame) {
    val activator = createActivatorWindow()
    try {
        activator.isVisible = true
        // One frame for the JWindow to realize its HWND.
        delay(16)
        activator.toFront()
        activator.requestFocus()
        delay(60)
        mainFrame.toFront()
        mainFrame.requestFocus()
        mainFrame.repaint()
    } finally {
        activator.dispose()
    }
}

/**
 * Hides the main window via Win32 ShowWindow(SW_HIDE), then re-shows it
 * via SW_SHOW. This forces DWM to release and re-create its composition
 * surface attachment for the HWND -- which is what alt-tab + iconify
 * both do, just from different angles. Unlike iconify, ShowWindow does
 * NOT trigger the taskbar minimize animation. The cost is a brief
 * window blank-out (~100ms) where the window is fully removed from
 * screen instead of minimized to taskbar.
 */
/**
 * Iconify dance with DWM-driven window transitions disabled for the
 * window before the dance, and restored after. The minimize and restore
 * happen instantly -- no animation plays. Taskbar entry still flashes
 * briefly but without the slide animation that makes it noticeable.
 *
 * If Dwmapi.dll is unavailable (very old Windows builds without DWM),
 * this falls back to a plain iconify dance.
 */
private suspend fun runIconifyNoTransitionGuard(mainFrame: Frame) {
    val mainHwnd = WinDef.HWND(Native.getComponentPointer(mainFrame))
    val disabled = IntByReference(1)
    val enabled = IntByReference(0)

    val applied = runCatching {
        Dwmapi.INSTANCE.DwmSetWindowAttribute(
            mainHwnd, DWMWA_TRANSITIONS_FORCEDISABLED, disabled, 4,
        )
    }.isSuccess
    if (!applied) {
        launchGuardLog("dwm_disable_transitions_unsupported")
    }

    val prev = mainFrame.extendedState
    mainFrame.extendedState = Frame.ICONIFIED
    delay(80)
    mainFrame.extendedState = prev
    mainFrame.toFront()
    mainFrame.requestFocus()

    if (applied) {
        runCatching {
            Dwmapi.INSTANCE.DwmSetWindowAttribute(
                mainHwnd, DWMWA_TRANSITIONS_FORCEDISABLED, enabled, 4,
            )
        }
    }
}

private suspend fun runShowHideGuard(mainFrame: Frame) {
    val mainHwnd = WinDef.HWND(Native.getComponentPointer(mainFrame))
    User32.INSTANCE.ShowWindow(mainHwnd, SW_HIDE)
    delay(50)
    User32.INSTANCE.ShowWindow(mainHwnd, SW_SHOW)
    delay(16)
    // Re-establish foreground (SW_HIDE may have given foreground to
    // another process) and force a synchronous repaint.
    User32.INSTANCE.SetForegroundWindow(mainHwnd)
    val flags = RDW_INVALIDATE or RDW_UPDATENOW or RDW_ALLCHILDREN or
        RDW_FRAME or RDW_ERASE
    User32.INSTANCE.RedrawWindow(mainHwnd, null, null, WinDef.DWORD(flags.toLong()))
}

/**
 * Forces DWM to recompute the window frame via SetWindowPos with
 * SWP_FRAMECHANGED. Documented to fire WM_NCCALCSIZE which on Vista+
 * triggers DWM to re-evaluate composition attachment. Lightest possible
 * intervention: no visible artifact, no foreground change, no z-order
 * change. May or may not be aggressive enough to actually rebuild
 * composition -- worth probing.
 */
private suspend fun runSwpFrameGuard(mainFrame: Frame) {
    val mainHwnd = WinDef.HWND(Native.getComponentPointer(mainFrame))
    val flags = SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or
        SWP_NOZORDER or SWP_NOACTIVATE
    User32.INSTANCE.SetWindowPos(mainHwnd, null, 0, 0, 0, 0, flags)
    delay(16)
    val rdwFlags = RDW_INVALIDATE or RDW_UPDATENOW or RDW_ALLCHILDREN or
        RDW_FRAME or RDW_ERASE
    User32.INSTANCE.RedrawWindow(
        mainHwnd, null, null, WinDef.DWORD(rdwFlags.toLong()),
    )
}

private suspend fun runNativeGuard(mainFrame: Frame) {
    val activator = createActivatorWindow()
    try {
        activator.isVisible = true
        delay(16) // Wait for the JWindow's HWND to exist.

        val activatorHwnd = WinDef.HWND(Native.getComponentPointer(activator))
        val mainHwnd = WinDef.HWND(Native.getComponentPointer(mainFrame))

        User32.INSTANCE.SetForegroundWindow(activatorHwnd)
        delay(60)
        User32.INSTANCE.SetForegroundWindow(mainHwnd)

        // Force the OS to fire WM_ERASEBKGND + WM_PAINT across the
        // entire client + non-client area, including all child HWNDs.
        // This is the same effective sequence as restoring from
        // iconified state, minus the taskbar animation.
        val flags = RDW_INVALIDATE or RDW_UPDATENOW or RDW_ALLCHILDREN or
            RDW_FRAME or RDW_ERASE
        User32.INSTANCE.RedrawWindow(mainHwnd, null, null, WinDef.DWORD(flags.toLong()))
    } finally {
        activator.dispose()
    }
}

private fun createActivatorWindow(): JWindow {
    val window = JWindow()
    window.focusableWindowState = true
    window.setSize(4, 4)
    window.setLocation(0, 0)
    // Translucency requires PERPIXEL_TRANSLUCENT, which Windows 7+
    // normally supports but RDP / VMs / old drivers can refuse.
    // Fall back to a fully opaque 4x4 dot if the GraphicsConfiguration
    // doesn't support translucency -- still good enough for an
    // 80ms-or-less activation flicker.
    runCatching { window.opacity = 0.01f }
        .onFailure {
            launchGuardLog(
                "activator_translucency_unsupported",
                "error" to (it.message ?: it::class.simpleName ?: "unknown"),
            )
        }
    return window
}
