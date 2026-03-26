package com.torve.android.tv.focus

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvModalFocusRestoreControllerTest {

    @Test
    fun exactTarget_isFirstRestoreCandidate() {
        val controller = TvModalFocusRestoreController()
        val origin = TvFocusTargetId(
            screenId = "settings",
            rowKey = "PLAYBACK",
            itemKey = "min_quality",
            rowIndex = 3,
            itemIndex = 1,
        )
        controller.registerTarget(origin, FocusRequester())
        controller.registerTarget(
            TvFocusTargetId("settings", "PLAYBACK", "max_quality", 2, 0),
            FocusRequester(),
        )

        val candidates = controller.resolveCandidates(controller.captureOrigin(origin))

        assertEquals(origin, candidates.first())
    }

    @Test
    fun missingExactTarget_fallsBackToNearestItemInSameRow() {
        val controller = TvModalFocusRestoreController()
        val previousTarget = TvFocusTargetId("settings", "PLAYBACK", "min_quality", 3, 1)
        controller.registerTarget(
            TvFocusTargetId("settings", "PLAYBACK", "max_quality", 2, 0),
            FocusRequester(),
        )
        controller.registerTarget(
            TvFocusTargetId("settings", "PLAYBACK", "audio_mode", 4, 2),
            FocusRequester(),
        )

        val candidates = controller.resolveCandidates(controller.captureOrigin(previousTarget))

        assertEquals("max_quality", candidates.first().itemKey)
    }

    @Test
    fun missingRow_fallsBackToNearestRowInsteadOfFirstScreenItem() {
        val controller = TvModalFocusRestoreController()
        val removedTarget = TvFocusTargetId("settings", "PLAYBACK", "min_quality", 3, 1)
        controller.registerTarget(
            TvFocusTargetId("settings", "ACCOUNT", "email", 0, 0),
            FocusRequester(),
        )
        controller.registerTarget(
            TvFocusTargetId("settings", "PLAYBACK_AUDIO", "audio_mode", 4, 0),
            FocusRequester(),
        )
        controller.registerTarget(
            TvFocusTargetId("settings", "APPEARANCE", "language", 8, 0),
            FocusRequester(),
        )

        val candidates = controller.resolveCandidates(controller.captureOrigin(removedTarget))

        assertEquals("audio_mode", candidates.first().itemKey)
        assertNotEquals("email", candidates.first().itemKey)
    }

    @Test
    fun requestRestore_replacesPreviousPendingToken() {
        val controller = TvModalFocusRestoreController()
        val target = TvFocusTargetId("settings", "PLAYBACK", "max_quality", 2, 0)

        val first = controller.captureOrigin(target, requestedAtMillis = 10L)
        val second = controller.requestRestore(first)

        assertNotEquals(first.restoreToken, second?.restoreToken)
        assertEquals(second, controller.pendingRestore)
    }

    @Test
    fun unregisterLastTarget_clearsFocusedTargetWhenOriginDisappears() {
        val controller = TvModalFocusRestoreController()
        val target = TvFocusTargetId("settings", "PLAYBACK", "max_quality", 2, 0)
        controller.registerTarget(target, FocusRequester())
        controller.markFocused(target)

        controller.unregisterTarget(target)

        assertNull(controller.focusedTarget)
    }
}
