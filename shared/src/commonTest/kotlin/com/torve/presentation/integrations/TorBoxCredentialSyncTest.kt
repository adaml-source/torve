package com.torve.presentation.integrations

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorBoxCredentialSyncTest {
    @Test
    fun syncMirrorsDebridTorBoxKeyToPandaDownloadClient() = runTest {
        val store = FakeSecretStore()
        store.put(IntegrationSecretKey.DEBRID_API_KEY_TORBOX, "tb-key")

        assertTrue(store.syncTorBoxCredentialPair())

        assertEquals("tb-key", store.get(IntegrationSecretKey.DEBRID_API_KEY_TORBOX))
        assertEquals(
            "tb-key",
            store.get(
                IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY,
                subKey = TORBOX_DOWNLOAD_CLIENT_SUBKEY,
            ),
        )
    }

    @Test
    fun syncMirrorsPandaDownloadClientTorBoxKeyToDebrid() = runTest {
        val store = FakeSecretStore()
        store.put(
            IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY,
            "tb-key",
            subKey = TORBOX_DOWNLOAD_CLIENT_SUBKEY,
        )

        assertTrue(store.syncTorBoxCredentialPair())

        assertEquals("tb-key", store.get(IntegrationSecretKey.DEBRID_API_KEY_TORBOX))
        assertEquals(
            "tb-key",
            store.get(
                IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY,
                subKey = TORBOX_DOWNLOAD_CLIENT_SUBKEY,
            ),
        )
    }

    @Test
    fun syncIgnoresBlankAndRedactedValues() = runTest {
        val store = FakeSecretStore()

        assertFalse(store.syncTorBoxCredentialPair("[redacted]"))
        assertEquals(null, store.get(IntegrationSecretKey.DEBRID_API_KEY_TORBOX))
        assertEquals(
            null,
            store.get(
                IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY,
                subKey = TORBOX_DOWNLOAD_CLIENT_SUBKEY,
            ),
        )
    }
}

private class FakeSecretStore : IntegrationSecretStore {
    private val values = mutableMapOf<Pair<IntegrationSecretKey, String?>, String>()
    private val modes = mutableMapOf<IntegrationSecretKey, IntegrationStorageMode>()

    override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) {
        values[key to subKey] = value
    }

    override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? {
        return values[key to subKey]
    }

    override suspend fun remove(key: IntegrationSecretKey, subKey: String?) {
        values.remove(key to subKey)
    }

    override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) {
        modes[key] = mode
    }

    override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode {
        return modes[key] ?: IntegrationStorageMode.DEVICE_ONLY
    }

    override suspend fun clearAllSecrets() {
        values.clear()
        modes.clear()
    }
}
