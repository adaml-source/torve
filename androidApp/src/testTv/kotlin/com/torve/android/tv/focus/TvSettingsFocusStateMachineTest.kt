package com.torve.android.tv.focus

import androidx.compose.ui.focus.FocusRequester
import com.torve.android.tv.screens.TvSettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TvSettingsFocusStateMachineTest {
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
