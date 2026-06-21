package com.torve.data.addon

import com.torve.data.account.AccountSettingsApi
import com.torve.domain.model.AddonManifest
import com.torve.domain.model.InstalledAddon
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AddonSyncServiceTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun signInSyncInstallsServerAddonLocally() = runTest {
        val repo = FakeAddonRepository()
        val service = serviceWithResponses(
            repo = repo,
            responses = listOf(
                HttpStatusCode.OK to """
                    [
                      {
                        "id":"server-1",
                        "manifest_url":"https://example.com/manifest.json",
                        "addon_id":"com.example.addon",
                        "name":"Example",
                        "description":"Example addon",
                        "version":"1.0.0",
                        "has_catalog":true,
                        "has_streams":true,
                        "is_enabled":false,
                        "sort_order":2,
                        "installed_from":"web",
                        "created_at":"2026-03-27T09:00:00Z",
                        "updated_at":"2026-03-27T09:00:00Z"
                      }
                    ]
                """.trimIndent(),
            ),
        )

        service.syncAfterSignIn()

        val installed = repo.getAddon("https://example.com/manifest.json")
        assertNotNull(installed)
        assertEquals("server-1", installed.serverId)
        assertEquals(false, installed.isEnabled)
        assertEquals(2, installed.priority)
        assertEquals("web", installed.installedFrom)
    }

    @Test
    fun localAddonMissingOnServerGetsPushed() = runTest {
        val repo = FakeAddonRepository().apply {
            seed(installedAddon("https://local.example/manifest.json", syncedAt = null))
        }
        val service = serviceWithResponses(
            repo = repo,
            responses = listOf(
                HttpStatusCode.OK to "[]",
                HttpStatusCode.OK to addonResponse("server-local", "https://local.example/manifest.json", 0),
                HttpStatusCode.OK to addonResponse("server-local", "https://local.example/manifest.json", 0),
            ),
        )

        service.syncAfterSignIn()

        val synced = repo.getAddon("https://local.example/manifest.json")
        assertNotNull(synced)
        assertEquals("server-local", synced.serverId)
        assertNotNull(synced.syncedAt)
    }

    @Test
    fun duplicateManifestUrlDoesNotCreateDuplicateLocalAddon() = runTest {
        val repo = FakeAddonRepository().apply {
            seed(installedAddon("https://duplicate.example/manifest.json", serverId = "server-dup", syncedAt = 1L))
        }
        val service = serviceWithResponses(
            repo = repo,
            responses = listOf(
                HttpStatusCode.OK to """
                    [
                      {
                        "id":"server-dup",
                        "manifest_url":"https://duplicate.example/manifest.json",
                        "addon_id":"com.example.addon",
                        "name":"Example",
                        "description":"Example addon",
                        "version":"1.0.0",
                        "has_catalog":true,
                        "has_streams":true,
                        "is_enabled":true,
                        "sort_order":0,
                        "installed_from":"app",
                        "created_at":"2026-03-27T09:00:00Z",
                        "updated_at":"2026-03-27T09:00:00Z"
                      }
                    ]
                """.trimIndent(),
            ),
        )

        service.syncAfterSignIn()

        assertEquals(1, repo.getInstalledAddons().size)
    }

    @Test
    fun addonRemovedOnWebDisappearsLocallyOnNextSync() = runTest {
        val repo = FakeAddonRepository().apply {
            seed(installedAddon("https://removed.example/manifest.json", serverId = "server-removed", syncedAt = 5L, installedFrom = "web"))
        }
        val service = serviceWithResponses(
            repo = repo,
            responses = listOf(HttpStatusCode.OK to "[]"),
        )

        service.syncAfterSignIn()

        assertNull(repo.getAddon("https://removed.example/manifest.json"))
    }

    @Test
    fun addonAddedOnWebAppearsLocallyOnNextSync() = runTest {
        val repo = FakeAddonRepository()
        val service = serviceWithResponses(
            repo = repo,
            responses = listOf(
                HttpStatusCode.OK to """
                    [
                      {
                        "id":"server-web",
                        "manifest_url":"https://web.example/manifest.json",
                        "addon_id":"com.example.addon",
                        "name":"Example",
                        "description":"Example addon",
                        "version":"1.0.0",
                        "has_catalog":true,
                        "has_streams":true,
                        "is_enabled":true,
                        "sort_order":1,
                        "installed_from":"web",
                        "created_at":"2026-03-27T09:00:00Z",
                        "updated_at":"2026-03-27T09:00:00Z"
                      }
                    ]
                """.trimIndent(),
            ),
        )

        service.syncAfterSignIn()

        val addon = repo.getAddon("https://web.example/manifest.json")
        assertNotNull(addon)
        assertEquals("server-web", addon.serverId)
    }

    @Test
    fun toggleStateSyncsFromServer() = runTest {
        val repo = FakeAddonRepository().apply {
            seed(installedAddon("https://toggle.example/manifest.json", serverId = "server-toggle", syncedAt = 7L, enabled = true))
        }
        val service = serviceWithResponses(
            repo = repo,
            responses = listOf(
                HttpStatusCode.OK to """
                    [
                      {
                        "id":"server-toggle",
                        "manifest_url":"https://toggle.example/manifest.json",
                        "addon_id":"com.example.addon",
                        "name":"Example",
                        "description":"Example addon",
                        "version":"1.0.0",
                        "has_catalog":true,
                        "has_streams":true,
                        "is_enabled":false,
                        "sort_order":4,
                        "installed_from":"web",
                        "created_at":"2026-03-27T09:00:00Z",
                        "updated_at":"2026-03-27T09:00:00Z"
                      }
                    ]
                """.trimIndent(),
            ),
        )

        service.syncAfterSignIn()

        val addon = repo.getAddon("https://toggle.example/manifest.json")
        assertNotNull(addon)
        assertEquals(false, addon.isEnabled)
        assertEquals(4, addon.priority)
    }

    @Test
    fun backendFailureDoesNotBreakLocalAddonOperations() = runTest {
        val repo = FakeAddonRepository()
        val addon = installedAddon("https://failure.example/manifest.json", syncedAt = null)
        repo.seed(addon)
        val service = serviceWithResponses(
            repo = repo,
            responses = listOf(HttpStatusCode.InternalServerError to """{"detail":"boom"}"""),
        )

        service.onAddonInstalled(addon)

        val stillLocal = repo.getAddon("https://failure.example/manifest.json")
        assertNotNull(stillLocal)
        assertNull(stillLocal.serverId)
    }

    @Test
    fun signOutClearsSyncMetadataButKeepsLocalAddons() = runTest {
        val repo = FakeAddonRepository().apply {
            seed(installedAddon("https://keep.example/manifest.json", serverId = "server-keep", syncedAt = 99L, installedFrom = "web"))
        }
        val prefs = FakePreferencesRepository().apply {
            setString("addon_sync_last_at", "123")
        }
        val service = AddonSyncService(
            accessTokenProvider = { "token" },
            addonRepo = repo,
            accountSettingsApi = accountApi { _, _ -> respondJson("[]") },
            prefsRepo = prefs,
            settingsRefreshNotifier = SettingsRefreshNotifier(),
            json = json,
            clock = TestClock(1_000L),
        )

        service.clearSyncStateOnSignOut()

        val addon = repo.getAddon("https://keep.example/manifest.json")
        assertNotNull(addon)
        assertNull(addon.serverId)
        assertNull(addon.syncedAt)
        assertEquals("web", addon.installedFrom)
        assertNull(prefs.getString("addon_sync_last_at"))
    }

    @Test
    fun appResumeTriggersSyncOnlyWhenStale() = runTest {
        val repo = FakeAddonRepository()
        val prefs = FakePreferencesRepository()
        val calls = mutableListOf<String>()
        val service = AddonSyncService(
            accessTokenProvider = { "token" },
            addonRepo = repo,
            accountSettingsApi = accountApi { _, url ->
                calls += url
                respondJson("[]")
            },
            prefsRepo = prefs,
            settingsRefreshNotifier = SettingsRefreshNotifier(),
            json = json,
            clock = TestClock(1_000_000L),
        )

        service.syncIfStale(reason = "foreground")
        service.syncIfStale(reason = "foreground")

        assertEquals(1, calls.size)
    }

    private fun serviceWithResponses(
        repo: FakeAddonRepository,
        responses: List<Pair<HttpStatusCode, String>>,
    ): AddonSyncService {
        val queue = ArrayDeque(responses)
        return AddonSyncService(
            accessTokenProvider = { "token" },
            addonRepo = repo,
            accountSettingsApi = accountApi { _, _ ->
                val (status, body) = queue.removeFirst()
                respondJson(body, status)
            },
            prefsRepo = FakePreferencesRepository(),
            settingsRefreshNotifier = SettingsRefreshNotifier(),
            json = json,
            clock = TestClock(10_000L),
        )
    }

    private fun accountApi(
        handler: suspend MockRequestHandleScope.(method: String, url: String) -> HttpResponseData,
    ): AccountSettingsApi {
        val client = HttpClient(MockEngine { request ->
            handler(request.method.value, request.url.toString())
        }) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return AccountSettingsApi(client, baseUrlProvider = { "https://api.torve.app" })
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData {
        return respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun addonResponse(
        id: String,
        manifestUrl: String,
        sortOrder: Int,
    ): String {
        return """
            {
              "id":"$id",
              "manifest_url":"$manifestUrl",
              "addon_id":"com.example.addon",
              "name":"Example",
              "description":"Example addon",
              "version":"1.0.0",
              "has_catalog":true,
              "has_streams":true,
              "is_enabled":true,
              "sort_order":$sortOrder,
              "installed_from":"app",
              "created_at":"2026-03-27T09:00:00Z",
              "updated_at":"2026-03-27T09:00:00Z"
            }
        """.trimIndent()
    }

    private fun installedAddon(
        manifestUrl: String,
        serverId: String? = null,
        syncedAt: Long? = null,
        enabled: Boolean = true,
        priority: Int = 0,
        installedFrom: String = "app",
    ): InstalledAddon {
        return InstalledAddon(
            manifestUrl = manifestUrl,
            manifest = AddonManifest(
                id = "com.example.addon",
                name = "Example",
                version = "1.0.0",
                description = "Example addon",
                resources = listOf("catalog", "stream"),
            ),
            isEnabled = enabled,
            priority = priority,
            installedAt = 1L,
            serverId = serverId,
            syncedAt = syncedAt,
            installedFrom = installedFrom,
        )
    }
}

private class FakeAddonRepository : AddonRepository {
    private val addons = linkedMapOf<String, InstalledAddon>()

    fun seed(vararg installedAddons: InstalledAddon) {
        installedAddons.forEach { addons[normalize(it.manifestUrl)] = it }
    }

    override suspend fun installAddon(
        url: String,
        enabled: Boolean,
        priority: Int?,
        serverId: String?,
        syncedAt: Long?,
        installedFrom: String,
    ): InstalledAddon {
        val installed = InstalledAddon(
            manifestUrl = normalize(url),
            manifest = AddonManifest(
                id = "com.example.addon",
                name = "Example",
                version = "1.0.0",
                description = "Example addon",
                resources = listOf("catalog", "stream"),
            ),
            isEnabled = enabled,
            priority = priority ?: addons.size,
            installedAt = 1L,
            serverId = serverId,
            syncedAt = syncedAt,
            installedFrom = installedFrom,
        )
        addons[normalize(url)] = installed
        return installed
    }

    override suspend fun removeAddon(manifestUrl: String) {
        addons.remove(normalize(manifestUrl))
    }

    override suspend fun getInstalledAddons(): List<InstalledAddon> = addons.values.sortedBy { it.priority }

    override suspend fun getEnabledAddons(): List<InstalledAddon> = addons.values.filter { it.isEnabled }.sortedBy { it.priority }

    override suspend fun toggleAddon(manifestUrl: String, enabled: Boolean) {
        val key = normalize(manifestUrl)
        val addon = addons[key] ?: return
        addons[key] = addon.copy(isEnabled = enabled, syncedAt = null)
    }

    override suspend fun reorderAddons(orderedUrls: List<String>) {
        orderedUrls.forEachIndexed { index, url ->
            val key = normalize(url)
            val addon = addons[key] ?: return@forEachIndexed
            addons[key] = addon.copy(priority = index, syncedAt = null)
        }
    }

    override suspend fun getManifest(url: String): AddonManifest {
        return addons[normalize(url)]?.manifest ?: AddonManifest(
            id = "com.example.addon",
            name = "Example",
            version = "1.0.0",
            resources = listOf("catalog", "stream"),
        )
    }

    override suspend fun getAddon(manifestUrl: String): InstalledAddon? = addons[normalize(manifestUrl)]

    override suspend fun markAddonSynced(manifestUrl: String, serverId: String, syncedAt: Long?, installedFrom: String) {
        val key = normalize(manifestUrl)
        val addon = addons[key] ?: return
        addons[key] = addon.copy(serverId = serverId, syncedAt = syncedAt, installedFrom = installedFrom)
    }

    override suspend fun syncRemoteState(
        manifestUrl: String,
        serverId: String,
        enabled: Boolean,
        priority: Int,
        syncedAt: Long,
        installedFrom: String,
    ) {
        val key = normalize(manifestUrl)
        val addon = addons[key] ?: return
        addons[key] = addon.copy(
            serverId = serverId,
            isEnabled = enabled,
            priority = priority,
            syncedAt = syncedAt,
            installedFrom = installedFrom,
        )
    }

    override suspend fun clearSyncMetadata() {
        addons.keys.toList().forEach { key ->
            val addon = addons[key] ?: return@forEach
            addons[key] = addon.copy(serverId = null, syncedAt = null)
        }
    }

    override suspend fun setAddonConfigId(manifestUrl: String, configId: String?) {
        val normalized = normalize(manifestUrl)
        addons[normalized]?.let { existing ->
            addons[normalized] = existing.copy(configId = configId)
        }
    }

    private fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return "${trimmed.removeSuffix("/manifest.json")}/manifest.json"
    }
}

private class FakePreferencesRepository : PreferencesRepository {
    private val values = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun setString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

private class TestClock(private var current: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(current++)
}
