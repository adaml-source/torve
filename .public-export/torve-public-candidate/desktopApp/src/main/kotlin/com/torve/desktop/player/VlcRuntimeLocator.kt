package com.torve.desktop.player

import java.io.File
import java.net.URI

/**
 * Discovers VLC native libraries for LibVLC/vlcj initialization.
 *
 * Discovery order:
 * 1. Bundled Torve runtime under runtime/<os>/vlc
 * 2. JVM property: torve.desktop.vlc.path
 * 3. Environment variable: TORVE_VLC_PATH
 * 4. Standard installed VLC paths for the current OS
 */
object VlcRuntimeLocator {

    data class DiscoveryResult(
        val found: Boolean,
        val vlcDirectory: String? = null,
        val discoverySource: String? = null,
        val attemptedPaths: List<String> = emptyList(),
        val diagnosticMessage: String,
    )

    fun discover(): DiscoveryResult {
        val attempted = mutableListOf<String>()

        // 1. Bundled Torve runtime
        for (baseDir in discoverBaseDirectories()) {
            for (relativePath in listOf(
                "runtime/${osFolder()}/vlc",
                "runtime/vlc",
                "vlc",
                "desktopApp/runtime/${osFolder()}/vlc",
            )) {
                val dir = File(baseDir, relativePath)
                val normalized = normalize(dir)
                attempted += normalized
                if (isValidVlcDirectory(dir)) {
                    return DiscoveryResult(
                        found = true,
                        vlcDirectory = normalized,
                        discoverySource = "Bundled Torve runtime",
                        attemptedPaths = attempted,
                        diagnosticMessage = "VLC found in bundled runtime at $normalized",
                    )
                }
            }
        }

        // Also check Compose app resources dir
        val composeResDir = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        if (composeResDir != null) {
            for (rel in listOf("vlc", "${osFolder()}/vlc")) {
                val dir = File(composeResDir, rel)
                val normalized = normalize(dir)
                attempted += normalized
                if (isValidVlcDirectory(dir)) {
                    return DiscoveryResult(
                        found = true,
                        vlcDirectory = normalized,
                        discoverySource = "Bundled Torve runtime (packaged)",
                        attemptedPaths = attempted,
                        diagnosticMessage = "VLC found in packaged resources at $normalized",
                    )
                }
            }
        }

        // 2. JVM property
        val jvmPropPath = System.getProperty("torve.desktop.vlc.path")?.takeIf { it.isNotBlank() }
        if (jvmPropPath != null) {
            val dir = File(jvmPropPath)
            val normalized = normalize(dir)
            attempted += normalized
            if (isValidVlcDirectory(dir)) {
                return DiscoveryResult(
                    found = true,
                    vlcDirectory = normalized,
                    discoverySource = "JVM property torve.desktop.vlc.path",
                    attemptedPaths = attempted,
                    diagnosticMessage = "VLC found via JVM property at $normalized",
                )
            }
        }

        // 3. Environment variable
        val envPath = System.getenv("TORVE_VLC_PATH")?.takeIf { it.isNotBlank() }
        if (envPath != null) {
            val dir = File(envPath)
            val normalized = normalize(dir)
            attempted += normalized
            if (isValidVlcDirectory(dir)) {
                return DiscoveryResult(
                    found = true,
                    vlcDirectory = normalized,
                    discoverySource = "Environment variable TORVE_VLC_PATH",
                    attemptedPaths = attempted,
                    diagnosticMessage = "VLC found via TORVE_VLC_PATH at $normalized",
                )
            }
        }

        // 4. Standard install locations for the current OS
        for (path in standardInstallLocations()) {
            val dir = File(path)
            val normalized = normalize(dir)
            attempted += normalized
            if (isValidVlcDirectory(dir)) {
                return DiscoveryResult(
                    found = true,
                    vlcDirectory = normalized,
                    discoverySource = "Standard VLC install",
                    attemptedPaths = attempted,
                    diagnosticMessage = "VLC found at standard install location $normalized",
                )
            }
        }

        // 5. VLC_PLUGIN_PATH env (sometimes set by VLC installer)
        val pluginPath = System.getenv("VLC_PLUGIN_PATH")?.takeIf { it.isNotBlank() }
        if (pluginPath != null) {
            val dir = File(pluginPath).parentFile
            if (dir != null) {
                val normalized = normalize(dir)
                attempted += normalized
                if (isValidVlcDirectory(dir)) {
                    return DiscoveryResult(
                        found = true,
                        vlcDirectory = normalized,
                        discoverySource = "VLC_PLUGIN_PATH environment variable",
                        attemptedPaths = attempted,
                        diagnosticMessage = "VLC found via VLC_PLUGIN_PATH at $normalized",
                    )
                }
            }
        }

        return DiscoveryResult(
            found = false,
            attemptedPaths = attempted.distinct(),
            diagnosticMessage = buildString {
                // End-user copy first - what they should DO. The path list
                // and `Searched N locations.` detail still ride in
                // `attemptedPaths` so a support reader can see them
                // without exposing dev-style paths in the UI banner.
                append("Playback runtime is missing from this Torve build. ")
                append("Reinstall Torve from the official release page, or contact support ")
                append("if reinstalling doesn't fix it. ")
                append("(Searched ${attempted.distinct().size} locations.)")
            },
        )
    }

