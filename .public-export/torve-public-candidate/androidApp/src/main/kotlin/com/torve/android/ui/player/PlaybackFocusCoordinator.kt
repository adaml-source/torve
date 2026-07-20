package com.torve.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

// -- Playback UI mode: represents the current focus topology --------------------

sealed interface PlaybackUiMode {
    data object ChromeHidden : PlaybackUiMode
    data object ControlsVisible : PlaybackUiMode
    data object TrackSelection : PlaybackUiMode
    data object AudioDelay : PlaybackUiMode
    data object PictureFormat : PlaybackUiMode
    data object Equalizer : PlaybackUiMode
    data object DevicePicker : PlaybackUiMode
    data object ResumePrompt : PlaybackUiMode
    data object NextEpisode : PlaybackUiMode
}

// -- Semantic focus regions ----------------------------------------------------

enum class PlaybackFocusRegion {
    PlayerSurface,
    TopActions,
    TransportControls,
    Timeline,
    TrackSelectionOverlay,
    AudioDelayOverlay,
    PictureFormatOverlay,
    EqualizerOverlay,
    DevicePickerOverlay,
    ResumePromptOverlay,
    NextEpisodeOverlay,
}

// -- Region handle: each region exposes this to the coordinator ----------------

@Stable
class FocusRegionHandle(
    val region: PlaybackFocusRegion,
    private val requestFocus: (preferredItemKey: String?) -> Boolean,
) {
    fun requestPreferredFocus(preferredItemKey: String? = null): Boolean {
        return requestFocus(preferredItemKey)
    }
}

// -- Coordinator: screen-scoped, tracks active regions and focus state ---------

@Stable
class PlaybackFocusCoordinator {

    var uiMode: PlaybackUiMode by mutableStateOf(PlaybackUiMode.ControlsVisible)

    var currentRegion: PlaybackFocusRegion by mutableStateOf(PlaybackFocusRegion.PlayerSurface)
        private set

    private val activeHandles = mutableMapOf<PlaybackFocusRegion, FocusRegionHandle>()
    private val lastFocusedKeyPerRegion = mutableMapOf<PlaybackFocusRegion, String?>()

    fun registerRegion(handle: FocusRegionHandle) {
        activeHandles[handle.region] = handle
    }

    fun unregisterRegion(region: PlaybackFocusRegion) {
        activeHandles.remove(region)
        // If the disappeared region was current, fall back to PlayerSurface
        if (currentRegion == region) {
            currentRegion = PlaybackFocusRegion.PlayerSurface
        }
    }

    fun isRegionActive(region: PlaybackFocusRegion): Boolean {
        return activeHandles.containsKey(region)
    }

    fun reportFocusedRegion(region: PlaybackFocusRegion, itemKey: String? = null) {
        currentRegion = region
        if (itemKey != null) {
            lastFocusedKeyPerRegion[region] = itemKey
        }
    }

    fun lastFocusedKey(region: PlaybackFocusRegion): String? {
        return lastFocusedKeyPerRegion[region]
    }

    fun saveLastFocusedKey(region: PlaybackFocusRegion, key: String?) {
        lastFocusedKeyPerRegion[region] = key
    }

    /**
     * Request focus to a specific region. Returns true if the region was active
     * and focus was requested. Returns false if the region is not in the composition
     * tree — never dereferences a stale requester.
     */
    fun requestFocusToRegion(
        region: PlaybackFocusRegion,
        preferredItemKey: String? = null,
    ): Boolean {
        val handle = activeHandles[region] ?: return false
        val key = preferredItemKey ?: lastFocusedKeyPerRegion[region]
        return handle.requestPreferredFocus(key)
    }

    /**
     * Resolve a directional move from the current region based on the current uiMode.
     * Returns the target region, or null if there is no valid target.
     */
    fun resolveDirectionalMove(direction: FocusDirection): PlaybackFocusRegion? {
        return when (uiMode) {
            is PlaybackUiMode.ControlsVisible -> resolveControlsDirection(direction)
            // Modal overlays handle their own internal focus; no cross-region navigation
            else -> null
        }
    }

