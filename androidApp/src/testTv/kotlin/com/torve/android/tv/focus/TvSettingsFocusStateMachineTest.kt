package com.torve.android.tv.focus

import androidx.compose.ui.focus.FocusRequester
import com.torve.android.tv.screens.TvSettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TvSettingsFocusStateMachineTest {
    @Test
    fun leftAndRightSwitchAdjacentCategoriesFromOrdinaryContent() {
        val order = listOf(
            TvSettingsCategory.ACCOUNT,
            TvSettingsCategory.PLAYBACK,
            TvSettingsCategory.APPEARANCE,
            TvSettingsCategory.ABOUT,
        )

        assertEquals(
            TvSettingsCategory.APPEARANCE,
            adjacentTvSettingsCategory(order, TvSettingsCategory.PLAYBACK, 1, "action"),
        )
        assertEquals(
            TvSettingsCategory.ACCOUNT,
            adjacentTvSettingsCategory(order, TvSettingsCategory.PLAYBACK, -1, "toggle"),
        )
        assertNull(adjacentTvSettingsCategory(order, TvSettingsCategory.ACCOUNT, -1, "action"))
        assertNull(adjacentTvSettingsCategory(order, TvSettingsCategory.ABOUT, 1, "action"))
    }

    @Test
    fun horizontalInputsKeepTheirOwnLeftAndRightBehavior() {
        val order = listOf(TvSettingsCategory.PLAYBACK, TvSettingsCategory.APPEARANCE)

        assertNull(adjacentTvSettingsCategory(order, TvSettingsCategory.PLAYBACK, 1, "selector"))
        assertNull(adjacentTvSettingsCategory(order, TvSettingsCategory.PLAYBACK, 1, "input"))
    }

    @Test
    fun categorySwitchClearsStaleFocusBeforeNewDefaultIsRequested() {
        val controller = TvSettingsFocusStateMachine(TvSettingsCategory.ABOUT)
        register(controller, "about-check", TvSettingsCategory.ABOUT, 3)
        controller.markFocused("about-check")

        controller.beginCategorySwitch(TvSettingsCategory.ACCOUNT)

        assertEquals(TvSettingsCategory.ACCOUNT, controller.selectedCategory)
        assertNull(controller.focusedItemId)
        assertNull(controller.pendingFocusRepair)
        assertNull(controller.pendingRestore)
    }

    @Test
    fun removingFocusedRowRepairsToNextThenPreviousVisibleRowInSameCategory() {
        val controller = TvSettingsFocusStateMachine(TvSettingsCategory.CONNECTIONS)
        register(controller, "connection-1", TvSettingsCategory.CONNECTIONS, 1)
        register(controller, "connection-3", TvSettingsCategory.CONNECTIONS, 3)
        register(controller, "connection-4", TvSettingsCategory.CONNECTIONS, 4)
        register(controller, "about-1", TvSettingsCategory.ABOUT, 1)
        controller.markFocused("connection-3")

        controller.unregisterItem("connection-3")

        val repair = controller.pendingFocusRepair
        assertNotNull(repair)
        requireNotNull(repair)
        assertEquals("connection-3", repair.itemId)
        assertEquals(TvSettingsCategory.CONNECTIONS, repair.category)
        assertEquals(
            listOf("connection-4", "connection-1"),
            controller.resolveMutationRepairCandidates(repair).map { it.itemId },
        )
    }

    @Test
    fun backgroundMutationInAnotherCategoryDoesNotDisturbCurrentFocus() {
        val controller = TvSettingsFocusStateMachine(TvSettingsCategory.CONNECTIONS)
        register(controller, "connection-current", TvSettingsCategory.CONNECTIONS, 2)
        register(controller, "account-refreshing", TvSettingsCategory.ACCOUNT, 2)
        controller.markFocused("connection-current")

        controller.unregisterItem("account-refreshing")

        assertEquals("connection-current", controller.focusedItemId)
        assertEquals(TvSettingsCategory.CONNECTIONS, controller.selectedCategory)
        assertNull(controller.pendingFocusRepair)
    }

    @Test
    fun exactOriginWinsAfterBackgroundRowsAreRegistered() {
        val controller = TvSettingsFocusStateMachine(TvSettingsCategory.CONNECTIONS)
        register(controller, "connection-origin", TvSettingsCategory.CONNECTIONS, 8)
        val origin = requireNotNull(controller.captureOrigin("connection-origin", requestedAtMillis = 10L))
        register(controller, "connection-new", TvSettingsCategory.CONNECTIONS, 1)
        register(controller, "library-new", TvSettingsCategory.LIBRARY, 8)

        assertEquals(
            listOf("connection-origin"),
            controller.resolveCandidates(origin).map { it.itemId },
        )
    }

    @Test
    fun updaterRoundTripRestoresExactAboutRowBeforeFallbacks() {
        val controller = TvSettingsFocusStateMachine(TvSettingsCategory.ABOUT)
        register(controller, "about-version", TvSettingsCategory.ABOUT, 0)
        register(controller, "about-check-for-updates", TvSettingsCategory.ABOUT, 2)
        register(controller, "about-support", TvSettingsCategory.ABOUT, 3)

        val launchOrigin = requireNotNull(
            controller.captureOrigin(
                itemId = "about-check-for-updates",
                reason = "app_update_launch",
                requestedAtMillis = 20L,
            ),
        )
        val returnOrigin = requireNotNull(
            controller.requestRestore(
                itemId = "about-check-for-updates",
                reason = "app_update_return",
            ),
        )

        assertEquals(launchOrigin.itemId, returnOrigin.itemId)
        assertEquals(TvSettingsCategory.ABOUT, controller.selectedCategory)
        assertEquals(
            listOf("about-check-for-updates"),
            controller.resolveCandidates(returnOrigin).map { it.itemId },
        )
    }

    @Test
    fun missingUpdaterOriginFallsBackWithinAboutWithoutLosingFocusSurface() {
        val controller = TvSettingsFocusStateMachine(TvSettingsCategory.ABOUT)
        register(controller, "about-version", TvSettingsCategory.ABOUT, 0)
        register(controller, "about-check-for-updates", TvSettingsCategory.ABOUT, 2)
        register(controller, "about-support", TvSettingsCategory.ABOUT, 3)
        val origin = requireNotNull(
            controller.captureOrigin("about-check-for-updates", reason = "app_update_launch"),
        )

        controller.unregisterItem("about-check-for-updates")

        assertEquals(
            listOf("about-support", "about-version"),
            controller.resolveCandidates(origin).map { it.itemId },
        )
    }

    @Test
    fun everyOrdinaryCategoryCanSwitchToItsVisibleNeighbor() {
        val order = TvSettingsCategory.entries
        order.forEachIndexed { index, category ->
            if (index > 0) {
                assertEquals(order[index - 1], adjacentTvSettingsCategory(order, category, -1, "action"))
            }
            if (index < order.lastIndex) {
                assertEquals(order[index + 1], adjacentTvSettingsCategory(order, category, 1, "navigation"))
            }
        }
    }

    private fun register(
        controller: TvSettingsFocusStateMachine,
        itemId: String,
        category: TvSettingsCategory,
        index: Int,
    ) {
        controller.registerItem(
            target = TvSettingsFocusTarget(
                itemId = itemId,
                category = category,
                listIndex = index,
                focusTargetType = "button",
            ),
            requester = FocusRequester(),
        )
    }
}
