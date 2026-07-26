package com.torve.data.integrations

import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationInstanceRole
import com.torve.domain.integrations.AutomationPermission
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrefsAutomationInstanceRepositoryTest {
    @Test
    fun `standard and uhd defaults coexist while duplicate role defaults are demoted`() = runTest {
        val prefs = MemoryPrefs()
        val secrets = MemorySecrets()
        val repository = PrefsAutomationInstanceRepository(prefs, secrets)

        repository.save(instance("series-a", AutomationInstanceRole.STANDARD, isDefault = true), "key-a")
        repository.save(instance("series-4k", AutomationInstanceRole.UHD, isDefault = true), "key-4k")
        repository.save(instance("series-b", AutomationInstanceRole.STANDARD, isDefault = true), "key-b")

        val saved = repository.list().associateBy { it.id }
        assertFalse(saved.getValue("series-a").isDefault)
        assertTrue(saved.getValue("series-b").isDefault)
        assertTrue(saved.getValue("series-4k").isDefault)
        assertEquals("key-b", repository.apiKey(saved.getValue("series-b")))
        assertEquals(IntegrationStorageMode.DEVICE_ONLY, secrets.modes[IntegrationSecretKey.SONARR_API_KEY])
    }

    @Test
    fun `direct instance validates transport and exposes only service permissions`() = runTest {
        val repository = PrefsAutomationInstanceRepository(MemoryPrefs(), MemorySecrets())
        assertFailsWith<IllegalArgumentException> {
            repository.save(instance("bad", serverUrl = "ftp://nas/sonarr"), "key")
        }

        val radarr = AutomationInstance(
            id = "movies",
            serviceType = AutomationServiceType.RADARR,
            name = "Movies",
            serverUrl = "https://radarr.example.test/",
        )
        repository.save(radarr, "key")
        val saved = repository.list().single()
        assertEquals("https://radarr.example.test", saved.serverUrl)
        assertEquals(setOf(AutomationPermission.MANAGE_MOVIES), saved.permissions)
    }

    @Test
    fun `changing service type requires and moves the encrypted key`() = runTest {
        val secrets = MemorySecrets()
        val repository = PrefsAutomationInstanceRepository(MemoryPrefs(), secrets)
        val original = instance("living-room")
        repository.save(original, "sonarr-key")
        val changed = original.copy(serviceType = AutomationServiceType.RADARR, name = "Movies")

        assertFailsWith<IllegalArgumentException> { repository.save(changed) }
        repository.save(changed, "radarr-key")

        assertEquals("radarr-key", repository.apiKey(repository.list().single()))
        assertFalse(secrets.has(IntegrationSecretKey.SONARR_API_KEY, original.id))
    }

    @Test
    fun `server url rejects embedded credentials query and fragment`() {
        assertEquals(null, com.torve.domain.integrations.normalizeAutomationServerUrl("https://user:pass@host/sonarr"))
        assertEquals(null, com.torve.domain.integrations.normalizeAutomationServerUrl("https://host/sonarr?apikey=secret"))
        assertEquals(null, com.torve.domain.integrations.normalizeAutomationServerUrl("https://host/sonarr#token"))
        assertEquals("https://host/sonarr", com.torve.domain.integrations.normalizeAutomationServerUrl("HTTPS://host/sonarr/"))
    }

    @Test
    fun `failed metadata write restores the previous encrypted key and instance`() = runTest {
        val prefs = MemoryPrefs()
        val repository = PrefsAutomationInstanceRepository(prefs, MemorySecrets())
        val original = instance("atomic")
        repository.save(original, "working-key")

        prefs.failWrites = true
        assertFailsWith<IllegalStateException> {
            repository.save(original.copy(name = "Should not persist"), "replacement-key")
        }
        prefs.failWrites = false

        val restored = repository.list().single()
        assertEquals("atomic", restored.name)
        assertEquals("working-key", repository.apiKey(restored))
    }

    private fun instance(
        id: String,
        role: AutomationInstanceRole = AutomationInstanceRole.STANDARD,
        isDefault: Boolean = false,
        serverUrl: String = "https://sonarr.example.test/",
    ) = AutomationInstance(
        id = id,
        serviceType = AutomationServiceType.SONARR,
        name = id,
        serverUrl = serverUrl,
        role = role,
        isDefault = isDefault,
    )

    private class MemoryPrefs : PreferencesRepository {
        private val values = mutableMapOf<String, String>()
        var failWrites: Boolean = false
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun setString(key: String, value: String) {
            if (failWrites) throw IllegalStateException("simulated preference failure")
            values[key] = value
        }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    private class MemorySecrets : IntegrationSecretStore {
        private val values = mutableMapOf<Pair<IntegrationSecretKey, String?>, String>()
        val modes = mutableMapOf<IntegrationSecretKey, IntegrationStorageMode>()

        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) {
            values[key to subKey] = value
        }

        override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? = values[key to subKey]

        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) {
            values.remove(key to subKey)
        }

        override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) {
            modes[key] = mode
        }

        override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode =
            modes[key] ?: IntegrationStorageMode.DEVICE_ONLY

        override suspend fun clearAllSecrets() {
            values.clear()
            modes.clear()
        }

        override suspend fun getSubKeys(key: IntegrationSecretKey): List<String> =
            values.keys.mapNotNull { (storedKey, subKey) -> subKey?.takeIf { storedKey == key } }

        fun has(key: IntegrationSecretKey, subKey: String): Boolean = values.containsKey(key to subKey)
    }
}
