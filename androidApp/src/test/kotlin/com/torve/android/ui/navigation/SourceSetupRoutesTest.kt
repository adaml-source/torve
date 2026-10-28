package com.torve.android.ui.navigation

import com.torve.android.ui.community.SettingsMenus
import com.torve.android.ui.setup.SourceSetupRoutes
import com.torve.android.ui.transfer.providerSettingsRouteFor
import com.torve.domain.providerhealth.ProviderHealthCategory
import org.junit.Assert.*
import org.junit.Test

class SourceSetupRoutesTest {
    @Test fun sourceDirectoryExposesUsenetAndOnlyCurrentDestinations() {
        val entries = SettingsMenus.sourceSetup()
        assertEquals("usenet_setup", entries.first().id)
        val routes = entries.map { SourceSetupRoutes.destination(requireNotNull(it.action)) }
        assertEquals(listOf("usenet_setup", "integrations?service=nzbdav", "integrations?service=debrid",
            "extensions", "integrations?service=jellyfin", "integrations?service=plex"), routes)
        assertTrue(routes.none { it in SourceSetupRoutes.directoryAliases })
    }

    @Test fun savedOldLinksAreHandledByTheCurrentDirectory() {
        assertTrue(SourceSetupRoutes.directoryAliases.containsAll(
            listOf("setup", "setup_choice", "setup_guided", "panda_setup", "manage_panda")))
        assertEquals(SourceSetupRoutes.directoryAliases.size, SourceSetupRoutes.directoryAliases.distinct().size)
    }

    @Test fun repairLinksGoDirectlyToTheMatchingCurrentForm() {
        assertEquals("integrations?service=debrid", providerSettingsRouteFor(ProviderHealthCategory.DEBRID))
        listOf(ProviderHealthCategory.USENET_PROVIDER, ProviderHealthCategory.USENET_INDEXER,
            ProviderHealthCategory.DOWNLOAD_CLIENT).forEach {
            assertEquals("usenet_setup", providerSettingsRouteFor(it))
        }
    }
}
