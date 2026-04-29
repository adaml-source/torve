package com.torve.desktop.ui.components

import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * Opens a native directory-selection dialog (JFileChooser in DIRECTORIES_ONLY mode).
 * Returns the absolute path string, or null if the user cancelled.
 *
 * Compose Desktop click handlers dispatch on the AWT EDT, so the chooser can be
 * shown directly; if called off-EDT we invoke-and-wait for safety.
 */
fun pickDirectory(title: String, initialPath: String? = null): String? {
    val run: () -> String? = {
        val start = initialPath?.takeIf { it.isNotBlank() }
        val chooser = if (start != null) JFileChooser(start) else JFileChooser()
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = title
        chooser.isMultiSelectionEnabled = false
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }
    return if (SwingUtilities.isEventDispatchThread()) {
        run()
    } else {
        var out: String? = null
        SwingUtilities.invokeAndWait { out = run() }
        out
    }
}
