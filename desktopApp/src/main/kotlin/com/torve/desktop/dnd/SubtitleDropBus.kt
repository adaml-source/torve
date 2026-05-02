package com.torve.desktop.dnd

import java.awt.Window
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide pub/sub for subtitle files dropped onto the application
 * window. Subscribers (typically the active player surface) receive the
 * absolute path of any dropped file with a recognised subtitle extension.
 *
 * Runs as a singleton because there's only ever one Torve window and one
 * playback engine; if you ever spawn a second window, give each its own
 * bus.
 */
object SubtitleDropBus {

    interface Handle { fun unsubscribe() }

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun subscribe(listener: (String) -> Unit): Handle {
        listeners.add(listener)
        return object : Handle {
            override fun unsubscribe() {
                listeners.remove(listener)
            }
        }
    }

    /** Called by the AWT DropTarget; fans out to every active subscriber. */
    internal fun publish(path: String) {
        listeners.forEach { runCatching { it(path) } }
    }
}

private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "sbv", "stl", "idx")

/**
 * Install an AWT DropTarget on [window]'s root pane. Dropped subtitle files
 * are forwarded to [SubtitleDropBus]; everything else is silently rejected
 * so a stray .mp4 can't replace the live media URL.
 *
 * Idempotent - subsequent calls replace the previous DropTarget.
 */
fun installSubtitleDropTarget(window: Window) {
    val root = (window as? javax.swing.RootPaneContainer)?.rootPane ?: return
    val target = DropTarget(root, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
        override fun drop(event: DropTargetDropEvent) {
            try {
                event.acceptDrop(DnDConstants.ACTION_COPY)
                val transferable = event.transferable
                val flavor = java.awt.datatransfer.DataFlavor.javaFileListFlavor
                if (!transferable.isDataFlavorSupported(flavor)) {
                    event.dropComplete(false)
                    return
                }
                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(flavor) as? List<File> ?: emptyList()
                val accepted = files.firstOrNull { f ->
                    val ext = f.name.substringAfterLast('.', "").lowercase()
                    ext in SUBTITLE_EXTENSIONS
                }
                if (accepted != null) {
                    SubtitleDropBus.publish(accepted.absolutePath)
                    event.dropComplete(true)
                } else {
                    event.dropComplete(false)
                }
            } catch (t: Throwable) {
                println("TORVE DND | drop failed: ${t.message}")
                runCatching { event.dropComplete(false) }
            }
        }
    }, true)
    // Hold a reference so the GC can't reclaim it; AWT keeps a weak ref.
    root.putClientProperty("torve.dropTarget", target)
}
