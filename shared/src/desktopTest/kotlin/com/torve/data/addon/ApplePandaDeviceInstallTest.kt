package com.torve.data.addon

import com.torve.data.PersonalDeviceFixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class ApplePandaDeviceInstallTest {
    @Test
    fun oldAccountPandaCannotSupplySourcesAndManualInstallSurvivesRepositoryRestart() = runTest {
        val previous = ExtensionRuntimePolicy.restorePandaFromAccount
        val json = Json { ignoreUnknownKeys = true }
        try {
            ExtensionRuntimePolicy.restorePandaFromAccount = false
            PersonalDeviceFixture(HttpClient(MockEngine {
                respond("""{"id":"com.torve.panda","name":"Panda","version":"1.0.0","resources":["stream"],"types":["movie","series"]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            })).use { device ->
                device.signIn()
                val url = "https://panda.torve.app/u/fixture/manifest.json"
                device.database.torveQueries.insertAddon(device.ids.currentDataUserId(), url,
                    "com.torve.panda", "Panda", "1.0.0", "", null,
                    """{"id":"com.torve.panda","name":"Panda","version":"1.0.0","resources":["stream"]}""",
                    1, 0, 0, "android-row", 1, "app", null)
                fun repository() = AddonRepositoryImpl(device.database, StremioAddonClient(device.http, json), json, device.ids)
                val first = repository()
                assertNull(first.getAddon(url))
                assertTrue(first.getInstalledAddons().isEmpty())
                assertTrue(first.getEnabledAddons().isEmpty())
                val installed = first.installAddon(url)
                assertEquals(DEVICE_MANIFEST_INSTALL, installed.installedFrom)
                first.clearSyncMetadata()
                val restarted = repository()
                assertEquals(url, restarted.getEnabledAddons().single().manifestUrl)
                assertEquals(DEVICE_MANIFEST_INSTALL, restarted.getAddon(url)?.installedFrom)
                restarted.removeAddon(url)
                assertTrue(repository().getInstalledAddons().isEmpty())
                // Reading an old row on Android still retains the original behaviour.
                ExtensionRuntimePolicy.restorePandaFromAccount = true
                first.installAddon(url, serverId = "android-row", syncedAt = 1)
                assertEquals("android-row", repository().getAddon(url)?.serverId)
            }
        } finally { ExtensionRuntimePolicy.restorePandaFromAccount = previous }
    }
}
