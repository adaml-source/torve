package com.torve.android.tv.focus

import androidx.compose.ui.focus.FocusRequester
import com.torve.android.tv.screens.TvSettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSettingsFocusStateMachineTest {

    @Test
    fun requestRestore_keepsExactItemAsFirstCandidate() {
        val controller = TvSettingsFocusStateMachine()
        val max = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_MAX_QUALITY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 1,
            focusTargetType = "selector",
        )
        val min = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_MIN_QUALITY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 2,
            focusTargetType = "selector",
        )
        controller.registerItem(max, FocusRequester(), isDefaultEntry = true)
        controller.registerItem(min, FocusRequester())

        val origin = controller.captureOrigin(min.itemId, reason = "selector_open")!!
        val candidates = controller.resolveCandidates(origin)

        assertEquals(min.itemId, candidates.first().itemId)
        assertEquals(1, candidates.size)
    }

    @Test
    fun missingItem_fallsBackToNearestItemInSameCategory() {
        val controller = TvSettingsFocusStateMachine()
        val max = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_MAX_QUALITY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 1,
            focusTargetType = "selector",
        )
        val audioMode = TvSettingsFocusTarget(
            itemId = "settings/playback/audio_mode",
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 3,
            focusTargetType = "selector",
        )
        controller.registerItem(max, FocusRequester(), isDefaultEntry = true)
        controller.registerItem(audioMode, FocusRequester())

        val origin = TvSettingsFocusOrigin(
            itemId = TvSettingsItemIds.PLAYBACK_MIN_QUALITY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 2,
            focusTargetType = "selector",
            listSnapshot = null,
            requestedAtMillis = 0L,
            restoreToken = 1L,
            reason = "back",
        )

        val candidates = controller.resolveCandidates(origin)

        assertEquals(TvSettingsItemIds.PLAYBACK_MAX_QUALITY, candidates.first().itemId)
        assertTrue(candidates.all { it.category == TvSettingsCategory.PLAYBACK })
    }

    @Test
    fun entryRequester_prefersLastFocusedItemInCurrentCategory() {
        val controller = TvSettingsFocusStateMachine()
        val pairDeviceRequester = FocusRequester()
        val maxQualityRequester = FocusRequester()
        val pairDevice = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIR_DEVICE,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 0,
            focusTargetType = "action",
        )
        val maxQuality = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_MAX_QUALITY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 1,
            focusTargetType = "selector",
        )
        controller.registerItem(pairDevice, pairDeviceRequester, isDefaultEntry = true)
        controller.registerItem(maxQuality, maxQualityRequester, isDefaultEntry = true)
        controller.markFocused(maxQuality.itemId, maxQualityRequester)

        assertEquals(maxQualityRequester, controller.entryRequesterForCurrentState())
    }

    @Test
    fun unregisteringFocusedItem_clearsFocusedItemId() {
        val controller = TvSettingsFocusStateMachine()
        val language = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.APPEARANCE_LANGUAGE,
            category = TvSettingsCategory.APPEARANCE,
            listIndex = 2,
            focusTargetType = "selector",
        )
        controller.registerItem(language, FocusRequester())
        controller.markFocused(language.itemId)

        controller.unregisterItem(language.itemId)

        assertNull(controller.focusedItemId)
    }

    @Test
    fun syncRegisteredItem_updatesCandidateOrderingWhenRowIndexChanges() {
        val controller = TvSettingsFocusStateMachine()
        val previousInstalled = TvSettingsFocusTarget(
            itemId = "settings/advanced/addon_previous",
            category = TvSettingsCategory.ADVANCED,
            listIndex = 90,
            focusTargetType = "action",
        )
        val shiftedInstalled = TvSettingsFocusTarget(
            itemId = "settings/advanced/addon_shifted",
            category = TvSettingsCategory.ADVANCED,
            listIndex = 92,
            focusTargetType = "action",
        )
        val shiftedRequester = FocusRequester()
        controller.registerItem(previousInstalled, FocusRequester())
        controller.registerItem(shiftedInstalled, shiftedRequester)

        controller.syncRegisteredItem(
            target = shiftedInstalled.copy(listIndex = 91),
            requester = shiftedRequester,
        )

        val origin = TvSettingsFocusOrigin(
            itemId = "settings/advanced/addon_removed",
            category = TvSettingsCategory.ADVANCED,
            listIndex = 91,
            focusTargetType = "action",
            listSnapshot = null,
            requestedAtMillis = 0L,
            restoreToken = 1L,
            reason = "addon_uninstall",
        )

        val candidates = controller.resolveCandidates(origin)

        assertEquals("settings/advanced/addon_shifted", candidates.first().itemId)
    }

    @Test
    fun accountRows_restoreToExactLastFocusedRow() {
        val controller = TvSettingsFocusStateMachine()
        val pairDeviceRequester = FocusRequester()
        val forgotPasswordRequester = FocusRequester()
        val premiumMonthlyRequester = FocusRequester()
        val premiumLifetimeRequester = FocusRequester()
        val refreshRequester = FocusRequester()
        val syncStatusRequester = FocusRequester()
        val pairDevice = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIR_DEVICE,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 0,
            focusTargetType = "action",
        )
        val forgotPassword = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_FORGOT_PASSWORD,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 25,
            focusTargetType = "action",
        )
        val premiumMonthly = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_MONTHLY,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 31,
            focusTargetType = "action",
        )
        val premiumLifetime = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_LIFETIME,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 32,
            focusTargetType = "action",
        )
        val refreshAccess = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_REFRESH,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 33,
            focusTargetType = "action",
        )
        val syncStatus = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SYNC_STATUS,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 3,
            focusTargetType = "action",
        )
        controller.registerItem(pairDevice, pairDeviceRequester, isDefaultEntry = true)
        controller.registerItem(forgotPassword, forgotPasswordRequester)
        controller.registerItem(premiumMonthly, premiumMonthlyRequester)
        controller.registerItem(premiumLifetime, premiumLifetimeRequester)
        controller.registerItem(refreshAccess, refreshRequester)
        controller.registerItem(syncStatus, syncStatusRequester)

        listOf(
            forgotPassword.itemId to forgotPasswordRequester,
            premiumMonthly.itemId to premiumMonthlyRequester,
            premiumLifetime.itemId to premiumLifetimeRequester,
            refreshAccess.itemId to refreshRequester,
            syncStatus.itemId to syncStatusRequester,
        ).forEach { (itemId, requester) ->
            controller.markFocused(itemId, requester)
            controller.saveReturnTarget(reason = "rail_exit")

            assertNull(controller.pendingRestore)
            assertEquals(itemId, controller.entryItemIdForCurrentState())
            assertSame(requester, controller.entryRequesterForCurrentState())
        }
    }

    @Test
    fun accountFallback_usesDefaultOnlyWhenNoRememberedRowExists() {
        val controller = TvSettingsFocusStateMachine()
        val pairDeviceRequester = FocusRequester()
        val pairedDevicesRequester = FocusRequester()
        val pairDevice = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIR_DEVICE,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 0,
            focusTargetType = "action",
        )
        val pairedDevices = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIRED_DEVICES,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 1,
            focusTargetType = "navigation",
        )
        controller.registerItem(pairDevice, pairDeviceRequester, isDefaultEntry = true)
        controller.registerItem(pairedDevices, pairedDevicesRequester)

        assertSame(pairDeviceRequester, controller.entryRequesterForCurrentState())

        controller.markFocused(pairedDevices.itemId, pairedDevicesRequester)
        controller.saveReturnTarget(reason = "rail_exit")

        assertSame(pairedDevicesRequester, controller.entryRequesterForCurrentState())
    }

    @Test
    fun saveReturnTarget_isDormantUntilExplicitRestoreIsRequested() {
        val controller = TvSettingsFocusStateMachine()
        val pairDeviceRequester = FocusRequester()
        val premiumMonthlyRequester = FocusRequester()
        val pairDevice = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIR_DEVICE,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 0,
            focusTargetType = "action",
        )
        val premiumMonthly = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_MONTHLY,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 31,
            focusTargetType = "action",
        )
        controller.registerItem(pairDevice, pairDeviceRequester, isDefaultEntry = true)
        controller.registerItem(premiumMonthly, premiumMonthlyRequester)
        controller.markFocused(premiumMonthly.itemId, premiumMonthlyRequester)

        controller.saveReturnTarget(reason = "rail_exit")

        assertNull(controller.pendingRestore)
        assertEquals(premiumMonthly.itemId, controller.savedReturnTarget?.itemId)

        controller.requestRestore(reason = "explicit_reentry")

        assertEquals(premiumMonthly.itemId, controller.pendingRestore?.itemId)
        assertSame(premiumMonthlyRequester, controller.entryRequesterForCurrentState())
    }

    @Test
    fun secondaryActivatedDevicesEntry_canBeRestoredExactly() {
        val controller = TvSettingsFocusStateMachine()
        val defaultRequester = FocusRequester()
        val activatedDevicesRequester = FocusRequester()
        val subscriptionManageDevicesRequester = FocusRequester()
        val pairDevice = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIR_DEVICE,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 0,
            focusTargetType = "action",
        )
        val activatedDevices = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_ACTIVATED_DEVICES,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 2,
            focusTargetType = "navigation",
        )
        val subscriptionManageDevices = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_MANAGE_DEVICES,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 30,
            focusTargetType = "navigation",
        )
        controller.registerItem(pairDevice, defaultRequester, isDefaultEntry = true)
        controller.registerItem(activatedDevices, activatedDevicesRequester)
        controller.registerItem(subscriptionManageDevices, subscriptionManageDevicesRequester)

        controller.captureOrigin(subscriptionManageDevices.itemId, reason = "route_open")
        controller.requestRestore(itemId = subscriptionManageDevices.itemId, reason = "route_return")

        assertEquals(subscriptionManageDevices.itemId, controller.entryItemIdForCurrentState())
        assertSame(subscriptionManageDevicesRequester, controller.entryRequesterForCurrentState())
    }

    @Test
    fun unregisteringFocusedItem_queuesMutationRepairForNearestVisibleSibling() {
        val controller = TvSettingsFocusStateMachine()
        val pairDevice = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIR_DEVICE,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 0,
            focusTargetType = "action",
        )
        val forgotPassword = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_FORGOT_PASSWORD,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 25,
            focusTargetType = "action",
        )
        val refreshAccess = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_REFRESH,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 33,
            focusTargetType = "action",
        )
        controller.registerItem(pairDevice, FocusRequester(), isDefaultEntry = true)
        controller.registerItem(forgotPassword, FocusRequester())
        controller.registerItem(refreshAccess, FocusRequester())
        controller.markFocused(forgotPassword.itemId)

        controller.unregisterItem(forgotPassword.itemId)

        assertNull(controller.focusedItemId)
        assertEquals(forgotPassword.itemId, controller.pendingFocusRepair?.itemId)
        assertEquals(
            listOf(refreshAccess.itemId, pairDevice.itemId),
            controller.resolveMutationRepairCandidates(controller.pendingFocusRepair!!).map { it.itemId },
        )
    }

    @Test
    fun mutationRepair_prefersExactStableSlotWhenReplacementReusesSameItemId() {
        val controller = TvSettingsFocusStateMachine()
        val stablePrimaryAction = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_PRIMARY_ACTION,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 12,
            focusTargetType = "action",
        )
        controller.registerItem(stablePrimaryAction, FocusRequester())

        val origin = TvSettingsFocusOrigin(
            itemId = TvSettingsItemIds.ACCOUNT_AUTH_PRIMARY_ACTION,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 23,
            focusTargetType = "action",
            listSnapshot = null,
            requestedAtMillis = 0L,
            restoreToken = 1L,
            reason = "mutation_invalidation",
        )

        assertEquals(
            listOf(TvSettingsItemIds.ACCOUNT_AUTH_PRIMARY_ACTION),
            controller.resolveMutationRepairCandidates(origin).map { it.itemId },
        )
    }

    @Test
    fun mutationRepair_fallsBackToFirstVisibleItemWhenNoSiblingSurvives() {
        val controller = TvSettingsFocusStateMachine()
        val pairDeviceRequester = FocusRequester()
        val pairDevice = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIR_DEVICE,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 0,
            focusTargetType = "action",
        )
        controller.registerItem(pairDevice, pairDeviceRequester, isDefaultEntry = true)

        val origin = TvSettingsFocusOrigin(
            itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_RETRY,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 34,
            focusTargetType = "action",
            listSnapshot = null,
            requestedAtMillis = 0L,
            restoreToken = 1L,
            reason = "mutation_invalidation",
        )

        assertEquals(
            listOf(TvSettingsItemIds.ACCOUNT_PAIR_DEVICE),
            controller.resolveMutationRepairCandidates(origin).map { it.itemId },
        )
        assertSame(pairDeviceRequester, controller.requesterForItemId(TvSettingsItemIds.ACCOUNT_PAIR_DEVICE))
    }

    @Test
    fun unregisteringFocusedItem_fromInactiveCategory_doesNotQueueMutationRepair() {
        val controller = TvSettingsFocusStateMachine()
        val accountDefaultRequester = FocusRequester()
        val playbackDefaultRequester = FocusRequester()
        val accountDefault = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.ACCOUNT_PAIR_DEVICE,
            category = TvSettingsCategory.ACCOUNT,
            listIndex = 0,
            focusTargetType = "action",
        )
        val playbackDefault = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_MAX_QUALITY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 1,
            focusTargetType = "selector",
        )
        controller.registerItem(accountDefault, accountDefaultRequester, isDefaultEntry = true)
        controller.registerItem(playbackDefault, playbackDefaultRequester, isDefaultEntry = true)
        controller.markFocused(accountDefault.itemId, accountDefaultRequester)
        controller.selectedCategory = TvSettingsCategory.PLAYBACK

        controller.unregisterItem(accountDefault.itemId)

        assertNull(controller.pendingFocusRepair)
        assertEquals(TvSettingsCategory.PLAYBACK, controller.selectedCategory)
        assertSame(playbackDefaultRequester, controller.entryRequesterForCurrentState())
    }

    @Test
    fun playbackRows_restoreToExactLastFocusedRow() {
        val controller = TvSettingsFocusStateMachine()
        val maxQualityRequester = FocusRequester()
        val autoplayNextRequester = FocusRequester()
        val maxQuality = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_MAX_QUALITY,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 1,
            focusTargetType = "selector",
        )
        val autoplayNext = TvSettingsFocusTarget(
            itemId = TvSettingsItemIds.PLAYBACK_AUTOPLAY_NEXT,
            category = TvSettingsCategory.PLAYBACK,
            listIndex = 11,
            focusTargetType = "toggle",
        )
        controller.registerItem(maxQuality, maxQualityRequester, isDefaultEntry = true)
        controller.registerItem(autoplayNext, autoplayNextRequester)
        controller.markFocused(autoplayNext.itemId, autoplayNextRequester)

        controller.saveReturnTarget(reason = "rail_exit")

        assertNull(controller.pendingRestore)
        assertEquals(autoplayNext.itemId, controller.entryItemIdForCurrentState())
        assertSame(autoplayNextRequester, controller.entryRequesterForCurrentState())
    }
}
