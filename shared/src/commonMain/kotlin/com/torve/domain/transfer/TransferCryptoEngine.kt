package com.torve.domain.transfer

/**
 * Cryptographic primitives the protocol needs. Implementations are
 * platform-specific (JVM via `java.security`, iOS via CryptoKit) — but
 * the protocol layer above only sees this interface.
 *
 * Implementations MUST throw `IllegalStateException` for primitive
 * failures (e.g. unsupported curve) and return null for AEAD
 * authentication failures so the protocol can map them onto
 * [TransferDecryptResult.AuthenticationFailure].
 */
interface TransferCryptoEngine {

    /** Fresh X25519 key pair. Public key is 32 bytes; private key opaque. */
    suspend fun generateEphemeralKeyPair(): EphemeralKeyPair

    /**
     * X25519 ECDH agreement followed by HKDF-SHA256 expansion to a
     * 32-byte symmetric key. `info` namespaces the derivation; the
     * protocol layer always passes [SHARED_KEY_INFO].
     */
    suspend fun deriveSharedKey(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
        info: ByteArray,
    ): ByteArray

    /**
     * AES-256-GCM seal. `nonce` is 12 bytes; `key` is 32 bytes;
     * `associatedData` is authenticated but not encrypted.
     */
    suspend fun encryptAead(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    /**
     * AES-256-GCM open. Returns null when the auth tag fails to verify
     * (wrong key, tampered ciphertext, wrong AAD).
     */
    suspend fun decryptAead(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray?

    /** Cryptographically secure random bytes. */
    suspend fun secureRandom(byteCount: Int): ByteArray
}
