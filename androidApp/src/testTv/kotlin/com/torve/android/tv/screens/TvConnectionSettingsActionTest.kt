package com.torve.android.tv.screens

import com.torve.domain.providerhealth.*
import org.junit.Assert.*
import org.junit.Test

class TvConnectionSettingsActionTest {
    @Test fun everyNavigableHealthCategoryOpensAnExistingForm() {
        ProviderHealthCategory.entries.forEach { category ->
            val row = ProviderHealthEntry(category, "sample", category.name, ProviderHealthStatus.GREEN)
            listOf(false, true).forEach { jellyfin ->
                val action = tvConnectionSettingsAction(row, jellyfin)
                assertNotNull(category.name, action)
                assertNotNull(category.name, com.torve.android.ui.community.SettingsMenus.leafForAction(tvSettingsGroups, action))
            }
        }
    }
    @Test fun trackingAndChannelShortcutsSelectTheirOwnForms() {
        fun action(category: ProviderHealthCategory) = tvConnectionSettingsAction(ProviderHealthEntry(category, "sample", "Sample", ProviderHealthStatus.RED), false)
        assertEquals("Trakt", action(ProviderHealthCategory.TRAKT))
        assertEquals("SIMKL", action(ProviderHealthCategory.SIMKL))
        assertEquals("Channels", action(ProviderHealthCategory.IPTV))
        assertEquals("Channels", action(ProviderHealthCategory.EPG))
        assertEquals("Connections", action(ProviderHealthCategory.DEBRID))
    }
    @Test fun combinedLibraryProbeOpensTheConfiguredLibrary() {
        val row = ProviderHealthEntry(ProviderHealthCategory.PLEX_JELLYFIN, "library:plex_jellyfin", "Library", ProviderHealthStatus.GREEN)
        assertEquals("Plex", tvConnectionSettingsAction(row, false))
        assertEquals("Jellyfin", tvConnectionSettingsAction(row, true))
    }
    @Test fun unsupportedAutomationDoesNotOfferABrokenNavigationArrow() {
        val row = ProviderHealthEntry(ProviderHealthCategory.REQUEST_MANAGER, "automation:admin", "Automation", ProviderHealthStatus.YELLOW)
        assertNull(tvConnectionSettingsAction(row, false))
    }
    @Test fun statusCopyDescribesTheResultInsteadOfNamingItsColor() {
        assertEquals("Available", tvConnectionStatusLabel(ProviderHealthStatus.GREEN))
        assertEquals("Connection failed", tvConnectionStatusLabel(ProviderHealthStatus.RED))
        assertEquals("Needs attention", tvConnectionStatusLabel(ProviderHealthStatus.YELLOW))
    }
}
