package com.torve.android.sync.lan

import com.torve.android.sync.model.SyncDeviceDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LanHelloResponse(
    val status: String = "ok",
    val device: SyncDeviceDto,
)

@Serializable
data class LanPairClaimRequest(
    val code: String,
    @SerialName("source_device")
    val sourceDevice: SyncDeviceDto,
    /** Ephemeral ECDH public key (Base64-encoded X.509/SubjectPublicKeyInfo) for key agreement. */
    @SerialName("ecdh_public_key")
    val ecdhPublicKey: String? = null,
)

@Serializable
data class LanPairClaimResponse(
    val status: String,
    val device: SyncDeviceDto? = null,
    val message: String? = null,
    /** Ephemeral ECDH public key (Base64-encoded X.509/SubjectPublicKeyInfo) for key agreement. */
    @SerialName("ecdh_public_key")
    val ecdhPublicKey: String? = null,
    /** HMAC-SHA256 confirmation: proves TV derived the correct shared secret. */
    @SerialName("tv_confirm")
    val tvConfirm: String? = null,
)

/** Phone's confirmation MAC, sent after verifying TV's confirmation. */
@Serializable
data class LanPairConfirmRequest(
    @SerialName("phone_confirm")
    val phoneConfirm: String,
    @SerialName("phone_installation_id")
    val phoneInstallationId: String,
)

@Serializable
data class LanEventEnvelope(
    @SerialName("event_id")
    val eventId: String,
    @SerialName("event_type")
    val eventType: String,
    @SerialName("source_device_id")
    val sourceDeviceId: String,
    @SerialName("target_device_id")
    val targetDeviceId: String,
    val payload: JsonElement,
)

@Serializable
data class LanStatusResponse(
    val status: String = "ok",
    val message: String? = null,
)

/**
 * Secure envelope for setup transfer. Replaces plaintext SETTINGS_PUSH for secret payloads.
 * The ciphertext is AES-256-GCM encrypted with a key derived from the pairing shared secret.
 * AAD binds the ciphertext to the specific sender, target, pairing, and timestamp.
 */
@Serializable
data class SecureTransferEnvelope(
    val version: Int = 1,
    @SerialName("pairing_id")
    val pairingId: String,
    @SerialName("sender_installation_id")
    val senderInstallationId: String,
    @SerialName("target_installation_id")
    val targetInstallationId: String,
    @SerialName("issued_at")
    val issuedAt: Long,
    @SerialName("expires_at")
    val expiresAt: Long,
    val nonce: String,
    val ciphertext: String,
)

@Serializable
data class LanChannelInitiateRequest(
    @SerialName("phone_installation_id") val phoneInstallationId: String,
    @SerialName("ecdh_public_key") val ecdhPublicKey: String,
    @SerialName("backend_pairing_id") val backendPairingId: String,
)

@Serializable
data class LanChannelInitiateResponse(
    val status: String,
    @SerialName("ecdh_public_key") val ecdhPublicKey: String? = null,
    @SerialName("tv_confirm") val tvConfirm: String? = null,
    @SerialName("tv_installation_id") val tvInstallationId: String? = null,
    val message: String? = null,
)

@Serializable
data class LanChannelConfirmRequest(
    @SerialName("phone_confirm") val phoneConfirm: String,
    @SerialName("phone_installation_id") val phoneInstallationId: String,
)

data class LanResolvedService(
    val serviceName: String,
    val host: String,
    val port: Int,
)
