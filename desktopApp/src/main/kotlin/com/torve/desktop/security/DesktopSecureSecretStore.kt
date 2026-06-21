package com.torve.desktop.security

import com.sun.jna.platform.win32.Crypt32Util
import com.torve.desktop.platform.desktopDataDir
import com.torve.desktop.platform.desktopPlatform
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationStorageMode
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties

private const val ENCRYPTED_VALUE_PREFIX = "dpapi-v1:"
internal const val SECURE_DESKTOP_SECRET_FILE_NAME = "desktop-secrets.dpapi.properties"

interface DesktopSecretCipher {
    fun protect(plaintext: ByteArray): ByteArray
    fun unprotect(ciphertext: ByteArray): ByteArray
}

class WindowsDpapiSecretCipher : DesktopSecretCipher {
    override fun protect(plaintext: ByteArray): ByteArray =
        Crypt32Util.cryptProtectData(plaintext)

    override fun unprotect(ciphertext: ByteArray): ByteArray =
        Crypt32Util.cryptUnprotectData(ciphertext)
}

class DesktopSecureSecretStore(
    private val cipher: DesktopSecretCipher = WindowsDpapiSecretCipher(),
    private val storeFile: File = File(desktopDataDir(), SECURE_DESKTOP_SECRET_FILE_NAME),
    private val insecureStoreFile: File = File(desktopDataDir(), INSECURE_DESKTOP_SECRET_FILE_NAME),
    private val deleteMigratedInsecureStore: Boolean = true,
) : DesktopSecretStore {
    private val lock = Any()
    private val properties = Properties()

    init {
        load()
        migrateFromInsecureStore()
    }

    override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) {
        putString(storageKey(key, subKey), value)
    }

    override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? =
        getString(storageKey(key, subKey))

    override suspend fun remove(key: IntegrationSecretKey, subKey: String?) {
        remove(storageKey(key, subKey))
    }

    override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) {
        putString(modeKey(key), mode.name)
    }

    override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode {
        val stored = getString(modeKey(key)) ?: return IntegrationStorageMode.DEVICE_ONLY
        return runCatching { IntegrationStorageMode.valueOf(stored) }
            .getOrDefault(IntegrationStorageMode.DEVICE_ONLY)
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
            properties.setProperty(key, encrypt(value))
            save()
        }
    }

    override suspend fun getString(key: String): String? {
        synchronized(lock) {
            return decrypt(properties.getProperty(key) ?: return null)
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

    private fun storageKey(key: IntegrationSecretKey, subKey: String?): String =
        if (subKey.isNullOrEmpty()) key.name else "${key.name}:$subKey"

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
            properties.store(output, "Torve desktop encrypted secrets")
        }
    }

    private fun migrateFromInsecureStore() {
        synchronized(lock) {
            if (!insecureStoreFile.exists()) return

            val insecureProperties = Properties()
            insecureStoreFile.inputStream().use { insecureProperties.load(it) }
            val copiedKeys = mutableListOf<String>()

            insecureProperties.stringPropertyNames().forEach { key ->
                if (!properties.containsKey(key)) {
                    properties.setProperty(key, encrypt(insecureProperties.getProperty(key)))
                    copiedKeys += key
                }
            }

            if (copiedKeys.isNotEmpty()) {
                save()
            }

            val copiedValuesVerified = copiedKeys.all { key ->
                decrypt(properties.getProperty(key)) == insecureProperties.getProperty(key)
            }
            if (copiedValuesVerified && deleteMigratedInsecureStore) {
                removeMigratedInsecureStore()
            }
        }
    }

    private fun removeMigratedInsecureStore() {
        if (insecureStoreFile.delete() || !insecureStoreFile.exists()) return
        runCatching { insecureStoreFile.writeText("") }
        runCatching { insecureStoreFile.delete() }
    }

    private fun encrypt(value: String): String {
        val plaintext = value.toByteArray(StandardCharsets.UTF_8)
        val protected = cipher.protect(plaintext)
        return ENCRYPTED_VALUE_PREFIX + Base64.getEncoder().encodeToString(protected)
    }

    private fun decrypt(value: String): String? {
        if (!value.startsWith(ENCRYPTED_VALUE_PREFIX)) return null
        val encrypted = Base64.getDecoder().decode(value.removePrefix(ENCRYPTED_VALUE_PREFIX))
        val plaintext = cipher.unprotect(encrypted)
        return String(plaintext, StandardCharsets.UTF_8)
    }
}

fun createDesktopSecretStore(): DesktopSecretStore =
    createDesktopSecretStore(platform = desktopPlatform())

internal fun createDesktopSecretStore(
    platform: String,
    allowInsecureFallback: Boolean = isInsecureDesktopSecretStoreAllowed(),
    secureFactory: () -> DesktopSecretStore = { DesktopSecureSecretStore() },
    insecureFactory: () -> DesktopSecretStore = { InsecureDesktopFileSecretStore() },
): DesktopSecretStore {
    if (platform == "windows") {
        return runCatching { secureFactory() }.getOrElse { error ->
            if (allowInsecureFallback) {
                insecureFactory()
            } else {
                throw IllegalStateException("Windows desktop secure vault could not be initialized.", error)
            }
        }
    }
    return insecureFactory()
}

private fun isInsecureDesktopSecretStoreAllowed(): Boolean =
    System.getProperty("torve.allowInsecureDesktopSecretStore") == "true" ||
        System.getenv("TORVE_ALLOW_INSECURE_DESKTOP_SECRET_STORE") == "1"