    private fun resolveControlsDirection(direction: FocusDirection): PlaybackFocusRegion? {
        return when (currentRegion) {
            PlaybackFocusRegion.TopActions -> when (direction) {
                FocusDirection.Down -> PlaybackFocusRegion.TransportControls
                else -> null // left/right handled region-internally
            }
            PlaybackFocusRegion.TransportControls -> when (direction) {
                FocusDirection.Up -> PlaybackFocusRegion.TopActions
                FocusDirection.Down -> PlaybackFocusRegion.Timeline
                else -> null
            }
            PlaybackFocusRegion.Timeline -> when (direction) {
                FocusDirection.Up -> PlaybackFocusRegion.TransportControls
                else -> null
            }
            PlaybackFocusRegion.PlayerSurface -> when (direction) {
                FocusDirection.Up, FocusDirection.Down -> PlaybackFocusRegion.TopActions
                else -> null
            }
            // Modal overlays don't participate in inter-region navigation
            else -> null
        }
    }

    /**
     * Try to restore focus after a uiMode change. Finds the best active region
     * for the new mode and requests focus there.
     */
    fun restoreFocusForCurrentMode(): Boolean {
        val candidates = regionsForMode(uiMode)
        for (region in candidates) {
            if (requestFocusToRegion(region)) return true
        }
        return false
    }

    private fun regionsForMode(mode: PlaybackUiMode): List<PlaybackFocusRegion> {
        return when (mode) {
            is PlaybackUiMode.ChromeHidden -> listOf(PlaybackFocusRegion.PlayerSurface)
            is PlaybackUiMode.ControlsVisible -> listOf(
                PlaybackFocusRegion.TopActions,
                PlaybackFocusRegion.TransportControls,
                PlaybackFocusRegion.Timeline,
                PlaybackFocusRegion.PlayerSurface,
            )
            is PlaybackUiMode.TrackSelection -> listOf(PlaybackFocusRegion.TrackSelectionOverlay)
            is PlaybackUiMode.AudioDelay -> listOf(PlaybackFocusRegion.AudioDelayOverlay)
            is PlaybackUiMode.PictureFormat -> listOf(PlaybackFocusRegion.PictureFormatOverlay)
            is PlaybackUiMode.Equalizer -> listOf(PlaybackFocusRegion.EqualizerOverlay)
            is PlaybackUiMode.DevicePicker -> listOf(PlaybackFocusRegion.DevicePickerOverlay)
            is PlaybackUiMode.ResumePrompt -> listOf(PlaybackFocusRegion.ResumePromptOverlay)
            is PlaybackUiMode.NextEpisode -> listOf(PlaybackFocusRegion.NextEpisodeOverlay)
        }
    }
}

enum class FocusDirection { Up, Down, Left, Right }

// -- Composable helpers -------------------------------------------------------

@Composable
fun rememberPlaybackFocusCoordinator(): PlaybackFocusCoordinator {
    return remember { PlaybackFocusCoordinator() }
}

/**
 * Register a focus region with the coordinator for the lifetime of this composable.
 * The region is unregistered when the composable leaves composition — ensuring
 * no stale handles survive a Back transition.
 */
@Composable
fun RegisterFocusRegion(
    coordinator: PlaybackFocusCoordinator,
    region: PlaybackFocusRegion,
    requestFocus: (preferredItemKey: String?) -> Boolean,
) {
    val handle = remember(region) { FocusRegionHandle(region, requestFocus) }
    DisposableEffect(region) {
        coordinator.registerRegion(handle)
        onDispose {
            coordinator.unregisterRegion(region)
        }
    }
}

/**
 * Create a region-local FocusRequester that is safe from cross-tree issues.
 * Use this instead of passing requesters between conditional composable subtrees.
 */
@Composable
fun rememberRegionFocusRequester(): FocusRequester {
    return remember { FocusRequester() }
}
