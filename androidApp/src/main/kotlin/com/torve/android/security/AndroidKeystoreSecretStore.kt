package com.torve.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.security.SecureStorage
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreSecretStore(
    context: Context,
) : IntegrationSecretStore, SecureStorage {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) =
        putString(storageKey(key, subKey), value)

    override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? =
        getString(storageKey(key, subKey))

    override suspend fun remove(key: IntegrationSecretKey, subKey: String?) =
        remove(storageKey(key, subKey))

    // Scoped entries are suffixed `<KEY>:<subKey>` so they coexist in the same
    // SharedPreferences file without clobbering the legacy single-value slot.
    private fun storageKey(key: IntegrationSecretKey, subKey: String?): String =
        if (subKey.isNullOrEmpty()) key.name else "${key.name}:$subKey"

    // ── Storage mode tracking ──────────────────────────────────
    // Stored in the same encrypted SharedPreferences with a "_mode" suffix.
    // Defaults to DEVICE_ONLY for all existing installations (backward-compatible).

    private fun modeKey(key: IntegrationSecretKey): String = "${key.name}_mode"

    override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) {
        prefs.edit().putString(modeKey(key), mode.name).apply()
    }

    override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode {
        val stored = prefs.getString(modeKey(key), null) ?: return IntegrationStorageMode.DEVICE_ONLY
        return runCatching { IntegrationStorageMode.valueOf(stored) }.getOrDefault(IntegrationStorageMode.DEVICE_ONLY)
    }

    override suspend fun getSubKeys(key: IntegrationSecretKey): List<String> {
        val prefix = "${key.name}:"
        return prefs.all.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
    }

    override suspend fun clearAllSecrets() {
        val editor = prefs.edit()
        val knownPrefixes = IntegrationSecretKey.entries.map { it.name }.toSet()
        // Scoped entries ("<KEY>:<subKey>") aren't enumerable from the enum alone.
        // Walk the prefs once and drop anything that belongs to an IntegrationSecretKey
        // (bare name, scoped suffix, or mode flag).
        for (existing in prefs.all.keys) {
            val base = existing.substringBefore(':').removeSuffix("_mode")
            if (base in knownPrefixes) editor.remove(existing)
        }
        editor.apply()
    }

    override suspend fun putString(key: String, value: String) {
        val encrypted = encrypt(value)
        prefs.edit().putString(key, encrypted).apply()
    }

    override suspend fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching { decrypt(stored) }.getOrNull()
    }

    override suspend fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override suspend fun removeByPrefix(prefix: String) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(prefix) }
            .forEach(editor::remove)
        editor.apply()
    }

    override suspend fun updateStrings(updates: Map<String, String?>) {
        val editor = prefs.edit()
        updates.forEach { (key, value) ->
            if (value == null) {
                editor.remove(key)
            } else {
                editor.putString(key, encrypt(value))
            }
        }
        editor.commit()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val payload = iv + ciphertext
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_LENGTH) { "Invalid payload" }
        val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH, iv),
        )
        val decoded = cipher.doFinal(ciphertext)
        return String(decoded, StandardCharsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "integration_secret_store"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "torve_integration_secret_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH = 128
    }
}
