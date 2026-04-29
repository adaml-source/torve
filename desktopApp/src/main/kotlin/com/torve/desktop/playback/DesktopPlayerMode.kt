package com.torve.desktop.playback

import com.torve.desktop.mpv.LibMpv
import com.torve.desktop.mpv.MpvPlaybackEngine
import com.torve.desktop.vlc.VlcPlaybackEngine

enum class DesktopPlayerMode(val label: String, val settingsKey: String) {
    VLC("VLC Player", "vlc"),
    MPV("MPV (experimental, separate window)", "mpv"),
    ;

    companion object {
        fun fromSettingsKey(key: String?): DesktopPlayerMode =
            entries.firstOrNull { it.settingsKey == key } ?: VLC
    }
}

/**
 * Build the playback engine for [preferredMode]. If MPV is requested but
 * libmpv isn't present on the system, we fall back to VLC silently — the
 * UI surfaces a "MPV not found, using VLC" toast on next probeRuntime.
 */
fun createPlaybackEngineWithFallback(preferredMode: DesktopPlayerMode): Pair<DesktopPlaybackEngine, DesktopPlayerMode> {
    println("TORVE PLAYER ┃ preferred mode = $preferredMode")
    return when (preferredMode) {
        DesktopPlayerMode.VLC -> {
            val engine = VlcPlaybackEngine()
            println("TORVE PLAYER ┃ using VLC direct rendering engine")
            engine to DesktopPlayerMode.VLC
        }
        DesktopPlayerMode.MPV -> {
            // Probe up-front so we can fall back to VLC silently if
            // libmpv isn't on the system. probeRuntime later will
            // re-emit the "found" event for telemetry; here we only
            // care whether to instantiate.
            val mpvLib = LibMpv.loadOrNull()
            if (mpvLib != null) {
                println("TORVE PLAYER ┃ using MPV engine (libmpv loaded)")
                MpvPlaybackEngine() to DesktopPlayerMode.MPV
            } else {
                println("TORVE PLAYER ┃ MPV requested but libmpv not found — falling back to VLC")
                VlcPlaybackEngine() to DesktopPlayerMode.VLC
            }
        }
    }
}
