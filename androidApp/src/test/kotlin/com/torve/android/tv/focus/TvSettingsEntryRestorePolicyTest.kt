package com.torve.android.tv.focus

import com.torve.android.tv.TvSettingsDestination
import com.torve.android.tv.nav.TvRoutes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSettingsEntryRestorePolicyTest {

    @Test
    fun enteringSettings_requestsRestore() {
        assertTrue(
            shouldRequestSettingsEntryRestore(
                TvSettingsEntryRestoreInputs(
                    previousSelectedTopRoute = TvRoutes.HOME,
                    selectedTopRoute = TvRoutes.SETTINGS,
                    previousSettingsDestination = TvSettingsDestination.MAIN,
                    settingsDestination = TvSettingsDestination.MAIN,
                    previousIsRailFocused = true,
                    isRailFocused = false,
                    hasSettingsEntryRequester = true,
                    hasExplicitReturnFocus = false,
                    hasPendingExactRestore = false,
                ),
            ),
        )
    }

    @Test
    fun pendingExactRestore_blocksGenericSettingsEntryRestore() {
        assertFalse(
            shouldRequestSettingsEntryRestore(
                TvSettingsEntryRestoreInputs(
                    previousSelectedTopRoute = TvRoutes.SETTINGS,
                    selectedTopRoute = TvRoutes.SETTINGS,
                    previousSettingsDestination = TvSettingsDestination.MAIN,
                    settingsDestination = TvSettingsDestination.MAIN,
                    previousIsRailFocused = false,
                    isRailFocused = false,
                    hasSettingsEntryRequester = true,
                    hasExplicitReturnFocus = false,
                    hasPendingExactRestore = true,
                ),
            ),
        )
    }

    @Test
    fun unchangedMainSettingsState_doesNotRetriggerRestore() {
        assertFalse(
            shouldRequestSettingsEntryRestore(
                TvSettingsEntryRestoreInputs(
                    previousSelectedTopRoute = TvRoutes.SETTINGS,
                    selectedTopRoute = TvRoutes.SETTINGS,
                    previousSettingsDestination = TvSettingsDestination.MAIN,
                    settingsDestination = TvSettingsDestination.MAIN,
                    previousIsRailFocused = false,
                    isRailFocused = false,
                    hasSettingsEntryRequester = true,
                    hasExplicitReturnFocus = false,
                    hasPendingExactRestore = false,
                ),
            ),
        )
    }

    @Test
    fun enteringSettingsSubmenu_requestsRestore() {
        assertTrue(
            shouldRequestSettingsEntryRestore(
                TvSettingsEntryRestoreInputs(
                    previousSelectedTopRoute = TvRoutes.SETTINGS,
                    selectedTopRoute = TvRoutes.SETTINGS,
                    previousSettingsDestination = TvSettingsDestination.MAIN,
                    settingsDestination = TvSettingsDestination.PAIRED_DEVICES,
                    previousIsRailFocused = false,
                    isRailFocused = false,
                    hasSettingsEntryRequester = true,
                    hasExplicitReturnFocus = false,
                    hasPendingExactRestore = false,
                ),
            ),
        )
    }

    @Test
    fun leavingToRailAndPassiveRailFocusChanges_doNotRequestRestore() {
        assertFalse(
            shouldRequestSettingsEntryRestore(
                TvSettingsEntryRestoreInputs(
                    previousSelectedTopRoute = TvRoutes.SETTINGS,
                    selectedTopRoute = TvRoutes.SETTINGS,
                    previousSettingsDestination = TvSettingsDestination.MAIN,
                    settingsDestination = TvSettingsDestination.MAIN,
                    previousIsRailFocused = true,
                    isRailFocused = false,
                    hasSettingsEntryRequester = true,
                    hasExplicitReturnFocus = false,
                    hasPendingExactRestore = false,
                ),
            ),
        )
    }
}
