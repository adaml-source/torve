package com.torve.domain.transfer

import kotlinx.serialization.Serializable

/**
 * Phase 3 Slice B — encrypted credential transfer.
 *
 * Types are intentionally shape-only; the cryptographic engine that
 * actually generates X25519 key pairs and runs AEAD lives behind
 * [TransferCryptoEngine] so the protocol layer is pure-Kotlin and
 * cross-platform.
 *
 * See `docs/credential-transfer-protocol.md` for the wire contract.
 */

/** Categories of secret a user can choose to include in a transfer. */
@Serializable
enum class SecretCategory {
    DEBRID,
    IPTV,
    PLEX_JELLYFIN,
    TRAKT_SIMKL,
    AI_KEYS,
    PANDA,
}

/** One key/value record from the sender's IntegrationSecretStore. */
@Serializable
data class SecretRecord(
    val category: SecretCategory,
    /**
     * Stable enum name from
     * [com.torve.domain.integrations.IntegrationSecretKey]. The receiver
     * maps the same name back into its own store; unknown names are
     * rejected silently.
     */
    val key: String,
    val value: String,
    /**
     * Optional sub-key for keys that index by provider/url
     * (e.g. PANDA_INDEXER_API_KEY uses `<type>|<url>`). Null for
     * single-value keys.
     */
    val subKey: String? = null,
)

/**
 * Non-secret companion config record — e.g. a Plex/Jellyfin server URL
 * that an imported access token needs in order to be usable.
 *
 * The [key] is the exact `PreferencesRepository` key the receiver will
 * write to. Receivers reject any key not on their
 * [com.torve.domain.transfer.ConfigKeyAllowlist] — no arbitrary preference
 * writes from a paste.
 */
@Serializable
data class ConfigEntry(
    val category: SecretCategory,
    val key: String,
    val value: String,
)

/**
 * One IPTV / Xtream / M3U playlist with its credentials, packed inside a
 * transfer envelope. Lives outside [SecretRecord] because IPTV
 * credentials are stored per-row in SQLite (not as enum-keyed singletons
 * in [com.torve.domain.integrations.IntegrationSecretStore]) — the
 * shared catalog can't enumerate them, so we ship the playlist rows
 * themselves alongside the secret bag.
 *
 * `password` is plaintext but only ever appears inside the AES-GCM
 * ciphertext — same protection as every other secret in this protocol.
 */
@Serializable
data class TransferPlaylistDto(
    val playlistId: String,
    val name: String,
    /** "m3u" or "xtream" — matches [com.torve.domain.model.PlaylistType.wireValue]. */
    val playlistType: String,
    /** M3U source URL (only when playlistType == "m3u"). */
    val url: String? = null,
    /** Optional EPG companion URL for M3U playlists. */
    val epgUrl: String? = null,
    /** Xtream server base URL (only when playlistType == "xtream"). */
    val server: String? = null,
    /** Xtream username (only when playlistType == "xtream"). */
    val username: String? = null,
    /** Xtream password — plaintext inside the sealed envelope. */
    val password: String? = null,
)

/** Plaintext payload — what's inside [SealedSecretsEnvelope.ciphertext]. */
@Serializable
data class SecretsTransferPayload(
    val version: Int = PROTOCOL_VERSION,
    val senderDeviceName: String? = null,
    val createdAtEpochMs: Long,
    /** MUST equal [SealedSecretsEnvelope.expiresAtEpochMs] — checked by receiver. */
    val expiresAtEpochMs: Long,
    /** One-time-use guard. Receiver maintains a seen-set to reject replays. */
    val transferNonce: String,
    val categories: List<SecretCategory>,
    val secrets: List<SecretRecord>,
    /**
     * Non-secret companion config travelling alongside [secrets].
     * Backwards compatible — pre-companion senders emit no entries.
     * Receivers gate every key through [ConfigKeyAllowlist].
     */
    val configEntries: List<ConfigEntry> = emptyList(),
    /**
     * IPTV/M3U/Xtream playlists travelling alongside [secrets].
     * Backwards compatible — pre-IPTV-aware senders emit no entries
     * and receivers will simply not import any playlists.
     */
    val playlists: List<TransferPlaylistDto> = emptyList(),
) {
    init {
        require(transferNonce.isNotBlank()) { "transferNonce required" }
        require(secrets.all { it.key.isNotBlank() }) { "secret keys must be non-blank" }
        require(configEntries.all { it.key.isNotBlank() }) { "config keys must be non-blank" }
        require(playlists.all { it.playlistId.isNotBlank() && it.name.isNotBlank() }) {
            "playlist id and name must be non-blank"
        }
    }
}

/** Wire format. JSON-encoded by the relay; binary fields are base64url. */
@Serializable
data class SealedSecretsEnvelope(
    val version: Int = PROTOCOL_VERSION,
    /** Base64url, 32 bytes — sender's ephemeral X25519 public key. */
    val senderEphemeralPublicKey: String,
    /** Base64url, 12 bytes — random AES-GCM nonce. */
    val aeadNonce: String,
    /** Base64url, ≥ 16 bytes — AES-GCM ciphertext + 16-byte auth tag. */
    val ciphertext: String,
    /** Visible in the clear so the relay can enforce TTL without decrypting. */
    val expiresAtEpochMs: Long,
    /** Opaque sender-device id. Never a hostname; ≤ 128 bytes. */
    val senderDeviceId: String,
)

/** Public key to embed in the QR Device B advertises to the sender. */
@Serializable
data class TransferReceiverHandshake(
    val sessionId: String,
    /** Base64url, 32 bytes — receiver's ephemeral X25519 public key. */
    val receiverEphemeralPublicKey: String,
    /** Base64url, encoded ahead of envelope creation — a sender hint. */
    val expiresAtEpochMs: Long,
    /**
     * Backend-assigned session id. Present iff the receiver successfully
     * registered with the relay. When present, the sender posts the
     * sealed envelope to `POST /api/v1/transfer/sessions/<id>/envelope`
     * instead of (or in addition to) emitting it for manual paste.
     * Backwards-compatible additive field — older parsers ignore it.
     */
    val relaySessionId: String? = null,
)

/** Successful key generation, captured momentarily by sender or receiver. */
data class EphemeralKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
)

/** Result of [SecretsTransferProtocol.open]. */
sealed interface TransferDecryptResult {
    data class Success(val payload: SecretsTransferPayload) : TransferDecryptResult
    /** Envelope's expiresAtEpochMs is in the past. */
    data object Expired : TransferDecryptResult
    /** AEAD authentication failed (key mismatch or tampering). */
    data object AuthenticationFailure : TransferDecryptResult
    /** Envelope or decrypted payload version is not supported. */
    data class UnsupportedVersion(val seenVersion: Int) : TransferDecryptResult
    /** Envelope nonce was already consumed by this receiver. */
    data object Replayed : TransferDecryptResult
    /** Envelope.expiresAtEpochMs ≠ payload.expiresAtEpochMs. Drop. */
    data object EnvelopePayloadMismatch : TransferDecryptResult
    /** Generic decode failure (malformed JSON, wrong shape). */
    data class Malformed(val reason: String) : TransferDecryptResult
}

const val PROTOCOL_VERSION: Int = 1

/** HKDF info string. Bumped if/when the wire format changes. */
const val SHARED_KEY_INFO: String = "torve-secrets-transfer-v1"
