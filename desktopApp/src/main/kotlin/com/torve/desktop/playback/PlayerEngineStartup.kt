package com.torve.desktop.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide capture of which engine was *requested* at startup vs which
 * one we actually instantiated. Used by the shell to surface a one-shot
 * banner when [requested] != [actual] (e.g. user picked MPV but libmpv
 * isn't installed → silently downgraded to VLC).
 *
 * Lives outside Koin/DI so Main.kt can record before any UI composes and
 * any composable can read [state] without threading it through.
 */
object PlayerEngineStartup {

    data class Snapshot(
        val requested: DesktopPlayerMode,
        val actual: DesktopPlayerMode,
        val acknowledged: Boolean = false,
    ) {
        val didFallBack: Boolean get() = requested != actual
    }

    private val _state = MutableStateFlow<Snapshot?>(null)
    val state: StateFlow<Snapshot?> = _state.asStateFlow()

    fun record(requested: DesktopPlayerMode, actual: DesktopPlayerMode) {
        _state.value = Snapshot(requested = requested, actual = actual)
    }

    /** Mark the fall-back banner as dismissed so the shell stops showing it. */
    fun acknowledge() {
        val current = _state.value ?: return
        _state.value = current.copy(acknowledged = true)
    }
}
