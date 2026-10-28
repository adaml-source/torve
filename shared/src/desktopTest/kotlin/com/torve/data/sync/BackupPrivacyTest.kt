package com.torve.data.sync

import com.torve.data.PersonalDeviceFixture
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class BackupPrivacyTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val privateURL = "https://panda.torve.app/u/PRIVATE_CANARY/manifest.json"

    @Test fun defaultBackupCannotExportOrImportConnectionsOrCredentialsButLanTransferStillCan() = runTest {
        PersonalDeviceFixture(HttpClient(MockEngine { respond("{}") })).use { device ->
            device.signIn()
            val q = device.database.torveQueries
            val uid = device.ids.currentDataUserId()
            q.insertAddon(uid, privateURL, "com.torve.panda", "Panda", "1", "", null, "{}", 1, 0, 1, null, null, "device", null)
            q.insertPlaylist(uid, "private", "Playlist", "https://tv.test/live/USER_CANARY/PW_CANARY/1", "https://tv.test/xmltv?password=PW_CANARY", 0, 1, "xtream", "https://tv.test", "USER_CANARY", "PW_CANARY")
            q.setPreference(uid, "addon_sync_pending_removals", privateURL)
            q.setPreference(uid, "panda_indexer_subkeys", "https://indexer.test/API_CANARY")
            q.setPreference(uid, "theme_mode", "DARK")
            q.setPreference(uid, "home_section_configs", "{\"url\":\"https://private.test/TOKEN_CANARY\"}")
            device.secrets.put(IntegrationSecretKey.MDBLIST_API_KEY, "MDB_CANARY")
            val repo = SyncRepositoryImpl(device.database, json, device.ids, device.secrets)
            val backup = repo.exportToJson()
            for (secret in listOf("PRIVATE_CANARY", "USER_CANARY", "PW_CANARY", "API_CANARY", "MDB_CANARY", "TOKEN_CANARY")) assertFalse(secret in backup, secret)
            assertTrue("DARK" in backup)
            val legacy = SyncPayload(exportedAt = 1, addons = listOf(SyncAddon(privateURL, true, 0)),
                preferences = listOf(SyncPreference("theme_mode", "LIGHT"), SyncPreference("API_KEY", "EVIL_CANARY")),
                integrationSecrets = listOf(SyncIntegrationSecret("MDBLIST_API_KEY", "EVIL_CANARY")),
                channelFavorites = listOf(SyncFavorite("https://tv.test/live/user/PW_CANARY/1", "p", "Channel")),
                watchProgress = listOf(SyncProgress("1399", "series", "Show", posterUrl = privateURL,
                    positionMs = 10, durationMs = 100, updatedAt = 2)))
            repo.importFromJson(json.encodeToString(legacy))
            assertEquals("MDB_CANARY", device.secrets.get(IntegrationSecretKey.MDBLIST_API_KEY))
            assertEquals("device", q.getAddonByUrl(uid, privateURL).executeAsOne().installed_from)
            assertNull(q.getProgress(uid, "1399").executeAsOne().poster_url)
            assertEquals("LIGHT", q.getPreference(uid, "theme_mode").executeAsOne())
            assertTrue("PW_CANARY" in repo.exportForLocalTransfer())
            repo.importForLocalTransfer(legacy)
            assertEquals("EVIL_CANARY", device.secrets.get(IntegrationSecretKey.MDBLIST_API_KEY))
        }
    }

    @Test fun safePreferenceNameDoesNotPermitNestedConnectionValues() {
        assertFalse(safeBackupPreference("theme_mode", "{\"nested\":{\"API_KEY\":\"opaque\"}}"))
        assertFalse(safeBackupPreference("home_layout_order", "[\"https%3A%2F%2Fprivate.test\"]"))
        assertFalse(safeBackupPreference("PANDA_INDEXER_SUBKEYS", "opaque"))
        assertTrue(safeBackupPreference("home_layout_order", "[\"trending\",\"popular\"]"))
    }
}
