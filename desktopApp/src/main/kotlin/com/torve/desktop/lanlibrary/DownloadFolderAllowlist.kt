package com.torve.desktop.lanlibrary

import java.io.File
import java.io.IOException

/**
 * Phase 3 Slice C - storage safety primitive.
 *
 * A path is "allowed" if its canonical (symlink-resolved) form sits
 * underneath the canonical form of one of the configured download
 * folders. Anything else is rejected - including paths that try to
 * escape via `..`, paths that resolve through a symlink to a sibling
 * directory, and paths to system locations.
 *
 * The allowlist is the only thing the LAN serving layer trusts; without
 * it a manifest-token leak could be parlayed into arbitrary file read.
 */
class DownloadFolderAllowlist(
    private val rootsProvider: () -> List<File>,
) {

    /** Snapshot of the current allowed canonical roots. */
    fun roots(): List<File> = rootsProvider()
        .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .filter { it.isDirectory }

    /**
     * @return true iff [path] (after symlink resolution) is the same
     * file as, or a descendant of, one of [roots]. Returns false on
     * any IO error - fail-closed.
     */
    fun isAllowed(path: File): Boolean {
        val resolved = runCatching { path.canonicalFile }.getOrNull() ?: return false
        if (!resolved.exists()) return false
        if (!resolved.isFile) return false
        val rs = roots()
        if (rs.isEmpty()) return false
        return rs.any { root -> isUnder(resolved, root) }
    }

    /**
     * Convenience: parse a string path and apply [isAllowed]. Empty or
     * blank strings are rejected.
     */
    fun isAllowed(path: String): Boolean {
        if (path.isBlank()) return false
        return isAllowed(File(path))
    }

    /**
     * Directory-level check for writers that create files after validation.
     *
     * [isAllowed] intentionally requires an existing file because it protects
     * reads. Recording writes need to approve a directory before the target
     * `.ts` exists, so this accepts a directory when it is either one of the
     * configured roots or a descendant of one.
     */
    fun coversDirectory(path: File): Boolean {
        val resolved = runCatching { path.canonicalFile }.getOrNull() ?: return false
        if (!resolved.exists() || !resolved.isDirectory) return false
        val rs = roots()
        if (rs.isEmpty()) return false
        return rs.any { root -> isUnder(resolved, root) }
    }

    /**
     * Walk parents of [resolved] until we hit [root] or fall off the
     * tree. We compare by canonical absolute path strings to avoid
     * pitfalls of [File.equals] on case-insensitive filesystems.
     */
    private fun isUnder(resolved: File, root: File): Boolean {
        val rootPath = root.absolutePath
        var current: File? = resolved
        while (current != null) {
            if (current.absolutePath.equals(rootPath, ignoreCase = pathIsCaseInsensitive())) {
                return true
            }
            current = current.parentFile
        }
        return false
    }

    /** Approximate: Windows is case-insensitive; macOS HFS+/APFS may be either. */
    private fun pathIsCaseInsensitive(): Boolean {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return osName.contains("windows") || osName.contains("mac")
    }
}
