package com.torve.desktop.mpv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.sun.jna.Native
import java.awt.Canvas
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import kotlinx.coroutines.launch

/**
 * Compose surface that hosts an AWT [Canvas] and tells [engine] to paint
 * into it via libmpv's legacy `wid` embedding. The Canvas is added to a
 * [SwingPanel] so it lives inside the Compose layout (full-screen,
 * position, padding all just work).
 *
 * Why `wid` and not the render API?
 *  - `wid` is one option set on libmpv before init; mpv's own video
 *    output driver does the painting. No GL interop, no Skia bridge, no
 *    multi-week implementation work.
 *  - Trade-off: no per-frame Compose overlay on top of the video frame
 *    (Compose paints around the Canvas, not on it). HDR pass-through and
 *    chroma negotiation still work because mpv owns the surface.
 *
 * Platform support:
 *  - **Windows** - [Native.getComponentPointer] returns the HWND. Works.
 *  - **Linux/X11** - returns the X Window XID. Works for libmpv built
 *    against `--enable-x11`.
 *  - **macOS** - returns an NSView pointer; needs libmpv built with
 *    `--enable-cocoa` (the libmpv published with default brew has
 *    `--enable-cocoa-cb` instead, which is render-API-only). Standalone
 *    fallback kicks in if attach silently no-ops, so users on macOS see
 *    mpv's own window until Stage 3 lands.
 *
 * The Canvas's HWND is only valid once the peer is created. We listen
 * for [HierarchyEvent.DISPLAYABILITY_CHANGED] and call attach the moment
 * the canvas is realised.
 */
@Composable
fun MpvComposePlayerSurface(
    engine: MpvPlaybackEngine,
    onCanvasMotion: () -> Unit = {},
    cursorHidden: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val canvas = remember {
        Canvas().apply {
            background = java.awt.Color.BLACK
            // mpv expects to be the only painter on this surface.
            ignoreRepaint = true
        }
    }

    // Hide / restore the AWT cursor over the canvas when chrome
    // auto-hides. Uses a 1x1 transparent BufferedImage as a "blank"
    // cursor - Java's standard idiom for hiding the cursor on a
    // component while keeping it active for input.
    DisposableEffect(canvas, cursorHidden) {
        if (cursorHidden) {
            val blankImage = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val blankCursor = java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
                blankImage,
                java.awt.Point(0, 0),
                "torve-mpv-blank-cursor",
            )
            canvas.cursor = blankCursor
        } else {
            canvas.cursor = java.awt.Cursor.getDefaultCursor()
        }
        onDispose { canvas.cursor = java.awt.Cursor.getDefaultCursor() }
    }

    // AWT MouseMotionListener forwards canvas hover events. Lightweight
    // Compose pointer events don't fire when the cursor is over the
    // heavyweight Canvas, so we intercept at the AWT level.
    //
    // Filter spurious events: mpv's OSC + cursor-autohide cycle, and the
    // heavyweight peer repainting on each frame, both fire MOUSE_MOVED
    // at the *same* screen position even though the user never moved
    // their mouse. If we forward those, the chrome auto-hide timer
    // resets every cycle and the controls flash every ~3s. Only forward
    // when the cursor's screen position actually changed.
    DisposableEffect(canvas, onCanvasMotion) {
        var lastX = Int.MIN_VALUE
        var lastY = Int.MIN_VALUE
        val motion = object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val x = e.xOnScreen
                val y = e.yOnScreen
                if (x == lastX && y == lastY) return
                lastX = x
                lastY = y
                onCanvasMotion()
            }
            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                lastX = e.xOnScreen
                lastY = e.yOnScreen
                onCanvasMotion()
            }
        }
        canvas.addMouseMotionListener(motion)
        onDispose { canvas.removeMouseMotionListener(motion) }
    }

    var attachedHandle: Long? = null
    DisposableEffect(canvas) {
        val listener = HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L &&
                canvas.isDisplayable
            ) {
                runCatching {
                    val pointer = Native.getComponentPointer(canvas)
                    if (pointer != null) {
                        val handle = com.sun.jna.Pointer.nativeValue(pointer)
                        attachedHandle = handle
                        scope.launch { engine.attachToWindow(handle) }
                    }
                }.onFailure {
                    println("TORVE MPV ┃ failed to extract HWND for canvas: ${it.message}")
                }
            }
        }
        canvas.addHierarchyListener(listener)
        onDispose {
            canvas.removeHierarchyListener(listener)
            // Reset engine state so a re-mount with a new canvas waits
            // for the new attach instead of racing the previous open().
            attachedHandle?.let { engine.detachIf(it) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        SwingPanel(
            background = Color.Black,
            modifier = Modifier.fillMaxSize(),
            factory = { canvas },
        )
    }
}
