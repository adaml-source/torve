package com.torve.desktop.playback

import com.torve.desktop.mpv.LibMpv
import com.torve.desktop.mpv.MpvPlaybackEngine
import com.torve.desktop.vlc.VlcPlaybackEngine

enum class DesktopPlayerMode(val label: String, val settingsKey: String) {
    VLC("VLC Player", "vlc"),
    MPV("MPV Labs", "mpv"),
    ;

    companion object {
        fun fromSettingsKey(key: String?): DesktopPlayerMode =
            entries.firstOrNull { it.settingsKey == key } ?: VLC
    }
}

/**
 * Build the playback engine for [preferredMode]. If MPV is requested but
 * libmpv is not present, fall back to VLC silently. Main.kt normalizes the
 * persisted preference back to VLC so users are not interrupted by an
 * experimental-engine warning.
 */
fun createPlaybackEngineWithFallback(preferredMode: DesktopPlayerMode): Pair<DesktopPlaybackEngine, DesktopPlayerMode> {
    println("TORVE PLAYER | preferred mode = $preferredMode")
    return when (preferredMode) {
        DesktopPlayerMode.VLC -> {
            val engine = VlcPlaybackEngine()
            println("TORVE PLAYER | using VLC direct rendering engine")
            engine to DesktopPlayerMode.VLC
        }

        DesktopPlayerMode.MPV -> {
            val mpvLib = LibMpv.loadOrNull()
            if (mpvLib != null) {
                println("TORVE PLAYER | using MPV engine (libmpv loaded)")
                MpvPlaybackEngine() to DesktopPlayerMode.MPV
            } else {
                println("TORVE PLAYER | MPV requested but libmpv not found; falling back to VLC")
                VlcPlaybackEngine() to DesktopPlayerMode.VLC
            }
        }
    }
}
