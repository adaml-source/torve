package com.torve.android.tv.focus

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class TvModalFocusRestoreControllerTest {
    @Test
    fun pruneDropsOnlyInactiveTargetsOutsideTheCurrentContentModel() {
        val controller = TvModalFocusRestoreController()
        val stale = target("stale")
        val active = target("active")
        val staleRequester = controller.registerTarget(stale, FocusRequester())
        val activeRequester = controller.registerTarget(active, FocusRequester())

        controller.unregisterTarget(stale)
        controller.pruneInactiveTargets(emptySet())

        assertNotSame(staleRequester, controller.requesterFor(stale))
        assertSame(activeRequester, controller.requesterFor(active))
    }

    @Test
    fun validInactiveTargetKeepsItsRequesterForExactFocusRestoration() {
        val controller = TvModalFocusRestoreController()
        val target = target("movie-42")
        val requester = controller.registerTarget(target, FocusRequester())
        controller.unregisterTarget(target)

        controller.pruneInactiveTargets(setOf(target))

        assertSame(requester, controller.requesterFor(target))
    }

    private fun target(itemKey: String) = TvFocusTargetId(
        screenId = "movies",
        rowKey = "popular",
        itemKey = itemKey,
        rowIndex = 0,
        itemIndex = 0,
        targetType = "card",
    )
}
