package com.torve.android.ui.player

import androidx.compose.ui.input.key.Key
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class PlaybackFocusCoordinatorTest {
    @Test
    fun disappearingSegmentActionRestoresExistingPlaybackFocus() {
        val coordinator = PlaybackFocusCoordinator()
        var topRequested = false
        coordinator.registerRegion(FocusRegionHandle(PlaybackFocusRegion.TopActions) { topRequested = true; true })
        coordinator.registerRegion(FocusRegionHandle(PlaybackFocusRegion.SegmentAction) { true })
        coordinator.reportFocusedRegion(PlaybackFocusRegion.SegmentAction, "intro")

        coordinator.unregisterRegion(PlaybackFocusRegion.SegmentAction)

        assertTrue(topRequested)
        assertEquals(PlaybackFocusRegion.PlayerSurface, coordinator.currentRegion)
    }

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

        coordinator.registerRegion(FocusRegionHandle(PlaybackFocusRegion.SegmentAction) { true })
        coordinator.reportFocusedRegion(PlaybackFocusRegion.PlayerSurface)
        assertEquals(
            PlaybackFocusRegion.SegmentAction,
            coordinator.resolveDirectionalMove(FocusDirection.Down),
        )
    }

    @Test
    fun focusedSegmentActionOwnsRemoteActivationKeys() {
        for (key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
            assertTrue(
                segmentActionOwnsActivationKey(
                    isTv = true,
                    currentRegion = PlaybackFocusRegion.SegmentAction,
                    key = key,
                ),
            )
        }
        assertFalse(
            segmentActionOwnsActivationKey(
                isTv = true,
                currentRegion = PlaybackFocusRegion.PlayerSurface,
                key = Key.DirectionCenter,
            ),
        )
        assertFalse(
            segmentActionOwnsActivationKey(
                isTv = false,
                currentRegion = PlaybackFocusRegion.SegmentAction,
                key = Key.DirectionCenter,
            ),
        )
    }

    @Test
    fun segmentActionAutoFocusOnlyTakesUnobstructedTvPlaybackFocus() {
        assertTrue(
            shouldAutoFocusSegmentAction(
                isTv = true,
                currentRegion = PlaybackFocusRegion.PlayerSurface,
                blockingOverlayVisible = false,
            ),
        )
        assertFalse(
            shouldAutoFocusSegmentAction(
                isTv = true,
                currentRegion = PlaybackFocusRegion.TransportControls,
                blockingOverlayVisible = false,
            ),
        )
        assertFalse(
            shouldAutoFocusSegmentAction(
                isTv = true,
                currentRegion = PlaybackFocusRegion.PlayerSurface,
                blockingOverlayVisible = true,
            ),
        )
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

    @Test
    fun subtitleDelayMode_restoresFocusOnlyInsideSubtitleDelayOverlay() {
        val coordinator = PlaybackFocusCoordinator()
        var subtitleDelayFocusRequested = false
        coordinator.registerRegion(
            FocusRegionHandle(PlaybackFocusRegion.SubtitleDelayOverlay) {
                subtitleDelayFocusRequested = true
                true
            },
        )
        coordinator.registerRegion(
            FocusRegionHandle(PlaybackFocusRegion.TopActions) {
                throw AssertionError("Playback controls must not receive subtitle-delay focus")
            },
        )
        coordinator.uiMode = PlaybackUiMode.SubtitleDelay

        assertTrue(coordinator.restoreFocusForCurrentMode())
        assertTrue(subtitleDelayFocusRequested)
        assertNull(coordinator.resolveDirectionalMove(FocusDirection.Down))
    }
}
