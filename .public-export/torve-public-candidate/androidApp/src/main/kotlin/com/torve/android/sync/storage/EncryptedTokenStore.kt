package com.torve.android.sync.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SyncStoredSession(
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String,
    val userId: String,
    val email: String,
)

class EncryptedTokenStore(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context)
    private val secretKey: SecretKey by lazy { getOrCreateSecretKey() }

    fun saveSession(session: SyncStoredSession) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, encrypt(session.accessToken))
            .putString(KEY_REFRESH_TOKEN, encrypt(session.refreshToken))
            .putString(KEY_DEVICE_ID, encrypt(session.deviceId))
            .putString(KEY_USER_ID, encrypt(session.userId))
            .putString(KEY_EMAIL, encrypt(session.email))
            .apply()
    }

    fun loadSession(): SyncStoredSession? {
        val accessToken = decryptOrLegacy(KEY_ACCESS_TOKEN) ?: return null
        val refreshToken = decryptOrLegacy(KEY_REFRESH_TOKEN) ?: return null
        val deviceId = decryptOrLegacy(KEY_DEVICE_ID) ?: return null
        val userId = decryptOrLegacy(KEY_USER_ID) ?: return null
        val email = decryptOrLegacy(KEY_EMAIL) ?: return null
        return SyncStoredSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            deviceId = deviceId,
            userId = userId,
            email = email,
        )
    }

    fun updateAccessToken(accessToken: String) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, encrypt(accessToken)).apply()
    }

    fun updateRefreshToken(refreshToken: String) {
        prefs.edit().putString(KEY_REFRESH_TOKEN, encrypt(refreshToken)).apply()
    }

    fun getAccessToken(): String? = decryptOrLegacy(KEY_ACCESS_TOKEN)
    fun getRefreshToken(): String? = decryptOrLegacy(KEY_REFRESH_TOKEN)
    fun getDeviceId(): String? = decryptOrLegacy(KEY_DEVICE_ID)
    fun getUserId(): String? = decryptOrLegacy(KEY_USER_ID)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun createPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun decryptOrLegacy(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching { decrypt(stored) }.getOrElse { stored }
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val ivEncoded = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataEncoded = Base64.encodeToString(cipherText, Base64.NO_WRAP)
        return "$ivEncoded:$dataEncoded"
    }

    private fun decrypt(payload: String): String {
        val chunks = payload.split(":")
        require(chunks.size == 2)
        val iv = Base64.decode(chunks[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(chunks[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) {
            return existing
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
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
        const val PREFS_NAME = "torve_sync_tokens_secure"
        const val KEY_ALIAS = "torve_sync_token_key"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128

        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_EMAIL = "email"
    }
}
