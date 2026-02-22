package com.streamvault.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class StreamQuality {
    REMUX_4K,
    UHD_4K,
    FHD_1080P,
    HD_720P,
    SD_480P,
    UNKNOWN;

    companion object {
        fun fromString(text: String): StreamQuality {
            val t = text.uppercase()
            return when {
                t.contains("REMUX") && (t.contains("2160") || t.contains("4K")) -> REMUX_4K
                t.contains("2160") || t.contains("4K") || t.contains("UHD") -> UHD_4K
                t.contains("1080") -> FHD_1080P
                t.contains("720") -> HD_720P
                t.contains("480") -> SD_480P
                else -> FHD_1080P
            }
        }
    }
}

@Serializable
data class StreamSource(
    val addonName: String,
    val title: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIndex: Int? = null,
    val quality: StreamQuality = StreamQuality.UNKNOWN,
    val size: Long? = null,
    val codec: String? = null,
    val audioCodec: String? = null,
    val seeds: Int? = null,
    val behaviorHints: Map<String, String> = emptyMap(),
    val debridService: String? = null,
    val isDebridCached: Boolean = false,
)
