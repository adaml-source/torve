package com.torve.data.integrations

import com.torve.domain.integrations.AutomationServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServarrAdminClientTest {
    @Test
    fun `each service uses its own read-only status contract`() {
        assertEquals("/api/v3/system/status", automationProbeSpec(AutomationServiceType.SONARR).path)
        assertEquals("/api/v3/system/status", automationProbeSpec(AutomationServiceType.RADARR).path)
        assertEquals("/api/v1/system/status", automationProbeSpec(AutomationServiceType.PROWLARR).path)
        assertEquals("/api/system/status", automationProbeSpec(AutomationServiceType.BAZARR).path)
        assertEquals("/api/v2/status", automationProbeSpec(AutomationServiceType.TDARR).path)
    }

    @Test
    fun `only tdarr liveness probe is keyless`() {
        AutomationServiceType.entries.filterNot { it == AutomationServiceType.TDARR }.forEach { type ->
            assertNotNull(automationProbeSpec(type).apiKeyHeader, type.name)
        }
        assertEquals("X-API-KEY", automationProbeSpec(AutomationServiceType.BAZARR).apiKeyHeader)
        assertNull(automationProbeSpec(AutomationServiceType.TDARR).apiKeyHeader)
    }
}
