package com.torve.presentation.transfer

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.transfer.DefaultConfigKeyAllowlist
import com.torve.domain.transfer.SecretCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArrTransferCatalogTest {
    @Test
    fun `automation category includes every supported service key`() {
        assertEquals(
            setOf(
                IntegrationSecretKey.SEERR_API_KEY,
                IntegrationSecretKey.SONARR_API_KEY,
                IntegrationSecretKey.RADARR_API_KEY,
                IntegrationSecretKey.PROWLARR_API_KEY,
                IntegrationSecretKey.BAZARR_API_KEY,
                IntegrationSecretKey.TDARR_API_KEY,
            ),
            TransferSecretCatalog.keysFor(SecretCategory.ARR_STACK).toSet(),
        )
    }

    @Test
    fun `automation companion URLs are explicit allowlist entries`() {
        val allowlist = DefaultConfigKeyAllowlist()
        assertTrue(allowlist.allows(DefaultConfigKeyAllowlist.SEERR_SERVER_URL))
        assertTrue(allowlist.allows(DefaultConfigKeyAllowlist.SONARR_SERVER_URL))
        assertTrue(allowlist.allows(DefaultConfigKeyAllowlist.RADARR_SERVER_URL))
        assertTrue(allowlist.allows(DefaultConfigKeyAllowlist.PROWLARR_SERVER_URL))
        assertTrue(allowlist.allows(DefaultConfigKeyAllowlist.BAZARR_SERVER_URL))
        assertTrue(allowlist.allows(DefaultConfigKeyAllowlist.TDARR_SERVER_URL))
        assertFalse(allowlist.allows("arbitrary_preference"))
    }
}
