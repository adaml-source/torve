package com.streamvault.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class DebridServiceType {
    REAL_DEBRID,
    ALL_DEBRID,
    PREMIUMIZE,
    TORBOX;

    val label: String
        get() = when (this) {
            REAL_DEBRID -> "Real-Debrid"
            ALL_DEBRID -> "AllDebrid"
            PREMIUMIZE -> "Premiumize"
            TORBOX -> "TorBox"
        }
}

@Serializable
data class DebridAccount(
    val service: DebridServiceType,
    val username: String = "",
    val email: String = "",
    val premiumUntil: String? = null,
    val isActive: Boolean = false,
)

@Serializable
data class ResolvedStream(
    val url: String,
    val service: DebridServiceType? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val mimeType: String? = null,
    val transcodeUrls: TranscodeUrls? = null,
)

@Serializable
data class TranscodeUrls(
    val mp4: String? = null,
    val hls: String? = null,
    val webm: String? = null,
)

@Serializable
data class StreamPreferences(
    val preferredQuality: StreamQuality = StreamQuality.FHD_1080P,
    val hdrEnabled: Boolean = false,
    val dvEnabled: Boolean = false,
    val cachedOnly: Boolean = true,
    val minQuality: StreamQuality = StreamQuality.SD_480P,
)
