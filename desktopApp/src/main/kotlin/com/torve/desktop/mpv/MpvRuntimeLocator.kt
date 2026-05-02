package com.torve.desktop.mpv

import java.io.File
import java.net.URI

/**
 * Discovers libmpv on disk so [LibMpv.loadOrNull] can prepend a known
 * directory to `jna.library.path` before [com.sun.jna.Native.load].
 *
 * Discovery order mirrors [com.torve.desktop.player.VlcRuntimeLocator]:
 * 1. Bundled Torve runtime under `runtime/<platform>/mpv/` next to the
 *    project root, dev classpath, or jpackage-built app dir.
 * 2. JVM property `torve.desktop.mpv.path` - explicit override.
 * 3. Environment variable `TORVE_MPV_PATH`.
 * 4. Standard install paths (Windows: `C:\Program Files\mpv`).
 *
 * If none of those produce a hit, the result is `found=false`. The caller
 * still attempts a plain `Native.load("mpv-2")`, which falls through to
 * the OS's native library search path (`PATH` on Windows,
 * `LD_LIBRARY_PATH` on Linux, `DYLD_LIBRARY_PATH` on macOS).
 */
object MpvRuntimeLocator {

    data class DiscoveryResult(
        val found: Boolean,
        val mpvDirectory: String? = null,
        val discoverySource: String? = null,
        val attemptedPaths: List<String> = emptyList(),
        val diagnosticMessage: String,
    )

    fun discover(): DiscoveryResult {
        val attempted = mutableListOf<String>()

        // 1. Bundled Torve runtime - same layout convention as VLC.
        for (baseDir in discoverBaseDirectories()) {
            for (relativePath in listOf(
                "runtime/${osFolder()}/mpv",
                "runtime/mpv",
                "mpv",
                "desktopApp/runtime/${osFolder()}/mpv",
            )) {
                val dir = File(baseDir, relativePath)
                val normalized = normalize(dir)
                attempted += normalized
                if (isValidMpvDirectory(dir)) {
                    return DiscoveryResult(
                        found = true,
                        mpvDirectory = normalized,
                        discoverySource = "Bundled Torve runtime",
                        attemptedPaths = attempted,
                        diagnosticMessage = "libmpv found in bundled runtime at $normalized",
                    )
                }
            }
        }

        // Compose-packaged resources dir, set by the jpackage launcher.
        System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.let { resDir ->
                for (rel in listOf("mpv", "${osFolder()}/mpv")) {
                    val dir = File(resDir, rel)
                    val normalized = normalize(dir)
                    attempted += normalized
                    if (isValidMpvDirectory(dir)) {
                        return DiscoveryResult(
                            found = true,
                            mpvDirectory = normalized,
                            discoverySource = "Bundled Torve runtime (packaged)",
                            attemptedPaths = attempted,
                            diagnosticMessage = "libmpv found in packaged resources at $normalized",
                        )
                    }
                }
            }

        // 2. JVM property override.
        System.getProperty("torve.desktop.mpv.path")?.takeIf { it.isNotBlank() }?.let { p ->
            val dir = File(p)
            val normalized = normalize(dir)
            attempted += normalized
            if (isValidMpvDirectory(dir)) {
                return DiscoveryResult(
                    found = true,
                    mpvDirectory = normalized,
                    discoverySource = "JVM property torve.desktop.mpv.path",
                    attemptedPaths = attempted,
                    diagnosticMessage = "libmpv found via JVM property at $normalized",
                )
            }
        }

        // 3. Env var override.
        System.getenv("TORVE_MPV_PATH")?.takeIf { it.isNotBlank() }?.let { p ->
            val dir = File(p)
            val normalized = normalize(dir)
            attempted += normalized
            if (isValidMpvDirectory(dir)) {
                return DiscoveryResult(
                    found = true,
                    mpvDirectory = normalized,
                    discoverySource = "Environment variable TORVE_MPV_PATH",
                    attemptedPaths = attempted,
                    diagnosticMessage = "libmpv found via TORVE_MPV_PATH at $normalized",
                )
            }
        }

        // 4. Standard install locations per OS.
        for (path in standardInstallLocations()) {
            val dir = File(path)
            val normalized = normalize(dir)
            attempted += normalized
            if (isValidMpvDirectory(dir)) {
                return DiscoveryResult(
                    found = true,
                    mpvDirectory = normalized,
                    discoverySource = "Standard mpv install",
                    attemptedPaths = attempted,
                    diagnosticMessage = "libmpv found at standard install location $normalized",
                )
            }
        }

        return DiscoveryResult(
            found = false,
            attemptedPaths = attempted.distinct(),
            diagnosticMessage = buildString {
                append("libmpv not found in any bundled or standard location. ")
                append("Install mpv (https://mpv.io) or bundle libmpv under runtime/${osFolder()}/mpv/. ")
                append("Searched ${attempted.distinct().size} directories.")
            },
        )
    }

    /**
     * Prepend [result]'s directory to `jna.library.path` so [com.sun.jna.Native.load]
     * picks up the bundled binary before consulting the OS search path.
     */
    fun apply(result: DiscoveryResult) {
        val dir = result.mpvDirectory ?: return
        val existing = System.getProperty("jna.library.path", "")
        val merged = if (existing.isBlank()) dir else "$dir${File.pathSeparator}$existing"
        System.setProperty("jna.library.path", merged)
        println("TORVE MPV ┃ Applied mpv discovery: dir=$dir source=${result.discoverySource}")
    }

    private fun isValidMpvDirectory(dir: File): Boolean {
        if (!dir.isDirectory) return false
        // Each platform spells the libmpv shared object differently.
        val candidates = listOf(
            "mpv-2.dll", "libmpv-2.dll",          // Windows
            "libmpv.so.2", "libmpv.so",            // Linux
            "libmpv.2.dylib", "libmpv.dylib",      // macOS
        )
        return candidates.any { File(dir, it).exists() }
    }

    private fun standardInstallLocations(): List<String> {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        return when {
            os.contains("win") -> listOf(
                "C:\\Program Files\\mpv",
                "C:\\Program Files (x86)\\mpv",
                "${System.getenv("LOCALAPPDATA").orEmpty()}\\Programs\\mpv".trim(),
            ).filter { it.isNotBlank() && !it.startsWith("\\Programs") }
            os.contains("mac") || os.contains("darwin") -> listOf(
                "/opt/homebrew/lib",
                "/usr/local/lib",
                "/Applications/mpv.app/Contents/MacOS/lib",
            )
            else -> listOf(
                "/usr/lib",
                "/usr/lib/x86_64-linux-gnu",
                "/usr/local/lib",
            )
        }
    }

    private fun osFolder(): String {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        return when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            else -> "linux"
        }
    }

    private fun discoverBaseDirectories(): List<File> {
        val userDir = File(System.getProperty("user.dir"))
        val codeSourceDir = runCatching {
            val location = MpvRuntimeLocator::class.java.protectionDomain.codeSource?.location
                ?: return@runCatching null
            val file = File(URI(location.toString()))
            if (file.isDirectory) file else file.parentFile
        }.getOrNull()

        return buildList {
            add(userDir)
            codeSourceDir?.let {
                add(it)
                it.parentFile?.let(::add)
            }
        }.distinctBy { normalize(it) }
    }

    private fun normalize(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
}
