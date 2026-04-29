package com.torve.desktop.security

import com.torve.domain.security.SecureStorage
import com.torve.domain.security.SyncPayloadEncryptor
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DesktopSyncPayloadEncryptor(
    private val secureStorage: SecureStorage,
) : SyncPayloadEncryptor {
    override suspend fun encrypt(plaintext: String): String? {
        val keyBytes = loadKeyBytes() ?: return null
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    override suspend fun decrypt(ciphertext: String): String? {
        val keyBytes = loadKeyBytes() ?: return null
        return try {
            val payload = Base64.getDecoder().decode(ciphertext)
            if (payload.size <= IV_LENGTH) return null
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val encrypted = payload.copyOfRange(IV_LENGTH, payload.size)
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BIT_LENGTH, iv))
            val decrypted = cipher.doFinal(encrypted)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun deriveAndStoreKey(email: String, password: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "${email.lowercase().trim()}:$password"
        val keyBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        secureStorage.putString(KEY_STORAGE_NAME, Base64.getEncoder().encodeToString(keyBytes))
    }

    override suspend fun hasKey(): Boolean = secureStorage.getString(KEY_STORAGE_NAME) != null

    override suspend fun clearKey() {
        secureStorage.remove(KEY_STORAGE_NAME)
    }

    private suspend fun loadKeyBytes(): ByteArray? {
        val encoded = secureStorage.getString(KEY_STORAGE_NAME) ?: return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    private companion object {
        const val KEY_STORAGE_NAME = "sync_payload_encryption_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BIT_LENGTH = 128
    }
}
