package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class DebridServiceType {
    REAL_DEBRID,
    ALL_DEBRID,
    PREMIUMIZE,
    TORBOX;

    val label: String
        get() = when (this) {
            REAL_DEBRID -> "Real Debrid"
            ALL_DEBRID -> "All Debrid"
            PREMIUMIZE -> "Premiumize Me"
            TORBOX -> "Torbox"
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
    val isTemporary: Boolean = false,
    val isDirect: Boolean = true,
    val supportsRange: Boolean = true,
    val streamId: String? = null,
    val expiresInSeconds: Int? = null,
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
    val maxQuality: StreamQuality = StreamQuality.REMUX_4K,
    val hdrEnabled: Boolean = false,
    val dvEnabled: Boolean = false,
    val cachedOnly: Boolean = true,
    val minQuality: StreamQuality = StreamQuality.SD_480P,
    val maxFileSizeBytes: Long? = null,
    val autoPlayEnabled: Boolean = true,
    val codecPreference: CodecPreference = CodecPreference.HEVC_PREFERRED,
    val hdrMode: HdrMode = HdrMode.AUTO,
    val maxFallbackAttempts: Int = 3,
    val autoPlayNextEpisodeEnabled: Boolean = true,
    val autoSourceMode: AutoSourceMode = AutoSourceMode.BALANCED,
    val allow4kAuto: Boolean = false,
    val preferCompatibleCodecs: Boolean = true,
)

@Serializable
enum class AutoSourceMode(val label: String) {
    BALANCED("Auto (Balanced)"),
    STABILITY_FIRST("Stability First"),
    QUALITY_FIRST("Quality First"),
    MAX_1080P("Max 1080p"),
    MAX_720P("Max 720p"),
}

@Serializable
enum class CodecPreference(val label: String) {
    HEVC_PREFERRED("HEVC Preferred"),
    H264_ONLY("H.264 Only"),
    ANY("Any"),
}

@Serializable
enum class HdrMode(val label: String) {
    PREFER_HDR("Prefer HDR"),
    SDR_ONLY("SDR Only"),
    AUTO("Auto (match display)"),
}
