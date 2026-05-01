package com.torve.desktop.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide capture of which engine was requested at startup vs which
 * one we actually instantiated. Kept for diagnostics; unavailable lab engines
 * are normalized back to the release-safe VLC path without surfacing a warning
 * in the consumer UI.
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

    /** Mark the diagnostic snapshot as acknowledged. */
    fun acknowledge() {
        val current = _state.value ?: return
        _state.value = current.copy(acknowledged = true)
    }
}