    /**
     * Apply the discovered VLC path so vlcj's NativeDiscovery finds it.
     * Must be called before creating any MediaPlayerFactory.
     */
    fun applyDiscovery(result: DiscoveryResult) {
        val vlcDir = result.vlcDirectory ?: return
        // Set the NativeLibrary search path for JNA
        System.setProperty("jna.library.path", vlcDir)
        // Set VLC_PLUGIN_PATH so VLC finds its plugins
        val pluginDir = File(vlcDir, "plugins")
        if (pluginDir.isDirectory) {
            // VLC_PLUGIN_PATH can't be set via System.setProperty for native code,
            // but we ensure the jna.library.path includes the VLC directory
            System.setProperty("VLC_PLUGIN_PATH", pluginDir.absolutePath)
        }
        println("TORVE VLC ┃ Applied VLC discovery: dir=$vlcDir source=${result.discoverySource}")
    }

    private fun isValidVlcDirectory(dir: File): Boolean {
        if (!dir.isDirectory) return false
        // Check for libvlc.dll (Windows), libvlc.so* (Linux), or libvlc.dylib (macOS).
        val files = dir.listFiles().orEmpty()
        return files.any { file ->
            val name = file.name
            name == "libvlc.dll" ||
                name == "libvlccore.dll" ||
                name == "libvlc.dylib" ||
                name.startsWith("libvlc.so")
        }
    }

    private fun standardInstallLocations(): List<String> {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            "win" in os -> listOf(
                "C:\\Program Files\\VideoLAN\\VLC",
                "C:\\Program Files (x86)\\VideoLAN\\VLC",
            )
            "mac" in os || "darwin" in os -> listOf(
                "/Applications/VLC.app/Contents/MacOS/lib",
                "/opt/homebrew/lib",
                "/usr/local/lib",
            )
            else -> listOf(
                "/usr/lib",
                "/usr/lib/x86_64-linux-gnu",
                "/usr/local/lib",
                "/snap/vlc/current/usr/lib",
            )
        }
    }

    private fun osFolder(): String {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            "win" in os -> "windows"
            "mac" in os || "darwin" in os -> "macos"
            else -> "linux"
        }
    }

    private fun discoverBaseDirectories(): List<File> {
        val userDir = File(System.getProperty("user.dir"))
        val codeSourceDir = runCatching {
            val location = VlcRuntimeLocator::class.java.protectionDomain.codeSource?.location
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
