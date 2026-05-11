package com.torve.desktop.ui.components

import java.awt.Frame
import java.awt.Window
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * Opens a native directory-selection dialog (JFileChooser in DIRECTORIES_ONLY mode).
 * Returns the absolute path string, or null if the user cancelled.
 *
 * Compose Desktop click handlers dispatch on the AWT EDT, so the chooser can be
 * shown directly; if called off-EDT we invoke-and-wait for safety.
 *
 * IMPORTANT: We pass the visible Torve frame as the dialog's parent and
 * temporarily clear its always-on-top flag while the chooser is up. Without
 * this, the chooser opens *behind* the borderless fullscreen Torve window on
 * Windows and the user has no idea why "Browse..." appeared to do nothing.
 */
fun pickDirectory(title: String, initialPath: String? = null): String? {
    val run: () -> String? = {
        val start = initialPath?.takeIf { it.isNotBlank() }
        val chooser = if (start != null) JFileChooser(start) else JFileChooser()
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = title
        chooser.isMultiSelectionEnabled = false
        // Disable the broken "New Folder" toolbar button. The Swing
        // implementation throws java.io.IOException when the current
        // view isn't a real filesystem folder (e.g. "This PC", "Quick
        // Access"), and on Windows it routinely lands the user there.
        // Users can create folders by typing the path into the settings
        // field instead -- our "Create & Save" button mkdirs() it.
        disableNewFolderAction(chooser)

        // Locate the visible top-level Torve frame so the chooser opens
        // in front of (and modal to) the main window. With null parent
        // the dialog spawns its own ownerless frame which DWM stacks
        // beneath any fullscreen / always-on-top window above it.
        val parent: Window? = Window.getWindows().firstOrNull {
            it.isVisible && it is Frame
        }
        val frame = parent as? Frame
        val wasAlwaysOnTop = frame?.isAlwaysOnTop == true
        if (wasAlwaysOnTop) frame?.isAlwaysOnTop = false
        try {
            val result = chooser.showOpenDialog(parent)
            if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
        } finally {
            if (wasAlwaysOnTop) frame?.isAlwaysOnTop = true
        }
    }
    return if (SwingUtilities.isEventDispatchThread()) {
        run()
    } else {
        var out: String? = null
        SwingUtilities.invokeAndWait { out = run() }
        out
    }
}

/**
 * Walk the chooser's component tree and disable the "New Folder" action
 * (button + key binding). Implementation detail: the Windows L&F adds
 * the action to the chooser's actionMap under the key "New Folder", and
 * the toolbar button references that same action -- disabling the
 * Action covers both surfaces.
 */
private fun disableNewFolderAction(chooser: JFileChooser) {
    runCatching {
        val action = chooser.actionMap.get("New Folder")
        action?.isEnabled = false
    }
    // The L&F doesn't realize the dialog's component tree until
    // showOpenDialog() runs. Wait for the chooser to be added to its
    // dialog, then walk the tree and disable any button that looks
    // like the "New Folder" toolbar button.
    chooser.addAncestorListener(object : javax.swing.event.AncestorListener {
        override fun ancestorAdded(event: javax.swing.event.AncestorEvent) {
            javax.swing.SwingUtilities.invokeLater {
                runCatching { walkAndDisableNewFolderButtons(chooser) }
            }
        }
        override fun ancestorRemoved(event: javax.swing.event.AncestorEvent) {}
        override fun ancestorMoved(event: javax.swing.event.AncestorEvent) {}
    })
}

private fun walkAndDisableNewFolderButtons(root: java.awt.Component) {
    if (root is javax.swing.JButton) {
        val tip = root.toolTipText ?: ""
        val name = root.accessibleContext?.accessibleName ?: ""
        val cmd = root.actionCommand ?: ""
        if (tip.contains("New Folder", ignoreCase = true) ||
            name.contains("New Folder", ignoreCase = true) ||
            cmd.contains("New Folder", ignoreCase = true)
        ) {
            root.isEnabled = false
            root.isVisible = false
        }
    }
    if (root is java.awt.Container) root.components.forEach { walkAndDisableNewFolderButtons(it) }
}
