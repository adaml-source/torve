package com.torve.android.sync.lan

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets as Charsets_
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives for secure device-to-device setup transfer.
 *
 * ## Bootstrap (ECDH key agreement during pairing)
 * During pairing, each device generates an ephemeral ECDH key pair (P-256).
 * Only public keys are exchanged over the LAN. Both sides compute the same
 * ECDH shared secret, then derive the pairing transfer key using:
 *
 *   pairingSecret = SHA-256(ecdhSharedSecret || pairingCode || senderInstallationId || targetInstallationId || "torve-pairing-v3")
 *
 * The pairing code is included in the KDF to bind the exchange to the
 * human-verified authorization code displayed on the TV. An attacker who
 * observes only the public keys on the LAN cannot derive the final secret
 * without knowing the pairing code.
 *
 * ## Transfer (AES-256-GCM)
 * The pairing secret is used to derive per-transfer AES-256 keys.
 * Payloads are encrypted with AES-256-GCM, with AAD binding the ciphertext
 * to the sender, target, pairing, and timestamp.
 */
object SecureTransferCrypto {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_NONCE_LENGTH = 12
    private const val GCM_TAG_BIT_LENGTH = 128
    private const val DOMAIN_SEPARATOR = "torve-setup-transfer-v1"
    private const val PAIRING_KDF_LABEL = "torve-pairing-v3"
    private const val CHANNEL_BOOTSTRAP_LABEL = "torve-channel-bootstrap-v1"
    private const val EC_CURVE = "secp256r1"

    // ── ECDH Key Agreement ──

