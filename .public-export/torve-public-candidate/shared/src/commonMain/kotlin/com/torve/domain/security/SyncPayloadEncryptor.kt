package com.torve.domain.security

/**
 * Encrypts and decrypts the sync payload before storing on the backend.
 * The encryption key is derived from the user's password at login time
 * and stored locally in SecureStorage. The server never sees the plaintext
 * integration secrets.
 */
interface SyncPayloadEncryptor {
    /** Encrypt plaintext JSON. Returns Base64-encoded ciphertext. */
    suspend fun encrypt(plaintext: String): String?

    /** Decrypt Base64-encoded ciphertext. Returns plaintext JSON, or null on failure. */
    suspend fun decrypt(ciphertext: String): String?

    /** Derive and store the encryption key from the user's password. Call at login/register. */
    suspend fun deriveAndStoreKey(email: String, password: String)

    /** True if a key is available for encryption/decryption. */
    suspend fun hasKey(): Boolean

    /** Clear the stored key (on logout). */
    suspend fun clearKey()
}
