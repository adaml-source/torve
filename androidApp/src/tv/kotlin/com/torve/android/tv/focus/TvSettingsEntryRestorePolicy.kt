package com.torve.android.tv.focus

import com.torve.android.tv.TvSettingsDestination
import com.torve.android.tv.nav.TvRoutes

internal data class TvSettingsEntryRestoreInputs(
    val previousSelectedTopRoute: String,
    val selectedTopRoute: String,
    val previousSettingsDestination: TvSettingsDestination,
    val settingsDestination: TvSettingsDestination,
    val previousIsRailFocused: Boolean,
    val isRailFocused: Boolean,
    val hasSettingsEntryRequester: Boolean,
    val hasExplicitReturnFocus: Boolean,
    val hasPendingExactRestore: Boolean,
)

internal fun shouldRequestSettingsEntryRestore(
    inputs: TvSettingsEntryRestoreInputs,
): Boolean {
    if (inputs.selectedTopRoute != TvRoutes.SETTINGS) return false
    if (inputs.hasExplicitReturnFocus || inputs.hasPendingExactRestore) return false
    if (inputs.settingsDestination == TvSettingsDestination.MAIN && inputs.isRailFocused) return false
    if (inputs.settingsDestination != TvSettingsDestination.MAIN && !inputs.hasSettingsEntryRequester) return false

    val enteringSettings = inputs.previousSelectedTopRoute != TvRoutes.SETTINGS &&
        inputs.selectedTopRoute == TvRoutes.SETTINGS
    val enteringSettingsSubmenu = inputs.previousSettingsDestination != inputs.settingsDestination &&
        inputs.settingsDestination != TvSettingsDestination.MAIN &&
        inputs.selectedTopRoute == TvRoutes.SETTINGS

    return enteringSettings || enteringSettingsSubmenu
}
