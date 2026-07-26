package com.torve.presentation.providerhealth

import com.torve.data.integrations.AutomationAdminClient
import com.torve.data.integrations.AutomationConnectionResult
import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationInstanceRepository
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AutomationStackProviderHealthCheckerTest {
    @Test
    fun `empty stack is unconfigured without probing`() = runTest {
        val client = FakeClient(emptyMap())
        val entry = AutomationStackProviderHealthChecker(FakeRepository(), client).check()

        assertEquals(ProviderHealthStatus.UNCONFIGURED, entry.status)
        assertEquals(ProviderHealthCategory.REQUEST_MANAGER, entry.category)
        assertEquals(0, client.calls)
    }

    @Test
    fun `fully connected stack is green and tdarr does not require a key`() = runTest {
        val sonarr = instance("sonarr", AutomationServiceType.SONARR)
        val tdarr = instance("tdarr", AutomationServiceType.TDARR)
        val entry = AutomationStackProviderHealthChecker(
            FakeRepository(listOf(sonarr, tdarr), mapOf("sonarr" to "private-key")),
            FakeClient(
                mapOf(
                    "sonarr" to AutomationConnectionResult.Connected("4.0"),
                    "tdarr" to AutomationConnectionResult.Connected("2.0"),
                ),
            ),
        ).check()

        assertEquals(ProviderHealthStatus.GREEN, entry.status)
        assertEquals("2 automation services connected", entry.message)
    }

    @Test
    fun `partial health is yellow and output never exposes connection details`() = runTest {
        val secret = "secret-that-must-not-appear"
        val instance = AutomationInstance(
            id = "sonarr",
            serviceType = AutomationServiceType.SONARR,
            name = "Private NAS",
            serverUrl = "https://private-host.example.test",
        )
        val missing = instance("radarr", AutomationServiceType.RADARR)
        val entry = AutomationStackProviderHealthChecker(
            FakeRepository(listOf(instance, missing), mapOf("sonarr" to secret)),
            FakeClient(mapOf("sonarr" to AutomationConnectionResult.Connected("4.0"))),
        ).check()

        assertEquals(ProviderHealthStatus.YELLOW, entry.status)
        val output = entry.message.orEmpty()
        assertFalse(output.contains(secret))
        assertFalse(output.contains("Private NAS"))
        assertFalse(output.contains("private-host"))
    }

    @Test
    fun `no reachable configured service is red`() = runTest {
        val sonarr = instance("sonarr", AutomationServiceType.SONARR)
        val entry = AutomationStackProviderHealthChecker(
            FakeRepository(listOf(sonarr), mapOf("sonarr" to "key")),
            FakeClient(mapOf("sonarr" to AutomationConnectionResult.Unreachable)),
        ).check()

        assertEquals(ProviderHealthStatus.RED, entry.status)
        assertEquals("Check connections", entry.nextAction)
    }

    private fun instance(id: String, type: AutomationServiceType) = AutomationInstance(
        id = id,
        serviceType = type,
        name = id,
        serverUrl = "https://$id.example.test",
    )

    private class FakeRepository(
        private val instances: List<AutomationInstance> = emptyList(),
        private val keys: Map<String, String> = emptyMap(),
    ) : AutomationInstanceRepository {
        override suspend fun list(): List<AutomationInstance> = instances
        override suspend fun save(instance: AutomationInstance, apiKey: String?) = Unit
        override suspend fun remove(instanceId: String) = Unit
        override suspend fun apiKey(instance: AutomationInstance): String? = keys[instance.id]
    }

    private class FakeClient(
        private val results: Map<String, AutomationConnectionResult>,
    ) : AutomationAdminClient {
        var calls: Int = 0
        override suspend fun testConnection(
            instance: AutomationInstance,
            apiKey: String,
        ): AutomationConnectionResult {
            calls++
            return results[instance.id] ?: AutomationConnectionResult.Unreachable
        }
    }
}
