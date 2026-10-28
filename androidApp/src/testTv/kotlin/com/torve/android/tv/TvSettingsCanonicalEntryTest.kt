package com.torve.android.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvSettingsCanonicalEntryTest {
    @Test fun accountAndPhoneSetupShareTheAccountForm() {
        listOf("settings/account/auth_account", "settings/connections/pairing", "settings/account/import_setup").forEach {
            assertEquals("account", canonicalTvSettingsEntryAction(it))
        }
    }
    @Test fun helpHasOneCanonicalDestination() = assertEquals("about", canonicalTvSettingsEntryAction("settings/about/support"))
    @Test fun trackingEntriesOpenOnlyTheirService() {
        assertEquals("Trakt", canonicalTvSettingsEntryAction("settings/connections/trakt"))
        assertEquals("SIMKL", canonicalTvSettingsEntryAction("settings/connections/simkl"))
    }
    @Test fun streamingServicesNeverMapToExtensions() = assertEquals("Connections", canonicalTvSettingsEntryAction("settings/advanced/real_debrid"))
    @Test fun mediaServersUseTheirFocusedForms() {
        assertEquals("Jellyfin", canonicalTvSettingsEntryAction("settings/advanced/jellyfin_url"))
        assertEquals("Plex", canonicalTvSettingsEntryAction("settings/advanced/plex_url"))
    }
    @Test fun unknownDiagnosticEntryStaysInSettings() = assertEquals("tests", canonicalTvSettingsEntryAction("provider_attention"))
}
