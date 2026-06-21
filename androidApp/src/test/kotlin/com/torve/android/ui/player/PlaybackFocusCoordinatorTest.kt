package com.torve.android.ui.player

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class PlaybackFocusCoordinatorTest {

    @Test
    fun controlsVisible_uses_expected_region_graph() {
        val coordinator = PlaybackFocusCoordinator()
        coordinator.uiMode = PlaybackUiMode.ControlsVisible

        coordinator.reportFocusedRegion(PlaybackFocusRegion.TopActions)
        assertEquals(
            PlaybackFocusRegion.TransportControls,
            coordinator.resolveDirectionalMove(FocusDirection.Down),
        )
        assertNull(coordinator.resolveDirectionalMove(FocusDirection.Up))

        coordinator.reportFocusedRegion(PlaybackFocusRegion.TransportControls)
        assertEquals(
            PlaybackFocusRegion.TopActions,
            coordinator.resolveDirectionalMove(FocusDirection.Up),
        )
        assertEquals(
            PlaybackFocusRegion.Timeline,
            coordinator.resolveDirectionalMove(FocusDirection.Down),
        )

        coordinator.reportFocusedRegion(PlaybackFocusRegion.Timeline)
        assertEquals(
            PlaybackFocusRegion.TransportControls,
            coordinator.resolveDirectionalMove(FocusDirection.Up),
        )
        assertNull(coordinator.resolveDirectionalMove(FocusDirection.Down))
    }

    @Test
    fun requestFocusToRegion_returns_false_for_inactive_region() {
        val coordinator = PlaybackFocusCoordinator()

        assertFalse(coordinator.requestFocusToRegion(PlaybackFocusRegion.TopActions))
    }

    @Test
    fun requestFocusToRegion_uses_registered_handle_and_saved_key() {
        val coordinator = PlaybackFocusCoordinator()
        var requestedKey: String? = null
        coordinator.registerRegion(
            FocusRegionHandle(PlaybackFocusRegion.TopActions) { preferredItemKey ->
                requestedKey = preferredItemKey
                true
            },
        )
        coordinator.saveLastFocusedKey(PlaybackFocusRegion.TopActions, "BACK")

        val restored = coordinator.requestFocusToRegion(PlaybackFocusRegion.TopActions)

        assertTrue(restored)
        assertEquals("BACK", requestedKey)
    }
}
