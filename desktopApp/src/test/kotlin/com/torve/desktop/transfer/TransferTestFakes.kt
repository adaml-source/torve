package com.torve.desktop.transfer

import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.repository.PreferencesRepository

class FakeTransferPrefs : PreferencesRepository {
    val store = mutableMapOf<String, String>()
    override suspend fun getString(key: String): String? = store[key]
    override suspend fun setString(key: String, value: String) {
        store[key] = value
    }
    override suspend fun remove(key: String) {
        store.remove(key)
    }
}

class FakeTransferSecretStore : IntegrationSecretStore {
    private val backing = mutableMapOf<String, String>()
    var putCount: Int = 0
    var failPutOn: ((IntegrationSecretKey, String?) -> Boolean)? = null

    private fun addr(key: IntegrationSecretKey, subKey: String?): String =
        "${key.name}|${subKey.orEmpty()}"

    fun seed(key: IntegrationSecretKey, value: String, subKey: String? = null) {
        backing[addr(key, subKey)] = value
    }

    fun snapshot(): Map<String, String> = backing.toMap()

    override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) {
        putCount += 1
        if (failPutOn?.invoke(key, subKey) == true) {
            throw RuntimeException("simulated put failure for $key")
        }
        backing[addr(key, subKey)] = value
    }

    override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? =
        backing[addr(key, subKey)]

    override suspend fun remove(key: IntegrationSecretKey, subKey: String?) {
        backing.remove(addr(key, subKey))
    }

    override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit

    override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode =
        IntegrationStorageMode.DEVICE_ONLY

    override suspend fun clearAllSecrets() {
        backing.clear()
    }
}

class FakeTransferDeviceIdProvider : DeviceIdProvider {
    override fun getDeviceId(): String = "desktop-test-device"
    override fun getDeviceName(): String = "Desktop Test"
    override fun getDeviceType(): String = "desktop"
    override fun getPlatform(): String = "desktop"
}
