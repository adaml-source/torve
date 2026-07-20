package com.torve.domain.transfer

import kotlinx.serialization.json.Json

/**
 * High-level protocol layer. Pure Kotlin: depends only on
 * [TransferCryptoEngine] for primitives and [Json] for payload codec.
 *
 * Sender flow:
 *   1. Receiver scans QR → caller has receiver's public key.
 *   2. Caller calls [seal] with receiver's pubkey, the secrets to send,
 *      sender device id/name, and an absolute expiry.
 *   3. Caller serializes the returned envelope to JSON, posts to relay.
 *   4. Caller MUST forget every byte of the returned key material —
 *      this protocol holds nothing on the sender's behalf.
 *
 * Receiver flow:
 *   1. Caller calls [generateReceiverKeyPair] up front; embeds the
 *      public key in the QR shown on screen.
 *   2. Caller polls the relay for an envelope.
 *   3. Caller calls [open] with the envelope and the receiver's private
 *      key; switches on [TransferDecryptResult].
 *   4. Caller maintains the consumed-nonce set out-of-band (e.g. a
 *      persistent table) — the protocol layer reports replay only when
 *      the caller-supplied predicate marks the nonce as already-seen.
 */
class SecretsTransferProtocol(
    private val engine: TransferCryptoEngine,
    /** Source of "now" — injectable so tests can drive expiry. */
    private val nowMs: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
) {
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Convenience for the receiver: fresh ephemeral key pair to embed in a QR. */
    suspend fun generateReceiverKeyPair(): EphemeralKeyPair = engine.generateEphemeralKeyPair()

    /**
     * Encrypt and seal a payload to a receiver's public key.
     *
     * Implementation:
     *   - Generate an ephemeral sender X25519 key pair.
     *   - ECDH(senderPriv, receiverPub) → HKDF → 32-byte AES key.
     *   - Random 12-byte AES-GCM nonce.
     *   - associatedData = `version || senderEphemeralPublicKey || aeadNonce || expiresAtEpochMs`
     *     so any tamper of the public-clear envelope fields fails AEAD verify on the receiver.
     *   - Caller is responsible for zeroing the returned envelope from
     *     memory after transmission; the engine returns the sealed
     *     blob and discards its private-key copy via no-op (Kotlin
     *     can't actually wipe heap bytes).
     */
    suspend fun seal(
        receiverPublicKey: ByteArray,
        senderDeviceId: String,
        senderDeviceName: String?,
        categories: List<SecretCategory>,
        secrets: List<SecretRecord>,
        expiresAtEpochMs: Long,
        /**
         * Non-secret companion config travelling with this transfer.
         * Defaults to empty for callers that don't need it. Receivers
         * gate every key through [ConfigKeyAllowlist].
         */
        configEntries: List<ConfigEntry> = emptyList(),
        /**
         * IPTV/M3U/Xtream playlists travelling alongside the secret bag.
         * Each entry contains the credentials directly — they only ever
         * appear inside the AES-GCM ciphertext.
         */
        playlists: List<TransferPlaylistDto> = emptyList(),
    ): SealedSecretsEnvelope {
        require(senderDeviceId.isNotBlank() && senderDeviceId.length <= 128) {
            "senderDeviceId required, ≤ 128 chars"
        }
        require(secrets.isNotEmpty() || configEntries.isNotEmpty() || playlists.isNotEmpty()) {
            "at least one secret, config entry, or playlist required"
        }
        require(expiresAtEpochMs > nowMs()) { "envelope already expired" }

        val senderKeyPair = engine.generateEphemeralKeyPair()
        val sharedKey = engine.deriveSharedKey(
            privateKey = senderKeyPair.privateKey,
            peerPublicKey = receiverPublicKey,
            info = SHARED_KEY_INFO.encodeToByteArray(),
        )
        val aeadNonce = engine.secureRandom(AES_GCM_NONCE_BYTES)
        val transferNonce = Base64Url.encode(engine.secureRandom(TRANSFER_NONCE_BYTES))

        val payload = SecretsTransferPayload(
            senderDeviceName = senderDeviceName,
            createdAtEpochMs = nowMs(),
            expiresAtEpochMs = expiresAtEpochMs,
            transferNonce = transferNonce,
            categories = categories,
            secrets = secrets,
            configEntries = configEntries,
            playlists = playlists,
        )
        val plaintext = json.encodeToString(SecretsTransferPayload.serializer(), payload)
            .encodeToByteArray()

        val senderPubB64 = Base64Url.encode(senderKeyPair.publicKey)
        val nonceB64 = Base64Url.encode(aeadNonce)
        val aad = aadFor(
            version = PROTOCOL_VERSION,
            senderPubB64 = senderPubB64,
            nonceB64 = nonceB64,
            expiresAtEpochMs = expiresAtEpochMs,
        )
        val ciphertext = engine.encryptAead(
            key = sharedKey,
            nonce = aeadNonce,
            plaintext = plaintext,
            associatedData = aad,
        )

        return SealedSecretsEnvelope(
            version = PROTOCOL_VERSION,
            senderEphemeralPublicKey = senderPubB64,
            aeadNonce = nonceB64,
            ciphertext = Base64Url.encode(ciphertext),
            expiresAtEpochMs = expiresAtEpochMs,
            senderDeviceId = senderDeviceId,
        )
    }

    /**
     * Open a sealed envelope. Returns one of [TransferDecryptResult].
     *
     * `consumedNonceCheck` is the caller's persistent replay guard:
     * given a candidate nonce, return true if it's already been
     * consumed. The protocol calls this BEFORE returning Success and
     * mutates nothing; the caller is responsible for recording the
     * nonce after applying the secrets. (This separation lets the
     * caller keep the nonce-set in a transactional store.)
     */
    suspend fun open(
        envelope: SealedSecretsEnvelope,
        receiverPrivateKey: ByteArray,
        consumedNonceCheck: suspend (String) -> Boolean = { false },
    ): TransferDecryptResult {
        if (envelope.version != PROTOCOL_VERSION) {
            return TransferDecryptResult.UnsupportedVersion(envelope.version)
        }
        if (envelope.expiresAtEpochMs < nowMs()) {
            return TransferDecryptResult.Expired
        }
        val senderPub = Base64Url.decodeOrNull(envelope.senderEphemeralPublicKey)
            ?: return TransferDecryptResult.Malformed("senderEphemeralPublicKey not base64url")
        val nonce = Base64Url.decodeOrNull(envelope.aeadNonce)
            ?: return TransferDecryptResult.Malformed("aeadNonce not base64url")
        val ciphertext = Base64Url.decodeOrNull(envelope.ciphertext)
            ?: return TransferDecryptResult.Malformed("ciphertext not base64url")
        if (senderPub.size != X25519_PUBLIC_KEY_BYTES) {
            return TransferDecryptResult.Malformed("sender pubkey wrong size: ${senderPub.size}")
        }
        if (nonce.size != AES_GCM_NONCE_BYTES) {
            return TransferDecryptResult.Malformed("aeadNonce wrong size: ${nonce.size}")
        }

        val sharedKey = runCatching {
            engine.deriveSharedKey(
                privateKey = receiverPrivateKey,
                peerPublicKey = senderPub,
                info = SHARED_KEY_INFO.encodeToByteArray(),
            )
        }.getOrElse {
            return TransferDecryptResult.AuthenticationFailure
        }

        val aad = aadFor(
            version = envelope.version,
            senderPubB64 = envelope.senderEphemeralPublicKey,
            nonceB64 = envelope.aeadNonce,
            expiresAtEpochMs = envelope.expiresAtEpochMs,
        )
        val plaintextBytes = engine.decryptAead(
            key = sharedKey,
            nonce = nonce,
            ciphertext = ciphertext,
            associatedData = aad,
        ) ?: return TransferDecryptResult.AuthenticationFailure

        val payload = runCatching {
            json.decodeFromString(SecretsTransferPayload.serializer(), plaintextBytes.decodeToString())
        }.getOrElse { t ->
            return TransferDecryptResult.Malformed(t.message ?: "payload decode failed")
        }
        if (payload.version != PROTOCOL_VERSION) {
            return TransferDecryptResult.UnsupportedVersion(payload.version)
        }
        if (payload.expiresAtEpochMs != envelope.expiresAtEpochMs) {
            return TransferDecryptResult.EnvelopePayloadMismatch
        }
        if (consumedNonceCheck(payload.transferNonce)) {
            return TransferDecryptResult.Replayed
        }
        return TransferDecryptResult.Success(payload)
    }

    private fun aadFor(
        version: Int,
        senderPubB64: String,
        nonceB64: String,
        expiresAtEpochMs: Long,
    ): ByteArray = "v=$version|spk=$senderPubB64|nonce=$nonceB64|exp=$expiresAtEpochMs"
        .encodeToByteArray()

    companion object {
        const val AES_GCM_NONCE_BYTES = 12
        const val X25519_PUBLIC_KEY_BYTES = 32
        const val TRANSFER_NONCE_BYTES = 16
    }
}
