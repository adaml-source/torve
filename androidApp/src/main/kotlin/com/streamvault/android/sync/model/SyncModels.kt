package com.streamvault.android.sync.model

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
    @SerialName("installation_id")
    val installationId: String,
    @SerialName("device_name")
    val deviceName: String,
    @SerialName("device_type")
    val deviceType: String,
    val platform: String,
    @SerialName("last_seen_at")
    val lastSeenAt: String,
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
