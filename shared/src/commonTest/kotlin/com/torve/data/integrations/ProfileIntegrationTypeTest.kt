package com.torve.data.integrations

import com.torve.domain.integrations.IntegrationSecretKey
import kotlin.test.*

class ProfileIntegrationTypeTest {
    @Test fun everyConnectionFitsTheDeployedBackendLimitForANewProfile() {
        val profile = "m1234567abcd12"
        for (key in IntegrationSecretKey.entries) {
            val type = profileIntegrationType(key.name, profile)
            assertTrue(type.length <= 50, "${key.name} exceeds the deployed API's limit")
            assertEquals(key.name, baseIntegrationType(type))
            assertEquals(profile, integrationProfileId(type))
        }
        assertEquals("TRAKT_TOKENS", profileIntegrationType("TRAKT_TOKENS", "default"))
        assertNotEquals(profileIntegrationType("TRAKT_TOKENS", "first"), profileIntegrationType("TRAKT_TOKENS", "second"))
    }

    @Test fun legacyConnectionsStillRestoreToTheirOriginalProfile() {
        assertEquals("kids", integrationProfileId("TRAKT_TOKENS::profile:kids"))
        assertEquals("TRAKT_TOKENS", baseIntegrationType("TRAKT_TOKENS::profile:kids"))
        assertEquals("default", integrationProfileId("TRAKT_TOKENS"))
    }
}
