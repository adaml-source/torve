package com.torve.android.security

import android.util.Base64
import com.torve.domain.security.SecureStorage
import com.torve.domain.security.SyncPayloadEncryptor
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for sync payloads.
 *
 * Key derivation: SHA-256(email_lowercase + ":" + password) → 256-bit AES key.
 * The derived key is stored in [SecureStorage] (Android Keystore-backed)
 * so decryption works without re-entering the password.
 *
 * Wire format: Base64(IV_12_bytes + ciphertext_with_tag)
 */
class AndroidSyncPayloadEncryptor(
    private val secureStorage: SecureStorage,
) : SyncPayloadEncryptor {

    override suspend fun encrypt(plaintext: String): String? {
        val keyBytes = loadKeyBytes() ?: return null
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val payload = iv + ciphertext
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    override suspend fun decrypt(ciphertext: String): String? {
        val keyBytes = loadKeyBytes() ?: return null
        return try {
            val payload = Base64.decode(ciphertext, Base64.NO_WRAP)
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
        val input = "${email.lowercase().trim()}:$password"
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        val encoded = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        secureStorage.putString(KEY_STORAGE_NAME, encoded)
    }

    override suspend fun hasKey(): Boolean {
        return secureStorage.getString(KEY_STORAGE_NAME) != null
    }

    override suspend fun clearKey() {
        secureStorage.remove(KEY_STORAGE_NAME)
    }

    private suspend fun loadKeyBytes(): ByteArray? {
        val encoded = secureStorage.getString(KEY_STORAGE_NAME) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    private companion object {
        const val KEY_STORAGE_NAME = "sync_payload_encryption_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BIT_LENGTH = 128
    }
}
