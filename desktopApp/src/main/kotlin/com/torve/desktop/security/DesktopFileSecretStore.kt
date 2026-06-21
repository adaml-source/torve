package com.torve.desktop.security

import com.torve.desktop.platform.desktopDataDir
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationStorageMode
import java.io.File
import java.util.Properties

/**
 * Insecure fallback desktop secret store.
 *
 * This is intentionally a bounded local file store so desktop bootstrap can
 * start cleanly without introducing OS-specific credential vault work yet.
 *
 * Windows uses [DesktopSecureSecretStore]. This fallback remains for
 * unsupported desktop platforms and explicitly opted-in development runs.
 */
class InsecureDesktopFileSecretStore(
    private val storeFile: File = File(desktopDataDir(), INSECURE_DESKTOP_SECRET_FILE_NAME),
) : DesktopSecretStore {
    private val lock = Any()
    private val properties = Properties()

    init {
        load()
    }

    override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) {
        putString(storageKey(key, subKey), value)
    }

    override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? =
        getString(storageKey(key, subKey))

    override suspend fun remove(key: IntegrationSecretKey, subKey: String?) {
        remove(storageKey(key, subKey))
    }

    // Scoped entries are suffixed `<KEY>:<subKey>` so they coexist with the
    // legacy single-value slot (stored under the bare key name).
    private fun storageKey(key: IntegrationSecretKey, subKey: String?): String =
        if (subKey.isNullOrEmpty()) key.name else "${key.name}:$subKey"

    override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) {
        synchronized(lock) {
            properties.setProperty(modeKey(key), mode.name)
            save()
        }
    }

    override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode {
        synchronized(lock) {
            val stored = properties.getProperty(modeKey(key)) ?: return IntegrationStorageMode.DEVICE_ONLY
            return runCatching { IntegrationStorageMode.valueOf(stored) }
                .getOrDefault(IntegrationStorageMode.DEVICE_ONLY)
        }
    }

    override suspend fun getSubKeys(key: IntegrationSecretKey): List<String> {
        synchronized(lock) {
            val prefix = "${key.name}:"
            return properties.stringPropertyNames()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
        }
    }

    override suspend fun clearAllSecrets() {
        synchronized(lock) {
            val knownPrefixes = IntegrationSecretKey.entries.map { it.name }.toSet()
            // Walk once and drop every entry owned by an IntegrationSecretKey -
            // covers bare name, "<KEY>:<subKey>" scoped entries, and "<KEY>_mode".
            val toRemove = properties.stringPropertyNames().filter { existing ->
                val base = existing.substringBefore(':').removeSuffix("_mode")
                base in knownPrefixes
            }
            toRemove.forEach(properties::remove)
            save()
        }
    }

    override suspend fun putString(key: String, value: String) {
        synchronized(lock) {
            properties.setProperty(key, value)
            save()
        }
    }

    override suspend fun getString(key: String): String? {
        synchronized(lock) {
            return properties.getProperty(key)
        }
    }

    override suspend fun remove(key: String) {
        synchronized(lock) {
            properties.remove(key)
            save()
        }
    }

    override suspend fun removeByPrefix(prefix: String) {
        synchronized(lock) {
            properties.stringPropertyNames()
                .filter { it.startsWith(prefix) }
                .forEach(properties::remove)
            save()
        }
    }

    private fun modeKey(key: IntegrationSecretKey): String = "${key.name}_mode"

    private fun load() {
        synchronized(lock) {
            if (!storeFile.exists()) return
            storeFile.inputStream().use { properties.load(it) }
        }
    }

    private fun save() {
        storeFile.parentFile?.mkdirs()
        storeFile.outputStream().use { output ->
            properties.store(output, "Torve desktop preview secrets")
        }
    }
}

@Deprecated("Use DesktopSecretStore from DI; Windows binds DesktopSecureSecretStore.")
typealias DesktopFileSecretStore = InsecureDesktopFileSecretStore

internal const val INSECURE_DESKTOP_SECRET_FILE_NAME = "desktop-secrets.properties"
