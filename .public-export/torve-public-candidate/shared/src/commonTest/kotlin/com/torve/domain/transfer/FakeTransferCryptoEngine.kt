package com.torve.domain.transfer

/**
 * Test-only [TransferCryptoEngine]. Deterministic, in-memory.
 *
 *   - Key pairs are 32 bytes of `(counter, repeated)` so we can produce
 *     many distinct pairs in a test run.
 *   - "ECDH" is byte-wise XOR of priv ⊕ peer-pub, then "HKDF" is the
 *     SHA-1-flavoured combine `info || sharedSecret` truncated to 32
 *     bytes (it's deterministic and free of platform crypto). The
 *     critical invariant — "two parties with matched key pairs derive
 *     the same key" — is preserved because XOR is commutative when both
 *     sides feed the same (priv, peer-pub) shape that real X25519 has.
 *   - "AEAD" is plaintext XOR'd with the key (cyclic), prefixed by a
 *     16-byte synthetic auth tag = first 16 bytes of XOR(plaintext, key,
 *     associatedData). Decryption recomputes the tag and rejects on
 *     mismatch.
 *
 * Real cryptographic correctness is exercised by `JvmTransferCryptoEngineTest`
 * in desktopApp/src/test against the actual JVM engine. This fake only
 * exists so commonTest can verify the *protocol* layer's
 * version/replay/expiry/AAD-binding rules.
 */
internal class FakeTransferCryptoEngine : TransferCryptoEngine {

    private var keyCounter: Int = 0
    private var randomCounter: Int = 100

    override suspend fun generateEphemeralKeyPair(): EphemeralKeyPair {
        keyCounter += 1
        val pub = ByteArray(32) { (keyCounter * 7 + it).toByte() }
        val priv = ByteArray(32) { (keyCounter * 13 + it).toByte() }
        // Store linkage so deriveSharedKey can reconstruct ECDH symmetry:
        // XOR(priv_a, pub_b) == XOR(priv_b, pub_a) is NOT generally true,
        // so we instead derive a stable "session id" by hashing the SET
        // of (low byte) seeds. See deriveSharedKey.
        privToCounter[priv.contentToString()] = keyCounter
        pubToCounter[pub.contentToString()] = keyCounter
        return EphemeralKeyPair(publicKey = pub, privateKey = priv)
    }

    override suspend fun deriveSharedKey(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
        info: ByteArray,
    ): ByteArray {
        val a = privToCounter[privateKey.contentToString()]
            ?: error("unknown private key in fake engine")
        val b = pubToCounter[peerPublicKey.contentToString()]
            ?: error("unknown peer public key in fake engine")
        val ordered = if (a < b) intArrayOf(a, b) else intArrayOf(b, a)
        val seed = (ordered[0].toString() + ":" + ordered[1].toString() + ":" + info.decodeToString())
        // Deterministic 32-byte derivation: repeating seed bytes mod 256.
        val seedBytes = seed.encodeToByteArray()
        return ByteArray(32) { seedBytes[it % seedBytes.size] }
    }

    override suspend fun encryptAead(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val tag = computeTag(key, nonce, plaintext, associatedData)
        val body = ByteArray(plaintext.size) { i ->
            (plaintext[i].toInt() xor key[i % key.size].toInt() xor nonce[i % nonce.size].toInt()).toByte()
        }
        return tag + body
    }

    override suspend fun decryptAead(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray? {
        if (ciphertext.size < 16) return null
        val tag = ciphertext.copyOfRange(0, 16)
        val body = ciphertext.copyOfRange(16, ciphertext.size)
        val plaintext = ByteArray(body.size) { i ->
            (body[i].toInt() xor key[i % key.size].toInt() xor nonce[i % nonce.size].toInt()).toByte()
        }
        val expected = computeTag(key, nonce, plaintext, associatedData)
        if (!expected.contentEquals(tag)) return null
        return plaintext
    }

    override suspend fun secureRandom(byteCount: Int): ByteArray {
        randomCounter += 1
        return ByteArray(byteCount) { (randomCounter * 31 + it).toByte() }
    }

    /**
     * Strong-enough mixing so that ANY byte change in (key, nonce,
     * plaintext, associatedData) produces a different tag. SplitMix64-
     * style absorber + squeezer. Not cryptographic — fine for tests.
     */
    @OptIn(kotlin.ExperimentalUnsignedTypes::class)
    private fun computeTag(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        var rolling: ULong = 0x9E3779B97F4A7C15uL
        fun absorb(input: ByteArray) {
            for (b in input) {
                rolling = rolling xor (b.toUByte().toULong())
                rolling = rolling * 0x9E3779B97F4A7C15uL
                rolling = (rolling shr 27) xor rolling
            }
        }
        absorb(key); absorb(nonce); absorb(plaintext); absorb(associatedData)
        val out = ByteArray(16)
        for (i in 0 until 16) {
            rolling = rolling * 0x9E3779B97F4A7C15uL
            rolling = (rolling shr 13) xor rolling
            out[i] = (rolling and 0xFFuL).toByte()
        }
        return out
    }

    private val privToCounter = mutableMapOf<String, Int>()
    private val pubToCounter = mutableMapOf<String, Int>()
}
