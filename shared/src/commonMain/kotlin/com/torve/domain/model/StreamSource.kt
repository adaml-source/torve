package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class StreamQuality {
    REMUX_4K,
    UHD_4K,
    FHD_1080P,
    HD_720P,
    SD_480P,
    UNKNOWN;

    val label: String
        get() = when (this) {
            REMUX_4K -> "4K Remux"
            UHD_4K -> "4K"
            FHD_1080P -> "1080p"
            HD_720P -> "720p"
            SD_480P -> "480p"
            UNKNOWN -> "Any"
        }

    /** Lower rank = higher quality. UNKNOWN is treated as lowest. */
    val rank: Int
        get() = when (this) {
            REMUX_4K -> 0
            UHD_4K -> 1
            FHD_1080P -> 2
            HD_720P -> 3
            SD_480P -> 4
            UNKNOWN -> 5
        }

    /** Pixel height for this quality tier. UNKNOWN returns null. */
    val heightPx: Int?
        get() = when (this) {
            REMUX_4K -> 2160
            UHD_4K -> 2160
            FHD_1080P -> 1080
            HD_720P -> 720
            SD_480P -> 480
            UNKNOWN -> null
        }

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

        /** All selectable qualities (excluding UNKNOWN). */
        val selectable: List<StreamQuality>
            get() = listOf(REMUX_4K, UHD_4K, FHD_1080P, HD_720P, SD_480P)
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
