package com.torve.android.sync.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncDeviceRegistration(
    @SerialName("installation_id")
    val installationId: String,
    @SerialName("device_name")
    val deviceName: String,
    @SerialName("device_type")
    val deviceType: String,
    val platform: String,
)

@Serializable
data class SyncRegisterRequest(
    val email: String,
    val password: String,
    val device: SyncDeviceRegistration,
)

@Serializable
data class SyncLoginRequest(
    val email: String,
    val password: String,
    val device: SyncDeviceRegistration,
)

@Serializable
data class SyncRefreshRequest(
    @SerialName("refresh_token")
    val refreshToken: String,
)

@Serializable
data class SyncLogoutRequest(
    @SerialName("refresh_token")
    val refreshToken: String? = null,
)

@Serializable
data class SyncUserDto(
    val id: String,
    val email: String,
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
data class SyncDeviceDto(
    val id: String,
    @SerialName("pairing_id")
    val pairingId: String? = null,
    @SerialName("installation_id")
    val installationId: String,
    @SerialName("device_name")
    val deviceName: String,
    @SerialName("device_type")
    val deviceType: String,
    val platform: String,
    @SerialName("last_seen_at")
    val lastSeenAt: String,
    @SerialName("pairing_state")
    val pairingState: String = "paired",
    @SerialName("revoked_at")
    val revokedAt: String? = null,
)

@Serializable
data class SyncTokensDto(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("token_type")
    val tokenType: String,
    @SerialName("expires_in")
    val expiresIn: Int,
)

@Serializable
data class SyncAuthResponse(
    val user: SyncUserDto,
    val device: SyncDeviceDto,
    val tokens: SyncTokensDto,
)

@Serializable
data class SyncPairingCodeRequest(
    @SerialName("installation_id")
    val installationId: String,
    @SerialName("device_name")
    val deviceName: String,
    @SerialName("device_type")
    val deviceType: String,
    val platform: String,
)

@Serializable
data class SyncPairingCodeResponse(
    val code: String,
    @SerialName("expires_at")
    val expiresAt: String,
)

@Serializable
data class SyncPairingClaimRequest(
    val code: String,
)

@Serializable
data class SyncPairingClaimResponse(
    val status: String,
    val device: SyncDeviceDto,
)

@Serializable
data class SyncPairingStatusRequest(
    val code: String,
    @SerialName("installation_id")
    val installationId: String,
)

@Serializable
data class SyncPairingStatusResponse(
    val status: String,
    @SerialName("paired_device")
    val pairedDevice: SyncDeviceDto? = null,
    val user: SyncUserDto? = null,
    val tokens: SyncTokensDto? = null,
)

@Serializable
data class SyncStatusMessage(
    val status: String = "ok",
)

@Serializable
data class SyncSearchPushPayload(
    val query: String,
    val filters: Map<String, String> = emptyMap(),
    @SerialName("issued_by_device_id")
    val issuedByDeviceId: String? = null,
)

@Serializable
data class SyncSearchPushRequest(
    @SerialName("target_device_id")
    val targetDeviceId: String,
    val payload: SyncSearchPushPayload,
)

@Serializable
data class SyncPlaybackIntentPayload(
    @SerialName("content_id")
    val contentId: String,
    @SerialName("provider_target")
    val providerTarget: String,
    @SerialName("position_ms")
    val positionMs: Long,
    @SerialName("media_type")
    val mediaType: String? = null,
    val audio: String? = null,
    val subtitles: String? = null,
    @SerialName("issued_by_device_id")
    val issuedByDeviceId: String? = null,
)

@Serializable
data class SyncPlaybackIntentRequest(
    @SerialName("target_device_id")
    val targetDeviceId: String,
    val payload: SyncPlaybackIntentPayload,
)

@Serializable
data class SyncEventDispatchResponse(
    val status: String,
    @SerialName("event_id")
    val eventId: String,
    @SerialName("target_device_id")
    val targetDeviceId: String,
    @SerialName("event_type")
    val eventType: String,
)

@Serializable
data class SyncWatchStateReportRequest(
    @SerialName("content_id")
    val contentId: String,
    val provider: String,
    @SerialName("position_ms")
    val positionMs: Long,
)

@Serializable
data class SyncWatchStateReportResponse(
    val status: String,
    @SerialName("reported_at")
    val reportedAt: String,
)

@Serializable
data class SyncWatchStateLatestResponse(
    @SerialName("content_id")
    val contentId: String,
    val provider: String,
    @SerialName("position_ms")
    val positionMs: Long,
    @SerialName("reported_at")
    val reportedAt: String,
    @SerialName("device_id")
    val deviceId: String,
)

@Serializable
data class SyncSettingsPushPayload(
    val categories: List<String> = emptyList(),
    @SerialName("payload_json")
    val payloadJson: String,
    @SerialName("issued_by_device_id")
    val issuedByDeviceId: String? = null,
)

sealed class SyncInboundEvent {
    data class SearchPush(
        val query: String,
        val filters: Map<String, String> = emptyMap(),
        val issuedByDeviceId: String? = null,
    ) : SyncInboundEvent()

    data class PlaybackIntent(
        val contentId: String,
        val providerTarget: String,
        val positionMs: Long,
        val mediaType: String? = null,
        val audio: String? = null,
        val subtitles: String? = null,
        val issuedByDeviceId: String? = null,
    ) : SyncInboundEvent()

    data class SettingsPush(
        val categories: List<String>,
        val payloadJson: String,
        val issuedByDeviceId: String? = null,
    ) : SyncInboundEvent()
}

/** Secure-channel state for a paired device. */
enum class SecureChannelState {
    NOT_PAIRED,
    PAIRED_BACKEND_ONLY,
    PAIRED_LAN_UNREACHABLE,
    PAIRED_LAN_REACHABLE_NO_SECRET,
    PAIRED_SECURE_CHANNEL_READY,
    PAIRED_REVOKED,
}

/** Role of a paired device. */
enum class PairingRole {
    SETUP_OWNER,
    CONTROLLER,
}