    /**
     * Generate an ephemeral ECDH key pair (P-256 / secp256r1).
     * Returns (publicKeyBase64, privateKeyBase64).
     */
    fun generateEphemeralKeyPair(): Pair<String, String> {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec(EC_CURVE))
        val keyPair = keyGen.generateKeyPair()
        val pubBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val privBase64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
        return pubBase64 to privBase64
    }

    /**
     * Perform ECDH key agreement and derive the per-pairing transfer secret.
     *
     * @param myPrivateKeyBase64 This device's ephemeral private key
     * @param peerPublicKeyBase64 The peer device's ephemeral public key
     * @param pairingCode The 6-character code displayed on the TV (binds to human authorization)
     * @param senderInstallationId The claimer's (phone's) installation ID
     * @param targetInstallationId The code-holder's (TV's) installation ID
     * @return Base64-encoded 256-bit pairing transfer secret
     */
    fun deriveSharedSecret(
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String,
        pairingCode: String,
        senderInstallationId: String,
        targetInstallationId: String,
    ): String {
        // Decode keys
        val privKeyBytes = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)
        val pubKeyBytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)

        val keyFactory = KeyFactory.getInstance("EC")
        val privateKey = keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privKeyBytes))
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(pubKeyBytes))

        // ECDH agreement
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        val ecdhSecret = agreement.generateSecret()

        // KDF: SHA-256(ecdhSharedSecret || pairingCode || senderInstallationId || targetInstallationId || label)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ecdhSecret)
        digest.update(pairingCode.uppercase().toByteArray(StandardCharsets.UTF_8))
        digest.update(senderInstallationId.toByteArray(StandardCharsets.UTF_8))
        digest.update(targetInstallationId.toByteArray(StandardCharsets.UTF_8))
        digest.update(PAIRING_KDF_LABEL.toByteArray(StandardCharsets.UTF_8))
        val derived = digest.digest()

        return Base64.encodeToString(derived, Base64.NO_WRAP)
    }

    // ── Mutual Key Confirmation ──

    /**
     * Build a canonical transcript string for HMAC confirmation.
     * Both sides must produce exactly the same transcript.
     */
    fun buildTranscript(
        pairingCode: String,
        phoneInstallationId: String,
        tvInstallationId: String,
        phonePubKeyBase64: String,
        tvPubKeyBase64: String,
    ): String {
        // Canonical format: fields joined with pipes, code uppercased
        return "v3|${pairingCode.uppercase()}|$phoneInstallationId|$tvInstallationId|$phonePubKeyBase64|$tvPubKeyBase64"
    }

    /**
     * Compute a confirmation HMAC over the transcript using the derived secret.
     * @param role "tv-confirm" or "phone-confirm" — directional label prevents reflection attacks
     */
    fun computeConfirmation(
        derivedSecretBase64: String,
        role: String,
        transcript: String,
    ): String {
        val keyBytes = Base64.decode(derivedSecretBase64, Base64.NO_WRAP)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        mac.update(role.toByteArray(StandardCharsets.UTF_8))
        mac.update(transcript.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(mac.doFinal(), Base64.NO_WRAP)
    }

    /**
     * Verify a confirmation HMAC. Returns true if the HMAC matches.
     */
    fun verifyConfirmation(
        derivedSecretBase64: String,
        role: String,
        transcript: String,
        expectedMac: String,
    ): Boolean {
        val computed = computeConfirmation(derivedSecretBase64, role, transcript)
        return MessageDigest.isEqual(
            computed.toByteArray(StandardCharsets.UTF_8),
            expectedMac.toByteArray(StandardCharsets.UTF_8),
        )
    }

    // ── Transfer Encryption ──

    /**
     * Derive a per-transfer AES-256 key from the pairing shared secret
     * and the identities of both endpoints.
     */
    fun deriveKey(
        pairingSecret: String,
        senderInstallationId: String,
        targetInstallationId: String,
    ): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(pairingSecret.toByteArray(StandardCharsets.UTF_8))
        digest.update(senderInstallationId.toByteArray(StandardCharsets.UTF_8))
        digest.update(targetInstallationId.toByteArray(StandardCharsets.UTF_8))
        digest.update(DOMAIN_SEPARATOR.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(digest.digest(), "AES")
    }

    fun encrypt(plaintext: String, key: SecretKeySpec, aad: String): String {
        val nonce = ByteArray(GCM_NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BIT_LENGTH, nonce))
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        val output = nonce + ciphertext
        return Base64.encodeToString(output, Base64.NO_WRAP)
    }

    fun decrypt(encrypted: String, key: SecretKeySpec, aad: String): String? {
        return try {
            val data = Base64.decode(encrypted, Base64.NO_WRAP)
            if (data.size <= GCM_NONCE_LENGTH) return null

            val nonce = data.copyOfRange(0, GCM_NONCE_LENGTH)
            val ciphertext = data.copyOfRange(GCM_NONCE_LENGTH, data.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BIT_LENGTH, nonce))
            cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun buildAad(
        pairingId: String,
        senderInstallationId: String,
        targetInstallationId: String,
        issuedAt: Long,
    ): String {
        return "pairing:$pairingId|sender:$senderInstallationId|target:$targetInstallationId|issued:$issuedAt"
    }

    /**
     * Derive a shared secret for channel bootstrap of an already-paired device.
     * Uses the backend pairing ID as channel binding instead of a human-entered code.
     */
    fun deriveChannelBootstrapSecret(
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String,
        backendPairingId: String,
        initiatorInstallationId: String,
        responderInstallationId: String,
    ): String {
        val myPrivKey = KeyFactory.getInstance("EC")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)))
        val peerPubKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(myPrivKey)
        agreement.doPhase(peerPubKey, true)
        val ecdhSecret = agreement.generateSecret()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ecdhSecret)
        digest.update(backendPairingId.toByteArray(Charsets.UTF_8))
        digest.update(initiatorInstallationId.toByteArray(Charsets.UTF_8))
        digest.update(responderInstallationId.toByteArray(Charsets.UTF_8))
        digest.update(CHANNEL_BOOTSTRAP_LABEL.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    /**
     * Build canonical transcript for channel bootstrap mutual confirmation.
     */
    fun buildChannelBootstrapTranscript(
        backendPairingId: String,
        initiatorInstallationId: String,
        responderInstallationId: String,
        initiatorPubKeyBase64: String,
        responderPubKeyBase64: String,
    ): String = "channel-bootstrap-v1|$backendPairingId|$initiatorInstallationId|$responderInstallationId|$initiatorPubKeyBase64|$responderPubKeyBase64"

    /** @deprecated Use ECDH key agreement instead. Kept only for reference. */
    fun generatePairingSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
