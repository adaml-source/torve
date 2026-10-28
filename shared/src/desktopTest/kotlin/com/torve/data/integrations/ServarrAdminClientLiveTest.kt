package com.torve.data.integrations

import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationServiceType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Optional real-service compatibility test.
 *
 * It is a no-op in normal CI. Supplying TORVE_LIVE_* URL/key environment
 * variables runs Torve's production client against disposable real servers.
 */
class ServarrAdminClientLiveTest {
    @Test
    fun `real automation services accept valid credentials and reject invalid credentials`() = runTest {
        val cases = listOfNotNull(
            keyedCase(AutomationServiceType.SONARR),
            keyedCase(AutomationServiceType.RADARR),
            keyedCase(AutomationServiceType.PROWLARR),
            keyedCase(AutomationServiceType.BAZARR),
            keylessCase(AutomationServiceType.TDARR),
        )
        if (cases.size != AutomationServiceType.entries.size) return@runTest

        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        try {
            val client = ServarrAdminClient(httpClient)
            cases.forEach { case ->
                val connected = client.testConnection(case.instance, case.apiKey)
                assertIs<AutomationConnectionResult.Connected>(connected, case.instance.serviceType.name)
                assertTrue(connected.version?.isNotBlank() == true, "Missing ${case.instance.serviceType} version")

                if (case.instance.serviceType != AutomationServiceType.TDARR) {
                    assertIs<AutomationConnectionResult.Unauthorized>(
                        client.testConnection(case.instance, "definitely-wrong"),
                        "${case.instance.serviceType} accepted an invalid key",
                    )
                }
            }
        } finally {
            httpClient.close()
        }
    }

    private fun keyedCase(type: AutomationServiceType): LiveCase? {
        val prefix = "TORVE_LIVE_${type.name}"
        val url = System.getenv("${prefix}_URL")?.takeIf { it.isNotBlank() } ?: return null
        val key = System.getenv("${prefix}_KEY")?.takeIf { it.isNotBlank() } ?: return null
        return LiveCase(instance(type, url), key)
    }

    private fun keylessCase(type: AutomationServiceType): LiveCase? {
        val url = System.getenv("TORVE_LIVE_${type.name}_URL")?.takeIf { it.isNotBlank() } ?: return null
        return LiveCase(instance(type, url), "")
    }

    private fun instance(type: AutomationServiceType, url: String) = AutomationInstance(
        id = "live-${type.name.lowercase()}",
        serviceType = type,
        name = "Live ${type.name}",
        serverUrl = url,
    )

    private data class LiveCase(val instance: AutomationInstance, val apiKey: String)
}
