package com.torve.data.debrid

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Common types ---

data class DebridResult(
    val success: Boolean,
    val user: DebridUser? = null,
    val error: String? = null,
)

data class DebridUser(
    val username: String,
    val email: String? = null,
    val premium: Boolean,
    val expiresAt: String? = null,
    val points: Int? = null,
)

data class DeviceCodeInfo(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val interval: Int,
    val expiresIn: Int,
)

data class DevicePollResult(
    val done: Boolean,
    val apiKey: String? = null,
    val oauthTokens: RdOAuthTokens? = null,
)

data class RdOAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val clientId: String,
    val clientSecret: String,
    val expiresAt: Long,
)

data class UnrestrictedFile(
    val id: String,
    val filename: String,
    val mimeType: String,
    val download: String,
    val streamable: Boolean,
)

// --- Real-Debrid API response models ---

@Serializable
data class RdUserResponse(
    val username: String = "",
    val email: String = "",
    val type: String = "",
    val expiration: String = "",
    val points: Int = 0,
)

@Serializable
data class RdDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_url") val verificationUrl: String = "",
    val interval: Int = 5,
    @SerialName("expires_in") val expiresIn: Int = 600,
)

@Serializable
data class RdCredentialsResponse(
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("client_secret") val clientSecret: String? = null,
)

@Serializable
data class RdTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Int = 3600,
    @SerialName("token_type") val tokenType: String = "",
)

@Serializable
data class RdAddMagnetResponse(
    val id: String = "",
    val uri: String = "",
)

@Serializable
data class RdErrorResponse(
    val error: String = "",
    @SerialName("error_code") val errorCode: Int? = null,
)

@Serializable
data class RdTorrentFile(
    val id: Int = 0,
    val path: String = "",
    val bytes: Long = 0,
    val selected: Int = 0,
)

@Serializable
data class RdTorrentInfoResponse(
    val id: String = "",
    val status: String = "",
    val links: List<String> = emptyList(),
    val filename: String = "",
    val files: List<RdTorrentFile> = emptyList(),
)

@Serializable
data class RdUnrestrictResponse(
    val id: String = "",
    val filename: String = "",
    val mimeType: String = "",
    val download: String = "",
    val streamable: Int = 0,
)

@Serializable
data class RdTranscodeResponse(
    val liveMP4: RdTranscodeQuality? = null,
    val apple: RdTranscodeQuality? = null,
    @SerialName("h264WebM")
    val h264WebM: RdTranscodeQuality? = null,
)

@Serializable
data class RdTranscodeQuality(
    val full: String? = null,
)

// --- AllDebrid API response models ---

@Serializable
data class AdResponse<T>(
    val status: String = "",
    val data: T? = null,
)

@Serializable
data class AdUserData(
    val user: AdUserInfo? = null,
)

@Serializable
data class AdUserInfo(
    val username: String = "",
    val email: String = "",
    val isPremium: Boolean = false,
    val premiumUntil: Long = 0,
)

@Serializable
data class AdPinGetData(
    val pin: String = "",
    val check: String = "",
    @SerialName("user_url") val userUrl: String = "",
    @SerialName("expires_in") val expiresIn: Int = 600,
)

@Serializable
data class AdPinCheckData(
    val activated: Boolean = false,
    val apikey: String? = null,
)

@Serializable
data class AdMagnetUploadData(
    val magnets: List<AdMagnetInfo> = emptyList(),
)

@Serializable
data class AdMagnetInfo(
    val id: Long = 0,
    val hash: String = "",
    val ready: Boolean = false,
)

@Serializable
data class AdMagnetStatusData(
    val magnets: AdMagnetStatusInfo? = null,
)

@Serializable
data class AdMagnetStatusInfo(
    val id: Long = 0,
    val status: String = "",
    val links: List<AdLinkInfo> = emptyList(),
)

@Serializable
data class AdLinkInfo(
    val link: String = "",
    val filename: String = "",
    val size: Long = 0,
)

@Serializable
data class AdUnlockData(
    val link: String = "",
    val filename: String = "",
    val size: Long = 0,
    val host: String = "",
    val streaming: List<String> = emptyList(),
)

// --- Premiumize API response models ---

@Serializable
data class PmAccountResponse(
    val status: String = "",
    @SerialName("customer_id") val customerId: Long? = null,
    @SerialName("premium_until") val premiumUntil: Long? = null,
    @SerialName("limit_used") val limitUsed: Int = 0,
    val message: String? = null,
)

@Serializable
data class PmDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_url") val verificationUrl: String? = null,
    val interval: Int = 5,
    @SerialName("expires_in") val expiresIn: Int = 600,
)

@Serializable
data class PmDeviceTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class PmCacheCheckResponse(
    val status: String = "",
    val response: List<Boolean> = emptyList(),
)

@Serializable
data class PmDirectDlResponse(
    val status: String = "",
    val content: List<PmDirectDlContent> = emptyList(),
)

@Serializable
data class PmDirectDlContent(
    val path: String = "",
    val size: Long = 0,
    val link: String = "",
    @SerialName("stream_link") val streamLink: String? = null,
    @SerialName("transcode_status") val transcodeStatus: String? = null,
)

// --- TorBox API response models ---

@Serializable
data class TbResponse<T>(
    val success: Boolean = false,
    val data: T? = null,
    val error: String? = null,
)

@Serializable
data class TbUserData(
    val email: String? = null,
    val plan: Int = 0,
    @SerialName("premium_expires_at") val premiumExpiresAt: String? = null,
)

@Serializable
data class TbTorrentData(
    val id: Long = 0,
    val hash: String = "",
    val name: String = "",
)

@Serializable
data class TbTorrentInfoData(
    val id: Long = 0,
    @SerialName("download_state") val downloadState: String = "",
    val files: List<TbFileInfo> = emptyList(),
)

@Serializable
data class TbFileInfo(
    val id: Long = 0,
    val name: String = "",
    val size: Long = 0,
)

@Serializable
data class TbDownloadLinkData(
    val data: String? = null,
)
