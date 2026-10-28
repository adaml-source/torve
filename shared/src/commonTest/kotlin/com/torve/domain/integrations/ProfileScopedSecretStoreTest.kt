package com.torve.domain.integrations

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProfileScopedSecretStoreTest {
    @Test fun connectionsAndStorageModeStayWithTheSelectedPersonAndPrimarySlotsSurviveUpgrade() = runTest {
        var person = "default"
        val raw = MemorySecrets()
        raw.put(IntegrationSecretKey.TRAKT_TOKENS, "primary-token")
        val scoped = ProfileScopedSecretStore(raw, { person })
        assertEquals("primary-token", scoped.get(IntegrationSecretKey.TRAKT_TOKENS))
        person = "second"
        assertNull(scoped.get(IntegrationSecretKey.TRAKT_TOKENS))
        scoped.put(IntegrationSecretKey.TRAKT_TOKENS, "second-token")
        scoped.setStorageMode(IntegrationSecretKey.TRAKT_TOKENS, IntegrationStorageMode.ACCOUNT)
        scoped.put(IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN, "second-config", "config")
        assertEquals(listOf("config"), scoped.getSubKeys(IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN))
        person = "third"
        assertNull(scoped.get(IntegrationSecretKey.TRAKT_TOKENS))
        assertEquals(IntegrationStorageMode.DEVICE_ONLY, scoped.getStorageMode(IntegrationSecretKey.TRAKT_TOKENS))
        person = "default"
        assertEquals("primary-token", scoped.get(IntegrationSecretKey.TRAKT_TOKENS))
        assertTrue(scoped.getSubKeys(IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN).isEmpty())
        person = "second"
        assertEquals("second-token", scoped.get(IntegrationSecretKey.TRAKT_TOKENS))
        assertEquals(IntegrationStorageMode.ACCOUNT, scoped.getStorageMode(IntegrationSecretKey.TRAKT_TOKENS))
    }
    private class MemorySecrets : IntegrationSecretStore {
        private val entries = mutableMapOf<Pair<IntegrationSecretKey, String?>, String>()
        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) { entries[key to subKey] = value }
        override suspend fun get(key: IntegrationSecretKey, subKey: String?) = entries[key to subKey]
        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) { entries.remove(key to subKey) }
        override suspend fun getSubKeys(key: IntegrationSecretKey) = entries.keys.filter { it.first == key }.mapNotNull { it.second }
        override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
        override suspend fun getStorageMode(key: IntegrationSecretKey) = IntegrationStorageMode.DEVICE_ONLY
        override suspend fun clearAllSecrets() { entries.clear() }
    }
}
