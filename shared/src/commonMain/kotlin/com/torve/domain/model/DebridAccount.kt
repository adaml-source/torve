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
    /** Ordered ISO-639 language codes. The currently selected audio language may be prepended at runtime. */
    val preferredAudioLanguages: List<String> = emptyList(),
    val sourceLanguageMatchMode: SourceLanguageMatchMode = SourceLanguageMatchMode.PREFER,
    /** Minimum advertised source size normalized to one hour. Zero disables the floor. */
    val minSourceSizePerHourBytes: Long = 0L,
    val unknownSourceSizePolicy: UnknownSourceMetadataPolicy = UnknownSourceMetadataPolicy.ALLOW_WITH_PENALTY,
    val unknownSourceLanguagePolicy: UnknownSourceMetadataPolicy = UnknownSourceMetadataPolicy.ALLOW_WITH_PENALTY,
    val sourceFallbackPolicy: SourceFallbackPolicy = SourceFallbackPolicy.ASK,
    val nextEpisodeMode: NextEpisodeMode = NextEpisodeMode.AT_END,
    val nextEpisodePreparationMode: NextEpisodePreparationMode = NextEpisodePreparationMode.RESOLVE_ONLY,
    val nextEpisodePreloadBufferSeconds: Int = 30,
    val nextEpisodePreloadMaxBytes: Long = 128L * 1024L * 1024L,
    val nextEpisodePreloadWifiOnly: Boolean = true,
)

@Serializable
enum class SourceLanguageMatchMode(val label: String) {
    PREFER("Prefer selected languages"),
    REQUIRE("Require selected languages"),
}

@Serializable
enum class UnknownSourceMetadataPolicy(val label: String) {
    ALLOW_WITH_PENALTY("Allow, but rank lower"),
    REJECT("Do not auto-select"),
}

@Serializable
enum class SourceFallbackPolicy(val label: String) {
    ASK("Ask before relaxing rules"),
    DO_NOT_AUTOPLAY("Do not autoplay"),
    BEST_AVAILABLE("Use best available"),
}

@Serializable
enum class NextEpisodeMode(val label: String) {
    AT_END("At episode end"),
    AT_CREDITS("At credits"),
    OFF("Off"),
}

@Serializable
enum class NextEpisodePreparationMode(val label: String) {
    OFF("Off"),
    RESOLVE_ONLY("Resolve only"),
    RESOLVE_AND_BUFFER("Resolve and buffer"),
}

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
